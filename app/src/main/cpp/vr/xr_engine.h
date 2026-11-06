// SY --> MihonVR：OpenXR 双目渲染引擎（Pico Neo 3 等 OpenXR 头显）
//
// 只做三件事：
//   1. 把 OpenXR 实例/会话/交换链生命周期收在一处，渲染线程独占 GL 上下文；
//   2. 把 Kotlin 送来的「页面 RGBA + 深度 R8」两张图上传成纹理；
//   3. 每帧按头显给的两只眼睛的位姿，把虚拟屏幕做深度位移后 warp 出去。
//
// 刻意不做的事：不碰 mihon 的 PageLoader / Viewer。那边通过 vr_jni.cpp 的几个
// native 方法单向喂数据进来，VR 层出错也不会把阅读器带崩。
#pragma once

#include <atomic>
#include <chrono>
#include <cstdint>
#include <mutex>
#include <string>
#include <thread>
#include <vector>

// CMake 也定义了这两个宏，这里兜底给不走 CMake 的包含方（比如将来的独立测试程序）。
// 必须用 #ifndef：同一份定义给两遍，-Wall 下每个编译单元都会刷两条 warning。
#ifndef XR_USE_PLATFORM_ANDROID
#define XR_USE_PLATFORM_ANDROID
#endif
#ifndef XR_USE_GRAPHICS_API_OPENGL_ES
#define XR_USE_GRAPHICS_API_OPENGL_ES
#endif
#include <EGL/egl.h>
#include <EGL/eglext.h>
#include <GLES3/gl3.h>
#include <android/asset_manager.h>
// openxr_platform.h 在 XR_USE_PLATFORM_ANDROID 下会声明带 jobject 的函数指针类型，
// 它自己不包含 jni.h，顺序错了就是 "unknown type name 'jobject'"。
#include <jni.h>
#include <openxr/openxr.h>
#include <openxr/openxr_platform.h>

namespace mihonvr {

/// 一次页面更新的载荷。`pixels` 是紧凑的行优先数据，`channels` 为 4（RGBA 页面）
/// 或 1（R8 深度图）。Kotlin 线程只负责填这个结构，GL 纹理在渲染线程创建。
struct TexturePayload {
    int32_t width = 0;
    int32_t height = 0;
    int32_t channels = 0;
    /// 单调递增的页号，用来让 Kotlin 侧确认「这一页真的上屏了」。
    int64_t serial = 0;
    std::vector<uint8_t> pixels;

    [[nodiscard]] bool valid() const {
        return width > 0 && height > 0 && (channels == 1 || channels == 4) &&
               pixels.size() >= static_cast<size_t>(width) * height * channels;
    }
};

/// 可调的观感参数。全部是「虚拟屏幕」几何 + warp 强度，不含任何图源信息。
struct RenderParams {
    /// 虚拟屏幕到头部锚点的距离，米。
    float distance = 2.0f;
    /// 虚拟屏幕水平张角的一半，度。高度按页面宽高比自动推出，所以换页不会改变画面大小。
    float halfWidthDegrees = 26.0f;
    /// 2D→3D 出屏/入屏总位移量，米。0 等于纯平面。
    float pop = 0.28f;
    /// 屏幕在锚点里的高低偏移，米（负=往下，读漫画一般想让它落在视线略下方）。
    float verticalOffset = 0.0f;
    /// 深度图缺失时按平面渲染。
    bool useDepth = true;
    /// true：屏幕锁在头上（转头画面跟着走，读数不丢）；false：锁在空间里。
    bool headLocked = true;
    /// 把 IPD 取反。部分 runtime 的左右眼位姿符号与规范相反，用这个当场验证。
    bool invertParallax = false;
};

struct FrameStats {
    double fps = 0.0;
    int32_t viewCount = 0;
    int32_t width = 0;
    int32_t height = 0;
    int64_t activeSerial = 0;
    bool trackingValid = false;
    bool haveDepth = false;
};

class XrEngine {
public:
    XrEngine();
    ~XrEngine();

    XrEngine(const XrEngine&) = delete;
    XrEngine& operator=(const XrEngine&) = delete;

    /// 非阻塞：起渲染线程并等它把会话拉到 READY。失败时 `error` 带回可显示的原因。
    bool start(AAssetManager* assetManager, std::string& error);
    void stop();

    [[nodiscard]] bool isRunning() const { return running_.load(); }
    [[nodiscard]] bool sessionReady() const { return sessionReady_.load(); }

    /// 以下从任意线程调用：内部只做一次拷贝 + 置脏，真正的 GL 上传在渲染线程。
    void submitPage(TexturePayload rgba);
    void submitDepth(TexturePayload r8);
    void setParams(const RenderParams& params);
    /// 世界锁定时把屏幕重新摆回当前视线正前方。
    void recenter();

    FrameStats stats() const;
    /// 渲染线程写、Kotlin 读的最近一次失败原因（含 GL/XR 报错）。空表示正常。
    std::string lastError() const;
    /// JNI 层在还没到渲染线程就挡下来的错误（比如位图格式不对）也写到这里，
    /// 让界面上的错误框能看到完整原因。
    void setError(const std::string& message);

private:
    void renderLoop();
    // --- OpenXR 生命周期 ---
    bool createInstance(std::string& error);
    bool createSystem(std::string& error);
    bool createSession(std::string& error);
    bool createReferenceSpace(std::string& error);
    bool createViewSwapchains(std::string& error);
    void destroySwapchains();
    void teardown();
    void pollEvents();
    void onSessionStateChanged(XrSessionState state);

    // --- GL ---
    bool initGl(std::string& error);
    void releaseGl();
    bool buildProgram(std::string& error);
    void uploadIfDirty();
    /// 把一只眼睛的画面画进它自己的交换链图像。返回 false 表示这一帧没画成。
    bool renderEye(size_t eyeIndex, uint32_t imageIndex, const XrPosef& anchor,
                   const RenderParams& params);

    [[nodiscard]] std::string xrResultString(XrResult result) const;

    // OpenXR
    XrInstance instance_ = XR_NULL_HANDLE;
    XrSystemId systemId_ = XR_NULL_SYSTEM_ID;
    XrSession session_ = XR_NULL_HANDLE;
    XrSpace space_ = XR_NULL_HANDLE;
    XrSessionState xrState_ = XR_SESSION_STATE_UNKNOWN;
    XrViewConfigurationType viewConfig_ = XR_VIEW_CONFIGURATION_TYPE_PRIMARY_STEREO;
    std::vector<XrViewConfigurationView> viewViews_;
    std::vector<XrSwapchain> viewSwapchains_;
    std::vector<std::vector<XrSwapchainImageOpenGLESKHR>> viewImages_;
    std::vector<XrView> views_;
    std::vector<XrCompositionLayerProjectionView> layerViews_;
    PFN_xrGetOpenGLESGraphicsRequirementsKHR getGlRequirements_ = nullptr;

    // EGL / GL
    EGLDisplay eglDisplay_ = EGL_NO_DISPLAY;
    EGLConfig eglConfig_ = nullptr;
    EGLContext eglContext_ = EGL_NO_CONTEXT;
    EGLSurface eglPbuffer_ = EGL_NO_SURFACE;
    GLuint program_ = 0;
    GLuint vao_ = 0;
    GLuint vbo_ = 0;
    GLuint fbo_ = 0;
    GLuint colorTex_ = 0;
    GLuint depthTex_ = 0;
    bool haveDepthTex_ = false;
    GLint uColor_ = -1;
    GLint uDepth_ = -1;
    GLint uFov_ = -1;
    GLint uEyePos_ = -1;
    GLint uEyeRot_ = -1;
    GLint uHalf_ = -1;
    GLint uQuadHalf_ = -1;
    GLint uCenter_ = -1;
    GLint uProj_ = -1;
    GLint uPop_ = -1;
    GLint uUseDepth_ = -1;
    GLint uImageSize_ = -1;

    // 线程与共享状态
    std::thread thread_;
    std::atomic<bool> running_{ false };
    std::atomic<bool> sessionReady_{ false };
    std::atomic<bool> recenterRequested_{ false };

    // `FrameStats` 有 40 字节，32 位下 atomic 走的是 libatomic 的自旋锁；
    // 读写都不在热路径上，直接上 mutex 更明确。
    mutable std::mutex statsMutex_;
    FrameStats stats_;

    mutable std::mutex texMutex_;
    TexturePayload pendingColor_;
    TexturePayload pendingDepth_;
    bool colorDirty_ = false;
    bool depthDirty_ = false;
    int64_t activeSerial_ = 0;
    int32_t colorW_ = 0;
    int32_t colorH_ = 0;

    mutable std::mutex paramMutex_;
    RenderParams params_;

    mutable std::mutex errorMutex_;
    std::string lastError_;

    // 只在渲染线程访问
    AAssetManager* assetManager_ = nullptr;
    XrPosef anchorPose_{};
    bool anchorCaptured_ = false;
};

}  // namespace mihonvr
