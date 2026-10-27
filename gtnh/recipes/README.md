# MTR recipes for GT New Horizons

This profile adds MV lifts, escalators and cable cars, HV railway infrastructure and boats, and EV high-speed rail tools and aircraft. Powered equipment uses GregTech components and assembler recipes. Structural parts come in batches so a station or lift shaft remains practical to build.

The profile targets MTR 4.0.5-gtnh.2 in GTNH 2.9.0-RC-2, with GregTech 5.09.54.205, CraftTweaker 3.4.8 and GTTweaker 2.3.4.

It replaces 211 original recipes with 164 controlled base recipes: 105 for the assembler and 59 for crafting. Another 97 original recipes stay in place, and 26 safe conversions are restored. All 261 original output items remain reachable in the static progression check.

## Install

1. Copy `MTR-GTNH.zs` into the instance's `.minecraft/scripts/` directory.
2. Keep the existing MTR JAR in `mods/`.
3. Restart Minecraft, then open a world. CraftTweaker applies the recipes when the server starts. Check `logs/minetweaker.log` if a recipe is missing.

Use one copy of the script. Restart the process when changing this profile: the installed GTTweaker's machine additions cannot be undone by a script reload.

To remove the profile, remove `MTR-GTNH.zs` and restart Minecraft. Original recipes return at startup. Existing blocks, items and saves use the same registry IDs.

## Progression

| Stage | Equipment |
| --- | --- |
| Steam / LV | Building materials, platforms, signs, poles, lights and passive lift parts |
| LV / MV | Displays, ticket machines, platform doors, signals and sensors |
| MV | Lift refresher, escalators, lift control tools, cable-car dashboard and nodes; shared platform/siding connectors |
| HV | Railway dashboard and nodes, rail connectors from 20 to 120 km/h, turn-back tool, boat dashboard and nodes |
| EV | Rail connectors from 140 to 300 km/h, aircraft dashboard and nodes, runway tool and tunnel tools |

Platform and siding connectors also serve cable cars, so they are available at MV. A new railway still needs HV train nodes and the railway dashboard.

The original tunnel-tool conversions could turn one tool into two and repeat the process indefinitely. This profile replaces all 35 original tunnel recipes with one EV assembly recipe for each size.

## What the recipes control

Recipes control access to MTR items. The existing transport mechanics remain: rail connectors are reusable, the lift refresher generates cabins, and the dashboard creates trains without consuming a train item. Vehicles do not consume EU or fuel.

The EV rail tier unlocks faster connector tools. Vehicle models and dashboard speed settings follow MTR's existing rules. Existing networks and shared tools can make later-tier transport available earlier; a dashboard can also open an existing nearby siding of another transport mode.

Circuits accept the OreDictionary alternatives for their tier. Machine recipes select a single OreDictionary item for each structural material and cable to keep registration size bounded. NEI will show the selected material item.

Some assembler recipes also need a configured integrated circuit. Its zero input amount means it selects the recipe and is kept by the machine. Use the mode listed in the recipe. When switching recipes, remove an old selector if the new recipe does not use it.

The full replacement recipe table is in [RECIPES.md](RECIPES.md).

## Validation

The source includes an independent progression and conversion checker. It checks the 308 original recipes, functional replacements, reachable outputs, tier gates, machine slot limits and item-multiplication cycles. Run it from the repository root:

```sh
python3 gtnh/recipes/test_recipes.py
```

Minecraft was not launched for this delivery, at the user's request. Native recipe registration, machine operation and NEI display remain unverified.

`profile.json` and `generate.py` are authoring files. Only `MTR-GTNH.zs` is needed in the client.
