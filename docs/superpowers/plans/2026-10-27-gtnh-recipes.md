# GTNH Recipes Implementation Plan

> **For agentic workers:** Use the approved design and execute these tasks without another approval round. Recipe authoring and independent validation can run in parallel; installation follows validation.

**Goal:** Deliver and install a recipe script that follows the approved MV/HV/EV progression.

**Architecture:** Pack-local CraftTweaker and GTTweaker recipes. Keep the ordinary mod JAR unchanged. Generate a self-contained script from a small explicit recipe profile and preserve only the original conversions that maintain progression.

**Tech Stack:** Python standard library, ZenScript, installed CT 3.4.8 / GTTweaker 2.3.4 / GT 5.09.54.205.

**Spec:** `docs/superpowers/specs/2026-10-27-gtnh-recipes-design.md`

## Global Constraints

- MV lifts/escalators/cable cars; HV ordinary railway/boats; EV rail connectors from 140 km/h/aircraft/tunnel tools.
- Shared platform/siding tools are MV; train nodes and dashboard are HV.
- No new items, EU/fuel consumption, vehicle construction costs or multiplayer tests.
- User's later instruction: do not launch Minecraft. Native startup/NEI checks are deferred and must be reported as unverified.
- Modify only the working source copy and copied client's scripts directory. Preserve the original client, saves and ordinary gtnh.2 JAR.

## Review Focus

- Every original output is accounted for, including eye_candy.
- Output-wide removals restore necessary decorative conversions without cheaper functional recipes.
- Tunnel tools have no multiplication cycles.
- Circuit alternatives keep tier compatibility and exact input amounts; at most six assembler item inputs.
- Script syntax/API compatibility is distinguished from native registration and NEI validation.

## Task 1: Recipe profile and self-contained script

**Ownership:** recipe author owns `gtnh/recipes/profile.json`, `gtnh/recipes/generate.py`, and `gtnh/recipes/MTR-GTNH.zs`. Root owns the independent checker and installation.

- [x] Add an independent failing checker for required tiers, original coverage, recipe removals/conversions, machine limits and duplication risks.
- [x] Create explicit functional replacement recipes and a generator that restores safe original conversions when needed. Passive materials and decor stay affordable.
- [x] Generate `MTR-GTNH.zs`; assert valid brackets, amounts, voltages and deterministic output.
- [x] Run the independent checker and fix all material failures.

## Task 2: Documentation, review and copy-only installation

**Files:** `gtnh/recipes/README.md`, independent checks under `work/gregified-recipes`, delivered artifacts under `outputs/mtr-gtnh/recipes/`.

- [x] Write an English guide with progression, actual recipe table, dependencies, installation, restart/rollback and mechanical limits.
- [x] Have a fresh reviewer check the complete script/profile, circuit alternatives, retained conversions and the approved scope.
- [x] Validate script parsing/API use without launching the game where feasible; do not claim runtime acceptance.
- [x] Install the exact delivered script in the copied client with hashes, backup and rollback record. Assert ordinary JAR unchanged.
- [x] Deliver the script and guide, explicitly reporting that runtime and NEI checks were skipped at the user's request.

No publishing or force pushes are needed for this change.
