package eu.kanade.tachiyomi.ui.reader.vr

import android.content.res.AssetManager
import android.graphics.Bitmap

/**
 * SY --> [libmihonvr-jni.so] 的 Kotlin 门面。
 *
 * 之所以是 `object` 而不是 companion object：companion 的成员编译后落在
 * `VrBridge$Companion` 这个类上，JNI 符号名会变成 `..._00024Companion_nativeStart`，
 * 和原生侧写的 `Java_eu_kanade_tachiyomi_ui_reader_vr_VrBridge_nativeStart` 对不上，
 * 而这种不匹配编译期一句报错都没有，只在第一次调用时抛 UnsatisfiedLinkError。
 *
 * 原生层的线程模型决定了这里的约定：OpenXR 会话和 GL 上下文只存在于渲染线程，
 * 所以所有方法都是「把数据丢进队列就返回」，不阻塞调用方（也就是主线程）；
 * 真正的纹理上传由渲染线程在下一帧开始前完成。
 */
object VrBridge {

    private const val LIBRARY = "mihonvr-jni"

    /** `System.loadLibrary` 的失败原因；null 表示库可用。abiFilters 之外的设备上就会走到这里。 */
    private var loadFailure: String? = null

    init {
        try {
            System.loadLibrary(LIBRARY)
        } catch (e: Throwable) {
            loadFailure = e.message ?: e.javaClass.toString()
        }
    }

    /** 库是否可用。这不等于设备上有 OpenXR runtime —— 后者要到 [start] 才知道。 */
    val libraryAvailable: Boolean get() = loadFailure == null

    /**
     * 起渲染线程并等 OpenXR 会话进入 READY。
     * @return true 表示会话已就绪；false 时原因在 [lastError]。
     */
    fun start(assetManager: AssetManager): Boolean {
        if (!libraryAvailable) return false
        return runCatching { nativeStart(assetManager) }.getOrDefault(false)
    }

    fun stop() {
        if (libraryAvailable) runCatching { nativeStop() }
    }

    fun isReady(): Boolean = libraryAvailable && runCatching { nativeIsReady() }.getOrDefault(false)

    /** [bitmap] 必须是 [Bitmap.Config.ARGB_8888]；[serial] 用来确认这一页真的上屏了。 */
    fun setPage(bitmap: Bitmap, serial: Long): Boolean =
        libraryAvailable && runCatching { nativeSetPage(bitmap, serial) }.getOrDefault(false)

    /**
     * 深度图：[Bitmap.Config.ALPHA_8] 或 ARGB_8888（取红通道），0=远 1=近。
     * [serial] 必须是它所属那一页的序号 —— 原生层只在 serial 追上当前页时才采用，
     * 否则会出现「拿上一张图的深度 warp 这一张图」的鬼影。
     */
    fun setDepth(bitmap: Bitmap, serial: Long): Boolean =
        libraryAvailable && runCatching { nativeSetDepth(bitmap, serial) }.getOrDefault(false)

    fun setParams(params: VrRenderParams) {
        if (!libraryAvailable) return
        runCatching {
            nativeSetParams(
                params.distanceMeters,
                params.halfWidthDegrees,
                params.popMeters,
                params.verticalOffsetMeters,
                params.useDepth,
                params.headLocked,
                params.invertParallax,
            )
        }
    }

    /** 世界锁定时把屏幕挪回当前视线正前方。 */
    fun recenter() {
        if (libraryAvailable) runCatching { nativeRecenter() }
    }

    fun stats(): VrStats {
        if (!libraryAvailable) return VrStats()
        val out = FloatArray(7)
        runCatching { nativeStats(out) }
        return VrStats(
            fps = out[0].toDouble(),
            viewCount = out[1].toInt(),
            width = out[2].toInt(),
            height = out[3].toInt(),
            activeSerial = out[4].toLong(),
            trackingValid = out[5] > 0.5f,
            haveDepth = out[6] > 0.5f,
        )
    }

    fun lastError(): String {
        loadFailure?.let { return it }
        if (!libraryAvailable) return ""
        return runCatching { nativeLastError() }.getOrDefault("")
    }

    private external fun nativeStart(assetManager: AssetManager): Boolean
    private external fun nativeStop()
    private external fun nativeIsReady(): Boolean
    private external fun nativeSetPage(bitmap: Bitmap, serial: Long): Boolean
    private external fun nativeSetDepth(bitmap: Bitmap, serial: Long): Boolean
    private external fun nativeSetParams(
        distance: Float,
        halfWidthDegrees: Float,
        pop: Float,
        verticalOffset: Float,
        useDepth: Boolean,
        headLocked: Boolean,
        invertParallax: Boolean,
    )

    private external fun nativeRecenter()
    private external fun nativeStats(out: FloatArray)
    private external fun nativeLastError(): String
}

/** 传给原生层的观感参数，全部是「虚拟屏幕」几何，与图源无关。 */
data class VrRenderParams(
    /** 虚拟屏幕到头部锚点的距离，米。 */
    val distanceMeters: Float = 2.0f,
    /** 屏幕水平张角的一半，度。半高按页面宽高比推出，所以换页不会改变画面大小。 */
    val halfWidthDegrees: Float = 26f,
    /** 2D→3D 的出屏/入屏总位移，米。0 = 纯平面。 */
    val popMeters: Float = 0.28f,
    /** 屏幕在锚点里的高低偏移，米。 */
    val verticalOffsetMeters: Float = 0f,
    val useDepth: Boolean = true,
    /** true = 屏幕锁在头上；false = 锁在空间里，转头可以把屏幕甩出视野。 */
    val headLocked: Boolean = true,
    /** IPD 取反，用来当场验证个别 runtime 的左右眼符号。 */
    val invertParallax: Boolean = false,
)

data class VrStats(
    val fps: Double = 0.0,
    val viewCount: Int = 0,
    val width: Int = 0,
    val height: Int = 0,
    val activeSerial: Long = 0,
    val trackingValid: Boolean = false,
    val haveDepth: Boolean = false,
)
