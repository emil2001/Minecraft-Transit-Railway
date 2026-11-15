"""Independent progression checks; does not start Minecraft or import the generator."""
import collections
import json
from fractions import Fraction
from pathlib import Path
import re
import unittest

HERE = Path(__file__).resolve().parent
REPO = HERE.parents[1]
RANK = {"Steam": 0, "LV": 1, "MV": 2, "HV": 3, "EV": 4}
VOLTAGE = {"LV": 30, "MV": 120, "HV": 480, "EV": 1920}


def originals():
    result = {}
    for path in (REPO / "fabric/src/main/resources/data").glob("*/recipes/*.json"):
        data = json.loads(path.read_text())
        namespace = path.parents[1].name
        result[namespace + ":" + path.stem] = data
    return result


def original_inputs(recipe):
    if recipe["type"].endswith("shapeless"):
        return recipe["ingredients"]
    return [recipe["key"][symbol] for row in recipe["pattern"] for symbol in row if symbol != " "]


def is_mtr(value):
    return value.startswith(("mtr:", "mtrsteamloco:"))


def required_tiers():
    expected = {key: "MV" for key in ["lift_refresher", "escalator", "lift_buttons_link_connector",
        "dashboard_3", "cable_car_node_lower", "cable_car_node_station", "cable_car_node_upper",
        "rail_connector_cable_car", "rail_connector_platform", "rail_connector_siding"]}
    expected.update({key: "HV" for key in ["rail", "dashboard", "boat_node", "dashboard_2", "rail_connector_turn_back"]})
    expected.update({key: "EV" for key in ["airplane_node", "dashboard_4", "rail_connector_runway"]})
    for speed in (20, 40, 60, 80, 100, 120, 140, 160, 200, 300):
        for suffix in ("", "_one_way"):
            expected[f"rail_connector_{speed}{suffix}"] = "HV" if speed <= 120 else "EV"
    for height in (4, 5, 6):
        for width in (3, 5, 7, 9):
            expected[f"tunnel_creator_{height}_{width}"] = "EV"
    return {"mtr:" + key: tier for key, tier in expected.items()}


def split_arguments(text):
    parts, start, depth = [], 0, 0
    for index, char in enumerate(text):
        if char in "([<":
            depth += 1
        elif char in ")]>":
            depth -= 1
        elif char == "," and depth == 0:
            parts.append(text[start:index].strip())
            start = index + 1
    assert depth == 0, "Unbalanced generated recipe call"
    parts.append(text[start:].strip())
    return parts


def bracket(expression):
    match = re.fullmatch(r"\s*<([^>]+)>\s*(\.firstItem)?\s*(?:\.withAmount\((\d+)\)|\*\s*(\d+))?\s*", expression)
    assert match, "Unexpected ingredient expression: " + expression
    name, canonical, method_amount, multiply_amount = match.groups()
    kind, identifier, meta = "item", name, 0
    if name.startswith("ore:"):
        kind, identifier = "ore", name[4:]
    elif name.startswith("liquid:"):
        kind, identifier = "fluid", name[7:]
    elif name.rsplit(":", 1)[-1].isdigit():
        identifier, metadata = name.rsplit(":", 1)
        meta = int(metadata)
    return kind, identifier, meta, int(method_amount or multiply_amount or 1), bool(canonical)


class RecipeProgressionTest(unittest.TestCase):
    def load_profile(self):
        path = HERE / "profile.json"
        self.assertTrue(path.is_file(), "The agreed recipe profile has not been implemented")
        profile = json.loads(path.read_text())
        self.assertEqual(profile["schema"], 1)
        return profile

    def test_original_coverage_and_functional_replacements(self):
        old = originals()
        self.assertEqual(len(old), 308)
        outputs = {r["result"]["item"] for r in old.values()}
        self.assertEqual(len(outputs), 261)
        profile = self.load_profile()
        replacements = profile["replacements"]
        replaced = {r["output"] for r in replacements}
        self.assertEqual(len(replaced), len(replacements), "Each replacement output needs one controlled base recipe")
        self.assertFalse(replaced - outputs, "Do not add unregistered/new MTR items")
        prefixes = ("lift_", "escalator", "rail_connector_", "rail_remover", "dashboard", "airplane_node",
            "boat_node", "cable_car_node_", "signal_", "ticket_", "train_", "tunnel_creator_", "psd_", "apg_")
        functional = {item for item in outputs if item.startswith("mtr:") and
            (item.split(":", 1)[1].startswith(prefixes) or item == "mtr:rail")}
        self.assertFalse(functional - replaced, "Original functional recipes still allow cheaper crafting")
        restored = profile["restore"]
        self.assertEqual(len(set(restored)), len(restored))
        self.assertFalse(set(restored) - old.keys())
        self.assertTrue(all(old[key]["result"]["item"] in replaced for key in restored))
        self.assertIn("mtrsteamloco:eye_candy", outputs)

    def test_machine_limits_and_tier_components(self):
        for recipe in self.load_profile()["replacements"]:
            with self.subTest(output=recipe["output"]):
                self.assertIn(recipe["tier"], RANK)
                self.assertTrue(1 <= recipe["count"] <= 64)
                self.assertTrue(recipe["inputs"])
                for ingredient in recipe["inputs"]:
                    self.assertIn(ingredient["kind"], ("item", "ore"))
                    selector = ingredient["kind"] == "item" and ingredient["id"] == "gregtech:gt.integrated_circuit"
                    self.assertTrue((0 if selector else 1) <= ingredient["amount"] <= 64)
                    if selector:
                        self.assertTrue(1 <= ingredient.get("meta", -1) <= 24)
                        self.assertEqual(ingredient["amount"], 0, "Configured selectors must not be consumed")
                    self.assertRegex(ingredient["id"], r"^[a-zA-Z0-9_:./-]+$")
                    if ingredient["kind"] == "item":
                        self.assertIn(":", ingredient["id"])
                    else:
                        self.assertNotIn(":", ingredient["id"])
                if recipe["method"] == "assembler":
                    self.assertLessEqual(len(recipe["inputs"]), 6)
                    self.assertEqual(recipe["eut"], VOLTAGE[recipe["tier"]])
                    self.assertGreater(recipe["duration"], 0)
                    circuits = [i for i in recipe["inputs"] if i["kind"] == "ore" and i["id"].startswith("circuit")]
                    self.assertLessEqual(len(circuits), 1, "Avoid Cartesian expansion of several circuit ore inputs")
                    self.assertTrue(all(not i.get("canonical", False) for i in circuits), "Accept equivalent tier circuits")
                    self.assertTrue(all(i.get("canonical", False) for i in recipe["inputs"]
                        if i["kind"] == "ore" and not i["id"].startswith("circuit")), "Bound material ore expansion")
                    if recipe.get("fluid"):
                        self.assertTrue(0 < recipe["fluid"]["amount"] <= 64000)
                else:
                    self.assertIn(recipe["method"], ("shaped", "shapeless"))
                    self.assertLessEqual(sum(i["amount"] for i in recipe["inputs"]), 9)

    def test_operating_items_have_agreed_machine_tiers(self):
        profile = self.load_profile()
        by_output = {r["output"]: r for r in profile["replacements"]}
        for output, tier in required_tiers().items():
            with self.subTest(output=output):
                self.assertIn(output, by_output)
                recipe = by_output[output]
                self.assertEqual(recipe["tier"], tier)
                self.assertEqual(recipe["method"], "assembler")
                self.assertEqual(recipe["eut"], VOLTAGE[tier])
                circuit = {"MV": "circuitGood", "HV": "circuitAdvanced", "EV": "circuitData"}[tier]
                self.assertTrue(any(i["kind"] == "ore" and i["id"] == circuit for i in recipe["inputs"]),
                    "Control tools and transport nodes must use their tier's circuits")

    def test_replacements_have_distinct_input_signatures(self):
        seen = {}
        for recipe in self.load_profile()["replacements"]:
            if recipe["method"] == "assembler":
                # GT recipe lookup can collide even if output, duration or input quantities differ.
                inputs = sorted((i["kind"], i["id"], i.get("meta", 0)) for i in recipe["inputs"])
                key = ("assembler", tuple(inputs), recipe.get("fluid", {}).get("id"))
            else:
                inputs = sorted((i["kind"], i["id"], i.get("meta", 0), i["amount"]) for i in recipe["inputs"])
                key = (recipe["method"], tuple(inputs), tuple(recipe.get("pattern", [])))
            self.assertNotIn(key, seen, f"Ambiguous inputs: {recipe['output']} and {seen.get(key)}")
            seen[key] = recipe["output"]

    def test_machine_input_sets_do_not_shadow_each_other(self):
        machines = [r for r in self.load_profile()["replacements"] if r["method"] == "assembler"]
        for small in machines:
            small_inputs = {(i["kind"], i["id"], i.get("meta", 0)) for i in small["inputs"]}
            for large in machines:
                if small is large or small.get("fluid", {}).get("id") != large.get("fluid", {}).get("id"):
                    continue
                large_inputs = {(i["kind"], i["id"], i.get("meta", 0)) for i in large["inputs"]}
                # A full input stack/tank can satisfy either quantity. GT tries the
                # previously used recipe first and permits unused input items.
                self.assertFalse(small_inputs <= large_inputs,
                    f"Cached recipe {small['output']} can shadow {large['output']}; use distinct selectors")

    def effective_originals(self, profile):
        targets = {r["output"] for r in profile["replacements"]}
        restore = set(profile["restore"])
        return {key: value for key, value in originals().items() if
            value["result"]["item"] not in targets or key in restore}

    def test_retained_conversions_do_not_bypass_progression(self):
        profile = self.load_profile()
        paths = []
        for recipe in self.effective_originals(profile).values():
            inputs = {i["item"] for i in original_inputs(recipe) if "item" in i and is_mtr(i["item"])}
            paths.append((recipe["result"]["item"], inputs, 0))
        for recipe in profile["replacements"]:
            inputs = {i["id"] for i in recipe["inputs"] if i["kind"] == "item" and is_mtr(i["id"])}
            paths.append((recipe["output"], inputs, RANK[recipe["tier"]]))
        reachable = collections.defaultdict(lambda: 99)
        for _ in range(len(paths) + 1):
            changed = False
            for output, inputs, process in paths:
                tier = max([process] + [reachable[item] for item in inputs])
                if tier < reachable[output]:
                    reachable[output] = tier
                    changed = True
            if not changed:
                break
        for output, tier in required_tiers().items():
            self.assertEqual(reachable[output], RANK[tier], f"Cheaper path or unreachable item: {output}")
        self.assertTrue(all(reachable[r["result"]["item"]] < 99 for r in originals().values()),
            "A removed base recipe left an output unreachable")

    def test_no_free_item_multiplication_cycles(self):
        profile = self.load_profile()
        edges = []
        for recipe in self.effective_originals(profile).values():
            inputs = original_inputs(recipe)
            if not all("item" in i and is_mtr(i["item"]) for i in inputs):
                continue
            counts = collections.Counter(i["item"] for i in inputs)
            if len(counts) == 1:
                item, count = next(iter(counts.items()))
                edges.append((item, recipe["result"]["item"], Fraction(recipe["result"].get("count", 1), count)))
        for recipe in profile["replacements"]:
            inputs = recipe["inputs"]
            if recipe["method"] != "assembler" and all(i["kind"] == "item" and is_mtr(i["id"]) for i in inputs):
                counts = collections.Counter()
                for i in inputs:
                    counts[i["id"]] += i["amount"]
                if len(counts) == 1:
                    item, count = next(iter(counts.items()))
                    edges.append((item, recipe["output"], Fraction(recipe["count"], count)))
        nodes = {v for edge in edges for v in edge[:2]}
        gain = {node: Fraction(1) for node in nodes}
        for step in range(len(nodes)):
            changed = False
            for source, target, ratio in edges:
                if gain[source] * ratio > gain[target]:
                    gain[target] = gain[source] * ratio
                    changed = True
            if not changed:
                break
            self.assertLess(step, len(nodes) - 1, "A retained conversion cycle creates free items")
        self.assertFalse(any(key.startswith("mtr:tunnel_creator_") for key in profile["restore"]))
        tunnel = [r for r in profile["replacements"] if r["output"].startswith("mtr:tunnel_creator_")]
        self.assertEqual(len(tunnel), 12)
        self.assertTrue(all(r["count"] == 1 for r in tunnel))

    def test_script_removes_all_replaced_outputs(self):
        profile = self.load_profile()
        path = HERE / "MTR-GTNH.zs"
        self.assertTrue(path.is_file(), "Standalone installation script missing")
        text = path.read_text()
        removed = re.findall(r"recipes\.remove\(\s*<([^>]+)>\s*\)", text)
        self.assertEqual(set(removed), {r["output"] for r in profile["replacements"]})
        self.assertEqual(len(removed), len(set(removed)))
        self.assertNotIn("RA2", text, "Use the checked legacy assembler API")
        self.assertNotIn("/mt reload", text, "Machine additions are not undoable")
        self.assertEqual(len(re.findall(r"Assembler\.addRecipe\(", text)),
            sum(r["method"] == "assembler" for r in profile["replacements"]))

    def test_emitted_machine_calls_match_recipe_profile(self):
        profile = self.load_profile()
        path = HERE / "MTR-GTNH.zs"
        self.assertTrue(path.is_file())
        text = re.sub(r"//[^\n]*", "", path.read_text())
        actual = {}
        for body in re.findall(r"Assembler\.addRecipe\((.*?)\);", text, re.S):
            args = split_arguments(body)
            self.assertEqual(len(args), 5)
            output = bracket(args[0])
            self.assertEqual(output[0], "item")
            self.assertTrue(args[1].startswith("[") and args[1].endswith("]"))
            inputs = sorted(bracket(arg) for arg in split_arguments(args[1][1:-1]))
            fluid = None if args[2] == "null" else bracket(args[2])
            self.assertNotIn(output[1], actual)
            actual[output[1]] = (output[3], inputs, fluid, int(args[3]), int(args[4]))
        expected = {}
        for recipe in profile["replacements"]:
            if recipe["method"] != "assembler":
                continue
            inputs = sorted((i["kind"], i["id"], i.get("meta", 0), i["amount"], i.get("canonical", False))
                for i in recipe["inputs"])
            fluid = recipe.get("fluid")
            expected[recipe["output"]] = (recipe["count"], inputs,
                ("fluid", fluid["id"], 0, fluid["amount"], False) if fluid else None,
                recipe["duration"], recipe["eut"])
        self.assertEqual(actual, expected, "Emitted machine calls changed an amount, ingredient, tier or output")

    def test_emitted_crafting_and_conversions_match_recipe_profile(self):
        profile = self.load_profile()
        path = HERE / "MTR-GTNH.zs"
        self.assertTrue(path.is_file())
        text = re.sub(r"//[^\n]*", "", path.read_text())
        self.assertNotIn("recipes.addShaped(", text, "This profile only retains shapeless MTR conversions")
        actual = []
        for body in re.findall(r"recipes\.addShapeless\((.*?)\);", text, re.S):
            args = split_arguments(body)
            self.assertEqual(len(args), 2)
            output = bracket(args[0])
            self.assertTrue(args[1].startswith("[") and args[1].endswith("]"))
            inputs = sorted(bracket(arg) for arg in split_arguments(args[1][1:-1]))
            actual.append((output, tuple(inputs)))
        expected = []
        for recipe in profile["replacements"]:
            if recipe["method"] == "assembler":
                continue
            self.assertEqual(recipe["method"], "shapeless")
            inputs = sorted((i["kind"], i["id"], i.get("meta", 0), 1, i.get("canonical", False))
                for i in recipe["inputs"] for _ in range(i["amount"]))
            expected.append((("item", recipe["output"], 0, recipe["count"], False), tuple(inputs)))
        old = originals()
        for key in profile["restore"]:
            recipe = old[key]
            self.assertEqual(recipe["type"], "minecraft:crafting_shapeless")
            self.assertTrue(all("item" in i and is_mtr(i["item"]) for i in recipe["ingredients"]))
            inputs = sorted(("item", i["item"], 0, 1, False) for i in recipe["ingredients"])
            expected.append((("item", recipe["result"]["item"], 0, recipe["result"].get("count", 1), False), tuple(inputs)))
        self.assertCountEqual(actual, expected, "A generated crafting/conversion recipe differs from its reviewed inputs")


if __name__ == "__main__":
    unittest.main(verbosity=2)
