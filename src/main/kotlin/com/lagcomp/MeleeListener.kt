package com.lagcomp

import io.papermc.paper.event.entity.EntityPushedByEntityAttackEvent
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.entity.EntityDamageByEntityEvent
import org.bukkit.event.entity.EntityDamageEvent
import kotlin.math.sqrt

/**
 * Melee validation. Runs at HIGH with ignoreCancelled = true: after protection/region plugins (NORMAL)
 * have had their say, and it can only ever CANCEL, never un-cancel - so every vanilla and plugin rule
 * (creative/spectator, invulnerability, PvP regions, cooldowns, shields, blocking) still applies.
 */
class MeleeListener(private val plugin: LagCompPlugin) : Listener {
    private val sample = Sample() // reused scratch, no per-hit allocation

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onAttack(e: EntityDamageByEntityEvent) {
        val damager = e.damager
        if (damager !is Player || (e.cause != EntityDamageEvent.DamageCause.ENTITY_ATTACK &&
                e.cause != EntityDamageEvent.DamageCause.ENTITY_SWEEP_ATTACK)) return
        try {
            // Invalidate the prior attack's hint before any early return, so it cannot affect a later hit this tick.
            plugin.kbHint.tick = -1
            handle(damager, e)
        } catch (t: Exception) {
            plugin.reportError(t)
        }
    }

    private fun handle(damager: Player, e: EntityDamageByEntityEvent) {
        val s = plugin.settings
        val victimPlayer = e.entity as? Player
        if (plugin.isFakePlayer(damager) || (victimPlayer != null && plugin.isFakePlayer(victimPlayer))) return
        val now = System.nanoTime() / 1_000_000L
        val att = plugin.players[damager.uniqueId] ?: return
        if (!att.enabled) return

        val victim = e.entity
        val hist: History
        var victimPing = -1
        val label: String
        if (victim is Player) {
            val vd = plugin.players[victim.uniqueId] ?: return
            if (!vd.enabled) return
            hist = vd.history
            if (vd.emaPing >= 0.0) victimPing = vd.emaPing.toInt()
            label = victim.name
        } else {
            val te = plugin.entities.get(victim.uniqueId) ?: return
            hist = te.history
            label = victim.type.name
        }

        val rewind = plugin.computeRewind(att, victimPing, now)
        if (rewind == LagCompPlugin.FLAGGED) {
            plugin.stats.skippedFlagged++
            return
        }
        if (rewind <= 0) {
            plugin.stats.skippedLowPing++
            return
        }
        plugin.stats.checked++

        // Rewind only the target: the attacker's own server position already reflects what their client
        // reported, it is the target that the attacker saw late.
        if (!hist.sample(now - rewind, sample)) {
            plugin.stats.noData++
            att.logDecision(Decision.NO_DATA, rewind, -1.0, -1.0, now, label)
            return
        }

        val ax = damager.x
        val ay = damager.y + damager.eyeHeight
        val az = damager.z
        val rewDist = Geo.distToBox(ax, ay, az, sample.x, sample.y, sample.z, sample.w * 0.5, sample.h.toDouble())
        val curDist = Geo.distToBox(ax, ay, az, victim.x, victim.y, victim.z, victim.width * 0.5, victim.height)
        val limit = s.reachLimit + s.reachTolerance

        if (curDist <= limit) {
            plugin.stats.allowedCurrent++
            att.logDecision(Decision.ALLOWED_CURRENT, rewind, curDist, rewDist, now, label)
        } else if (rewDist <= limit) {
            plugin.stats.allowedRewound++
            att.logDecision(Decision.ALLOWED_REWOUND, rewind, curDist, rewDist, now, label)
        } else {
            plugin.stats.denied++
            att.logDecision(Decision.DENIED_REACH, rewind, curDist, rewDist, now, label)
            e.isCancelled = true
            return
        }

        if (s.rewoundKnockback && rewDist <= limit) prepareKnockback(damager, victim.entityId, victim.x, victim.z)
    }

    /**
     * Vanilla pushes the victim away from where it is NOW. We store the angle between the current and the
     * rewound attacker->victim direction; KnockbackListener rotates vanilla's own vector by it.
     */
    private fun prepareKnockback(damager: Player, victimId: Int, vx: Double, vz: Double) {
        val hx = vx - damager.x
        val hz = vz - damager.z
        val rx = sample.x - damager.x
        val rz = sample.z - damager.z
        val lc = sqrt(hx * hx + hz * hz)
        val lr = sqrt(rx * rx + rz * rz)
        if (lc < 1e-3 || lr < 1e-3) return
        val h = plugin.kbHint
        h.cos = (hx * rx + hz * rz) / (lc * lr)
        h.sin = (hx * rz - hz * rx) / (lc * lr)
        h.entityId = victimId
        h.attackerId = damager.entityId
        h.tick = plugin.tick
    }
}

/** Only registered when melee.rewound-knockback is on. */
class KnockbackListener(private val plugin: LagCompPlugin) : Listener {

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onPush(e: EntityPushedByEntityAttackEvent) {
        val h = plugin.kbHint
        // The hint is only valid for the exact victim/attacker pair in the same tick.
        if (h.tick != plugin.tick || h.entityId != e.entity.entityId || h.attackerId != e.pushedBy.entityId) return
        h.tick = -1
        try {
            val k = e.knockback
            val nx = k.x * h.cos - k.z * h.sin
            val nz = k.x * h.sin + k.z * h.cos
            k.setX(nx)
            k.setZ(nz)
            e.knockback = k
        } catch (t: Exception) {
            plugin.reportError(t)
        }
    }
}