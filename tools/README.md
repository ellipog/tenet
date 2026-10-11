# tenet/tools

Tools and content that belong to the Tenet **repository** rather than to the mod.

Nothing in this folder is compiled, and nothing in it ships in the jar.

## `quests/` — the worked examples

**One group, one chapter, every feature.** The examples used to be twelve questlines, one per
family of mechanisms, because that was the only way to show everything: a chapter wears one theme
and one progression mode, so demonstrating sixteen themes took sixteen chapters. They are one
orrery now — `first_light/first_steps` — because a reader learns more from a single canvas that
uses everything than from twelve that each use a part.

The reason they live here rather than in `src/main/resources` is worth restating: **Tenet ships
no quests.** It is a quest engine, and a mod that installs example chapters into every player's
config directory has made a decision that is not its to make. The first thing a pack author would
have to do is delete somebody else's content, and every file a mod ships is a file that has to
keep working forever against a format that is still moving. So the examples are authoring
documentation, and they arrive in a `config/tenet/quests` directory because somebody ran the
script below.

### The shape of it

The five quests at the western edge are the engine's own playthrough fixture:
`punch_a_tree`, `read_the_sign`, `make_a_table`, `stone_tools` and `the_underground` keep their
ids and their mechanics, because `QuestPlaythroughTest` walks them by name. They end at the hub,
and the hub opens the sky — seven arms, each a family of mechanisms:

| Arm | Carries |
|---|---|
| instruments | The item tasks: tags, matching, components, crafted-only, the consuming task and the task that declines the chapter default. |
| measures | Experience in points and levels, both fluids, a vanilla statistic, `sequentialTasks`, the custom task, and the turned gear. |
| the compass | Dimension, biome (id and tag), structure (id and tag), location boxes (with and without `ignoreDimension`), the curved line with anchors, and the size extremes. |
| the hunt | Kills by id, custom name, SNBT filter and entity tag; breaks by id, block tag and block state; all seven `observeType` values; the advancement task whole and by criterion. |
| the treasury | Eight of the twelve reward types — `advancement`, `all_table`, `choice`, `command`, `item`, `loot`, `random` and `xp` — every table mode (named, inline, nested, the empty band), the payout flags, and the command reward's placeholders. Two more live elsewhere: `stage` in the clockwork, `custom` in the veils. Two have no worked example yet: `currency` (needs an economy mod to pay through) and `toast` — both decode and are unit-tested, but no quest here uses one. |
| the clockwork | The OR-gate, the exclusive pair, the capped branch point, the repeatable round, stages granted, read and removed, a translation-key title, and all six condition types. |
| the veils | The whole hiding family, an alias with a dependency written against it, and the custom reward. |

Every file names the field it demonstrates and says why the field exists. The chapter also sets
all four of its inheritable defaults and then overrides them — `defaultConsumeItems: true`,
`defaultPrerequisiteMode: one_started`, `autoClaim: enabled`, and a `dependencyStyle` that lines
override axis by axis — because a default nothing ever overrides reads as a fact rather than as a
default.

### The coverage is enforced, twice

`QuestIndexTest` asserts the exhibition line by line: every task, reward and condition type, every
value of every line-art axis, every shape, a 16-pixel speck and a node of 200 pixels or more,
`iconScale` at 1.0 and at 0.5 or less, the auto-claim ladder, the payout flags, the visibility family.
Both ends of the *published* ranges are not shown and that is deliberate: the top is 512 pixels and an
icon of 0.25, and a node that size is a wall rather than an example — the codec's own note calls 4000
"certainly a typo for 40". And `gen_examples.py` — the script
that writes the files — runs the same checklist *before* it writes anything, so a mechanism cannot
be lost by editing the generator either.

That checklist is currently behind the registries, and the gap is worth stating rather than leaving
for the next reader to trip over: the `filter` and `energy` tasks and the `currency` and `toast`
rewards have registered types but no worked example, so neither the test nor the script names them
yet. Writing the four example quests is what promotes them onto both lists; extending either list
without the quests would fail the build rather than fix it.

Three assertions left the test in the collapse to one chapter, and they are worth naming because
their absence is a deliberate trade: a large **LINEAR** chapter, a dependency that crosses chapter
groups, and examples in two sizes. A chapter is linear or flexible and not both, and linear would
deadlock the exclusive pairs and OR-gates the same test insists on; a cross-group edge needs a
second group; one chapter is one size. LINEAR remains a documented mode with its own engine tests.

### Where to look for a particular field

Every field the format has is exercised somewhere, and this is the index from "I want to see one"
to the file that has it. All paths are under `first_light/first_steps/` unless they start with
`reward_tables/` or name a manifest.

| If you want to see… | read |
|---|---|
| an item task that counts itself, without consuming | `punch_a_tree.json`, `make_a_table.json` and `stone_tools.json` — the three onboarding quests with an item task; `read_the_sign.json` and `the_underground.json` are checkmark-only |
| a consuming task declared on the task itself | `the_carpenters_due.json` (`consumeItems: true`, `match: none`) |
| `components` + `match: fuzzy` | `the_nameplate.json` |
| `components` + `match: strict` | `the_warrant.json` |
| `onlyFromCrafting` | `the_apprentices_ink.json` |
| a count in the hundreds, where the progress bar is the point | `the_granary.json` (256) |
| an `item_tag` task | `the_counting_house.json` |
| an xp task in points, and one in whole levels | `the_tithe_of_experience.json` |
| a fluid task (water and lava) | `the_floodgate.json` |
| a stat task | `the_long_walk.json` |
| a custom task, and the warning it carries | `the_beacon_lit.json` |
| `sequentialTasks` | `the_crank.json` |
| `rotation` | `the_turned_gear.json` (45°) |
| a dimension task | `the_other_side.json` |
| a biome task by tag, and by id | `the_greenwood.json` |
| a location box, and one with `ignoreDimension` | `the_watch_hill.json` |
| a structure task by tag, and by id | `the_villages.json`, `the_ancient_city.json` |
| an advancement task whole, and by `criterion` | `the_first_iron.json`, `the_breeder.json` |
| a kill by entity id | `the_hunt.json` |
| a kill by `customName` | `the_pale_stag.json` |
| a kill by `nbtFilter` | `the_armoured_husk.json` |
| a kill by `entityTypeTag` | `the_undead_cull.json` |
| a break by block id | `the_quarry.json` |
| a break by `blockTag` | `the_timber_rights.json` |
| a break by `blockState` | `the_ripe_harvest.json` |
| observation: block and block tag | `the_lantern_watch.json` |
| observation: block state and block entity (with a long `timer` and `autoSubmitTicks`) | `the_weathervane.json` |
| observation: block entity type and entity type, plus an optional task | `the_reading_room.json` |
| observation: entity type tag | `the_grey_visitors.json` |
| a named table, an inline table, and a nested roll | `the_house_always_wins.json` (tables `dice`, `dregs`) |
| `loot` with an empty band, and `all_table` | `the_dust_draw.json` (table `toll`, `emptyWeight: 4`) |
| a `choice` reward | `the_patrons_gift.json` |
| `randomBonus`, `onlyOne`, and a renamed item reward | `the_bonus_bag.json` |
| a `team: true` reward | `the_guild_chest.json` |
| xp paid in whole levels | `the_home_road.json` |
| an advancement reward | `the_triumph.json` |
| a command reward with placeholders | `the_bells_rung.json` |
| a silent command, held back from Claim all | `the_quiet_word.json` |
| `permissionLevel` and `ignoreRewardBlocking` | `the_crowns_due.json` |
| a command that creates the objective a score condition reads | `the_first_account.json` |
| `auto: no_toast` and `auto: invisible` | `the_scarecrows_blessing.json` |
| an item condition, an item_tag condition and a stage condition on one task | `the_witness_list.json` |
| an advancement condition on a reward | `the_shepherds_tally.json` |
| a score condition and a party-size condition on one reward | `the_festival.json` |
| an OR-gate (`minRequired`) | `the_open_road.json` (two of three) |
| an exclusive pair | `patron_gold.json` and `patron_silver.json` |
| `one_completed`, and a split line with handles | `the_ledger_closes.json` |
| `maxCompletableDependents` | `the_crossroads.json`, with `road_north.json` and `road_south.json` |
| a repeatable quest with a cooldown | `the_daily_round.json` |
| a stage reward granted, and a translated title | `the_calling.json` |
| `requiresStage`, and the stage task that reads it | `the_oath.json` |
| a stage reward that clears a flag (`"remove": true`) | `the_graduation.json` |
| `invisible` + `invisibleUntilTasks` | `the_hidden_room.json` |
| `hideUntilDependenciesComplete` | `the_fenced_goods.json` |
| `hideUntilDependenciesVisible`, recursive | `the_far_star.json` and `the_farther_star.json` |
| `hideDependencyLines`, and a custom reward | `the_earth.json` |
| `hideTextUntilComplete` | `the_inscription.json` |
| `hideDetailsUntilStartable` | `the_glazier.json` |
| an alias, and a dependency written against one | `the_stall_with_no_name.json` declares it; `the_glazier.json` depends on it |
| a chapter alias and a group alias | `chapter.json` (`the_engine_room`), `group.json` (`the_first_light`) |
| a group collapsed by default | `group.json` |
| a chapter theme with a `themePatch` | `chapter.json` (default palette, two token colours, radius, motion, easing) |
| the smallest node there is, `shape: none`, icon corner to corner | `the_speck.json` (16px, `iconScale: 1.0`) |
| the largest node, icon sitting small inside it | `the_sun.json` (256px, `iconScale: 0.35`) |
| a picture made of positions | the whole chapter — a hub with seven arms and a comet tail entering from the west |

And the line-art matrix — every value of every axis a line has, with the chapter's default and its
overrides as the index:

| If you want to see… | read |
|---|---|
| a chapter's `dependencyStyle` default | `chapter.json` (orthogonal, chevron, target, solid, thin) |
| `form: orthogonal` / `chamfered` / `straight` / `curved` | the chapter default (`chapter.json`, orthogonal); `the_carpenters_due.json` and `the_crank.json` (chamfered); `the_ancient_city.json` (straight); `the_engine.json`, `the_lantern_watch.json`, `the_mirror.json` and `the_undead_cull.json` (curved) |
| `dash: solid` / `dashed` / `dotted` / `dash_dot` / `double` / `hazard` | the chapter default; `the_tithe_of_experience.json`, `the_greenwood.json`, `the_apprentices_ink.json`, `the_villages.json`, `the_armoured_husk.json` |
| `weight: thin` / `thick` / `bold` / `conduit` | the chapter default; `the_armoured_husk.json`, `the_first_account.json`, `the_engine.json` |
| `arrowHead: chevron` / `triangle` / `dot` / `diamond` / `none` | the chapter default; `the_floodgate.json`, `the_lantern_watch.json`, `the_house_always_wins.json`, `the_graduation.json` |
| `arrowPlace: target` / `both` / `mid` / `stream` | the chapter default; `the_pale_stag.json`, `the_reading_room.json`, `the_engine.json` |
| `arrowDensity: low` / `medium` / `high` | `the_sun.json`, `the_engine.json`, `the_grey_visitors.json` |
| `bend` at both extremes | `the_mirror.json` (0.8) and `the_lantern_watch.json` (−0.8) |
| `fromAnchor` / `toAnchor` | `the_mirror.json` |
| `fromHandle` / `toHandle` | `the_ledger_closes.json` |
| `hideDependencyLines` as a design decision | `the_earth.json` |

### The one thing the examples deliberately do not have

There is no `index.json` in `quests/`, and with one chapter the reason is sharper than ever: an
`index.json` is a **complete manifest of the root** — every folder and file at the top level that
it does not list is an *error* — and the playthrough test seeds its own fixture chapters beside
the examples. A shipped index would turn every fixture into a load error. Tree-wide settings would
also reach the examples' claim assertions, and a `defaultAutoClaim` set here would pay out the
onboarding quests before their claims could be tested. The fields are documented on the
[[tenet:authoring/quest-files|quest-files page]] and in `_schema/index.schema.json`.

The legacy `arrows` axis (`none`/`one`/`both`/`many`) is likewise deliberately unused in fresh
content: it is still read, for old files, but new lines write the three current axes.

### The layout, which is the format

A questline is a **folder tree**, not one file per questline. There is no `01_stone_age.json` any
more and there is no `version` field to write:

```
quests/
  first_light/                a group. Its folder name IS its id.
    group.json                title, and the chapter folders in order
    first_steps/              a chapter. Folder name is its id too.
      chapter.json            title, and the quest file names in order
      punch_a_tree.json       one whole quest per file
      the_engine.json
      ...
  reward_tables/              named reward tables. Reserved: not a quest, read by its own pass.
    dice.json
  _schema/                    editor schemas. Never loaded, never copied.
```

Three rules worth knowing before you edit anything here:

  * **A folder's name is its id**, and its manifest has to agree. Disagreement is an error naming
    both sides, because the folder name is what every path in the tree is built from.
  * **Order is declared twice, and by different things.** Group folders come in *folder-name* order
    — which means renaming a group folder can reorder the book, including which chapter you land on.
    Chapters inside a group and quests inside a chapter come from their manifest's list, so that
    order is yours and it is load-bearing: a `LINEAR` chapter's progression **is** its quest list.
  * **A file nobody lists is an error, not silence — and so is a name with nothing behind it.** A
    chapter folder the group's `chapters` list does not name will never load, and the loader says so
    rather than skipping it quietly. The other direction is the same message about the other side: a
    name in a `chapters` or `quests` list that resolves to no folder or file is an error against *that
    name*, so deleting a quest file by hand costs that quest and leaves the chapter's other quests
    loading. A manifest that cannot be read at all is the only thing that costs its own subtree.

`_schema/` holds a JSON Schema per kind — group, chapter, quest, index and reward table — so your
editor autocompletes every file a pack is made of. The same five are published under
`https://ellipog.dev/tenet/_schema/`; a file's `$schema` names the URL, and a local copy works
offline. It is skipped by the `_` prefix rule everywhere — by the loader, by the seeding script, and
by the tests — which is the same convention the deliberately-broken fixtures in a test combo use.

`QuestFilesTest` and `QuestFormatMigrationTest` cover all three rules, including that a chapter
written both ways — one flat file versus a folder tree — produces a **byte-identical** quest tree.

### The orrery, and why it is built the way it is

The chapter is one composition rather than a pile of files, and the composition is the teaching
device. The hub is the largest node on the canvas and the only quest every arm begins at; each arm
is a family of mechanisms, its head hanging off the hub and its members fanning outward; the
onboarding five enter from the west as a comet tail. Cross-arm dependencies are rare and
deliberate — the open road takes two of three roads from three different arms — so the long lines
across the sky are the ones worth following.

Read it in this order: the five onboarding quests, then the hub, then any arm. `/tenet complete
<quest>` walks a node at a time, which is how a canvas this size is meant to be seen.

**Geometry is generated, content is authored.** The arms, slots and offsets live in
`gen_examples.py` as a table; the polar layout pass turns them into snapped canvas coordinates;
the descriptions and fields are written out in the same file, one entry per quest. The rule for
editing it is the same as it always was: change the catalogue, regenerate, and let the coverage
check tell you what you broke.

## `gen_examples.py` — how the chapter is written

```cmd
python tenet/tools/gen_examples.py
```

The script owns `first_light/` and `reward_tables/` and rewrites both from scratch on every run,
so a quest removed from the catalogue does not linger as a file the chapter manifest no longer
lists. Other group folders it only *reports*, because an author's own content beside the examples
is not this script's to delete.

Before writing anything it runs the coverage check: every task type, every reward type and table
mode, every condition, every shape, every value of every line-art axis, the size and icon-scale
extremes, the auto-claim ladder, the visibility family, the inheritance pair for each chapter
default. A mechanism that goes missing fails the run with its name, rather than quietly leaving
the exhibition. `QuestIndexTest` asserts the same list on the written files, so the two halves
agree by construction.

It lives here rather than in `.utils/` for the same reason `seed_quests.py` does: it belongs to
the Tenet repository, so a clone gets the *how* as well as the *what*.

## `seed_quests.py` — how they get into a config directory

```cmd
python tenet/tools/seed_quests.py --workspace           this repo's combos and both profiles
python tenet/tools/seed_quests.py <dir> [<dir>...]      any config/tenet/quests directories
```

Created if missing. **Never overwritten unless you pass `--force`** — that directory is where an
author's own questline lives, so refreshing it automatically would destroy work. `--force` names
every file it replaces, so a replace is never something that happened while you were not looking.

**The one file it edits without being asked is a root `index.json`, and it only ever appends.**
With a manifest present the loader reads *only what it lists* and reports every other entry as an
error that says so — *"it will never load"* — so seeding into a root that has one used to fill a
directory with quests the game never read. So each group or chapter folder just copied is added to
`entries` if it is missing, after everything already there: the manifest is also the book's reading
order, and an author's order is theirs. `reward_tables/` and `lang/` are never added, because the
loader reserves those two names and does not want them listed. A target with no manifest is left
alone entirely — absence is not a fault, and the tree is then read the old way, which loads every
folder there is.

`--dry-run` says what would happen and changes nothing.

**Replacing one exhibition with another is `--reset`.** It deletes everything in each target's quest
directory — every file and folder, named one by one — and then copies the examples in fresh. The one
thing it keeps is anything `_`-prefixed, because that prefix means "deliberately out of the loader's
way" everywhere in this project: a test combo's `_example_typos.json` and friends are fixtures a
person renames to run, not stale content. `--reset` is the flag for the collapse from twelve
questlines to one: without it the old groups sit beside the new chapter and load as extra content,
and `--force` alone would keep them as stale folders. It is never implied by any other flag.

If you seeded an earlier version of the examples, this is the one-time command:

```cmd
python tenet/tools/seed_quests.py --workspace --reset
```

Files beginning with `_` in `quests/` are skipped, and deliberately: they are the broken fixtures the
loader ignores by that prefix, and copying one would install a questline whose only purpose is to
fail.

A newly-added example is **created** rather than kept, so a new file reaches every profile and combo
on the next `--workspace` run without `--force`. `--force` is only needed to *replace* files that
were already there.

## The one thing it deletes, and why

Converting an install from the old flat format to folders means the same questline exists **twice** —
once as `01_stone_age.json`, once as `first_light/`. That is worse than a stale copy. It is two
questlines, and every group, chapter and quest in the pair is reported as a duplicate id, naming a
file *inside a folder* as the second claimant. That message reads as a broken conversion rather than
as a file that needs deleting.

So on every run the script removes a root-level `*.json` **if and only if** every chapter-group id it
declares is also provided by a group folder that was just copied. Everything else at that level is
**kept and named, with the reason**:

```
  - 01_stone_age.json  removed: a version-1 file whose group(s) 'getting_started' are now in folders beside it
  ! mine.json  KEPT -- it declares 'my_own_group', which no copied group folder provides.
      A version-1 file beside the folders would load as a second copy of whatever it
      declares, so every shared id is reported as a duplicate. Delete it, or move it
      somewhere the loader does not read.
```

Matching on declared **ids** rather than on file names is the point. A name list would go stale, and
it would delete `01_stone_age.json` even if you had rewritten it into something of your own. A file
that will not parse is never deleted. No folder is ever deleted — `--reset` is the only thing that
removes a folder, and it only does so when asked. `_`-prefixed files are not touched.

If you have a `mine.json` of your own, move it into a folder of its own or rename it with a leading
`_`, and the warning goes away.

## Why these are not in `.utils/`

Every other script for this project lives in `.utils/`, which is gitignored workspace tooling. The
two here are different in kind: they belong to the Tenet repository, so that a clone of Tenet
alone gets the examples, the script that installs them, and the script that regenerates them.
`.utils/Seed Example Quests.cmd` is the launcher that runs `seed_quests.py` with `--workspace`, and
`.utils/preview.py` renders the chapter's canvas without launching the game.
