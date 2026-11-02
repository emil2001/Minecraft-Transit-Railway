# Minecraft Transit Railway for GT New Horizons

The `gtnh-1.7.10` branch ports Minecraft Transit Railway 4.0.5 to Minecraft 1.7.10 Forge for GT New Horizons 2.9.0-RC-2.

This fork builds on [Minecraft Transit Railway](https://github.com/Minecraft-Transit-Railway/Minecraft-Transit-Railway), created by Jonathan Ho and its contributors. The [original README](README.upstream.md) retains the upstream project guide. The companion compatibility code is in [Minecraft-Mappings](https://github.com/emil2001/Minecraft-Mappings/tree/gtnh-1.7.10).

The port includes arrival, motor and door sounds for lifts. [LIFT_SOUNDS.md](LIFT_SOUNDS.md) explains the floor bell setting, resource-pack replacements and recording credits.

The current build is `4.0.5-gtnh.18`. It fixes the ticket-barrier crash on dedicated servers and supports the separate [MTR France Addon port](https://github.com/emil2001/MTR-France-Addon/tree/gtnh/1.2.0-port). Rail disappearance at the reported junction remains unresolved; a separate [diagnostic tool](gtnh/tools/rail-trace/README.md) can record the affected section.

`gtnh.8` keeps lift doors closed until the rendered cabin finishes its arrival, then starts a smooth opening animation. It also extends the legacy API for the Yunzhu Transit Extension port. The offline UUID, customization-screen ordering and lift-panel crash fixes are retained. [Dedicated-server notes](DEDICATED_SERVER_FIXES.md) describe the checks and diagnostic logs.

The Dashboard saves viewed terrain between sessions. [DASHBOARD_MAP_CACHE.md](DASHBOARD_MAP_CACHE.md) describes the cache, memory limits and offline verification. Native rendering of this change remains untested.

`gtnh.13` preserves door-sound events across skipped render callbacks, dispatches queued samples once and chooses the nearest source by distance to the audio listener. [Door-sound notes](D78_DOOR_SOUND_GTNH_13.md) describe the regression tests and platform-observer checks.

`gtnh.15` releases tactile-map music when its source block is broken and lets a surviving nearby map take over the loop. [Tactile-map audio notes](TACTILE_MAP_AUDIO_GTNH_15.md) describe the lifecycle fix and checks.

## Building

The port uses Java 21 and Gradle 8.14. Follow [BUILDING.md](BUILDING.md) to check out both repositories and supply the pinned external inputs. Retrieval of every dependency in a fresh environment remains untested.

## License and credits

Minecraft Transit Railway is licensed under the [MIT License](LICENSE), with the original copyright notice for Jonathan Ho (2022). Preserve the upstream authorship and license notices when redistributing this port. Bundled Noto fonts declare Open Font License terms; dependencies and assets retain their own notices. [THIRD_PARTY.md](THIRD_PARTY.md) records the unresolved attribution issues from the port review.
