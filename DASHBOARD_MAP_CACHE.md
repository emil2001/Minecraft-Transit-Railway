# Dashboard terrain cache

MTR 4.0.5-gtnh.3 saves terrain drawn in the Dashboard. Viewed terrain remains available after its Minecraft chunks unload, after the Dashboard closes, and after reconnecting or restarting the client.

The cache records terrain while the Dashboard is open. It starts empty when this version is installed and does not import JourneyMap history. Loaded chunks refresh the saved image when viewed again. Rails, stations and routes continue to use MTR's existing data storage.

## Storage

Cached images live under the instance's `.minecraft/mtr/dashboard-map-cache/v1/` directory. Local worlds use a hash of the save directory and world seed; remote servers use a hash of their address. Each identity has a separate dimension directory, then `top/` or a `y_<height>/` directory for the current-height view.

One compressed file stores a 16×16-chunk region. Unknown pixels stay transparent. The client checks the format, coordinates and length before using a file; corrupt or incomplete files are treated as missing images. Writes use a temporary file and an atomic replacement where the filesystem supports it.

To clear the terrain history, close Minecraft and remove `mtr/dashboard-map-cache/` from that instance. It contains map images only. A server that replaces its world while keeping the same address needs a manual cache clear, because this Minecraft version does not send a persistent server-world identifier to the client.

## Performance

File reads, merging and writes run on a background worker. Repeated edits to a region are combined, and unchanged pixels do not cause another write. Closing the Dashboard lets accepted edits finish; opening the same cache again waits on that work in the background.

The client reads only already-loaded chunks. It does not load chunks or keep them loaded for the map. Sampling is limited to two chunks per rendered frame and texture uploads to two region images per frame. Memory residency is limited to 128 region snapshots and 128 region textures; pending work is bounded separately.

At very distant zoom levels, the Dashboard prioritizes up to 128 regions nearest the viewport center. Panning changes which regions are displayed; evicted images remain on disk. This version does not build a low-detail overview of the entire explored world. The disk history grows with the terrain and height layers viewed.

## Verification

Offline tests cover pixel persistence, corruption, atomic replacement, negative coordinates, asynchronous merging, bounded queues and memory, close/reopen ordering, world identity and texture clipping. The port was built without launching Minecraft, as requested. Native rendering, frame times and multiplayer behavior remain unverified.
