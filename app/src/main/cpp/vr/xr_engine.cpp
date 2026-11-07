// SY --> MihonVR：OpenXR + GLES 双目渲染实现
#include "xr_engine.h"

#include <cmath>
#include <cstring>

#include <android/log.h>

#include "shaders.h"

#define LOG_TAG "MihonVR"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {

using mihonvr::RenderParams;
using mihonvr::TexturePayload;

struct Quat {
    float x = 0, y = 0, z = 0, w = 1;
};

struct Vec3 {
    float x = 0, y = 0, z = 0;
};

Vec3 operator-(const Vec3& a, const Vec3& b) { return { a.x - b.x, a.y - b.y, a.z - b.z }; }
Vec3 operator+(const Vec3& a, const Vec3& b) { return { a.x + b.x, a.y + b.y, a.z + b.z }; }
Vec3 operator*(const Vec3& a, float s) { return { a.x * s, a.y * s, a.z * s }; }

Vec3 toVec(const XrVector3f& v) { return { v.x, v.y, v.z }; }
XrVector3f toXr(const Vec3& v) { return { v.x, v.y, v.z }; }
Quat toQuat(const XrQuaternionf& q) { return { q.x, q.y, q.z, q.w }; }
XrQuaternionf toXr(const Quat& q) { return { q.x, q.y, q.z, q.w }; }

/// t = 2(q.xyz × v); v' = v + q.w·t + q.xyz × t
Vec3 rotate(const Quat& q, const Vec3& v) {
    const Vec3 u{ q.x, q.y, q.z };
    const Vec3 t{
        2.0f * (u.y * v.z - u.z * v.y),
        2.0f * (u.z * v.x - u.x * v.z),
        2.0f * (u.x * v.y - u.y * v.x),
    };
    return {
        v.x + q.w * t.x + (u.y * t.z - u.z * t.y),
        v.y + q.w * t.y + (u.z * t.x - u.x * t.z),
        v.z + q.w * t.z + (u.x * t.y - u.y * t.x),
    };
}

Quat conjugate(const Quat& q) { return { -q.x, -q.y, -q.z, q.w }; }

Quat multiply(const Quat& a, const Quat& b) {
    return {
        a.w * b.x + a.x * b.w + a.y * b.z - a.z * b.y,
        a.w * b.y - a.x * b.z + a.y * b.w + a.z * b.x,
        a.w * b.z + a.x * b.y - a.y * b.x + a.z * b.w,
        a.w * b.w - a.x * b.x - a.y * b.y - a.z * b.z,
    };
}

/// 单位四元数的逆就是共轭；OpenXR 给出来的位姿都保证单位化，不做归一化检查。
XrPosef poseInverse(const XrPosef& p) {
    XrPosef out{};
    const Quat inv = conjugate(toQuat(p.orientation));
    out.orientation = toXr(inv);
    out.position = toXr(rotate(inv, toVec(p.position)) * -1.0f);
    return out;
}

XrPosef poseMultiply(const XrPosef& a, const XrPosef& b) {
    XrPosef out{};
    const Quat ra = toQuat(a.orientation);
    const Quat rb = toQuat(b.orientation);
    out.orientation = toXr(multiply(ra, rb));
    out.position = toXr(toVec(a.position) + rotate(ra, toVec(b.position)));
    return out;
}

/// 列主序 mat3，和 GLSL 的 `uniform mat3` 内存布局一致。
void quatToMat3(const Quat& q, float* out) {
    const float xx = q.x * q.x, yy = q.y * q.y, zz = q.z * q.z;
    const float xy = q.x * q.y, xz = q.x * q.z, yz = q.y * q.z;
    const float wx = q.w * q.x, wy = q.w * q.y, wz = q.w * q.z;
    out[0] = 1.0f - 2.0f * (yy + zz);
    out[1] = 2.0f * (xy + wz);
    out[2] = 2.0f * (xz - wy);
    out[3] = 2.0f * (xy - wz);
    out[4] = 1.0f - 2.0f * (xx + zz);
    out[5] = 2.0f * (yz + wx);
    out[6] = 2.0f * (xz + wy);
    out[7] = 2.0f * (yz - wx);
    out[8] = 1.0f - 2.0f * (xx + yy);
}

/// 列主序 mat4。OpenXR 的投影约定和 GLES 完全一致（视空间 Z 向后、NDC 深度 [-1,1]），
/// 所以不用像 Vulkan 那样再套一层 Y/深度翻转。
/// 注意这里**不**掺入眼睛位移：屏幕的相对位置在着色器里单独算，
/// 这样交给合成器的 layer pose 就是锚点位姿，头部旋转可以由时间扭曲处理。
void fovToMatrix(const XrFovf& fov, float zn, float zf, float* out) {
    const float l = std::tan(fov.angleLeft);
    const float r = std::tan(fov.angleRight);
    const float t = std::tan(fov.angleUp);
    const float b = std::tan(fov.angleDown);
    std::memset(out, 0, sizeof(float) * 16);
    out[0] = 2.0f / (r - l);
    out[5] = 2.0f / (t - b);
    out[8] = (r + l) / (r - l);
    out[9] = (t + b) / (t - b);
    out[10] = (zf + zn) / (zn - zf);
    out[11] = -1.0f;
    out[14] = 2.0f * zf * zn / (zn - zf);
}

float clampf(float v, float lo, float hi) { return v < lo ? lo : (v > hi ? hi : v); }

/// 单位 quad，TRIANGLE_STRIP。顶点着色器把 x/y 当平面参数用，所以这里不需要索引。
const float kQuadVerts[8] = { -1.f, -1.f, 1.f, -1.f, -1.f, 1.f, 1.f, 1.f };

constexpr float kNear = 0.05f;
constexpr float kFar = 50.0f;

}  // namespace

namespace mihonvr {

XrEngine::XrEngine() = default;

XrEngine::~XrEngine() { stop(); }

void XrEngine::setError(const std::string& message) {
    std::lock_guard<std::mutex> lk(errorMutex_);
    lastError_ = message;
}

std::string XrEngine::xrResultString(XrResult result) const {
    char buf[XR_MAX_RESULT_STRING_SIZE] = {};
    if (instance_ != XR_NULL_HANDLE &&
        xrResultToString(instance_, result, buf) == XR_SUCCESS && buf[0] != '\0') {
        return buf;
    }
    return std::string("XrResult(") + std::to_string(static_cast<int32_t>(result)) + ")";
}

bool XrEngine::start(AAssetManager* assetManager, std::string& error) {
    if (running_.load()) {
        error = "VR 引擎已在运行";
        return false;
    }
    assetManager_ = assetManager;
    xrState_ = XR_SESSION_STATE_UNKNOWN;
    anchorCaptured_ = false;
    running_.store(true);
    thread_ = std::thread([this]() { renderLoop(); });

    // 会话只能由渲染线程建立（GL 上下文是线程独占的），这里等它落到 READY。
    for (int i = 0; i < 200 && !sessionReady_.load() && running_.load(); ++i) {
        std::this_thread::sleep_for(std::chrono::milliseconds(50));
    }
    if (!sessionReady_.load()) {
        error = lastError().empty() ? "OpenXR 会话未能就绪" : lastError();
        stop();
        return false;
    }
    return true;
}

void XrEngine::stop() {
    running_.store(false);
    if (thread_.joinable()) thread_.join();
    sessionReady_.store(false);
}

void XrEngine::submitPage(TexturePayload rgba) {
    if (!rgba.valid()) {
        setError("页面纹理非法");
        return;
    }
    std::lock_guard<std::mutex> lk(texMutex_);
    pendingColor_ = std::move(rgba);
    colorDirty_ = true;
}

void XrEngine::submitDepth(TexturePayload r8) {
    if (!r8.valid()) {
        setError("深度纹理非法");
        return;
    }
    std::lock_guard<std::mutex> lk(texMutex_);
    pendingDepth_ = std::move(r8);
    depthDirty_ = true;
}
void XrEngine::setParams(const RenderParams& params) {
    std::lock_guard<std::mutex> lk(paramMutex_);
    params_ = params;
}

void XrEngine::recenter() {
    // 只置标志：anchorCaptured_ / anchorPose_ 是渲染线程私有的，跨线程写会打架。
    recenterRequested_.store(true);
}

FrameStats XrEngine::stats() const {
    std::lock_guard<std::mutex> lk(statsMutex_);
    return stats_;
}

std::string XrEngine::lastError() const {
    std::lock_guard<std::mutex> lk(errorMutex_);
    return lastError_;
}

// ---------------------------------------------------------------- OpenXR 建链

bool XrEngine::createInstance(std::string& error) {
    // 先看 runtime 有没有 GLES 出图能力，没有就别浪费时间建实例了。
    uint32_t count = 0;
    XrResult r = xrEnumerateInstanceExtensionProperties(nullptr, 0, &count, nullptr);
    if (r != XR_SUCCESS || count == 0) {
        error = "没有查询到任何 OpenXR 扩展（设备上大概率没装 runtime）: " + xrResultString(r);
        return false;
    }
    std::vector<XrExtensionProperties> props(count);
    for (auto& p : props) {
        p.type = XR_TYPE_EXTENSION_PROPERTIES;
        p.next = nullptr;
    }
    r = xrEnumerateInstanceExtensionProperties(nullptr, count, &count, props.data());
    if (r != XR_SUCCESS) {
        error = "枚举扩展失败: " + xrResultString(r);
        return false;
    }
    bool hasGles = false;
    for (const auto& p : props) {
        if (std::strcmp(p.extensionName, XR_KHR_OPENGL_ES_ENABLE_EXTENSION_NAME) == 0) hasGles = true;
    }
    if (!hasGles) {
        error = std::string("runtime 不支持 ") + XR_KHR_OPENGL_ES_ENABLE_EXTENSION_NAME;
        return false;
    }

    XrInstanceCreateInfo info{ XR_TYPE_INSTANCE_CREATE_INFO };
    info.next = nullptr;
    info.createFlags = 0;
    std::strncpy(info.applicationInfo.applicationName, "mihonW VR",
                 XR_MAX_APPLICATION_NAME_SIZE - 1);
    info.applicationInfo.applicationVersion = 1;
    std::strncpy(info.applicationInfo.engineName, "mihonvr", XR_MAX_ENGINE_NAME_SIZE - 1);
    info.applicationInfo.engineVersion = 1;
    // 只声明 1.0：用到的东西（stereo、GLES、参考空间、投影层）全都在 1.0 里，
    // 要新版本的 API 只会把老 runtime（Neo 3 的系统不一定新）挡在门外。
    info.applicationInfo.apiVersion = XR_API_VERSION_1_0;
    const char* enabled[] = { XR_KHR_OPENGL_ES_ENABLE_EXTENSION_NAME };
    info.enabledExtensionCount = 1;
    info.enabledExtensionNames = enabled;

    r = xrCreateInstance(&info, &instance_);
    if (r != XR_SUCCESS) {
        error = "xrCreateInstance 失败: " + xrResultString(r);
        return false;
    }

    XrInstanceProperties instProps{ XR_TYPE_INSTANCE_PROPERTIES };
    instProps.next = nullptr;
    if (xrGetInstanceProperties(instance_, &instProps) == XR_SUCCESS) {
        LOGI("runtime: %s %u.%u", instProps.runtimeName,
             XR_VERSION_MAJOR(instProps.runtimeVersion), XR_VERSION_MINOR(instProps.runtimeVersion));
    }

    r = xrGetInstanceProcAddr(instance_, "xrGetOpenGLESGraphicsRequirementsKHR",
                              reinterpret_cast<PFN_xrVoidFunction*>(&getGlRequirements_));
    if (r != XR_SUCCESS || getGlRequirements_ == nullptr) {
        error = "取不到 xrGetOpenGLESGraphicsRequirementsKHR";
        return false;
    }
    return true;
}

bool XrEngine::createSystem(std::string& error) {
    XrSystemGetInfo getInfo{ XR_TYPE_SYSTEM_GET_INFO };
    getInfo.next = nullptr;
    getInfo.formFactor = XR_FORM_FACTOR_HEAD_MOUNTED_DISPLAY;
    XrResult r = xrGetSystem(instance_, &getInfo, &systemId_);
    if (r != XR_SUCCESS) {
        error = "没有找到 HMD: " + xrResultString(r);
        return false;
    }

    XrSystemProperties props{ XR_TYPE_SYSTEM_PROPERTIES };
    props.next = nullptr;
    if (xrGetSystemProperties(instance_, systemId_, &props) == XR_SUCCESS) {
        LOGI("设备 %s vendor=%u 最大纹理=%ux%u 最大层=%u 位置追踪=%d 朝向追踪=%d", props.systemName,
             props.vendorId, props.graphicsProperties.maxSwapchainImageWidth,
             props.graphicsProperties.maxSwapchainImageHeight, props.graphicsProperties.maxLayerCount,
             static_cast<int>(props.trackingProperties.positionTracking),
             static_cast<int>(props.trackingProperties.orientationTracking));
    }

    uint32_t configCount = 0;
    r = xrEnumerateViewConfigurations(instance_, systemId_, 0, &configCount, nullptr);
    if (r != XR_SUCCESS || configCount == 0) {
        error = "没有可用的视图配置: " + xrResultString(r);
        return false;
    }
    std::vector<XrViewConfigurationType> configs(configCount);
    r = xrEnumerateViewConfigurations(instance_, systemId_, configCount, &configCount, configs.data());
    if (r != XR_SUCCESS || configCount == 0) {
        error = "枚举视图配置失败: " + xrResultString(r);
        return false;
    }
    bool stereo = false;
    for (const auto c : configs) {
        if (c == XR_VIEW_CONFIGURATION_TYPE_PRIMARY_STEREO) stereo = true;
    }
    viewConfig_ = stereo ? XR_VIEW_CONFIGURATION_TYPE_PRIMARY_STEREO : configs[0];
    if (!stereo) LOGW("runtime 不提供 PRIMARY_STEREO，退回第一个配置");

    uint32_t viewCount = 0;
    r = xrEnumerateViewConfigurationViews(instance_, systemId_, viewConfig_, 0, &viewCount, nullptr);
    if (r != XR_SUCCESS || viewCount == 0) {
        error = "xrEnumerateViewConfigurationViews 失败: " + xrResultString(r);
        return false;
    }
    viewViews_.resize(viewCount);
    for (auto& v : viewViews_) {
        v.type = XR_TYPE_VIEW_CONFIGURATION_VIEW;
        v.next = nullptr;
    }
    r = xrEnumerateViewConfigurationViews(instance_, systemId_, viewConfig_, viewCount, &viewCount,
                                          viewViews_.data());
    if (r != XR_SUCCESS) {
        error = "读取视图配置细节失败: " + xrResultString(r);
        return false;
    }
    for (const auto& v : viewViews_) {
        LOGI("视图 建议=%ux%u 最大=%ux%u 采样=%u", v.recommendedImageRectWidth,
             v.recommendedImageRectHeight, v.maxImageRectWidth, v.maxImageRectHeight,
             v.maxSwapchainSampleCount);
    }
    if (viewCount != 2) LOGW("期望双目视图，实际 %u 个", viewCount);

    views_.resize(viewCount);
    for (auto& v : views_) {
        v.type = XR_TYPE_VIEW;
        v.next = nullptr;
    }
    layerViews_.resize(viewCount);
    return true;
}

bool XrEngine::createSession(std::string& error) {
    XrGraphicsRequirementsOpenGLESKHR req{ XR_TYPE_GRAPHICS_REQUIREMENTS_OPENGL_ES_KHR };
    req.next = nullptr;
    if (getGlRequirements_ != nullptr &&
        getGlRequirements_(instance_, systemId_, &req) == XR_SUCCESS) {
        LOGI("OpenXR 要求 GLES min=%u.%u max=%u.%u", XR_VERSION_MAJOR(req.minApiVersionSupported),
             XR_VERSION_MINOR(req.minApiVersionSupported), XR_VERSION_MAJOR(req.maxApiVersionSupported),
             XR_VERSION_MINOR(req.maxApiVersionSupported));
        if (XR_VERSION_MAJOR(req.maxApiVersionSupported) < 3) {
            error = "runtime 只支持 GLES 2，跑不了 GLSL ES 3.00";
            return false;
        }
    }

    eglDisplay_ = eglGetDisplay(EGL_DEFAULT_DISPLAY);
    if (eglDisplay_ == EGL_NO_DISPLAY) {
        error = "eglGetDisplay 失败";
        return false;
    }
    EGLint major = 0, minor = 0;
    if (eglInitialize(eglDisplay_, &major, &minor) == EGL_FALSE) {
        error = "eglInitialize 失败";
        return false;
    }

    const EGLint configAttrs[] = {
        EGL_SURFACE_TYPE, EGL_PBUFFER_BIT,
        EGL_RENDERABLE_TYPE, EGL_OPENGL_ES3_BIT_KHR,
        EGL_RED_SIZE, 8,
        EGL_GREEN_SIZE, 8,
        EGL_BLUE_SIZE, 8,
        EGL_ALPHA_SIZE, 8,
        EGL_NONE,
    };
    EGLint numConfigs = 0;
    if (eglChooseConfig(eglDisplay_, configAttrs, &eglConfig_, 1, &numConfigs) == EGL_FALSE ||
        numConfigs < 1) {
        error = "找不到 EGL_OPENGL_ES3 配置";
        return false;
    }
    const EGLint pbufAttrs[] = { EGL_WIDTH, 1, EGL_HEIGHT, 1, EGL_NONE };
    eglPbuffer_ = eglCreatePbufferSurface(eglDisplay_, eglConfig_, pbufAttrs);
    if (eglPbuffer_ == EGL_NO_SURFACE) {
        error = "eglCreatePbufferSurface 失败";
        return false;
    }
    const EGLint ctxAttrs[] = { EGL_CONTEXT_CLIENT_VERSION, 3, EGL_NONE };
    eglContext_ = eglCreateContext(eglDisplay_, eglConfig_, EGL_NO_CONTEXT, ctxAttrs);
    if (eglContext_ == EGL_NO_CONTEXT) {
        error = "eglCreateContext 失败";
        return false;
    }
    if (eglMakeCurrent(eglDisplay_, eglPbuffer_, eglPbuffer_, eglContext_) == EGL_FALSE) {
        error = "eglMakeCurrent 失败";
        return false;
    }
    LOGI("EGL %d.%d / %s", major, minor, reinterpret_cast<const char*>(glGetString(GL_VERSION)));

    XrGraphicsBindingOpenGLESAndroidKHR binding{ XR_TYPE_GRAPHICS_BINDING_OPENGL_ES_ANDROID_KHR };
    binding.next = nullptr;
    binding.display = eglDisplay_;
    binding.config = eglConfig_;
    binding.context = eglContext_;

    XrSessionCreateInfo createInfo{ XR_TYPE_SESSION_CREATE_INFO };
    createInfo.next = &binding;
    createInfo.createFlags = 0;
    createInfo.systemId = systemId_;
    XrResult r = xrCreateSession(instance_, &createInfo, &session_);
    if (r != XR_SUCCESS) {
        error = "xrCreateSession 失败: " + xrResultString(r);
        return false;
    }
    return true;
}

bool XrEngine::createReferenceSpace(std::string& error) {
    uint32_t count = 0;
    if (xrEnumerateReferenceSpaces(session_, 0, &count, nullptr) != XR_SUCCESS || count == 0) {
        error = "runtime 没有提供任何参考空间";
        return false;
    }
    std::vector<XrReferenceSpaceType> spaces(count);
    if (xrEnumerateReferenceSpaces(session_, count, &count, spaces.data()) != XR_SUCCESS) {
        error = "枚举参考空间失败";
        return false;
    }
    // 锚点一律由两眼位姿现算，所以参考空间选哪个都不影响画面正确性；
    // VIEW 更省（原点就是头），LOCAL 兜底（Neo 3 一定有两个里的一个）。
    XrReferenceSpaceType pick = XR_REFERENCE_SPACE_TYPE_LOCAL;
    bool hasView = false;
    for (const auto s : spaces) {
        if (s == XR_REFERENCE_SPACE_TYPE_VIEW) hasView = true;
    }
    if (!hasView) {
        for (const auto s : spaces) {
            if (s == XR_REFERENCE_SPACE_TYPE_STAGE) pick = XR_REFERENCE_SPACE_TYPE_STAGE;
        }
    } else {
        pick = XR_REFERENCE_SPACE_TYPE_VIEW;
    }

    XrReferenceSpaceCreateInfo info{ XR_TYPE_REFERENCE_SPACE_CREATE_INFO };
    info.next = nullptr;
    info.referenceSpaceType = pick;
    info.poseInReferenceSpace.orientation = { 0.0f, 0.0f, 0.0f, 1.0f };
    info.poseInReferenceSpace.position = { 0.0f, 0.0f, 0.0f };
    XrResult r = xrCreateReferenceSpace(session_, &info, &space_);
    if (r != XR_SUCCESS) {
        error = "xrCreateReferenceSpace 失败: " + xrResultString(r);
        return false;
    }
    LOGI("参考空间类型=%d（1=VIEW 2=LOCAL 3=STAGE）", static_cast<int>(pick));
    return true;
}

bool XrEngine::createViewSwapchains(std::string& error) {
    uint32_t count = 0;
    XrResult r = xrEnumerateSwapchainFormats(session_, 0, &count, nullptr);
    if (r != XR_SUCCESS || count == 0) {
        error = "xrEnumerateSwapchainFormats 失败: " + xrResultString(r);
        return false;
    }
    std::vector<int64_t> formats(count);
    r = xrEnumerateSwapchainFormats(session_, count, &count, formats.data());
    if (r != XR_SUCCESS) {
        error = "读取交换链格式失败: " + xrResultString(r);
        return false;
    }
    // runtime 给的顺序就是它的偏好顺序，按我们能画的这几个去对。
    // sRGB 优先：漫画是 sRGB 内容，落在 sRGB 附件上由合成器转换，比自己线性化更正。
    const int64_t preferred[] = { GL_SRGB8_ALPHA8, GL_RGBA8, GL_RGB8 };
    int64_t chosen = 0;
    for (const int64_t cand : preferred) {
        for (const int64_t f : formats) {
            if (f == cand) {
                chosen = cand;
                break;
            }
        }
        if (chosen != 0) break;
    }
    if (chosen == 0) {
        error = "runtime 没有提供 RGBA8/sRGB8 交换链格式";
        return false;
    }

    destroySwapchains();
    viewSwapchains_.assign(viewViews_.size(), XR_NULL_HANDLE);
    viewImages_.assign(viewViews_.size(), {});
    for (size_t i = 0; i < viewViews_.size(); ++i) {
        const auto& vv = viewViews_[i];
        XrSwapchainCreateInfo ci{ XR_TYPE_SWAPCHAIN_CREATE_INFO };
        ci.next = nullptr;
        ci.createFlags = 0;
        ci.usageFlags = XR_SWAPCHAIN_USAGE_COLOR_ATTACHMENT_BIT | XR_SWAPCHAIN_USAGE_SAMPLED_BIT;
        ci.format = chosen;
        ci.sampleCount = 1;
        ci.width = vv.recommendedImageRectWidth;
        ci.height = vv.recommendedImageRectHeight;
        ci.arraySize = 1;
        ci.mipCount = 1;
        ci.faceCount = 1;
        r = xrCreateSwapchain(session_, &ci, &viewSwapchains_[i]);
        if (r != XR_SUCCESS) {
            error = "xrCreateSwapchain 失败: " + xrResultString(r);
            return false;
        }
        uint32_t n = 0;
        r = xrEnumerateSwapchainImages(viewSwapchains_[i], 0, &n, nullptr);
        if (r != XR_SUCCESS || n == 0) {
            error = "xrEnumerateSwapchainImages 失败: " + xrResultString(r);
            return false;
        }
        viewImages_[i].resize(n);
        for (auto& img : viewImages_[i]) {
            img.type = XR_TYPE_SWAPCHAIN_IMAGE_OPENGL_ES_KHR;
            img.next = nullptr;
            img.image = 0;
        }
        r = xrEnumerateSwapchainImages(
            viewSwapchains_[i], n, &n,
            reinterpret_cast<XrSwapchainImageBaseHeader*>(viewImages_[i].data()));
        if (r != XR_SUCCESS) {
            error = "读取交换链图像失败: " + xrResultString(r);
            return false;
        }
        LOGI("交换链[%zu] %u 张，首张纹理名=%u", i, n, viewImages_[i][0].image);
    }
    LOGI("交换链格式=0x%x", static_cast<unsigned>(chosen));
    return true;
}

void XrEngine::destroySwapchains() {
    for (const auto sc : viewSwapchains_) {
        if (sc != XR_NULL_HANDLE) xrDestroySwapchain(sc);
    }
    viewSwapchains_.clear();
    viewImages_.clear();
}

void XrEngine::teardown() {
    destroySwapchains();
    releaseGl();
    if (space_ != XR_NULL_HANDLE) {
        xrDestroySpace(space_);
        space_ = XR_NULL_HANDLE;
    }
    if (session_ != XR_NULL_HANDLE) {
        xrDestroySession(session_);
        session_ = XR_NULL_HANDLE;
    }
    if (eglDisplay_ != EGL_NO_DISPLAY) {
        eglMakeCurrent(eglDisplay_, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
        if (eglContext_ != EGL_NO_CONTEXT) eglDestroyContext(eglDisplay_, eglContext_);
        if (eglPbuffer_ != EGL_NO_SURFACE) eglDestroySurface(eglDisplay_, eglPbuffer_);
        eglTerminate(eglDisplay_);
    }
    eglContext_ = EGL_NO_CONTEXT;
    eglPbuffer_ = EGL_NO_SURFACE;
    eglDisplay_ = EGL_NO_DISPLAY;
    if (instance_ != XR_NULL_HANDLE) {
        xrDestroyInstance(instance_);
        instance_ = XR_NULL_HANDLE;
    }
}

// -------------------------------------------------------------------- 事件

void XrEngine::pollEvents() {
    XrEventDataBuffer event{};
    event.type = XR_TYPE_EVENT_DATA_BUFFER;
    event.next = nullptr;
    while (xrPollEvent(instance_, &event) == XR_SUCCESS) {
        if (event.type == XR_TYPE_EVENT_DATA_SESSION_STATE_CHANGED) {
            const auto* e = reinterpret_cast<const XrEventDataSessionStateChanged*>(&event);
            onSessionStateChanged(e->state);
        } else if (event.type == XR_TYPE_EVENT_DATA_EVENTS_LOST) {
            LOGW("OpenXR 事件队列溢出，丢弃了部分事件");
        }
        event.type = XR_TYPE_EVENT_DATA_BUFFER;
        event.next = nullptr;
    }
}

void XrEngine::onSessionStateChanged(XrSessionState state) {
    if (state == xrState_) return;
    LOGI("会话状态 %d -> %d", static_cast<int>(xrState_), static_cast<int>(state));
    xrState_ = state;
    switch (state) {
        case XR_SESSION_STATE_READY: {
            XrSessionBeginInfo begin{ XR_TYPE_SESSION_BEGIN_INFO };
            begin.next = nullptr;
            begin.primaryViewConfigurationType = viewConfig_;
            XrResult r = xrBeginSession(session_, &begin);
            if (r != XR_SUCCESS) {
                setError("xrBeginSession 失败: " + xrResultString(r));
                running_.store(false);
                break;
            }
            sessionReady_.store(true);
            break;
        }
        case XR_SESSION_STATE_STOPPING:
            sessionReady_.store(false);
            // 交换链必须在 xrEndSession 之前销毁，下次 READY 再按新尺寸重建。
            destroySwapchains();
            if (xrEndSession(session_) != XR_SUCCESS) LOGW("xrEndSession 失败");
            break;
        case XR_SESSION_STATE_EXITING:
            running_.store(false);
            break;
        case XR_SESSION_STATE_LOSS_PENDING:
            setError("OpenXR runtime 连接已丢失");
            sessionReady_.store(false);
            running_.store(false);
            break;
        default:
            break;
    }
}

// ----------------------------------------------------------------------- GL

bool XrEngine::initGl(std::string& error) {
    glGenVertexArrays(1, &vao_);
    glGenBuffers(1, &vbo_);
    glBindVertexArray(vao_);
    glBindBuffer(GL_ARRAY_BUFFER, vbo_);
    glBufferData(GL_ARRAY_BUFFER, sizeof(kQuadVerts), kQuadVerts, GL_STATIC_DRAW);
    glEnableVertexAttribArray(0);
    glVertexAttribPointer(0, 2, GL_FLOAT, GL_FALSE, 2 * sizeof(float), nullptr);
    glBindVertexArray(0);

    glGenFramebuffers(1, &fbo_);

    glGenTextures(1, &colorTex_);
    glBindTexture(GL_TEXTURE_2D, colorTex_);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
    // 页面没来之前先给个 1x1 深灰，省得整片纯黑看不出是启动中还是坏了。
    const uint8_t placeholder[4] = { 26, 26, 28, 255 };
    glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, 1, 1, 0, GL_RGBA, GL_UNSIGNED_BYTE, placeholder);

    glGenTextures(1, &depthTex_);
    glBindTexture(GL_TEXTURE_2D, depthTex_);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
    // 0.5 = 不位移。深度没算好时画面退化成平面，而不是变成一团糊。
    const uint8_t mid = 128;
    glTexImage2D(GL_TEXTURE_2D, 0, GL_R8, 1, 1, 0, GL_RED, GL_UNSIGNED_BYTE, &mid);
    glBindTexture(GL_TEXTURE_2D, 0);

    if (!buildProgram(error)) return false;
    colorW_ = 1;
    colorH_ = 1;
    GLenum err = glGetError();
    if (err != GL_NO_ERROR) {
        error = "GL 初始化报错 0x" + std::to_string(err);
        return false;
    }
    return true;
}

bool XrEngine::buildProgram(std::string& error) {
    auto compile = [&](GLenum type, const char* src, const char* label) -> GLuint {
        GLuint sh = glCreateShader(type);
        glShaderSource(sh, 1, &src, nullptr);
        glCompileShader(sh);
        GLint ok = GL_FALSE;
        glGetShaderiv(sh, GL_COMPILE_STATUS, &ok);
        if (ok != GL_TRUE) {
            char log[4096];
            GLsizei len = 0;
            glGetShaderInfoLog(sh, sizeof(log), &len, log);
            LOGE("%s 编译失败: %.*s", label, static_cast<int>(len), log);
            error = std::string(label) + " 编译失败";
        }
        return sh;
    };
    GLuint vs = compile(GL_VERTEX_SHADER, kWarpVertexShader, "vertex");
    GLuint fs = compile(GL_FRAGMENT_SHADER, kWarpFragmentShader, "fragment");
    program_ = glCreateProgram();
    glAttachShader(program_, vs);
    glAttachShader(program_, fs);
    // 顶点属性位置由着色器里的 layout(location=0) 指定，不再 glBindAttribLocation。
    glLinkProgram(program_);
    glDeleteShader(vs);
    glDeleteShader(fs);

    GLint ok = GL_FALSE;
    glGetProgramiv(program_, GL_LINK_STATUS, &ok);
    if (ok != GL_TRUE) {
        char log[4096];
        GLsizei len = 0;
        glGetProgramInfoLog(program_, sizeof(log), &len, log);
        LOGE("程序链接失败: %.*s", static_cast<int>(len), log);
        error = "着色器链接失败";
        return false;
    }
    uColor_ = glGetUniformLocation(program_, "uColor");
    uDepth_ = glGetUniformLocation(program_, "uDepth");
    uFov_ = glGetUniformLocation(program_, "uFov");
    uEyePos_ = glGetUniformLocation(program_, "uEyePos");
    uEyeRot_ = glGetUniformLocation(program_, "uEyeRot");
    uHalf_ = glGetUniformLocation(program_, "uHalf");
    uCenter_ = glGetUniformLocation(program_, "uCenter");
    uProj_ = glGetUniformLocation(program_, "uProj");
    uPop_ = glGetUniformLocation(program_, "uPop");
    uUseDepth_ = glGetUniformLocation(program_, "uUseDepth");
    uImageSize_ = glGetUniformLocation(program_, "uImageSize");
    GLint quadHalf = glGetUniformLocation(program_, "uQuadHalf");
    // 顶点着色器里的 uQuadHalf 拿不到位置说明源码被改坏了，直接报错而不是默默画歪。
    if (uColor_ < 0 || uDepth_ < 0 || uFov_ < 0 || uEyePos_ < 0 || uEyeRot_ < 0 || uHalf_ < 0 ||
        uCenter_ < 0 || uProj_ < 0 || uPop_ < 0 || uUseDepth_ < 0 || uImageSize_ < 0 || quadHalf < 0) {
        error = "着色器 uniform 缺失（名字被改了？）";
        LOGE("uniform 位置 color=%d depth=%d fov=%d eyePos=%d eyeRot=%d half=%d quadHalf=%d "
             "center=%d proj=%d pop=%d useDepth=%d imageSize=%d",
             uColor_, uDepth_, uFov_, uEyePos_, uEyeRot_, uHalf_, quadHalf, uCenter_, uProj_, uPop_,
             uUseDepth_, uImageSize_);
        return false;
    }
    // uQuadHalf 只在顶点着色器里用，存进 uHalf_ 之外的槽位会浪费一次查询，
    // 干脆每帧重新取——位置查询很便宜，但每帧查也没必要，所以缓存成成员。
    uQuadHalf_ = quadHalf;
    return true;
}

void XrEngine::releaseGl() {
    if (program_ != 0) glDeleteProgram(program_);
    if (vbo_ != 0) glDeleteBuffers(1, &vbo_);
    if (vao_ != 0) glDeleteVertexArrays(1, &vao_);
    if (fbo_ != 0) glDeleteFramebuffers(1, &fbo_);
    if (colorTex_ != 0) glDeleteTextures(1, &colorTex_);
    if (depthTex_ != 0) glDeleteTextures(1, &depthTex_);
    program_ = vbo_ = vao_ = fbo_ = colorTex_ = depthTex_ = 0;
}

void XrEngine::uploadIfDirty() {
    TexturePayload color;
    TexturePayload depth;
    bool doColor = false;
    bool doDepth = false;
    {
        std::lock_guard<std::mutex> lk(texMutex_);
        if (colorDirty_) {
            color = pendingColor_;  // 拷一份：渲染线程不能抱着锁做 glTexImage2D
            doColor = true;
            activeSerial_ = pendingColor_.serial;
            colorDirty_ = false;
        }
        if (depthDirty_) {
            // 深度是跟着「某一页」算出来的，而且往往比页本身晚到（推理要时间），
            // 也可能被提前预读好。所以只有 serial 与当前页**完全相等**才换上去：
            // 对不上就留着——拿上一张图的深度去 warp 这一张图，会浮出明显的鬼影。
            //
            // 不能用 <= 或 >= 判断新旧：serial 是「第几次解码」的计数器而不是页号，
            // 翻回一张早已缓存的页会复用那个旧的、更小的 serial，用大小关系就会把
            // 它当成过期数据丢掉（或者更糟：错配到当前页上）。
            // serial==0 保留给「与页无关的通用深度」，方便调试。
            if (pendingDepth_.serial == 0 || pendingDepth_.serial == activeSerial_) {
                depth = pendingDepth_;
                doDepth = true;
                depthDirty_ = false;
            }
        }
    }
    if (doColor && color.valid()) {
        glBindTexture(GL_TEXTURE_2D, colorTex_);
        glPixelStorei(GL_UNPACK_ALIGNMENT, 4);
        glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, color.width, color.height, 0, GL_RGBA,
                     GL_UNSIGNED_BYTE, color.pixels.data());
        colorW_ = color.width;
        colorH_ = color.height;
    }
    if (doDepth && depth.valid()) {
        glBindTexture(GL_TEXTURE_2D, depthTex_);
        // R8 每行 1 字节，行宽不是 4 的倍数时必须把对齐放开，否则整张图会斜。
        glPixelStorei(GL_UNPACK_ALIGNMENT, 1);
        glTexImage2D(GL_TEXTURE_2D, 0, GL_R8, depth.width, depth.height, 0, GL_RED,
                     GL_UNSIGNED_BYTE, depth.pixels.data());
        haveDepthTex_ = true;
    }
    glPixelStorei(GL_UNPACK_ALIGNMENT, 4);
    glBindTexture(GL_TEXTURE_2D, 0);
    GLenum err = glGetError();
    if (err != GL_NO_ERROR) setError("纹理上传 GL 错误 0x" + std::to_string(err));
}

bool XrEngine::renderEye(size_t eyeIndex, uint32_t imageIndex, const XrPosef& anchor,
                         const RenderParams& params) {
    const XrView& view = views_[eyeIndex];
    const auto& vv = viewViews_[eyeIndex];
    const GLuint tex = viewImages_[eyeIndex][imageIndex].image;
    const uint32_t w = vv.recommendedImageRectWidth;
    const uint32_t h = vv.recommendedImageRectHeight;
    if (w == 0 || h == 0 || tex == 0) return false;

    // 虚拟屏幕：半宽由张角决定，半高按页面宽高比推出来——换一张竖/横不同的图，
    // 画面不会突然变大变小。
    const float distance = clampf(params.distance, 0.4f, 20.0f);
    const float halfW =
        distance * std::tan(clampf(params.halfWidthDegrees, 2.0f, 75.0f) * static_cast<float>(M_PI) /
                            180.0f);
    const float aspect = (colorH_ > 0) ? static_cast<float>(colorW_) / static_cast<float>(colorH_) : 1.0f;
    const float halfH = halfW / clampf(aspect, 0.05f, 20.0f);
    const float pop = clampf(params.pop, 0.0f, distance * 0.8f);
    // 出屏的内容会投影到平面轮廓外面，几何按最大位移量放大，uv 仍按原尺寸算。
    const float grow = 1.0f + (pop * 0.5f) / distance;

    glBindFramebuffer(GL_FRAMEBUFFER, fbo_);
    glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, tex, 0);
    if (glCheckFramebufferStatus(GL_FRAMEBUFFER) != GL_FRAMEBUFFER_COMPLETE) {
        setError("交换链纹理挂不上 FBO");
        return false;
    }
    glViewport(0, 0, static_cast<GLsizei>(w), static_cast<GLsizei>(h));
    glDisable(GL_DEPTH_TEST);
    glDisable(GL_BLEND);
    glDisable(GL_CULL_FACE);
    const float black[4] = { 0.f, 0.f, 0.f, 1.f };
    glClearBufferfv(GL_COLOR, 0, black);

    // 眼位姿换算到锚点空间：屏幕上「两只眼睛看到的画面差多少」完全由这个相对量决定。
    XrPosef rel = poseMultiply(poseInverse(anchor), view.pose);
    if (params.invertParallax) rel.position.x = -rel.position.x;

    float proj[16];
    fovToMatrix(view.fov, kNear, kFar, proj);
    float eyeRot[9];
    quatToMat3(toQuat(rel.orientation), eyeRot);

    glUseProgram(program_);
    glBindVertexArray(vao_);
    glActiveTexture(GL_TEXTURE0);
    glBindTexture(GL_TEXTURE_2D, colorTex_);
    glUniform1i(uColor_, 0);
    glActiveTexture(GL_TEXTURE1);
    glBindTexture(GL_TEXTURE_2D, depthTex_);
    glUniform1i(uDepth_, 1);
    glUniform2f(uHalf_, halfW, halfH);
    glUniform2f(uQuadHalf_, halfW * grow, halfH * grow);
    glUniform3f(uCenter_, 0.0f, clampf(params.verticalOffset, -3.0f, 3.0f), -distance);
    glUniformMatrix4fv(uProj_, 1, GL_FALSE, proj);
    glUniformMatrix3fv(uEyeRot_, 1, GL_FALSE, eyeRot);
    glUniform3f(uEyePos_, rel.position.x, rel.position.y, rel.position.z);
    glUniform2f(uImageSize_, static_cast<float>(w), static_cast<float>(h));
    glUniform1f(uPop_, pop);
    glUniform1f(uUseDepth_, (params.useDepth && haveDepthTex_) ? 1.0f : 0.0f);

    glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);

    glBindVertexArray(0);
    glBindFramebuffer(GL_FRAMEBUFFER, 0);
    glActiveTexture(GL_TEXTURE0);
    GLenum err = glGetError();
    if (err != GL_NO_ERROR) {
        setError("绘制 GL 错误 0x" + std::to_string(err));
        return false;
    }
    return true;
}

// ------------------------------------------------------------------ 渲染循环

void XrEngine::renderLoop() {
    std::string error;
    LOGI("VR 渲染线程启动");
    bool ok = createInstance(error) && createSystem(error) && createSession(error) && initGl(error) &&
              createReferenceSpace(error);
    if (!ok) {
        setError(error);
        LOGE("初始化失败: %s", error.c_str());
        teardown();
        sessionReady_.store(false);
        running_.store(false);
        return;
    }

    XrFrameWaitInfo wait{ XR_TYPE_FRAME_WAIT_INFO };
    wait.next = nullptr;
    XrFrameBeginInfo begin{ XR_TYPE_FRAME_BEGIN_INFO };
    begin.next = nullptr;
    XrFrameEndInfo end{ XR_TYPE_FRAME_END_INFO };
    end.next = nullptr;
    XrFrameState frameState{ XR_TYPE_FRAME_STATE };
    frameState.next = nullptr;
    XrViewLocateInfo locate{ XR_TYPE_VIEW_LOCATE_INFO };
    locate.next = nullptr;
    XrViewState viewState{ XR_TYPE_VIEW_STATE };
    viewState.next = nullptr;
    // 投影层必须活到 xrEndFrame 之后，所以放在循环外面，每帧复填。
    XrCompositionLayerProjection layer{ XR_TYPE_COMPOSITION_LAYER_PROJECTION };
    layer.next = nullptr;
    layer.layerFlags = 0;
    layer.space = space_;
    std::vector<XrCompositionLayerProjectionView> layerViews;
    const XrCompositionLayerBaseHeader* layerPtrs[1] = {
        reinterpret_cast<const XrCompositionLayerBaseHeader*>(&layer)
    };

    int64_t frames = 0;
    auto lastTick = std::chrono::steady_clock::now();
    uint32_t locatedViews = 0;
    bool tracking = false;

    while (running_.load()) {
        pollEvents();
        if (!sessionReady_.load()) {
            std::this_thread::sleep_for(std::chrono::milliseconds(20));
            continue;
        }
        if (viewSwapchains_.empty() && !createViewSwapchains(error)) {
            setError(error);
            LOGE("%s", error.c_str());
            running_.store(false);
            break;
        }

        XrResult r = xrWaitFrame(session_, &wait, &frameState);
        if (r == XR_ERROR_SESSION_LOST || r == XR_ERROR_INSTANCE_LOST) {
            setError("OpenXR 会话丢失: " + xrResultString(r));
            break;
        }
        if (r != XR_SUCCESS) {
            std::this_thread::sleep_for(std::chrono::milliseconds(10));
            continue;
        }

        r = xrBeginFrame(session_, &begin);
        if (r != XR_SUCCESS) {
            LOGW("xrBeginFrame: %s", xrResultString(r).c_str());
            continue;
        }

        uploadIfDirty();

        uint32_t drawn = 0;
        locatedViews = static_cast<uint32_t>(views_.size());
        if (layerViews.size() < views_.size()) layerViews.resize(views_.size());
        const bool shouldDraw = frameState.shouldRender == XR_TRUE && !viewSwapchains_.empty();
        if (shouldDraw) {
            locate.viewConfigurationType = viewConfig_;
            locate.displayTime = frameState.predictedDisplayTime;
            locate.space = space_;
            r = xrLocateViews(session_, &locate, &viewState, locatedViews, &locatedViews, views_.data());
            tracking = (viewState.viewStateFlags & XR_VIEW_STATE_POSITION_VALID_BIT) != 0 &&
                       (viewState.viewStateFlags & XR_VIEW_STATE_ORIENTATION_VALID_BIT) != 0;
            if (r != XR_SUCCESS) {
                LOGW("xrLocateViews: %s", xrResultString(r).c_str());
            } else if (!tracking || locatedViews != viewViews_.size()) {
                // 追踪没稳住就宁可不画，也不要拿一个错误的 IPD 去 warp——那会看成人斗鸡眼。
                static int skipped = 0;
                if ((skipped++ % 60) == 0) LOGW("跳过一帧：located=%u 期望=%zu", locatedViews, viewViews_.size());
            } else {
                RenderParams params;
                {
                    std::lock_guard<std::mutex> lk(paramMutex_);
                    params = params_;
                }
                // 锚点 = 两眼连线中点 + 左眼朝向。锁头时每帧重算，画面钉在头上；
                // 不锁时定一次，转头就能把屏幕甩到视野外（世界锁定）。
                XrPosef anchor;
                anchor.orientation = views_[0].pose.orientation;
                if (params.headLocked) {
                    const Vec3 right = toVec(views_[locatedViews - 1].pose.position);
                    anchor.position = toXr((toVec(views_[0].pose.position) + right) * 0.5f);
                } else if (!anchorCaptured_ || recenterRequested_.exchange(false)) {
                    anchor.position = views_[0].pose.position;
                    anchorPose_ = anchor;
                    anchorCaptured_ = true;
                } else {
                    anchor = anchorPose_;
                }

                for (uint32_t i = 0; i < locatedViews; ++i) {
                    XrSwapchainImageAcquireInfo acq{ XR_TYPE_SWAPCHAIN_IMAGE_ACQUIRE_INFO };
                    acq.next = nullptr;
                    uint32_t imageIndex = 0;
                    r = xrAcquireSwapchainImage(viewSwapchains_[i], &acq, &imageIndex);
                    if (r != XR_SUCCESS) {
                        LOGW("xrAcquireSwapchainImage[%u]: %s", i, xrResultString(r).c_str());
                        continue;
                    }
                    XrSwapchainImageWaitInfo wi{ XR_TYPE_SWAPCHAIN_IMAGE_WAIT_INFO };
                    wi.next = nullptr;
                    wi.timeout = XR_INFINITE_DURATION;
                    bool drawnThis = false;
                    if (xrWaitSwapchainImage(viewSwapchains_[i], &wi) == XR_SUCCESS) {
                        drawnThis = imageIndex < viewImages_[i].size() &&
                                    renderEye(i, imageIndex, anchor, params);
                        XrSwapchainImageReleaseInfo ri{ XR_TYPE_SWAPCHAIN_IMAGE_RELEASE_INFO };
                        ri.next = nullptr;
                        xrReleaseSwapchainImage(viewSwapchains_[i], &ri);
                    }
                    if (!drawnThis) continue;
                    auto& lv = layerViews[drawn];
                    lv.type = XR_TYPE_COMPOSITION_LAYER_PROJECTION_VIEW;
                    lv.next = nullptr;
                    // 交给合成器的位姿是锚点，不是眼睛：两眼之间的差异已经在着色器里
                    // 做进像素里了，这里再叠一次眼位姿会把它抵消掉。
                    lv.pose = anchor;
                    lv.fov = views_[i].fov;
                    lv.subImage.swapchain = viewSwapchains_[i];
                    lv.subImage.imageArrayIndex = 0;
                    lv.subImage.imageRect.offset = { 0, 0 };
                    lv.subImage.imageRect.extent = {
                        static_cast<int32_t>(viewViews_[i].recommendedImageRectWidth),
                        static_cast<int32_t>(viewViews_[i].recommendedImageRectHeight)
                    };
                    ++drawn;
                }
            }
        }

        end.displayTime = frameState.predictedDisplayTime;
        end.environmentBlendMode = XR_ENVIRONMENT_BLEND_MODE_OPAQUE;
        end.layerCount = drawn;
        end.layers = drawn > 0 ? layerPtrs : nullptr;
        if (drawn > 0) {
            layer.viewCount = drawn;
            layer.views = layerViews.data();
        }
        r = xrEndFrame(session_, &end);
        if (r != XR_SUCCESS) LOGW("xrEndFrame: %s", xrResultString(r).c_str());

        ++frames;
        const auto now = std::chrono::steady_clock::now();
        const double dt = std::chrono::duration<double, std::milli>(now - lastTick).count();
        if (dt >= 1000.0) {
            FrameStats s;
            s.fps = frames * 1000.0 / dt;
            s.viewCount = static_cast<int32_t>(locatedViews);
            s.trackingValid = tracking;
            s.haveDepth = haveDepthTex_;
            if (!viewViews_.empty()) {
                s.width = static_cast<int32_t>(viewViews_[0].recommendedImageRectWidth);
                s.height = static_cast<int32_t>(viewViews_[0].recommendedImageRectHeight);
            }
            {
                std::lock_guard<std::mutex> lk(texMutex_);
                s.activeSerial = activeSerial_;
            }
            {
                std::lock_guard<std::mutex> lk(statsMutex_);
                stats_ = s;
            }
            LOGI("fps=%.1f 视图=%d %dx%d 深度=%d serial=%lld", s.fps, s.viewCount, s.width, s.height,
                 static_cast<int>(s.haveDepth), static_cast<long long>(s.activeSerial));
            frames = 0;
            lastTick = now;
        }
    }

    LOGI("VR 渲染线程退出");
    sessionReady_.store(false);
    teardown();
}

}  // namespace mihonvr
