# Dedicated-server lift fixes

In gtnh.7, the server binds transport-data subscriptions to the UUID of the player on the connection. On an offline-mode server, that UUID can differ from the account UUID stored in the client's launcher session. Earlier versions subscribed the account UUID, failed to find that player on the server, and removed the subscription. The client then received no lifts or vehicles; lift buttons also had no floor data to display. Position, update radius and all six existing-ID lists remain intact.

The identity regression sends the real `PacketRequestData` through its server handler. It checks the UUID used by Core and compares the remaining serialized request fields. Native checks use a synthetic client-session UUID v4 and a different server-assigned UUID v3, with four connected floors at Y72, 112, 152 and 192. This reproduces the identity difference that the previous matching-UUID fixture missed.

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

Use the same `MTR-GTNH-4.0.5-gtnh.7.jar` on the client and server. Stop each process before replacing its old MTR JAR, and keep one MTR JAR in each `mods` directory. No recipe-script changes are needed.

A copied GTNH 2.9.0-RC-2 server and one copied client provide the native verification environment. The user's remote world and server configuration are unavailable locally.

The native regression source is `gtnh/src/probe/java/org/mtr/gtnh/probe/DedicatedLiftProbe.java`, selected in the mapping project with `-PmtrGameProbe=true -PmtrDedicatedLiftProbe=true`. It requires an accountless client and UUID-owned local server fixture with the configured ownership markers. Normal production builds exclude every probe class.

With different client and server UUIDs, the unchanged gtnh.6 created a valid four-floor lift on the server but removed the client's subscription. The client received no lift and never opened the customization screen. The same fixture on gtnh.7 retained the subscription under the server player's UUID, delivered the lift and its rendering wrapper, and opened the original screen after each individual click on Floor Track and Vertical Track. All four panel-destroy effects survived. Both processes exited with code 0, with no crash reports or remaining listener.

The Forge suite passed 328 tests, including both identity regressions. The unchanged core/cache suite passed 38 tests in the previous gtnh.6 run; it was not rerun for this packet fix. This check covers data delivery and the lift screen. It does not separately measure the rendered floor-number pixels or ride smoothness. The user's remote installation still needs gtnh.7 installed on both sides to confirm its result.
