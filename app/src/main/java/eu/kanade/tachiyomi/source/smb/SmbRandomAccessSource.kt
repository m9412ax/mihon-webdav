package eu.kanade.tachiyomi.source.smb

import logcat.LogPriority
import mihon.core.archive.RandomAccessSource
import tachiyomi.core.common.util.system.logcat

/**
 * SY --> SMB 章节的随机读源：把 [RandomAccessSource] 直接映射到 SMB2 READ 的 offset 语义。
 *
 * 与 [eu.kanade.tachiyomi.source.webdav.WebDavRandomAccessSource]（HTTP Range）相比省掉了两件事：
 *  - **不需要探测 Range 支持、也没有「整本落盘回退」**：SMB 原生就是按 offset 读，
 *    所以 `isLocalFallback` 这类状态在 SMB 侧不存在（`BaseWebDavSource.openPdfChapter` 里的
 *    `as? WebDavRandomAccessSource` 判断对本类恒为 null，即按真实链长决定流式还是整本下载）。
 *  - **不需要短时效直链缓存**：句柄一次打开、长期持有，直到 close 或读失败。
 *
 * 两个必须绕开的 smbj 行为（都已在源码核对过）：
 *  1. `File.read(buffer, offset, bufOffset, length)` **只发一个 SMB2_READ 请求、不循环补齐**，
 *     且长度会被 `min(请求长度, 服务端 MaxReadSize)` 钳短 —— 所以这里按返回值循环推进；
 *  2. 读到文件尾返回 **-1** 而不是 0，而 [RandomAccessSource] 的契约是「越界返回空数组、
 *     禁止抛异常」—— 所以 -1 统一翻译成「本块到此为止」。
 *
 * 块缓存（1MB 块 / 单源 32MB LRU）：`RemoteZipReader` 会先读文件尾的中央目录、再按需读各条目，
 * 归档页解码还会并发读多页；没有块缓存时这些访问会退化成大量重复的小请求。
 * SY <--
 */
class SmbRandomAccessSource(
    private val client: SmbClient,
    private val remotePath: String,
) : RandomAccessSource {

    private val lock = Any()
    private var file: com.hierynomus.smbj.share.File? = null
    private var fileSize = -1L
    private var closed = false

    /** 块号 → 块内容。accessOrder=true 让它天然按「最近使用」排序，淘汰时从头取即可。 */
    private val blocks = LinkedHashMap<Long, ByteArray>(16, 0.75f, true)
    private var cachedBytes = 0L

    override val size: Long
        get() = synchronized(lock) {
            if (closed) return@synchronized 0L
            ensureOpenLocked()
            fileSize
        }

    override fun read(offset: Long, length: Int): ByteArray {
        if (length <= 0) return ByteArray(0)
        synchronized(lock) {
            if (closed) return ByteArray(0)
            return try {
                readLocked(offset, length)
            } catch (e: Exception) {
                // 句柄失效（NAS 待机踢掉空闲会话、Wi-Fi 切换、服务端重启）→ 丢弃会话重开一次再读。
                // 不重开的话一次网络抖动就会让整章读成 EOF，表现为「章节打不开」或「后面几页缺失」。
                logcat(LogPriority.WARN, e) { "SMB: 读 $remotePath@$offset 失败，重开句柄重试一次" }
                dropHandleLocked()
                client.reset()
                readLocked(offset, length)
            }
        }
    }

    override fun close() {
        synchronized(lock) {
            if (closed) return
            closed = true
            dropHandleLocked()
        }
    }

    private fun readLocked(offset: Long, length: Int): ByteArray {
        ensureOpenLocked()
        // 越界（含 offset 落在文件尾之后）→ 空数组，调用方据此判 EOF
        if (offset < 0 || offset >= fileSize) return ByteArray(0)
        // 请求跨过文件尾时只读到尾：契约允许返回少于 length 的数据
        val want = minOf(length.toLong(), fileSize - offset).toInt()
        val out = ByteArray(want)
        val end = offset + want
        var written = 0
        var pos = offset
        while (pos < end) {
            val blockStart = pos / BLOCK_BYTES * BLOCK_BYTES
            val block = blockAtLocked(blockStart)
            val inBlock = (pos - blockStart).toInt()
            if (inBlock >= block.size) break
            val count = minOf((block.size - inBlock).toLong(), end - pos).toInt()
            System.arraycopy(block, inBlock, out, written, count)
            written += count
            pos += count
        }
        return if (written == want) out else out.copyOf(written)
    }

    /** 取覆盖 [blockStart] 的那个块（缺失则从服务器读满一块并计入 LRU）。 */
    private fun blockAtLocked(blockStart: Long): ByteArray {
        val index = blockStart / BLOCK_BYTES
        blocks[index]?.let { return it }

        val remaining = fileSize - blockStart
        if (remaining <= 0L) return ByteArray(0)
        val length = minOf(BLOCK_BYTES.toLong(), remaining).toInt()
        val buffer = ByteArray(length)
        val handle = file ?: error("SMB 句柄未打开")
        var pos = 0
        while (pos < length) {
            val count = handle.read(buffer, blockStart + pos, pos, length - pos)
            if (count <= 0) break // -1 = EOF；0 只可能是服务端异常，同样按读不动处理
            pos += count
        }
        // 末块（或读不满时）短于 BLOCK_BYTES，也照常缓存：它就是这个文件的真实尾部
        val block = if (pos == length) buffer else buffer.copyOf(pos)
        blocks[index] = block
        cachedBytes += block.size
        evictLocked()
        return block
    }

    private fun evictLocked() {
        if (cachedBytes <= MAX_CACHE_BYTES) return
        val iterator = blocks.entries.iterator()
        // 至少留一个块：刚读进来的那块是 MRU（在尾部），淘汰从头部开始，正常轮不到它
        while (cachedBytes > MAX_CACHE_BYTES && blocks.size > 1 && iterator.hasNext()) {
            val oldest = iterator.next()
            cachedBytes -= oldest.value.size
            iterator.remove()
        }
    }

    private fun ensureOpenLocked() {
        if (file != null) return
        val handle = client.openRead(remotePath)
        fileSize = handle.length
        file = handle
    }

    private fun dropHandleLocked() {
        file?.let { runCatching { it.close() } }
        file = null
        fileSize = -1L
        blocks.clear()
        cachedBytes = 0L
    }

    private companion object {
        /** 块大小 1MB，与 WebDavRandomAccessSource 的 READ_CHUNK 同口径。 */
        const val BLOCK_BYTES = 1024 * 1024

        /** 单章块缓存上限 32MB：够放下一本 CBZ 的中央目录加若干页，又不至于吃内存。 */
        const val MAX_CACHE_BYTES = 32L * 1024 * 1024
    }
}
