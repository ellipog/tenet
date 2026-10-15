# Quests

A [[quest]] is one file, and this page is the fields it can carry. Three groups: how it introduces
itself, where it sits on the [[canvas]], and what it does (dependencies, progression, visibility and
gates). The [[task]]s and [[reward]]s it holds have pages of their own.

```json
{
  "id": "punch_a_tree",
  "title": "Punch a Tree",
  "subtitle": "Eight logs, and it counts them itself",
  "description": ["Everything worth having starts with a tree."],
  "icon": { "item": "minecraft:oak_log" },
  "x": 0,
  "y": 0,
  "tasks": [
    { "type": "tenet:item", "item": "minecraft:oak_log", "count": 8 }
  ],
  "rewards": [
    { "type": "tenet:item", "item": "minecraft:wooden_axe" }
  ]
}
```

## Identity

| Field | Meaning |
|---|---|
| `id` | Lowercase letters, digits and underscores. The name progress is stored under (see the rename note on [[tenet:authoring/quest-files]]). |
| `title` | What the player sees, under the node and at the top of its card. |
| `subtitle` | One line beneath the title. Optional. |
| `description` | Paragraphs, read as markdown (see below). |
| `icon` | An item, a texture file, or an entity (see below). Defaults to paper. |
| `aliases` | Former ids, so a rename does not orphan progress. |
| `tags` | Words this quest answers to in lookups by tag (see below). Each tag is lowercase letters, digits and underscores, the same rule an id follows. |
| `guidePage` | The guide book page this quest belongs to. A reference the quest card shows as `Guide: <page>`; Tenet has no guide integration, so nothing reads it further. Empty means absent. |

Chapters have ids, titles, subtitles and icons too, and the same rules apply to them. A chapter's
`subtitle` is the one line under its name, drawn as the second line of its sidebar row's hover, and
it is translated with `chapter.<id>.subtitle` (see [[tenet:authoring/languages]]). A chapter
**group** has an id, a title, a description and an icon, and no subtitle: a group is a heading over
chapters, and a second line of prose above them belongs to the chapter it is about.

### The default title

A new quest is minted titled `New quest`, and the file keeps saying so until its author writes a
better one. The book does not wait: while the file still carries exactly that literal, the quest
reads as its first task's subject instead. A quest asking for blue dye reads **Blue Dye** with no
manual rename. Thirteen of the eighteen task kinds lend a name: items, item tags, blocks and
block tags, mobs, fluids, dimensions, biomes, structures, advancements, stats, observations, a
filter matching exactly one item, and a task carrying the author's own caption, which outranks
its requirement (a kill task captioned "Cull the horde" reads that, not "Zombie"). Advancements
read their real display title where the client knows it. The five that lend nothing do so for a
reason: energy and xp have no noun to lend, a location is coordinates, and stage and custom ids
are pack spelling rather than player words. A quest with nothing to lend keeps the default.

Adoption is display-only and in the player's own language. The file is untouched, and any edit
to the title wins permanently, as does a `quest.<id>.title` translation (see
[[tenet:authoring/languages]]). Retyping `New quest` verbatim re-enables it.

### Tags

Quests, chapters, chapter groups, tasks and rewards each carry an optional `tags` list: words the
object answers to in lookups by tag. A lookup of `#village` (in a command, in a script, or in an
`open_quest` click) resolves to the first object of the asked kind carrying that tag, in
declaration order. Dependency edges keep their id-or-alias charset (a `#` there is refused), so
tags name one thing to open or check rather than gating anything.

Each tag is lowercase letters, digits and underscores (`^[a-z0-9_]{1,64}$`), the same rule an id
follows; anything else is an [error](validation.md). Quests, chapters, tasks and rewards edit tags
as a comma list in the editor; a group's tags are file-only, like its aliases. There is no
quest-book text search over tags yet: they are stored, validated, resolved for `#tag` lookups,
and search integration is future work. the legacy mod calls this `tags`, present on every object.
Its one use in the reference pack is `["village"]` on a quest.

### Icons

An icon is one of four arms, named by its key:

```json
{ "item": "minecraft:oak_log", "count": 1 }
{ "texture": "my_pack:textures/gui/emblem.png" }
{ "sprite": "occultism:block/chalk_glyph/0" }
{ "entity": "minecraft:creeper" }
```

An item is drawn as the item, with its count and data components. A texture is a file's path
(`textures/` and `.png` included), drawn stretched into the icon's box; a path nothing holds draws
the missing texture. A sprite is an atlas region id, drawn from the atlas into the icon's box;
a region nothing holds draws the missing mark. An entity is drawn as its spawn egg where one exists;
an entity with no egg draws the missing mark naming it. The item arm is the shape every file written
before the union uses, so old files read unchanged. Naming two arms warns, naming the winner and the ignored
arms; only the winner draws.

Texture and sprite icons are drawn on quest nodes (and the links that mirror them), the quest
card, task and reward rows, sidebar rows, the book's header, and toasts. The in-game picture
picker sets any arm: its Item tab picks items, File picks pack textures with thumbnails, Sprite
takes an atlas id with a live preview of exactly what the node will draw, and Entity searches
mobs. Setting one arm clears the others.

## Writing a description

A description is an array of strings, one per paragraph, and each is read as **markdown** when a
player opens the quest:

- `**bold**`, `*italic*` (or `_italic_`), and `` `code` ``.
- `[text](https://example.com)` for a link: underlined, and it opens in the browser when clicked.
  Only `http` and `https` open; anything else is refused with a word in the status bar.
- A line starting with `#`..`######` is a heading, and one starting with `- `, `* ` or `+ ` is a
  bullet. Headings come in four sizes: `#` is twice the body text, then `##` at 1.75×, `###` at 1.5×
  and `####` at 1.25×. `#####` and `######` use the smallest of those, because the card's font has one
  size and the body's is its floor.
- A backslash escapes the punctuation that would otherwise be markup (a backslash, `*`, `_`, a
  backtick, a bracket, a parenthesis, `#`, `-` or `+`), so `\*` is a literal star and `\a` stays
  `\a`.

A line break is a line break: markdown here does not join lines into flowing paragraphs, because these
files are written a line per paragraph and joining them would re-wrap prose an author had already
wrapped. The file keeps the raw markdown. The editor shows it as written, and only the reader's card
renders it.

## Rich-text codes

A description converted from the legacy mod keeps its text tokens, and the reader's card reads them.
The converter passes pack text through verbatim, and the tokens are a render-time reading:

- `&` and `§` colour and style codes (`&a` green, `&l` bold, `&o` italic, `&n` underline, `&r`
  reset), `&#RRGGBB` hex, and `&z` rainbow. Struck-through (`&m`) arrives underlined and obfuscated
  (`&k`) arrives plain: the seam has no run for either, and those are the closest survivals.
- `{@pagebreak}` starts a new paragraph run. The card scrolls rather than paging, so a boundary is
  a break and not a pager.
- `{image:path width:N height:N align:center}` draws the picture inline in the prose, fitted to the
  column and centred unless told otherwise. Files stretch to fill, like chapter pictures; the same
  `.png`-means-file rule applies, and a file the pack does not ship draws nothing rather than
  breaking the card.
- `{open_url:...}` opens through the same http/https-only rule a markdown link uses, and
  `{substitute:key}` resolves against the locale overlay.
- A whole paragraph that is a raw JSON text component reads its clickable runs: `open_url`
  opens through the same http/https-only rule, and the legacy mod's `change_page` (whose value the legacy mod's own
  reader treats as a quest to open rather than a book page) opens that quest by id or alias. Any
  other click action reads as words with nowhere to press, and `docs:` addresses read the same
  way: there is no guide shelf here. Colours and emphasis inherit down the component the way
  vanilla reads them.
- `\&` is a literal ampersand, which is also the rule for Tenet-native packs: `&` starts a colour
  code, legacy-style, so `R&B` reads as `R` and a literal one is written `\&`.

Titles, subtitles, chapter titles and element words read the words without the ink: those surfaces
draw one ink, so `&aChapter 2` reads as `Chapter 2` in white. Descriptions wear the colours,
because the card draws run by run. The quest card's header and the canvas labels wear them too:
a title there draws its runs, truncated like plain text, because colours are widthless and styles
are dropped where widths are measured. Everywhere else a title is one ink: sidebar rows, toasts,
captions and the HUD read the stripped words, and never the raw codes.

A chapter's and a group's description are parsed and shown to nobody: neither reaches the reader's
card (a chapter's does not cross to the client at all, and the chapter panel shows only a paragraph
count), so the reading above is a quest's description. The editor always shows text that a reader
would not yet see, which is the point of the `hideTextUntilComplete` flag below.

## The node on the canvas

| Field | Default | Meaning |
|---|---|---|
| `x`, `y` | `0` | Position on the canvas, in canvas pixels. The shipped chapters sit on a 32-pixel grid; the editor snaps to 8. |
| `shape` | `rounded` | The outline: `rounded`, `square`, `circle`, `diamond`, `hexagon`, `octagon`, `pentagon`, `gear`, `heart`, `tome`, `star`, or `none`. |
| `size` | `48` | The node's width and height, 16–512 pixels. |
| `rotation` | `0` | Degrees clockwise, 0–359. Any shape can be turned, and the turned outline is fitted to the node with one uniform scale, so nothing is cut off at the node's edge and nothing is stretched. A full turn is written as `0`, because it is the same shape. |
| `iconScale` | `0.75` | How much of the node the icon fills, 0.25–1.0; the outline caps it, so a shape with less room draws the largest item it can hold. `1.0` is corner to corner; the default leaves a margin so the shape reads. |
| `showTitle` | `false` | Draw the quest's name under its node. Off by default, because a canvas of fifty names is a wall of text, and the name is on hover either way. |
| `minWidth` | `0` | Minimum width of the quest's detail panel, 0–3000. `0` is unset: the chapter's `defaultMinWidth` decides, and then the panel kind's default. |

A shape of `none` draws no panel at all: the node is its icon, and the whole square is clickable. Every
other outline is hit-tested by its own pixels, so a click lands on exactly what is drawn.

A quest's coordinates are its own; a [[chapter]] is never positioned. Its [[frame]] on the canvas is
computed from the quests inside it plus padding, so moving a quest moves the frame with it.

## Dependencies

`dependsOn` is a list of quest ids (or their aliases) that must be satisfied before this one is
open. They may live in other files, and a reference that resolves to nothing is an
[[tenet:authoring/validation|error rather than a quiet lock]]. A [[chapter]] whose `progressionMode` is
`linear` supplies the chain itself, so its quests need no `dependsOn` at all.

| Field | Default | Meaning |
|---|---|---|
| `dependsOn` | none | Quest ids or aliases this quest waits on. |
| `prerequisiteMode` | the chapter's | What the dependencies have to reach: `all_completed`, `one_completed`, `all_started`, `one_started`. This is the [[progression-mode]] axis, how a set of prerequisites is treated. |
| `minRequired` | `0` | How many dependencies must be satisfied, 0–64, replacing the mode's own count. This is how "any three of these five" is written. May not exceed the number of dependencies. |
| `dependencyLines` | none | Per-line overrides for the canvas, keyed by the dependency's id. |

`minRequired` replaces the *count*, not the *bar*: the mode still decides what each one must reach, so
`one_started` with `minRequired: 2` means "any two of these, started".

### Optional quests

A quest with `"optional": true` is marked a side quest, a branch the player may do, drawn
with its dependency lines. The flag is a marker, not a gate: an optional quest gates the
quests that depend on it exactly like any other quest (that is the legacy mod's `optional`,
whose only other effect is excusing the quest from chapter completion). `minRequired`
counts every dependency, and the card's "2 of 3 met" counts the same denominator the
engine enforces.

### Early progress

A quest with `"flexibleProgress": true` lets its tasks be worked on before its dependencies are
met; completion still waits for them. A quest that says nothing follows its chapter's
`defaultFlexibleProgress`: either true makes the quest flexible, and there is no opt-out, so a
migration tool writes the resolved value onto each quest and leaves the chapter default off.

What that means in play: progress on a flexible quest accumulates while its gate is shut. The
card shows the real counts, and the dependency lines show what is missing, and an already-maxed
quest completes on the first tick after its gate opens. A submit or a kill that maxes the last
task does not finish the gate's other quests in the same call; the next tick does that, once per
team rather than once per node.

While the gate is shut the quest still draws gated (padlock, dimmed node, `REQUIRES x of y`),
although its state stays measurable rather than locked. An UNLOCKED reading is what made
whole flexible chapters look open; the legacy mod shows such quests locked, and the padlock is what
tells a player measuring from a player who may claim.

Do not confuse this with the chapter's `progressionMode`: that one chains a chapter's quest list
in order (`flexible` there means "order means nothing", `linear` means the list is the road), and
this one is about whether *dependency edges* block task progress. Two different axes.

### A chapter's own dependencies

A chapter can wait on other chapters, the same way a quest waits on quests. Until its rule is met,
**every quest inside it is locked**: it cannot be started, finished or claimed, whatever its own
`dependsOn` says. By default the chapter is listed, dimmed, with what it is waiting for on hover.

```json
{
  "id": "the_deep",
  "title": "The Deep",
  "dependsOn": ["first_steps"],
  "prerequisiteMode": "all_completed",
  "completesWhen": ["the_festival"],
  "hideUntilDependenciesComplete": true
}
```

| Field | Default | Meaning |
|---|---|---|
| `dependsOn` | none | Chapter ids or aliases this chapter waits on, in any group. A reference that resolves to nothing is an [error](validation.md), because the chapter could then never be opened. |
| `prerequisiteMode` | `all_completed` | The rule over *this chapter's* `dependsOn`, the same four values a quest uses. **Not** `defaultPrerequisiteMode`, which is the mode a quest in this chapter inherits. |
| `minRequired` | `0` | How many of `dependsOn` must be satisfied, replacing the mode's own count: "any two of these three chapters". May not exceed the list. |
| `completesWhen` | none | The quests, in any chapter, that finish this one. The chapter reports **completed** once every one of them is; a repeatable quest counts from its first completion. Empty means the chapter never reports completed. |
| `autofocus` | none | The quest, by id or alias **in this chapter**, the canvas centres on when the chapter is selected. Absent centres on the chapter's bounding box. A name that resolves to nothing, or to a quest on another canvas, is an error. |
| `hideUntilDependenciesComplete` | `false` | Leave the chapter out of the book entirely until its gate is met. Off, it is listed dimmed. Authors always see it, or the flag could not be authored. |
| `alwaysInvisible` | `false` | Leave the chapter out of every reader's book, whatever its gate says. Beside the row above because they are the pair an author mixes up: that one withholds the row until the gate is met, this one withholds it always. The gate itself is unaffected (the chapter still opens, completes and gates its quests), its progress reads 100%, and authors still see the row. the legacy mod calls this `always_invisible`. |
| `disableCanvasLod` | defer | Whether this chapter's canvas draws without the zoom tiers: icons, rings, titles and badges at any zoom. Absent defers to the book's own flag (see [[tenet:authoring/quest-files]]); present `true` draws everything always, present `false` keeps the tiers even under a tierless book. the legacy mod has no tiers, so migrated packs arrive tierless through the book half. |
| `tags` | none | Words this chapter answers to in lookups by tag (see below). Each tag is lowercase letters, digits and underscores, the same rule an id follows. |
| `defaultHideUntilDependenciesComplete` | `false` | What **the quests in this chapter** do about their own prerequisites unless a quest says otherwise: on, a quest here is hidden until its own rule is met. A quest writes `false` to opt out. |
| `defaultHideUntilDependenciesVisible` | `false` | The same default for the reveal that waits on a prerequisite being *visible*. |

Note the pair of similar names, because they are the ones an author mixes up:
`hideUntilDependenciesComplete` withholds **this chapter's row** until its own gate is met, while
`defaultHideUntilDependencies*` decide what its **quests** do about their own dependencies. The editor
labels them "Hide until open" and "Hide quests until done" for that reason.

A chapter with nothing to show is also left out of a reader's book on its own: if every quest in it is
hidden (by these defaults, by the flags below, or because the chapter holds no quests), the sidebar
row goes with them, and returns the moment one quest is visible. That is what makes a chapter of fifty
unrevealed quests behave like a chapter that has not started yet rather than a door into an empty
canvas. An author in edit mode sees it either way.

A chapter's state is **locked → open → started → completed**:

- **locked:** its `dependsOn` rule is unmet.
- **open:** the rule is met, and nothing inside it is done.
- **started:** any quest in it has progress or is finished.
- **completed:** every quest in `completesWhen` is done.

A chapter with no `completesWhen` therefore never reports *completed*, which is a real state: a
chapter only ever waited on as "started" needs no completion to declare. A chapter that another
chapter waits on as completed **must** declare one, and the loader reports the pair that does not,
because that combination is a chapter that can never be opened.

The two graphs are checked separately: a **quest** cycle is refused, and so is a **chapter** cycle,
including the one that is easy to miss (chapter A waits on chapter B, and B is finished by a quest
inside A). Both are reported with the chain, because a loop like that is otherwise a chapter that
simply never opens.

Hiding is only how a gated chapter is *presented*. The gate itself is enforced by the server whatever
`hideUntilDependenciesComplete` says, so a command or a script cannot complete a quest in a chapter
that is not open.

### Drawing the lines

The chapter's `dependencyStyle` sets how its lines look; `dependencyLines` overrides one dependency's
line, axis by axis. Only the axes a line names are overridden, while the rest fall back to the chapter,
then to the built-ins (`chamfered`, a chevron at the target, `solid`, `thin`).

| Axis | Values | Meaning |
|---|---|---|
| `form` | `orthogonal`, `chamfered`, `straight`, `curved` | The route's shape: a three-segment step, the same step with 45° corner cuts (a circuit trace, and the built-in default), a direct line, or a smooth bow. `curved` reads `bend`. |
| `arrowHead` | `chevron`, `triangle`, `dot`, `diamond`, `none` | The glyph an arrowhead is drawn as. `chevron` is the built-in; `none` is a blunt line end. |
| `arrowPlace` | `target`, `both`, `mid`, `stream` | Where the heads sit: one at the dependent end (the built-in), one at each end, one in the middle of the route, or a repeating run. |
| `arrowDensity` | `low`, `medium`, `high` | How far apart a `stream` repeats: 64, 32 or 16 pixels. |
| `dash` | `solid`, `dashed`, `dotted`, `dash_dot`, `double`, `hazard` | The line's pattern. `double` is two hairlines either side of the route and ignores `weight`; `hazard` is the run plus diagonal hatch marks. |
| `weight` | `thin`, `thick`, `bold`, `conduit` | How many pixels wide: 1, 2, 3, or a 6-pixel conduit with dark borders around a lighter core. |
| `bend` | −0.8–0.8 | How far a curved line bows, as a fraction of the chord between its ends. The built-ins carry `0.2`, so a chapter that sets only `form: curved` still bows slightly. |
| `fromAnchor` / `toAnchor` | degrees | The angle a line leaves or meets a node at, 0 east and growing clockwise. Per line only, because which rim a line meets is a fact about its two ends. |
| `fromHandle` / `toHandle` | `[along, across]` | A split line's control points. They are written together, because one alone is a split with half a shape. |

Older files that write the single `arrows` axis (`none`, `one`, `both`, `many`) are still read:
`none` is a blunt end, `one` a chevron at the target, `both` one at each end, and `many` a stream at
its old 24-pixel spacing. The editor writes the three current axes, and a chapter's default may set
all of them, while only anchors and split handles are per-line.

For a whole chapter:

```json
{
  "dependencyStyle": { "form": "curved", "arrowHead": "triangle", "arrowPlace": "stream", "dash": "solid" }
}
```

## Progression and repeats

| Field | Default | Meaning |
|---|---|---|
| `repeatable` | `false` | Completable more than once. `timesCompleted` survives each completion, and something depending on it stays satisfied. |
| `repeatCooldownTicks` | `0` | Ticks to wait between completions. 2400 is two minutes. |
| `sequentialTasks` | `false` | Tasks must be handed in in order: the second cannot be handed in until the first is. Either this or the chapter's `defaultSequentialTasks` makes the quest sequential. |
| `flexibleProgress` | `false` | Tasks may be worked on before the dependencies are met; completion still waits for them. Either this or the chapter's `defaultFlexibleProgress` makes the quest flexible. |
| `autoClaim` | the chapter's | Whether this quest's rewards are handed over the moment it completes: `disabled`, `enabled`, `no_toast` or `invisible`. Overrides the chapter's `autoClaim`, and is overridden by a reward's own `auto`. See [[tenet:authoring/rewards]]. |
| `exclusiveGroup` | none | Quests sharing a name are mutually exclusive: completing one locks the others, permanently. A [[exclusive-group]] is a choice of paths. Scoped to the chapter. |
| `maxCompletableDependents` | `0` | At most this many of the quests depending on this one may complete, 0–64. `0` is no cap. A dependent already completed stays completed. |

A repeatable quest's cooldown runs from the moment its **last unclaimed reward is claimed**,
not from the completion: the round ends when its payout is fully collected, and the count moves
then too. A repeatable with nothing to collect (no rewards, or everything automatic) resets at
completion instead, because no claim will ever arrive to end it.

The chapter's own `progressionMode` decides whether the chapter is walked one quest at a time or
unlocked as it becomes available:

- `flexible`: every quest whose prerequisites are met is open. The default.
- `linear`: the order of the chapter's `quests` list *is* the progression: each unlocks when the one
  above it completes.

A quest in a linear chapter that also declares `dependsOn` must satisfy both its own edges and the
quest above it. That is a real combination (a linear spine with one quest that also needs something
from another chapter), so nothing warns about it.

## Visibility

Nine flags, each hiding one different thing. All of them are presentation: a hidden quest still
loads, still counts for progress, and is always shown to the editor.

| Field | Default | Hides |
|---|---|---|
| `invisible` | `false` | The whole quest, until it is completed. |
| `invisibleUntilTasks` | `0` | With `invisible` set: also unhide once this many tasks have any progress, 0–64. The easter-egg case, a quest nobody can see until they stumble onto part of it. Without `invisible` it does nothing. |
| `hideUntilDependenciesComplete` | the chapter's | Until the prerequisite *rule* is satisfied, the same rule the card's "2 of 3 met" counts, so `minRequired` and the started-based modes are honoured. Three states: leave it out and the chapter's `defaultHideUntilDependenciesComplete` decides, `true` forces it on, and `false` opts out of a chapter that hides its quests by default. |
| `hideUntilDependenciesVisible` | the chapter's | Until at least one prerequisite is itself visible. Recursive, so a chain reveals itself one link at a time from its first visible end. The same three states as the row above. A quest with **no** prerequisites is visible: an empty rule is met. |
| `hideDependencyLines` | `false` | The lines arriving at this quest. The quest itself is unaffected, and quests that depend on it still draw their lines to it. |
| `hideDependentLines` | `false` | The lines leaving this quest for its dependants. The outgoing half of the row above: a quest that fans out to twenty dependants draws twenty lines across the chapter, and the author may want the quest without the clutter. Either silence wins. |
| `hideTextUntilComplete` | `false` | The description, until the quest is completed, for a quest whose text would give away what it asks for. |
| `hideDetailsUntilStartable` | `false` | Task and reward details, until the quest can be started. The prerequisites stay: they are what tells the reader how to unlock it. |
| `disableRecipeMod` | the file's | The quest in recipe viewers (JEI, REI, EMI): with it set, no viewer lists this quest. Three states like the two chapter-defaulted rows above: leave it out and the file's `defaultDisableRecipeMod` decides (see [[tenet:authoring/quest-files]]), `true` hides it, and `false` opts out of a file that hides its quests by default. the legacy mod calls this `disable_recipe_mod`. |
| `hideLockIcon` | `false` | The quest's own padlock: with it set, this locked quest wears no padlock on the canvas. The quest's half of the file's `showLockIcons` (see [[tenet:authoring/quest-files]]), where either silence wins, and the node still reads locked through its edge and wash. the legacy mod calls this `hide_lock_icon`. |

## Recipe viewers

A recipe viewer with Tenet's pages shows the questline beside the recipes: EMI renders full quest
pages, JEI and REI share a fallback over the same item lookup, and where more than one viewer is
installed the first in that order wins outright, while the others register nothing. All three are soft
dependencies: a client without any of them loses nothing.

Traffic goes both ways. Pressing a quest in the viewer opens the book on that quest, while a press for a
quest the tree no longer holds opens nothing rather than an empty card. Pressing a task row in the
book opens the viewer's recipes for what the row names, tags included where the viewer supports them;
with no viewer installed the press is inert. Reward icons in a viewer never open recipes: a reward is
something to collect, and once collected it wears its claimed mark instead of a lookup. In the book,
a claimed reward likewise offers no lookup; an unclaimed one still links, so a locked reward can be
identified. Which quests a viewer may list is the
`disableRecipeMod` flag above: a hidden quest never reaches the viewer, because the server resolves
it onto the wire and the client holds no file record to second-guess.

## Announcements

`disableToast` quiets a completion. It lives on the quest, on every task, and on every reward
(the legacy mod's `disable_toast` on every quest object), and either silence wins over the auto-claim
ladder: a quest that asked for no toast gets none, whatever its `auto` says.

| Where | Quiets |
|---|---|
| quest | The quest's completion notice, and every task row arriving with it. |
| task | That task's row only; its siblings still speak. |
| reward | Recorded on the model, the wire and the editor for the reward-level notice. No such notice exists yet (only quest and task notices do), so it travels as data for the notice that will. A reward in a quieted quest stays quiet through the quest's own flag. |

## Held payouts

`/tenet rewards block` holds a team's payouts, and a held reward stays outstanding until it is
released. Two flags exempt a payout from the hold (the legacy mod's `ignore_reward_blocking` on both
quest objects):

| Where | Exempts |
|---|---|
| quest (`ignoreRewardBlocking`) | Every reward on the quest. |
| reward (`ignoreRewardBlocking`) | Just itself. |

Either flag wins: a held team still pays a quest that asked to be exempt, and a held quest still
pays the one reward on it that asked. See [[tenet:authoring/rewards]].

## Stages

`requiresStage` is a namespaced id the player must have for the quest to be open to them:

```json
{ "requiresStage": "my_pack:inducted" }
```

It is the one gate here that is **per player rather than per team**: a quest gated on a stage is open
to a player who has it and locked to one who does not, even in the same party. Stages are granted by
[[tenet:authoring/rewards|stage rewards]] and removed by them, read by stage tasks, and manipulated from
scripts (see [[tenet:authoring/kubejs]]).

`requiresStageTeam: true` reads the gate from the team's stages instead: one member's induction opens
the quest for everybody. The team half is granted by a stage reward with `teamStage: true`, asked about
by a stage task with `teamStage: true`, and handed out by hand with `/tenet stage add-team`. the legacy mod
calls the flag `team_stage`.

> [!WARNING]
> Nothing validates that a stage exists, because a stage exists by being granted: there is no list to
> check against. A typo is therefore not reported, because it shows up as a quest nobody can ever open.

A stage is the gate for **one player**, and it is the wrong tool for "this chapter follows that one":
repeating it on every quest in the chapter is worse than one `dependsOn` on the chapter, which is
checked and cannot be missed on a quest somebody adds later. Reach for a stage when the gate really is
per player ("this is the tutorial, and only the person who did it may see this"). For a chapter
gate use the chapter's own fields above.

## What a quest inherits

These live on the chapter manifest and apply to its quests unless a quest overrides them:

| Field | Default | Meaning |
|---|---|---|
| `defaultPrerequisiteMode` | `all_completed` | The `prerequisiteMode` a quest uses unless it says otherwise. |
| `defaultConsumeItems` | `false` | Whether item tasks in this chapter take the items unless the task says otherwise. An author sets it once for a whole trade chapter. When neither the task nor the chapter says, the file's `defaultConsumeItems` in `index.json` decides. |
| `defaultFlexibleProgress` | `false` | Whether this chapter's quests let their tasks be worked on before their dependencies are met unless a quest says otherwise. Either the quest's own `flexibleProgress` or this makes its tasks flexible. |
| `defaultSequentialTasks` | `false` | Whether this chapter's quests require their tasks in order unless a quest says otherwise. Either the quest's own `sequentialTasks` or this makes its tasks sequential. |
| `defaultHideUntilDependenciesComplete` | `false` | Whether the chapter's quests are hidden until their own prerequisite rule is met, the reveal flag below, set once for a whole chapter. A quest writes `false` to opt out. |
| `defaultHideUntilDependenciesVisible` | `false` | The same, for the reveal that waits on a prerequisite being visible. |
| `defaultMinWidth` | `0` | What the chapter's quests use for their detail-panel width unless a quest says otherwise: a quest's own `minWidth` wins, and `0` (unset) means the panel kind decides. |
| `autoClaim` | the pack's | Whether this chapter's quests hand their rewards over on completion: `disabled`, `enabled`, `no_toast`, `invisible`, or `default` for the pack setting. The row that spares players fifty early-game claim clicks. See [[tenet:authoring/rewards]]. |
| `dependencyStyle` | built-ins | The drawing defaults for the chapter's lines. |
| `theme` | none | A palette the chapter asks to be drawn in. A client concept: the catalogue lives on the client, so the name is a plain string here, and a client that cannot resolve it says so. `vanilla` ships with Tenet and draws vanilla's own sprites (stone buttons, bevel panels, the vanilla tooltip box). |
| `themePatch` | none | Token-level overrides for `theme`: individual colours, a corner radius, a canvas background and a chrome style (`"chrome": "vanilla"` opts one chapter's content into the sprites), applied over the named theme (or over the player's own when no theme is named). The Chapter tab's appearance section writes this; a file can also carry it by hand: `{ "colours": { "raised": "#FF24242E" }, "cornerRadius": 4 }`. |

A chapter's palette reaches everything that belongs to it (the canvas, the quest cards and their buttons and fields, the picker and rename cards, and the tooltips describing them), while the book's own chrome (sidebar, header, menus, tools panel, notices) keeps the player's theme. `themePatch` is how a chapter sets "raised surfaces at `#24242E`, radius 4" without shipping a whole theme file.
