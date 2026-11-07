package eu.kanade.tachiyomi.source.smb

import android.content.Context
import android.net.Uri
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import eu.kanade.tachiyomi.data.download.CloudSyncDirectory
import eu.kanade.tachiyomi.data.download.CloudSyncEntry
import eu.kanade.tachiyomi.data.download.normalizePath
import eu.kanade.tachiyomi.source.SourceFolder
import eu.kanade.tachiyomi.source.netdisk.NetdiskPreferences
import eu.kanade.tachiyomi.source.webdav.BaseWebDavSource
import eu.kanade.tachiyomi.source.webdav.CoverQuality
import eu.kanade.tachiyomi.source.webdav.DirListingCache
import eu.kanade.tachiyomi.source.webdav.MountedFolderBackend
import eu.kanade.tachiyomi.source.webdav.WebDavCoverBridge
import eu.kanade.tachiyomi.source.webdav.WebDavCoverStore
import eu.kanade.tachiyomi.source.webdav.enforceLruCacheBudget
import eu.kanade.tachiyomi.util.lang.Hash
import eu.kanade.tachiyomi.util.pdf.PdfiumRenderer
import logcat.LogPriority
import mihon.core.archive.RandomAccessSource
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.core.common.util.system.logcat
import java.io.File

/**
 * SY --> SMB/NAS 阅读源：与 WebDAV 云盘、网盘源同级的第四个内置后端。
 *
 * 浏览 / 章节识别 / 封面 / 文件夹视图 / 排序 / 目录缓存**全部继承自 [BaseWebDavSource]**，
 * 本类只填后端接缝，差异集中在两点：
 *  - **真实文件路径**：章节 url 就是共享内路径（带扩展名），所以不需要网盘源那套
 *    「id 段路径 + id→名字缓存」，`fileNameOf` 用基类默认实现（取路径末段）即可；
 *  - **原生随机读**：[openRangeSource] 直接给 [SmbRandomAccessSource]（SMB2 READ 带 offset），
 *    zip/cbz 流式、PDF、MOBI 全部自动生效，且不存在「服务器不支持 Range → 整本落盘」这条回退路径。
 *
 * 按当前设计**不单独出现在「浏览 → 图源」里**，而是像夸克/115/阿里那样，通过
 * 「云盘 → 添加网盘文件夹」挂载进云盘的文件夹视图（前缀 `@smb:`，见 [MountedFolderBackend]），
 * 复用云盘的封面规则与加载链路。同时它仍注册为一个独立源，供可视化目录选择器按 id 取用。
 *
 * **刻意不实现**深链 PDF 占位封面策略（`tryHandlePdfCoverLocally` / `hasPlaceholderCover` /
 * `isDeepChainPdfOrPlaceholder` 全部沿用基类默认的 false）：那套策略是为「按调用次数风控、
 * 单次请求高延迟」的网盘设计的（深链 PDF 抽一张封面要串行 ~230 次取链请求）。SMB 在局域网内
 * 随机读几乎没有成本，直接流式渲染首页更快也更准 —— 挂上占位图反而是退化。
 * SY <--
 */
@Inject
@SingleIn(AppScope::class)
class SmbSource(
    context: Context,
    private val smbClient: SmbClient,
    private val smbPreferences: SmbPreferences,
    // 挂载记录与网盘共用同一份偏好，「云盘」文件夹视图的刷新/删除链路才能一并复用
    private val netdiskPreferences: NetdiskPreferences,
) : BaseWebDavSource(context), MountedFolderBackend {

    init {
        // 让封面 Provider 能按 authority 路由到本源（独立浏览本源时才会走到）
        WebDavCoverBridge.bindSmbSource(this)
    }

    override val id: Long = ID
    override val name: String = "SMB"
    override val routeKey: String = ROUTE_KEY

    // ===================== 后端接缝 =====================

    override fun isConfigured(): Boolean = smbPreferences.isConfigured()

    override val backend = WebDavCoverStore.Backend.SMB
    override val coverAuthority = WebDavCoverBridge.SMB_AUTHORITY
    override val coverCacheDirName = COVER_CACHE_DIR
    override val archiveCacheSubdir = ARCHIVE_CACHE_SUBDIR
    override val folderCoverSegment = WebDavCoverBridge.FOLDER_SEGMENT

    override suspend fun listDirectories(path: String): List<CloudSyncDirectory> {
        if (!isConfigured()) return emptyList()
        return smbClient.listDirectories(path)
    }

    override suspend fun listFiles(path: String): List<CloudSyncEntry> {
        if (!isConfigured()) return emptyList()
        return smbClient.listFiles(path)
    }

    override fun openRangeSource(remotePath: String): RandomAccessSource? {
        if (!isConfigured()) return null
        return SmbRandomAccessSource(smbClient, remotePath)
    }

    override fun openPdfRenderer(ras: RandomAccessSource): PdfiumRenderer =
        PdfiumRenderer.openProxy(context, ras)

    override suspend fun downloadImageToCache(folderPath: String, fileName: String): File {
        if (!isConfigured()) throw IllegalStateException("SMB 未配置，无法读取章节")
        val remote = normalizePath(folderPath).trimEnd('/') + "/" + fileName
        // 独立于云盘的 cache/webdav-images：散图缓存键是「目录 + 文件名」的哈希，
        // 而 SMB 与 WebDAV 的路径空间各自独立，共用一个目录会让同路径同名文件互相顶掉。
        val cacheDir = File(context.cacheDir, IMAGE_CACHE_SUBDIR).apply { mkdirs() }
        val cacheFile = File(cacheDir, Hash.sha256(remote))
        if (!cacheFile.exists()) {
            smbClient.downloadTo(remote, cacheFile)
            enforceLruCacheBudget(cacheDir, IMG_CACHE_BYTES, setOf(cacheFile.name))
                .forEach { (evicted, bytes) ->
                    logcat(LogPriority.INFO) { "SmbSource: 散图缓存超限，淘汰 $evicted ($bytes B)" }
                }
        } else {
            // 命中即刷新 mtime 作为 LRU 依据：常看的散图不该因 mtime 旧被优先淘汰
            cacheFile.setLastModified(System.currentTimeMillis())
        }
        return cacheFile
    }

    override suspend fun downloadChapterToCache(remotePath: String): File {
        if (!isConfigured()) throw IllegalStateException("SMB 未配置，无法读取章节")
        val fullPath = remotePath.trimStart('/')
        val cacheDir = File(context.cacheDir, ARCHIVE_CACHE_SUBDIR).apply { mkdirs() }
        val cacheFile = File(cacheDir, fullPath.replace('/', '_'))
        if (!cacheFile.exists()) {
            // 只有无法随机读的格式（rar/cbr/7z/cb7/epub）与深链 PDF 兜底会走到这里
            smbClient.downloadTo(remotePath, cacheFile)
            enforceLruCacheBudget(cacheDir, ARCHIVE_CACHE_BYTES, setOf(cacheFile.name))
                .forEach { (evicted, bytes) ->
                    logcat(LogPriority.INFO) { "SmbSource: 章节缓存超限，淘汰 $evicted ($bytes B)" }
                }
        } else {
            cacheFile.setLastModified(System.currentTimeMillis())
        }
        return cacheFile
    }

    override suspend fun downloadCoverEntry(
        actual: CloudSyncEntry,
        mangaUrl: String,
        coverDir: File,
    ): File? {
        if (!isConfigured()) return null
        val ext = actual.name.substringAfterLast('.', "jpg").ifBlank { "jpg" }
        // 命名必须与 BaseWebDavSource.resolveCover 里的 baseName 逐字一致，否则「存进去」和
        // 「读出来」会落到两个文件（这正是 WebDavCoverStore 注释里记过的那个坑）
        val dst = File(coverDir, "${mangaUrl.replace('/', '_')}.$ext")
        runCatching { smbClient.downloadTo(actual.path, dst) }
            .onFailure { e ->
                runCatching { dst.delete() }
                logcat(LogPriority.WARN, e) { "SmbSource: 封面文件下载失败 ${actual.name}" }
            }
        return dst.takeIf { it.exists() && it.length() > 0L && !CoverQuality.isBlank(it) }
    }

    /**
     * 去掉基类的 `lastIndexOf('/') <= 0` 守卫：共享根目录下的单文件漫画（如 `/onepiece.cbz`）
     * 路径里没有第二个 `/`，基类会把它们一律判成 null，导致「读过之后把占位封面升级成真封面」
     * 的兜底失效。命名规则与 [downloadChapterToCache] 逐字一致。
     */
    override fun cachedArchiveFile(remotePath: String): File? =
        File(File(context.cacheDir, ARCHIVE_CACHE_SUBDIR), remotePath.trimStart('/').replace('/', '_'))
            .takeIf { it.exists() && it.length() > 0L }

    // ===================== 文件夹视图与挂载 =====================

    override fun getFoldersPaths(): List<String> = mounts().map { it.first }

    override suspend fun getFolders(): List<SourceFolder> = withIOContext {
        if (!isConfigured()) return@withIOContext emptyList()
        mounts().map { (path, label) ->
            SourceFolder(
                url = path,
                name = label.ifBlank { fallbackLabel(path) },
                coverUrl = "content://$coverAuthority/$folderCoverSegment/${Uri.encode(path)}",
            )
        }
    }

    override suspend fun addFolder(identifier: String): Boolean {
        val path = normalizePath(identifier)
        if (path.isBlank() || !isConfigured()) return false
        if (mounts().any { it.first == path }) return false
        // 校验目录存在：列一次即可（失败 = 路径无效 / 无权限 / 连不上）
        val exists = runCatching { smbClient.listDirectories(path) }.isSuccess
        if (!exists) return false
        return addMount(path, fallbackLabel(path))
    }

    /** 可视化选择器逐层浏览（[MountedFolderBackend]）；空串 = 共享根目录。 */
    override suspend fun browseFolders(path: String): List<Pair<String, String>> = withIOContext {
        if (!isConfigured()) return@withIOContext emptyList()
        runCatching { listDirectories(path) }
            .onFailure { e -> logcat(LogPriority.WARN, e) { "$name: 浏览目录失败 $path" } }
            .getOrDefault(emptyList())
            .map { it.path to it.name }
    }

    /** 挂载记录解析：`"smb|<共享内路径>|<显示名>"`（与网盘挂载同存 netdiskMounts）。 */
    private fun mounts(): List<Pair<String, String>> =
        netdiskPreferences.netdiskMounts.get().mapNotNull { entry ->
            val parts = entry.split('|')
            if (parts.size < 3 || parts[0] != ROUTE_KEY) return@mapNotNull null
            parts[1] to parts.drop(2).joinToString("|")
        }

    /** 写挂载记录并穿透旧的目录缓存（旧缓存可能是添加失败时留下的空列表）。 */
    private fun addMount(path: String, label: String): Boolean {
        val entry = "$ROUTE_KEY|$path|$label"
        val current = netdiskPreferences.netdiskMounts.get()
        if (entry in current) return false
        netdiskPreferences.netdiskMounts.set(current + entry)
        DirListingCache.markStaleDirectory(ID, path)
        DirListingCache.markStaleEntries(ID, path)
        return true
    }

    /** 路径末段做显示名；共享根目录（路径为 "/"）显示「根目录」。 */
    private fun fallbackLabel(path: String): String =
        path.trim('/').substringAfterLast('/', "").ifBlank { "根目录" }

    // ===================== 供「云盘」门面委托的 public 包装（MountedFolderBackend） =====================

    override suspend fun delegateListDirectories(path: String): List<CloudSyncDirectory> = listDirectories(path)

    override suspend fun delegateListFiles(path: String): List<CloudSyncEntry> = listFiles(path)

    override fun delegateOpenRangeSource(path: String): RandomAccessSource? = openRangeSource(path)

    override suspend fun delegateDownloadChapterToCache(remotePath: String): File =
        downloadChapterToCache(remotePath)

    override suspend fun delegateDownloadCoverEntry(
        actual: CloudSyncEntry,
        mangaUrl: String,
        coverDir: File,
    ): File? = downloadCoverEntry(actual, mangaUrl, coverDir)

    override suspend fun delegateTryHandlePdfCoverLocally(
        targetPath: String,
        baseName: String,
        title: String,
        coverFile: File,
    ): Boolean = tryHandlePdfCoverLocally(targetPath, baseName, title, coverFile)

    override suspend fun delegateIsDeepChainPdfOrPlaceholder(remotePath: String): Boolean =
        isDeepChainPdfOrPlaceholder(remotePath)

    override fun delegateCachedArchiveFile(remotePath: String): File? = cachedArchiveFile(remotePath)

    companion object {
        /**
         * 源 ID 槽位：`LocalSource.ID = 0`、`WebDavSource.ID = -20_260_924`、
         * 夸克 `-20_261_002`、阿里云盘 `-20_261_003`、115 开放平台 `-20_261_004`，
         * 本源取下一个空位，沿用同款日期命名便于辨认。
         */
        const val ID = -20_261_005L

        /** 「云盘」门面按此前缀把挂载路径路由回本源。 */
        const val ROUTE_KEY = "smb"

        /** 封面缓存目录（filesDir 下），须与 WebDavCoverStore 的 DIR_SMB 一致。 */
        private const val COVER_CACHE_DIR = "smb-covers"

        /** 整本下载缓存子目录。 */
        private const val ARCHIVE_CACHE_SUBDIR = "smb-archives"

        /** 散图缓存子目录。 */
        private const val IMAGE_CACHE_SUBDIR = "smb-images"

        /** 整本下载缓存预算：1GB LRU（与 WebDAV / 网盘同口径）。 */
        private const val ARCHIVE_CACHE_BYTES = 1L * 1024 * 1024 * 1024
    }
}
