# Third-party materials

The source repositories retain their MIT licenses and Jonathan Ho's copyright notices: [MTR](LICENSE) (2022) and [Minecraft Mappings](https://github.com/emil2001/Minecraft-Mappings/blob/gtnh-1.7.10/LICENSE) (2023). Dependencies, fonts, textures and frontend assets have their own terms. The build-input manifests identify particular files; a matching digest does not establish permission to redistribute them.

The port review recorded eight unresolved attribution issues:

1. The reviewed production JAR has no dedicated root MIT notice files for MTR, Mappings or Core. The local delivery supplied their source license texts separately. Those source notices still need to be accounted for in any future binary distribution.

2. A source MIT license is available for [Transport Simulation Core](https://github.com/Minecraft-Transit-Railway/Transport-Simulation-Core), but the exact source revision corresponding to the supplied upstream `0.0.1` binary has not been established.

3. The Shadow util/net containers contain nested package families whose original component notices and, in many cases, exact versions were absent from the reviewed inputs. Their licenses were not inferred from package names.

4. The reviewed Brigadier and text2speech artifacts and POMs did not provide license declarations or full license texts. This is a gap in the collected materials, not a conclusion that those projects have no licenses.

5. The java-objc-bridge POM declares Apache-2.0. A generic full Apache license text was available from JNA, but java-objc-bridge's project-specific notices were not found.

6. Both bundled Noto fonts declare OFL-1.1 in their TTF metadata. A full Open Font License text was absent from the reviewed project and artifact materials. Exported font metadata does not replace that text.

7. The Public Suffix List notice declares MPL-2.0, but the reviewed Shadow input did not supply the full MPL text. The notice names `PublicSuffixDatabase.gz`; it does not establish a license for all of OkHttp.

8. The copied Minecraft textures and recovered published creator frontend have recorded provenance. A complete separate third-party license inventory for those materials has not been established.

This publication provides the port source branches without a new port binary release. It retains these gaps for review and makes no claim that every redistribution obligation has been fulfilled. [BUILDING.md](BUILDING.md) identifies the external inputs and their pinned manifests.

The GTNH lift sound recordings are CC0-1.0. Their authors, source pages and processing are listed in [LIFT_SOUNDS.md](LIFT_SOUNDS.md). The mod includes their file hashes and provenance in `META-INF/mtr/lift-sounds.json`.
