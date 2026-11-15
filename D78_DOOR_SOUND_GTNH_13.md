# Door sound reliability in gtnh.13

Vehicle door events are now captured during the persistent vehicle update. A skipped rendering callback no longer loses the opening or closing transition. The next eligible sound callback consumes the event once. Reopening cancels a pending close event, and closing replaces an unplayed opening event. The existing visibility and platform-door checks still decide whether a car can play its sound.

The scheduled sound queue also had two errors. Flushing N distinct samples called the native sound API N² times. It now calls it once per queued sample. Identical samples from several cars now select the source by squared distance from the player's eye position to the source block center. The old Manhattan-distance choice could discard an audible source in favor of a more distant one.

The D78 sound files, volume, pitch and configured closing threshold are unchanged.

## Verification

The original code failed five of eight door-event tests when a playback callback was skipped or repeated. Two queue tests also reproduced duplicate dispatch and the wrong source choice. A separate vertical-distance case reproduced the difference between the player's feet and the native audio listener. All 360 mapping tests passed after the changes.

A hidden singleplayer baseline used one four-car Normal D78 at 40 km/h and a platform observer. Four ordinary departures all queued the closing event and selected its OGG sample in Minecraft's SoundManager. The intermittent report did not recur in those four baseline departures; the skipped-callback failure was demonstrated by the deterministic regression tests.

The release verification report records the candidate run and artifact hashes. Native audio uses OpenAL Soft's null output, so these checks observe events, samples and channels without an acoustic listening test.
