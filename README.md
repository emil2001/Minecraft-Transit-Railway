# Minecraft Transit Railway for GT New Horizons

The `gtnh-1.7.10` branch ports Minecraft Transit Railway 4.0.5 to Minecraft 1.7.10 Forge for GT New Horizons 2.9.0-RC-2.

This fork builds on [Minecraft Transit Railway](https://github.com/Minecraft-Transit-Railway/Minecraft-Transit-Railway), created by Jonathan Ho and its contributors. The [original README](README.upstream.md) retains the upstream project guide. The companion compatibility code is in [Minecraft-Mappings](https://github.com/emil2001/Minecraft-Mappings/tree/gtnh-1.7.10).

The port includes arrival, motor and door sounds for lifts. [LIFT_SOUNDS.md](LIFT_SOUNDS.md) explains the floor bell setting, resource-pack replacements and recording credits.

The current build is `4.0.5-gtnh.21`. It sends the first passenger update when the client selects a lift or train cabin. The previous initial-send condition was unreachable, and a connected-player test on `.20` measured a 996 ms delay before the movement-triggered update. Repeated doorway frames and car changes keep their previous sending behavior. The server still validates every request; this change does not add acknowledgement or retry handling for a missing server snapshot. Shutdown cleanup also clears passenger flags without trying to send a final packet through a stopped server queue.

Passenger checks use detached car poses from Core instead of rebuilding the train’s full rail path in Minecraft’s server tick. Core-to-server updates are copied before publication so that threaded simulation does not share live train, lift or station data with the game thread. It also preserves runtime translations added by other mods when MTR loads its JSON language files. The ticket-barrier server fix and support for the separate [MTR France Addon port](https://github.com/emil2001/MTR-France-Addon/tree/gtnh/1.2.0-port) are retained. Rail disappearance at the reported junction remains unresolved; a separate [diagnostic tool](gtnh/tools/rail-trace/README.md) can record the affected section.

`gtnh.8` keeps lift doors closed until the rendered cabin finishes its arrival, then starts a smooth opening animation. It also extends the legacy API for the Yunzhu Transit Extension port. The offline UUID, customization-screen ordering and lift-panel crash fixes are retained. [Dedicated-server notes](DEDICATED_SERVER_FIXES.md) describe the checks and diagnostic logs.

The Dashboard saves viewed terrain between sessions. [DASHBOARD_MAP_CACHE.md](DASHBOARD_MAP_CACHE.md) describes the cache, memory limits and offline verification. Native rendering of this change remains untested.

`gtnh.13` preserves door-sound events across skipped render callbacks, dispatches queued samples once and chooses the nearest source by distance to the audio listener. [Door-sound notes](D78_DOOR_SOUND_GTNH_13.md) describe the regression tests and platform-observer checks.

`gtnh.15` releases tactile-map music when its source block is broken and lets a surviving nearby map take over the loop. [Tactile-map audio notes](TACTILE_MAP_AUDIO_GTNH_15.md) describe the lifecycle fix and checks.

## Server simulation

`server.useThreadedSimulation` in `config/mtr.json` is read at startup and defaults to `false` when absent. Setting it to `true` runs each dimension on Core's scheduler. Restart the server after changing it. The numeric snapshot fix applies in both modes; changing the setting alone does not remove the old duplicate path reconstruction.

The port copies vehicle/lift updates and operation replies on the Core thread before Minecraft consumes them. Passenger snapshot results are published through a volatile completion flag; expired reads and reads from an old player/world generation are discarded. The shared system-map cache is synchronized across dimensions; a large map response can briefly delay another dimension’s map request. Player-name lookups use a concurrent cache filled by native join/disconnect events.

The standalone benchmark compares one four-car snapshot handoff on synthetic curved routes. It checks exact bogie coordinates before timing. Median acceptance cost on the local JDK 21 run:

| Route segments | Previous, ms | Detached poses, ms | Previous payload, bytes | Detached payload, bytes |
|---|---:|---:|---:|---:|
| 10 | 0.695 | 0.021 | 5064 | 1599 |
| 100 | 5.626 | 0.015 | 31118 | 1599 |
| 1000 | 55.680 | 0.016 | 293396 | 1599 |

[Raw measurements](gtnh/benchmarks/riding-snapshots.json) include producer-plus-consumer timings and every sample. To rerun, use `./gradlew -p gtnh benchmarkRidingSnapshots -PmtrLog4j=/path/to/libraries --quiet` after supplying the build inputs. These figures measure the handoff only. They do not predict total server MSPT or remove CPU contention from other mods or the host.

Validation covers 59 Core tests and 392 backend tests, with one optional addon-resource skip. This includes initial lift/train packet encoding, repeated and overlapping doorway frames, real threaded and manual Core schedulers in two dimensions, lifecycle revocation and passenger/key authorization. In a copy of a GTNH 2.9.0 dedicated server and client, a four-tick entry into an existing lift sent the first packet in the same frame and gained server authorization within 90 ms. No fall occurred in that trial. A further run stopped the dedicated server with the passenger connected and completed without the previous shutdown exception. Other intermittent boarding failures remain unconfirmed. A native Portable Hole entry followed by walking backwards out did not reproduce the persistent rendering artifact. The thread audit covers the changed boundaries and does not prove that every upstream Core path is race-free.

## Building

The port uses JDK 21 and Gradle 8.14, with JDK 8 also installed for the Minecraft build toolchain. Follow [BUILDING.md](BUILDING.md) to check out both repositories and supply the pinned external inputs.

## License and credits

Minecraft Transit Railway is licensed under the [MIT License](LICENSE), with the original copyright notice for Jonathan Ho (2022). Preserve the upstream authorship and license notices when redistributing this port. Bundled Noto fonts declare Open Font License terms; dependencies and assets retain their own notices. [THIRD_PARTY.md](THIRD_PARTY.md) records the unresolved attribution issues from the port review.
