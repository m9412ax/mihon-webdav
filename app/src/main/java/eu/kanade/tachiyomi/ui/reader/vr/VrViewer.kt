package eu.kanade.tachiyomi.ui.reader.vr

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.ui.reader.ReaderActivity
import eu.kanade.tachiyomi.ui.reader.model.ReaderChapter
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import eu.kanade.tachiyomi.ui.reader.model.ViewerChapters
import eu.kanade.tachiyomi.ui.reader.viewer.Viewer
import eu.kanade.tachiyomi.util.vr.VrDepthDiskCache
import eu.kanade.tachiyomi.util.vr.VrDepthEstimator
import eu.kanade.tachiyomi.util.vr.VrDepthModel
import eu.kanade.tachiyomi.util.vr.VrDepthModelStore
import eu.kanade.tachiyomi.util.vr.VrStorage
import eu.kanade.tachiyomi.util.vr.toAlphaBitmap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import logcat.LogPriority
import mihon.app.di.appGraph
import tachiyomi.core.common.util.system.logcat
import java.io.InputStream
import java.util.PriorityQueue

/**
 * SY --> 头显（OpenXR）阅读器。
 *
 * 画面不走这个 Activity 的窗口：真正出图的是原生层提交给 OpenXR 合成器的投影层。
 * [getView] 返回的视图只是「VR 没起来时给人看原因」的落点，以及在手机/桌面上当调试面板。
 *
 * 和另外两个 viewer 最大的区别是这里必须自己管解码：PagerViewer/WebtoonViewer 把位图
 * 交给 holder，靠视图回收限内存；VR 没有视图树，所以「预读几张、每张多大、什么时候丢」
 * 全部由 [cache] 显式控制。
 *
 * 这就是 Thru3D 那类播放器做不好的地方：它发起预读但不确认落地，于是换页时仍要重新解码。
 * 这里每页带一个 serial，原生层只在其 serial 成为当前页时才采用对应深度，
 * 而且 [pollStats] 会把「原生现在放的 serial」读回来，能直接看出预读有没有生效。
 */
class VrViewer(private val activity: ReaderActivity) : Viewer {

    private val graph by lazy { activity.appGraph }
    private val readerPreferences get() = graph.readerPreferences

    private val mainScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val workerScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // 解码在 worker 线程上跑，翻页在主线程上发生，下面这几个字段两边都会读，
    // 所以必须 @Volatile：否则 worker 可能拿着旧的 currentPage 把已经翻走的那一页上传上去。
    @Volatile private var pages: List<ReaderPage> = emptyList()
    @Volatile private var currentPage: ReaderPage? = null
    @Volatile private var deliveredSerial = 0L
    private var currentChapter: ReaderChapter? = null
    private var nextChapter: ReaderChapter? = null

    /** 页号 → 已解码结果。accessOrder=true ⇒ 迭代顺序就是最近使用顺序，淘汰才有意义。 */
    private val cache = object : LinkedHashMap<Int, VrPage>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, VrPage>?): Boolean =
            size > cacheCapacity()
    }
    private var cachedBytes = 0L

    private val pending = PriorityQueue<DecodeJob>(compareBy { it.distance })

    /**
     * 深度任务队列。FIFO，但当前页会被 [showPage] 插到队首——用户正看着这一页，
     * 不该排在几张预读图后面等好几秒。
     */
    private val pendingDepth = ArrayDeque<Pair<ReaderPage, VrPage>>()
    private var workerRunning = false
    private var serialCounter = 0L

    // 深度这条链路只在 vrDepthEnabled 时才真正干活，但对象本身一直存在：
    // 开关一打开就要能立刻用，不该在渲染线程上等一次磁盘扫描。
    private val modelStore = VrDepthModelStore(VrStorage.modelRoot(activity))
    private val depthCache = VrDepthDiskCache(
        VrStorage.depthCacheRoot(activity),
        readerPreferences.vrDepthCacheMb.get() * 1024L * 1024L,
    )
    private var estimator: VrDepthEstimator? = null
    private var estimatorModelId: String? = null

    private val rootView: FrameLayout
    private val statusText: TextView

    /** 扳机是模拟轴，只有连续的压力值，靠它做边沿检测 + 冷却，否则按住一次能翻十几页。 */
    private var triggerWasDown = false
    private var lastStepAt = 0L

    init {
        rootView = FrameLayout(activity).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
            setBackgroundColor(Color.BLACK)
        }
        statusText = TextView(activity).apply {
            setTextColor(Color.parseColor("#B8B8C0"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            gravity = Gravity.CENTER
            setPadding(48, 48, 48, 48)
            setOnClickListener { startSession() }
        }
        rootView.addView(
            statusText,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER,
            ),
        )
        startSession()
        observePreferences()
        pollStats()
    }

    // region 会话与参数

    private fun startSession() {
        if (!VrBridge.libraryAvailable) {
            renderStatus("原生库不可用：${VrBridge.lastError()}")
            return
        }
        if (!VrBridge.start(activity.assets)) {
            renderStatus(VrBridge.lastError().ifBlank { "OpenXR 会话未能建立" })
            return
        }
        applyParams()
        currentPage?.let { showPage(it) } ?: renderStatus("VR 会话已就绪，等待页面…")
    }

    private fun applyParams() {
        val prefs = readerPreferences
        val depthOn = prefs.vrDepthEnabled.get()
        VrBridge.setParams(
            VrRenderParams(
                distanceMeters = prefs.vrDistanceMeters.get(),
                halfWidthDegrees = prefs.vrScreenWidthDegrees.get(),
                popMeters = if (depthOn) prefs.vrDepthStrengthMeters.get() else 0f,
                verticalOffsetMeters = prefs.vrVerticalOffsetMeters.get(),
                useDepth = depthOn,
                headLocked = prefs.vrHeadLocked.get(),
                invertParallax = prefs.vrInvertParallax.get(),
            ),
        )
    }

    private fun observePreferences() {
        val prefs = readerPreferences
        val flows: List<Flow<*>> = listOf(
            prefs.vrDistanceMeters.changes(),
            prefs.vrScreenWidthDegrees.changes(),
            prefs.vrDepthStrengthMeters.changes(),
            prefs.vrVerticalOffsetMeters.changes(),
            prefs.vrDepthEnabled.changes(),
            prefs.vrHeadLocked.changes(),
            prefs.vrInvertParallax.changes(),
        )
        flows.forEach { flow -> mainScope.launch { flow.collect { applyParams() } } }
    }

    private fun pollStats() {
        mainScope.launch {
            while (isActive) {
                delay(1000)
                if (activity.isFinishing || activity.isDestroyed) return@launch
                val s = VrBridge.stats()
                val state = when {
                    !s.trackingValid -> "追踪丢失"
                    s.activeSerial == deliveredSerial && deliveredSerial != 0L -> "已出图"
                    else -> "等解码"
                }
                renderStatus(
                    "VR %dx%d · %.0ffps · %s · 缓存 %d 页 · %s".format(
                        s.width, s.height, s.fps, state, cache.size,
                        currentPage?.let { "第 ${it.number} 页" } ?: "无页",
                    ),
                )
            }
        }
    }

    private fun renderStatus(text: String) {
        // [deliver] 在 worker 线程上也会走到这里，所以先回主线程再碰 View。
        if (Looper.myLooper() != Looper.getMainLooper()) {
            statusText.post { renderStatus(text) }
            return
        }
        statusText.text = text
        // 会话正常时头显里看到的是合成器的投影层，这块文字只在 2D 镜像窗口里可见；
        // 一旦真出错，它就是用户唯一能读到的诊断信息，所以不能提前藏掉。
        statusText.visibility = if (VrBridge.isReady() && deliveredSerial != 0L) {
            View.INVISIBLE
        } else {
            View.VISIBLE
        }
    }

    // endregion

    // region Viewer 接口

    override fun getView(): View = rootView

    override fun destroy() {
        workerScope.cancel()
        mainScope.cancel()
        synchronized(pending) { pending.clear() }
        synchronized(pendingDepth) { pendingDepth.clear() }
        synchronized(cache) {
            cache.values.forEach { it.release() }
            cache.clear()
            cachedBytes = 0
        }
        VrBridge.stop()
        // ONNX 会话持有整份权重常驻内存（int8 small 也要 30MB 左右），
        // 退出阅读器时必须关掉，否则换一本书就叠加一次。
        runCatching { estimator?.close() }
        estimator = null
        estimatorModelId = null
        pages = emptyList()
        currentPage = null
        currentChapter = null
        nextChapter = null
    }

    override fun setChapters(chapters: ViewerChapters) {
        nextChapter = chapters.nextChapter
        val newPages = chapters.currChapter.pages.orEmpty()
        // 用 ReaderChapter 实例本身判同章，不去碰 ReaderPage.chapter：它是 lateinit，
        // 页列表刚建好、还没回填章节时访问会直接抛。
        val sameChapter = currentChapter === chapters.currChapter
        currentChapter = chapters.currChapter
        if (!sameChapter) {
            synchronized(pending) { pending.clear() }
        synchronized(pendingDepth) { pendingDepth.clear() }
            synchronized(cache) {
                cache.values.forEach { it.release() }
                cache.clear()
                cachedBytes = 0
            }
            deliveredSerial = 0L
            currentPage = null
        }
        pages = newPages
        logcat(LogPriority.INFO) { "VrViewer.setChapters: ${pages.size} 页（同章=$sameChapter）" }
        // ReaderActivity 的顺序是 setChapters → moveToPage，所以正常路径这里不该跳页；
        // 但「同一话被重载」（改双页设置等）之后不会再有 moveToPage，得自己接住。
        val keep = currentPage?.takeIf { cur -> newPages.any { it.index == cur.index } }
        val target = keep ?: newPages.getOrNull(chapters.currChapter.requestedPage)
        if (target != null && target !== currentPage) {
            showPage(target)
        } else if (pages.isEmpty()) {
            renderStatus("这一话还没有页")
        }
    }

    override fun moveToPage(page: ReaderPage) {
        showPage(page)
    }

    // endregion

    // region 输入

    override fun handleKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_MULTIPLE) return false
        if (event.action != KeyEvent.ACTION_DOWN) return false
        val invert = readerPreferences.vrInvertButtons.get()
        val delta: Int = when (event.keyCode) {
            // 音量键在头显里就是手柄的肩键语义：上/左/肩左 = 上一页。
            KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_BUTTON_L1 ->
                if (invert) 1 else -1
            KeyEvent.KEYCODE_VOLUME_DOWN, KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_BUTTON_R1 ->
                if (invert) -1 else 1
            KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_ENTER ->
                if (invert) -1 else 1
            KeyEvent.KEYCODE_DPAD_UP -> if (invert) 1 else -1
            KeyEvent.KEYCODE_BUTTON_B -> {
                VrBridge.recenter()
                return true
            }
            KeyEvent.KEYCODE_MENU -> {
                activity.toggleMenu()
                return true
            }
            else -> return false
        }
        step(delta)
        return true
    }

    /**
     * 控制器的扳机不走 [handleKeyEvent]：它是模拟轴（AXIS_GAS / AXIS_BRAKE），
     * 只有连续的压力值，没有 key up/down。
     */
    override fun handleGenericMotionEvent(event: MotionEvent): Boolean {
        if (pages.isEmpty()) return false
        event.device?.let { device ->
            if (device.sources and (InputDevice.SOURCE_GAMEPAD or InputDevice.SOURCE_JOYSTICK) == 0) {
                return false
            }
        }
        val gas = event.getAxisValue(MotionEvent.AXIS_GAS)
        val brake = event.getAxisValue(MotionEvent.AXIS_BRAKE)
        val pressed = (gas > 0.5f || brake > 0.5f) && !triggerWasDown
        triggerWasDown = gas > 0.5f || brake > 0.5f
        if (!pressed) return false
        val forward = gas > 0.5f
        val invert = readerPreferences.vrInvertButtons.get()
        step(if (invert xor forward) -1 else 1)
        return true
    }

    /** 当前页位置 ±[delta]。越过本章边界时请求加载下一章，由随后的 setChapters 接住。 */
    private fun step(delta: Int) {
        if (pages.isEmpty()) return
        val now = System.currentTimeMillis()
        if (now - lastStepAt < readerPreferences.vrPageCooldownMillis.get()) return
        lastStepAt = now

        val position = currentPage?.let { cur ->
            pages.indexOfFirst { it === cur }.takeIf { it >= 0 } ?: pages.indexOfFirst { it.index == cur.index }
        } ?: -1
        if (position < 0) {
            pages.firstOrNull()?.let(::showPage)
            return
        }
        val targetPosition = position + delta
        if (targetPosition in pages.indices) {
            showPage(pages[targetPosition])
            return
        }
        if (delta > 0) {
            nextChapter?.let { activity.requestPreloadChapter(it) }
        }
    }

    // endregion

    // region 页面管道

    private fun showPage(page: ReaderPage) {
        currentPage = page
        activity.onPageSelected(page)
        val cached = synchronized(cache) { cache[page.index] }
        if (cached != null) {
            deliver(page, cached)
            // 缓存命中时没有人会再启动 worker（没有解码任务），所以这里得自己把
            // 缺失的深度插进队列并踢一脚，否则这一页会一直是平面的。
            if (cached.depthBitmap == null && readerPreferences.vrDepthEnabled.get()) {
                synchronized(pendingDepth) {
                    if (pendingDepth.none { it.first === page }) pendingDepth.addFirst(page to cached)
                }
                drainQueues()
            }
        } else {
            enqueue(page, distance = 0)
        }
        schedulePrefetch(page)
    }

    /** 只有深度变了的一帧：页面不动，避免十几 MB 的整页重传。 */
    private fun deliverDepth(vrPage: VrPage) {
        val depth = vrPage.depthBitmap ?: return
        VrBridge.setDepth(depth, vrPage.serial)
    }

    /** 只有「这还是用户要看的那一页」才下发，否则快速连翻时后完成的旧页会把新页盖掉。 */
    private fun deliver(page: ReaderPage, vrPage: VrPage) {
        if (currentPage?.index != page.index || vrPage.released) return
        // 深度先、页面后：原生层的深度只在 serial 追上当前页时才采用，
        // 所以这个顺序不会错帧，反过来则可能让新一帧配上旧深度。
        deliverDepth(vrPage)
        if (!VrBridge.setPage(vrPage.bitmap, vrPage.serial)) {
            renderStatus("页面上传失败：${VrBridge.lastError()}")
            return
        }
        deliveredSerial = vrPage.serial
        renderStatus("")
    }

    /**
     * 给这一页补深度图：先查磁盘缓存，没有再推理。
     *
     * 页面本身**不**等这一步——先按平面投出去，深度算好后再补一帧。SD835 上单帧推理要
     * 几百毫秒到几秒，让当前页等深度等于把「翻页立刻出图」这个最基本的体验赔进去。
     */
    private fun attachDepth(readerPage: ReaderPage, vrPage: VrPage): Boolean {
        if (!readerPreferences.vrDepthEnabled.get()) return false
        // 缓存命中时深度可能早就算好了，别再来一遍。
        if (vrPage.depthBitmap != null) return false
        val (estimator, model) = ensureEstimator() ?: return false
        // 缓存键里的模型必须就是真正在跑的那个。分开读偏好的话，用户在推理进行的
        // 空档换了模型，就会拿 A 的输出写进 B 键名的缓存里。
        val key = runCatching {
            val chapter = readerPage.chapter.chapter
            depthCache.keyFor(model, chapter.manga_id ?: 0L, chapter.url, readerPage.index)
        }.getOrNull() ?: return false

        val map = depthCache.read(key) ?: estimator.estimate(vrPage.bitmap)?.also {
            depthCache.write(key, it)
        } ?: return false
        if (vrPage.released) return false
        vrPage.attachDepth(map.toAlphaBitmap())
        return true
    }

    /**
     * 懒加载 ONNX 会话。模型没下载过就返回 null，界面据此退回纯平面显示。
     *
     * 换模型时必须先关掉旧的：一个 int8 small 会话要常驻 30 MB 左右权重，
     * 只把 [estimator] 换掉的话旧会话没人关，切几次模型就是几份内存。
     */
    private fun ensureEstimator(): Pair<VrDepthEstimator, VrDepthModel>? {
        val model = VrDepthModel.fromId(readerPreferences.vrDepthModel.get())
        estimator?.let { existing ->
            if (estimatorModelId == model.id) return existing to model
            runCatching { existing.close() }
            estimator = null
            estimatorModelId = null
        }
        val file = modelStore.localFile(model)?.takeIf { modelStore.isReady(model) } ?: return null
        val opened = runCatching { VrDepthEstimator(file, model) }
            .onFailure { logcat(LogPriority.ERROR, it) { "打开深度模型 ${model.id} 失败" } }
            .getOrNull() ?: return null
        estimator = opened
        estimatorModelId = model.id
        return opened to model
    }

    private fun schedulePrefetch(page: ReaderPage) {
        val count = readerPreferences.vrPrefetchPages.get().coerceAtLeast(0)
        if (count == 0) return
        val position = pages.indexOfFirst { it.index == page.index }
        if (position < 0) return
        for (offset in 1..count) {
            pages.getOrNull(position + offset)?.let { enqueue(it, offset) }
        }
        // 往回也留一张：往回翻是最常见的「翻错了」，只往前预读会在这里明显卡一下。
        pages.getOrNull(position - 1)?.let { enqueue(it, count + 1) }
    }

    private fun enqueue(page: ReaderPage, distance: Int) {
        val started = synchronized(pending) {
            if (synchronized(cache) { cache.containsKey(page.index) } ||
                pending.any { it.page === page }
            ) {
                false
            } else {
                pending.add(DecodeJob(page, distance))
                true
            }
        }
        if (started) drainQueues()
    }

    /**
     * 一条线程、两个队列：**解码永远优先于深度**。
     *
     * 深度单帧要几百毫秒到几秒，如果和「解下一页的图」串在同一条队列里按页交替，
     * 用户连翻几页时看到的会是一串平面 + 一张慢慢补上的深度，而不是提前备好的图。
     * 所以深度任务只在解码队列空了之后才拿出来跑，且随时可以被更紧急的解码抢回去
     * （每跑完一个深度任务就回到循环顶部重新比较）。
     */
    private fun drainQueues() {
        synchronized(this) {
            if (workerRunning) return
            workerRunning = true
        }
        workerScope.launch {
            while (isActive) {
                val job = synchronized(pending) { pending.poll() }
                if (job != null) {
                    // 排队期间用户可能已经翻走了，超出预读窗口的任务直接丢掉，
                    // 别让它去抢当前页的解码带宽。
                    val distance = currentPage?.let { absDistance(pages, it.index, job.page.index) }
                        ?: job.distance
                    if (distance == 0 || distance <= readerPreferences.vrPrefetchPages.get() + 1) {
                        val result = decode(job.page)
                        if (result != null) {
                            putCache(job.page.index, result)
                            deliver(job.page, result)
                            if (readerPreferences.vrDepthEnabled.get()) {
                                synchronized(pendingDepth) { pendingDepth.add(Pair(job.page, result)) }
                            }
                        }
                    }
                    continue
                }

                val depthJob = synchronized(pendingDepth) { pendingDepth.poll() } ?: break
                val (page, vrPage) = depthJob
                // 这页已经被淘汰、或用户早翻走了并且它也不在预读窗口里，就别再花几秒算它。
                if (vrPage.released || vrPage.depthBitmap != null) continue
                if (attachDepth(page, vrPage)) deliverDepth(vrPage)
            }
            synchronized(this@VrViewer) { workerRunning = false }
        }
    }

    private suspend fun decode(page: ReaderPage): VrPage? {
        if (page.status != Page.State.Ready) {
            page.chapter.pageLoader?.loadPage(page)
            // 没有超时的话，一个卡住的下载会把整条预读队列永久堵死。
            val settled = withTimeoutOrNull(PAGE_LOAD_TIMEOUT_MS) {
                page.statusFlow.first { it == Page.State.Ready || it is Page.State.Error }
            }
            if (settled != Page.State.Ready) return null
        }
        val streamFactory = page.stream ?: return null
        return withContext(Dispatchers.Default) {
            val bitmap = decodeScaled(streamFactory, readerPreferences.vrMaxEdgePixels.get())
                ?: return@withContext null
            VrPage(serial = ++serialCounter, bitmap = bitmap)
        }
    }

    /**
     * 两次解码：先量尺寸定 inSampleSize，再按目标边长解出真图。
     *
     * 必须限尺寸：源站一页可能是 2500x3600，而这块虚拟屏幕在头显里实际只占每眼一千多
     * 像素宽。不限的话每页 36MB，预读几张就把 Neo 3 的 4GB 吃掉一大块，而且每次 JNI
     * 拷贝和 glTexImage2D 都按这个尺寸付一遍时间。
     */
    private fun decodeScaled(streamFactory: () -> InputStream, maxEdge: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        runCatching { streamFactory().use { BitmapFactory.decodeStream(it, null, bounds) } }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val options = BitmapFactory.Options().apply {
            inSampleSize = computeInSampleSize(bounds.outWidth, bounds.outHeight, maxEdge)
            // 原生层只认 RGBA_8888：RGB_565 省下的那一半内存，抵不过它带来的色带，
            // 而且还得再转一次格式才能上传。
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        return runCatching {
            streamFactory().use { BitmapFactory.decodeStream(it, null, options) }
        }.getOrNull()
    }

    private fun putCache(index: Int, page: VrPage) {
        synchronized(cache) {
            cache.put(index, page)?.let { cachedBytes -= it.estimateBytes() }
            cachedBytes += page.estimateBytes()
            // removeEldestEntry 只按张数淘汰，这里再按字节兜一层：几张特别大的图
            // 就足以越过张数上限把内存吃掉。当前页永远不淘汰，否则会被自己抹掉。
            val budget = readerPreferences.vrCacheMemoryLimitMb.get() * 1024L * 1024L
            val iterator = cache.entries.iterator()
            while (cachedBytes > budget && iterator.hasNext()) {
                val eldest = iterator.next()
                if (eldest.key == currentPage?.index) continue
                cachedBytes -= eldest.value.estimateBytes()
                eldest.value.release()
                iterator.remove()
            }
        }
    }

    private fun cacheCapacity(): Int = readerPreferences.vrPrefetchPages.get().coerceAtLeast(0) * 2 + 2

    private class DecodeJob(val page: ReaderPage, val distance: Int)
}

/** 一页在 VR 侧的全部产物。深度是后补的：先出平面，算完再补一帧。 */
private class VrPage(
    val serial: Long,
    val bitmap: Bitmap,
) {
    @Volatile var depthBitmap: Bitmap? = null
        private set

    var released = false
        private set

    fun attachDepth(depth: Bitmap) {
        if (released) {
            runCatching { depth.recycle() }
            return
        }
        depthBitmap?.let { runCatching { it.recycle() } }
        depthBitmap = depth
    }

    fun estimateBytes(): Long =
        bitmap.allocationByteCount.toLong() + (depthBitmap?.allocationByteCount?.toLong() ?: 0L)

    fun release() {
        if (released) return
        released = true
        runCatching {
            if (!bitmap.isRecycled) bitmap.recycle()
            depthBitmap?.let { if (!it.isRecycled) it.recycle() }
        }
        depthBitmap = null
    }
}

/** 采样率取 2 的幂，且缩完长边仍不低于 [maxEdge]（BitmapFactory 只会按 1/2、1/4… 缩）。 */
internal fun computeInSampleSize(width: Int, height: Int, maxEdge: Int): Int {
    if (maxEdge <= 0) return 1
    var sample = 1
    while (maxOf(width, height) / (sample * 2) >= maxEdge) sample *= 2
    return sample
}

/** 双页模式下 index 会重复，所以「隔了几页」要按列表位置算，不能直接相减。 */
private fun absDistance(pages: List<ReaderPage>, fromIndex: Int, toIndex: Int): Int? {
    val a = pages.indexOfFirst { it.index == fromIndex }
    val b = pages.indexOfFirst { it.index == toIndex }
    if (a < 0 || b < 0) return null
    return kotlin.math.abs(a - b)
}

private const val PAGE_LOAD_TIMEOUT_MS = 30_000L
