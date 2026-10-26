# Building the GTNH port

Use JDK 21 and the included Gradle 8.14 wrappers. The production build needs both source repositories and external dependency files. The commands below follow the configured Gradle tasks; fetching every external input in a fresh environment remains untested.

## Check out both repositories

Keep the directory names `mtr` and `mappings` as siblings. The build uses those relative source paths. The port branch includes the generated Java inputs and font definition needed by the 1.7.10 build, so this build does not require the upstream `setupFiles` task or translation service credentials.

```sh
mkdir mtr-gtnh
cd mtr-gtnh
git clone --branch gtnh-1.7.10 https://github.com/emil2001/Minecraft-Transit-Railway.git mtr
git clone --branch gtnh-1.7.10 https://github.com/emil2001/Minecraft-Mappings.git mappings
```

## Supply the external inputs

The three Gradle properties below accept absolute paths. Set them explicitly when building outside the original development layout.

| Property | Required contents |
|---|---|
| `mtrLog4j` | A directory containing `log4j-api-2.0-beta9-fixed.jar` and `log4j-core-2.0-beta9-fixed.jar` from GTNH 2.9.0-RC-2. The build searches recursively; supply exactly one matching API JAR. |
| `mtrDependencies` | A directory containing `minecraft-1.20.4-client.jar`, `occlusionculling-0.0.9-SNAPSHOT.jar` and the five narrator runtime JARs listed below. |
| `mtrAngelica` | The file `angelica-2.2.28.jar` from the [GTNH Angelica releases](https://github.com/GTNewHorizons/Angelica/releases). |

Use the target pack's fixed Log4j JARs. This build compiles against the API and uses the Core JAR for tests; the game supplies Log4j at runtime.

### Minecraft textures

The texture input is the official Minecraft 1.20.4 client JAR. Resolve version 1.20.4 through the [official launcher version manifest](https://piston-meta.mojang.com/mc/game/version_manifest_v2.json), then use that version's `downloads.client` entry. Save the file as `minecraft-1.20.4-client.jar` in `mtrDependencies`.

The companion repository's [`vanilla-textures.json`](https://github.com/emil2001/Minecraft-Mappings/blob/gtnh-1.7.10/forge/1.7.10-mapping/src/main/resources/META-INF/mtr/vanilla-textures.json) contains the exact official download URL, expected size and digests. The texture task verifies SHA-256 before extracting its selected textures. The complete client JAR is an external build input.

### Narrator libraries

Place these five files in `mtrDependencies`:

| Maven coordinate | Filename |
|---|---|
| `com.mojang:text2speech:1.17.9` | `text2speech-1.17.9.jar` |
| `net.java.dev.jna:jna:5.13.0` | `jna-5.13.0.jar` |
| `net.java.dev.jna:jna-platform:5.13.0` | `jna-platform-5.13.0.jar` |
| `ca.weblite:java-objc-bridge:1.1` | `java-objc-bridge-1.1.jar` |
| `org.slf4j:slf4j-api:2.0.7` | `slf4j-api-2.0.7.jar` |

The official Minecraft 1.20.4 version metadata lists their Mojang library downloads. [Mojang's library repository](https://libraries.minecraft.net/) and [Maven Central](https://repo.maven.apache.org/maven2/) are the sources for these coordinates. Verify the files against the committed [`narrator-runtime.json`](https://github.com/emil2001/Minecraft-Mappings/blob/gtnh-1.7.10/forge/1.7.10-mapping/build-inputs/narrator-runtime.json), which pins filenames, sizes and digests. The production build reads these files from the supplied directory; it does not fetch them through these properties.

### Occlusion culling

The required input is `com.logisticscraft:occlusionculling:0.0.9-SNAPSHOT`. Its byte identity is pinned in the companion [`occlusionculling.json`](https://github.com/emil2001/Minecraft-Mappings/blob/gtnh-1.7.10/forge/1.7.10-mapping/src/main/resources/META-INF/mtr/occlusionculling.json). The production task verifies the SHA-256 and expected class count.

Upstream uses the [CodeMC Maven repository](https://repo.codemc.org/repository/maven-public) for this dependency. The validated input was recovered from an upstream Gradle cache. Availability of those exact snapshot bytes from CodeMC has not been verified. A different build of the same snapshot version will fail the hash check; obtain the pinned file before attempting the production build.

The MTR checkout also contains upstream dependency JARs in `libs/`, including Transport Simulation Core and the Shadow libraries. Keep those files in place. The source licenses and open attribution issues are described in [THIRD_PARTY.md](THIRD_PARTY.md).

## Build and test

From the directory containing `mtr` and `mappings`, set these paths to the files you supplied:

```sh
MTR_LOG4J=/absolute/path/to/gtnh-libraries
MTR_DEPENDENCIES=/absolute/path/to/port-dependencies
MTR_ANGELICA=/absolute/path/to/angelica-2.2.28.jar

cd mtr
./gradlew -p gtnh test productionBuild \
  "-PmtrLog4j=$MTR_LOG4J" \
  "-PmtrDependencies=$MTR_DEPENDENCIES" \
  "-PmtrAngelica=$MTR_ANGELICA"
```

`productionBuild` prepares the Core compatibility output and delegates to the Mappings backend's `reobfPortJar` task. It passes the properties to that nested build. The production mod is written to `mtr/gtnh/build/libs/MTR-GTNH-4.0.5-gtnh.1.jar`, relative to the shared checkout directory.

Run the Mappings backend tests separately, using the same path variables:

```sh
cd ../mappings
./gradlew -p forge/1.7.10-mapping test \
  "-PmtrLog4j=$MTR_LOG4J" \
  "-PmtrDependencies=$MTR_DEPENDENCIES" \
  "-PmtrAngelica=$MTR_ANGELICA"
```

The backend passes the properties to its nested Core preparation task. Its test reports are under `mappings/forge/1.7.10-mapping/build/reports/tests/test/`.

The wrappers, plugins and Maven dependencies use online repositories, including the GTNH Nexus public repository, Maven Central and Mojang's library repository. A sibling `local-maven` directory can serve as a fallback cache for the configured plugin. An offline build requires a separately populated cache; no fresh offline checkout build is claimed.

These build tasks were checked in isolated source checkouts using pinned local dependencies: 283 backend tests and six Core compatibility tests passed. All 23,386 file payloads in the rebuilt mod matched the accepted singleplayer artifact, with no test probes. The ZIP archive hashes differ, so this establishes matching contents rather than a byte-identical JAR rebuild. Retrieval of every external input on a clean machine remains untested.
