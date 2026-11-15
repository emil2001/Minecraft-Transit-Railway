# Building the GTNH port

Use JDK 21 to run the included Gradle 8.14 wrappers. RetrofuturaGradle also needs an installed JDK 8 to compile its Minecraft launcher and patched Minecraft sources. The mod itself targets Java 8 bytecode. Both source repositories and the external inputs below are required.

If Gradle does not discover both JDKs, set `org.gradle.java.installations.paths=/path/to/jdk8,/path/to/jdk21` in your Gradle user properties, or pass that property with `-P`. Run Gradle with `JAVA_HOME` pointing to JDK 21.

## Check out both repositories

Keep the directory names `mtr` and `mappings` as siblings. The build uses those relative source paths. The port branch includes the generated Java inputs and font definition needed by the 1.7.10 build, so this build does not require the upstream `setupFiles` task or translation service credentials.

```sh
mkdir mtr-gtnh
cd mtr-gtnh
git clone --branch fix/boarding-first-packet https://github.com/emil2001/Minecraft-Transit-Railway.git mtr
git clone --branch fix/boarding-first-packet https://github.com/emil2001/Minecraft-Mappings.git mappings
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

Upstream uses the [CodeMC Maven repository](https://repo.codemc.org/repository/maven-public) for this dependency. The pinned bytes are available from CodeMC as `0.0.9-20261016.150747-1`, under the `com/logisticscraft/occlusionculling/0.0.9-SNAPSHOT/` Maven path. Save `occlusionculling-0.0.9-20261016.150747-1.jar` as `occlusionculling-0.0.9-SNAPSHOT.jar`. Its SHA-256 must be `2d9677ef86c768c2cc3c01f117909d7734538ffa29372ff352a22ce9cb10335d`. A different build of the same snapshot version fails the hash check.

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

`productionBuild` prepares the Core compatibility output and delegates to the Mappings backend's `reobfPortJar` task. It passes the properties to that nested build. Core preparation also freezes outbound vehicle/lift updates before publication and makes the save-request flag visible between threads. These hooks are checked against the pinned Core method signatures; an incompatible Core fails the build.

The production mod is written to `mtr/gtnh/build/libs/MTR-GTNH-4.0.5-gtnh.21.jar`, relative to the shared checkout directory.

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

The gtnh.20 source build passed 59 Core tests and 388 backend tests with no failures or errors and one optional addon-resource skip. The Core tests include real manual/threaded schedulers in two dimensions, detached S2C/callback payloads and shared HTTP cache concurrency. The backend tests cover numeric cabin acceptance, expired reads, world/source changes and existing passenger/key authorization checks. All eight France mapping tests passed with addon resources present. The dedicated-server ticket regression exercised actual ticket accounting with a FakePlayer; a connected-player barrier walkthrough was outside that check. The ordinary JAR contains no test probes. The external inputs above were retrieved and the production task completed in the recovered build environment. The gtnh.21 follow-up passed 59 Core tests and 392 backend tests, with the same optional skip. Its three new regressions check the first passenger packet and repeated or overlapping doorway frames. A connected-player test on a copied GTNH 2.9.0 dedicated server confirmed same-frame initial sending and server authorization within 90 ms on an existing lift. The shutdown regression exercises the actual native packet sink before and after stopping the server. A connected-player shutdown completed without the previous queue rejection. Other intermittent boarding failures remain unconfirmed.
