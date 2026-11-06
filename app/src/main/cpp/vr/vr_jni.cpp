// SY --> MihonVR 的 JNI 边界。
//
// 对应 Kotlin：eu.kanade.tachiyomi.ui.reader.vr.VrBridge
//
// 这一层刻意做得很薄：只做 Bitmap → TexturePayload 的搬运和参数下发，
// 不含任何阅读逻辑。页面位图解码、预读、深度推理都在 Kotlin 侧，
// 这样原生层出问题最多是黑屏，不会把阅读器整个带崩。
#include <jni.h>

#include <memory>
#include <mutex>
#include <string>
#include <vector>

#include <android/log.h>
#include <android/asset_manager.h>
#include <android/asset_manager_jni.h>
#include <android/bitmap.h>

#include "xr_engine.h"

#define LOG_TAG "MihonVR"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {

std::mutex g_engineMutex;
std::shared_ptr<mihonvr::XrEngine> g_engine;

/// 取引擎的强引用：nativeStop 只会摘掉全局指针，正在用它的调用要等它自己返回，
/// 否则 stop 和 setDepth 撞上就是 use-after-free。
std::shared_ptr<mihonvr::XrEngine> acquireEngine() {
    std::lock_guard<std::mutex> lk(g_engineMutex);
    return g_engine;
}

jstring toJString(JNIEnv* env, const std::string& text) {
    return env->NewStringUTF(text.c_str());
}

/// 把 Bitmap 拷成一次上传用的载荷。返回 false 时 `error` 里有能直接显示的原因。
bool payloadFromBitmap(AndroidBitmapInfo info, const void* pixels, int wantedChannels,
                       mihonvr::TexturePayload& out, std::string& error) {
    if (info.width == 0 || info.height == 0) {
        error = "位图尺寸为 0";
        return false;
    }
    const size_t w = info.width;
    const size_t h = info.height;
    const size_t stride = info.stride;
    const auto* src = static_cast<const uint8_t*>(pixels);
    out.width = static_cast<int32_t>(w);
    out.height = static_cast<int32_t>(h);

    if (wantedChannels == 4) {
        if (info.format != ANDROID_BITMAP_FORMAT_RGBA_8888) {
            error = "页面必须是 ARGB_8888，实际 format=" + std::to_string(info.format);
            return false;
        }
        out.channels = 4;
        out.pixels.resize(w * h * 4);
        // Bitmap 的像素可能在 graphics memory 里，而且 unlockPixels 之后指针就失效了，
        // 所以必须整块拷出来交给渲染线程。按 stride 逐行拷，不假设行是紧排的。
        for (size_t y = 0; y < h; ++y) {
            std::memcpy(out.pixels.data() + y * w * 4, src + y * stride, w * 4);
        }
        return true;
    }

    // 深度：优先 A_8（省一半内存、也没有「深度藏在哪个通道」的歧义）；
    // 同时接受 ARGB_8888 取红通道，方便直接把图像模型输出的灰度图喂进来调试。
    if (info.format == ANDROID_BITMAP_FORMAT_A_8) {
        out.channels = 1;
        out.pixels.resize(w * h);
        for (size_t y = 0; y < h; ++y) {
            std::memcpy(out.pixels.data() + y * w, src + y * stride, w);
        }
        return true;
    }
    if (info.format == ANDROID_BITMAP_FORMAT_RGBA_8888) {
        out.channels = 1;
        out.pixels.resize(w * h);
        for (size_t y = 0; y < h; ++y) {
            const uint8_t* row = src + y * stride;
            for (size_t x = 0; x < w; ++x) out.pixels[y * w + x] = row[x * 4];
        }
        return true;
    }
    error = "深度必须是 ALPHA_8 或 ARGB_8888，实际 format=" + std::to_string(info.format);
    return false;
}

/// 从 Java Bitmap 走一遍 lock/unlock 的公共流程。
bool withLockedBitmap(JNIEnv* env, jobject bitmap, int wantedChannels,
                      mihonvr::TexturePayload& out, std::string& error) {
    if (bitmap == nullptr) {
        error = "位图为空";
        return false;
    }
    AndroidBitmapInfo info{};
    if (AndroidBitmap_getInfo(env, bitmap, &info) != ANDROID_BITMAP_RESULT_SUCCESS) {
        error = "AndroidBitmap_getInfo 失败";
        return false;
    }
    void* pixels = nullptr;
    if (AndroidBitmap_lockPixels(env, bitmap, &pixels) != ANDROID_BITMAP_RESULT_SUCCESS ||
        pixels == nullptr) {
        error = "AndroidBitmap_lockPixels 失败（位图可能已被回收）";
        return false;
    }
    const bool ok = payloadFromBitmap(info, pixels, wantedChannels, out, error);
    AndroidBitmap_unlockPixels(env, bitmap);
    return ok;
}

}  // namespace

extern "C" {

JNIEXPORT jboolean JNICALL Java_eu_kanade_tachiyomi_ui_reader_vr_VrBridge_nativeStart(JNIEnv* env,
                                                                                       jobject,
                                                                                       jobject assetManager) {
    std::lock_guard<std::mutex> lk(g_engineMutex);
    if (g_engine != nullptr && g_engine->isRunning()) {
        LOGI("VR 引擎已经在跑，忽略重复 start");
        return JNI_TRUE;
    }
    AAssetManager* am = assetManager != nullptr ? AAssetManager_fromJava(env, assetManager) : nullptr;
    auto engine = std::make_shared<mihonvr::XrEngine>();
    std::string error;
    const bool ok = engine->start(am, error);
    if (!ok) {
        // start 内部已经把线程收掉了，这里只把原因留在引擎对象里给 Kotlin 读。
        LOGE("启动失败: %s", error.c_str());
        g_engine = engine;  // 保留实例，让 nativeLastError 能取到原因
        return JNI_FALSE;
    }
    g_engine = engine;
    return JNI_TRUE;
}

JNIEXPORT void JNICALL Java_eu_kanade_tachiyomi_ui_reader_vr_VrBridge_nativeStop(JNIEnv*, jobject) {
    std::shared_ptr<mihonvr::XrEngine> engine;
    {
        std::lock_guard<std::mutex> lk(g_engineMutex);
        engine.swap(g_engine);
    }
    if (engine != nullptr) engine->stop();
}

JNIEXPORT jboolean JNICALL Java_eu_kanade_tachiyomi_ui_reader_vr_VrBridge_nativeIsReady(JNIEnv*,
                                                                                         jobject) {
    auto engine = acquireEngine();
    return (engine != nullptr && engine->sessionReady()) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL Java_eu_kanade_tachiyomi_ui_reader_vr_VrBridge_nativeSetPage(
    JNIEnv* env, jobject, jobject bitmap, jlong serial) {
    auto engine = acquireEngine();
    if (engine == nullptr) return JNI_FALSE;
    mihonvr::TexturePayload payload;
    std::string error;
    if (!withLockedBitmap(env, bitmap, 4, payload, error)) {
        LOGE("页面上传被拒: %s", error.c_str());
        engine->setError(error);
        return JNI_FALSE;
    }
    payload.serial = serial;
    engine->submitPage(std::move(payload));
    return JNI_TRUE;
}

JNIEXPORT jboolean JNICALL Java_eu_kanade_tachiyomi_ui_reader_vr_VrBridge_nativeSetDepth(
    JNIEnv* env, jobject, jobject bitmap, jlong serial) {
    auto engine = acquireEngine();
    if (engine == nullptr) return JNI_FALSE;
    mihonvr::TexturePayload payload;
    std::string error;
    if (!withLockedBitmap(env, bitmap, 1, payload, error)) {
        LOGE("深度上传被拒: %s", error.c_str());
        engine->setError(error);
        return JNI_FALSE;
    }
    // 深度必须带上它属于哪一页：渲染线程只在 serial 与当前页一致时才换深度图，
    // 否则会出现「上一张图的深度 warp 这一张图」的鬼影。
    payload.serial = serial;
    engine->submitDepth(std::move(payload));
    return JNI_TRUE;
}

JNIEXPORT void JNICALL Java_eu_kanade_tachiyomi_ui_reader_vr_VrBridge_nativeSetParams(
    JNIEnv*, jobject, jfloat distance, jfloat halfWidthDegrees, jfloat pop, jfloat verticalOffset,
    jboolean useDepth, jboolean headLocked, jboolean invertParallax) {
    auto engine = acquireEngine();
    if (engine == nullptr) return;
    mihonvr::RenderParams params;
    params.distance = distance;
    params.halfWidthDegrees = halfWidthDegrees;
    params.pop = pop;
    params.verticalOffset = verticalOffset;
    params.useDepth = useDepth == JNI_TRUE;
    params.headLocked = headLocked == JNI_TRUE;
    params.invertParallax = invertParallax == JNI_TRUE;
    engine->setParams(params);
}

JNIEXPORT void JNICALL Java_eu_kanade_tachiyomi_ui_reader_vr_VrBridge_nativeRecenter(JNIEnv*,
                                                                                      jobject) {
    auto engine = acquireEngine();
    if (engine != nullptr) engine->recenter();
}

/// 把运行状况一次写进调用方给的数组，省得每帧跨 JNI 调用十几次。
/// [0]=fps [1]=视图数 [2]=宽 [3]=高 [4]=已上屏 serial [5]=追踪有效 [6]=有深度
JNIEXPORT void JNICALL Java_eu_kanade_tachiyomi_ui_reader_vr_VrBridge_nativeStats(JNIEnv* env,
                                                                                  jobject,
                                                                                  jfloatArray out) {
    auto engine = acquireEngine();
    if (engine == nullptr || out == nullptr || env->GetArrayLength(out) < 7) return;
    const mihonvr::FrameStats s = engine->stats();
    float values[7] = {
        static_cast<float>(s.fps),
        static_cast<float>(s.viewCount),
        static_cast<float>(s.width),
        static_cast<float>(s.height),
        static_cast<float>(s.activeSerial),
        s.trackingValid ? 1.0f : 0.0f,
        s.haveDepth ? 1.0f : 0.0f,
    };
    env->SetFloatArrayRegion(out, 0, 7, values);
}

JNIEXPORT jstring JNICALL Java_eu_kanade_tachiyomi_ui_reader_vr_VrBridge_nativeLastError(JNIEnv* env,
                                                                                         jobject) {
    auto engine = acquireEngine();
    if (engine == nullptr) return toJString(env, "");
    return toJString(env, engine->lastError());
}

}  // extern "C"
