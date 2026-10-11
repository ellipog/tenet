# The quest folder

Everything Tenet loads lives under one root, `config/tenet/quests/`. The folder layout *is* the
format: a [[chapter-group]] is a folder with a manifest, a [[chapter]] is a folder inside it, and each
[[quest]] is one file.

```
config/tenet/quests/
├── index.json                  the root: what exists, and in what order
├── reward_tables/              rolls a reward can name
│   └── bell_toll.json
├── first_light/                a chapter group
│   ├── group.json
│   └── first_steps/            a chapter
│       ├── chapter.json
│       └── punch_a_tree.json   one quest
└── stuck_in_the_past.json      an older single-file questline — still read
```

## Groups and chapters

A group folder holds a `group.json`. Its `chapters` array names the group's chapter folders, in the
order they should appear — that list is the only place the order is expressed:

```json
{
  "id": "first_light",
  "title": "First Light",
  "chapters": ["first_steps"]
}
```

A group also carries its own `description` and `icon` (a group with no icon borrows its first
chapter's on the client), its former ids as `aliases`, and `collapsedByDefault`: with it set, the
book shows the group's chapters collapsed the first time it sees the tree. That first sight is all
it decides — no client ever writes it back, so reopening a group stays reopened.

A chapter folder holds a `chapter.json`. Its `quests` array names the chapter's quest files, in order,
and that order is **load-bearing rather than cosmetic**: in a `linear` chapter it *is* the progression,
because each quest unlocks when the one above it completes.

```json
{
  "id": "first_steps",
  "title": "First Steps",
  "subtitle": "Five quests, no tricks",
  "progressionMode": "linear",
  "quests": [
    "punch_a_tree.json",
    "make_a_table.json",
    "stone_tools.json"
  ]
}
```

A chapter can also wait on other chapters, which is how a pack gates a whole chapter rather than every
quest in it — `dependsOn` refuses its quests until the rule is met, and `completesWhen` says what
finishes it. The two `defaultHideUntilDependencies*` fields are the chapter's defaults for its quests'
*own* reveal flags, so a reveal chapter is one line rather than fifty:

```json
{
  "id": "the_deep",
  "title": "The Deep",
  "dependsOn": ["first_steps"],
  "completesWhen": ["the_festival"],
  "hideUntilDependenciesComplete": true,
  "defaultHideUntilDependenciesComplete": true
}
```

Here the chapter's row is withheld until `first_steps` is finished, and every quest inside it is
withheld until its own prerequisites are met — a quest that should be visible early writes
`"hideUntilDependenciesComplete": false` to say so. A chapter whose quests are all still hidden is
itself left out of a reader's book, so the whole chapter arrives as one reveal.

The fields a group or a chapter can carry beyond those are on [[tenet:authoring/quests]], because they are the
same fields a quest inherits from them: `defaultPrerequisiteMode`, `defaultConsumeItems`,
`defaultFlexibleProgress`, `defaultMinWidth`, `defaultSequentialTasks`, and the
`dependencyStyle` a chapter's lines are drawn with. A chapter's own `dependsOn`, `prerequisiteMode`,
`minRequired`, `completesWhen`, `autofocus`, `hideUntilDependenciesComplete` and `alwaysInvisible`
are documented there too, under
*[a chapter's own dependencies](quests.md#a-chapters-own-dependencies)*. Quests, chapters and
groups each carry an optional `tags` list as well — see
*[tags](quests.md#tags)*.

> [!WARNING]
> **A folder's name is its id, and that is checked.** A folder called `first_light` whose manifest
> says `"id": "first_steps"` is reported, naming both sides — and the folder name wins, because every
> path in the tree is built from it. The same goes for the name lists: a chapter named in `chapters`
> that is not on disk is reported, and a chapter folder that no manifest names is reported too.

## What is drawn on the canvas

A chapter's `elements` array is everything on its canvas that is not a quest: a picture, a label, a rule
or a box. They are drawn **under** the quest nodes and the dependency lines, so a box is a container
around a cluster of quests rather than a lid over it, and among themselves by `order` and then by the
order they are written here.

```json
{
  "id": "first_steps",
  "title": "First Steps",
  "elements": [
    { "type": "rect", "id": "bench", "x": -32, "y": -32, "width": 320, "height": 192,
      "fillColor": "#206FA8DC", "borderColor": "#806FA8DC", "borderWidth": 2, "order": -1 },
    { "type": "text", "id": "bench_label", "x": 0, "y": -56, "text": "The bench",
      "scale": 1.5, "color": "#FFE8D8A0", "shadow": true },
    { "type": "line", "id": "rule", "x1": -40, "y1": -72, "x2": 340, "y2": -72,
      "width": 2, "color": "#606FA8DC", "arrowhead": "none" },
    { "type": "image", "id": "crest", "x": 360, "y": -32, "width": 64, "height": 64,
      "image": { "sprite": "minecraft:block/lodestone_top" }, "rotation": 12,
      "title": "Sealed", "click": { "type": "open_quest", "data": "make_a_table" } }
  ]
}
```

**`x` and `y` are the top-left corner in canvas pixels**, exactly as they are for a quest — the same
coordinate space, the same grid, and the same numbers you see in the editor. `line` is the exception and
says what it is: `x1, y1, x2, y2` are its two endpoints, so a line is placed by placing both its ends.

The four types are `image`, `text`, `line` and `rect`, and every field they read is in
[the chapter schema](../../tools/quests/_schema/chapter.schema.json) — including the ranges, which the
loader clamps rather than refuses, so a `width` of `99999` becomes the largest legal box instead of
costing you the chapter.

| | |
|---|---|
| `image` | A picture from a **file** in a resource pack (`{ "texture": "pack:textures/crest.png" }`) or from a **block-atlas sprite** (`{ "sprite": "minecraft:block/lodestone_top" }`). A sprite always resolves; a file that is not in the pack draws nothing at all. `title` is what the picture says: a hover tooltip, or — when `label.onImage` is set — words painted into the picture, placed by `label.hAlign`, `label.vAlign` and an `inset` from the edge they name, in the font's own shadow or not (`label.shadow`). |
| `text` | A label. `\n` starts a second line, `scale` sizes it, and `shadow` is the font's own shadow — which is what makes words readable over a picture. |
| `line` | A rule with an optional head at either end: `arrowhead` is `none`, `start`, `end` or `both`. |
| `rect` | A filled box with an optional border. The border is drawn across the whole box and the fill inset by `borderWidth`, so the two never fight. |

**Two fields hide an element, and neither is access control.** `dev: true` draws it only for an author
with the editor's advanced depth on, and `requires: "<quest>"` draws it only once that quest is
complete. Both reach every client with the tree — they are presentation, and the file is not a place to
keep a secret.

**`click` makes an element pressable**: `open_quest` opens another quest by id or alias, and `open_uri`
opens an `http` or `https` address in the browser. `show_recipe` opens the named item's recipes in a
viewer — or says there is none installed, because a viewer is a soft dependency. `show_docs` names a
guide page as `<mod>,<book>[,<page>[,<anchor>]]`, and answers with a message naming it: guide books
have no integration here, and a click that silently did nothing would read as a bug in the mod.
`run_command` runs server-side as the pressing player, through the server's own dispatcher, at the
pack's `clickCommandLevel` — with `{p}`, `{x}`/`{y}`/`{z}`, `{chapter}` and `{element}` filled in,
and the command's own output as the feedback. `custom_event` fires `TenetEvents.clickEvent` with the
`namespace:path` id the file named — see [[tenet:authoring/kubejs]] — so a script sorts presses by id.

**`links` marks quests that live elsewhere**: each entry draws in the node layer with its target's
state and icon, and pressing it opens that quest — switching chapters when the quest lives in
another one. A link holds no progress, gates nothing and is never counted for its chapter's
completion; no `dependsOn`, gate or milestone may name one.

```json
{ "id": "gate_hint", "quest": "the_deep_descent", "x": 336, "y": -64 }
```

`quest` is the target by id or alias, in any chapter — a name that resolves to nothing is reported
with the file and line, because the link would then mirror nothing and its press would do nothing.
A link's `id` must not equal any quest, chapter or group id or alias. `x` and `y` are the top-left
corner in canvas pixels, exactly as for a quest; `shape` and `size` fall back to the quest defaults
(`rounded`, 48) rather than copying the target's. the legacy mod calls the target `linked_quest` and writes centre-based doubles, and
the migration tool maps both onto this shape.

**`locked: true` pins a picture against the editor's drag**: grips and moves leave it where it is,
while readers see no difference. the legacy mod calls this `position_locked`, and the migration tool
maps it onto this key — a deliberate edit still writes, so unlocking is always one switch away.

Elements are translated like everything else: `element.<id>.text` for a label's words and
`element.<id>.title` for a picture's caption, in the same `lang` folder as `quest.<id>.title`. A
literal in the file is used when no translation exists, so words in the file are never a raw key on
screen.

> [!WARNING]
> **A sprite that does not exist cannot be checked by the server, and a file that does not exist is not
> a fault either.** The atlas is built by the client, so an unresolvable sprite draws the game's own
> missing-texture marker rather than refusing the chapter — look at the picture, not at the log. The
> one thing that *is* checked is a `requires` or an `open_quest` naming a quest that does not exist,
> which is reported with the file and line, because an element gated on nothing is never drawn and a
> press that opens nothing reads as a broken control.

## The root `index.json`

The root's `index.json` does two jobs: it lists the top level of the book in reading order, and it
holds the tree's own settings.

```json
{
  "settings": {
    "defaultAutoClaim": "disabled",
    "defaultTeamReward": false,
    "suppressAllAutoclaiming": false,
    "detectionDelay": 20,
    "bookTitle": "The Orrery Ledger",
    "bookIcon": "minecraft:spyglass"
  },
  "entries": [
    { "group": "first_light" },
    { "chapter": "a_lone_chapter" },
    { "file": "stuck_in_the_past.json" }
  ]
}
```

Each entry names exactly one of a `group` folder, a bare `chapter` that belongs to no group, or a
version-1 `file` at the root. The settings are the tree's defaults, each overridable closer to the
thing it affects:

| Setting | Default | What it does |
|---|---|---|
| `defaultAutoClaim` | `disabled` | What a reward with `auto: "default"` does — see [[tenet:authoring/rewards]] |
| `defaultTeamReward` | `false` | Whether a reward that does not say otherwise is one claim for the team |
| `suppressAllAutoclaiming` | `false` | Holds every automatic payout, whatever individual rewards say — an operator's switch for an event |
| `detectionDelay` | `20` | Minimum ticks between inventory checks: an item, item-tag, filter, fluid or energy task is re-read no more often than this, whatever its own interval says. Other task types keep their own cadence. |
| `clickCommandLevel` | `0` | What a canvas click's `run_command` runs as: `0` is the pressing player's own level, `2` is elevated enough for `say`, `give` and `summon` without opening op. Never above `2`, and never the presser's own level however high that is. File-only: no client draws it, so the Book panel has no row for it. |
| `defaultConsumeItems` | `false` | Whether item tasks take the items when neither the task nor its chapter says: the bottom rung of the consume ladder (task wins over chapter, chapter over this). |
| `defaultDisableRecipeMod` | `false` | Whether quests stay out of recipe viewers (JEI, REI, EMI) when they say nothing themselves: the fallback a quest's own `disableRecipeMod` defers to, and a quest may write `false` to opt out. |
| `showLockIcons` | `false` | Whether a locked quest wears its padlock on the canvas. No marks unless the file asks for them; a pack that wants the legacy mod's old always-drawn marks writes `true`. A quest opts out for itself with `hideLockIcon` (see [[tenet:authoring/quests]]); either silence wins. Synced to every client with the tree. |
| `hideExcludedQuests` | `false` | Whether quests shut out for good by an exclusive choice (a taken `exclusiveGroup`, or a reached `maxCompletableDependents` cap) vanish from the reader's book instead of drawing locked. Synced to every client with the tree. |
| `pauseGame` | `false` | Whether the book pauses the world in single player while it is open. Synced to every client with the tree. |
| `disableGui` | `false` | Whether the book refuses to open: every open path (command, key, button, book item, viewer) answers "The quest book is disabled in this pack" instead of a screen. Synced to every client with the tree. |
| `disableCanvasLod` | `false` | Whether the book's chapters draw without the zoom tiers unless a chapter says otherwise: icons, rings, titles and badges at any zoom, as the legacy mod drew them. A chapter opts back into tiers with an explicit `false` of its own. The migration tool writes `true`, so migrated packs read as their source did. Synced to every client with the tree; editable in game from the **Book** section like the name and icon. |
| `dropBookOnDeath` | `false` | Whether a dying player drops a quest book where they fell, so death cannot take the pack away with it. Server-side: nothing crosses the wire. |
| `gridScale` | `0.5` | The editor canvas's grid step, from the legacy mod's `grid_scale` (1/32 to 8). File-only for now: validated and stored, while the editor keeps its 8-unit step — see the field's note in the schema. |
| `lockMessage` | `""` | What a locked quest is called when the pack has a better word than "Locked": the author's own sentence, drawn on the quest card. Empty means the client's own word. Synced to every client with the tree. |
| `emergencyItemsCooldown` | `300` | How long a player waits between `/tenet emergency` grants, in seconds, 0–86400 (the legacy mod documents no unit; Tenet reads seconds, so `300` is five minutes). See [[tenet:commands]]. |
| `emergencyItems` | `[]` | What `/tenet emergency` hands out: item references with counts and components, exactly as a task writes them. Empty means the command answers that there is nothing to grant. See [[tenet:commands]]. |
| `bookTitle` | `""` | What the book calls itself, drawn top-left in its header; empty uses the client's own title. Translatable via `book.title` in `lang/` — see [[tenet:authoring/languages]] |
| `bookIcon` | `""` | The item id the book wears in its header; an id a client cannot resolve is drawn as a missing-item mark |
| `fallbackLocale` | `en_us` | The locale the tree's own strings are written in. Every other locale is merged over it, so a player whose language has no file still reads this one — see [[tenet:authoring/languages]] |

The last two are the book's identity, synced to every client with the tree. They can be set by hand
here or in game from the **Book** section of the tools panel — an operator's tool. Unlike every other
edit there, a Book edit is not covered by Ctrl+Z: the settings block is not modelled by the editor's
history, and the write goes straight to this file.

An absent `index.json` is not an error: the tree is ordered the old way, folders by name, and that is
how every pack written before the file existed still loads.

**A broken one is read the same way, and says so.** A file that will not parse, or whose `entries` is
not a list, cannot say what the root is — so the tree is read as if the file were not there, with one
error naming it and one warning saying what was done instead: every folder at the root is a group, in
folder-name order, and anything this file would have left out is loaded instead. That rule has one cost
worth knowing, and the warning names it: a chapter that only `index.json` declares is a folder with no
`group.json`, so with the manifest unreadable there is nothing left that says it is a chapter, and it is
reported as a folder that is not one. An `entries` list that is *declared* is still believed, even when
an entry in it resolves to nothing — that is a per-entry fault, and falling back there would load
content the file deliberately left out.

> [!NOTE]
> Group folders come before their contents in reading order, but nothing above them declares an order
> unless `index.json` does — which is why **renaming a group folder can reorder the book** when the
> file is absent. With an `index.json`, the order is authored and a rename changes nothing but the id.

## Ids, aliases, and the cost of a rename

An id is lowercase letters, digits and underscores, at most 64 characters, and it may not begin with
`_` — the loader skips every name that does — nor end in `.deleted`, which is what a removed chapter is
renamed to. It appears in player progress files, which is the whole reason `aliases` exists: a renamed
quest, chapter or group lists its former ids, and nothing that referenced the old name breaks.

An id and an alias are **one namespace** per kind: a quest may not take an id another quest holds as an
alias, and a chapter may not take one another chapter holds. A clash is reported at load and the later
entry is not loaded at all, so the editor refuses it before the write. That is also why renaming
something back to a name it used to have is allowed while renaming it onto somebody else's old name is
not.

Lookups ignore letter case: an id, an alias, and any case mix of either resolve to the same quest,
chapter or group. That is for converted packs, whose ids may be uppercase hexadecimal — the case is
never what makes a reference fail. The files themselves stay lowercase: ids and `dependsOn` entries
must still be written that way, while aliases may use uppercase. An alias that differs from its own
id only in case is an error, because it names nothing the id does not already name; and two names
that differ only in case on two different objects are a clash, which fails fast naming both.

> [!CAUTION]
> **Renaming an id without an alias orphans the progress stored under the old one.** A player keeps
> the completion; the quest no longer recognises it, so it reads as incomplete and their rewards for
> it are gone. Add the old id to `aliases` in the same edit, and there is nothing to fix.

## Removing something, and getting it back

A delete in the editor is a **rename**, never an erase: a quest file becomes `<name>.json.deleted`, a
chapter's or a group's whole folder becomes `<name>.deleted` with everything inside it, and a reward
table becomes `<name>.json.deleted`. A chapter's, a group's and a table's copy is **numbered** when that
name is already set aside — `<name>.deleted`, then `<name>.deleted.2` — so a second removal cannot
overwrite the first copy. A quest file's copy is the plain `<name>.json.deleted`, because an undo finds
it by that exact name, and a second removal of that name is **refused** with a sentence naming the copy
in the way rather than numbered. The loader skips that suffix at every level, exactly as it skips the `_`
prefix, so a pack with removed content in it still loads.

**A removed copy's names are still taken.** A new quest is never minted under a name, an id or an alias
that a set-aside copy holds: the id would inherit the removed quest's stored progress (progress is keyed
by id, and an id no loaded quest claims is kept), and the file would sit beside a copy of its own name,
which makes it undeletable until that copy is moved. Bring the removed quest back with `Ctrl+Z` or
`/tenet restore` instead.

`Ctrl+Z` puts a chapter's own edit back — a deleted quest file, a deleted chapter or group, a table's
field edit — and it is one history step like any other edit. A **table** delete is the one removal that
is not on that trail: put it back with `/tenet restore`. The history is the **server's**, so it does not
survive a restart, and `/tenet reload` drops it deliberately — the copies on disk survive both, and
`/tenet removed` lists them with the name each would come back as. `/tenet restore <path>` takes one
back: the file or folder returns under its own name and is listed again in the manifest it belonged to.
Both take permission level 2, like `reload`; see [[tenet:commands]].

## Reward tables

Rolls live in `reward_tables/`, one file per table, named without the `.json` suffix. A reward names
one with `"table": "dungeon"`; the file's own format is on [[tenet:authoring/rewards]].

## Underscores are skipped, everywhere

Any file or folder whose name begins with `_` is skipped by the loader — the convention that lets a
schema folder, a note, or a draft sit beside the content without being loaded. It is a
[[declared-path]]: the mod reads its directories by name rather than scanning them, so a stray file
cannot be mistaken for content. `tools/quests/_schema/` in the repository is the worked example.

## Editor schemas

Each kind has a published JSON Schema, and a file that names it gets autocomplete and
error-underlining in any editor that speaks JSON Schema. Tenet ignores the key entirely; it is
there for the editor.

| File | `$schema` |
|---|---|
| `group.json` | `https://ellipog.dev/tenet/_schema/group.schema.json` |
| `chapter.json` | `https://ellipog.dev/tenet/_schema/chapter.schema.json` |
| a quest file | `https://ellipog.dev/tenet/_schema/quest.schema.json` |
| `index.json` | `https://ellipog.dev/tenet/_schema/index.schema.json` |
| `reward_tables/<name>.json` | `https://ellipog.dev/tenet/_schema/reward_table.schema.json` |

The same five files are checked into the repository under `tools/quests/_schema/`, so a copy works
offline. [[tenet:authoring/validation]] is what Tenet itself checks; the schema is what your
editor checks before a file ever reaches the game, and the two are held in step by a test.

## The older, single-file format

<details>
<summary>If you have a questline written as one flat document</summary>

Before the folder format, a questline was one `.json` document holding the whole tree as
`chapterGroups[]`. Those files are still read, forever: a `.json` file at the root of the quest folder
is recognised by *position*, not by a `version` field, so an old file needs no edit to keep working.

The two formats describe the same objects, so a flat file may carry the same fields a `group.json`
does. New packs should use the folder format; the flat one is kept so old ones do not have to be
rewritten. Its schema is published at
`https://ellipog.dev/tenet/_legacy/tenet-quests.schema.json`.

A chapter inside a flat file carries `elements` exactly as a `chapter.json` does — it is the same codec
and the same record, so a decoration written in either format behaves identically. The one difference is
where the array sits: on the chapter object inside `chapterGroups[]` rather than in a file of its own,
which is the difference between the two formats and nothing to do with elements.

</details>

## Reloading

`/tenet reload` re-reads the folder without a restart, and re-syncs every connected player's tree and
progress — a quest removed, an id renamed, or a dependency broken is visible immediately rather than
on the next reconnect. A file with an error is reported and skipped; the rest of the pack still loads.
[[tenet:authoring/validation]] is the page about what those reports say.
