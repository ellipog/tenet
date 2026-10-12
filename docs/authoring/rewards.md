# Rewards

Rewards are what a quest gives when it completes. They are separate from [[task]]s, evaluated once,
and by default they wait for the player to claim them: the claim button on a finished quest's card,
or the **Rewards** menu, which groups the whole book by chapter and collects at three levels: a
**Claim** on one reward's row, **Claim Chapter (N)** on a chapter's banner, and **Claim all** in the
footer for everything the server is holding. Two toggles decide what the menu is showing (*Ready
to claim* and *Choices pending*), and the footer's sweep follows whichever is active, so a
view of choices collects choices and each choice still opens its own picker. Pressing a ready
reward's row claims it too, exactly as its **Claim** button does. There is no history
view: a list of everything already collected is unbounded in a large pack, so the menu shows only
what the server still owes.

Each row reads across three columns: what the quest is, what it gives, and the
button that takes it. A quest's rewards are listed in the details column whether or not its row is
folded open, so a chapter can be read at a glance and unfolded only where something needs deciding.
A chapter's banner wears the chapter's title with its ready count inline ("The Engine · 1 ready");
a quest holding exactly one reward draws one row naming quest and payout together
("Make a Table · x8 Stick") under the payout's own icon.
Locked rewards are never listed here, because the menu is about what is owed, while the viewer still
shows a locked reward with what it waits for.

```json
{ "type": "tenet:item", "item": "minecraft:diamond", "count": 4 }
```

## Every reward has these

| Field | Default | Meaning |
|---|---|---|
| `team` | the tree's `defaultTeamReward` | One claim for the team, rather than one per player. |
| `auto` | `default` | When it is handed over. See below. |
| `excludeFromClaimAll` | `false` | Claim all leaves this one for its own press. |
| `ignoreRewardBlocking` | `false` | Give it even while the team's payouts are held by `/tenet rewards block`. A quest's own flag covers every reward on it; this one covers just itself. |
| `disableToast` | `false` | Collecting this reward raises no toast. Quiets the reward-level notice (toast description, command feedback). See [[tenet:authoring/quests#announcements]]. |
| `title` | the type's own | The words the row wears instead of the type's own sentence. |
| `icon` | the type's own | The picture the row wears instead of the type's own: an item, a texture file, or an entity drawn as its spawn egg. An entity with no egg keeps the type's picture. |
| `tags` | none | Words this reward answers to in lookups by tag (see [[tenet:authoring/quests#tags]]). Each tag is lowercase letters, digits and underscores, the same rule an id follows. |
| `conditions` | none | Gates the payout on the receiving player: an item, a tag, a score, an advancement, a stage, or how many of a party are online. Every entry must hold, and every path that pays checks them. A reward that is an *entry of a reward table* cannot carry them, because the validator refuses it: an entry is handed out by the roll rather than claimed, so put them on the table reward. See [[tenet:authoring/conditions]]. |

`auto` decides the moment the reward changes hands:

| Value | Meaning |
|---|---|
| `default` | Follow the quest's own `autoClaim`, then the chapter's, then the tree's `defaultAutoClaim`, which defaults to `disabled`. |
| `enabled` | Give it the moment the quest completes. |
| `disabled` | Wait for a claim. |
| `no_toast` | Give it automatically, without a toast. |
| `invisible` | Give it automatically; the same silence as `no_toast`. |

### Turning it on for a whole chapter

The fifty dirt-and-wood quests at the start of a pack should not be fifty clicks, and they should not
be fifty settings either. A chapter turns auto-claim on once:

```json
{ "id": "first_steps", "autoClaim": "enabled", "quests": [ ... ] }
```

and a quest that wants to differ says so for itself:

```json
{ "id": "the_boss", "autoClaim": "disabled" }
```

The ladder, most specific first: a reward's own `auto` → the quest's `autoClaim` → the chapter's
`autoClaim` → the pack's `defaultAutoClaim` in `index.json` → off. `default` at any level means "ask
the level below".

Two things a mode can never override:

- **A choice reward is never auto-granted.** Its payout *is* the player's pick, so it stays
  outstanding and is offered the moment the quest is claimed, whatever `auto` says. A file that sets
  an automatic `auto` on a `tenet:choice` gets a warning saying so, because the setting would
  otherwise read as supported.
- **A held team gets nothing.** `/tenet rewards block` outranks every automatic mode; unblocking is
  the one moment it is released.

`no_toast` and `invisible` are the same silence under two names: both grant without a toast and both
suppress the completion notice for that quest, so a chapter of starter quests does not announce
itself fifty times. The words are borrowed from the legacy mod, which is why there are two of them.

While anything is still waiting, the player sees it in two places: a small badge on the quest's node
with the number of rewards, and a count beside the chapter's row in the sidebar (`The Shop (3)`),
which is where the eye goes first.

An operator can hold every automatic payout at once with `/tenet rewards block`, whatever individual
rewards say, and release it with `/tenet rewards unblock`. The switch is stored in the team's
progress rather than in a save, so it is a server-side decision and not up to the player being paid.

## The types

| Type | Gives |
|---|---|
| `tenet:advancement` | An advancement, or one criterion of one. |
| `tenet:choice` | One entry of a table, picked by the player. A choice can never be a table *entry*, because a table that offers a pick inside a roll is refused: a roll cannot wait for an answer. |
| `tenet:command` | A command, run as the player. |
| `tenet:currency` | Money, paid through the installed economy. |
| `tenet:custom` | Whatever a registered handler does. |
| `tenet:item` | Items. |
| `tenet:loot` | A table roll that can come up empty. |
| `tenet:random` | A weighted table roll that promises something. |
| `tenet:all_table` | Every entry of a table. |
| `tenet:stage` | A stage, granted or taken away. |
| `tenet:toast` | A message, shown when collected. |
| `tenet:xp` | Experience, in points or levels. |

## Simple rewards

| Type | Field | Meaning |
|---|---|---|
| `tenet:advancement` | `advancement`, `criterion` | The advancement to award; `criterion` names one criterion instead of the whole thing. |
| `tenet:xp` | `amount`, `levels` | Points, 1 to 100,000, or whole levels when `levels` is `true`. Default is points. |
| `tenet:currency` | `amount` | How much currency to pay, 1 to 1,000,000. The coin it names belongs to the installed economy: a currency mod registers the paying half (`CurrencyReward.CurrencyProvider` via `CurrencyReward.CurrencyProviders.setActive`), and with nothing registered the grant pays nothing while the quest still completes. |
| `tenet:stage` | `stage`, `remove`, `teamStage` | The stage to set; `remove: true` takes it away instead of granting it. `teamStage: true` grants to the team rather than the claiming player. |
| `tenet:custom` | `id` | The id a handler was registered under. A reward whose handler is not registered warns rather than failing. |
| `tenet:toast` | `description` | The message shown when collected, as literal text or a translation key with fallback. Empty shows the generic toast sentence. |

## `tenet:item`

| Field | Default | Meaning |
|---|---|---|
| `item` | none | The item's namespaced id. |
| `count` | `1` | How many. |
| `components` | none | 1.21 data components, as on the item task. |
| `randomBonus` | `0` | Up to this many more, rolled at random on top of the count. |
| `onlyOne` | `false` | Skip it if the player already carries this item, checked by item type and ignoring components, so a plain sword counts as carrying the renamed one. |

## `tenet:command`

The escape hatch every pack reaches for, and the one reward whose reach is the whole server, so it
runs through the server's own dispatcher with the player as the source.

| Field | Default | Meaning |
|---|---|---|
| `command` | none | The command, without the leading slash. |
| `permissionLevel` | `2` | The level it runs at, 0 to 4; 2 is a command block's. |
| `silent` | `false` | Do not say in chat that it ran. |
| `feedbackMessage` | none | A message shown when the command runs; absent shows nothing extra. the legacy mod calls this `feedback_message`. |

The placeholders are the legacy mod's own, kept whole so a pack moved from it does not have to learn a
second vocabulary for the same sentence:

| Placeholder | Becomes |
|---|---|
| `{p}` | The player's scoreboard name |
| `{x}`, `{y}`, `{z}` | The player's block position |
| `{chapter}` | The id of the chapter the quest belongs to |
| `{quest}` | The quest's id |
| `{team}`, `{team_id}`, `{long_team_id}` | The progress owner's id |
| `{member_count}`, `{online_member_count}` | How many members the party has |

An unknown brace-word is left as written rather than blanked: a command that says `{player}` to an
operator reading the log is a one-second fix, and a command that silently loses the word is a mystery.

```json
{
  "type": "tenet:command",
  "command": "say {p} finished {quest}",
  "permissionLevel": 2
}
```

## Table rewards

Four types share one shape: `tenet:random`, `tenet:loot`, `tenet:all_table` and `tenet:choice`.
The type *is* the mode; a file cannot turn a `random` into a `choice` by adding a field.

| Type | What the roll does |
|---|---|
| `tenet:random` | Throws the dice `lootSize` times over the weights, and promises something: there is no empty band. |
| `tenet:loot` | The same, except the empty band exists, so `emptyWeight` is the chance of nothing on a throw. |
| `tenet:all_table` | Grants every entry, no dice. |
| `tenet:choice` | Sends the entries to the player and waits for the pick. |

| Field | Meaning |
|---|---|
| `table` | The table's id, a file under `reward_tables/`, without the `.json` suffix. |
| `inline` | The table itself, written in the reward. A table in its own file never needs one. |

Both are optional and the reward rolls one table or the other, never both; a reward carrying
neither rolls nothing. The type *is* still the mode, because a file cannot turn a `random` into a `choice`
by adding a field.

`random`, `loot` and `all_table` resolve the moment the reward is granted. `choice` cannot, because
the player picks, so the claim marks nothing until it is answered, which also means a crash between the
offer and the pick loses nothing.

## Writing a table

A table is a list of entries, each an ordinary reward with a weight:

```json
{
  "lootSize": 2,
  "emptyWeight": 10,
  "entries": [
    { "weight": 0, "reward": { "type": "tenet:item", "item": "minecraft:bread", "count": 4 } },
    { "weight": 3, "reward": { "type": "tenet:item", "item": "minecraft:iron_ingot", "count": 8 } },
    { "weight": 1, "reward": { "type": "tenet:xp", "amount": 30 } }
  ]
}
```

| Field | Default | Meaning |
|---|---|---|
| `entries` | none | The table. Each entry is a `reward` and a `weight`, which defaults to `1` when absent. |
| `emptyWeight` | `0` | The chance of nothing on a throw, against the positive weights. Only `tenet:loot` includes it. |
| `lootSize` | `1` | How many times the dice are thrown. |
| `title` | the id | What the in-game editor calls the table. Absent, the id is opened out: `tier_1_ores` reads as "Tier 1 ores". |
| `icon` | see below | The item the editor's table browser draws for it: `{ "item": "minecraft:iron_ingot" }`, with an optional `count` and `components`, the same shape a quest's `icon` uses. |
| `uid` | none | The editor's handle for a table written `inline`. A table in its own file does not need one, and the loader ignores it there. |
| `useTitle` | `false` | A reward row that rolls this table wears the table's title rather than the generic roll sentence. |
| `hideTooltip` | `false` | A reward row that rolls this table draws no hover, unless the row is locked, which is still explained, because hiding why a reward is shut would read as a broken row. |

An absent `icon` is not a blank row: the editor draws the first entry that has an item, so a table of
ore drops shows an ore rather than the chest every table reward type carries. A table of experience or
commands (entries with no item of their own) falls back to that entry's type icon, and an empty
table to paper.

> [!NOTE]
> `title`, `icon` and `uid` are for the editor and change nothing about a roll. They are optional, so
> every table written before them still reads, and a file never needs them to work.

A `weight` is how much of the table's probability space an entry takes; its chance is its weight over
the total. **A weight of zero means always granted**, once per roll call, which is how a table says
"and everyone also gets this", so the guaranteed entry lands even when the fashionable one misses.

> [!TIP]
> An entry's `reward` can itself be a table reward, so tables nest. Nesting is cut off past eight
> levels deep with a warning in the log, which is deep enough for a loot cascade and shallow enough
> that a table that accidentally includes itself cannot hang the server.

A table that does not resolve (a typo in `table`, or a file that failed validation) logs a warning
once and grants nothing. That is an author's mistake, not a crash.

## Editing a table in game

Nothing above has to be typed. A `tenet:random`, `tenet:loot`, `tenet:all_table` or `tenet:choice`
reward's **Table** field is a card: it shows the table's icon, its name and its entry count, and it
opens two panels.

**The browser** (`Click to select or create a table`) lists every table the pack has, searchable by
name or id, with `Edit`, `Copy` and `×` on each row and one button that makes one. `×` sets the file
aside, and refuses while any reward or table still names it. The refusal lists the referrers, so the
author can go and look:

- **New table:** a file under `reward_tables/`, with the name you give it.
- **None:** clears the field, so the reward rolls no table.

A table written *inside* a reward (the format's own inline shape) still reads and still rolls, but
the panels no longer make one: a table lives in a file under `reward_tables/` and nowhere else, so
there is one way to make one and one place to look. A reward holding an inline table shows it as what
it is, without an edit path into it.

**The editor** (the `Edit` chip) is the table itself:

| | |
|---|---|
| **The header** | the title (type it, since a blank title falls back to the id), the number of rolls, the entry count, the total weight, and the mode the chances are read as. Two toggles sit beside the title: **Title** (the row wears this table's title) and **Tip** (the row draws no hover). |
| **The rows** | one per entry: its icon and name, its weight with a stepper, and **the chance that weight means** (`Always` for a weight of zero, a percentage otherwise, `(per roll)` when the table rolls more than once, with the chance of seeing it *at least once* on hover). |
| **The fold** | an entry's own fields, exactly as the card draws a reward's: the item (the picker, with the data of the stack you are holding), the count, and, for an entry that is itself a table, a button that opens it, with a breadcrumb back. |
| **+ Item** | the item picker, appending one entry at weight 1. You can also **drag a stack in from EMI or JEI**: EMI lands it at the row under the pointer, JEI appends it to the end of the list, with its count and its data. |
| **+ Reward** | any registered reward type: experience, a command, another table, as an entry. |
| **Import...** | opens two rows. **From inventory** adds one entry per distinct item you carry; **From target chest** does the same for the container you are looking at, which the server resolves from your crosshair when the panel closes. Both append and never touch what is there, and tell you what they did. |
| **Undo** | the last edit to this table, and stops at the table you opened, since a nested table's steps are not its parent's. |
| **Test roll x10** | rolls the table (nesting and all) and shows what came up, without granting anything. The server rolls, because only it can see every table the first one reaches. |
| **Done** | one table back, or out of the panel. `Ctrl+Z` undoes your edits here, and stops at the table you opened. |

Filling a container from a table is a command rather than a button, because it needs the crosshair and a
screen captures the mouse: `/tenet table export <id>`. Reading one is a button above (the panel's
`Import... › From target chest` row), and `/tenet table import <id>` is the same read for a player who is
walking around. See [commands](../commands.md).

## Reusing a setup

A reward worth writing twice is worth saving once: the card's ★ keeps the setup as a
[[tenet:authoring/templates|template]], and the next quest takes it with one press.
