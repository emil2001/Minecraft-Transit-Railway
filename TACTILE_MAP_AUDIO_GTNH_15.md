# Tactile-map audio in gtnh.15

Breaking the selected tactile map now releases its native audio source. If another map remains nearby, its next block-entity tick restarts the loop at that map. Removing a map that does not own the loop leaves the selected source playing.

The 1.7.10 block-entity bridge now dispatches native invalidation to the modern `markRemoved2` override. Its base implementation calls native invalidation directly to avoid recursion, and an already invalid tile does not dispatch the hook again. The tactile loop also explicitly stops its native sound before resetting its position.

Three of four focused regressions failed on the original code. They reproduced the missing removal hook and retained native sound ownership. Those four pass after the fix, including immediate channel cleanup, restart, and preservation of the other map's sound. A fifth regression covers low-altitude handover: the old integer distance calculation overflowed when comparing the reset position, preventing a new source from being selected. The comparison now uses double arithmetic. All 365 mapping tests pass.

The game diagnostic places two original tactile-map items, breaks each map through the native player harvesting path, and records actual sound maps, channels and Paulscode sources before and after each removal. Its versioned release report distinguishes the .13 baseline from the .14 candidate. Native checks use a hidden window and OpenAL null output; they do not include an acoustic listening test.

This patch retains the existing chunk-unload behavior and does not add NTE or Joban scripting support.
