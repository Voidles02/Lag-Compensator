package com.lagcomp

import org.bukkit.command.Command
import org.bukkit.command.CommandSender
import org.bukkit.command.TabExecutor

class LagCompCommand(private val plugin: LagCompPlugin) : TabExecutor {

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        if (args.isEmpty()) {
            help(sender, label)
            return true
        }
        when (args[0].lowercase()) {
            "help" -> help(sender, label)
            "status" -> if (sender.hasPermission("lagcomp.status")) status(sender) else deny(sender)
            "debug" -> if (sender.hasPermission("lagcomp.debug")) debug(sender, args) else deny(sender)
            "reload" -> if (sender.hasPermission("lagcomp.reload")) {
                plugin.reloadAll()
                sender.sendMessage("§8[§6LagComp§8] §aConfiguration reloaded. §7Changes are active immediately; history is rebuilding.")
            } else deny(sender)
            else -> {
                sender.sendMessage("§cUnknown subcommand. §7Use /$label for help.")
            }
        }
        return true
    }

    private fun deny(sender: CommandSender) {
        sender.sendMessage("§cYou do not have permission.")
    }

    private fun help(sender: CommandSender, label: String) {
        sender.sendMessage("§8§m--------------------§r §6LagComp §7Help §8§m--------------------")
        if (sender.hasPermission("lagcomp.status")) sender.sendMessage("§e/$label status §8» §7View features, tracking, and hit statistics")
        if (sender.hasPermission("lagcomp.debug")) sender.sendMessage("§e/$label debug <player> §8» §7Inspect ping, history, and recent hit decisions")
        if (sender.hasPermission("lagcomp.reload")) sender.sendMessage("§e/$label reload §8» §7Reload settings and restart tracking")
        sender.sendMessage("§7Lag compensation is server-side; it does not reduce network ping.")
    }

    // Everything below is computed only when the command is run.
    private fun status(sender: CommandSender) {
        val s = plugin.settings
        val st = plugin.stats
        val now = System.nanoTime() / 1_000_000L
        var compensating = 0
        var bots = 0
        for (d in plugin.playerList) {
            if (d.enabled && !d.isFlagged(now) && d.emaPing >= s.lowPingMs) compensating++
            if (d.transientBot) bots++
        }
        val perPlayer = History.estimateBytes(s.capacity) + 450L
        val active = s.meleeEnabled || s.projectilesEnabled || s.entityTrackingEnabled
        sender.sendMessage("§8§m--------------------§r §6LagComp §8§m--------------------")
        sender.sendMessage("§7v${plugin.description.version} §8• §7Runtime ${if (active) "§aACTIVE" else "§eIDLE (all features off)"} §8• §7Mode §f${if (s.minimalMode) "Minimal" else "Full"}")
        sender.sendMessage("§6Features §8/ §7Melee ${enabled(s.meleeEnabled)} §8• §7Projectiles ${enabled(s.projectilesEnabled)} §8• §7Mob tracking ${enabled(s.entityTrackingEnabled)}")
        sender.sendMessage("§7Worlds §f${if (s.worlds.isEmpty()) "All" else s.worlds.joinToString()}")
        sender.sendMessage("§6Tracking §8/ §7Players §f${plugin.playerList.size} §8(§7compensating §f$compensating§8) §8• §7Bot players §f$bots §8/ §f${if (s.botCompatibilityEnabled) s.botMaxTracked else 0} §8• §7Mobs §f${plugin.entities.size} §8• §7Projectiles §f${plugin.projectiles.size}")
        sender.sendMessage("§7Estimated history §f${plugin.playerList.size * perPlayer / 1024} KB §8(§7~${perPlayer} B/player§8)")
        sender.sendMessage("§6Combat §8/ §7Checked §f${st.checked} §8• §7Current/rewound §f${st.allowedCurrent}/${st.allowedRewound} §8• §7Denied §c${st.denied} §8• §7No history §f${st.noData}")
        sender.sendMessage("§7Low-ping/flagged skips §f${st.skippedLowPing}/${st.skippedFlagged} §8• §7Rescued projectiles §f${st.rescuedProjectiles} §8• §7Errors §c${st.errors}")
    }

    private fun debug(sender: CommandSender, args: Array<out String>) {
        if (args.size < 2) {
            sender.sendMessage("§cUsage: /lagcomp debug <player>")
            return
        }
        val d = plugin.playerList.firstOrNull { it.player.name.equals(args[1], ignoreCase = true) }
        if (d == null) {
            sender.sendMessage("§cPlayer not found or not tracked.")
            return
        }
        val target = d.player
        val s = plugin.settings
        val now = System.nanoTime() / 1_000_000L
        val rewind = plugin.computeRewind(d, -1, now)
        val state = when {
            !d.enabled -> "world disabled"
            d.isFlagged(now) -> "FLAGGED for ping abuse (${(d.flaggedUntil - now) / 1000}s left) - no compensation"
            target.hasPermission(LagCompPlugin.BYPASS_PERMISSION) -> "bypass permission - no compensation"
            d.emaPing < s.lowPingMs -> "below low-ping threshold (${s.lowPingMs} ms) - vanilla"
            else -> "compensating"
        }
        sender.sendMessage("§8§m--------------------§r §6LagComp §7Debug §8§m--------------------")
        sender.sendMessage("§7Player §f${target.name} §8• §7State §f$state${if (d.transientBot) " §8• §bPvP bot" else ""}")
        sender.sendMessage("§7Ping raw/median/smoothed §f${d.lastRawPing}/${d.medianPing}/${"%.1f".format(d.emaPing)} ms")
        sender.sendMessage("§7Rewind §f${if (rewind < 0) 0 else rewind} ms §8/ §7maximum §f${s.maxRewindMs} ms")
        sender.sendMessage("§7History §f${d.history.count} samples §8over §f${d.history.spanMs()} ms")
        if (d.decisionCount == 0) {
            sender.sendMessage("§7No hit decisions recorded yet.")
            return
        }
        sender.sendMessage("§7Last ${d.decisionCount} hit decisions as attacker:")
        for (k in 0 until d.decisionCount) sender.sendMessage("§8 - §f${d.describeDecision(k, now)}")
    }

    override fun onTabComplete(sender: CommandSender, command: Command, alias: String, args: Array<out String>): MutableList<String>? {
        if (args.size == 1) {
            return listOf("help", "status", "debug", "reload")
                .filter { it.startsWith(args[0].lowercase()) && (it == "help" || sender.hasPermission("lagcomp.$it")) }
                .toMutableList()
        }
        if (args.size == 2 && args[0].equals("debug", true)) {
            if (!sender.hasPermission("lagcomp.debug")) return mutableListOf()
            return plugin.playerList.map { it.player.name }.filter { it.startsWith(args[1], true) }.toMutableList()
        }
        return mutableListOf()
    }

    private fun enabled(value: Boolean) = if (value) "§aON" else "§cOFF"
}