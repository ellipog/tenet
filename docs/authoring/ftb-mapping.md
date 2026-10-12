# FTB Quests mapping

Every legacy 1.21.1 field below already has a Tenet home: the middle column is what the
migration tool writes, with no Tenet change needed. This page is the tool's contract for the
already-mapped half of the format. The fields that still need Tenet work live in the migration
plan, not here, and anything on this page that stops decoding breaks [[tenet:authoring/tasks]],
[[tenet:authoring/rewards]] and [[tenet:authoring/quests]] along with it.

The rule throughout: the legacy mod's snake_case becomes Tenet's camelCase, and a legacy field that is absent
keeps Tenet's default rather than gaining a new one. The test that pins this page is
`LegacyMappingTest`: one row per mapping, each decoded through the real codec, so a field that
quietly becomes required fails there first.

## Tasks that map 1:1

| legacy task | Tenet type | Notes for the tool |
|---|---|---|
| `advancement` + `criterion` | `tenet:advancement` (`advancement`, `criterion`) | An empty `criterion` (`""`, which is all 54 ATM10 uses) means the whole advancement: emit the task **without** `criterion` rather than with an empty string. |
| `observation` (`to_observe`, `timer`, `observe_type`) | `tenet:observation` (`toObserve`, `timer`, `observeType`) | Names carry over: `block`, `block_tag`, `block_entity`, `entity_type` and friends are the same words. NBT appended to `toObserve` (e.g. `minecraft:sheep{Color: 4b}`) is a fuzzy subset match on both sides. |
| `stat` (`stat`, `value`) | `tenet:stat` (`stat`, `value`) | Same two fields, same meaning. |
| `forge_energy` (`value`, `max_input`) | `tenet:energy` (`value`, `maxInput`) | Same pair under Tenet's name, counted from carried items' energy storage rather than piped into a task screen. An absent `max_input` means unlimited here (the legacy mod's absent limit accepts nothing; the migrated ATM10 quest relies on this reading). |
| `kill` (`entity`, `entity_type_tag`, `value`, `custom_name`, `nbt_filter`) | `tenet:kill` (`entity`, `entityTypeTag`, `value`, `customName`, `nbtFilter`) | The tag takes precedence over the id on both sides; NBT is a fuzzy subset match on both sides. A kill naming one mob wears its spawn egg on rows, pins and (by adoption) its quest's node, resolved at sync so files stay clean; tags, mob-less kills and eggless entities keep the sword, and an author picture still wins. |
| `location` (box + `ignore_dim`) | `tenet:location` (box + `ignoreDimension`) | Same box, same flag. |
| `item` + `match_components` | `tenet:item` + `match` | The three words are identical: `none`, `fuzzy`, `strict`, with `strict` the default on both sides. A 1.20.1 pack's `match_nbt` becomes `match`. One exception: item components holding integers JSON cannot hold (past ±2^53−1, e.g. creative energy cells) force `fuzzy`, because strict is unachievable without the digits: a strict template could never equal a carried stack. The whole component goes (a pruned husk would compare unequal just the same) and the report names it, including when it overrides an explicit `strict`. |
| `item` data components | verbatim, with `1b`/`0b` flags read as booleans | Components pass through structurally, never interpreted. Integers past ±2^53−1 drop their whole component (see the `match` row); the one ATM10 case is a creative cube's infinite-energy marker, where fuzzy decides exactly the same gates. |
| `item` + `only_from_crafting` | `tenet:item` + `onlyFromCrafting` | Same flag, with one documented difference: Tenet counts lifetime crafted statistics rather than watching the crafting event, so handing the stack away does not un-count it. |
| `item` + `consume_items` | `tenet:item` + `consumeItems` | Absent defers to the chapter's `defaultConsumeItems` on both sides (see below for the file-level default). |
| filter stacks (`ftbfiltersystem:smart_filter` + `ftbfiltersystem:filter`) | `tenet:filter` (`filter`, `count`, `consumeItems`, `manualOnly`) | The expression passes through verbatim (`or(item(a)item(b))`, `item_tag(x)`, `mod/and/or/not` and all), because Tenet reads the same vocabulary. A single `item_tag(x)` may instead become a `tenet:item_tag`. Anything the expression answers that a quest cannot (`component` and friends) becomes a `tenet:custom` placeholder keyed `migrator:filter/<task>` instead of vanishing: a dropped task would silently finish quests whose only task it was, while the placeholder reads zero progress and waits for a handler. Filter matches cycle live on every surface (rows, nodes, adoption, HUD), so mapped filters carry no still icon. |
| `item`/`fluid` + `task_screen_only` | `tenet:item`/`tenet:item_tag`/`tenet:fluid` + `manualOnly` | Same flag under Tenet's name: the tick never counts from the inventory, only a submit press does. the legacy mod fills such tasks by piping into a task screen block; Tenet has no such block, so the piped path is a documented loss and this flag keeps the manual half. |
| `checkmark` | `tenet:checkmark` | Same handshake under Tenet's name: the row wears the empty box while todo and the checked box once done (`tenet:textures/gui/checkmark_unchecked.png`, `.../checkmark_checked.png`), which is what the legacy mod's `checkmark_task_inactive`/`checkmark_task_active` theme icons say. Pressing the row hands it in, like the legacy mod; the footer Submit stays for discoverability. An author picture on the task wins over the box on every surface, like every other override. |
| `gamestage` (`stage`, `team_stage`) | `tenet:stage` (`stage`, `teamStage`) | Same pair under Tenet's name: the stage to have, read from the team's stages when `team_stage` is set rather than the player's own. |

## Rewards that map 1:1

| legacy reward | Tenet field | Notes for the tool |
|---|---|---|
| `auto` (`enabled`, `invisible`, `no_toast`, `disabled`) | `auto` on every reward | The four words plus the default are identical, including the file-level default (`default_autoclaim_rewards` → `defaultAutoClaim`). `invisible` and `no_toast` are the same silence under two names on both sides. |
| `team_reward` (+ file `default_reward_team`) | `team` (+ `defaultTeamReward`) | Absent defers to the file default on both sides. |
| `exclude_from_claim_all` | `excludeFromClaimAll` on every reward | Same flag. the legacy mod forbids changing it on loot, random and choice rewards; keep that behaviour in the tool. |
| `ignore_reward_blocking` | `ignoreRewardBlocking` on every reward | Same flag. |
| `ignore_reward_blocking` (quest) | `ignoreRewardBlocking` on the quest | Same flag: either the quest's or a reward's own exempts that reward from a held payout. |
| `disable_toast` (reward) | `disableToast` on every reward | Same flag, recorded on the model, the wire and the editor. Quiets the reward-level notice (toast description, command feedback); a reward in a quieted quest stays quiet through the quest's own flag. |
| `title` (task, from lang `task.<id>.title`) | `title` on every task and reward | The row's own words instead of the type's sentence. A checkmark reads its button from here, exactly as before. The key is unchanged, so old files read the same way. A checkmark with no title draws its bare button beside the state box (the legacy mod shows no sentence either), and the tool reports each one as author work. |
| `icon` (task/reward override) | `icon` on every task and reward | An item, a texture file, an atlas region, or an entity drawn as its spawn egg. An entity with no egg keeps the type's picture on a row (quest and chapter nodes name the missing entity instead). |
| `command` + `silent` + `feedback_message` | `tenet:command` (`command`, `permissionLevel`, `silent`, `feedbackMessage`) | Same flag; placeholders (`{p}`, `{x}`/`{y}`/`{z}`, `{quest}`, `{chapter}`, `{team}`) carry over. `feedback_message` is the success line shown when the command runs; absent shows nothing extra. |
| `item` + `only_one` | `tenet:item` + `onlyOne` | Same flag: checked by item type, ignoring components, on both sides. Table entries keep `weight` and `randomBonus` as written. |
| `toast` (`description`) | `tenet:toast` (`description`) | The message shown when collected, as literal text or a translation key with fallback. |
| `currency` (`amount`) | `tenet:currency` (`amount`) | The amount passes through; the coin belongs to the installed economy on both sides. |
| `gamestage` (`stage`, `remove`, `team_stage`) | `tenet:stage` (`stage`, `remove`, `teamStage`) | Same triple under Tenet's name: the stage to set, taken away when `remove` is set, granted to the team when `team_stage` is set rather than the claiming player. |

## Quest, chapter and file rows that map 1:1

| legacy field | Tenet field | Notes for the tool |
|---|---|---|
| `require_sequential_tasks` (quest) | `sequentialTasks` (quest) | Same flag. Either the quest's own flag or the chapter's `defaultSequentialTasks` makes its tasks sequential. |
| `require_sequential_tasks` (chapter) | `defaultSequentialTasks` (chapter) | Same flag, as the chapter default for its quests. |
| `hide_quest_until_deps_complete` | `hideUntilDependenciesComplete` | Absent defers to the chapter default on both sides; `false` forces the quest visible. |
| `hide_quest_until_deps_visible` | `hideUntilDependenciesVisible` | Same tristate behaviour. |
| `hide_dependency_lines` | `hideDependencyLines` | Incoming lines only; the outgoing side is `hideDependentLines` below. |
| `hide_dependent_lines` | `hideDependentLines` | The outgoing half: lines leaving this quest for its dependants. Either silence wins. |
| `disable_toast` (quest, task) | `disableToast` (quest, every task) | Same flag. A quieted quest announces no completion and no task rows; a quieted task skips only its own row. Either silence wins over the auto-claim ladder. |
| `disable_recipe_mod` (quest) | `disableRecipeMod` (quest) | Same flag, as a tristate: absent defers to the file default below, `false` opts out of it. A hidden quest never reaches a viewer, because the server resolves it onto the wire and the client holds no file record. |
| `min_width` (quest) | `minWidth` (quest) | Same field, 0–3000, 0 unset. A quest's own value wins over the chapter's `defaultMinWidth`. |
| chapter `default_min_width` | `defaultMinWidth` (chapter) | The chapter default for its quests' panel width. |
| chapter `autofocus_id` | `autofocus` (chapter) | The quest, by id or alias in this chapter, the canvas centres on when selected. Absent centres on the chapter's bounding box. |
| quest/chapter/group/book icon (`custom_icon` texture, `entity_face`) | `icon` (`texture`, `sprite`, `entity` arms) | A texture file draws stretched into the icon's box; an atlas region draws from the atlas; an entity draws as its spawn egg where one exists, else the missing mark naming it. File or atlas is the legacy mod's own rule: a `.png`/`.jpg` suffix is a file, anything else a region. The migrator applies it verbatim, so `occultism:block/chalk_glyph/0` lands on `sprite`. Both readers draw from the blocks atlas, so regions resolve identically; the two `ftblibrary:icons/*` strings no atlas stitches draw the missing mark on both sides, which is parity rather than loss. The item arm is the shape every old file uses, so it reads unchanged. |
| table `use_title` | `useTitle` (reward table) | A reward row that rolls the table wears the table's title rather than the generic roll sentence. |
| table `hide_tooltip` | `hideTooltip` (reward table) | A reward row that rolls the table draws no hover, unless the row is locked, because a shut gate is still explained. |
| `hide_text_until_complete` | `hideTextUntilComplete` | Same flag. |
| `hide_details_until_startable` | `hideDetailsUntilStartable` | Same flag. |
| `invisible` (+ `invisible_until_tasks`) | `invisible` (+ `invisibleUntilTasks`) | Same pair, same counting rule. |
| chapter `default_hide_dependency_lines` etc. | `defaultHideUntilDependenciesComplete`, `defaultHideUntilDependenciesVisible` | The chapter defaults for the two tristate flags above. |
| chapter `default_consume_items` | `defaultConsumeItems` | The chapter default for item tasks. |
| file `default_consume_items` | `defaultConsumeItems` (index settings) | The bottom rung of the consume ladder: a task wins over its chapter, the chapter over this. |
| file `default_quest_disable_jei` | `defaultDisableRecipeMod` (index settings) | The fallback a quest's own `disableRecipeMod` defers to: with it on, every quest that says nothing stays out of the viewers. |
| file `detection_delay` | `detectionDelay` | Same field: the minimum ticks between inventory checks, flooring item, item-tag, filter, fluid and energy tasks. |
| quest `requiresStage` + `team_stage` | `requiresStage` + `requiresStageTeam` (quest) | The gate reads the team's stages when the team flag is set rather than the player's own. |
| chapter `subtitle` (+ lang `chapter.<id>.chapter_subtitle`, a single-element array) | `subtitle` (chapter) | The one line under the chapter's name, drawn as the second line of its sidebar row's hover. The lang array's element becomes the subtitle's literal text; a pack that wants it translated instead writes `chapter.<id>.subtitle` in Tenet's own `lang/` folder. |
| file `title` (+ lang `file.<id>.title`) | `bookTitle` (index settings) | What the book calls itself, drawn top-left in its header. The lang value becomes the title's literal text; a pack that wants it translated instead writes `book.title` in Tenet's own `lang/` folder. |
| quest `hide_lock_icon` | `hideLockIcon` (quest) | The quest's own padlock: with it set, this locked quest wears no padlock on the canvas. The quest's half of the file's `showLockIcons` below; either silence wins. Tenet draws the padlock itself (a fills-drawn badge on the locked node; the legacy mod's overlay has no sprite here), and the node still reads locked through its edge and wash. |
| chapter `always_invisible` | `alwaysInvisible` (chapter) | Same flag: the chapter is withheld from every reader whatever its gate says, while the gate itself still opens, completes and gates its quests. Its progress reads 100%, and the editor still lists it. No uses in the reference pack. |
| `tags` (quest, chapter, task, reward, group) | `tags` | Same list on every object. Each tag is lowercase letters, digits and underscores, the same rule an id follows. A `#tag` lookup resolves to the first object of the asked kind carrying it, in commands, scripts and `open_quest` clicks. The reference pack's one use is `["village"]` on a quest. |
| quest `guide_page` | `guidePage` (quest) | Same string. Tenet has no guide integration: the quest card shows it as a `Guide: <page>` reference and nothing reads it further. Empty means absent. No uses in the reference pack. |
| file `show_lock_icons` | `showLockIcons` (index settings) | Whether a locked quest wears its padlock. Tenet hides when absent; the legacy mod draws when absent (`!contains \|\| getBoolean`), so the tool writes an explicit `true` unless the legacy file says `false`. That explicit key is what preserves an old pack's marks. |
| _no legacy field_ | `disableCanvasLod` (index settings) | Whether the book's chapters draw without the zoom tiers. the legacy mod has no tiers (it draws every icon at every zoom), so the tool writes an explicit `true`. That key is what preserves an old pack's always-drawn canvas. A chapter opts back into tiers with its own explicit `false`. |
| file `hide_excluded_quests` | `hideExcludedQuests` (index settings) | Whether quests shut out for good vanish from the reader's book. Tenet's exclusion is a taken `exclusiveGroup` or a reached `maxCompletableDependents` cap resolving to LOCKED, and the server marks those quests on the progress wire, so the client cannot tell "excluded" from "not yet" by the state alone. |
| file `pause_game` | `pauseGame` (index settings) | Whether the book pauses the world in single player: `isPauseScreen` answers this rather than a constant. |
| file `disable_gui` | `disableGui` (index settings) | The book refuses to open: every open path answers "The quest book is disabled in this pack" instead of a screen. the legacy mod's own semantics are unclear; Tenet reads it as a pack-level switch. |
| file `drop_book_on_death` | `dropBookOnDeath` (index settings) | A dying player drops a quest book where they fell. |
| file `grid_scale` | `gridScale` (index settings) | The editor canvas's grid step, 1/32 to 8, default 0.5. File-only for now: validated and stored, while the editor keeps its 8-unit step. |
| file `lock_message` | `lockMessage` (index settings) | What a locked quest is called on its card when the pack has a better word than "Locked". Empty means the client's own word. |
| file `emergency_items_cooldown` | `emergencyItemsCooldown` (index settings) | How long a player waits between `/tenet emergency` grants, in seconds (the legacy mod documents no unit; Tenet reads seconds, so `300` is five minutes). |
| file `emergency_items` | `emergencyItems` (index settings) | What `/tenet emergency` hands out: item references with counts and components. Empty means the command answers that there is nothing to grant. There is no book button. The shelf is asked for by name, with an in-memory per-player cooldown. |
| file `drop_loot_crates`, `loot_crate_no_drop` | _tool-reported_ | No Tenet home: loot crates are out of scope (T29), so the tool reports these as manual work rather than emitting them. |
| file `verify_on_load` | _tool-reported_ | No Tenet home: a loader flag with no quest-file meaning here, so the tool reports it as manual work. |

## Dependencies

Quest gates carry over exactly: same targets, same order, same rule:

| legacy field | Tenet field | Notes for the tool |
|---|---|---|
| `dependencies` | `dependsOn` | One entry per legacy edge, remapped `HEX` → slug, order preserved, including edges into other chapters and groups. A gate on a task or reward id lifts onto the quest owning it (Tenet gates quests on quests only) and is reported naming both sides; where the owning quest is already named beside it, the pair collapses onto the one edge. A gate lifting onto the quest itself drops as a self-loop. A gate on a chapter id retargets onto that chapter's synthesised gate quest (`gate_<chapter>`, plus a `gate_<chapter>_started` arm where a referrer asks `*_started`): a taskless quest waiting on exactly the chapter's milestone set, so it completes exactly when the chapter does, and the edge keeps its single place in `prerequisiteMode`/`minRequired` counting. Each synthesis is reported naming chapter, gate and referrers; a cycle through a gate fails naming the chain. Duplicates collapse with a note; a dangling id or the quest's own id fails the run rather than emitting a file Tenet would refuse. |
| `dependency_requirement` | `prerequisiteMode` | The same four words (`all_completed`, `one_completed`, `all_started`, `one_started`); the default stays absent. |
| `min_required_dependencies` | `minRequired` | Same count ("any N of these"); written only when above 0, and refused when above the dependency count, which Tenet also refuses. |
| `max_completable_dependents` | `maxCompletableDependents` | Same cap; written only when above 0. |
| `optional` | `optional` | Same marker: a side quest. It still gates its dependants exactly like any other quest (the legacy mod's optional only excuses chapter completion, never a gate), so the 102 reference-pack edges onto optional quests stay real gates. |
| `progression_mode` | `flexibleProgress` | Quest → chapter → file; `flexible` anywhere effective sets the flag. |
| `can_repeat` (+ chapter `default_repeatable_quest`) | `repeatable` | Quest → chapter default → `false`. |
| `repeat_cooldown` (seconds) | `repeatCooldownTicks` (×20) | Same wait under Tenet's unit; a cooldown without `repeatable` is reported and dropped. |
| `dep_control_pts` | _tool-reported_ | No Tenet home: Tenet draws its own routes, so hand-bent lines are reported, never emitted. |

## Commands the tool rewrites

Pack command rewards and click actions naming the legacy mod commands are rewritten to these Tenet
spellings, with quest ids remapped to the new lowercase Tenet ids:

| the legacy mod command | Tenet command |
|---|---|
| `/ftbquests block_rewards` | `/tenet rewards block` (and `unblock` for the release) |
| `/ftbquests complete <quest> [player]` | `/tenet complete <quest> [player]`, with `with-dependencies` where the legacy spelling finished the chain, or `/tenet complete-all [player]` for the whole book |
| `/ftbquests reset <quest> [player]` | `/tenet reset <quest> [player]`, with `with-dependencies` where the legacy spelling cleared the chain, or `/tenet reset-all [player]` for the whole book |
| `/ftbquests open_book [quest]` | `/tenet open_book [quest]` |
| `/ftbquests reload` | `/tenet reload` |
| `/ftbteams teamstage …` | `/tenet stage add-team` / `remove-team` (named by a member's player id) |

`editing_mode`, `locked` and the rest have no Tenet home: the tool reports them as manual work
rather than emitting them.

## Inlined at import (no Tenet field)

Visual presets and chapter appearance defaults are resolved by the tool into explicit quest
values. Tenet reads the result, never the preset:

| legacy field | What the tool emits | Notes |
|---|---|---|
| quest/chapter/file `preset` + `presets` map (`goal`, `info`, `normal`) | quest `shape` + `size` | A preset names shape and size only. The tool resolves quest → chapter/file default → `presets[name]` and writes the shape and size onto each quest. Tenet keeps no preset map. |
| chapter `default_quest_shape`, `default_quest_size` | quest `shape` + `size` | Same inlining: quest → chapter → file `default_quest_shape` → `circle` / `1.0`. An empty shape (`""`) and a zero size mean unset. |
| chapter `default_repeatable_quest` | quest `repeatable` | Quest `can_repeat` → chapter default → `false`. |
| chapter `default_hide_dependency_lines` | quest `hideDependencyLines` | Quest → chapter → `false`. |
| file `default_quest_shape` | quest `shape` | Bottom of the shape chain above. |
| file `progression_mode` | quest `flexibleProgress` | Quest tristate → chapter → file; `flexible` sets the flag. |

## Deliberately unmapped here

Ghost validation lives Tenet-side rather than on this page: a taskless quest with no dependant
warns on load, a taskless quest with one loads quietly, and the tool names each ghost in its report
rather than suppressing the check. The file settings that have no Tenet home
(`drop_loot_crates`, `loot_crate_no_drop` (loot crates are out of scope) and `verify_on_load` (a loader flag))
are rows above marked _tool-reported_ rather than mappings.

Third-party task types (`custom` entries and the quest-unanswerable filter functions)
become `tenet:custom` placeholders keyed by the task's own id, so the quest waits for a
handler instead of completing free. Third-party reward types (`custom` entries,
`eternalcurrencies:currency`, `questsadditions:*`, `quest_loot`, and the crate types a
`loot_crate` sub-object would have named) are **not** mapped to Tenet types: Tenet loads
each such node as an unknown placeholder and warns, so the quest still plays around it
(a missing reward only withholds a payout, never an unlock), and the tool reports every
use as manual work. Install the mod that provides it, or replace the node. A legacy `loot`
reward itself converts as a weighted table; only the physical crate, its opener and its
drops are manual work.
