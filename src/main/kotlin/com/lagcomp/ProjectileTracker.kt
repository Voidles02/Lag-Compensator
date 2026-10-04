package com.lagcomp

import org.bukkit.FluidCollisionMode
import org.bukkit.GameMode
import org.bukkit.Location
import org.bukkit.World
import org.bukkit.entity.AbstractArrow
import org.bukkit.entity.Egg
import org.bukkit.entity.Player
import org.bukkit.entity.Projectile
import org.bukkit.entity.Snowball
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.entity.ProjectileLaunchEvent
import org.bukkit.util.Vector
import kotlin.math.sqrt

/**
 * Tracks a small, fixed number of in-flight projectiles launched by compensated shooters and sweeps
 * their per-tick segment against the targets' REWOUND hitboxes.
 *
 * If the segment crosses a rewound hitbox but not the target's current hitbox (so vanilla would miss),
 * the projectile is moved just in front of the target's current position. The hit itself is then
 * resolved by vanilla on the next tick, so shields, invulnerability, damage events and protection
 * plugins all still apply.
 */
class ProjectileTracker(private val plugin: LagCompPlugin, private val cap: Int) {
    private val projs = arrayOfNulls<Projectile>(cap)
    private val shooters = arrayOfNulls<PlayerData>(cap)
    private val lastX = DoubleArray(cap)
    private val lastY = DoubleArray(cap)
    private val lastZ = DoubleArray(cap)
    private val rewind = IntArray(cap)
    private val age = IntArray(cap)
    var size = 0
        private set

    private val sample = Sample()

    // Scratch for the current segment, so helpers need no long parameter lists.
    private var lx = 0.0
    private var ly = 0.0
    private var lz = 0.0
    private var cx = 0.0
    private var cy = 0.0
    private var cz = 0.0
    private var sdx = 0.0
    private var sdy = 0.0
    private var sdz = 0.0
    private var slen2 = 0.0

    fun track(p: Projectile, shooter: PlayerData, rewindMs: Int) {
        if (size >= cap) return
        projs[size] = p
        shooters[size] = shooter
        lastX[size] = p.x
        lastY[size] = p.y
        lastZ[size] = p.z
        rewind[size] = rewindMs
        age[size] = 0
        size++
    }

    fun tick(now: Long) {
        if (size == 0) return
        val s = plugin.settings
        var i = 0
        while (i < size) {
            val p = projs[i]!!
            if (!p.isValid || p.isDead) {
                removeAt(i)
                continue
            }
            cx = p.x
            cy = p.y
            cz = p.z
            lx = lastX[i]
            ly = lastY[i]
            lz = lastZ[i]
            sdx = cx - lx
            sdy = cy - ly
            sdz = cz - lz
            slen2 = sdx * sdx + sdy * sdy + sdz * sdz
            age[i] += 1
            // Stopped (stuck in a block / landed) or flown long enough: nothing left to compensate.
            if (age[i] > s.projectileMaxTicks || slen2 < 1e-8) {
                removeAt(i)
                continue
            }
            if (scan(p, shooters[i]!!, now - rewind[i], s)) {
                removeAt(i)
                continue
            }
            lastX[i] = cx
            lastY[i] = cy
            lastZ[i] = cz
            i++
        }
    }

    private fun scan(p: Projectile, shooter: PlayerData, rewoundTime: Long, s: Settings): Boolean {
        val world = p.world
        val list = plugin.playerList
        val inf = s.projectileInflate
        val n = list.size
        var j = 0
        while (j < n) {
            val t = list[j]
            j++
            if (t === shooter || !t.enabled || t.world !== world) continue
            // Cheap prefilter on the last snapshot position: rewinding moves a target only a few blocks.
            val ddx = t.px - cx
            val ddy = t.py - cy
            val ddz = t.pz - cz
            if (ddx * ddx + ddy * ddy + ddz * ddz > PREFILTER_SQ) continue
            if (!t.history.sample(rewoundTime, sample)) continue

            val hw = sample.w * 0.5 + inf
            val entry = Geo.segmentBoxEntry(
                lx, ly, lz, cx, cy, cz,
                sample.x - hw, sample.y - inf, sample.z - hw,
                sample.x + hw, sample.y + sample.h + inf, sample.z + hw
            )
            if (entry < 0.0) continue

            val pl = t.player
            val gm = pl.gameMode
            if ((gm != GameMode.SURVIVAL && gm != GameMode.ADVENTURE) || pl.isDead || pl.isInvulnerable) continue

            // If the current hitbox is already crossed, vanilla will register the hit itself.
            val chw = pl.width * 0.5 + inf
            val curEntry = Geo.segmentBoxEntry(
                lx, ly, lz, cx, cy, cz,
                pl.x - chw, pl.y - inf, pl.z - chw,
                pl.x + chw, pl.y + pl.height + inf, pl.z + chw
            )
            if (curEntry >= 0.0) continue

            if (pull(p, world, pl, entry, s)) return true
        }
        return false
    }

    private fun pull(p: Projectile, world: World, pl: Player, entry: Double, s: Settings): Boolean {
        val tx = pl.x
        val ty = pl.y + pl.height * 0.5
        val tz = pl.z

        // Where the projectile crossed the rewound hitbox vs where the target is now.
        val hx = lx + sdx * entry
        val hy = ly + sdy * entry
        val hz = lz + sdz * entry
        val px = tx - hx
        val py = ty - hy
        val pz = tz - hz
        if (px * px + py * py + pz * pz > s.projectileMaxPull * s.projectileMaxPull) return false

        val rx = tx - cx
        val ry = ty - cy
        val rz = tz - cz
        val dist = sqrt(rx * rx + ry * ry + rz * rz)
        if (dist < 1e-6) return false
        // Rare path, so one block ray trace is fine: never pull a projectile through a wall.
        val blocked = world.rayTraceBlocks(
            Location(world, cx, cy, cz), Vector(rx / dist, ry / dist, rz / dist), dist, FluidCollisionMode.NEVER, true
        )
        if (blocked != null) return false

        val len = sqrt(slen2)
        val loc = p.location
        // Half a block short of the target centre along the flight direction; the next vanilla move crosses it.
        loc.set(tx - sdx / len * 0.5, ty - sdy / len * 0.5, tz - sdz / len * 0.5)
        p.teleport(loc)
        plugin.stats.rescuedProjectiles++
        return true
    }

    private fun removeAt(i: Int) {
        val last = size - 1
        projs[i] = projs[last]
        shooters[i] = shooters[last]
        lastX[i] = lastX[last]
        lastY[i] = lastY[last]
        lastZ[i] = lastZ[last]
        rewind[i] = rewind[last]
        age[i] = age[last]
        projs[last] = null
        shooters[last] = null
        size = last
    }

    fun removeShooter(d: PlayerData) {
        var i = 0
        while (i < size) {
            if (shooters[i] === d) removeAt(i) else i++
        }
    }

    fun clearWorld(w: World) {
        var i = 0
        while (i < size) {
            if (projs[i]!!.world === w) removeAt(i) else i++
        }
    }

    fun clear() {
        java.util.Arrays.fill(projs, null)
        java.util.Arrays.fill(shooters, null)
        size = 0
    }

    companion object {
        private const val PREFILTER_SQ = 24.0 * 24.0
    }
}

class ProjectileListener(private val plugin: LagCompPlugin) : Listener {

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onLaunch(e: ProjectileLaunchEvent) {
        val p = e.entity
        if (!(p is AbstractArrow || p is Snowball || p is Egg)) return
        val shooter = p.shooter
        if (shooter !is Player) return
        try {
            val d = plugin.players[shooter.uniqueId] ?: return
            if (!d.enabled) return
            val rewind = plugin.computeRewind(d, -1, System.nanoTime() / 1_000_000L)
            if (rewind <= 0) return
            plugin.projectiles.track(p, d, rewind)
        } catch (t: Throwable) {
            plugin.reportError(t)
        }
    }
}