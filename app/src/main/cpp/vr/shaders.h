// SY --> MihonVR 着色器源码
//
// 放在独立的头文件里、而且整段用 raw string 包起来，是为了让 CI 能按每段前面的
// `const char* k… = R` 加 GLSL 定界符这对锚点把源码抠出来喂给 glslc/glslang 做语法
// 校验——GLSL 是运行时才由驱动编译的，写错了要等到戴上头显才发现，那个反馈环太长。
// 注意：这段注释里**不能**出现那对定界符的字面量，否则抠取会多切出一段空源码。
//
// 两条路径共用同一支片元着色器：uUseDepth < 0.5 时它就是普通的透视贴图四边形。
#pragma once

namespace mihonvr {

/// 顶点着色器：把「虚拟屏幕平面」上的一个四边形投到当前这只眼睛。
///
/// 关键点是 `vUv` 必须是平面上位置的**线性函数**，这样光栅化的透视校正插值
/// 出来的正好是这条视线与平面交点的 uv，片元里就不用再解一次 ray-plane 相交。
inline const char* kWarpVertexShader = R"GLSL(#version 300 es
precision highp float;

in vec2 aPos;                 // 四边形参数，-1..1
uniform vec2 uHalf;           // 虚拟屏幕半宽/半高（米）
uniform vec2 uQuadHalf;       // 几何用的半宽/半高，比 uHalf 略大（见下）
uniform vec3 uCenter;         // 屏幕中心，锚点空间
uniform mat4 uProj;           // 该眼的 OpenXR 投影矩阵
uniform mat3 uEyeRot;         // 该眼视空间 -> 锚点空间
out vec2 vUv;

void main() {
    // 出屏的内容会被投到平面轮廓之外，所以几何要放大一点点，否则前景边缘被切掉。
    // 但 uv 仍然按真实屏幕尺寸算，超出部分靠片元里的越界判断丢弃。
    vec2 p = aPos * uQuadHalf;
    vec3 q = uCenter + vec3(p, 0.0);
    // 只要「转过来」不要「移过来」：眼睛的位移在片元里单独参与 uv 反算，
    // 这样合成器拿到的 layer pose 是锚点位姿，头部旋转由它做时间扭曲。
    vec3 viewPos = transpose(uEyeRot) * q;
    gl_Position = uProj * vec4(viewPos, 1.0);
    vUv = vec2(0.5 + p.x / (2.0 * uHalf.x), 0.5 - p.y / (2.0 * uHalf.y));
}
)GLSL";

/// 片元着色器：一阶逆 warp（DIBR）。
///
/// 约定：深度 1=近、0=远；位移沿锚点 -Z 的反方向（出屏为正）。
/// 推导：源纹素 Q 在平面上，实际立体点 P = Q + (0,0,shift)。
/// 本像素的视线 (o,dir) 命中 P，故 t = (-D + shift - o.z)/dir.z，
/// 而平面交点 t0 = (-D - o.z)/dir.z，两者相减得 Q.xy = Q0.xy + shift*dir.xy/dir.z
/// —— o.z 和 D 全部消掉了，所以不需要真的求交点距离。
inline const char* kWarpFragmentShader = R"GLSL(#version 300 es
precision highp float;
precision highp sampler2D;

in vec2 vUv;
out vec4 fragColor;

uniform sampler2D uColor;
uniform sampler2D uDepth;     // R8，0=远 1=近
uniform vec4 uFov;            // angleLeft(负) angleRight angleUp angleDown(负)
uniform mat3 uEyeRot;         // 该眼视空间 -> 锚点空间
uniform vec3 uEyePos;         // 该眼在锚点空间里的位置
uniform vec2 uHalf;           // 屏幕半宽/半高（米）
uniform vec2 uImageSize;      // 本眼渲染目标尺寸（像素）
uniform float uPop;           // 出屏总位移（米）
uniform float uUseDepth;

void main() {
    // gl_FragCoord 是 framebuffer 坐标；本眼独占整张交换链图，所以直接除尺寸即可。
    vec2 local = gl_FragCoord.xy / uImageSize;
    vec2 ndc = local * 2.0 - 1.0;
    float tanX = mix(tan(uFov.x), tan(uFov.y), ndc.x * 0.5 + 0.5);
    float tanY = mix(tan(uFov.w), tan(uFov.z), ndc.y * 0.5 + 0.5);
    vec3 dir = uEyeRot * normalize(vec3(tanX, tanY, -1.0));

    if (uUseDepth < 0.5) {
        fragColor = texture(uColor, vUv);
        return;
    }

    // 先用平面 uv 查一次深度，拿到这一像素的位移量。
    float d0 = texture(uDepth, clamp(vUv, vec2(0.0), vec2(1.0))).r;
    float shift = (d0 - 0.5) * uPop;
    float s = shift / min(dir.z, -0.02);
    vec2 off = s * vec2(dir.x / (2.0 * uHalf.x), -dir.y / (2.0 * uHalf.y));

    // 位移会在背景一侧拉开缝隙。沿位移方向多打几个孔、取「最近深度」的那个，
    // 等于让前景边缘横向撑一点去盖住洞——比露出被糊到的背景耐看。
    vec2 bestUv = vUv + off;
    float bestDepth = d0;
    for (int i = -2; i <= 2; i += 1) {
        vec2 cand = vUv + off * (1.0 + 0.5 * float(i));
        float dc = texture(uDepth, cand).r;
        if (dc > bestDepth) {
            bestDepth = dc;
            bestUv = cand;
        }
    }
    if (any(lessThan(bestUv, vec2(0.0))) || any(greaterThan(bestUv, vec2(1.0)))) {
        // 屏幕外：让交换链清出来的黑底透上来，比拿边缘色拉伸一整条边干净。
        discard;
    }
    fragColor = texture(uColor, bestUv);
}
)GLSL";

}  // namespace mihonvr
