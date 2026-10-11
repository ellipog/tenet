#!/usr/bin/env python3
"""
gen_examples.py - write the worked examples: one group, one chapter, every feature.

Why one chapter
---------------
The examples used to be twelve questlines, one per mechanism family, because that was the only
way to show every field and every value of every enum: a chapter wears one theme and one
progression mode, so demonstrating sixteen themes took sixteen chapters. Twelve questlines is
also a lot of reading, and the reader who wants to see "how do I write an OR-gate" should not
have to learn which of twelve groups happens to hold one.

So the exhibition is one chapter now: `first_light/first_steps`, a single orrery of about
seventy quests. It keeps the five onboarding quests at its western edge -- their ids and their
mechanics are pinned by `QuestPlaythroughTest`, which walks them by name -- and everything else
orbits a hub: seven arms, each a family of mechanisms, every node carrying the field it
demonstrates in its own description.

What this script is
-------------------
The files are the content; this script is how they are written, so a clone of Tenet can
regenerate them rather than hand-editing seventy JSON documents. It is table-driven: one entry
per quest, a polar layout pass that turns an arm and a slot into canvas coordinates, and a
**coverage check** that fails before anything is written if a task type, a reward mode, a
condition, a shape, a line-axis value or a visibility flag has gone missing. That check is the
generator's half of the contract `QuestIndexTest` enforces on the output; between them, "every
feature" is not a claim in a README, it is something the build refuses to lose.

The script owns `first_light/` and `reward_tables/` and rewrites both from scratch on every
run. Other group folders it only *reports*: an author's own content beside the examples is not
this script's to delete.

Usage
-----
    python tenet/tools/gen_examples.py
"""

from __future__ import annotations

import json
import math
import pathlib
import shutil
import sys
import textwrap

TOOLS = pathlib.Path(__file__).resolve().parent
QUESTS = TOOLS / "quests"
GROUP_ID = "first_light"
CHAPTER_ID = "first_steps"
GROUP_DIR = QUESTS / GROUP_ID
CHAPTER_DIR = GROUP_DIR / CHAPTER_ID
TABLES_DIR = QUESTS / "reward_tables"

SCHEMA_GROUP = "../_schema/group.schema.json"
SCHEMA_CHAPTER = "../../_schema/chapter.schema.json"
SCHEMA_QUEST = "../../_schema/quest.schema.json"

# The descriptions are prose wrapped by hand in every other file in this tree; here the wrapping
# is mechanical so the paragraphs can be written as paragraphs. The width matches the rest of
# the collection closely enough that a regenerated file does not read as machine output.
WRAP = 68


def describe(*paragraphs: str) -> list[str]:
    """One string per rendered line, with a blank line between paragraphs."""
    lines: list[str] = []
    for index, paragraph in enumerate(paragraphs):
        if index:
            lines.append("")
        wrapped = textwrap.wrap(paragraph, width=WRAP, break_long_words=False,
                                break_on_hyphens=False)
        lines.extend(wrapped or [""])
    return lines


def task(kind: str, **fields):
    return {"type": "tenet:" + kind, **fields}


def reward(kind: str, **fields):
    return {"type": "tenet:" + kind, **fields}


def condition(kind: str, **fields):
    return {"type": "tenet:" + kind, **fields}


# ---------------------------------------------------------------------------
# The canvas: seven arms around a hub, and a comet tail entering from the west
# ---------------------------------------------------------------------------

ARMS = {
    "instruments": 90.0,    # north
    "measures": 45.0,       # north-east
    "compass": 0.0,         # east
    "hunt": -45.0,          # south-east
    "treasury": -90.0,      # south
    "clockwork": -135.0,    # south-west
    "veils": 135.0,         # north-west
}

# Radii in canvas units, one per slot. The hub sits at the origin; a slot-0 node is the head of
# its arm. 32 is the canvas grid, and every coordinate is snapped to it so the layout stays
# legible in the editor and the preview.
SLOT_RADIUS = 320
SLOT_STEP = 160


def place(arm: str, slot: int, offset: float) -> tuple[int, int]:
    angle = math.radians(ARMS[arm] + offset)
    radius = SLOT_RADIUS + slot * SLOT_STEP
    x = round(radius * math.cos(angle) / 32) * 32
    # Canvas y grows downward, so the sine is negated and the north arm points up.
    y = round(-radius * math.sin(angle) / 32) * 32
    return x, y


# ---------------------------------------------------------------------------
# Reward tables: named, nested, and the empty band
# ---------------------------------------------------------------------------

TABLES = {
    # Rolled by `the_house_always_wins`. The weight-zero entry is the table's "everyone also
    # gets this", and the last entry is a roll of another table -- nesting.
    "dice": {
        "lootSize": 1,
        "entries": [
            {"weight": 0, "reward": reward("xp", amount=5)},
            {"weight": 6, "reward": reward("item", item="minecraft:iron_ingot", count=4)},
            {"weight": 3, "reward": reward("item", item="minecraft:gold_ingot", count=2)},
            {"weight": 1, "reward": reward("item", item="minecraft:diamond")},
            {"weight": 1, "reward": reward("random", table="dregs")},
        ],
    },
    # The nested half of `dice`.
    "dregs": {
        "entries": [
            {"weight": 0, "reward": reward("xp", amount=5)},
            {"weight": 4, "reward": reward("item", item="minecraft:glowstone_dust", count=4)},
            {"weight": 2, "reward": reward("item", item="minecraft:amethyst_shard", count=2)},
        ],
    },
    # Rolled by `the_dust_draw` twice: once as `loot`, where `emptyWeight` is the chance of
    # nothing on a throw, and once as `all_table`, which grants every entry and ignores the dice.
    "toll": {
        "lootSize": 1,
        "emptyWeight": 4,
        "entries": [
            {"weight": 0, "reward": reward("xp", amount=5)},
            {"weight": 6, "reward": reward("item", item="minecraft:torch", count=8)},
            {"weight": 3, "reward": reward("item", item="minecraft:iron_ingot", count=2)},
            {"weight": 1, "reward": reward("item", item="minecraft:golden_apple")},
        ],
    },
}

# ---------------------------------------------------------------------------
# The catalogue. One entry per quest.
#
# Keys map to JSON fields in `build_quest`; `arm`/`slot`/`offset` become x/y, and an explicit
# `x`/`y` wins (the comet tail is placed by hand so it reads as a line entering the sky).
# ---------------------------------------------------------------------------

QUEST_DEFS: list[dict] = [

    # ------------------------------------------------------------------
    # The comet tail: the five onboarding quests, ids and mechanics pinned
    # ------------------------------------------------------------------
    {
        "id": "punch_a_tree",
        "title": "Punch a Tree",
        "subtitle": "Eight logs, and it counts them itself",
        "icon": "minecraft:oak_log",
        "x": -1600, "y": 64,
        "shape": "rounded",
        "auto_claim": "disabled",
        "desc": describe(
            "Everything worth having starts with a tree, and everything worth having ends with"
            " you hitting it.",
            "An item task that does not consume has no Submit button: the moment eight oak logs"
            " are in your inventory the quest completes and the logs stay where they are. The"
            " chapter around this one sets `defaultConsumeItems: true`, so the `consumeItems:"
            " false` written here is the exception -- a default is only a default if something"
            " declines it, and this is the task that does.",
            "The chapter also turns auto-claim on, and this quest opts back out with"
            " `autoClaim: disabled`: its wooden axe waits for the claim button, which is the"
            " middle rung of the ladder and the behaviour the engine's playthrough test asserts."),
        "tasks": [task("item", item="minecraft:oak_log", count=8, consumeItems=False)],
        "rewards": [reward("item", item="minecraft:wooden_axe")],
    },
    {
        "id": "read_the_sign",
        "title": "Read the Sign",
        "subtitle": "The first button in the book",
        "icon": "minecraft:oak_sign",
        "x": -1312, "y": -32,
        "shape": "tome",
        "auto_claim": "disabled",
        "deps": ["punch_a_tree"],
        "desc": describe(
            "The sign at the crossroads says the road down was closed in spring, which was true"
            " then. Read it anyway.",
            "A checkmark task is nothing but the button -- the game cannot see you read, so the"
            " press is the doing. The `title` field is what the button says, which is the one"
            " place a task gets to choose its own label."),
        "tasks": [task("checkmark", title="I read the sign")],
        "rewards": [reward("xp", amount=5)],
    },
    {
        "id": "make_a_table",
        "title": "Make a Table",
        "subtitle": "Three boards up, three boards across",
        "icon": "minecraft:crafting_table",
        "x": -1024, "y": 64,
        "shape": "square",
        "auto_claim": "disabled",
        "deps": ["punch_a_tree"],
        "desc": describe(
            "Eight logs make a table, and a table makes everything else possible, which is the"
            " closest thing a crafting recipe has to a thesis statement.",
            "The task counts one crafting table carried and declines the chapter's consume"
            " default, so the table stays on you -- put it down somewhere you will find again,"
            " because every arm of the orrery assumes you did."),
        "tasks": [task("item", item="minecraft:crafting_table", count=1, consumeItems=False)],
        "rewards": [reward("item", item="minecraft:stick", count=8)],
    },
    {
        "id": "stone_tools",
        "title": "Stone Tools",
        "subtitle": "The upgrade that is mostly an attitude",
        "icon": "minecraft:stone_pickaxe",
        "x": -736, "y": -32,
        "shape": "gear",
        "auto_claim": "disabled",
        "deps": ["make_a_table"],
        "desc": describe(
            "Wood is a loan from the greenwood. Stone is the first thing that is actually yours,"
            " taken from the hillside and shaped on the table you just made.",
            "Eight cobblestone carried is enough to make the whole kit. The quest pays back the"
            " pickaxe and some experience, so the rest of the orrery starts with you properly equipped."),
        "tasks": [task("item", item="minecraft:cobblestone", count=8, consumeItems=False)],
        "rewards": [
            reward("item", item="minecraft:stone_pickaxe"),
            reward("xp", amount=10),
        ],
    },
    {
        "id": "the_underground",
        "title": "The Underground",
        "subtitle": "The last step before the sky",
        "icon": "minecraft:iron_pickaxe",
        "x": -448, "y": 64,
        "shape": "circle",
        "auto_claim": "disabled",
        "deps": ["stone_tools"],
        "desc": describe(
            "Every questline in this collection ends in the dark somewhere, and this one is where"
            " the dark starts -- and where the orrery begins. The hub depends on this quest, so"
            " the five first steps are the key to the whole sky.",
            "There is nothing to carry here and nothing to press. Arrive, and the quest agrees"
            " you have arrived."),
        "tasks": [task("checkmark", title="Descend below the surface")],
        "rewards": [
            reward("item", item="minecraft:torch", count=16),
            reward("xp", amount=10),
        ],
    },

    # ------------------------------------------------------------------
    # The hub
    # ------------------------------------------------------------------
    {
        "id": "the_engine",
        "title": "The Engine",
        "subtitle": "The hub every arm is bolted to",
        "icon": "minecraft:nether_star",
        "x": 0, "y": 0,
        "shape": "gear", "size": 224, "icon_scale": 0.6,
        "show_title": True,
        "deps": ["the_underground"],
        "lines": {"the_underground": {
            "form": "curved", "bend": 0.3, "weight": "conduit",
            "arrowPlace": "stream", "arrowDensity": "medium",
        }},
        "desc": describe(
            "The chapter is one orrery and this is its hub: the largest node on the canvas, the"
            " only quest every arm begins at, and the line arriving from the west is the"
            " chapter's own default style overridden into a curved conduit.",
            "Seven arms leave this node. Each is a family of mechanisms -- instruments,"
            " measures, the compass, the hunt, the treasury, the clockwork, the veils -- and"
            " each head quest hangs off this one, so finishing the five first steps opens the"
            " whole sky at once.",
            "The description you are reading is one of the few written with `showTitle: true`,"
            " because a hub with no name is hard to point at."),
        "tasks": [task("checkmark", title="Wind the orrery")],
        "rewards": [reward("xp", amount=50)],
    },

    # ------------------------------------------------------------------
    # Arm: instruments (item tasks)
    # ------------------------------------------------------------------
    {
        "id": "the_counting_house",
        "title": "The Counting House",
        "subtitle": "An item tag, and the chapter default taking its due",
        "icon": "minecraft:barrel",
        "arm": "instruments", "slot": 0, "offset": 0,
        "deps": ["the_engine"],
        "desc": describe(
            "The counting house does not care which log you bring, only that it is a log: the"
            " task names `minecraft:logs`, a tag, so any item in it counts. A tag is written as"
            " the tag's own id, without a `#` -- `#` is for the biome and structure tasks, where"
            " the field is a string that may carry one.",
            "This task says nothing about consuming, so it inherits the chapter's"
            " `defaultConsumeItems: true` and takes the logs when it completes. Every other item"
            " task in the arm follows it; the ones that decline say so for themselves."),
        "tasks": [task("item_tag", tag="minecraft:logs", count=32)],
        "rewards": [reward("item", item="minecraft:emerald", count=2)],
    },
    {
        "id": "the_carpenters_due",
        "title": "The Carpenter's Due",
        "subtitle": "A consuming task, said on the task",
        "icon": "minecraft:oak_planks",
        "arm": "instruments", "slot": 1, "offset": -12,
        "shape": "square",
        "deps": ["the_counting_house"],
        "lines": {"the_counting_house": {"form": "chamfered"}},
        "desc": describe(
            "Sixty-four planks, handed over and gone. The `consumeItems: true` here is written"
            " on the task rather than inherited -- the explicit form of the chapter default, and"
            " the one to copy when a chapter's default is off.",
            "The line into this node is `form: chamfered`, the circuit-trace step: the same"
            " route as the chapter's orthogonal default with its corners cut at forty-five"
            " degrees."),
        "tasks": [task("item", item="minecraft:oak_planks", count=64,
                       consumeItems=True, match="none")],
        "rewards": [reward("xp", amount=15)],
    },
    {
        "id": "the_nameplate",
        "title": "The Nameplate",
        "subtitle": "components, and match: fuzzy",
        "icon": "minecraft:iron_sword",
        "arm": "instruments", "slot": 1, "offset": 0,
        "deps": ["the_counting_house"],
        "desc": describe(
            "A sword with a name on it. `components` names a `minecraft:custom_name` component --"
            " the value is a string holding the JSON the codec writes -- and `match: fuzzy` asks"
            " a carried stack to have the data the task names rather than to be identical to it"
            " in every other respect. A plain iron sword does not count; one named The Keystone"
            " does, whatever its durability says.",
            "`match: none` would ignore the data entirely, and `match: strict` (the default,"
            " shown on the warrant beside this) would need the whole stack to agree. This task"
            " also declines the chapter's consume default, because a nameplate is not something"
            " the house gets to keep."),
        "tasks": [task("item", item="minecraft:iron_sword", count=1,
                       components={"minecraft:custom_name": '"The Keystone"'},
                       match="fuzzy", consumeItems=False)],
        "rewards": [reward("item", item="minecraft:experience_bottle", count=4)],
    },
    {
        "id": "the_warrant",
        "title": "The Warrant",
        "subtitle": "match: strict, and the whole stack",
        "icon": "minecraft:stick",
        "arm": "instruments", "slot": 1, "offset": 12,
        "deps": ["the_counting_house"],
        "desc": describe(
            "A stick with a title carved into it, and `match: strict`: the carried stack must be"
            " the stack the task names, component for component. This is the default reading of"
            " an item task, written out so the difference from fuzzy is visible side by side.",
            "The warrant does not consume -- the court keeps its paper -- so `consumeItems:"
            " false` is here too, one more exception for the default to lean against."),
        "tasks": [task("item", item="minecraft:stick", count=1,
                       components={"minecraft:custom_name": '"The Warrant"'},
                       match="strict", consumeItems=False)],
        "rewards": [reward("xp", amount=10)],
    },
    {
        "id": "the_apprentices_ink",
        "title": "The Apprentice's Ink",
        "subtitle": "onlyFromCrafting",
        "icon": "minecraft:paper",
        "arm": "instruments", "slot": 2, "offset": -12,
        "shape": "pentagon",
        "deps": ["the_carpenters_due"],
        "lines": {"the_carpenters_due": {"dash": "dash_dot"}},
        "desc": describe(
            "Paper you made yourself. `onlyFromCrafting: true` counts only items the player"
            " crafted, so paper found in a chest is not the apprentice's ink -- the field exists"
            " for the quest that wants the work rather than the item.",
            "Its line runs `dash: dash_dot`, the long-short pattern, so the route to the"
            " workshop reads as a different kind of road."),
        "tasks": [task("item", item="minecraft:paper", count=8, onlyFromCrafting=True)],
        "rewards": [reward("item", item="minecraft:ink_sac", count=2)],
    },
    {
        "id": "the_granary",
        "title": "The Granary",
        "subtitle": "A count in the hundreds, where the bar is the point",
        "icon": "minecraft:hay_block",
        "arm": "instruments", "slot": 2, "offset": 12,
        "deps": ["the_carpenters_due"],
        "desc": describe(
            "Two hundred and fifty-six wheat, which is four stacks, which is the size at which"
            " the client stops drawing a number and draws a progress bar. `count` goes to 6400;"
            " this is the example that shows the field being used at a size where its rendering"
            " changes.",
            "The task says nothing about consuming, so the granary takes the wheat -- the"
            " chapter default doing exactly what a granary is for."),
        "tasks": [task("item", item="minecraft:wheat", count=256)],
        "rewards": [reward("item", item="minecraft:bread", count=16)],
    },

    # ------------------------------------------------------------------
    # Arm: measures (experience, fluid, statistics, the custom handler)
    # ------------------------------------------------------------------
    {
        "id": "the_tithe_of_experience",
        "title": "The Tithe of Experience",
        "subtitle": "An xp task in points, and one in whole levels",
        "icon": "minecraft:experience_bottle",
        "arm": "measures", "slot": 0, "offset": 0,
        "deps": ["the_engine"],
        "lines": {"the_engine": {"dash": "dashed"}},
        "desc": describe(
            "Experience is never taken automatically -- the Submit press is the consent, and the"
            " press takes it -- so both tasks here are manual. The first counts thirty points;"
            " the second counts five whole levels, because `points: false` reads the field as"
            " levels rather than points.",
            "A quest with two xp tasks needs both presses. The line from the hub is"
            " `dash: dashed`, one of the six patterns the axis can take."),
        "tasks": [
            task("xp", value=30, points=True),
            task("xp", value=5, points=False),
        ],
        "rewards": [reward("xp", amount=20)],
    },
    {
        "id": "the_floodgate",
        "title": "The Floodgate",
        "subtitle": "Two fluids, one valve",
        "icon": "minecraft:water_bucket",
        "arm": "measures", "slot": 1, "offset": -12,
        "shape": "hexagon",
        "deps": ["the_tithe_of_experience"],
        "lines": {"the_tithe_of_experience": {"arrowHead": "triangle"}},
        "desc": describe(
            "A fluid task measures fluid carried in buckets: `amount` is millibuckets, and a"
            " bucket is a thousand. The first task asks for a bucket of water, the second for a"
            " bucket of lava, and both are manual because handing over a bucket is the consent.",
            "The line into the floodgate ends in a `triangle` rather than the default chevron --"
            " one of the five glyphs an arrowhead can be."),
        "tasks": [
            task("fluid", fluid="minecraft:water", amount=1000),
            task("fluid", fluid="minecraft:lava", amount=1000),
        ],
        "rewards": [reward("item", item="minecraft:bucket", count=2)],
    },
    {
        "id": "the_long_walk",
        "title": "The Long Walk",
        "subtitle": "A vanilla statistic",
        "icon": "minecraft:leather_boots",
        "arm": "measures", "slot": 1, "offset": 12,
        "deps": ["the_tithe_of_experience"],
        "desc": describe(
            "A stat task watches one vanilla statistic and completes when it reaches `value`."
            " This one watches `minecraft:walk_one_cm` and wants fifty thousand of them -- five"
            " hundred blocks, counted by the game's own ledger rather than by the quest book.",
            "Statistics are registry entries, so the validator can check the id; a statistic"
            " this build does not have counts as zero rather than failing the load."),
        "tasks": [task("stat", stat="minecraft:walk_one_cm", value=50000)],
        "rewards": [reward("xp", amount=15)],
    },
    {
        "id": "the_crank",
        "title": "The Crank",
        "subtitle": "sequentialTasks, and all_started",
        "icon": "minecraft:lever",
        "arm": "measures", "slot": 2, "offset": 0,
        "shape": "octagon",
        "deps": ["the_long_walk", "the_beacon_lit"],
        "prereq": "all_started",
        "sequential": True,
        "lines": {"the_long_walk": {
            "form": "chamfered", "arrowPlace": "stream", "arrowDensity": "medium",
        }},
        "desc": describe(
            "Three checkmarks that must be handed in in order: with `sequentialTasks: true` the"
            " second cannot be pressed until the first is, and the third waits on the second."
            " The tasks are the same button three times, which is the point -- what changed is"
            " the rule joining them.",
            "This quest also states `prerequisiteMode: all_started`, so it opens when both its"
            " dependencies have been started rather than completed -- the loosest of the four"
            " modes, and the reason the beacon beside it can be lit in any order.",
            "Its line is a `chamfered` route drawn as a `stream` at `medium` density, three of"
            " the line axes set on one edge."),
        "tasks": [
            task("checkmark", title="Turn the crank"),
            task("checkmark", title="Turn it again"),
            task("checkmark", title="And once more"),
        ],
        "rewards": [reward("xp", amount=20)],
    },
    {
        "id": "the_beacon_lit",
        "title": "The Beacon Lit",
        "subtitle": "A custom task, and the warning it carries",
        "icon": "minecraft:beacon",
        "arm": "measures", "slot": 3, "offset": -12,
        "deps": ["the_long_walk"],
        "desc": describe(
            "A `tenet:custom` task measures whatever a registered handler answers. Nothing"
            " outside a handler can advance it, so this quest will not complete in a build"
            " without one -- and that is not an error: the validator warns that nothing provides"
            " the id, which is exactly what a pack shipping an optional handler mod wants to"
            " see. The KubeJS page in the manual shows the pattern with a placeholder id of its"
            " own (`my_pack:inducted_check`), so copying the page verbatim leaves this node"
            " dead: registering `first_light:beacon_lit` is one line in a script beside it.",
            "This is the one warning the shipped examples are expected to produce, and the"
            " playthrough test asserts the load is clean of *errors* rather than of warnings"
            " for exactly this reason."),
        "tasks": [task("custom", id="first_light:beacon_lit", value=1)],
        "rewards": [reward("xp", amount=25)],
    },
    {
        "id": "the_turned_gear",
        "title": "The Turned Gear",
        "subtitle": "rotation, in degrees",
        "icon": "minecraft:iron_block",
        "arm": "measures", "slot": 3, "offset": 12,
        "shape": "gear", "rotation": 45,
        "deps": ["the_crank"],
        "desc": describe(
            "A gear turned forty-five degrees clockwise. `rotation` turns the outline rather"
            " than naming a second shape: the node is drawn, clicked and icon-fitted in its"
            " turned form, and the icon turns with it. A full turn is written as 0, because it"
            " is the same shape.",
            "The turned outline is fitted with one uniform scale, so nothing is cut off at the"
            " node's edge and nothing is stretched -- which is easiest to see on a shape with"
            " teeth."),
        "tasks": [task("checkmark", title="Align the gear")],
        "rewards": [reward("xp", amount=10)],
    },

    # ------------------------------------------------------------------
    # Arm: the compass (dimension, biome, structure, location)
    # ------------------------------------------------------------------
    {
        "id": "the_other_side",
        "title": "The Other Side",
        "subtitle": "A dimension task",
        "icon": "minecraft:obsidian",
        "arm": "compass", "slot": 0, "offset": 0,
        "deps": ["the_engine"],
        "desc": describe(
            "A dimension task completes when the player is in the dimension it names. This one"
            " names `minecraft:the_nether`, so it completes the moment you step through -- no"
            " item, no press, just a fact about where you are.",
            "The quest is the head of the compass arm: every biome, structure and location"
            " task in the sky hangs off it."),
        "tasks": [task("dimension", dimension="minecraft:the_nether")],
        "rewards": [reward("item", item="minecraft:flint_and_steel")],
    },
    {
        "id": "the_greenwood",
        "title": "The Green Wood and the Salt Flats",
        "subtitle": "A biome by tag, and a biome by id",
        "icon": "minecraft:oak_sapling",
        "arm": "compass", "slot": 1, "offset": -12,
        "deps": ["the_other_side"],
        "lines": {"the_other_side": {"dash": "dotted"}},
        "desc": describe(
            "Two biome tasks, the two spellings. The first names `#minecraft:is_forest`, a tag,"
            " so standing in any forest counts -- a `#` in front of a biome means the whole tag."
            " The second names `minecraft:desert` exactly, so only the desert does.",
            "The dotted line into this node is `dash: dotted`, the finest of the six patterns."),
        "tasks": [
            task("biome", biome="#minecraft:is_forest"),
            task("biome", biome="minecraft:desert"),
        ],
        "rewards": [reward("item", item="minecraft:apple", count=4)],
    },
    {
        "id": "the_watch_hill",
        "title": "The Watch Hill",
        "subtitle": "Two location boxes, one that ignores its dimension",
        "icon": "minecraft:spyglass",
        "arm": "compass", "slot": 1, "offset": 12,
        "deps": ["the_other_side"],
        "desc": describe(
            "A location task is a box: `position` is one corner, `size` how far it reaches, and"
            " the task completes while the player is inside. The first box is thirty-two blocks"
            " of overworld hillside and says so with `dimension: minecraft:overworld`.",
            "The second box is the one worth reading: `ignoreDimension: true` counts it in any"
            " dimension, which is the field's honest use -- a waystation you can reach from"
            " wherever you fell."),
        "tasks": [
            task("location", position=[128, 64, -256], size=[32, 32, 32],
                 dimension="minecraft:overworld"),
            task("location", position=[-64, 32, 96], size=[16, 16, 16],
                 dimension="minecraft:overworld", ignoreDimension=True),
        ],
        "rewards": [reward("xp", amount=15)],
    },
    {
        "id": "the_villages",
        "title": "The Villages",
        "subtitle": "A structure by tag",
        "icon": "minecraft:bell",
        "arm": "compass", "slot": 2, "offset": -12,
        "deps": ["the_greenwood"],
        "lines": {"the_greenwood": {"dash": "double"}},
        "desc": describe(
            "A structure task completes while the player is inside the named structure, and the"
            " field takes a tag the same way a biome does: `#minecraft:village` counts any"
            " village, in any biome.",
            "The line into the villages is `dash: double`, two hairlines either side of the"
            " route. Double ignores `weight`, which is why it is the one pattern that looks the"
            " same however the rest of the line is styled."),
        "tasks": [task("structure", structure="#minecraft:village")],
        "rewards": [reward("item", item="minecraft:emerald", count=3)],
    },
    {
        "id": "the_ancient_city",
        "title": "The Ancient City",
        "subtitle": "A structure by id",
        "icon": "minecraft:sculk_shrieker",
        "arm": "compass", "slot": 2, "offset": 12,
        "deps": ["the_other_side"],
        "lines": {"the_other_side": {"form": "straight"}},
        "desc": describe(
            "The same field, naming one structure exactly: `minecraft:ancient_city`. Where the"
            " villages use a tag because a village is a family, the city is one place and the"
            " id is the honest way to say so.",
            "The sun at the end of this arm depends on this quest, and its line is a stream of"
            " arrows at the sparsest density the axis has."),
        "tasks": [task("structure", structure="minecraft:ancient_city")],
        "rewards": [reward("item", item="minecraft:echo_shard")],
    },
    {
        "id": "the_mirror",
        "title": "The Mirror",
        "subtitle": "A curved line, anchored at both rims",
        "icon": "minecraft:glass",
        "arm": "compass", "slot": 3, "offset": 0,
        "deps": ["the_watch_hill"],
        "lines": {"the_watch_hill": {
            "form": "curved", "bend": 0.8, "fromAnchor": 200, "toAnchor": 20,
        }},
        "desc": describe(
            "The line into this node is the chapter's boldest piece of line art: `form: curved`"
            " with `bend: 0.8`, the positive extreme of the axis, so the route bows as far as a"
            " curved line can.",
            "`fromAnchor` and `toAnchor` are per-line only, and they say which rim the line"
            " leaves and meets: two hundred degrees out of the watch hill, twenty degrees into"
            " the mirror. Anchors are refused in a chapter's default because which rim a line"
            " meets is a fact about that line's two ends, not about a chapter."),
        "tasks": [task("checkmark", title="Look into the mirror")],
        "rewards": [reward("xp", amount=15)],
    },
    {
        "id": "the_sun",
        "title": "The Sun",
        "subtitle": "The largest node, and the smallest icon",
        "icon": "minecraft:sunflower",
        "arm": "compass", "slot": 4, "offset": -8,
        "shape": "star", "size": 256, "icon_scale": 0.35,
        "show_title": True,
        "deps": ["the_ancient_city"],
        "lines": {"the_ancient_city": {"arrowPlace": "stream", "arrowDensity": "low"}},
        "desc": describe(
            "Two hundred and fifty-six pixels across: the top of the size range the canvas"
            " draws, and the node that shows what `iconScale` is for. At `0.35` the icon sits"
            " small inside a large node -- the other end of the range from the speck beside it,"
            " which fills its node corner to corner.",
            "Its line is an arrow `stream` at `arrowDensity: low`, the sparsest of the three"
            " spacings: heads every sixty-four pixels rather than every sixteen."),
        "tasks": [task("checkmark", title="Face the sun")],
        "rewards": [reward("xp", amount=50)],
    },
    {
        "id": "the_speck",
        "title": "The Speck",
        "subtitle": "The smallest node, and shape: none",
        "icon": "minecraft:amethyst_shard",
        "arm": "compass", "slot": 4, "offset": 8,
        "shape": "none", "size": 16, "icon_scale": 1.0,
        "deps": ["the_mirror"],
        "desc": describe(
            "Sixteen pixels, the floor of the size range, and `shape: none`: no panel at all,"
            " just the icon, with the whole square still clickable. `iconScale: 1.0` fills that"
            " square corner to corner -- the opposite end of the scale from the sun, and the"
            " pair that shows a reader what the two fields actually do.",
            "A speck is still a quest: it counts an amethyst shard and pays experience like any"
            " other node."),
        "tasks": [task("item", item="minecraft:amethyst_shard", count=1)],
        "rewards": [reward("xp", amount=5)],
    },

    # ------------------------------------------------------------------
    # Arm: the hunt (kills, observations, advancements)
    # ------------------------------------------------------------------
    {
        "id": "the_first_iron",
        "title": "The First Iron",
        "subtitle": "An advancement task, whole",
        "icon": "minecraft:iron_ingot",
        "arm": "hunt", "slot": 0, "offset": 0,
        "deps": ["the_engine"],
        "desc": describe(
            "An advancement task completes when the advancement is earned. Naming no"
            " `criterion` means the whole advancement counts, which for `minecraft:story/"
            "smelt_iron` is one step: smelt the ore.",
            "The breeder beside this one asks for a single criterion of its advancement"
            " instead, which is the other spelling of the same field."),
        "tasks": [task("advancement", advancement="minecraft:story/smelt_iron")],
        "rewards": [reward("item", item="minecraft:iron_ingot", count=4)],
    },
    {
        "id": "the_hunt",
        "title": "The Hunt",
        "subtitle": "A kill by entity id",
        "icon": "minecraft:bone",
        "arm": "hunt", "slot": 1, "offset": -14,
        "deps": ["the_first_iron"],
        "desc": describe(
            "The plainest kill task: an entity id and a number. Eight cows, counted by the"
            " game's own death event, completing the moment the eighth falls.",
            "The three quests beside and below this one are the same field filtered three"
            " different ways -- by custom name, by an SNBT filter, and by an entity tag -- so"
            " the family reads as one mechanism with three lenses."),
        "tasks": [task("kill", entity="minecraft:cow", value=8)],
        "rewards": [reward("item", item="minecraft:leather", count=8)],
    },
    {
        "id": "the_breeder",
        "title": "The Breeder",
        "subtitle": "An advancement by one criterion",
        "icon": "minecraft:wheat",
        "arm": "hunt", "slot": 1, "offset": 14,
        "deps": ["the_first_iron"],
        "desc": describe(
            "`criterion` narrows an advancement task to one of the advancement's own criteria"
            " rather than the whole thing. `minecraft:husbandry/breed_an_animal` is earned by"
            " breeding a pair; the criterion named here is the one that fires when you do.",
            "The advancement condition on the shepherd's tally in the clockwork arm asks the"
            " same question the other way round -- as a gate on a reward rather than a task."),
        "tasks": [task("advancement", advancement="minecraft:husbandry/breed_an_animal",
                       criterion="bred_an_animal")],
        "rewards": [reward("item", item="minecraft:hay_block", count=4)],
    },
    {
        "id": "the_pale_stag",
        "title": "The Pale Stag",
        "subtitle": "A kill by customName",
        "icon": "minecraft:white_wool",
        "arm": "hunt", "slot": 2, "offset": -14,
        "deps": ["the_hunt"],
        "lines": {"the_hunt": {"arrowPlace": "both"}},
        "desc": describe(
            "`customName` counts only a mob carrying this name, which is how a quest reaches a"
            " named individual rather than a species. A wolf named The Pale Stag is a different"
            " hunt from any other wolf, and the task says so in one field.",
            "Its line is drawn with `arrowPlace: both`, one head at each end -- the placement"
            " for a line that is also a warning."),
        "tasks": [task("kill", entity="minecraft:wolf", customName="The Pale Stag", value=1)],
        "rewards": [reward("item", item="minecraft:white_wool", count=8)],
    },
    {
        "id": "the_armoured_husk",
        "title": "The Armoured Husk",
        "subtitle": "A kill by nbtFilter",
        "icon": "minecraft:iron_chestplate",
        "arm": "hunt", "slot": 2, "offset": 0,
        "deps": ["the_hunt"],
        "lines": {"the_hunt": {"dash": "hazard", "weight": "thick"}},
        "desc": describe(
            "`nbtFilter` is an SNBT subset match against the mob's saved data -- the field for"
            " the mobs an id cannot pick out. `{IsBaby:1b}` counts only the small ones, which is"
            " a hunt with a different shape from the species.",
            "The line into it is `dash: hazard` at `weight: thick`: the hatch-marked run the"
            " road signs use, two pixels wide instead of one."),
        "tasks": [task("kill", entity="minecraft:zombie", nbtFilter="{IsBaby:1b}", value=3)],
        "rewards": [reward("item", item="minecraft:iron_ingot", count=6)],
    },
    {
        "id": "the_undead_cull",
        "title": "The Undead Cull",
        "subtitle": "A kill by entity tag",
        "icon": "minecraft:rotten_flesh",
        "arm": "hunt", "slot": 2, "offset": 14,
        "deps": ["the_hunt"],
        "lines": {"the_hunt": {"form": "curved", "bend": 0.5}},
        "desc": describe(
            "`entityTypeTag` counts any mob in a tag, so twelve of the undead is twelve of"
            " whatever the tag holds -- zombie, skeleton, drowned, phantom. It is its own field"
            " rather than a `#` in `entity`, so a tag where an id is expected is an error here"
            " as it is everywhere else.",
            "Its line is `form: curved` with a half-strength bend: a broad bow, the route the"
            " orrery's outer arms are drawn with."),
        "tasks": [task("kill", entityTypeTag="minecraft:undead", value=12)],
        "rewards": [reward("item", item="minecraft:gold_ingot", count=3)],
    },
    {
        "id": "the_lantern_watch",
        "title": "The Lantern Watch",
        "subtitle": "Observation: a block, and a block tag",
        "icon": "minecraft:lantern",
        "arm": "hunt", "slot": 3, "offset": -14,
        "deps": ["the_undead_cull"],
        "lines": {"the_undead_cull": {"form": "curved", "bend": -0.8, "arrowHead": "dot"}},
        "desc": describe(
            "An observation task completes when the player looks at the named thing for"
            " `timer` ticks. The first task names one block, `minecraft:lantern`, and looks at"
            " it for forty ticks -- two seconds, where the default is one.",
            "The second names a block *tag*, and a tag here is the tag's own id with no `#`:"
            " `minecraft:logs`. Any log answers it.",
            "Its line ends in a `dot`, one of the five arrowhead glyphs."),
        "tasks": [
            task("observation", observeType="block", toObserve="minecraft:lantern", timer=40),
            task("observation", observeType="block_tag", toObserve="minecraft:logs"),
        ],
        "rewards": [reward("xp", amount=20)],
    },
    {
        "id": "the_weathervane",
        "title": "The Weathervane",
        "subtitle": "Observation: a block state, and a block entity",
        "icon": "minecraft:lightning_rod",
        "arm": "hunt", "slot": 3, "offset": 0,
        "deps": ["the_lantern_watch"],
        "desc": describe(
            "`block_state` names a state, spelled the way the block-state parser spells it:"
            " `minecraft:oak_log[axis=y]` is a log standing on end, and a log lying down does"
            " not answer it.",
            "`block_entity` is the one whose target is not a name at all: it is an SNBT subset"
            " of the block entity's saved data, so `{}` counts any block entity and a filter"
            " like `{CustomName:'\"The Ledger\"'}` would count one particular chest. The timer"
            " here is sixty ticks and `autoSubmitTicks: 200` re-checks the look ten times less"
            " often than the default -- the field that keeps an expensive check from running"
            " twenty times a second."),
        "tasks": [
            task("observation", observeType="block_state", toObserve="minecraft:oak_log[axis=y]"),
            task("observation", observeType="block_entity", toObserve="{}", timer=60,
                 autoSubmitTicks=200),
        ],
        "rewards": [reward("xp", amount=20)],
    },
    {
        "id": "the_reading_room",
        "title": "The Reading Room",
        "subtitle": "Observation: a block entity type and an entity type, plus an optional task",
        "icon": "minecraft:lectern",
        "arm": "hunt", "slot": 3, "offset": 14,
        "deps": ["the_lantern_watch"],
        "lines": {"the_lantern_watch": {"arrowPlace": "mid"}},
        "desc": describe(
            "`block_entity_type` names the kind of block entity rather than the block -- a"
            " lectern is `minecraft:lectern` -- and `entity_type` names a mob to look at, here a"
            " villager for two seconds.",
            "The third task is `optional: true`, so it does not have to be done for the quest to"
            " complete. An optional task is how a quest says 'and also, if you like'; if every"
            " task were optional, any one of them would finish the quest.",
            "Its line places its arrowhead `mid`, in the middle of the route rather than at"
            " either end."),
        "tasks": [
            task("observation", observeType="block_entity_type", toObserve="minecraft:lectern"),
            task("observation", observeType="entity_type", toObserve="minecraft:villager",
                 timer=40),
            task("checkmark", title="Close the book", optional=True),
        ],
        "rewards": [reward("xp", amount=20)],
    },
    {
        "id": "the_grey_visitors",
        "title": "The Grey Visitors",
        "subtitle": "Observation: an entity type tag",
        "icon": "minecraft:gray_wool",
        "arm": "hunt", "slot": 4, "offset": 0,
        "deps": ["the_pale_stag"],
        "lines": {"the_pale_stag": {"arrowPlace": "stream", "arrowDensity": "high"}},
        "desc": describe(
            "The seventh and last `observeType`: an entity type *tag*, named without a `#` --"
            " `minecraft:illagers` counts a look at any of them. With this quest the arm has"
            " shown every value the field can take.",
            "Its line is a stream at `arrowDensity: high`, a head every sixteen pixels: the"
            " densest of the three spacings."),
        "tasks": [task("observation", observeType="entity_type_tag", toObserve="minecraft:illagers")],
        "rewards": [reward("item", item="minecraft:emerald", count=2)],
    },
    {
        "id": "the_quarry",
        "title": "The Quarry",
        "subtitle": "A break by block id",
        "icon": "minecraft:stone_pickaxe",
        "arm": "hunt", "slot": 5, "offset": -14,
        "deps": ["the_grey_visitors"],
        "desc": describe(
            "The plainest break task: a block id and a number. Thirty-two stone, counted"
            " by the game's own break event, completing the moment the thirty-second breaks."
            " What counts is the act, not the drop -- silk touch and a voided drop count"
            " just the same, because the event fires for the break either way.",
            "The two quests below it are the same field filtered two different ways -- by"
            " a block tag, and by a block state -- so the family reads as one mechanism"
            " with three lenses, the way the kills above it do."),
        "tasks": [task("break", block="minecraft:stone", value=32)],
        "rewards": [reward("item", item="minecraft:cobblestone", count=16)],
    },
    {
        "id": "the_timber_rights",
        "title": "The Timber Rights",
        "subtitle": "A break by block tag",
        "icon": "minecraft:oak_log",
        "arm": "hunt", "slot": 5, "offset": 0,
        "deps": ["the_quarry"],
        "desc": describe(
            "`blockTag` counts any block in a tag, so thirty-two logs is thirty-two of"
            " whatever the tag holds -- oak, birch, spruce, or the stems of the nether."
            " It is its own field rather than a `#` in `block`, so a tag where an id is"
            " expected is an error here as it is everywhere else.",
            "A tag task keeps the pickaxe on its row rather than wearing one member's"
            " item: a picture naming oak would be a promise the quest does not make."),
        "tasks": [task("break", blockTag="minecraft:logs", value=32)],
        "rewards": [reward("item", item="minecraft:oak_planks", count=16)],
    },
    {
        "id": "the_ripe_harvest",
        "title": "The Ripe Harvest",
        "subtitle": "A break by block state",
        "icon": "minecraft:wheat",
        "arm": "hunt", "slot": 5, "offset": 14,
        "deps": ["the_timber_rights"],
        "desc": describe(
            "`blockState` narrows a break to one state, spelled the way the block-state"
            " parser spells it: `minecraft:wheat[age=7]` is a fully grown crop, and"
            " breaking a green one does not answer it. It narrows `block` or `blockTag`,"
            " or stands alone implying its own block, as it does here.",
            "This is the field a lifetime mining statistic could never answer -- how much"
            " stone you have ever broken says nothing about whether the wheat was ripe --"
            " which is why break tasks are counted by the break event rather than polled."),
        "tasks": [task("break", blockState="minecraft:wheat[age=7]", value=16)],
        "rewards": [reward("item", item="minecraft:bread", count=4)],
    },

    # ------------------------------------------------------------------
    # Arm: the treasury (rewards, tables, payout flags)
    # ------------------------------------------------------------------
    {
        "id": "the_house_always_wins",
        "title": "The House Always Wins",
        "subtitle": "A named table, an inline table, and a nested roll",
        "icon": "minecraft:diamond",
        "arm": "treasury", "slot": 0, "offset": 0,
        "shape": "diamond",
        "deps": ["the_engine"],
        "lines": {"the_engine": {"arrowHead": "diamond"}},
        "desc": describe(
            "Two `tenet:random` rewards, the two ways a table can arrive. The first names"
            " `dice`, a file under `reward_tables/`; the second carries its table `inline`, for"
            " a reward that is its own roll.",
            "The inline table's one entry rolls another named table, `dregs` -- tables nest,"
            " and the manual cuts nesting off at eight levels so a table that accidentally"
            " includes itself cannot hang the server. A weight of zero in a table means always"
            " granted, which is how `dice` pays its experience even when the fashionable entry"
            " misses.",
            "Its line ends in a `diamond` arrowhead, the fourth of the five glyphs."),
        "tasks": [task("checkmark", title="Play the house's game")],
        "rewards": [
            reward("random", table="dice"),
            reward("random", inline={
                "entries": [{"weight": 1, "reward": reward("random", table="dregs")}],
            }),
        ],
    },
    {
        "id": "the_dust_draw",
        "title": "The Dust Draw",
        "subtitle": "loot with an empty band, and all_table",
        "icon": "minecraft:glowstone_dust",
        "arm": "treasury", "slot": 1, "offset": -12,
        "deps": ["the_house_always_wins"],
        "desc": describe(
            "The same table, rolled two ways. `tenet:loot` includes the empty band: `toll`"
            " carries `emptyWeight: 4`, so a throw can come up with nothing at all -- the one"
            " table mode where 'no prize' is a possible answer.",
            "`tenet:all_table` is the other extreme: no dice, every entry granted, weight-zero"
            " entries and all. Between them the two rewards show the mode field deciding what a"
            " table means."),
        "tasks": [task("checkmark", title="Draw the dust")],
        "rewards": [
            reward("loot", table="toll"),
            reward("all_table", table="toll"),
        ],
    },
    {
        "id": "the_patrons_gift",
        "title": "The Patron's Gift",
        "subtitle": "A choice reward, picked by the player",
        "icon": "minecraft:amethyst_shard",
        "arm": "treasury", "slot": 1, "offset": 12,
        "deps": ["the_house_always_wins"],
        "desc": describe(
            "`tenet:choice` sends the table's entries to the player and waits for the pick."
            " The payout *is* the decision, so a choice reward is never auto-granted -- whatever"
            " the chapter's `autoClaim` says -- and a crash between the offer and the pick"
            " loses nothing.",
            "The table here is inline, which is the form to use when the choice belongs to one"
            " quest rather than to a pack's loot."),
        "tasks": [task("checkmark", title="Choose a gift")],
        "rewards": [
            reward("choice", inline={"entries": [
                {"weight": 1, "reward": reward("item", item="minecraft:diamond", count=4)},
                {"weight": 1, "reward": reward("item", item="minecraft:enchanted_book")},
                {"weight": 1, "reward": reward("xp", amount=250)},
            ]}),
        ],
    },
    {
        "id": "the_bonus_bag",
        "title": "The Bonus Bag",
        "subtitle": "Three item-reward fields, one reward each",
        "icon": "minecraft:bundle",
        "arm": "treasury", "slot": 2, "offset": -16,
        "deps": ["the_dust_draw"],
        "desc": describe(
            "Three `tenet:item` rewards, one per field. The first rolls `randomBonus` on top of"
            " its count -- up to eight more gold ingots, decided when the reward is collected.",
            "The second sets `onlyOne: true`, so it is skipped if the player already carries a"
            " shield: the field for a reward that should not arrive twice.",
            "The third carries `components`, the same renamed-item spelling the nameplate task"
            " uses, so the keepsake arrives with its name already on it."),
        "tasks": [task("checkmark", title="Open the bonus bag")],
        "rewards": [
            reward("item", item="minecraft:gold_ingot", count=8, randomBonus=8),
            reward("item", item="minecraft:shield", onlyOne=True),
            reward("item", item="minecraft:golden_apple",
                   components={"minecraft:custom_name": '"The Keepsake"'}),
        ],
    },
    {
        "id": "the_guild_chest",
        "title": "The Guild Chest",
        "subtitle": "A team reward",
        "icon": "minecraft:chest",
        "arm": "treasury", "slot": 2, "offset": 0,
        "shape": "heart",
        "deps": ["the_patrons_gift"],
        "desc": describe(
            "`team: true` makes this a one-claim-for-the-team reward: the first member to claim"
            " it claims it for everybody, and nobody else is offered it. Absent, the field falls"
            " back to the tree's `defaultTeamReward`.",
            "The guild chest is drawn as a heart because it is the arm's one reward that is"
            " about the party rather than the player."),
        "tasks": [task("checkmark", title="Open the guild chest")],
        "rewards": [reward("item", item="minecraft:diamond", count=2, team=True)],
    },
    {
        "id": "the_home_road",
        "title": "The Home Road",
        "subtitle": "Experience paid in whole levels",
        "icon": "minecraft:compass",
        "arm": "treasury", "slot": 2, "offset": 16,
        "deps": ["the_dust_draw"],
        "desc": describe(
            "An xp reward pays points by default; `levels: true` pays whole levels instead."
            " Five levels is a very different gift from five points, and the field is the only"
            " difference between them.",
            "The road home is also where the command rewards begin: the bell and the quiet word"
            " both hang off this quest."),
        "tasks": [task("checkmark", title="Take the home road")],
        "rewards": [reward("xp", amount=5, levels=True)],
    },
    {
        "id": "the_triumph",
        "title": "The Triumph",
        "subtitle": "An advancement reward",
        "icon": "minecraft:firework_rocket",
        "arm": "treasury", "slot": 3, "offset": -12,
        "deps": ["the_bonus_bag"],
        "desc": describe(
            "An advancement reward awards the advancement itself, optionally narrowed to one"
            " criterion the way the task of the same name is. This one hands over the Nether"
            " achievement outright.",
            "It is the mirror of the advancement *task* in the hunt arm: one asks whether the"
            " player earned it, the other makes sure they have."),
        "tasks": [task("checkmark", title="Mark the triumph")],
        "rewards": [reward("advancement", advancement="minecraft:story/enter_the_nether")],
    },
    {
        "id": "the_bells_rung",
        "title": "The Bells Rung",
        "subtitle": "A command reward, with placeholders",
        "icon": "minecraft:bell",
        "arm": "treasury", "slot": 3, "offset": 0,
        "deps": ["the_home_road"],
        "desc": describe(
            "A command reward runs through the server's own dispatcher with the player as the"
            " source, at `permissionLevel` 2 by default -- a command block's level. The command"
            " is written without its leading slash.",
            "The brace-words are the legacy format's own placeholders, kept whole: `{p}` is the player's"
            " scoreboard name, `{quest}` the quest's id, `{chapter}` the chapter's, and `{x}`"
            " `{y}` `{z}` the block they stand on. An unknown brace-word is left as written"
            " rather than blanked, so a typo is visible in the log."),
        "tasks": [task("checkmark", title="Ring the bell")],
        "rewards": [reward("command", command="say {p} has rung the bell of {chapter}",
                           permissionLevel=2)],
    },
    {
        "id": "the_quiet_word",
        "title": "The Quiet Word",
        "subtitle": "A silent command, held back from Claim all",
        "icon": "minecraft:paper",
        "arm": "treasury", "slot": 3, "offset": 12,
        "deps": ["the_home_road"],
        "desc": describe(
            "`silent: true` keeps the command's run out of chat, which is the difference"
            " between a reward that announces itself and one that works in the background.",
            "`excludeFromClaimAll: true` is the other half: Claim all sweeps up everything"
            " outstanding, and this reward stays behind for its own press. The pair exists for"
            " rewards whose moment matters -- a word said quietly, at the player's choosing."),
        "tasks": [task("checkmark", title="Say the quiet word")],
        "rewards": [reward("command", command="say The quiet word was spoken.",
                           silent=True, excludeFromClaimAll=True)],
    },
    {
        "id": "the_crowns_due",
        "title": "The Crowns Due",
        "subtitle": "permissionLevel 4, past a payout block",
        "icon": "minecraft:golden_helmet",
        "arm": "treasury", "slot": 4, "offset": -12,
        "deps": ["the_bells_rung"],
        "desc": describe(
            "`permissionLevel: 4` runs the command at the server's own level, for the rewards"
            " that must not depend on the player's rights. Level 2 is the default because most"
            " commands should be a command block's; this one is the exception.",
            "`ignoreRewardBlocking: true` pays even while an operator has held the team's"
            " automatic payouts with `/tenet rewards block` -- the hold's one sanctioned"
            " exception, for the reward that is a debt rather than a gift."),
        "tasks": [task("checkmark", title="Pay the crowns due")],
        "rewards": [reward("command", command="give {p} minecraft:gold_block 1",
                           permissionLevel=4, ignoreRewardBlocking=True)],
    },
    {
        "id": "the_first_account",
        "title": "The First Account",
        "subtitle": "A command that makes a scoreboard objective",
        "icon": "minecraft:writable_book",
        "arm": "treasury", "slot": 4, "offset": 0,
        "deps": ["the_bells_rung"],
        "lines": {"the_bells_rung": {"weight": "bold"}},
        "desc": describe(
            "A command reward is the escape hatch every pack reaches for, and these two reach"
            " for the scoreboard: one creates the `first_light_standing` objective the"
            " festival's reward is gated on, and the other sets it to 1. That is the honest way"
            " to demonstrate a score condition -- the objective has to exist and hold a value"
            " before anything can read it, and an objective that exists at zero reads exactly"
            " like one that does not exist at all.",
            "One command per reward, because a command reward runs what it is given and nothing"
            " more. The pair is the gate's input; the festival's own place in the graph is a"
            " dependency on the witness list, not on this quest."),
        "tasks": [task("checkmark", title="Open the first account")],
        "rewards": [
            reward("command", command="scoreboard objectives add first_light_standing dummy"),
            reward("command", command="scoreboard players set {p} first_light_standing 1"),
        ],
    },
    {
        "id": "the_scarecrows_blessing",
        "title": "The Scarecrow's Blessing",
        "subtitle": "auto: no_toast and auto: invisible",
        "icon": "minecraft:carved_pumpkin",
        "arm": "treasury", "slot": 4, "offset": 12,
        "deps": ["the_crowns_due"],
        "desc": describe(
            "The chapter's `autoClaim: enabled` pays most rewards with a notification. These"
            " two pay more quietly: `no_toast` hands the reward over without the toast, and"
            " `invisible` without telling the player at all. Both also suppress the completion"
            " notice for the quest, so a chapter of starter quests does not announce itself"
            " fifty times.",
            "A reward's own `auto` outranks the quest's and the chapter's -- the most specific"
            " rung of the ladder wins, and these two are the ladder's quietest ends."),
        "tasks": [task("checkmark", title="Bless the scarecrows")],
        "rewards": [
            reward("item", item="minecraft:pumpkin_pie", count=4, auto="no_toast"),
            reward("item", item="minecraft:hay_block", count=8, auto="invisible"),
        ],
    },

    # ------------------------------------------------------------------
    # Arm: the clockwork (gates, repeats, stages, conditions)
    # ------------------------------------------------------------------
    {
        "id": "the_open_road",
        "title": "The Open Road",
        "subtitle": "An OR-gate: any two of three",
        "icon": "minecraft:oak_boat",
        "arm": "clockwork", "slot": 0, "offset": 0,
        "show_title": True,
        "deps": ["the_hunt", "the_floodgate", "the_greenwood"],
        "prereq": "all_completed",
        "min_required": 2,
        "desc": describe(
            "`minRequired: 2` replaces the count the prerequisite mode would use: any two of"
            " this quest's three dependencies will open it, rather than all three. It is how"
            "'any three of these five' is written, and it replaces the count rather than the"
            " bar -- the mode still decides what each dependency must reach.",
            "The three roads come from three different arms, which makes this node the one"
            " place where the canvas draws long cross-arm lines."),
        "tasks": [task("checkmark", title="Take the open road")],
        "rewards": [reward("xp", amount=30)],
    },
    {
        "id": "patron_gold",
        "title": "The Gold Patron",
        "subtitle": "One half of an exclusive pair",
        "icon": "minecraft:gold_ingot",
        "arm": "clockwork", "slot": 1, "offset": -18,
        "deps": ["the_engine"],
        "exclusive": "the_patrons",
        "desc": describe(
            "Two patrons share an `exclusiveGroup`, which is a pact between siblings:"
            " completing one locks the other for good. Take the gold patron's coin and the"
            " silver patron's door closes; the pair is a choice of paths, not a checklist.",
            "Exclusivity is scoped to the chapter, and the ledger below is written to survive"
            " it -- its prerequisite mode asks for one completed dependency, so either coin"
            " closes the book."),
        "tasks": [task("checkmark", title="Take the gold patron's coin")],
        "rewards": [reward("item", item="minecraft:gold_block", count=2)],
    },
    {
        "id": "patron_silver",
        "title": "The Silver Patron",
        "subtitle": "The other half of the pair",
        "icon": "minecraft:iron_ingot",
        "arm": "clockwork", "slot": 1, "offset": 18,
        "deps": ["the_engine"],
        "exclusive": "the_patrons",
        "desc": describe(
            "The same group name, the same consequence, the other door. Nothing links these two"
            " quests in the dependency graph -- the exclusivity is its own mechanism, and the"
            " name they share is the whole of it.",
            "An exclusive pair is how a questline asks a player to choose rather than to"
            " collect."),
        "tasks": [task("checkmark", title="Take the silver patron's coin")],
        "rewards": [reward("item", item="minecraft:iron_block", count=2)],
    },
    {
        "id": "the_crossroads",
        "title": "The Crossroads",
        "subtitle": "maxCompletableDependents: 1",
        "icon": "minecraft:compass",
        "arm": "clockwork", "slot": 1, "offset": 0,
        "shape": "octagon",
        "show_title": True,
        "deps": ["the_engine"],
        "max_dependents": 1,
        "desc": describe(
            "`maxCompletableDependents: 1` caps how many of this quest's dependents may ever"
            " complete: the first road walked closes the other for good. Where an exclusive"
            " group is a pact between siblings, this is a branch point that closes its own"
            " roads -- the same choice, said from the other end.",
            "The two roads below are identical in every other respect, because the cap is the"
            " only thing this exhibit is about."),
        "tasks": [task("checkmark", title="Stand at the crossroads")],
        "rewards": [reward("xp", amount=10)],
    },
    {
        "id": "the_ledger_closes",
        "title": "The Ledger Closes",
        "subtitle": "one_completed, and a split line",
        "icon": "minecraft:book",
        "arm": "clockwork", "slot": 2, "offset": -18,
        "deps": ["patron_gold", "patron_silver"],
        "prereq": "one_completed",
        "lines": {"patron_gold": {"fromHandle": [0.5, -0.4], "toHandle": [0.5, 0.4]}},
        "desc": describe(
            "`prerequisiteMode: one_completed` opens this quest when any one of its"
            " dependencies is complete -- the mode that lets a dependent survive an exclusive"
            " pair, since only one patron can ever be taken.",
            "Its line to the gold patron is a split: `fromHandle` and `toHandle` are written"
            " together, each a `[along, across]` control point as a fraction of the line's own"
            " chord. One alone would be a split with half a shape."),
        "tasks": [task("checkmark", title="Close the ledger")],
        "rewards": [reward("xp", amount=25)],
    },
    {
        "id": "road_north",
        "title": "The North Road",
        "subtitle": "A road the crossroads can close",
        "icon": "minecraft:arrow",
        "arm": "clockwork", "slot": 2, "offset": 0,
        "deps": ["the_crossroads"],
        "desc": describe(
            "One of the crossroads' two dependents. Nothing marks it as closable -- the cap"
            " lives on the crossroads -- and that is the point: a dependent is capped by the"
            " quest above it, not by anything it says about itself.",
            "A completed dependent stays completed; the cap only decides how many can ever get"
            " there."),
        "tasks": [task("checkmark", title="Walk the north road")],
        "rewards": [reward("xp", amount=10)],
    },
    {
        "id": "road_south",
        "title": "The South Road",
        "subtitle": "The road the other choice closes",
        "icon": "minecraft:arrow",
        "arm": "clockwork", "slot": 2, "offset": 18,
        "deps": ["the_crossroads"],
        "desc": describe(
            "The second of the crossroads' dependents. Walk the north road first and this one"
            " locks for good -- the cap on the crossroads is spent, and no amount of progress"
            " reopens it.",
            "Read the two road files side by side: they are the same file twice, which is what"
            " makes the cap the only thing the exhibit varies."),
        "tasks": [task("checkmark", title="Walk the south road")],
        "rewards": [reward("xp", amount=10)],
    },
    {
        "id": "the_calling",
        "title": {"translate": "tenet.example.first_light.the_calling",
                  "fallback": "The Calling"},
        "subtitle": "A stage granted, and a title in the other spelling",
        "icon": "minecraft:name_tag",
        "arm": "clockwork", "slot": 3, "offset": -18,
        "deps": ["the_ledger_closes"],
        "desc": describe(
            "A stage reward is the write half of the stage feature: completing this quest"
            " grants `first_light:sworn`, and the oath below is gated on it.",
            "The title is written in the other spelling a text field takes -- a translation"
            " key with an English fallback -- which is what a pack that wants its questline"
            " translated writes instead of a plain string. Without the fallback a player whose"
            " language has no translation sees the raw key."),
        "tasks": [task("checkmark", title="Answer the calling")],
        "rewards": [
            reward("stage", stage="first_light:sworn"),
            reward("xp", amount=20),
        ],
    },
    {
        "id": "the_daily_round",
        "title": "The Daily Round",
        "subtitle": "repeatable, on a cooldown",
        "icon": "minecraft:clock",
        "arm": "clockwork", "slot": 3, "offset": 0,
        "deps": ["the_crossroads"],
        "repeatable": True,
        "cooldown": 2400,
        "desc": describe(
            "`repeatable: true` makes a quest completable more than once, and"
            " `repeatCooldownTicks: 2400` makes the player wait two minutes between rounds."
            " `timesCompleted` survives each completion, and anything depending on a repeatable"
            " quest stays satisfied once it has been completed at least once.",
            "The shepherd's tally hangs off this round, so the arm also shows the other half:"
            " a dependency on a repeatable quest is a dependency on its first completion."),
        "tasks": [task("checkmark", title="Make the round")],
        "rewards": [reward("xp", amount=10)],
    },
    {
        "id": "the_oath",
        "title": "The Oath",
        "subtitle": "requiresStage, and the task that reads it",
        "icon": "minecraft:shield",
        "arm": "clockwork", "slot": 3, "offset": 18,
        "deps": ["the_calling"],
        "requires_stage": "first_light:sworn",
        "desc": describe(
            "`requiresStage` is the one gate that is per player rather than per team: this"
            " quest is open to a player carrying `first_light:sworn` and locked to one without"
            " it, even in the same party. The stage task beside it asks the same question as a"
            " task -- the read half of the feature whose write half was the calling's reward.",
            "Nothing validates that a stage exists, because a stage exists by being granted; a"
            " typo shows up as a quest nobody can open rather than as a file that will not"
            " load."),
        "tasks": [task("stage", stage="first_light:sworn")],
        "rewards": [reward("item", item="minecraft:shield")],
    },
    {
        "id": "the_graduation",
        "title": "The Graduation",
        "subtitle": "A stage reward that takes the stage away",
        "icon": "minecraft:enchanting_table",
        "arm": "clockwork", "slot": 4, "offset": -18,
        "deps": ["the_oath"],
        "lines": {"the_oath": {"arrowHead": "none"}},
        "desc": describe(
            "`remove: true` turns a stage reward into its inverse: this one takes"
            " `first_light:sworn` away instead of granting it, so the gate that opened the oath"
            " reads shut again. A stage is a flag, and both directions are one field.",
            "Its line ends in `arrowHead: none`, a blunt end rather than a glyph -- the fifth"
            " and last arrowhead, and the right one for a route that is an ending."),
        "tasks": [task("checkmark", title="Graduate")],
        "rewards": [
            reward("stage", stage="first_light:sworn", remove=True),
            reward("xp", amount=30, levels=True),
        ],
    },
    {
        "id": "the_witness_list",
        "title": "The Witness List",
        "subtitle": "Three conditions on one task",
        "icon": "minecraft:paper",
        "arm": "clockwork", "slot": 4, "offset": 0,
        "deps": ["the_calling"],
        "desc": describe(
            "A condition is a gate on acquiring progress: this checkmark cannot be pressed by a"
            " player who fails any of its three. The list is an AND -- every entry must hold --"
            " and each entry is a different one of the six condition types.",
            "The item condition asks for paper, the item_tag condition for four coals of any"
            " kind, and the stage condition for `first_light:sworn`. Nothing is consumed to"
            " satisfy a condition, and a task satisfied while its conditions held stays"
            " satisfied if they stop holding: complete is complete."),
        "tasks": [task("checkmark", title="Hand over the list", conditions=[
            condition("item", item="minecraft:paper", count=1),
            condition("item_tag", tag="minecraft:coals", count=4),
            condition("stage", stage="first_light:sworn"),
        ])],
        "rewards": [reward("xp", amount=20)],
    },
    {
        "id": "the_shepherds_tally",
        "title": "The Shepherd's Tally",
        "subtitle": "A statistic, and a reward behind an advancement",
        "icon": "minecraft:lead",
        "arm": "clockwork", "slot": 4, "offset": 18,
        "deps": ["the_daily_round"],
        "desc": describe(
            "The task is a statistic -- eight animals bred -- and the reward is gated on the"
            " advancement for breeding one, which is the belt-and-braces version of the same"
            " question. A reward's conditions are checked on every path that pays it, and a"
            " reward whose conditions are unmet is not lost: it waits, unclaimed, for the day"
            " they hold.",
            "The reward's condition is an advancement condition; the task beside it could have"
            " carried one too, and the manual's page on conditions says when each belongs."),
        "tasks": [task("stat", stat="minecraft:animals_bred", value=8)],
        "rewards": [reward("item", item="minecraft:name_tag", count=2, conditions=[
            condition("advancement", advancement="minecraft:husbandry/breed_an_animal"),
        ])],
    },
    {
        "id": "the_festival",
        "title": "The Festival",
        "subtitle": "A score gate and a bring-a-friend gate",
        "icon": "minecraft:cake",
        "arm": "clockwork", "slot": 5, "offset": 0,
        "shape": "pentagon",
        "deps": ["the_witness_list"],
        "desc": describe(
            "Two conditions on one reward, and both must hold. The score condition reads the"
            " `first_light_standing` objective the first account's commands created and set to"
            " 1: an objective that is missing reads as zero, and so does one nobody has set, so"
            " the gate is born shut and those commands are what open it.",
            "The party-size condition asks how many members of the player's party are online"
            " now, and you count as one: `min: 2` is the bring-a-friend gate. Nothing else"
            " about the party matters -- its progress mode, its owner, who is in it -- because"
            " this asks how many people are actually present, which is what a togetherness gate"
            " is about."),
        "tasks": [task("checkmark", title="Open the festival")],
        "rewards": [reward("item", item="minecraft:cake", conditions=[
            condition("score", objective="first_light_standing", min=1),
            condition("party_size", min=2),
        ])],
    },

    # ------------------------------------------------------------------
    # Arm: the veils (the hiding family, aliases, the custom reward)
    # ------------------------------------------------------------------
    {
        "id": "the_hidden_room",
        "title": "The Hidden Room",
        "subtitle": "invisible, and invisibleUntilTasks",
        "icon": "minecraft:amethyst_block",
        "arm": "veils", "slot": 0, "offset": 0,
        "deps": ["the_engine"],
        "invisible": True,
        "invisible_until_tasks": 1,
        "desc": describe(
            "`invisible: true` hides the whole quest until it is completed -- the node is not"
            " drawn, and nothing points at it. `invisibleUntilTasks: 1` adds the easter-egg"
            " case: one task with any progress reveals it early. Here, picking up a single"
            " amethyst shard makes the room appear.",
            "Without `invisible`, `invisibleUntilTasks` does nothing; the pair is the whole"
            " mechanism. A hidden quest still loads and still counts for progress, and the"
            " editor always shows it."),
        "tasks": [task("item", item="minecraft:amethyst_shard", count=1)],
        "rewards": [reward("item", item="minecraft:amethyst_block")],
    },
    {
        "id": "the_fenced_goods",
        "title": "The Fenced Goods",
        "subtitle": "hideUntilDependenciesComplete",
        "icon": "minecraft:gold_ingot",
        "arm": "veils", "slot": 1, "offset": -14,
        "deps": ["the_hidden_room"],
        "hide_until_complete": True,
        "desc": describe(
            "`hideUntilDependenciesComplete` hides the node until its prerequisite *rule* is"
            " satisfied -- the same rule the card's '2 of 3 met' counts, so `minRequired` and"
            " the started-based modes are honoured. A completed quest is always visible.",
            "The difference from `invisible` is the trigger: this one appears the moment its"
            " dependencies open it, rather than waiting to be completed or stumbled upon."),
        "tasks": [task("item", item="minecraft:gold_ingot", count=4)],
        "rewards": [reward("xp", amount=15)],
    },
    {
        "id": "the_far_star",
        "title": "The Far Star",
        "subtitle": "hideUntilDependenciesVisible",
        "icon": "minecraft:ender_eye",
        "arm": "veils", "slot": 1, "offset": 14,
        "deps": ["the_engine"],
        "hide_until_visible": True,
        "show_title": True,
        "desc": describe(
            "`hideUntilDependenciesVisible` hides this node until at least one of its"
            " prerequisites is itself visible. The rule is recursive, and the farther star"
            " below hangs off this one -- so the chain reveals itself one link at a time from"
            " its first visible end, which is the one reveal that cannot be read off a single"
            " file.",
            "Its name is drawn, which is one of the few `showTitle` nodes on the canvas."),
        "tasks": [task("checkmark", title="Find the far star")],
        "rewards": [reward("xp", amount=15)],
    },
    {
        "id": "the_stall_with_no_name",
        "title": "The Stall With No Name",
        "subtitle": "An alias, so a rename does not orphan progress",
        "icon": "minecraft:item_frame",
        "arm": "veils", "slot": 2, "offset": -18,
        "deps": ["the_far_star"],
        "aliases": ["the_nameless_stall"],
        "desc": describe(
            "`aliases` lists a quest's former ids. An id appears in player progress files, so a"
            " rename without an alias orphans the completions stored under the old one -- the"
            " player keeps the memory and the quest does not. The alias is the fix, and it"
            " resolves everywhere the id does.",
            "The glazier beside this quest is the proof: its `dependsOn` names"
            " `the_nameless_stall`, the alias, and the dependency resolves."),
        "tasks": [task("checkmark", title="Ask the stall with no name")],
        "rewards": [reward("item", item="minecraft:emerald")],
    },
    {
        "id": "the_glazier",
        "title": "The Glazier",
        "subtitle": "A dependency written against an alias",
        "icon": "minecraft:glass_pane",
        "arm": "veils", "slot": 2, "offset": 0,
        "deps": ["the_nameless_stall"],
        "hide_details": True,
        "desc": describe(
            "The dependency here is written as `the_nameless_stall`, which is not any quest's"
            " id -- it is the stall's alias. A second name resolves everywhere a first one"
            " does, which is what makes a rename safe.",
            "`hideDetailsUntilStartable` withholds the task and reward details until the quest"
            " can be started. The prerequisites stay: they are what tells the reader how to"
            " unlock it."),
        "tasks": [task("item", item="minecraft:glass", count=8)],
        "rewards": [reward("item", item="minecraft:glass_pane", count=16)],
    },
    {
        "id": "the_inscription",
        "title": "The Inscription",
        "subtitle": "hideTextUntilComplete",
        "icon": "minecraft:book",
        "arm": "veils", "slot": 2, "offset": 18,
        "shape": "tome",
        "deps": ["the_fenced_goods"],
        "hide_text": True,
        "desc": describe(
            "`hideTextUntilComplete` withholds the description until the quest is completed --"
            " for a quest whose text would give away what it asks for. The node, its name and"
            " its tasks are all still there; only the prose is held back.",
            "The editor always shows text a reader would not yet see, which is the point: the"
            " author writes the reveal, and the reader meets it."),
        "tasks": [task("item", item="minecraft:paper", count=1)],
        "rewards": [reward("xp", amount=15)],
    },
    {
        "id": "the_farther_star",
        "title": "The Farther Star",
        "subtitle": "The second link of a recursive reveal",
        "icon": "minecraft:firework_star",
        "arm": "veils", "slot": 3, "offset": -14,
        "deps": ["the_far_star"],
        "hide_until_visible": True,
        "desc": describe(
            "The far star's dependent, and the second link of the recursive reveal: this node"
            " is hidden until the far star is visible, and the far star is hidden until the hub"
            " is. One visible end is enough for the whole chain, one link at a time.",
            "A single file cannot show that recursion -- it takes a chain, which is why there"
            " are two stars."),
        "tasks": [task("checkmark", title="Find the farther star")],
        "rewards": [reward("xp", amount=20)],
    },
    {
        "id": "the_earth",
        "title": "The Earth",
        "subtitle": "hideDependencyLines, and a custom reward",
        "icon": "minecraft:grass_block",
        "arm": "veils", "slot": 3, "offset": 14,
        "deps": ["the_far_star"],
        "hide_lines": True,
        "desc": describe(
            "`hideDependencyLines` draws no line arriving at this quest. The node itself is"
            " unaffected, and quests that depend on it still draw their lines to it -- one"
            " edge's worth of line art, withheld.",
            "The reward is `tenet:custom`, the write half of the custom pair: it does whatever"
            " a handler registered under `first_light:announce` does, and warns rather than"
            " failing when no handler is registered. The KubeJS page has the pattern, with a"
            " placeholder id of its own rather than this one."),
        "tasks": [task("checkmark", title="Look back at the earth")],
        "rewards": [
            reward("custom", id="first_light:announce"),
            reward("xp", amount=20),
        ],
    },
]

# ---------------------------------------------------------------------------
# Building the documents
# ---------------------------------------------------------------------------

# Entry key -> JSON field, in the order fields appear in a quest file.
QUEST_FIELDS = [
    ("aliases", "aliases"),
    ("shape", "shape"),
    ("size", "size"),
    ("icon_scale", "iconScale"),
    ("rotation", "rotation"),
    ("show_title", "showTitle"),
    ("deps", "dependsOn"),
    ("lines", "dependencyLines"),
    ("prereq", "prerequisiteMode"),
    ("min_required", "minRequired"),
    ("repeatable", "repeatable"),
    ("cooldown", "repeatCooldownTicks"),
    ("sequential", "sequentialTasks"),
    ("auto_claim", "autoClaim"),
    ("exclusive", "exclusiveGroup"),
    ("invisible", "invisible"),
    ("invisible_until_tasks", "invisibleUntilTasks"),
    ("max_dependents", "maxCompletableDependents"),
    ("hide_until_complete", "hideUntilDependenciesComplete"),
    ("hide_until_visible", "hideUntilDependenciesVisible"),
    ("hide_lines", "hideDependencyLines"),
    ("hide_text", "hideTextUntilComplete"),
    ("hide_details", "hideDetailsUntilStartable"),
    ("requires_stage", "requiresStage"),
    ("tasks", "tasks"),
    ("rewards", "rewards"),
]


def build_quest(entry: dict) -> dict:
    if "x" in entry and "y" in entry:
        x, y = entry["x"], entry["y"]
    else:
        x, y = place(entry["arm"], entry["slot"], entry["offset"])

    doc = {"$schema": SCHEMA_QUEST, "id": entry["id"], "title": entry["title"]}
    if "subtitle" in entry:
        doc["subtitle"] = entry["subtitle"]
    if "desc" in entry:
        doc["description"] = entry["desc"]
    doc["icon"] = {"item": entry["icon"]}
    doc["x"], doc["y"] = x, y
    for key, field in QUEST_FIELDS:
        if key in entry:
            doc[field] = entry[key]
    return doc


def build_group() -> dict:
    return {
        "$schema": SCHEMA_GROUP,
        "id": GROUP_ID,
        "title": "First Light",
        "description": describe(
            "One group, one chapter, every feature. The worked examples used to be twelve"
            " questlines, one per family of mechanisms; they are one orrery now, because a"
            " reader learns more from a single canvas that uses everything than from twelve"
            " that each use a part.",
            "The five quests at its western edge are the engine's own playthrough fixture."
            " `punch_a_tree`, `read_the_sign`, `make_a_table`, `stone_tools` and"
            " `the_underground` keep their ids and their mechanics, because"
            " `QuestPlaythroughTest` walks them by name -- and they end at the hub, so the five"
            " first steps are also the key to the whole sky.",
            "The group starts collapsed in the sidebar. `collapsedByDefault` is one field the"
            " format has, and with one group it is either demonstrated here or not at all."),
        "aliases": ["the_first_light"],
        "icon": {"item": "minecraft:torch"},
        "collapsedByDefault": True,
        "chapters": [CHAPTER_ID],
    }


# ---------------------------------------------------------------------------
# The canvas elements: decoration the worked example draws under its quests.
#
# Thirteen was the plan and eight is what covers it: three pictures, two boxes,
# one line and two labels, exercising every arm and every leaf field the panel
# can edit -- position, size, rotation about the corner, tint and its separate
# alpha, a hover title and all five caption properties, both press actions, the
# pin, the draw order, the dev-only flag and the quest gate. An element here is
# a field the form must have a row for: `ElementPanelLayoutTest` walks this
# list and fails on a field with no row, so adding a field below without a row
# in the panel breaks that test by design.
# ---------------------------------------------------------------------------

ELEMENTS: list[dict] = [
    {
        "type": "image", "id": "orrery_banner",
        "x": -1600, "y": -480, "width": 512, "height": 128, "rotation": 0,
        "image": {"sprite": "minecraft:block/bookshelf"},
        "tint": "#FFFFFFFF", "alpha": 255,
        "title": "The Orrery",
        "label": {"onImage": True, "shadow": True, "inset": 4,
                  "hAlign": "middle", "vAlign": "middle"},
        "click": {"type": "open_uri", "data": "https://ellipog.dev"},
        "locked": False, "order": -10,
    },
    {
        "type": "image", "id": "corner_study",
        "x": -960, "y": -480, "width": 96, "height": 96, "rotation": 12,
        "corner": True,
        "image": {"texture": "first_light:banners/corner.png"},
        "alpha": 220, "locked": True, "dev": True, "order": -9,
    },
    {
        "type": "rect", "id": "granary_box",
        "x": -1450, "y": -260, "width": 420, "height": 300,
        "fillColor": "#1A6FA8DC", "borderColor": "#FF6FA8DC", "borderWidth": 2,
        "order": -20,
    },
    {
        "type": "line", "id": "comet_tail",
        "x1": -1312, "y1": -32, "x2": -1600, "y2": 64,
        "width": 3, "color": "#FF6FA8DC", "arrowhead": "both",
    },
    {
        "type": "text", "id": "chapter_motto",
        "x": -1600, "y": 340, "text": "Every mechanism, one sky.",
        "scale": 2.0, "color": "#FFFFFFFF", "shadow": True, "fixed": False,
    },
    {
        "type": "text", "id": "draft_note",
        "x": -960, "y": 340,
        "text": "Draft: move the treasury arm ten pixels sunward.",
        "dev": True,
    },
    {
        "type": "image", "id": "gated_emblem",
        "x": -320, "y": -480, "width": 128, "height": 128,
        "image": {"sprite": "minecraft:block/lantern"},
        "click": {"type": "open_quest", "data": "punch_a_tree"},
        "requires": "punch_a_tree", "order": -8,
    },
    {
        "type": "text", "id": "veiled_motto",
        "x": -320, "y": 340,
        "text": "The veils lift after the first tree falls.",
        "requires": "punch_a_tree",
    },
]


def build_chapter() -> dict:
    return {
        "$schema": SCHEMA_CHAPTER,
        "id": CHAPTER_ID,
        "title": "The Orrery",
        "subtitle": "Every mechanism, one sky",
        "description": describe(
            "The orrery is the whole engine in one chapter: every task type, every reward type,"
            " every condition, every line the canvas can draw, and every flag that changes what"
            " a player sees -- all of it in one sky.",
            "A hub at the centre opens seven arms. The instruments arm carries the item tasks;"
            " the measures arm carries experience, fluid, statistics and the custom handler;"
            " the compass carries dimension, biome, structure and location; the hunt carries"
            " kills, breaks and observations; the treasury carries eight of the twelve reward types and"
            " every table mode; the clockwork carries the gates, the repeats, the stages and a"
            " translation-key title; the veils carry the hiding family, the aliases and the"
            " custom reward. Every file names the field it demonstrates and says why the field"
            " exists.",
            "The chapter sets all four of its inheritable defaults and then overrides them:"
            " `defaultConsumeItems: true` with tasks that decline it,"
            " `defaultPrerequisiteMode: one_started` with quests that state their own mode,"
            " `autoClaim: enabled` with quests and rewards that pay more quietly, and a"
            " `dependencyStyle` every line may override. Its theme is the default palette with"
            " a `themePatch` layered on top -- two token colours, a corner radius, a motion"
            " duration and an easing curve -- which is how a chapter sets its own look without"
            " shipping a whole theme file.",
            "Read it as documentation, or walk it as a tour: `/tenet complete <quest>` moves"
            " one node at a time, which is how a canvas this size is meant to be seen."),
        "aliases": ["the_engine_room"],
        "icon": {"item": "minecraft:spyglass"},
        "progressionMode": "flexible",
        "defaultPrerequisiteMode": "one_started",
        "defaultConsumeItems": True,
        "autoClaim": "enabled",
        "dependencyStyle": {
            "form": "orthogonal",
            "arrowHead": "chevron",
            "arrowPlace": "target",
            "dash": "solid",
            "weight": "thin",
        },
        "theme": "default",
        "themePatch": {
            "colours": {"raised": "#FF1A1F2B", "line": "#FF6FA8DC"},
            "cornerRadius": 4,
            "motion": 180,
            "easing": "CUBIC_OUT",
        },
        "quests": [entry["id"] + ".json" for entry in QUEST_DEFS],
        "elements": ELEMENTS,
    }


# ---------------------------------------------------------------------------
# The coverage check: the generator's half of the contract
# ---------------------------------------------------------------------------

TASK_TYPES = [
    "advancement", "biome", "break", "checkmark", "custom", "dimension", "fluid", "item",
    "item_tag", "kill", "location", "observation", "stage", "stat", "structure", "xp",
]
REWARD_TYPES = [
    "advancement", "choice", "command", "custom", "item", "loot", "random", "all_table",
    "stage", "xp",
]
TABLE_MODES = ["random", "loot", "all_table", "choice"]
OBSERVE_TYPES = [
    "block", "block_tag", "block_state", "block_entity", "block_entity_type", "entity_type",
    "entity_type_tag",
]
CONDITION_TYPES = ["advancement", "item", "item_tag", "party_size", "score", "stage"]
SHAPES = ["rounded", "square", "circle", "diamond", "hexagon", "octagon", "pentagon", "gear",
          "heart", "tome", "star", "none"]
LINE_VALUES = {
    "form": ["orthogonal", "chamfered", "straight", "curved"],
    "arrowHead": ["chevron", "triangle", "dot", "diamond", "none"],
    "arrowPlace": ["target", "both", "mid", "stream"],
    "arrowDensity": ["low", "medium", "high"],
    "dash": ["solid", "dashed", "dotted", "dash_dot", "double", "hazard"],
    "weight": ["thin", "thick", "bold", "conduit"],
}


def walk_rewards(rewards):
    """Every reward, including the ones nested inside inline tables."""
    for entry in rewards:
        yield entry
        inline = entry.get("inline")
        if inline:
            for table_entry in inline.get("entries", []):
                yield from walk_rewards([table_entry.get("reward", {})])


def walk_conditions(tasks, rewards):
    for holder in list(tasks) + list(walk_rewards(rewards)):
        for cond in holder.get("conditions", []):
            yield cond


def check_coverage(quests: list[dict], chapter: dict, group: dict):
    problems: list[str] = []

    def need(condition_ok: bool, what: str):
        if not condition_ok:
            problems.append("missing: " + what)

    ids = {q["id"] for q in quests}
    aliases = {alias for q in quests for alias in q.get("aliases", [])}
    all_tasks = [t for q in quests for t in q.get("tasks", [])]
    all_rewards = [r for q in quests for r in walk_rewards(q.get("rewards", []))]
    task_types = {t["type"] for t in all_tasks}
    reward_types = {r["type"] for r in all_rewards}
    conditions = list(walk_conditions(all_tasks, [r for q in quests for r in q.get("rewards", [])]))
    condition_types = {c["type"] for c in conditions}

    for kind in TASK_TYPES:
        need("tenet:" + kind in task_types, "task type tenet:" + kind)
    for kind in REWARD_TYPES:
        need("tenet:" + kind in reward_types, "reward type tenet:" + kind)
    for mode in TABLE_MODES:
        need(any(r["type"] == "tenet:" + mode for r in all_rewards),
             "table mode tenet:" + mode)
    need(any(r.get("table") for r in all_rewards), "a named table reward")
    need(any(r.get("inline") for r in all_rewards), "an inline table reward")
    need(any(r["type"] == "tenet:random" and r.get("table") for r in all_rewards)
         or any(entry.get("reward", {}).get("table")
                for r in all_rewards if r.get("inline")
                for entry in r["inline"].get("entries", [])),
         "a nested table roll")

    item_tasks = [t for t in all_tasks if t["type"] == "tenet:item"]
    for match in ["none", "fuzzy", "strict"]:
        need(any(t.get("match") == match for t in item_tasks), "item match: " + match)
    need(any(t.get("components") for t in item_tasks), "item components")
    need(any(t.get("onlyFromCrafting") for t in item_tasks), "onlyFromCrafting")
    need(any(t.get("consumeItems") is True for t in item_tasks), "consumeItems: true on a task")
    need(any(t.get("consumeItems") is False for t in item_tasks),
         "consumeItems: false on a task (the declining task)")
    need(any(t.get("count", 0) >= 256 for t in item_tasks), "an item count in the hundreds")
    need(any(t["type"] == "tenet:xp" and t.get("points") is True for t in all_tasks),
         "an xp task in points")
    need(any(t["type"] == "tenet:xp" and t.get("points") is False for t in all_tasks),
         "an xp task in levels")
    need(any(t.get("ignoreDimension") for t in all_tasks), "location ignoreDimension")
    kills = [t for t in all_tasks if t["type"] == "tenet:kill"]
    need(any(t.get("customName") for t in kills), "a kill by customName")
    need(any(t.get("nbtFilter") for t in kills), "a kill by nbtFilter")
    need(any(t.get("entityTypeTag") for t in kills), "a kill by entityTypeTag")
    breaks = [t for t in all_tasks if t["type"] == "tenet:break"]
    need(any(t.get("block") for t in breaks), "a break by block id")
    need(any(t.get("blockTag") for t in breaks), "a break by blockTag")
    need(any(t.get("blockState") for t in breaks), "a break by blockState")
    observations = [t for t in all_tasks if t["type"] == "tenet:observation"]
    for observe in OBSERVE_TYPES:
        need(any(t.get("observeType") == observe for t in observations),
             "observation of " + observe)
    need(any(t.get("timer", 20) != 20 for t in observations), "an observation timer that differs")
    need(any(t.get("autoSubmitTicks", 20) != 20 for t in all_tasks),
         "autoSubmitTicks on an expensive check")
    need(any(t.get("optional") for t in all_tasks), "an optional task")

    for kind in CONDITION_TYPES:
        need("tenet:" + kind in condition_types, "condition type tenet:" + kind)

    item_rewards = [r for r in all_rewards if r["type"] == "tenet:item"]
    need(any(r.get("randomBonus") for r in item_rewards), "item reward randomBonus")
    need(any(r.get("onlyOne") for r in item_rewards), "item reward onlyOne")
    need(any(r.get("components") for r in item_rewards), "item reward components")
    need(any(r["type"] == "tenet:xp" and r.get("levels") for r in all_rewards),
         "an xp reward in levels")
    commands = [r for r in all_rewards if r["type"] == "tenet:command"]
    need(any(r.get("silent") for r in commands), "a silent command reward")
    need(any("{" in r.get("command", "") for r in commands), "command placeholders")
    need(any(r["type"] == "tenet:stage" and r.get("remove") for r in all_rewards),
         "a stage reward that removes")
    need(any(r.get("team") for r in all_rewards), "a team reward")
    need(any(r.get("excludeFromClaimAll") for r in all_rewards), "excludeFromClaimAll")
    need(any(r.get("ignoreRewardBlocking") for r in all_rewards), "ignoreRewardBlocking")
    need(any(r.get("auto") == "no_toast" for r in all_rewards), "a no_toast reward")
    need(any(r.get("auto") == "invisible" for r in all_rewards), "an invisible reward")

    need(any(q.get("repeatable") for q in quests), "a repeatable quest")
    need(any(q.get("repeatCooldownTicks", 0) > 0 for q in quests), "a repeat cooldown")
    need(any(q.get("sequentialTasks") for q in quests), "sequentialTasks")
    need(any(q.get("invisible") for q in quests), "an invisible quest")
    need(any(q.get("invisible") and q.get("invisibleUntilTasks", 0) > 0 for q in quests),
         "invisible with invisibleUntilTasks")
    need(any(q.get("showTitle") for q in quests), "a quest that shows its title")
    need(sum(1 for q in quests if q.get("showTitle")) * 2 < len(quests),
         "showTitle to stay the exception")
    need(any(q.get("exclusiveGroup") for q in quests), "an exclusive group")
    need(any(q.get("minRequired", 0) > 0 for q in quests), "an OR-gate (minRequired)")
    need(any(q.get("maxCompletableDependents", 0) > 0 for q in quests),
         "maxCompletableDependents")
    for field, label in [
        ("hideUntilDependenciesComplete", "hideUntilDependenciesComplete"),
        ("hideUntilDependenciesVisible", "hideUntilDependenciesVisible"),
        ("hideDependencyLines", "hideDependencyLines"),
        ("hideTextUntilComplete", "hideTextUntilComplete"),
        ("hideDetailsUntilStartable", "hideDetailsUntilStartable"),
        ("requiresStage", "requiresStage"),
    ]:
        need(any(q.get(field) for q in quests), label)
    need(any(q.get("autoClaim") == "disabled" for q in quests),
         "a quest that opts out of the chapter's auto-claim")

    need(any(isinstance(q.get("title"), dict) and q["title"].get("fallback") for q in quests),
         "a title written as a translation key with a fallback")
    need(any(q.get("aliases") for q in quests), "a quest alias")
    alias_dependency = False
    for q in quests:
        for dep in q.get("dependsOn", []):
            if dep not in ids and dep in aliases:
                alias_dependency = True
    need(alias_dependency, "a dependency written against an alias")
    need(chapter.get("aliases"), "a chapter alias")
    need(group.get("aliases"), "a group alias")

    shapes = {q.get("shape", "rounded") for q in quests}
    for shape in SHAPES:
        need(shape in shapes, "shape " + shape)
    need(any(q.get("size", 48) <= 16 for q in quests), "a node at the smallest size")
    need(any(q.get("size", 48) >= 200 for q in quests), "a node at the largest size")
    need(any(q.get("iconScale", 0.75) >= 1.0 for q in quests), "iconScale at 1.0")
    need(any(q.get("iconScale", 0.75) <= 0.5 for q in quests), "iconScale at or below 0.5")
    need(any(q.get("rotation") for q in quests), "a turned node")

    lines = [chapter.get("dependencyStyle", {})]
    for q in quests:
        lines.extend(q.get("dependencyLines", {}).values())
    for axis, values in LINE_VALUES.items():
        for value in values:
            need(any(line.get(axis) == value for line in lines), f"line {axis}: {value}")
    need(any(line.get("bend", 0) <= -0.8 for line in lines), "bend at -0.8")
    need(any(line.get("bend", 0) >= 0.8 for line in lines), "bend at 0.8")
    need(any(line.get("fromAnchor") is not None for line in lines), "fromAnchor")
    need(any(line.get("toAnchor") is not None for line in lines), "toAnchor")
    need(any(line.get("fromHandle") is not None for line in lines), "fromHandle")
    need(any(line.get("toHandle") is not None for line in lines), "toHandle")

    need(chapter.get("defaultConsumeItems") is True, "chapter defaultConsumeItems")
    need(chapter.get("defaultPrerequisiteMode") != "all_completed",
         "a chapter defaultPrerequisiteMode that differs from the built-in")
    need(chapter.get("autoClaim") == "enabled", "a chapter with auto-claim enabled")
    need(chapter.get("dependencyStyle"), "a chapter dependencyStyle")
    need(chapter.get("theme"), "a chapter theme")
    need(chapter.get("themePatch"), "a chapter themePatch")
    need(chapter.get("progressionMode") == "flexible", "the chapter to be flexible")
    need(group.get("collapsedByDefault") is True, "a group collapsed by default")

    elements = chapter.get("elements", [])
    for arm in ["image", "rect", "line", "text"]:
        need(any(e.get("type") == arm for e in elements),
             "a canvas element of type " + arm)
    need(any(e.get("label", {}).get("inset") for e in elements),
         "a caption with an inset")
    need(any(e.get("label", {}).get("shadow") for e in elements),
         "a caption with a shadow")
    need(any(e.get("click", {}).get("type") not in (None, "none") for e in elements),
         "a pressable element")
    need(any(e.get("requires") for e in elements),
         "an element gated on a quest")
    need(any(e.get("dev") for e in elements),
         "a dev-only element")

    default_mode = chapter.get("defaultPrerequisiteMode", "all_completed")
    effective = set()
    for q in quests:
        if q.get("dependsOn"):
            effective.add(q.get("prerequisiteMode", default_mode))
    for mode in ["all_completed", "one_completed", "all_started", "one_started"]:
        need(mode in effective, "an effective prerequisiteMode of " + mode)

    seen = set()
    for q in quests:
        if q["id"] in seen:
            problems.append("duplicate id: " + q["id"])
        seen.add(q["id"])
    for q in quests:
        for dep in q.get("dependsOn", []):
            need(dep in ids or dep in aliases, f"{q['id']} depends on unknown {dep}")
        if q.get("minRequired", 0) > len(q.get("dependsOn", [])):
            problems.append(q["id"] + ": minRequired exceeds its dependency count")
        for key in q.get("dependencyLines", {}):
            need(key in q.get("dependsOn", []),
                 f"{q['id']} styles a line to {key}, which is not a dependency")

    if problems:
        print("coverage check failed -- nothing written:")
        for problem in problems:
            print("  - " + problem)
        raise SystemExit(1)

    print(f"coverage check passed: {len(quests)} quests, "
          f"{len(task_types)} task types, {len(reward_types)} reward types, "
          f"{len(condition_types)} condition types, {len(shapes)} shapes")


# ---------------------------------------------------------------------------
# Writing
# ---------------------------------------------------------------------------

def write_json(path: pathlib.Path, document: dict):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(document, indent=2, ensure_ascii=False) + "\n",
                    encoding="utf-8", newline="\n")


def stale_groups() -> list[str]:
    return sorted(p.name for p in QUESTS.iterdir()
                  if p.is_dir() and p.name not in (GROUP_ID, "_schema", "reward_tables")
                  and not p.name.startswith("_"))


def main() -> int:
    quests = [build_quest(entry) for entry in QUEST_DEFS]
    chapter = build_chapter()
    group = build_group()

    check_coverage(quests, chapter, group)

    # This script owns these two directories and rewrites them whole, so a quest removed from
    # the catalogue does not linger as a file the chapter manifest no longer lists.
    for directory in (GROUP_DIR, TABLES_DIR):
        if directory.exists():
            shutil.rmtree(directory)

    for quest in quests:
        write_json(CHAPTER_DIR / (quest["id"] + ".json"), quest)
    write_json(CHAPTER_DIR / "chapter.json", chapter)
    write_json(GROUP_DIR / "group.json", group)
    for name, table in TABLES.items():
        write_json(TABLES_DIR / (name + ".json"), table)

    files = len(quests) + 2 + len(TABLES)
    print(f"wrote {files} files: {len(quests)} quests, a chapter, a group, "
          f"{len(TABLES)} reward tables")
    print(f"  {CHAPTER_DIR.relative_to(QUESTS.parent.parent)}")

    stale = stale_groups()
    if stale:
        print("stale group folders beside the examples (not this script's to delete):")
        for name in stale:
            print("  ! " + name)
    return 0


if __name__ == "__main__":
    sys.exit(main())
