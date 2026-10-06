package com.lagcomp

/** Reusable scratch result of a history lookup. */
class Sample {
    var x = 0.0
    var y = 0.0
    var z = 0.0
    var w = 0f
    var h = 0f
    var vx = 0f
    var vy = 0f
    var vz = 0f
    var pose = 0
}

/**
 * Fixed-size ring buffer of primitive arrays (structure-of-arrays). Allocated once, never grows.
 * ~53 bytes per sample; with the default 750 ms history (18 samples) that is ~1 KB.
 * Logical index 0 is the oldest sample, count-1 the newest.
 */
class History(private val cap: Int) {
    private val ts = LongArray(cap)
    private val xs = DoubleArray(cap)
    private val ys = DoubleArray(cap)
    private val zs = DoubleArray(cap)
    private val ws = FloatArray(cap)
    private val hs = FloatArray(cap)
    private val vxs = FloatArray(cap)
    private val vys = FloatArray(cap)
    private val vzs = FloatArray(cap)
    private val poses = ByteArray(cap)

    private var head = 0 // next write position
    var count = 0
        private set

    fun clear() {
        head = 0
        count = 0
    }

    fun record(now: Long, x: Double, y: Double, z: Double, w: Float, h: Float, vx: Float, vy: Float, vz: Float, pose: Int) {
        val i = head
        ts[i] = now
        xs[i] = x
        ys[i] = y
        zs[i] = z
        ws[i] = w
        hs[i] = h
        vxs[i] = vx
        vys[i] = vy
        vzs[i] = vz
        poses[i] = pose.toByte()
        head = if (i + 1 == cap) 0 else i + 1
        if (count < cap) count++
    }

    /** Physical index of logical index i (0 = oldest). head - count >= -cap so the sum is never negative. */
    private fun phys(i: Int): Int = (head - count + i + cap) % cap

    fun spanMs(): Long = if (count < 2) 0L else ts[phys(count - 1)] - ts[phys(0)]

    /**
     * Interpolated state at time [target] (same clock as [record]). O(1): the index is estimated from the
     * average tick spacing and corrected by at most a step or two - no scan of the history.
     * Returns false if the data cannot answer (too old, too stale, gap) so callers fail open to vanilla.
     */
    fun sample(target: Long, out: Sample): Boolean {
        val n = count
        if (n < 2) return false
        val tNew = ts[phys(n - 1)]
        val tOld = ts[phys(0)]
        if (target < tOld) return false
        if (target > tNew) return false
        if (target == tNew) {
            fill(out, phys(n - 1))
            return true
        }
        val step = (tNew - tOld).toDouble() / (n - 1)
        var i = n - 1 - ((tNew - target) / step).toInt()
        if (i < 0) i = 0 else if (i > n - 2) i = n - 2
        while (i > 0 && ts[phys(i)] > target) i--
        while (i < n - 2 && ts[phys(i + 1)] <= target) i++

        val a = phys(i)
        val b = phys(i + 1)
        val dt = ts[b] - ts[a]
        // A hole in the history (idle period, lag spike) must not be interpolated across.
        if (dt > MAX_GAP_MS) return false
        val f = if (dt <= 0L) 0.0 else (target - ts[a]).toDouble() / dt
        val ff = f.toFloat()
        out.x = xs[a] + (xs[b] - xs[a]) * f
        out.y = ys[a] + (ys[b] - ys[a]) * f
        out.z = zs[a] + (zs[b] - zs[a]) * f
        out.w = ws[a] + (ws[b] - ws[a]) * ff
        out.h = hs[a] + (hs[b] - hs[a]) * ff
        out.vx = vxs[a] + (vxs[b] - vxs[a]) * ff
        out.vy = vys[a] + (vys[b] - vys[a]) * ff
        out.vz = vzs[a] + (vzs[b] - vzs[a]) * ff
        out.pose = (if (f < 0.5) poses[a] else poses[b]).toInt()
        return true
    }

    private fun fill(out: Sample, i: Int) {
        out.x = xs[i]
        out.y = ys[i]
        out.z = zs[i]
        out.w = ws[i]
        out.h = hs[i]
        out.vx = vxs[i]
        out.vy = vys[i]
        out.vz = vzs[i]
        out.pose = poses[i].toInt()
    }

    companion object {
        const val MAX_GAP_MS = 200L
        private const val BYTES_PER_SAMPLE = 53L

        fun estimateBytes(capacity: Int): Long = capacity * BYTES_PER_SAMPLE + 200L
    }
}