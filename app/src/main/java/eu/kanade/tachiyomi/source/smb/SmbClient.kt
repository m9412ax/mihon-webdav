package eu.kanade.tachiyomi.source.smb

import com.hierynomus.msdtyp.AccessMask
import com.hierynomus.msfscc.FileAttributes
import com.hierynomus.msfscc.fileinformation.FileIdBothDirectoryInformation
import com.hierynomus.mssmb2.SMB2CreateDisposition
import com.hierynomus.mssmb2.SMB2ShareAccess
import com.hierynomus.mssmb2.SMBApiException
import com.hierynomus.protocol.transport.TransportException
import com.hierynomus.smbj.SMBClient
import com.hierynomus.smbj.SmbConfig
import com.hierynomus.smbj.auth.AuthenticationContext
import com.hierynomus.smbj.connection.Connection
import com.hierynomus.smbj.session.Session
import com.hierynomus.smbj.share.DiskShare
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import eu.kanade.tachiyomi.data.download.CloudSyncDirectory
import eu.kanade.tachiyomi.data.download.CloudSyncEntry
import logcat.LogPriority
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.core.common.util.system.logcat
import java.io.File
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.EnumSet
import java.util.concurrent.TimeUnit

/**
 * SY --> SMB/NAS 后端：把 smbj 的句柄收成进程级单例，并在**配置变更**或**连接失效**时透明重建。
 *
 * ## 路径模型（重要）
 *
 * 源内路径**永远**是 `/<共享名>/<共享内子路径>`：
 *  - 根（空串或 `/`）= 服务器上的共享列表（SRVSVC 枚举），呈现为目录；
 *  - `/comics/某漫画/第1话.cbz` = 共享 `comics` 内的 `某漫画/第1话.cbz`。
 *
 * 这样设计而不是「填了共享名就相对该共享、空了就跨共享」：后者会让**同一个路径字符串在两种
 * 配置下指向不同文件**，已挂载的目录、目录缓存键、整本缓存文件名全部会串。单一语义下
 * [SmbPreferences.share] 只作为「枚举被服务器拒绝时的兜底共享」（见 [listRoot]）。
 *
 * 与网盘后端（`NetdiskClient` 的 list/downlink 模型）不同，SMB 是真实文件路径 + 原生随机读：
 * 列目录/列文件直接映射 [CloudSyncDirectory] / [CloudSyncEntry]，章节读取交给
 * [SmbRandomAccessSource]（SMB2 READ 带 offset），不存在「服务器不支持 Range → 整本落盘」那条回退。
 *
 * ⚠️ smbj 全是**阻塞** API：suspend 方法内部一律 [withIOContext]；非 suspend 的 [openRead] /
 * [withShareFor] 只允许从 IO 线程调用（[SmbRandomAccessSource] 的读路径由阅读器调度器保证）。
 *
 * ⚠️ 只读：本类只有 list / open / read / 共享枚举，没有任何写、删、改名调用。
 */
@Inject
@SingleIn(AppScope::class)
class SmbClient(
    private val preferences: SmbPreferences,
) {

    private val lock = Any()
    private var handle: SmbHandle? = null

    /** 共享列表缓存：列根目录会频繁发生（进云盘、刷新、扫封面），没必要每次打 srvsvc。 */
    private var sharesCache: List<SmbShareEnumerator.Share>? = null
    private var sharesCachedAt = 0L

    private fun endpointOrNull(): SmbEndpoint? {
        val host = preferences.host.get().trim()
        val username = preferences.username.get()
        if (host.isBlank() || username.isBlank()) return null
        // 端口存的是字符串（与其它字段共用输入控件）：留空或非法一律回退到 445
        val port = preferences.port.get().trim().toIntOrNull()
            ?.takeIf { it in 1..65535 }
            ?: SmbPreferences.DEFAULT_PORT
        return SmbEndpoint(
            host = host,
            port = port,
            fallbackShare = preferences.share.get().trim(),
            domain = preferences.domain.get().trim(),
            username = username,
            password = preferences.password.get(),
        )
    }

    /** 每次建连都重新构造：smbj 的 SmbConfig 不可变，且配置项可能已被用户改过。 */
    private fun buildConfig(): SmbConfig =
        SmbConfig.builder()
            // 单次请求的响应超时；NAS 休眠唤醒后第一次请求可能很慢，给足余量
            .withTimeout(RESPONSE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .withSoTimeout(SO_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            // 让客户端与服务端协商出双方都支持的最高方言（SMB 2.0.2 ~ 3.1.1）
            .withMultiProtocolNegotiate(true)
            // DFS referral 会把路径重定向到别的服务器，漫画共享用不到，关掉少一层失败面
            .withDfsEnabled(false)
            // 期望的单次读长度；smbj 内部会 min(此值, 服务端 MaxReadSize) 钳位，
            // 所以填大了也安全，读不满由调用方循环补齐（见 SmbRandomAccessSource）
            .withReadBufferSize(READ_BUFFER_BYTES)
            .build()

    /**
     * 保证有一个活着的会话并返回它；端点变了或连接死了就整体重建。
     *
     * 锁只保护「建连/换连」，不覆盖具体操作 —— 一个 SMB2 会话本身是多路复用的，
     * 并发的列目录/读取/多个共享的 tree connect 都可以共用同一条连接。
     */
    private fun acquireSession(): Session {
        synchronized(lock) {
            val endpoint = endpointOrNull()
                ?: throw IllegalStateException("SMB 未配置：主机与用户名必填")
            handle?.let { existing ->
                if (existing.endpoint == endpoint && existing.connection.isConnected) {
                    return existing.session
                }
            }
            closeLocked()

            val client = SMBClient(buildConfig())
            val connection = client.connect(endpoint.host, endpoint.port)
            val session = connection.authenticate(
                AuthenticationContext(endpoint.username, endpoint.password.toCharArray(), endpoint.domain),
            )
            handle = SmbHandle(endpoint, client, connection, session)
            sharesCache = null // 换服务器/换账号，旧的共享列表立刻作废
            logcat(LogPriority.INFO) {
                "SMB: 已连接 ${endpoint.host}:${endpoint.port}" +
                    "（方言 ${connection.negotiatedProtocol.dialect}）"
            }
            return session
        }
    }

    /**
     * 取某个共享的 [DiskShare]。smbj 的 `Session.connectShare` 内部按共享名缓存 tree connect，
     * 所以这里不需要自己再做一层 map —— 跨共享浏览不会反复建立 tree connect。
     */
    private fun diskShare(shareName: String): DiskShare {
        val share = acquireSession().connectShare(shareName)
        if (share !is DiskShare) {
            throw IllegalStateException(
                "共享「$shareName」不是磁盘共享（${share.javaClass.simpleName}）——" +
                    "打印机共享和 IPC\$ 不能用来浏览文件",
            )
        }
        return share
    }

    /** 已建立连接的描述（端点 + 协商出的方言），供设置页展示；未连接时返回 null。 */
    private fun describeConnection(): String? = synchronized(lock) {
        handle?.let { "${it.endpoint.host}:${it.endpoint.port} ${it.connection.negotiatedProtocol.dialect}" }
    }

    /**
     * 在某个共享上执行一次操作；连接级失败时**丢弃会话重连并重试一次**。
     *
     * 为什么只对连接级错误重试：[SMBApiException] 表示服务器明确回了状态码
     * （共享不存在 / 路径不存在 / 无权限），重连不会改变结果，重试只是白等一次超时。
     * 典型触发场景是 NAS 待机踢掉了空闲会话、或 App 从后台回来时 socket 已死。
     */
    private fun <T> withShareFor(shareName: String, block: (DiskShare) -> T): T {
        try {
            return block(diskShare(shareName))
        } catch (e: Exception) {
            if (!isConnectionError(e)) throw e
            logcat(LogPriority.WARN, e) { "SMB: 连接失效，重建会话后重试一次" }
            reset()
            return block(diskShare(shareName))
        }
    }

    private fun isConnectionError(e: Throwable): Boolean = when (e) {
        is SMBApiException -> false
        is TransportException, is IOException -> true
        else -> false
    }

    // ===================== 路径拆分 =====================

    /**
     * 源内路径 → (共享名, 共享内路径)。
     *
     * 返回 null 表示「根」，即需要列共享而不是访问某个共享内的路径。
     */
    private fun route(path: String): Pair<String, String>? {
        val trimmed = path.trim('/')
        if (trimmed.isEmpty()) return null
        val share = trimmed.substringBefore('/')
        if (share.isEmpty()) return null
        val inner = trimmed.substringAfter('/', "")
        return share to inner
    }

    // ===================== 目录 / 文件枚举 =====================

    suspend fun listDirectories(path: String): List<CloudSyncDirectory> = withIOContext {
        val target = route(path)
        // 根层：列出服务器上的共享，每个共享呈现为一个目录（路径 = /<共享名>）
        if (target == null) return@withIOContext listRoot()
        val (shareName, inner) = target
        listRaw(shareName, inner)
            .filter { isDirectory(it) }
            .map { info ->
                CloudSyncDirectory(
                    name = info.fileName,
                    path = joinPath(path, info.fileName),
                    lastModified = epochMillis(info),
                )
            }
    }

    suspend fun listFiles(path: String): List<CloudSyncEntry> = withIOContext {
        val target = route(path) ?: return@withIOContext emptyList() // 根层没有文件，只有共享
        val (shareName, inner) = target
        listRaw(shareName, inner)
            .filterNot { isDirectory(it) }
            .map { info ->
                CloudSyncEntry(
                    name = info.fileName,
                    path = joinPath(path, info.fileName),
                    isCollection = false,
                    lastModified = epochMillis(info),
                )
            }
    }

    /** 根层 = 共享列表。枚举被服务器拒绝时退回 [SmbPreferences.share] 里那个兜底共享。 */
    private fun listRoot(): List<CloudSyncDirectory> {
        val shares = cachedSharesOrNull() ?: runCatching { enumerateSharesNow(force = true) }
            .getOrElse { e ->
                val fallback = endpointOrNull()?.fallbackShare.orEmpty()
                if (fallback.isBlank()) throw e
                logcat(LogPriority.WARN, e) {
                    "SMB: 共享枚举失败，退回使用设置的兜底共享「$fallback」"
                }
                return listOf(CloudSyncDirectory(name = fallback, path = "/$fallback", lastModified = 0L))
            }
        return shares
            .filter { it.isDiskTree && !it.isAdminShare }
            .map { CloudSyncDirectory(name = it.name, path = "/${it.name}", lastModified = 0L) }
    }

    private fun cachedSharesOrNull(): List<SmbShareEnumerator.Share>? =
        sharesCache?.takeIf { System.currentTimeMillis() - sharesCachedAt < SHARES_TTL_MS }

    private fun enumerateSharesNow(force: Boolean): List<SmbShareEnumerator.Share> {
        if (!force) cachedSharesOrNull()?.let { return it }
        val endpoint = endpointOrNull() ?: throw IllegalStateException("SMB 未配置：主机与用户名必填")
        val shares = SmbShareEnumerator.enumerate(acquireSession(), endpoint.host).getOrThrow()
        synchronized(lock) {
            sharesCache = shares
            sharesCachedAt = System.currentTimeMillis()
        }
        return shares
    }

    /** 供设置页「列出共享」使用。 */
    suspend fun enumerateShares(): Result<List<SmbShareEnumerator.Share>> = withIOContext {
        runCatching { enumerateSharesNow(force = false) }
            .recoverCatching { error -> throw describeFailure(error) }
    }

    /** 一次 SMB2 QUERY_DIRECTORY；滤掉 "." / ".." 两个自引用项。 */
    private fun listRaw(shareName: String, innerPath: String): List<FileIdBothDirectoryInformation> =
        withShareFor(shareName) { share -> share.list(smbPath(innerPath)) }
            .filter { it.fileName != "." && it.fileName != ".." }

    private fun isDirectory(info: FileIdBothDirectoryInformation): Boolean =
        (info.fileAttributes and FileAttributes.FILE_ATTRIBUTE_DIRECTORY.value) != 0L

    /** SMB 的 FILETIME → 毫秒；服务器没给或解析失败时回退 0（与 WebDAV 的 `parseLastModified` 同口径）。 */
    private fun epochMillis(info: FileIdBothDirectoryInformation): Long =
        runCatching { info.lastWriteTime.toDate().time }.getOrDefault(0L)

    // ===================== 读取 =====================

    /**
     * 打开只读文件句柄（阻塞，须在 IO 线程调用）。**调用方负责 close**。
     * 供 [SmbRandomAccessSource] 做带 offset 的随机读。
     */
    fun openRead(remotePath: String): com.hierynomus.smbj.share.File {
        val (shareName, inner) = route(remotePath)
            ?: throw IllegalArgumentException("SMB 路径必须以 /<共享名>/ 开头：$remotePath")
        return withShareFor(shareName) { share -> share.openReadOnly(inner) }
    }

    /** 整本下载到 [dest]（rar/7z/epub 等无法随机读的格式、以及深链 PDF 兜底走这里）。 */
    suspend fun downloadTo(remotePath: String, dest: File): Unit = withIOContext {
        val (shareName, inner) = route(remotePath)
            ?: throw IllegalArgumentException("SMB 路径必须以 /<共享名>/ 开头：$remotePath")
        withShareFor(shareName) { share ->
            val remote = share.openReadOnly(inner)
            try {
                dest.outputStream().use { out ->
                    val buffer = ByteArray(DOWNLOAD_CHUNK_BYTES)
                    var offset = 0L
                    while (true) {
                        // smbj 的 read 只发一次 SMB2_READ 且不循环，EOF 返回 -1，
                        // 所以必须自己按返回值推进 offset 直到读不动为止。
                        val read = remote.read(buffer, offset)
                        if (read <= 0) break
                        out.write(buffer, 0, read)
                        offset += read
                    }
                }
            } finally {
                runCatching { remote.close() }
            }
        }
    }

    /**
     * 设置页「测试连接」：建会话、列共享、再进第一个共享列一次根目录。
     *
     * 失败原因必须**解包到根因**再返回：smbj 的 `PromiseBackedFuture.get()` 会把任何
     * Throwable 重新包成 `ExecutionException(t)`，所以最外层永远是
     * `java.util.concurrent.ExecutionException: ...`，对用户（和对我们排查）毫无信息量。
     */
    suspend fun testConnection(): Result<String> = withIOContext {
        runCatching {
            acquireSession()
            val shares = enumerateSharesNow(force = true).count { it.isDiskTree && !it.isAdminShare }
            val fallback = endpointOrNull()?.fallbackShare.orEmpty()
            buildString {
                append(describeConnection().orEmpty())
                append("：可浏览共享 $shares 个")
                // 兜底共享填了就顺手列一次，用户能立刻看出「这个共享到底能不能进」
                if (fallback.isNotBlank()) {
                    val entries = withShareFor(fallback) { share -> share.list("").size }
                    append("；兜底共享「$fallback」根目录 $entries 项")
                }
            }
        }.recoverCatching { error ->
            logcat(LogPriority.ERROR, error) { "SMB: 测试连接失败" }
            throw describeFailure(error)
        }
    }

    /**
     * 把 smbj 的包装异常翻译成可据此行动的信息：给出根因 + 最短因果链。
     * 返回的异常仍带原始 cause，logcat 里能看到完整栈。
     */
    private fun describeFailure(original: Throwable): Throwable {
        // 取 cause 链时限定深度：理论上 cause 可能自引用，无界遍历会挂死在错误提示里
        val chain = generateSequence(original) { it.cause }.take(4).toList()
        val root = chain.last()
        val hint = when (root) {
            is UnknownHostException -> "主机名/IP 不可达：确认手机与 NAS 在同一网段，或直接填 IP"
            is SocketTimeoutException -> "连接超时：NAS 可能在待机、445 被拦截，或不在同一网段"
            is SMBApiException -> "服务器拒绝：状态 0x%08x（常见原因：账号或口令错误、禁用了 guest、共享名不存在）"
                .format(root.statusCode)
            is IOException -> "网络层失败：445 端口不通、被防火墙拦截，或 NAS 只启用了 SMB1"
            else -> root.javaClass.simpleName
        }
        val trace = chain.joinToString(" ← ") { "${it.javaClass.simpleName}: ${it.message}" }
        return IllegalStateException("$hint\n$trace", original)
    }

    // ===================== 会话生命周期 =====================

    /** 丢弃当前会话（配置变更、或连接级失败后强制重建）。下一次操作会自动重连。 */
    fun reset() {
        synchronized(lock) { closeLocked() }
    }

    private fun closeLocked() {
        handle?.let { h ->
            // 由内向外关；每一层都吞掉异常，否则前一层失败会让后面的句柄泄漏
            runCatching { h.session.close() }
            runCatching { h.connection.close() }
            runCatching { h.client.close() }
        }
        handle = null
        sharesCache = null
        sharesCachedAt = 0L
    }

    companion object {
        private const val RESPONSE_TIMEOUT_SECONDS = 60L
        private const val SO_TIMEOUT_SECONDS = 60L

        /** 期望的单次 SMB2_READ 长度；实际会被服务端 MaxReadSize 钳小。 */
        private const val READ_BUFFER_BYTES = 1024 * 1024

        /** 整本下载的单次读块。 */
        private const val DOWNLOAD_CHUNK_BYTES = 512 * 1024

        /** 共享列表缓存时长：进云盘、下拉刷新、扫封面都会列根目录，别每次都打 srvsvc。 */
        private const val SHARES_TTL_MS = 60_000L
    }
}

/**
 * 只读打开一个文件：GENERIC_READ + 三种共享访问全开 + FILE_OPEN（只开已存在的文件）。
 *
 * 共享访问全开是必须的 —— NAS 上的漫画文件常被其它客户端（电脑、播放器）同时打开，
 * 只声明 FILE_SHARE_READ 会因为对方持有写句柄而被服务器拒绝。
 * 属性与创建选项传 null 走服务器默认。
 */
private fun DiskShare.openReadOnly(innerPath: String): com.hierynomus.smbj.share.File =
    openFile(
        smbPath(innerPath),
        EnumSet.of(AccessMask.GENERIC_READ),
        null,
        EnumSet.allOf(SMB2ShareAccess::class.java),
        SMB2CreateDisposition.FILE_OPEN,
        null,
    )

/**
 * 共享内路径（POSIX 风格、可带前导 `/`）→ SMB 路径（相对共享根、反斜杠分隔）。
 * 空串表示共享根目录。
 */
private fun smbPath(path: String): String = path.trim('/').replace('/', '\\')

/**
 * 目录 + 条目名 → 源内路径（带前导 `/`，与 WebDAV 的 `CloudSyncEntry.path` 同约定）。
 * 因为路径永远形如 `/<共享名>/...`，这里直接拼接即可，共享名天然留在第一段。
 */
private fun joinPath(dir: String, name: String): String {
    val base = dir.trim('/')
    return if (base.isEmpty()) "/$name" else "/$base/$name"
}

private data class SmbEndpoint(
    val host: String,
    val port: Int,
    /** 枚举被拒绝时用来兜底的共享名；可为空。不再参与路径语义。 */
    val fallbackShare: String,
    val domain: String,
    val username: String,
    val password: String,
)

private class SmbHandle(
    val endpoint: SmbEndpoint,
    val client: SMBClient,
    val connection: Connection,
    val session: Session,
)
