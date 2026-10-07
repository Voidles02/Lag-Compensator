package com.lagcomp

import org.bukkit.World
import org.bukkit.entity.Player
import java.util.logging.Logger

/** Hit decision codes stored in the per-player decision ring (for /lagcomp debug). */
object Decision {
    const val ALLOWED_CURRENT = 0
    const val ALLOWED_REWOUND = 1
    const val DENIED_REACH = 2
    const val NO_DATA = 3

    val LABELS = arrayOf(
        "ALLOWED (in reach at current position)",
        "ALLOWED (in reach only at rewound position)",
        "DENIED (out of reach at current AND rewound position)",
        "SKIPPED (no usable history, vanilla result kept)"
    )
}

/**
 * Everything LagComp keeps per online player: position history, ping filter, abuse state and the
 * last few hit decisions. All fixed-size; allocated on join, dropped on quit.
 */
class PlayerData(val player: Player, s: Settings, val transientBot: Boolean = false) {
    val history = History(s.capacity)
    var lastInteractionTick = 0

    var world: World? = null
        private set
    var enabled = false
        private set

    // Position at the previous snapshot, used for velocity and teleport detection.
    var px = 0.0
        private set
    var py = 0.0
        private set
    var pz = 0.0
        private set
    private var hasPrev = false

    // ---- ping state ----
    var emaPing = -1.0
        private set
    var lastRawPing = -1
        private set
    var medianPing = 0
        private set
    private val pingRing = IntArray(PING_SLOTS)
    private val pingScratch = IntArray(PING_SLOTS)
    private var pingCount = 0
    private var pingIdx = 0
    private var spikeCount = 0
    private var spikeWindowStart = 0L
    var flaggedUntil = 0L
        private set

    // ---- decision log ----
    private val dCode = ByteArray(DECISION_SLOTS)
    private val dRewind = ShortArray(DECISION_SLOTS)
    private val dCur = FloatArray(DECISION_SLOTS)
    private val dRew = FloatArray(DECISION_SLOTS)
    private val dTime = LongArray(DECISION_SLOTS)
    private val dTarget = arrayOfNulls<String>(DECISION_SLOTS)
    private var dHead = 0
    var decisionCount = 0
        private set

    fun resetHistory() {
        history.clear()
        hasPrev = false
    }

    fun isFlagged(now: Long): Boolean = now < flaggedUntil

    /** Called once per tick, zero allocations. */
    fun snapshot(now: Long, s: Settings) {
        val p = player
        val w = p.world
        if (w !== world) {
            world = w
            enabled = s.isWorldEnabled(w.name)
            resetHistory()
        }
        if (!enabled) return
        if (p.isDead) {
            resetHistory()
            return
        }
        val x = p.x
        val y = p.y
        val z = p.z
        var vx = 0f
        var vy = 0f
        var vz = 0f
        if (hasPrev) {
            val dx = x - px
            val dy = y - py
            val dz = z - pz
            if (dx * dx + dy * dy + dz * dz > s.teleportDetectSq) {
                // A jump this big in one tick is a teleport/portal/etc: never interpolate across it.
                resetHistory()
            } else {
                // Server-side player velocity is unreliable, so use the real per-tick displacement.
                vx = dx.toFloat()
                vy = dy.toFloat()
                vz = dz.toFloat()
            }
        }
        history.record(now, x, y, z, p.width.toFloat(), p.height.toFloat(), vx, vy, vz, p.pose.ordinal)
        px = x
        py = y
        pz = z
        hasPrev = true
    }

    /**
     * Feeds the reported ping through spike detection, a short rolling median (outlier rejection)
     * and an exponential moving average. Only changed values are processed because the reported ping
     * only updates when the server receives a keep-alive reply.
     */
    fun samplePing(now: Long, s: Settings, log: Logger) {
        val raw = player.ping
        if (raw < 0) return
        if (raw > s.maxPingMs) {
            flag(now, s, log, "reported ping $raw ms exceeds abuse.max-ping-ms")
        }
        if (raw == lastRawPing) return
        lastRawPing = raw

        // Compare against the median from BEFORE this sample so a spike cannot hide itself.
        if (raw <= s.maxPingMs && pingCount >= 2 && raw > medianPing * s.spikeFactor + s.spikeMinMs) {
            if (now - spikeWindowStart > s.abuseWindowMs) {
                spikeWindowStart = now
                spikeCount = 0
            }
            spikeCount++
            if (spikeCount >= s.spikeCount) {
                flag(now, s, log, "$spikeCount ping spikes within ${s.abuseWindowMs / 1000}s (latest $raw ms, median $medianPing ms)")
            }
        }

        pingRing[pingIdx] = raw
        pingIdx = (pingIdx + 1) % PING_SLOTS
        if (pingCount < PING_SLOTS) pingCount++

        val n = if (pingCount < s.medianWindow) pingCount else s.medianWindow
        var k = 0
        while (k < n) {
            pingScratch[k] = pingRing[(pingIdx - 1 - k + PING_SLOTS) % PING_SLOTS]
            k++
        }
        // Insertion sort on at most 5 ints.
        var a = 1
        while (a < n) {
            val v = pingScratch[a]
            var b = a - 1
            while (b >= 0 && pingScratch[b] > v) {
                pingScratch[b + 1] = pingScratch[b]
                b--
            }
            pingScratch[b + 1] = v
            a++
        }
        medianPing = pingScratch[n / 2]
        emaPing = if (emaPing < 0.0) medianPing.toDouble() else emaPing + s.smoothing * (medianPing - emaPing)
    }

    private fun flag(now: Long, s: Settings, log: Logger, reason: String) {
        val wasFlagged = now < flaggedUntil
        flaggedUntil = now + s.penaltyMs
        spikeCount = 0
        if (!wasFlagged) {
            log.warning("Ping abuse suspected for ${player.name}: $reason. Compensation disabled for ${s.penaltyMs / 1000}s.")
        }
    }

    fun logDecision(code: Int, rewindMs: Int, curDist: Double, rewDist: Double, now: Long, target: String) {
        val i = dHead
        dCode[i] = code.toByte()
        dRewind[i] = rewindMs.toShort()
        dCur[i] = curDist.toFloat()
        dRew[i] = rewDist.toFloat()
        dTime[i] = now
        dTarget[i] = target
        dHead = (i + 1) % DECISION_SLOTS
        if (decisionCount < DECISION_SLOTS) decisionCount++
    }

    /** Builds a human readable line for the k-th most recent decision (0 = newest). Debug command only. */
    fun describeDecision(k: Int, now: Long): String {
        val i = (dHead - 1 - k + DECISION_SLOTS * 2) % DECISION_SLOTS
        return "${now - dTime[i]} ms ago vs ${dTarget[i]}: ${Decision.LABELS[dCode[i].toInt()]} | rewind=${dRewind[i]}ms " +
            "reachNow=${"%.2f".format(dCur[i])} reachRewound=${"%.2f".format(dRew[i])}"
    }

    companion object {
        const val PING_SLOTS = 5
        const val DECISION_SLOTS = 5
    }
}