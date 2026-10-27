# Dedicated-server lift fixes in gtnh.5

Breaking a lift display panel could crash clients running Et Futurum. Its destroy-particle handler asks the removed block for a collision shape after the client has already replaced it with air. The Forge bridge now checks that the state belongs to the queried block and uses that block's default state for late requests. Existing blocks keep their actual orientation. Collision lists, selection bounds and ray tracing share this check.

The reported Lift Refresher rejection remains unresolved on the user's remote server. In a copied GTNH 2.9.0-RC-2 dedicated-server test, the ordinary mouse handler selected fresh Floor and Vertical tracks through the native camera ray, created a lift and opened the original customization screen. On rejection, gtnh.5 writes this diagnostic to the server's `logs/latest.log`:

```text
Lift refresher rejected target at <x, y, z>: <translation key> (<native block class>)
```

This identifies the block the server actually received. The diagnostic does not change track recognition or lift creation.

Use the same `MTR-GTNH-4.0.5-gtnh.5.jar` on the client and server. Stop each process before replacing its old MTR JAR, and keep one MTR JAR in each `mods` directory. No recipe-script changes are needed.

Verification covers 326 Forge-backend tests and 38 core/cache tests. The new regressions reproduce the original property-ownership exception, cover all four lift-panel variants after removal or replacement, and retain live orientations. A local server and one client also survived all four real block-destroy effect packets and exited normally. These checks do not establish compatibility with every remote server configuration.
