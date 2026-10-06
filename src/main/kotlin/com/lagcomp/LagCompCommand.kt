package com.lagcomp

import org.bukkit.Bukkit
import org.bukkit.command.Command
import org.bukkit.command.CommandSender
import org.bukkit.command.TabExecutor

class LagCompCommand(private val plugin: LagCompPlugin) : TabExecutor {

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        if (args.isEmpty()) {
            sender.sendMessage("§8§m------------------§r §6LagComp §8§m------------------")
            sender.sendMessage("§e/$label status §8- §7View runtime and tracking statistics")
            if (sender.hasPermission("lagcomp.debug")) sender.sendMessage("§e/$label debug <player> §8- §7Inspect hit compensation")
            if (sender.hasPermission("lagcomp.reload")) sender.sendMessage("§e/$label reload §8- §7Reload configuration")
            return true
        }
        when (args[0].lowercase()) {
            "status" -> if (sender.hasPermission("lagcomp.status")) status(sender) else deny(sender)
            "debug" -> if (sender.hasPermission("lagcomp.debug")) debug(sender, args) else deny(sender)
            "reload" -> if (sender.hasPermission("lagcomp.reload")) {
                plugin.reloadAll()
                sender.sendMessage("§8[§6LagComp§8] §aConfiguration reloaded successfully.")
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
        sender.sendMessage("§8§m------------------§r §6LagComp §8§m------------------")
        sender.sendMessage("§7Version §f${plugin.description.version} §8| §7Status §a● Enabled §8| §7Mode §f${if (s.minimalMode) "Minimal" else "Full"}")
        sender.sendMessage("§6Features")
        sender.sendMessage(" §8• §7Melee §f${enabled(s.meleeEnabled)} §8| §7Projectiles §f${enabled(s.projectilesEnabled)} §8| §7Entity tracking §f${enabled(s.entityTrackingEnabled)}")
        sender.sendMessage(" §8• §7Worlds §f${if (s.worlds.isEmpty()) "All" else s.worlds.joinToString()}")
        sender.sendMessage("§6Tracking")
        sender.sendMessage(" §8• §7Players §f${plugin.playerList.size} §8(§7compensating §f$compensating§8) §8| §7Mobs §f${plugin.entities.size} §8| §7Projectiles §f${plugin.projectiles.size}")
        sender.sendMessage(" §8• §7Estimated history §f${plugin.playerList.size * perPlayer / 1024} KB §8(§7~${perPlayer} B/player§8)")
        sender.sendMessage("§6Hit decisions")
        sender.sendMessage(" §8• §7Checked §f${st.checked} §8| §7Current/rewound §f${st.allowedCurrent}/${st.allowedRewound} §8| §7Denied §c${st.denied} §8| §7No data §f${st.noData}")
        sender.sendMessage(" §8• §7Low-ping/flagged skips §f${st.skippedLowPing}/${st.skippedFlagged} §8| §7Projectiles rescued §f${st.rescuedProjectiles} §8| §7Errors §c${st.errors}")
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
        sender.sendMessage("§8§m------------------§r §6LagComp Debug §8§m------------------")
        sender.sendMessage("§7Player §f${target.name} §8| §7State §f$state")
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
            return listOf("status", "debug", "reload")
                .filter { it.startsWith(args[0].lowercase()) && sender.hasPermission("lagcomp.$it") }
                .toMutableList()
        }
        if (args.size == 2 && args[0].equals("debug", true)) {
            if (!sender.hasPermission("lagcomp.debug")) return mutableListOf()
            return Bukkit.getOnlinePlayers().map { it.name }.filter { it.startsWith(args[1], true) }.toMutableList()
        }
        return mutableListOf()
    }

    private fun enabled(value: Boolean) = if (value) "§aON" else "§cOFF"
}