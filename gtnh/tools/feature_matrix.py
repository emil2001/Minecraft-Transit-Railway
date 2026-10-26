"""Index pinned source registrations and resources; rows are not runtime counts."""
import csv
import json
import re
from collections import Counter
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
GAME = ROOT / "fabric/src/main/java/org/mtr/mod"
ASSIGNMENT = re.compile(r"^\s*([A-Z][A-Z0-9_]*)\s*=\s*(?:Init\.REGISTRY\.)?((?:register\w+|createCreativeModeTabHolder))\(")
PACKET = re.compile(r"REGISTRY\.registerPacket\((\w+)\.class,")
RENDERER = re.compile(r"REGISTRY_CLIENT\.registerBlockEntityRenderer\((BlockEntityTypes\.\w+),")
IDENTIFIER = re.compile(r'new Identifier\(Init\.MOD_ID, "([^"\n]+)"\)')


def registration(line):
    match = ASSIGNMENT.search(line)
    return match.groups() if match else None


assert registration('ESCALATOR_STEP = registerBlock(new Identifier(Init.MOD_ID, "escalator_step"), f);') == ("ESCALATOR_STEP", "registerBlock")
assert registration('BRUSH = Init.REGISTRY.registerItem(id, f);') == ("BRUSH", "registerItem")
assert registration('return Init.REGISTRY.registerBlock(id, f);') is None

rows = []


def add(kind, symbol, identifier, path, line, api, acceptance):
    rows.append((kind, symbol, identifier, str(path.relative_to(ROOT)), line, api, acceptance, "pending", "not_run"))


for file_name, kind, api in [
    ("Blocks", "block_registration_call", "Registry.registerBlock / BlockExtension"),
    ("Items", "item_registration_call", "Registry.registerItem / ItemExtension"),
    ("BlockEntityTypes", "tile_registration_call", "Registry.registerBlockEntityType / BlockEntityExtension"),
    ("SoundEvents", "sound_registration_call", "Registry.registerSoundEvent"),
    ("CreativeModeTabs", "creative_tab_call", "Registry.createCreativeModeTabHolder"),
]:
    path = GAME / (file_name + ".java")
    for number, line in enumerate(path.read_text().splitlines(), 1):
        parsed = registration(line)
        if parsed:
            symbol, method = parsed
            identifier = IDENTIFIER.search(line)
            identifier = "mtr:" + identifier.group(1) if identifier else "dynamic_or_indirect"
            test = "task6_lifts_escalators" if "lift" in line.lower() or "escalator" in line.lower() else "task7_full_transport"
            add(kind, file_name + "." + symbol, identifier, path, number, api + " (" + method + ")", test)

for path in [GAME / "Init.java", GAME / "InitClient.java"]:
    source = path.read_text()
    packet_count = 0
    for number, line in enumerate(source.splitlines(), 1):
        packet = PACKET.search(line)
        renderer = RENDERER.search(line)
        if packet:
            packet_count += 1
            add("packet", packet.group(1), "type_registration", path, number, "Registry.registerPacket / FML", "task3_network_roundtrip_and_limits")
        if renderer:
            add("tile_renderer", renderer.group(1), "renderer_registration", path, number, "RegistryClient.registerBlockEntityRenderer / TESR", "task4_graphics_angelica")
    assert packet_count == source.count("REGISTRY.registerPacket(")

for path in sorted((GAME / "screen").glob("*.java")):
    add("screen_source", path.stem, "source_file", path, 1, "ScreenExtension / GraphicsHolder / widgets", "task4_gui_input_and_scaling")

for path in sorted((ROOT / "fabric/src/main/resources/assets/mtr").rglob("*")):
    if path.is_file():
        add("resource", str(path.relative_to(ROOT / "fabric/src/main/resources")), "resource_file", path, 1,
            "CustomResourceLoader / RegistryClient", "task4_resource_loading_and_reload")

for path in sorted((ROOT / "buildSrc/src/main/resources/schema/config").glob("*.json")):
    add("config_schema", path.stem, "schema_file", path, 1, "generated config / config serialization", "task9_config_restart")

assert any(row[1] == "Blocks.ESCALATOR_STEP" for row in rows)
assert any(row[1] == "Blocks.LIFT_BUTTONS_1" for row in rows)
assert any(row[0] == "packet" for row in rows)
assert len(rows) == len({(row[0], row[1]) for row in rows})
output = ROOT / "gtnh/feature-matrix.tsv"
with output.open("w", newline="") as stream:
    writer = csv.writer(stream, delimiter="\t", lineterminator="\n")
    writer.writerow(["kind", "source_symbol", "identifier", "source", "line", "backend_api", "acceptance", "compile_status", "game_status"])
    writer.writerows(rows)
print(json.dumps({"rows": len(rows), "by_kind": dict(Counter(row[0] for row in rows)), "runtime_counts": False}))
