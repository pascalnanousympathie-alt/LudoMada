package com.ludomadagascar

/**
 * OpenGL ES has no portable ray-tracing API. We detect any vendor extension
 * defensively and otherwise use PBR image-based reflections as the mobile fallback.
 */
object RayTraceSupport {
    fun hasGlesRayTracing(extensions: String?): Boolean {
        val e = extensions.orEmpty().lowercase()
        return "ray_tracing" in e || "raytracing" in e
    }
}
