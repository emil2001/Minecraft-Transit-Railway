# MTR rail render trace (diagnostic only)

Small, separate Forge 1.7.10 client diagnostic for MTR `4.0.5-gtnh.18`.
It has no France dependency and does not contain a rail-rendering fix.

## Use

1. Build/reobfuscate this source against the `.18` MTR development JAR and
   its Forge mapping classpath. The source root is `src`; the mod entry class
   is `org.mtr.gtnh.railtrace.RailTrace`. Use the reobfuscated JAR in the
   client's `mods` directory. Do not install a development JAR in the game.
2. Open the affected world and stand at the problematic junction.
3. Run `/mtrrailtrace`, then reproduce the flicker normally for 30 seconds.
   `/mtrrailtrace help` describes the command. No operator permission is needed.
4. Read the local paths printed in chat. Files are written to:

   ```text
   <Minecraft directory>/mtr-render-diagnostics/<epoch-ms>-<random-UUID>/
     rail-trace.jsonl
     receipt.json
   ```

Nothing is recorded on login or until the command is entered. Only one
capture can run at a time. Leaving/changing worlds stops it. The deadline
also finalizes the local files if there is no later game callback. Completion
chat requires a player to still be available; both file paths are also printed
when capture starts.
An abrupt process termination can leave incomplete files.

## What the files mean

`rail-trace.jsonl` contains a `start` record, `enter`/`change`/`leave`
transitions observed at native `RenderTickEvent.END`, and up to 10 `frame`
samples per second. Frame samples contain the legacy renderer's camera
position/yaw/pitch, paused/skip-world-render flags, current screen class,
and `[rail ID, shouldRender]` pairs. Missed sampling slots
are not backfilled. Transitions can capture changes between camera samples;
changes that occur entirely between render-end observations can be missed.

Each rail transition contains wrapper/rail/math identity hashes, the raw
math AABB and the wrapper AABB, `shouldRender`, style IDs, effective style
IDs using MTR's default-3D selection rule, direction/flip, and resource
cache state. AABB arrays are `[minX,minY,minZ,maxX,maxY,maxZ]`. Identity hashes
are process-local hints and can theoretically collide. String fields are
limited to 256 characters. No world/server/player names or addresses are
recorded, but camera/AABB coordinates and custom style IDs are present.

Cache observations use reflection to read `CustomResourceLoader.RAILS_CACHE`,
`RailResource.cachedRailResource`, and its existing private `data` field.
They do not call `getOptimizedModel`, `getDynamicVehicleModel`, `preload`,
or `CachedResource.getData`. `populated` means only that the cached `data`
reference is non-null. The immutable pair's `left()`/`right()` references
are read without loading anything. `optimized_wrapper_present` records the
pair's left wrapper; reflection reads that wrapper's `optimizedModel` field
to report the actual `optimized_model_present`. Its existing `meshes` list
is read only for `size()`, reported as `optimized_mesh_count` and
`optimized_meshes_nonempty`. A nonzero mesh count does not guarantee faces
or a successful draw. `dynamic_model_present` separately records the pair's
right model. Their identity hashes are also recorded. No presence flag
proves a model was drawn or rendered
correctly. `empty`, missing resources, and reflection failures are reported
without trying to load a resource. `shouldRender` is the observed culling
flag; it is not proof of a GPU draw. AABB inequality is evidence of different
stored bounds, not by itself a diagnosis.

The neighborhood includes rails whose raw **or** wrapper AABB is within
128 blocks of the camera. The nearest 128 of the first 20,000 scanned
wrappers are retained per observation. Ties use the rail ID. A `leave`
record distinguishes a missing wrapper from leaving the radius or the
capped selection. Frame/receipt counters report selection truncation;
unselected rails have no individual history. Each rail records at most
16 styles and also reports its total style count.

`receipt.json` reports the actual stop reason, elapsed time, observed frames,
samples, transitions, trace record/byte counts, truncation counters, and
any observation/I/O error. A normal stop reason is `deadline`. A limit,
world/view change, or read/write error can stop the capture early.

## Bounds and safety

- Maximum capture time: 30 seconds, checked with a monotonic clock.
- Maximum render-end observations: 18,000; camera samples: 300.
- Maximum JSONL records: 6,000, including the start and frame records.
- Maximum trace: 4 MiB; receipt: 16 KiB. No existing files are overwritten.
- All output is beneath the current client's canonical Minecraft data
  directory. A symlink at the diagnostic root is rejected.
- No network requests, upload, screenshots, commands sent to the server,
  scene construction, teleportation, physics changes, camera changes,
  rail/renderer writes, or lazy model getter calls are made.
- The common mod bootstrap uses a sided proxy. Its server proxy is a no-op
  and does not reference client classes.

Sampling and local I/O add some overhead and can affect timing. Compare the
visible symptom with the recorded state; this trace alone cannot establish
the cause of flicker. Removing the diagnostic JAR removes its command and
event handler. Captured files remain local until you choose to share/delete
them.

## Build from this checkout

Run the prepared Mappings backend with its normal external dependency properties,
plus the following init script and properties (use absolute paths):

```sh
./gradlew -p forge/1.7.10-mapping \
  -I /absolute/path/to/mtr/gtnh/tools/rail-trace/trace.init.gradle \
  -PrailTraceRoot=/absolute/path/to/mtr/gtnh/tools/rail-trace \
  -PrailMtrDev=/absolute/path/to/MTR-GTNH-4.0.5-gtnh.18-dev-all.jar \
  reobfRailTraceJar
```

The ordinary `MTR-Rail-Trace-0.1.jar` is written to this directory's `build/`.
The main MTR build does not include these sources.

The earlier native check recorded one manual 30,001 ms capture with 880 observed
frames, 299 camera samples, all six test rails, no selection truncation and no
observation or I/O error. The generated test scene did not reproduce the reported
flicker. No new JVM run was performed when copying this source into the repository.
