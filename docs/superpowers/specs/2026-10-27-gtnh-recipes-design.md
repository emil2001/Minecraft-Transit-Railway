# GTNH recipe progression

## Intent

Integrate MTR 4.0.5-gtnh.2 into GT New Horizons 2.9.0-RC-2 survival progression. The user chose MV lifts and escalators, HV railways, and EV high-speed transport. Work and testing stay in the copied client.

## Delivery

Use a pack-local CraftTweaker script, `scripts/MTR-GTNH.zs`, with the installed CraftTweaker 3.4.8 and GTTweaker 2.3.4. This keeps recipe tuning separate from the mod JAR and makes rollback a file removal followed by a restart.

Embedding GregTech recipes in the JAR would couple the port to this pack's GregTech version. A separate integration mod would add a build and another JAR for the same recipe changes. The script is the smallest suitable option; this is the implementer's choice.

## Progression

| Stage | Infrastructure | Main ingredients and process |
| --- | --- | --- |
| Steam / LV | Building materials, platforms, signs, poles, basic lights and hand tools | Stone, glass, steel plates and rods; simple crafting or low-voltage processing; bulk outputs |
| LV / MV | Information displays, ticket equipment, signals and platform doors | Circuits, cables, sensors, pistons and solder; assembler recipes scaled by function |
| MV | Lifts, escalators, cable cars and the platform/siding tools shared with them | MV motors, conveyors, pistons and circuits; steel structure; MV assembler for operating tools and machinery |
| HV | Railway dashboard, ordinary rail tools up to 120 km/h, rail nodes and boats | HV circuits and components; steel and stainless steel; HV assembler for control tools |
| EV | Rail tools from 140 to 300 km/h, aircraft and tunnel construction tools | EV circuits and components; titanium where appropriate; EV assembler |

The 120/140 km/h boundary, cable-car/boat/aircraft placement, and details within the LV/MV equipment group are proposed defaults. No new items are needed.

Platform and siding connectors are shared between transport modes. They must be available at MV for cable cars to work. Train-specific nodes and the railway dashboard remain HV, so these shared tools alone cannot create a railway network.

## Recipe coverage

The port currently registers 308 recipes for 261 outputs. Cover every output explicitly: replace functional base recipes, retain inexpensive decorative recipes and conversions where they preserve the intended progression, and remove cheaper alternative recipes for gated outputs. Restore necessary decorative conversions if output-wide removal also deletes them.

Keep construction practical: costly reusable tools can gate a tier, while consumed track sections, doors, panels and building materials have bulk outputs. Do not charge a complete motor and control system for every structural lift guide or passive panel.

Replace all 35 tunnel-tool recipes with one controlled recipe for each of the 12 sizes. Their existing one-tool-to-two-tool conversions form duplication loops. Preserve ordinary color and size conversions only after checking for a route around the tier gates or net item multiplication.

Use components and OreDictionary names verified against the installed GregTech 5.09.54.205. Machine recipes must use the installed GTTweaker API and valid amounts, durations and voltages. Remove the original cheaper recipe before adding a replacement.

### Balance examples

These establish the intended cost scale; the final recipe table will list every input and output.

| Output | Proposed inputs | Process |
| --- | --- | --- |
| 1 lift refresher | 2 MV motors, 2 MV pistons, 2 MV circuits, 8 steel plates, 4 copper cables, 144 mB soldering alloy | Assembler, 30 s, 120 EU/t |
| 4 escalator sections | 1 MV motor, 1 MV conveyor, 4 small steel gears, 8 steel plates, 4 rubber plates, 2 copper cables | Assembler, 20 s, 120 EU/t |
| 1 railway dashboard | 2 HV circuits, 1 HV sensor, 4 stainless-steel plates, 2 glass panes, 2 gold cables, 144 mB soldering alloy | Assembler, 30 s, 480 EU/t |

Structural guides and passive panels use plates and rods in batches. Electronics and powered mechanisms carry the tier cost. Assemblers have at most six item input stacks; recipe amounts must fit their slot limits.

## Mechanical limits

This changes recipes. MTR's rail connectors remain reusable, its lift refresher generates cabins, and its dashboard creates vehicles without consuming a train item. Trains and lifts do not gain EU or fuel consumption.

The EV speed tier gates rail-connector recipes, not individual vehicle models or every speed setting. Existing worlds, shared tools and already-built networks can make later-tier infrastructure available earlier. In particular, a lower-tier dashboard can edit an existing nearby siding belonging to another transport mode. Enforcing ownership, energy use, per-vehicle costs or strict model/speed unlocks would require a separate mechanics change.

## Validation and installation

- Audit every original recipe and conversion for coverage and cheaper alternatives.
- Validate item, fluid and ore references, input/output counts, and machine voltage against the copied pack.
- Load the script in the actual copied pack and inspect registration and representative replacements, including absence of the original cheap functional recipes.
- Preserve the ordinary MTR JAR and existing saves. Keep installation hashes and a rollback record.
- Deliver the script and an English recipe/progression guide. Install only in the copied client's `scripts` directory.

No multiplayer testing or edits to the original client are part of this change.
