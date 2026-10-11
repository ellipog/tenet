# Conditions

A condition is a question about a player — do they have this item, this stage, this score, this
advancement — and a task or a reward can wait for the answer. A task with a condition counts only for
a player who meets it; a reward with a condition is paid only to one. Nothing is consumed to satisfy
one, and nothing about a condition changes a quest's dependency graph.

```json
{
  "type": "tenet:checkmark",
  "title": "Hand over the list",
  "conditions": [
    { "type": "tenet:item", "item": "minecraft:cobblestone", "count": 8 }
  ]
}
```

`conditions` sits on the task or reward itself, beside `optional` or `auto`, and it is a list. **Every
entry must hold** — the list is an AND, and an empty list (or no field at all) is met by definition.
Conditions can be put on a task, on a reward, or on both; a quest's `requiresStage` is a separate,
quest-level gate from the [[tenet:authoring/quests|quest's own fields]].

## How a condition is asked

A condition is asked **per player**, at the moment the player's answer would matter:

- **A measured task** — the server's own tick counts a member's progress only if that member meets the
  task's conditions. A member who does not contributes nothing, and in a `pooled` party they do not
  pay for it either.
- **A submit** — the button refuses, so a press cannot be used to skip a condition.
- **A reward** — every path that pays one checks its conditions: the claim, Claim all, the answer to a
  choice offer, and the two automatic ones. A reward whose conditions are unmet is not lost; it stays
  unclaimed and is paid the moment they hold. The exception is a reward that is an **entry of a reward
  table**: it is handed out by the roll rather than claimed, so the validator refuses `conditions` on
  one — put them on the table reward itself, where every payout path does check them.

A condition is a **gate on acquiring progress, not a lock on what is already recorded**. A task that
was satisfied while its condition held stays satisfied if the condition stops holding — complete is
complete — which is the same direction the engine's task counting has always taken. Rewards are the
exception by nature: they are paid on the day, so a reward's condition is checked on the day.

An unknown condition type is a warning in the file, reported at its own line by the
[[tenet:authoring/validation|validator]], exactly as an unknown task or reward type is: the node is
kept as a placeholder that names the missing mod, and the gate it guards stays shut until that mod
is loaded.

## Every condition has these

| Field | Default | Meaning |
|---|---|---|
| `type` | — | Which condition this is; the rest of the fields follow it. |

Nothing else is shared between the types below — a condition is small by design, so each type names
its own fields.

## The six types

| Type | Asks |
|---|---|
| `tenet:advancement` | Whether the player has earned an advancement, or one of its criteria. |
| `tenet:item` | Whether the player is carrying a count of an item. |
| `tenet:item_tag` | Whether the player is carrying a count of any item in a tag. |
| `tenet:party_size` | How many members of the player's party are online now. |
| `tenet:score` | Whether a scoreboard objective holds at least a value. |
| `tenet:stage` | Whether the player has a stage. |

## `tenet:item`

| Field | Default | Meaning |
|---|---|---|
| `item` | — | The item's namespaced id. |
| `count` | `1` | How many of them. |
| `components` | — | 1.21 data components, as on an item task: a condition can ask for the renamed sword and not the plain one. |
| `match` | `strict` | How closely a carried stack must match: `none` ignores its data, `fuzzy` needs the data it names, `strict` needs the whole stack. |

The condition half of the `tenet:item` task, and the same matching. It counts and never takes: the
item task's `consumeItems` has no counterpart here, because a condition only ever asks.

## `tenet:item_tag`

| Field | Default | Meaning |
|---|---|---|
| `tag` | — | The item tag's id, e.g. `minecraft:logs`. Any item in it counts. |
| `count` | `1` | How many of them. |

## `tenet:score`

| Field | Default | Meaning |
|---|---|---|
| `objective` | — | The scoreboard objective's name, as the scoreboard command spells it. |
| `min` | — | The score to reach, 1 or more. |

Read from the scoreboard, per player. Two things are worth knowing:

- **A missing objective reads as zero**, not as an error. Objectives are world state rather than
  registry entries, so the validator cannot check the name — a typo locks the gate, and nothing in
  the log says so. Create it with `/scoreboard objectives add <name> dummy`.
- **An objective's name is a plain word** in the scoreboard command, so it cannot contain a colon:
  `condition_gallery_standing`, not `pack:standing`. The condition still takes any name the server
  actually has.

## `tenet:advancement`

| Field | Default | Meaning |
|---|---|---|
| `advancement` | — | The advancement's namespaced id, e.g. `minecraft:story/mine_diamond`. |
| `criterion` | — | Require one criterion of it instead of the whole advancement. |

## `tenet:stage`

| Field | Default | Meaning |
|---|---|---|
| `stage` | — | The stage's id. A stage exists by being granted, so there is no list to pick from and no id to validate. |

The condition half of the `tenet:stage` task. `/tenet stage add` grants one, a stage reward grants
one, and a script can too.

## `tenet:party_size`

| Field | Default | Meaning |
|---|---|---|
| `min` | — | How many members of the party have to be online, 1 to 1000. |

**Online members, not the roster, and you count as one.** A solo player is a party of one, so
`"min": 2` is the bring-a-friend gate; a party of three with two members offline is one person here.
Nothing else about the party matters — its progress mode, its owner, who is in it — because this asks
how many people are actually present, which is what a togetherness gate is about.

## Reusing a setup

A condition worth writing twice is worth saving once: the condition row's ★ keeps the setup as a
[[tenet:authoring/templates|template]], and the next entry takes it from its `+ Condition` row with
one press.

## A worked example

`tools/quests/first_light/first_steps/` carries all six condition types in its clockwork arm: a task
gated by an item, an item tag and a stage together (`the_witness_list.json`), a reward gated by an
advancement (`the_shepherds_tally.json`), and a reward behind a scoreboard objective and a party gate
at once (`the_festival.json`, with the objective created by `the_first_account.json`'s command
reward). The [[tenet:authoring/quest-files|quest files]] page says how the folder is laid out.
