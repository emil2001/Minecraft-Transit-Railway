# Minecraft Transit Railway for GT New Horizons

The `gtnh-1.7.10` branch ports Minecraft Transit Railway 4.0.5 to Minecraft 1.7.10 Forge for GT New Horizons 2.9.0-RC-2. Validation covers singleplayer with the bundled MTR resources.

This fork builds on [Minecraft Transit Railway](https://github.com/Minecraft-Transit-Railway/Minecraft-Transit-Railway), created by Jonathan Ho and its contributors. The [original README](README.upstream.md) retains the upstream project guide. The companion compatibility code is in [Minecraft-Mappings](https://github.com/emil2001/Minecraft-Mappings/tree/gtnh-1.7.10).

## Status

This publication provides the source branches and documentation. No port binary release is published here.

The publication check passed 283 backend tests and six Core compatibility tests with no failures, errors or skips. The rebuilt `MTR-GTNH-4.0.5-gtnh.0.jar` contains no test probes. The native scenarios below were run against the accepted singleplayer artifact.

Current native scenarios cover Dashboard station and route editing, PIDS configuration and saved values, and one scheduled Eidan train profile. The train scenario checks natural boarding and exit, passenger doorway hold and release, resource-reload recovery, gravity and native audio lifecycle. Saved Core data and block entities were read back independently.

Retained scenarios also cover boats, cable cars, an A320, signals, lifts, escalators, waterlogging and crafting. Each keeps its original tested artifact and scope. These checks do not establish coverage of every vehicle model, block variant or configuration.

Multiplayer is deferred. Foreground FPS, physical speaker audibility, external add-ons and resource packs remain untested. Long-session freedom from memory leaks is unproven. Migration of external worlds and GTNH progression balance are outside the validated scope.

## World backups

Before installing or updating a locally built JAR, back up the complete world, including its `mtr/` directory. Restoring a world requires its matching mod JAR and settings as well as the saved files.

## Building

The port uses Java 21 and Gradle 8.14. Follow [BUILDING.md](BUILDING.md) to check out both repositories and supply the pinned external inputs. Retrieval of every dependency in a fresh environment remains untested.

## License and credits

Minecraft Transit Railway is licensed under the [MIT License](LICENSE), with the original copyright notice for Jonathan Ho (2022). Preserve the upstream authorship and license notices when redistributing this port. Bundled Noto fonts declare Open Font License terms; dependencies and assets retain their own notices. [THIRD_PARTY.md](THIRD_PARTY.md) records the unresolved attribution issues from the port review.
