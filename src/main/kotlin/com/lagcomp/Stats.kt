package com.lagcomp

/** Plain counters, only ever touched on the main thread. */
class Stats {
    var checked = 0L
    var allowedCurrent = 0L
    var allowedRewound = 0L
    var denied = 0L
    var noData = 0L
    var skippedLowPing = 0L
    var skippedFlagged = 0L
    var rescuedProjectiles = 0L
    var errors = 0L
}

/** Pending knockback rotation, valid only inside the tick/attack that created it. */
class KnockbackHint {
    var tick = -1
    var entityId = -1
    var attackerId = -1
    var cos = 1.0
    var sin = 0.0
}