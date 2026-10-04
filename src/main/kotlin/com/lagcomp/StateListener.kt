package com.lagcomp

import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.entity.PlayerDeathEvent
import org.bukkit.event.player.PlayerChangedWorldEvent
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.event.player.PlayerRespawnEvent
import org.bukkit.event.player.PlayerTeleportEvent
import org.bukkit.event.world.WorldUnloadEvent

/** Lifecycle: allocate on join, free on quit, and cut the history on anything that breaks continuity. */
class StateListener(private val plugin: LagCompPlugin) : Listener {

    @EventHandler(priority = EventPriority.MONITOR)
    fun onJoin(e: PlayerJoinEvent) {
        try {
            plugin.addPlayer(e.player)
        } catch (t: Throwable) {
            plugin.reportError(t)
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onQuit(e: PlayerQuitEvent) {
        try {
            plugin.removePlayer(e.player)
        } catch (t: Throwable) {
            plugin.reportError(t)
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onTeleport(e: PlayerTeleportEvent) {
        plugin.players[e.player.uniqueId]?.resetHistory()
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onWorldChange(e: PlayerChangedWorldEvent) {
        plugin.players[e.player.uniqueId]?.resetHistory()
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onRespawn(e: PlayerRespawnEvent) {
        plugin.players[e.player.uniqueId]?.resetHistory()
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onDeath(e: PlayerDeathEvent) {
        plugin.players[e.entity.uniqueId]?.resetHistory()
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onWorldUnload(e: WorldUnloadEvent) {
        try {
            val list = plugin.playerList
            var i = 0
            while (i < list.size) {
                val d = list[i]
                if (d.world === e.world) d.resetHistory()
                i++
            }
            plugin.entities.clearWorld(e.world)
            plugin.projectiles.clearWorld(e.world)
        } catch (t: Throwable) {
            plugin.reportError(t)
        }
    }
}