# Minecraft Transit Railway for GT New Horizons

The `gtnh-1.7.10` branch ports Minecraft Transit Railway 4.0.5 to Minecraft 1.7.10 Forge for GT New Horizons 2.9.0-RC-2. Validation covers singleplayer with the bundled MTR resources.

This fork builds on [Minecraft Transit Railway](https://github.com/Minecraft-Transit-Railway/Minecraft-Transit-Railway), created by Jonathan Ho and its contributors. The [original README](README.upstream.md) retains the upstream project guide. The companion compatibility code is in [Minecraft-Mappings](https://github.com/emil2001/Minecraft-Mappings/tree/gtnh-1.7.10).

The port includes arrival, motor and door sounds for lifts. [LIFT_SOUNDS.md](LIFT_SOUNDS.md) explains the floor bell setting, resource-pack replacements and recording credits.

## Building

The port uses Java 21 and Gradle 8.14. Follow [BUILDING.md](BUILDING.md) to check out both repositories and supply the pinned external inputs. Retrieval of every dependency in a fresh environment remains untested.

## License and credits

Minecraft Transit Railway is licensed under the [MIT License](LICENSE), with the original copyright notice for Jonathan Ho (2022). Preserve the upstream authorship and license notices when redistributing this port. Bundled Noto fonts declare Open Font License terms; dependencies and assets retain their own notices. [THIRD_PARTY.md](THIRD_PARTY.md) records the unresolved attribution issues from the port review.
