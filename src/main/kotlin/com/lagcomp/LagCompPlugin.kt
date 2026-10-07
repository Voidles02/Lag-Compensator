package com.lagcomp

import org.bukkit.Bukkit
import org.bukkit.entity.Player
import org.bukkit.event.HandlerList
import org.bukkit.plugin.java.JavaPlugin
import org.bukkit.scheduler.BukkitTask
import java.util.UUID
import java.util.logging.Level

class LagCompPlugin : JavaPlugin() {
    lateinit var settings: Settings
    lateinit var entities: EntityTracker
    lateinit var projectiles: ProjectileTracker

    // Bounded by the number of online players; entries removed on quit/reload/disable.
    val players = HashMap<UUID, PlayerData>(64)
    val playerList = ArrayList<PlayerData>(64)

    val stats = Stats()
    val kbHint = KnockbackHint()
    var tick = 0

    private var errorLogged = false
    private var task: BukkitTask? = null

    override fun onEnable() {
        saveDefaultConfig()
        ensureBotConfig()
        settings = Settings(config)
        logger.info("+--------------------------------------+")
        logger.info("|               LagComp                |")
        logger.info("|  Server-side combat lag compensation |")
        logger.info("|  Version ${description.version.padEnd(28)}|")
        logger.info("+--------------------------------------+")
        getCommand("lagcomp")?.setExecutor(LagCompCommand(this))
        startRuntime()
        logger.info("LagComp enabled (${if (settings.minimalMode) "minimal" else "full"} mode).")
    }

    override fun onDisable() {
        stopRuntime()
    }

    fun reloadAll() {
        reloadConfig()
        ensureBotConfig()
        settings = Settings(config)
        stopRuntime()
        startRuntime()
    }

    /** Registers only the listeners the current config needs, then starts the tick task. */
    private fun startRuntime() {
        val s = settings
        errorLogged = false
        entities = EntityTracker(this, if (s.entityTrackingEnabled) s.entityMaxTracked else 0)
        projectiles = ProjectileTracker(this, if (s.projectilesEnabled) s.projectileMaxTracked else 0)
        if (!s.meleeEnabled && !s.projectilesEnabled && !s.entityTrackingEnabled) return
        for (p in Bukkit.getOnlinePlayers()) addPlayer(p)

        val pm = server.pluginManager
        pm.registerEvents(StateListener(this), this)
        if (s.meleeEnabled) {
            pm.registerEvents(MeleeListener(this), this)
            if (s.rewoundKnockback) pm.registerEvents(KnockbackListener(this), this)
        }
        if (s.projectilesEnabled) pm.registerEvents(ProjectileListener(this), this)
        if (s.entityTrackingEnabled) pm.registerEvents(EntityListener(this), this)

        task = server.scheduler.runTaskTimer(this, TickTask(this), 1L, 1L)
    }

    private fun stopRuntime() {
        task?.cancel()
        task = null
        HandlerList.unregisterAll(this)
        players.clear()
        playerList.clear()
        if (::entities.isInitialized) entities.clear()
        if (::projectiles.isInitialized) projectiles.clear()
    }

    fun addPlayer(p: Player) {
        if (isFakePlayer(p)) return
        if (players.containsKey(p.uniqueId)) return
        val d = PlayerData(p, settings)
        players[p.uniqueId] = d
        playerList.add(d)
    }

    fun isFakePlayer(p: Player): Boolean {
        val s = settings
        if (!s.botCompatibilityEnabled || !s.botIgnoreFakePlayers) return false
        if (!p.isOnline) return true
        if (s.botMissingAddressIsFake && p.address == null) return true
        return s.botMetadata.any(p::hasMetadata)
    }

    private fun ensureBotConfig() {
        val defaults = mapOf<String, Any>(
            "bot-compatibility.enabled" to true,
            "bot-compatibility.ignore-fake-players" to true,
            "bot-compatibility.detect-missing-address" to true,
            "bot-compatibility.ignore-player-metadata" to listOf("NPC", "fakeplayer", "FakePlayer", "fake-player", "PPvPBot")
        )
        var changed = false
        for ((path, value) in defaults) {
            if (!config.contains(path, true)) {
                config.set(path, value)
                changed = true
            }
        }
        if (changed) saveConfig()
    }

    fun removePlayer(p: Player) {
        val d = players.remove(p.uniqueId) ?: return
        playerList.remove(d)
        projectiles.removeShooter(d)
        entities.removeTarget(p.uniqueId)
    }

    fun clearAllHistory() {
        for (d in playerList) d.resetHistory()
        entities.clear()
        projectiles.clear()
    }

    /**
     * Rewind amount in ms for an attacker. Returns [FLAGGED] if the attacker is penalised for ping abuse,
     * 0 if no compensation applies, otherwise the clamped rewind.
     *
     * rewind = ping + interpolation, scaled down above scaling.full-until-ms, shrunk if the victim's ping
     * is much lower (favour-defender), then clamped to [min, max].
     */
    fun computeRewind(attacker: PlayerData, victimPing: Int, now: Long): Int {
        val s = settings
        if (attacker.isFlagged(now)) return FLAGGED
        val ping = attacker.emaPing
        if (ping < s.lowPingMs) return 0
        // Permission lookup only happens for players that would actually be compensated.
        if (attacker.player.hasPermission(BYPASS_PERMISSION)) return 0

        var r = ping + s.interpolationMs
        if (r > s.scaleStartMs) r = s.scaleStartMs + (r - s.scaleStartMs) * s.scaleSlope
        if (s.defenderEnabled && victimPing >= 0 && ping - victimPing > s.defenderDiffMs) r *= s.defenderMultiplier
        return r.toInt().coerceIn(s.minRewindMs, s.maxRewindMs)
    }

    /** Fail-open error sink: counts every error, logs the stack trace only once per runtime. */
    fun reportError(t: Throwable) {
        stats.errors++
        if (!errorLogged) {
            errorLogged = true
            logger.log(Level.SEVERE, "LagComp hit an internal error and fell back to vanilla behaviour (further errors are only counted).", t)
        }
    }

    companion object {
        const val FLAGGED = -1
        const val BYPASS_PERMISSION = "lagcomp.bypass"
    }
}