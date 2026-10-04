package com.lagcomp

import org.bukkit.World
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.entity.EntityTargetLivingEntityEvent
import java.util.UUID

class TrackedEntity(val entity: LivingEntity, capacity: Int, var expireTick: Int) {
    val history = History(capacity)
    var px = 0.0
    var py = 0.0
    var pz = 0.0
    var hasPrev = false
}

/**
 * Optional mob tracking (entity-tracking.enabled). Bounded: at most [cap] mobs, and only mobs that
 * currently target a player; each entry expires after a TTL unless the mob re-targets.
 */
class EntityTracker(private val plugin: LagCompPlugin, private val cap: Int) {
    private val slots = arrayOfNulls<TrackedEntity>(cap)
    private val byId = HashMap<UUID, TrackedEntity>(if (cap > 0) cap * 2 else 1)
    var size = 0
        private set

    fun get(id: UUID): TrackedEntity? = if (size == 0) null else byId[id]

    fun untrack(id: UUID) {
        val tracked = byId[id] ?: return
        var i = 0
        while (i < size) {
            if (slots[i] === tracked) {
                removeAt(i)
                return
            }
            i++
        }
    }

    fun track(e: LivingEntity, ttlTicks: Int) {
        val existing = byId[e.uniqueId]
        if (existing != null) {
            existing.expireTick = plugin.tick + ttlTicks
            return
        }
        if (size >= cap) return
        val te = TrackedEntity(e, plugin.settings.capacity, plugin.tick + ttlTicks)
        slots[size++] = te
        byId[e.uniqueId] = te
    }

    fun tick(now: Long) {
        if (size == 0) return
        val sq = plugin.settings.teleportDetectSq
        var i = 0
        while (i < size) {
            val te = slots[i]!!
            val e = te.entity
            if (!e.isValid || e.isDead || plugin.tick > te.expireTick) {
                removeAt(i)
                continue
            }
            val x = e.x
            val y = e.y
            val z = e.z
            if (te.hasPrev) {
                val dx = x - te.px
                val dy = y - te.py
                val dz = z - te.pz
                if (dx * dx + dy * dy + dz * dz > sq) te.history.clear()
            }
            te.history.record(now, x, y, z, e.width.toFloat(), e.height.toFloat(), 0f, 0f, 0f, 0)
            te.px = x
            te.py = y
            te.pz = z
            te.hasPrev = true
            i++
        }
    }

    private fun removeAt(i: Int) {
        val last = size - 1
        byId.remove(slots[i]!!.entity.uniqueId)
        slots[i] = slots[last]
        slots[last] = null
        size = last
    }

    fun clearWorld(w: World) {
        var i = 0
        while (i < size) {
            if (slots[i]!!.entity.world === w) removeAt(i) else i++
        }
    }

    fun clear() {
        java.util.Arrays.fill(slots, null)
        byId.clear()
        size = 0
    }
}

class EntityListener(private val plugin: LagCompPlugin) : Listener {

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onTarget(e: EntityTargetLivingEntityEvent) {
        val mob = e.entity as? LivingEntity ?: return
        val target = e.target
        if (target !is Player) {
            plugin.entities.untrack(mob.uniqueId)
            return
        }
        try {
            val d = plugin.players[target.uniqueId]
            if (d == null || !d.enabled) {
                plugin.entities.untrack(mob.uniqueId)
                return
            }
            plugin.entities.track(mob, plugin.settings.entityTtlTicks)
        } catch (t: Throwable) {
            plugin.reportError(t)
        }
    }
}