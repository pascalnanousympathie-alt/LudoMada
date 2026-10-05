package com.ludomadagascar

/**
 * Lane C shader source contract.
 *
 * GLES3-only material/post-process sources. This file owns shader text so
 * Lane A can integrate it into the runtime renderer without mixing shader
 * tuning with gameplay state or input handling.
 */
object LudoPbrShaderSource {

    const val VERTEX_GLSL300 = """#version 300 es
precision highp float;

layout(location=0) in vec3 aPos;
layout(location=1) in vec3 aNormal;
layout(location=2) in vec2 aUv;

uniform mat4 uMvp;
uniform mat4 uMv;
uniform mat4 uModel;
uniform mat4 uLightVp;

out vec3 vNormal;
out vec3 vViewPos;
out vec3 vWorldPos;
out vec2 vUv;
out vec4 vShadow;

void main() {
    vec4 world = uModel * vec4(aPos, 1.0);
    vWorldPos = world.xyz;
    vViewPos = (uMv * vec4(aPos, 1.0)).xyz;
    vNormal = normalize(mat3(uMv) * aNormal);
    vUv = aUv;
    vShadow = uLightVp * world;
    gl_Position = uMvp * vec4(aPos, 1.0);
}
"""

    /**
     * Cook-Torrance GGX material response with:
     * - dielectric/metallic F0
     * - Schlick Fresnel
     * - GGX NDF
     * - Smith masking
     * - shadow-map PCF
     * - normal perturbation
     * - diffuse + specular IBL
     * - Fresnel-weighted environment reflection
     */
    const val PBR_FRAGMENT_GLSL300 = """#version 300 es
precision highp float;

uniform vec4 uColor;
uniform vec3 uLightPos;
uniform vec3 uCameraPos;
uniform vec3 uFillLight;
uniform vec3 uRimLight;
uniform float uKeyIntensity;
uniform float uFillIntensity;
uniform float uRimIntensity;
uniform float uAmbient;
uniform float uMetallic;
uniform float uRoughness;
uniform float uAO;
uniform float uEmission;
uniform float uNormalStrength;
uniform float uReflectionIntensity;
uniform float uFresnelBoost;
uniform float uShadowBias;
uniform sampler2D uAlbedoTex;
uniform sampler2D uNormalTex;
uniform sampler2D uShadowMap;
uniform samplerCube uEnvCube;

in vec3 vNormal;
in vec3 vViewPos;
in vec3 vWorldPos;
in vec2 vUv;
in vec4 vShadow;

layout(location=0) out vec4 fragColor;

const float PI = 3.14159265359;

float saturate(float x) {
    return clamp(x, 0.0, 1.0);
}

vec3 fresnelSchlick(float cosTheta, vec3 f0) {
    return f0 + (1.0 - f0) * pow(1.0 - saturate(cosTheta), 5.0);
}

float distributionGGX(float ndh, float roughness) {
    float a = roughness * roughness;
    float a2 = a * a;
    float d = ndh * ndh * (a2 - 1.0) + 1.0;
    return a2 / max(PI * d * d, 0.0001);
}

float geometrySchlickGGX(float ndv, float roughness) {
    float r = roughness + 1.0;
    float k = (r * r) / 8.0;
    return ndv / max(ndv * (1.0 - k) + k, 0.0001);
}

float geometrySmith(float ndv, float ndl, float roughness) {
    return geometrySchlickGGX(ndv, roughness) *
           geometrySchlickGGX(ndl, roughness);
}

float shadowPCF() {
    vec3 p = vShadow.xyz / max(vShadow.w, 0.0001);
    p = p * 0.5 + 0.5;

    if (p.z > 1.0 ||
        p.x < 0.0 || p.x > 1.0 ||
        p.y < 0.0 || p.y > 1.0) {
        return 1.0;
    }

    float current = p.z - uShadowBias;
    float visibility = 0.0;
    vec2 texel = 1.0 / vec2(textureSize(uShadowMap, 0));

    for (int x = -1; x <= 1; ++x) {
        for (int y = -1; y <= 1; ++y) {
            float closest = texture(
                uShadowMap,
                p.xy + vec2(float(x), float(y)) * texel
            ).r;
            visibility += current <= closest ? 1.0 : 0.0;
        }
    }
    return visibility / 9.0;
}

vec3 perturbNormal(vec3 n) {
    vec3 map = texture(uNormalTex, vUv * 3.0).xyz * 2.0 - 1.0;

    vec3 tangent = normalize(
        vec3(1.0, 0.0, 0.0) - n * dot(n, vec3(1.0, 0.0, 0.0))
    );

    if (length(tangent) < 0.1) {
        tangent = normalize(
            vec3(0.0, 0.0, 1.0) - n * dot(n, vec3(0.0, 0.0, 1.0))
        );
    }

    vec3 bitangent = normalize(cross(n, tangent));
    vec3 mapped = normalize(
        tangent * map.x +
        bitangent * map.y +
        n * max(map.z, 0.15)
    );

    return normalize(mix(n, mapped, saturate(uNormalStrength)));
}

void main() {
    vec3 N = perturbNormal(normalize(vNormal));
    vec3 V = normalize(uCameraPos - vWorldPos);

    vec3 albedoTex = texture(uAlbedoTex, vUv * 2.0).rgb;
    vec3 albedo = mix(uColor.rgb * albedoTex, uColor.rgb, 0.52);

    float metallic = saturate(uMetallic);
    float roughness = clamp(uRoughness, 0.045, 1.0);
    float ndv = saturate(dot(N, V));

    vec3 f0 = mix(vec3(0.04), albedo, metallic);

    vec3 result = albedo * uAmbient * uAO;

    vec3 L = normalize(uLightPos - vWorldPos);
    vec3 H = normalize(V + L);
    float ndl = saturate(dot(N, L));
    float ndh = saturate(dot(N, H));
    float vdh = saturate(dot(V, H));

    float D = distributionGGX(ndh, roughness);
    float G = geometrySmith(ndv, ndl, roughness);
    vec3 F = fresnelSchlick(vdh, f0);

    vec3 specular = (D * G * F) /
        max(4.0 * ndv * ndl, 0.001);

    vec3 diffuse = (1.0 - F) * (1.0 - metallic) *
        albedo / PI;

    float shadow = shadowPCF();
    result += (diffuse + specular) *
        vec3(1.06, 0.99, 0.90) *
        ndl * shadow * uKeyIntensity;

    vec3 fillDir = normalize(uFillLight);
    float fillNdl = saturate(dot(N, fillDir));
    result += albedo * fillNdl * uFillIntensity * 0.24;

    vec3 rimDir = normalize(uRimLight);
    float rimNdl = saturate(dot(N, rimDir));
    float rimFactor = pow(1.0 - ndv, 3.0);
    result += vec3(1.0, 0.84, 0.60) *
        rimNdl * rimFactor * uRimIntensity;

    vec3 R = reflect(-V, N);
    float mip = roughness * roughness * 5.0;
    vec3 envSpec = textureLod(uEnvCube, R, mip).rgb;
    vec3 envDiff = textureLod(uEnvCube, N, 4.0).rgb;
    float fresnel = pow(1.0 - ndv, 5.0);

    float reflection =
        uReflectionIntensity *
        mix(0.20, 1.0, fresnel) *
        uFresnelBoost;

    result += envDiff * albedo *
        (1.0 - metallic) * uAO * 0.38;
    result += envSpec * F * reflection;

    result += albedo * uEmission;

    // Mobile-friendly filmic/tone curve.
    result = result / (result + vec3(1.0));
    result = pow(max(result, vec3(0.0)), vec3(0.4545));

    fragColor = vec4(result, uColor.a);
}
"""

    const val POST_GLSL300 = """#version 300 es
precision highp float;

uniform sampler2D uScene;
uniform sampler2D uDepth;
uniform vec2 uInvResolution;
uniform float uExposure;
uniform float uSaturation;
uniform float uContrast;
uniform float uWarmBias;
uniform float uSsao;
uniform float uBloom;
uniform float uBloomThreshold;

in vec2 vUv;
layout(location=0) out vec4 fragColor;

float luminance(vec3 c) {
    return dot(c, vec3(0.299, 0.587, 0.114));
}

vec3 acesApprox(vec3 x) {
    const float a = 2.51;
    const float b = 0.03;
    const float c = 2.43;
    const float d = 0.59;
    const float e = 0.14;
    return clamp(
        (x * (a * x + b)) /
        max(x * (c * x + d) + e, vec3(0.0001)),
        0.0,
        1.0
    );
}

void main() {
    vec3 base = texture(uScene, vUv).rgb * uExposure;

    vec3 bloomColor = vec3(0.0);
    const float weights[4] = float[](
        0.22, 0.14, 0.08, 0.045
    );

    for (int i = 1; i <= 4; ++i) {
        vec2 p = uInvResolution * float(i) * 1.7;
        bloomColor += (
            texture(uScene, vUv + vec2(p.x, 0.0)).rgb +
            texture(uScene, vUv - vec2(p.x, 0.0)).rgb +
            texture(uScene, vUv + vec2(0.0, p.y)).rgb +
            texture(uScene, vUv - vec2(0.0, p.y)).rgb
        ) * weights[i - 1];
    }

    float bright = max(luminance(base) - uBloomThreshold, 0.0);
    bloomColor *= bright * uBloom;

    float depth0 = texture(uDepth, vUv).r;
    vec2 taps[8] = vec2[](
        vec2(1,0), vec2(-1,0), vec2(0,1), vec2(0,-1),
        vec2(1,1), vec2(-1,1), vec2(1,-1), vec2(-1,-1)
    );

    float occlusion = 0.0;

    for (int i = 0; i < 8; ++i) {
        float sampleDepth = texture(
            uDepth,
            vUv + taps[i] * uInvResolution * 2.0
        ).r;

        occlusion += smoothstep(
            0.0,
            0.025,
            max(0.0, sampleDepth - depth0)
        );
    }

    float ao = 1.0 - clamp(
        (occlusion / 8.0) * uSsao,
        0.0,
        0.34
    );

    vec3 color = acesApprox((base + bloomColor) * ao);

    float l = luminance(color);
    color = mix(color, vec3(l), (1.0 - uSaturation) * 0.08);
    color = (color - 0.5) * uContrast + 0.5;

    color += vec3(
        uWarmBias,
        uWarmBias * 0.55,
        0.0
    );

    color = pow(max(color, vec3(0.0)), vec3(0.4545));

    fragColor = vec4(color, 1.0);
}
"""
}
