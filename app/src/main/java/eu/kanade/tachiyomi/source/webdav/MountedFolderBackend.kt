package eu.kanade.tachiyomi.source.webdav

import eu.kanade.tachiyomi.data.download.CloudSyncDirectory
import eu.kanade.tachiyomi.data.download.CloudSyncEntry
import mihon.core.archive.RandomAccessSource
import java.io.File

/**
 * SY --> 可挂载进「云盘」（[WebDavSource]）文件夹视图的后端。
 *
 * 挂载记录统一是 `"<routeKey>|<目录标识>|<显示名>"`（存 `NetdiskPreferences.netdiskMounts`），
 * 在源内以 `@<routeKey>:<目录标识>` 前缀路径出现。云盘的所有后端接缝遇到该前缀就剥掉前缀、
 * 委托回对应后端 —— 于是挂载内容**完整共用云盘的浏览、封面、章节识别与流式读取规则**。
 *
 * 两类后端都实现本接口：
 *  - [BaseNetdiskSource] 子类（夸克 / 阿里云盘 / 115）：**id 段路径** + HTTP 直链 Range；
 *  - [eu.kanade.tachiyomi.source.smb.SmbSource]：**真实文件路径** + SMB2 原生 offset 随机读。
 *
 * 这些 `delegateXxx` 之所以存在：对应的接缝在 [BaseWebDavSource] 里是 `protected`，
 * 兄弟类之间不能直接调，必须由各自后端开一层 public 包装。
 * SY <--
 */
interface MountedFolderBackend {

    /** 展示名（书架/选择器标题用）。 */
    val name: String

    /** 路由键：挂载路径前缀 `@<routeKey>:` 里的那一段，也是挂载记录的第一段。 */
    val routeKey: String

    /**
     * 「添加文件夹」可视化选择器逐层浏览用：列出 [path] 下的子目录。
     * [path] 传空串表示该后端的根；返回 (目录标识, 目录名)，失败返回空列表（由调用方提示）。
     */
    suspend fun browseFolders(path: String): List<Pair<String, String>>

    suspend fun delegateListDirectories(path: String): List<CloudSyncDirectory>

    suspend fun delegateListFiles(path: String): List<CloudSyncEntry>

    fun delegateOpenRangeSource(path: String): RandomAccessSource?

    suspend fun delegateDownloadChapterToCache(remotePath: String): File

    suspend fun delegateDownloadCoverEntry(
        actual: CloudSyncEntry,
        mangaUrl: String,
        coverDir: File,
    ): File?

    suspend fun delegateTryHandlePdfCoverLocally(
        targetPath: String,
        baseName: String,
        title: String,
        coverFile: File,
    ): Boolean

    suspend fun delegateIsDeepChainPdfOrPlaceholder(remotePath: String): Boolean

    fun delegateCachedArchiveFile(remotePath: String): File?

    /** 章节逻辑路径 → 真实文件名（带扩展名），ChapterLoader 据此按扩展名分流。 */
    suspend fun fileNameOf(url: String): String

    /** 散图下载到本地缓存（已存在则零流量复用）。 */
    suspend fun downloadImageToCache(folderPath: String, fileName: String): File
}
