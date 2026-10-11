# Tasks

A quest's `tasks` array is what it asks for. Every task must be satisfied unless it is marked
`optional` — and if they are all optional, any single one of them satisfies the quest, because a quest
where nothing at all is required would complete itself the instant it unlocked.

Every task object carries its `type` and that type's fields, flat at the same level:

```json
{ "type": "tenet:item", "item": "minecraft:oak_log", "count": 8 }
```

A field that belongs to another type is accepted and ignored: the [[tenet:authoring/validation|validator]]
checks a task against every type's fields at once, so what it reports is a field **no** type declares — a
typo like `titl` rather than a field belonging to the type next door. What each type actually reads is
what the codec decides, and the list of what this build has — with every field each type takes — is also
one command away:

```cmd
/tenet types
```

## Every task has these

| Field | Default | Meaning |
|---|---|---|
| `optional` | `false` | This one does not have to be done for the quest to complete. |
| `autoSubmitTicks` | the type's own cadence | How often the task is re-checked, in ticks — twenty to the second, 1 to 72000. Most types check every 20; dimension checks every 100, advancement every 5, and stat and location every 3. Raise it for an expensive check. |
| `conditions` | none | Gates this task on the player: an item, a tag, a score, an advancement, a stage, or how many of a party are online. Every entry must hold. See [[tenet:authoring/conditions]]. |
| `disableToast` | `false` | Finishing this task raises no task toast. A quieted task in an announced quest stays quiet; an announced task in a quieted quest stays quiet through the quest's own flag. See [[tenet:authoring/quests#announcements]]. |
| `title` | the type's own | The words the row wears instead of the type's own sentence. A checkmark's title is its button's text; a checkmark that says nothing draws a bare button beside the state box. |
| `icon` | the type's own | The picture the row wears instead of the type's own: an item, a texture file, or an entity drawn as its spawn egg. An entity with no egg keeps the type's picture. |
| `tags` | none | Words this task answers to in lookups by tag — see [[tenet:authoring/quests#tags]]. Each tag is lowercase letters, digits and underscores, the same rule an id follows. |

## What a player has to do, and what just happens

A task that takes nothing completes itself the moment its count is met: gather eight logs and the
quest ticks over. A task that takes something waits for the player to press **Submit**, because the
press is the consent to take it — an item task with `consumeItems`, an `xp` task, and a `fluid` task.
A `checkmark` is nothing but the button.

Task ids are namespaced, so the built-ins live under `tenet:` and a mod's own type is its own id. The
types below are the eighteen this build ships.

| Type | Counts |
|---|---|
| `tenet:advancement` | An advancement earned, or one criterion of one. |
| `tenet:biome` | Standing in a biome, or any biome of a tag. |
| `tenet:break` | Blocks broken, filtered by id, tag or state. |
| `tenet:checkmark` | Nothing — it is a button the player presses. |
| `tenet:custom` | Whatever a registered handler answers. |
| `tenet:dimension` | Being in a dimension. |
| `tenet:energy` | Energy handed over, stored in carried items. |
| `tenet:filter` | Carried items matching a filter expression. |
| `tenet:fluid` | Fluid handed over, carried in buckets. |
| `tenet:item` | Carried items, with matching and consume rules. |
| `tenet:item_tag` | Carried items belonging to an item tag. |
| `tenet:kill` | Mobs killed, filtered by id, tag, name or SNBT. |
| `tenet:location` | Being inside a box. |
| `tenet:observation` | Looking at a block or an entity for a moment. |
| `tenet:stage` | Having a stage. |
| `tenet:stat` | A vanilla statistic's value. |
| `tenet:structure` | Being inside a structure. |
| `tenet:xp` | Experience handed over. |

## `tenet:advancement`

| Field | Meaning |
|---|---|
| `advancement` | The advancement's namespaced id, e.g. `minecraft:story/mine_stone`. |
| `criterion` | One criterion of it instead of the whole advancement. Absent means the whole one. |

## `tenet:biome`

| Field | Meaning |
|---|---|
| `biome` | The biome to stand in. A `#` tag counts any biome of it, e.g. `#minecraft:is_forest`. |

## `tenet:break`

| Field | Default | Meaning |
|---|---|---|
| `block` | — | The block's namespaced id, e.g. `minecraft:stone`. |
| `blockTag` | — | Or a block tag, to count any block in it. Its own field rather than a `#` in `block`. The tag wins when both are named. |
| `blockState` | — | Only count this block state, e.g. `minecraft:wheat[age=7]`. Narrows `block` or `blockTag`, or stands alone. |
| `value` | `1` | How many breaks count as enough, 1 to 1,000,000. |

With none of `block`, `blockTag` or `blockState`, any broken block counts.

What counts is the act, not the drop: silk touch, fortune and a voided drop all count,
because the break event fires for the break either way. A break that never happens — a
protected block, a cancelled event — counts nothing. A predicate that does not parse matches
nothing, and the validator refuses it at the file's line; like a kill task's SNBT filter, it
fails closed.

## `tenet:checkmark`

| Field | Meaning |
|---|---|
| `title` | The label on the button the player presses. |

The simplest way to say "read the sign", "talk to the NPC", "make the choice" — anything the game
cannot see.

## `tenet:custom`

| Field | Meaning |
|---|---|
| `id` | The id a handler was registered under: by a mod during its construction, or by a script — see [[tenet:authoring/kubejs]]. |
| `value` | The number the handler's answer has to reach, 1 to 1,000,000,000. |

The handler answers how far along the player is, and the engine treats that answer exactly as it
treats a stat's or a kill count's. Nothing outside a handler can advance a task, so a custom task is a
measured task or it is nothing.

A `tenet:custom` task whose handler is not registered is not an error: the quest still loads and the
validator warns that this build has nothing registered for the id — which is what a pack shipping a
handler mod as an optional dependency wants to see.

## `tenet:dimension`

| Field | Meaning |
|---|---|
| `dimension` | The dimension the player has to be in, e.g. `minecraft:the_nether`. |

## `tenet:energy`

| Field | Default | Meaning |
|---|---|---|
| `value` | — | How much stored energy counts as enough, in Forge Energy units. |
| `maxInput` | `0` | The most each item may contribute; `0` means unlimited. the legacy mod calls this `max_input`, capping each insertion into its task screen. |
| `manualOnly` | `false` | Never count from the inventory on the tick; only a submit press counts. |

Hand over energy stored in carried items — batteries, capacitors, charged tools — read through the
loader's energy access, which is also what a submit drains. the legacy mod fills this task by piping
energy into a task screen block; Tenet has no such block, so this is the carried-container path.
On Fabric the library is Team Reborn Energy, and it is optional: an instance without it reads
zero here. The integration lives in Armature's energy seam, installed by its entry points;
Tenet only reads it.

## `tenet:fluid`

| Field | Meaning |
|---|---|
| `fluid` | The fluid's namespaced id, e.g. `minecraft:water`. |
| `amount` | How much of it, in millibuckets, 1 to 1,000,000. A bucket is a thousand. |

Carried in the buckets the player holds — a submit empties them and hands the empties back —
and in anything else that holds fluid: tanks, capsules and canisters count through the loader's
fluid access, which is also what a submit drains. the legacy mod fills this task at a task screen
block; Tenet has no such block, so this is the carried-container path.

| Field | Default | Meaning |
|---|---|---|
| `manualOnly` | `false` | Never count from the inventory on the tick; only a submit press counts. the legacy mod calls this `task_screen_only`. |

## `tenet:item`

| Field | Default | Meaning |
|---|---|---|
| `item` | — | The item's namespaced id. |
| `count` | `1` | How many, 1 to 6400. |
| `components` | — | 1.21 data components, keyed by component id in the datapack's own spelling. |
| `match` | `strict` | How closely a carried stack must match: `none` ignores its data, `fuzzy` needs the data this task names, `strict` needs the whole stack. |
| `consumeItems` | the chapter's `defaultConsumeItems` | Whether handing it in takes the items. |
| `onlyFromCrafting` | `false` | Only items the player crafted themselves count. Counted from lifetime crafting statistics, so handing the stack away does not un-count it. |
| `manualOnly` | `false` | Never count from the inventory on the tick; only a submit press counts. the legacy mod calls this `task_screen_only`, filled by piping into a task screen; Tenet has no such block, so the piped path is not ported and this flag keeps the manual half. |

`components` is how a task asks for a *renamed* sword rather than a plain one: a text component's
value is a string holding JSON, as the codec writes it, and the editor writes that form when you pick
a renamed item:

```json
{
  "type": "tenet:item",
  "item": "minecraft:diamond_sword",
  "components": { "minecraft:custom_name": "\"Tempered Blade\"" }
}
```

An item this build does not have keeps its id and the quest still loads: the row draws a placeholder
and says the item is missing, so a removed mod can come back.

## `tenet:item_tag`

| Field | Default | Meaning |
|---|---|---|
| `tag` | — | An item tag, without a leading `#`, e.g. `minecraft:logs`. Any item in it counts. |
| `count` | `1` | How many, 1 to 6400. |
| `consumeItems` | the chapter's default | Whether handing it in takes the items. |
| `onlyFromCrafting` | `false` | Only items the player crafted themselves count, summed across the tag from lifetime crafting statistics. |
| `manualOnly` | `false` | Never count from the inventory on the tick; only a submit press counts. the legacy mod calls this `task_screen_only`. |

A sibling of `tenet:item` rather than a field on it, so a tag where an id is expected is an error
here as it is everywhere else.

## `tenet:filter`

| Field | Default | Meaning |
|---|---|---|
| `filter` | — | Which items count, as a legacy filter expression (below). |
| `count` | `1` | How many matching items, 1 to 6400. |
| `consumeItems` | the chapter's default | Whether handing it in takes the items. |
| `manualOnly` | `false` | Never count from the inventory on the tick; only a submit press counts. |

the legacy mod has no tag field of its own: tags — and the `mod`, `and`, `or` and `not` combinators —
arrive through filter stacks, an `ftbfiltersystem:smart_filter` item carrying the expression in its
`ftbfiltersystem:filter` component. The migration tool passes that string through verbatim, so a
converted pack reads the same vocabulary it was written in:

```json
{
  "type": "tenet:filter",
  "filter": "or(item(minecraft:coal)item_tag(minecraft:coals))",
  "count": 4
}
```

A juxtaposition of forms at the top level reads as an implicit `and`, the same reading the legacy mod's own
parser gives it. Any function a "hand in N" task cannot answer (`component`, `durability` and
friends) is refused with its name in the message, and the migration tool reports such tasks as
manual work rather than emitting them. An `item()` id whose mod is not installed warns like any
other missing item: the id is kept and the quest still loads.

## `tenet:kill`

| Field | Default | Meaning |
|---|---|---|
| `entity` | — | The mob's namespaced id, e.g. `minecraft:zombie`. |
| `entityTypeTag` | — | Or an entity tag, to count any mob in it. Its own field rather than a `#` in `entity`. The tag wins when both are named. |
| `value` | `1` | How many kills count as enough, 1 to 1,000,000. |
| `customName` | — | Only count a mob with this name. |
| `nbtFilter` | — | An SNBT filter, for the mobs an id cannot pick out. |

With neither `entity` nor `entityTypeTag`, any mob counts.

## `tenet:location`

| Field | Default | Meaning |
|---|---|---|
| `position` | — | One corner of the box, in blocks, as `[x, y, z]` — exactly three integers. |
| `size` | `[1, 1, 1]` | How far the box reaches from that corner, as `[x, y, z]`. |
| `dimension` | — | The dimension the box is in. Absent counts the box anywhere the coordinates fall — prefer `ignoreDimension` below, which says so on purpose. |
| `ignoreDimension` | `false` | Count the box anywhere, instead of only in the dimension above. |

## `tenet:observation`

| Field | Default | Meaning |
|---|---|---|
| `observeType` | `block` | What counts as looking: `block`, `block_tag`, `block_state`, `block_entity`, `block_entity_type`, `entity_type`, or `entity_type_tag`. |
| `toObserve` | — | The block or entity to look at, following `observeType`. For `block_entity` this is an SNBT filter, e.g. `{Items:[…]}`, and for the tag modes it is a tag id without the `#`. |
| `timer` | `20` | How long to look at it, in ticks, 0 to 1200. |

## `tenet:stage`

| Field | Meaning |
|---|---|
| `stage` | The stage the player has to have, e.g. `my_pack:left_the_village`. |
| `teamStage` | `false` | Ask about the team's stages rather than the player's own. One member's induction then satisfies the task for everybody. the legacy mod calls this `team_stage`. |

The read half of the same feature whose write half is a stage [[tenet:authoring/rewards|reward]], a command, or
a script: a pack grants a stage somewhere and asks about it here. Nothing is required of the id — a
stage exists by being granted — so a typo shows up as a task that never completes rather than as a file
that will not load.

## `tenet:stat`

| Field | Meaning |
|---|---|
| `stat` | Which vanilla statistic to watch, e.g. `minecraft:walk_one_cm`. |
| `value` | The value that counts as enough, 1 to 1,000,000,000. |

## `tenet:structure`

| Field | Meaning |
|---|---|
| `structure` | The structure to be inside, e.g. `minecraft:village_plains`. A `#` tag counts any of them. |

The in-game editor's list of structures is the **connected server's** own, because a structure is worldgen
data a client is never sent: a modded or datapack structure appears in the list once you have joined, and
one added by a datapack reload arrives on the next join. The box also takes a typed id, so a structure the
list has not offered yet is still settable.

## `tenet:xp`

| Field | Default | Meaning |
|---|---|---|
| `value` | — | The number of points or levels, 1 to 100,000. |
| `points` | `true` | `true` counts experience **points**; `false` counts whole **levels**. |

Manual only, deliberately: experience is not something the engine should take the moment a player
happens to be carrying enough — the Submit press is the consent, and the press takes it.
