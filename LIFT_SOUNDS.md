# Lift sounds

The GTNH port plays an arrival bell, a quiet motor loop and sliding-door sounds. They use Minecraft's Blocks volume setting and fade with distance. Only nearby lifts play sounds.

To enable the arrival bell, right-click a **Lift Track Floor** with the MTR brush and select **Play "Ding" Sound**. Set this for each floor where the bell should ring. Existing floor settings are preserved. Motor and door sounds work without this option.

## Replace the recordings

A resource pack can replace these mono Ogg Vorbis files:

| Sound | Resource path | Duration |
|---|---|---:|
| Arrival bell | `assets/mtr/sounds/lift/arrival.ogg` | 0.47 s |
| Motor loop | `assets/mtr/sounds/lift/motor.ogg` | 3.75 s |
| Door opening | `assets/mtr/sounds/lift/door_open.ogg` | 1.60 s |
| Door closing | `assets/mtr/sounds/lift/door_close.ogg` | 1.60 s |

Keep the motor loop seamless. The doors move for 1.6 seconds. The corresponding sound events are `mtr:lift_arrival`, `mtr:lift_motor`, `mtr:lift_door_open` and `mtr:lift_door_close`.

## Recording credits

All four sounds use recordings released under [CC0 1.0](https://creativecommons.org/publicdomain/zero/1.0/):

| Recording | Author | Use |
|---|---|---|
| [Elevator Ding and Door](https://freesound.org/people/maxmaxmaxmaxmaxmaxmax/sounds/439423/) | maxmaxmaxmaxmaxmaxmax | Short arrival bell excerpt |
| [Elevator Approaching Motor](https://freesound.org/people/maxmaxmaxmaxmaxmaxmax/sounds/439419/) | maxmaxmaxmaxmaxmaxmax | Crossfaded motor loop |
| [Elevator_Door](https://opengameart.org/content/elevatordoor) | PagDev | Door opening |
| [Door - Elevator, Closing](https://freesound.org/people/colinpoh/sounds/146342/) | colinpoh | Door closing |

The files were downmixed, filtered and converted to Vorbis. The door recordings were shortened with pitch-preserving time stretching to match the animation. Freesound inputs use public HQ MP3 previews; the opening-door input uses the original WAV. Source URLs, processing steps and file hashes are included in `META-INF/mtr/lift-sounds.json` in the mod JAR.
