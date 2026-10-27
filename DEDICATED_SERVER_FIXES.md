# Dedicated-server lift fixes

In gtnh.6, Lift Refresher keeps its request to open the customization screen until the client's canonical lift data arrives. Previously, a new lift could be created on the server while the early open packet found no client-side lift and silently did nothing. The request now retries after the lift update, expires after ten seconds, and cancels if the world or current screen changes. Existing lifts still open immediately.

The focused regression requires the original customization screen after each individual native mouse click. It reproduces the failure on gtnh.5 even though the simulator already contains the new lift. Earlier acceptance checked the combined result of two clicks and missed this failure.

The panel fix from gtnh.5 is retained. Et Futurum's destroy-particle handler can ask a removed panel for its collision shape after the client has already replaced it with air. The Forge bridge checks state ownership and uses the queried block's default state for late requests. Existing blocks keep their actual orientation.

GTNH writes the MTR logger to the server's `logs/fml-server-latest.log`. A valid track click records:

```text
Lift refresher scan at <x, y, z>: <count> connected floors
```

A click on another block records:

```text
Lift refresher rejected target at <x, y, z>: <translation key> (<native block class>)
```

These diagnostics distinguish a rejected target from floor discovery and screen delivery. Lift creation needs at least two connected Floor Tracks.

Use the same `MTR-GTNH-4.0.5-gtnh.6.jar` on the client and server. Stop each process before replacing its old MTR JAR, and keep one MTR JAR in each `mods` directory. No recipe-script changes are needed.

A copied GTNH 2.9.0-RC-2 server and one copied client provide the native verification environment. The user's remote world and server configuration are unavailable locally.

The native regression source is `gtnh/src/probe/java/org/mtr/gtnh/probe/DedicatedLiftProbe.java`, selected in the mapping project with `-PmtrGameProbe=true -PmtrDedicatedLiftProbe=true`. It requires an accountless client and UUID-owned local server fixture with the configured ownership markers. Normal production builds exclude every probe class.

The gtnh.6 native run passed both individual click checks, received all four panel-destroy effects without a crash, and shut down client and server with exit code 0. The unchanged gtnh.5 failed the new-lift screen check before the fix. The Forge suite passed 326 tests and the core/cache suite passed 38. The remote server still needs the updated JAR installed to confirm its result.
