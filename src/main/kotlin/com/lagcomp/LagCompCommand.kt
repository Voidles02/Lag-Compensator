package com.lagcomp

import org.bukkit.Bukkit
import org.bukkit.command.Command
import org.bukkit.command.CommandSender
import org.bukkit.command.TabExecutor

class LagCompCommand(private val plugin: LagCompPlugin) : TabExecutor {

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        if (args.isEmpty()) {
            sender.sendMessage("§6LagComp §7- /$label <status|debug <player>|reload>")
            return true
        }
        when (args[0].lowercase()) {
            "status" -> if (sender.hasPermission("lagcomp.status")) status(sender) else deny(sender)
            "debug" -> if (sender.hasPermission("lagcomp.debug")) debug(sender, args) else deny(sender)
            "reload" -> if (sender.hasPermission("lagcomp.reload")) {
                plugin.reloadAll()
                sender.sendMessage("§aLagComp reloaded.")
            } else deny(sender)
            else -> sender.sendMessage("§6LagComp §7- /$label <status|debug <player>|reload>")
        }
        return true
    }

    private fun deny(sender: CommandSender) {
        sender.sendMessage("§cYou do not have permission.")
    }

    // Everything below is computed only when the command is run.
    private fun status(sender: CommandSender) {
        val s = plugin.settings
        val st = plugin.stats
        val now = System.nanoTime() / 1_000_000L
        var compensating = 0
        for (d in plugin.playerList) {
            if (d.enabled && !d.isFlagged(now) && d.emaPing >= s.lowPingMs) compensating++
        }
        val perPlayer = History.estimateBytes(s.capacity) + 450L
        sender.sendMessage("§6LagComp §7v${plugin.description.version} §8- §aON §7(${if (s.minimalMode) "minimal" else "full"} mode)")
        sender.sendMessage("§7Melee: §f${s.meleeEnabled} §7Projectiles: §f${s.projectilesEnabled} §7Entity tracking: §f${s.entityTrackingEnabled}")
        sender.sendMessage("§7Worlds: §f${if (s.worlds.isEmpty()) "all" else s.worlds.joinToString()}")
        sender.sendMessage("§7Tracked players: §f${plugin.playerList.size} §7(compensating now: §f$compensating§7)")
        sender.sendMessage("§7Tracked mobs: §f${plugin.entities.size} §7In-flight projectiles: §f${plugin.projectiles.size}")
        sender.sendMessage("§7Estimated history memory: §f${plugin.playerList.size * perPlayer / 1024} KB §7(~${perPlayer} B/player)")
        sender.sendMessage("§7Melee checks: §f${st.checked} §7allowed now/rewound: §f${st.allowedCurrent}/${st.allowedRewound} §7denied: §f${st.denied} §7no data: §f${st.noData}")
        sender.sendMessage("§7Skipped low-ping: §f${st.skippedLowPing} §7skipped flagged: §f${st.skippedFlagged} §7projectiles pulled: §f${st.rescuedProjectiles} §7errors: §f${st.errors}")
    }

    private fun debug(sender: CommandSender, args: Array<out String>) {
        if (args.size < 2) {
            sender.sendMessage("§cUsage: /lagcomp debug <player>")
            return
        }
        val target = Bukkit.getPlayerExact(args[1])
        val d = if (target != null) plugin.players[target.uniqueId] else null
        if (target == null || d == null) {
            sender.sendMessage("§cPlayer not found or not tracked.")
            return
        }
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
        sender.sendMessage("§6LagComp debug: §f${target.name}")
        sender.sendMessage("§7Ping raw/median/smoothed: §f${d.lastRawPing}/${d.medianPing}/${"%.1f".format(d.emaPing)} ms")
        sender.sendMessage("§7State: §f$state")
        sender.sendMessage("§7Current rewind: §f${if (rewind < 0) 0 else rewind} ms §7(max ${s.maxRewindMs})")
        sender.sendMessage("§7History: §f${d.history.count} samples over ${d.history.spanMs()} ms")
        if (d.decisionCount == 0) {
            sender.sendMessage("§7No hit decisions recorded yet.")
            return
        }
        sender.sendMessage("§7Last ${d.decisionCount} hit decisions as attacker:")
        for (k in 0 until d.decisionCount) sender.sendMessage("§8 - §f${d.describeDecision(k, now)}")
    }

    override fun onTabComplete(sender: CommandSender, command: Command, alias: String, args: Array<out String>): MutableList<String>? {
        if (args.size == 1) {
            return listOf("status", "debug", "reload")
                .filter { it.startsWith(args[0].lowercase()) && sender.hasPermission("lagcomp.$it") }
                .toMutableList()
        }
        if (args.size == 2 && args[0].equals("debug", true)) {
            return Bukkit.getOnlinePlayers().map { it.name }.filter { it.startsWith(args[1], true) }.toMutableList()
        }
        return mutableListOf()
    }
}