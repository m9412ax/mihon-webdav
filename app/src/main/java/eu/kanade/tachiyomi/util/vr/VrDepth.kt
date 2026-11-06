package eu.kanade.tachiyomi.util.vr

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.graphics.Bitmap
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.nio.FloatBuffer
import java.security.MessageDigest
import java.util.Arrays
import java.util.Locale
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * SY --> 2D→3D 的深度来源：运行时下载单目深度模型，推理出一张视差图喂给 warp。
 *
 * 拆成三块是因为它们的失败模式完全不同，混在一起只会让「下不动 / 跑不了 / 跑出来是糊的」
 * 变成同一条无从判断的报错：
 *  - [VrDepthModel] 清单（地址、体积、许可）
 *  - [VrDepthModelStore] 下载与本地校验
 *  - [VrDepthEstimator] ONNX 会话 + 预处理 + 归一化
 *  - [VrDepthDiskCache] 换页不重算
 *
 * 推理放在头显本机：读漫画时可能压根没有网络（NAS / 离线包），而深度只跟这一页有关，
 * 没有任何需要集中的状态。
 */
enum class VrDepthModel(
    /** 参与缓存键：换模型不能让旧深度被误用。 */
    val id: String,
    val label: String,
    val repo: String,
    val path: String,
    /** 官方标称体积，用来判断「下完了没有」。 */
    val expectedBytes: Long,
    /** DPT 主干要求边长是它的倍数，否则位置编码插值不齐。 */
    val sideDivisor: Int = 14,
    /** 推理分辨率的像素预算；边长按原图比例缩到这个面积附近。 */
    val pixelBudget: Int = 518 * 518,
    /**
     * 许可。small 是 Apache-2.0；base 是 CC-BY-NC-4.0 只能个人用途——
     * 所以默认给 small，而不是效果更好的 base。
     */
    val license: String,
) {
    SMALL_QUANTIZED(
        "dav2-small-int8",
        "Depth Anything V2 Small · int8（27 MB，Apache-2.0）",
        "onnx-community/depth-anything-v2-small",
        "onnx/model_quantized.onnx",
        27_258_801L,
        license = "apache-2.0",
    ),
    SMALL_FP32(
        "dav2-small-fp32",
        "Depth Anything V2 Small · fp32（99 MB，Apache-2.0）",
        "onnx-community/depth-anything-v2-small",
        "onnx/model.onnx",
        99_060_839L,
        license = "apache-2.0",
    ),
    BASE_QUANTIZED(
        "dav2-base-int8",
        "Depth Anything V2 Base · int8（102 MB，CC-BY-NC 仅个人用途）",
        "onnx-community/depth-anything-v2-base",
        "onnx/model_quantized.onnx",
        102_391_398L,
        license = "cc-by-nc-4.0",
    ),
    ;

    companion object {
        fun fromId(id: String?): VrDepthModel = entries.firstOrNull { it.id == id } ?: SMALL_QUANTIZED
    }
}

/**
 * 模型下载与本地存放。
 *
 * 源按顺序试，先 hf-mirror.com 再 huggingface.co：这一切发生在头显上、没有代理可用，
 * 而国内直连 huggingface 通常取不到，所以镜像必须排在前面。
 */
class VrDepthModelStore(private val root: File) {

    private fun fileFor(model: VrDepthModel) = File(root, "${model.id}.onnx")

    fun isReady(model: VrDepthModel): Boolean = fileFor(model).let { it.isFile && it.length() == model.expectedBytes }

    fun localFile(model: VrDepthModel): File? = fileFor(model).takeIf { it.isFile }

    fun readyModels(): List<VrDepthModel> = VrDepthModel.entries.filter { isReady(it) }

    fun delete(model: VrDepthModel) {
        runCatching { fileFor(model).delete() }
        runCatching { File(root, "${model.id}.onnx.part").delete() }
    }

    /**
     * 下载。[onProgress] 收到 (已下载, 总字节)，总字节未知时为 -1。
     * 已存在且体积正确就直接返回。两个源都失败时把最后一个真实异常原样抛出——
     * 只说「下载失败」无从判断是网络、体积不符还是磁盘满。
     */
    fun download(model: VrDepthModel, onProgress: (Long, Long) -> Unit = { _, _ -> }): File {
        val target = fileFor(model)
        if (target.isFile && target.length() == model.expectedBytes) return target
        // 体积不对说明上次中断或被截断；重下比续传省心，也避免半截文件被当成好的。
        runCatching { target.delete() }
        root.mkdirs()
        val tmp = File(root, "${model.id}.onnx.part")
        var lastError: Throwable? = null
        for (host in MIRROR_HOSTS) {
            val url = "$host/${model.repo}/resolve/main/${model.path}"
            try {
                httpGet(url, tmp, onProgress)
                if (tmp.length() != model.expectedBytes) {
                    throw IllegalStateException(
                        "下载体积不符：期望 ${model.expectedBytes} 字节，实际 ${tmp.length()}（$url）",
                    )
                }
                check(tmp.renameTo(target)) { "无法把临时文件改名为 $target（磁盘满？）" }
                return target
            } catch (e: Throwable) {
                lastError = e
                Log.w(TAG, "从 $host 下载 ${model.id} 失败", e)
                runCatching { tmp.delete() }
            }
        }
        throw lastError ?: IllegalStateException("没有可用的下载源")
    }

    private fun httpGet(url: String, dest: File, onProgress: (Long, Long) -> Unit) {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.instanceFollowRedirects = true
        connection.connectTimeout = 15_000
        connection.readTimeout = 30_000
        // 关掉压缩：断点/体积校验都按原始字节数算，gzip 会让 contentLength 对不上。
        connection.setRequestProperty("Accept-Encoding", "identity")
        try {
            connection.connect()
            val code = connection.responseCode
            if (code !in 200..299) throw IllegalStateException("HTTP $code ← $url")
            val total = connection.contentLengthLong
            var written = 0L
            connection.inputStream.use { input ->
                FileOutputStream(dest).use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        written += read
                        onProgress(written, total)
                    }
                    output.flush()
                }
            }
        } finally {
            connection.disconnect()
        }
    }

    private companion object {
        const val TAG = "VrDepthStore"
        val MIRROR_HOSTS = listOf("https://hf-mirror.com", "https://huggingface.co")
    }
}

/** 一次深度图的尺寸 + 数据。R8，0=远 255=近。 */
data class VrDepthMap(val width: Int, val height: Int, val pixels: ByteArray) {
    override fun equals(other: Any?): Boolean =
        other is VrDepthMap && other.width == width && other.height == height &&
            other.pixels.contentEquals(pixels)

    override fun hashCode(): Int = (width * 31 + height) * 31 + pixels.contentHashCode()
}

/**
 * 单目深度推理。
 *
 * 生命周期与一次阅读会话相当：建会话（图优化 + 权重常驻）比单次推理贵得多，
 * 所以显式 open/close，不每页新建。[close] 之后不可复用。
 *
 * 输出是**视差**（越大越近），和原生层 warp 的约定一致，中间不取反。
 */
class VrDepthEstimator(modelFile: File, private val model: VrDepthModel) {

    private val environment: OrtEnvironment = OrtEnvironment.getEnvironment()
    private val session: OrtSession = environment.createSession(modelFile.absolutePath)

    val inputName: String = session.inputNames.firstOrNull() ?: error("模型没有输入节点")
    val outputName: String = session.outputNames.firstOrNull() ?: error("模型没有输出节点")

    /**
     * @return 视差图；null 表示这次推理失败（调用方应当退化成平面显示，而不是卡住）。
     */
    fun estimate(bitmap: Bitmap): VrDepthMap? {
        val (width, height) = chooseInputSize(bitmap.width, bitmap.height)
        val scaled = Bitmap.createScaledBitmap(bitmap, width, height, true)
        val pixels = IntArray(width * height)
        scaled.getPixels(pixels, 0, width, 0, 0, width, height)
        if (scaled !== bitmap) runCatching { scaled.recycle() }

        // CHW + ImageNet 归一化，与 transformers.js 的 DPTImageProcessor 参数一致。
        val plane = width * height
        val input = FloatArray(3 * plane)
        for (i in pixels.indices) {
            val color = pixels[i]
            input[i] = ((color shr 16 and 0xFF) / 255f - MEAN[0]) / STD[0]
            input[plane + i] = ((color shr 8 and 0xFF) / 255f - MEAN[1]) / STD[1]
            input[2 * plane + i] = ((color and 0xFF) / 255f - MEAN[2]) / STD[2]
        }

        val output = infer(width, height, input) ?: return null
        return normalize(output, width, height)
    }

    private fun infer(width: Int, height: Int, input: FloatArray): FloatArray? {
        // 刻意不去探测模型的固定输入形状、也就完全不碰 TensorInfo：这几个类在不同
        // onnxruntime 版本里换过归属，而动态形状本来就用不上它。真遇到静态形状的导出，
        // ORT 会在这里抛异常，我们返回 null，画面退化成平面——不会卡住也不会崩。
        val shape = longArrayOf(1, 3, height.toLong(), width.toLong())
        val started = System.currentTimeMillis()
        val tensor = OnnxTensor.createTensor(environment, FloatBuffer.wrap(input), shape)
        val result = try {
            session.run(mapOf(inputName to tensor))
        } catch (e: Throwable) {
            Log.e(TAG, "深度推理失败", e)
            null
        } finally {
            runCatching { tensor.close() }
        } ?: return null
        return try {
            val buffer = (result[0] as? OnnxTensor)?.floatBuffer
            if (buffer == null) {
                null
            } else {
                val values = FloatArray(buffer.remaining())
                buffer.get(values)
                values
            }
        } catch (e: Throwable) {
            Log.e(TAG, "读取深度输出失败", e)
            null
        } finally {
            runCatching { result.close() }
            Log.d(TAG, "深度推理 ${width}x$height 用时 ${System.currentTimeMillis() - started}ms")
        }
    }

    /**
     * 压到 0..255。
     *
     * 用 1%/99% 分位而不是 min/max：一张离群值（镜面高光、黑边框）就能把整个动态范围吃掉，
     * min/max 归一化在漫画上几乎必然翻车。
     */
    private fun normalize(values: FloatArray, width: Int, height: Int): VrDepthMap? {
        // 输出分辨率应当等于输入，但 DPT 的头偶尔会差一个像素；按实际长度反推高度，
        // 免得建出来的位图和缓冲区尺寸对不上，那会是一个很难看的 IllegalArgumentException。
        if (values.isEmpty()) return null
        val outHeight = if (width > 0) values.size / width else 0
        if (outHeight <= 0) return null
        val sorted = values.copyOf().apply { Arrays.sort(this) }
        val lo = sorted[min((sorted.size * 0.01f).toInt(), sorted.size - 1)]
        val hi = sorted[min((sorted.size * 0.99f).toInt(), sorted.size - 1)]
        val span = hi - lo
        val out = ByteArray(values.size)
        if (span < 1e-6f) {
            // 纯色页/空白页：输出「平面」，而不是除零或者一片随机噪点。
            Arrays.fill(out, 128.toByte())
        } else {
            for (i in values.indices) {
                val normalized = ((values[i] - lo) / span).coerceIn(0f, 1f)
                out[i] = (normalized * 255f + 0.5f).toInt().toByte()
            }
        }
        return VrDepthMap(width, outHeight, out)
    }

    /** 保比例，面积贴近模型的 pixelBudget，边长对齐到 sideDivisor。 */
    private fun chooseInputSize(sourceWidth: Int, sourceHeight: Int): Pair<Int, Int> {
        val divisor = model.sideDivisor
        val scale = sqrt(model.pixelBudget.toDouble() / max(1, sourceWidth * sourceHeight).toDouble())
        val width = align(sourceWidth * scale, divisor)
        val height = align(sourceHeight * scale, divisor)
        return width to height
    }

    private fun align(scaled: Double, divisor: Int): Int =
        max(divisor, min(floor(scaled / divisor).toInt() * divisor, MAX_SIDE))

    fun close() {
        runCatching { session.close() }
    }

    private companion object {
        const val TAG = "VrDepthEstimator"

        /** 单边上限。面积上限由 [pixelBudget] 管，所以这个数只挡住极端细长的图。 */
        const val MAX_SIDE = 14 * 60

        val MEAN = floatArrayOf(0.485f, 0.456f, 0.406f)
        val STD = floatArrayOf(0.229f, 0.224f, 0.225f)
    }
}

/**
 * 深度图的磁盘缓存。
 *
 * 「换页不要重复算」的关键：SD835 上单帧推理几百毫秒到几秒，来回翻页不该再算第二遍。
 * 存裸 R8 + 12 字节头，比 PNG 省掉一次编解码，也不用管位图格式。
 */
class VrDepthDiskCache(private val root: File, private val maxBytes: Long) {

    /**
     * 缓存键。必须把「哪本书 + 哪一话 + 哪一页 + 哪个模型 + 什么推理分辨率」都算进去：
     * 少任何一项，都会在换书 / 换模型之后拿到一张对不上号的深度图，
     * 而那表现成「立体感莫名错乱」，几乎无法从现象反查原因。
     *
     * 用 manga_id + 章节 url 而不是 source_id：Chapter 上根本没有 source 字段，
     * 而章节 url 在同一本书内就是源的天然标识。
     */
    fun keyFor(model: VrDepthModel, mangaId: Long, chapterUrl: String, pageIndex: Int): String {
        val raw = "${model.id}|${model.pixelBudget}|$mangaId|$chapterUrl|$pageIndex"
        return MessageDigest.getInstance("SHA-1").digest(raw.toByteArray())
            .joinToString("") { "%02x".format(Locale.ROOT, it) }
    }

    fun read(key: String): VrDepthMap? {
        val file = File(root, key)
        if (!file.isFile) return null
        return runCatching {
            val bytes = file.readBytes()
            if (bytes.size < 12) return null
            val width = readInt(bytes, 0)
            val height = readInt(bytes, 4)
            val length = readInt(bytes, 8)
            if (width <= 0 || height <= 0 || length != width * height || bytes.size < 12 + length) {
                return null
            }
            file.setLastModified(System.currentTimeMillis())
            VrDepthMap(width, height, bytes.copyOfRange(12, 12 + length))
        }.getOrNull()
    }

    fun write(key: String, map: VrDepthMap) {
        runCatching {
            root.mkdirs()
            val header = ByteArray(12)
            writeInt(header, 0, map.width)
            writeInt(header, 4, map.height)
            writeInt(header, 8, map.pixels.size)
            FileOutputStream(File(root, key)).use {
                it.write(header)
                it.write(map.pixels)
            }
            evictIfNeeded()
        }.onFailure { Log.w(TAG, "深度缓存写入失败", it) }
    }

    /** 超预算就按最后访问时间淘汰。刚写过的那条时间最新，不会把自己踢掉。 */
    private fun evictIfNeeded() {
        val files = root.listFiles()?.toList() ?: return
        var total = files.sumOf { it.length() }
        if (total <= maxBytes) return
        for (file in files.sortedBy { it.lastModified() }) {
            total -= file.length()
            runCatching { file.delete() }
            if (total <= maxBytes) break
        }
    }

    fun clear() {
        runCatching { root.listFiles()?.forEach { it.delete() } }
    }

    private fun readInt(bytes: ByteArray, at: Int): Int =
        (bytes[at].toInt() and 0xFF) or
            ((bytes[at + 1].toInt() and 0xFF) shl 8) or
            ((bytes[at + 2].toInt() and 0xFF) shl 16) or
            ((bytes[at + 3].toInt() and 0xFF) shl 24)

    private fun writeInt(bytes: ByteArray, at: Int, value: Int) {
        bytes[at] = (value and 0xFF).toByte()
        bytes[at + 1] = (value shr 8 and 0xFF).toByte()
        bytes[at + 2] = (value shr 16 and 0xFF).toByte()
        bytes[at + 3] = (value shr 24 and 0xFF).toByte()
    }

    private companion object {
        const val TAG = "VrDepthCache"
    }
}

/**
 * VR 相关的存储路径。集中一处，是为了让「设置页显示已下载」和「VrViewer 决定去读哪个文件」
 * 看的确实是同一个地方——这两处各写一遍路径字符串，写错一个字符就表现为
 * 「明明显示已下载，打开却说没有模型」。
 */
object VrStorage {
    fun modelRoot(context: android.content.Context): File = File(context.filesDir, "vr-models")

    /** 深度图缓存放在 cacheDir：它是可再生产物，系统存储紧张时有权自己清掉。 */
    fun depthCacheRoot(context: android.content.Context): File = File(context.cacheDir, "vr-depth")
}

/** 位图 → 深度图上传用的 ALPHA_8。原生长度按 stride==width 读，所以直接 copyPixelsFromBuffer。 */
fun VrDepthMap.toAlphaBitmap(): Bitmap {
    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ALPHA_8)
    bitmap.copyPixelsFromBuffer(ByteBuffer.wrap(pixels))
    return bitmap
}
