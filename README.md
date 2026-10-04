# LagComp – server-side lag compensation (Paper 1.21.x, Kotlin)

## Assumptions I made (you asked me to ask – I could not mid-generation, so these are the lighter/safer choices)
1. **No Folia.** Folia needs region schedulers and per-region state; that is heavier and not declared in `plugin.yml`. Paper/Spigot-style main thread only.
2. **No packet library.** Everything runs on Bukkit/Paper events. Consequence: vanilla's own reach check runs *before* any event fires, so LagComp cannot resurrect an attack vanilla already rejected. It can only validate/deny accepted melee hits, rotate knockback, and (for projectiles) pull a projectile onto a target.
3. **Vanilla-rule safety:** event cancellation is never reversed. The default melee reach threshold is 4.0 blocks (3.0 + 1.0), matching ordinary vanilla reach slack; a custom lower threshold is intentionally stricter and may deny hits vanilla accepted.
4. **Mob tracking** (off by default) tracks only mobs with an active player target, removes them when the target changes away from a player, and is bounded.
5. **Projectile compensation** moves arrows, tridents, snowballs and eggs just in front of the target's *current* position; vanilla resolves the actual hit next tick, so shields, invulnerability, damage events and region plugins still apply. Ender pearls are deliberately excluded because pulling one onto a player can cause an unexpected teleport.
7. **Ping source:** `Player#getPing()`. It only changes when the server gets a keep-alive reply, so it adapts slowly. Only changed values are processed (no repeated-sample false spikes).
8. **Velocity** is stored as the real per-tick displacement (server-side player velocity is not reliable).

## Kotlin runtime choice
`plugin.yml` declares Paper's `libraries:` entry for `kotlin-stdlib`, so Paper downloads it when loading the plugin; `manifest.kod` also lists it for dependency handling. The stdlib is not shaded, so the plugin jar stays smaller and avoids duplicate Kotlin classes, at the cost of Paper loading the runtime library separately for this plugin.
| Option | Jar size | Memory |
|---|---|---|
| Paper library (chosen) | Plugin classes only; exact size depends on the build | No stdlib copy inside the plugin jar; runtime library is loaded by Paper |
| Shaded | Larger jar (includes stdlib classes) | Classes are packaged with the plugin; no separate download |

Paper API is `compileOnly`; no ProtocolLib/PacketEvents.

## Rewind math and hit decision
Per tick every player's position, hitbox width/height, per-tick velocity and pose go into a fixed ring buffer (timestamps in ms).

```
raw      = smoothedPing + interpolation-ms
scaled   = raw                                   if raw <= full-until-ms
         = full-until + (raw - full-until)*slope otherwise
scaled  *= rewind-multiplier                     if favour-defender && attackerPing - victimPing > min-ping-difference
rewind   = clamp(scaled, min-ms, max-ms)         (0 when ping < low-ping threshold, flagged, or bypass)
```
`smoothedPing` = EMA of a rolling median (default window 3) of the reported ping, so single spikes never cause a big rewind.

Melee (`EntityDamageByEntityEvent`, HIGH, ignoreCancelled):
1. Look up target history at `now - rewind` (index estimated from tick spacing and interpolated, O(1)).
2. `reachNow` = eye -> current hitbox, `reachRewound` = eye -> rewound hitbox.
3. `reachNow <= limit+tol` -> allow. Else `reachRewound <= limit+tol` -> allow (valid only for the attacker's view). Else cancel.
4. No usable history -> vanilla result is kept (fail open).
5. If allowed, vanilla's knockback vector is rotated by the angle between the current and rewound attacker->target direction (Paper's `EntityPushedByEntityAttackEvent`).

Projectiles: launched arrows/tridents/snowballs/eggs from compensated shooters are tracked (max 32, max 40 ticks). Each tick the segment last-pos -> pos is swept against targets' hitboxes rewound by the shooter's rewind. If it crosses a rewound box but not the current one, and no block is in between, the projectile is moved 0.5 blocks in front of the target's centre. Ender pearls are not compensated.

Abuse: a ping above `abuse.max-ping-ms` flags at once; `spike-count` spikes (sample > median*factor + min) within `window-seconds` flag too. Flagged players get no compensation for `penalty-seconds` (logged once).

History is cleared on teleport, world change, respawn, death, world unload, quit, any >8 block jump in one tick, and when fewer than two players are online.

## Known limitations
- Real network latency cannot be reduced; client-side prediction and the client's own hit/reach decision are untouched.
- Vanilla rejects attacks beyond ~4 blocks (3.0 + 1.0 slack) at the target's *current* position before events fire, so very late hits beyond that cannot be revived without packet-level hooks (PacketEvents). Vanilla's 1.0 slack, 1.9+ attack cooldown and the client's interpolation are the compensation vanilla already has.
- Ping resolution depends on the server's keep-alive interval.
- Mobs are only rewound when tracked (targeting a player). Projectile compensation only targets players.
- Older versions: `EntityPushedByEntityAttackEvent#getKnockback` needs Paper 1.20.6+; for older Paper, drop `KnockbackListener` (set `melee.rewound-knockback: false` is not enough because the class would fail to load, so remove it). Below 1.20.5 entity-interaction-range does not exist; the config reach limit is already used instead.

## Test plan (local server)
1. Join with two clients (or one client + an offline-mode second account).
2. Add latency on the **server** machine's loopback (Linux, delay applies per direction, so RTT = 2x): `sudo tc qdisc add dev lo root netem delay 50ms` (about 100 ms ping). Use 8ms, 25ms, 100ms, 250ms to cover 15-500 ms. Remove with `sudo tc qdisc del dev lo root`. On Windows use *clumsy* (lag filter), on macOS *Network Link Conditioner*.
3. Wait one keep-alive cycle, then run `/lagcomp debug <player>` – check smoothed ping and "compensating".
4. Have the laggy player hit a strafing target; `/lagcomp debug` shows `ALLOWED (in reach only at rewound position)` entries.
5. Reach limit: hit from ~4 blocks (reach-modified client) -> `DENIED`.
6. Abuse: with clumsy toggle 0 ms <-> 400 ms lag several times in 30 s -> console logs "Ping abuse suspected" once, debug shows FLAGGED.
7. Very high ping (400-500 ms): confirm rewind is capped (`/lagcomp debug` -> Current rewind <= max-ms).
8. Teleport mid-fight (`/tp`) and confirm no `ALLOWED (rewound)` decisions for a few ticks afterwards.
9. `/lagcomp reload` repeatedly, then `/lagcomp status` – tracked counts stay bounded.

## Overhead (estimates, not measured)
| | |
|---|---|
| RAM per player | ~1.1 KB history (18 x 53 B + headers) + ~0.4 KB ping/decision state = **~1.5-2 KB** |
| Tick time @ 20 players | ~0.02 ms |
| Tick time @ 50 players | ~0.05 ms |
| Tick time @ 100 players | ~0.1 ms |
| Per melee hit | O(1), ~1-2 us, zero allocation |
| Tracked mobs (opt.) / projectiles | max 64 x ~1.2 KB / 32 x small arrays |

Verify with Spark: `/spark profiler start --timeout 120`, fight, `/spark profiler stop`, search the report for `com.lagcomp` (`TickTask.run`, `MeleeListener.onAttack`). For memory, `/spark heapsummary` and look at `com.lagcomp.History`, or compare TPS/MSPT with the plugin removed.