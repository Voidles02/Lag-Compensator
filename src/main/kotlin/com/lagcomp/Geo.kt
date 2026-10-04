package com.lagcomp

import kotlin.math.abs
import kotlin.math.sqrt

/** Allocation-free geometry helpers. */
object Geo {
    private const val EPS = 1e-9

    /** Distance from a point to an upright box centred on (cx, cz) with the given half width, from minY up by h. */
    fun distToBox(px: Double, py: Double, pz: Double, cx: Double, minY: Double, cz: Double, halfW: Double, h: Double): Double {
        val dx = abs(px - cx) - halfW
        val dz = abs(pz - cz) - halfW
        val ddx = if (dx > 0.0) dx else 0.0
        val ddz = if (dz > 0.0) dz else 0.0
        val maxY = minY + h
        val ddy = if (py < minY) minY - py else if (py > maxY) py - maxY else 0.0
        return sqrt(ddx * ddx + ddy * ddy + ddz * ddz)
    }

    /**
     * Slab test of the segment s->e against an AABB. Returns the entry fraction (0..1) along the segment,
     * or -1.0 if the segment misses. Sweeping the whole segment (not just the end point) is what stops
     * fast projectiles from tunnelling through a hitbox between two ticks.
     */
    fun segmentBoxEntry(
        sx: Double, sy: Double, sz: Double, ex: Double, ey: Double, ez: Double,
        minX: Double, minY: Double, minZ: Double, maxX: Double, maxY: Double, maxZ: Double
    ): Double {
        var tMin = 0.0
        var tMax = 1.0

        var d = ex - sx
        if (abs(d) < EPS) {
            if (sx < minX || sx > maxX) return -1.0
        } else {
            var t1 = (minX - sx) / d
            var t2 = (maxX - sx) / d
            if (t1 > t2) { val tmp = t1; t1 = t2; t2 = tmp }
            if (t1 > tMin) tMin = t1
            if (t2 < tMax) tMax = t2
            if (tMin > tMax) return -1.0
        }

        d = ey - sy
        if (abs(d) < EPS) {
            if (sy < minY || sy > maxY) return -1.0
        } else {
            var t1 = (minY - sy) / d
            var t2 = (maxY - sy) / d
            if (t1 > t2) { val tmp = t1; t1 = t2; t2 = tmp }
            if (t1 > tMin) tMin = t1
            if (t2 < tMax) tMax = t2
            if (tMin > tMax) return -1.0
        }

        d = ez - sz
        if (abs(d) < EPS) {
            if (sz < minZ || sz > maxZ) return -1.0
        } else {
            var t1 = (minZ - sz) / d
            var t2 = (maxZ - sz) / d
            if (t1 > t2) { val tmp = t1; t1 = t2; t2 = tmp }
            if (t1 > tMin) tMin = t1
            if (t2 < tMax) tMax = t2
            if (tMin > tMax) return -1.0
        }
        return tMin
    }
}