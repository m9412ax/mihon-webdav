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
 * SY --> SMB/NAS 后端：把 smbj 的四级句柄（SMBClient → Connection → Session → DiskShare）
 * 收成进程级单例，并在**配置变更**或**连接失效**时透明重建。
 *
 * 与网盘后端（`NetdiskClient` 的 list/downlink 模型）不同，SMB 是真实文件路径 + 原生随机读：
 *  - 列目录/列文件直接映射 [CloudSyncDirectory] / [CloudSyncEntry]，路径就是共享内相对路径；
 *  - 章节读取交给 [SmbRandomAccessSource]（SMB2 READ 带 offset），不需要 HTTP Range 那套。
 *
 * ⚠️ smbj 全是**阻塞** API：suspend 方法内部一律 [withIOContext]；非 suspend 的 [openRead] /
 * [withShare] 只允许从 IO 线程调用（[SmbRandomAccessSource] 的读路径由阅读器调度器保证）。
 *
 * ⚠️ 只读：本类只有 list / open / read，没有任何写、删、改名调用。
 * SY <--
 */
@Inject
@SingleIn(AppScope::class)
class SmbClient(
    private val preferences: SmbPreferences,
) {

    private val lock = Any()
    private var handle: SmbHandle? = null

    /**
     * 未配置时返回 null，调用方据此优雅降级（与 WebDAV 的 `configOrNull()` 同语义）。
     *
     * @param requireShare 枚举共享时传 false —— 用户此时恰恰因为不知道共享名才要枚举，
     *                     把共享名当必填会让这个功能永远打不开。正常读取路径仍传 true。
     */
    private fun endpointOrNull(requireShare: Boolean): SmbEndpoint? {
        val host = preferences.host.get().trim()
        val share = preferences.share.get().trim()
        val username = preferences.username.get()
        if (host.isBlank() || username.isBlank()) return null
        if (requireShare && share.isBlank()) return null
        // 端口存的是字符串（与其它字段共用输入控件）：留空或非法一律回退到 445，不让用户填错就连不上
        val port = preferences.port.get().trim().toIntOrNull()
            ?.takeIf { it in 1..65535 }
            ?: SmbPreferences.DEFAULT_PORT
        return SmbEndpoint(
            host = host,
            port = port,
            share = share,
            domain = preferences.domain.get().trim(),
            username = username,
            password = preferences.password.get(),
        )
    }

    /** 每次建连都重新构造：smbj 的 SmbConfig 是不可变的，且配置项可能已被用户改过。 */
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
     * 取得可用的 [DiskShare]：端点没变且连接还活着就直接复用，否则丢弃旧句柄重连。
     * 锁只保护「建连/换连」，不覆盖具体操作 —— 一个 SMB2 会话本身是多路复用的，
     * 并发的列目录/读取可以共用同一条连接。
     */
    private fun acquireShare(): DiskShare {
        synchronized(lock) {
            val endpoint = endpointOrNull(requireShare = true) ?: throw IllegalStateException("SMB 未配置")
            handle?.let { existing ->
                if (existing.endpoint == endpoint && existing.connection.isConnected) {
                    return existing.share
                }
            }
            closeLocked()

            val client = SMBClient(buildConfig())
            val connection = client.connect(endpoint.host, endpoint.port)
            val session = connection.authenticate(
                AuthenticationContext(endpoint.username, endpoint.password.toCharArray(), endpoint.domain),
            )
            val share = session.connectShare(endpoint.share)
            if (share !is DiskShare) {
                runCatching { session.close() }
                runCatching { client.close() }
                throw IllegalStateException(
                    "共享「${endpoint.share}」不是磁盘共享（${share.javaClass.simpleName}），无法浏览文件",
                )
            }
            handle = SmbHandle(endpoint, client, connection, session, share)
            logcat(LogPriority.INFO) {
                "SMB: 已连接 ${endpoint.host}:${endpoint.port}/${endpoint.share}" +
                    "（方言 ${connection.negotiatedProtocol.dialect}）"
            }
            return share
        }
    }

    /**
     * 枚举服务器上的共享（SRVSVC / NetrShareEnum）。
     *
     * 走**独立的临时连接**而不是进程级句柄池：这是设置页的一次性动作，此时共享名通常还
     * 是空的（用户正因为不知道才要枚举），复用池子既拿不到端点、也会和正常读取路径互相
     * 干扰——枚举失败不该把已经在工作的会话一起拖下水。
     */
    suspend fun enumerateShares(): Result<List<SmbShareEnumerator.Share>> = withIOContext {
        runCatching {
            val endpoint = endpointOrNull(requireShare = false)
                ?: throw IllegalStateException("枚举共享至少需要主机与用户名（密码可留空）")
            SMBClient(buildConfig()).use { client ->
                client.connect(endpoint.host, endpoint.port).use { connection ->
                    connection.authenticate(
                        AuthenticationContext(endpoint.username, endpoint.password.toCharArray(), endpoint.domain),
                    ).use { session ->
                        SmbShareEnumerator.enumerate(session, endpoint.host).getOrThrow()
                    }
                }
            }
        }.recoverCatching { error -> throw describeFailure(error) }
    }

    /** 已建立连接的描述（端点 + 协商出的方言），供设置页「测试连接」展示；未连接时返回 null。 */
    private fun describeConnection(): String? = synchronized(lock) {
        handle?.let {
            "${it.endpoint.host}:${it.endpoint.port}/${it.endpoint.share} ${it.connection.negotiatedProtocol.dialect}"
        }
    }

    /**
     * 在 [DiskShare] 上执行一次操作；连接级失败（传输异常 / IO 异常）时**丢弃会话重连并重试一次**。
     *
     * 为什么只对连接级错误重试：[SMBApiException] 表示服务器明确回了状态码
     * （路径不存在 / 无权限 / 参数非法），重连不会改变结果，重试只是白等一次超时。
     * 典型触发场景是 NAS 待机踢掉了空闲会话、或 App 从后台回来时 socket 已死。
     */
    private fun <T> withShare(block: (DiskShare) -> T): T {
        try {
            return block(acquireShare())
        } catch (e: Exception) {
            if (!isConnectionError(e)) throw e
            logcat(LogPriority.WARN, e) { "SMB: 连接失效，重建会话后重试一次" }
            reset()
            return block(acquireShare())
        }
    }

    private fun isConnectionError(e: Throwable): Boolean = when (e) {
        is SMBApiException -> false
        is TransportException, is IOException -> true
        else -> false
    }

    // ===================== 目录 / 文件枚举 =====================

    suspend fun listDirectories(path: String): List<CloudSyncDirectory> = withIOContext {
        listRaw(path)
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
        listRaw(path)
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

    /** 一次 SMB2 QUERY_DIRECTORY；滤掉 "." / ".." 两个自引用项。 */
    private fun listRaw(path: String): List<FileIdBothDirectoryInformation> =
        withShare { share -> share.list(smbPath(path)) }
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
    fun openRead(remotePath: String): com.hierynomus.smbj.share.File =
        withShare { share -> share.openReadOnly(remotePath) }

    /** 整本下载到 [dest]（rar/7z/epub 等无法随机读的格式、以及 PDF 深链兜底走这里）。 */
    suspend fun downloadTo(remotePath: String, dest: File): Unit = withIOContext {
        withShare { share ->
            val remote = share.openReadOnly(remotePath)
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
     * 设置页「测试连接」：建连 + 列一次共享根目录，返回可直接展示的结果描述。
     *
     * ⚠️ 失败原因必须**解包到根因**再返回：smbj 的 `PromiseBackedFuture.get()` 会把任何
     * Throwable 重新包成 `ExecutionException(t)`，所以最外层永远是
     * `java.util.concurrent.ExecutionException: ...`，对用户（和对我们排查）毫无信息量。
     * 真正的线索在 cause 链最深处（`SMBApiException` 的状态码 / `UnknownHostException` /
     * `SocketTimeoutException` 等），这里一路取到最后一个带 message 的 cause。
     */
    suspend fun testConnection(): Result<String> = withIOContext {
        runCatching {
            withShare { share ->
                val rootCount = share.list("").size
                "${describeConnection().orEmpty()}：根目录 $rootCount 项"
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
            runCatching { h.share.close() }
            runCatching { h.session.close() }
            runCatching { h.connection.close() }
            runCatching { h.client.close() }
        }
        handle = null
    }

    companion object {
        private const val RESPONSE_TIMEOUT_SECONDS = 60L
        private const val SO_TIMEOUT_SECONDS = 60L

        /** 期望的单次 SMB2_READ 长度；实际会被服务端 MaxReadSize 钳小。 */
        private const val READ_BUFFER_BYTES = 1024 * 1024

        /** 整本下载的单次读块。 */
        private const val DOWNLOAD_CHUNK_BYTES = 512 * 1024
    }
}

/**
 * 只读打开一个文件：GENERIC_READ + 三种共享访问全开 + FILE_OPEN（只开已存在的文件）。
 *
 * 共享访问全开是必须的 —— NAS 上的漫画文件常被其它客户端（电脑、播放器）同时打开，
 * 只声明 FILE_SHARE_READ 会因为对方持有写句柄而被服务器拒绝。
 * 属性与创建选项传 null 走服务器默认。
 */
private fun DiskShare.openReadOnly(remotePath: String): com.hierynomus.smbj.share.File =
    openFile(
        smbPath(remotePath),
        EnumSet.of(AccessMask.GENERIC_READ),
        null,
        EnumSet.allOf(SMB2ShareAccess::class.java),
        SMB2CreateDisposition.FILE_OPEN,
        null,
    )

/**
 * 源内路径（POSIX 风格、可带前导 `/`）→ SMB 路径（共享内相对、反斜杠分隔）。
 * 空串表示共享根目录。
 */
private fun smbPath(path: String): String = path.trim('/').replace('/', '\\')

/** 目录 + 条目名 → 源内路径（带前导 `/`，与 WebDAV 的 `CloudSyncEntry.path` 同约定）。 */
private fun joinPath(dir: String, name: String): String {
    val base = dir.trim('/')
    return if (base.isEmpty()) "/$name" else "/$base/$name"
}

private data class SmbEndpoint(
    val host: String,
    val port: Int,
    val share: String,
    val domain: String,
    val username: String,
    val password: String,
)

private class SmbHandle(
    val endpoint: SmbEndpoint,
    val client: SMBClient,
    val connection: Connection,
    val session: Session,
    val share: DiskShare,
)
