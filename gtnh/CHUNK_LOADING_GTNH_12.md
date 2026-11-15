# Chunk-loading fix in MTR gtnh.12

MTR gtnh.12 fixes a client crash while receiving chunk data containing a block whose entity factory deliberately returns null. The reported stack reached `BlockExtension.createTileEntity` through Angelica and `S26PacketMapChunkBulk`.

Middle sections of long MTR railway signs have no block entity. YTE's PAT-RS01 signs use the same convention. The compatibility layer now respects that result. If a block needs storage for an extended state, it receives the existing state-only container. Functional block entities retain their original objects, types and data. No world conversion is required.

Replace the previous MTR JAR on both client and server. Keep one MTR JAR in `mods`; YTE gtnh.1 remains unchanged.

Validation reproduced the exact original NPE before the change, then passed all 348 mapping tests. A fresh hidden singleplayer copy used original items to place long MTR and YTE signs and horizontal and vertical MTR PIDS on platform blocks. A real server-to-client bulk chunk packet then exercised client chunk loading. All twelve sign and PIDS parts retained their expected states and native/mapped entity types, including four middle sections with no entity. The client rendered a real framebuffer and exited normally without a crash report.

The native test used an integrated server and the real client packet path. A separate dedicated server and the user's remote world were not tested. The crash report does not identify the specific block in its chunk. Assets are byte-identical to gtnh.11; only the factory bridge and version metadata changed. Test probes and the hidden-window runtime are excluded from the release JAR.
