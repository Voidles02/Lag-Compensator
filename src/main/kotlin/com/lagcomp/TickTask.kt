package com.lagcomp

/**
 * The single repeating task. One Runnable instance, reused every tick.
 * Work per tick is O(players) + O(tracked mobs) + O(tracked projectiles x players in range), all allocation-free.
 */
class TickTask(private val plugin: LagCompPlugin) : Runnable {
    private var idle = false

    override fun run() {
        try {
            plugin.tick++
            val list = plugin.playerList
            val n = list.size

            // With fewer than two players nobody can fight: do nothing, and drop stale history once so a
            // player joining later can never be rewound onto old data.
            if (n < 2) {
                if (!idle) {
                    idle = true
                    plugin.clearAllHistory()
                }
                return
            }
            idle = false

            val s = plugin.settings
            val now = System.nanoTime() / 1_000_000L
            val samplePing = plugin.tick % s.pingSampleTicks == 0
            var i = 0
            while (i < n) {
                val d = list[i]
                d.snapshot(now, s)
                if (samplePing) d.samplePing(now, s, plugin.logger)
                i++
            }
            plugin.entities.tick(now)
            plugin.projectiles.tick(now)
        } catch (t: Exception) {
            plugin.reportError(t)
        }
    }
}