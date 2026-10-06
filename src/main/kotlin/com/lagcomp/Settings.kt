package com.lagcomp

import org.bukkit.configuration.file.FileConfiguration

/** Immutable snapshot of config.yml. Every value is clamped so a bad config can never break the plugin. */
class Settings(c: FileConfiguration) {
    val minimalMode = c.getBoolean("minimal-mode", false)

    val historyMs = c.getInt("history-length-ms", 750).coerceIn(300, 2000)
    val capacity = historyMs / 50 + 3

    val minRewindMs = c.getInt("rewind.min-ms", 0).coerceIn(0, 500).coerceAtMost(historyMs - 60)
    val maxRewindMs = c.getInt("rewind.max-ms", 250).coerceIn(minRewindMs, 500)
        .coerceAtMost(historyMs - 60).coerceAtLeast(minRewindMs)
    val interpolationMs = c.getInt("rewind.interpolation-ms", 50).coerceIn(0, 200)

    val smoothing = c.getDouble("ping.smoothing-factor", 0.3).coerceIn(0.01, 1.0)
    val medianWindow = c.getInt("ping.median-window", 3).coerceIn(1, 5)
    val lowPingMs = c.getInt("ping.low-ping-threshold-ms", 10).coerceIn(0, 500)
    val pingSampleTicks = c.getInt("ping.sample-interval-ticks", 10).coerceIn(1, 200)

    val scaleStartMs = c.getInt("scaling.full-until-ms", 150).coerceIn(0, 500)
    val scaleSlope = c.getDouble("scaling.slope-above", 0.5).coerceIn(0.0, 1.0)

    val defenderEnabled = c.getBoolean("favour-defender.enabled", true)
    val defenderDiffMs = c.getInt("favour-defender.min-ping-difference-ms", 100).coerceIn(0, 500)
    val defenderMultiplier = c.getDouble("favour-defender.rewind-multiplier", 0.6).coerceIn(0.0, 1.0)

    val reachLimit = c.getDouble("reach.limit", 3.0).coerceIn(1.0, 8.0)
    val reachTolerance = c.getDouble("reach.tolerance", 1.0).coerceIn(0.0, 3.0)

    val meleeEnabled = c.getBoolean("melee.enabled", true)
    val rewoundKnockback = c.getBoolean("melee.rewound-knockback", true)

    val projectilesEnabled = !minimalMode && c.getBoolean("projectiles.enabled", true)
    val projectileMaxTracked = c.getInt("projectiles.max-tracked", 32).coerceIn(1, 256)
    val projectileMaxTicks = c.getInt("projectiles.max-flight-ticks", 40).coerceIn(2, 200)
    val projectileInflate = c.getDouble("projectiles.hitbox-inflate", 0.3).coerceIn(0.0, 1.0)
    val projectileMaxPull = c.getDouble("projectiles.max-pull-distance", 6.0).coerceIn(0.5, 20.0)

    val entityTrackingEnabled = !minimalMode && c.getBoolean("entity-tracking.enabled", false)
    val entityMaxTracked = c.getInt("entity-tracking.max-tracked", 64).coerceIn(1, 512)
    val entityTtlTicks = c.getInt("entity-tracking.ttl-seconds", 60).coerceIn(5, 600) * 20

    // Squared once here so the per-tick teleport check needs no sqrt.
    val teleportDetectSq = c.getDouble("teleport-detect-blocks", 8.0).coerceIn(2.0, 64.0).let { it * it }

    val maxPingMs = c.getInt("abuse.max-ping-ms", 600).coerceIn(100, 5000)
    val spikeFactor = c.getDouble("abuse.spike-factor", 2.0).coerceIn(1.2, 10.0)
    val spikeMinMs = c.getInt("abuse.spike-min-ms", 80).coerceIn(0, 1000)
    val spikeCount = c.getInt("abuse.spike-count", 3).coerceIn(1, 20)
    val abuseWindowMs = c.getInt("abuse.window-seconds", 30).coerceIn(5, 600) * 1000L
    val penaltyMs = c.getInt("abuse.penalty-seconds", 60).coerceIn(5, 3600) * 1000L

    val worlds: Set<String> = c.getStringList("enabled-worlds").toHashSet()

    fun isWorldEnabled(name: String): Boolean = worlds.isEmpty() || worlds.contains(name)
}