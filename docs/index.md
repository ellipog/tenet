# Tenet documentation

Tenet is a questing engine for Minecraft 1.21.1, on Fabric and NeoForge. A pack author describes a
[[quest]] line in JSON, Tenet draws it on a pannable [[canvas]], and the server decides what counts
as done. The client renders what it is told and nothing more, so no player can complete a quest by
editing their own game.

## What it needs

| | |
|---|---|
| Minecraft | 1.21.1, on Fabric or NeoForge |
| Fabric | Fabric Loader 0.16.9+, with Fabric API 0.109.0+1.21.1 |
| NeoForge | 21.1.252+ |
| Armature | 1.0.0+, required on both loaders ([[armature:index]] is the library Tenet is built on) |
| KubeJS | optional, NeoForge only, 2101.7.2+ (see [[tenet:authoring/kubejs]]) |

Tenet ships no questlines of its own. It is an engine, and installing it does not put somebody else's
content in your config; the worked questline lives in the repository under `tools/quests/`, as
authoring material for a pack's first chapter.

## Where to start

| Page | What it is |
|---|---|
| [[tenet:hud]] | The HUD: pinning quests to it, the notices it shows, and the editor that places them |
| [[tenet:authoring/quest-files]] | The folder format: chapter groups, chapters, one quest per file, and the order things load in |
| [[tenet:authoring/quests]] | Every field on a quest, from its place on the canvas to the flags that hide it |
| [[tenet:authoring/tasks]] | The eighteen task types, and what each one counts |
| [[tenet:authoring/rewards]] | The twelve reward types, and the reward tables behind four of them |
| [[tenet:authoring/conditions]] | The six condition types, gating a task or a reward on an item, a tag, a score, an advancement, a stage or a party's size |
| [[tenet:authoring/templates]] | The template shelf: saving a task, reward or condition setup and applying it to any quest as a copy |
| [[tenet:authoring/languages]] | Shipping a questline in more than one language: `lang/<locale>.json`, which file a player gets, and the keys |
| [[tenet:authoring/validation]] | What the validator checks, and what a mistake reads like |
| [[tenet:authoring/ftb-mapping]] | Which legacy fields already have a Tenet home: the migration tool's contract for the mapped half |
| [[tenet:commands]] | The `/tenet` commands, for players and operators |
| [[tenet:authoring/kubejs]] | Driving a questline from a KubeJS script |

## For addon mods

Quests name their types by id, and the set is open: a mod registers its own task, reward and
condition types with `TaskTypes.register`, `RewardTypes.register` and `ConditionTypes.register`,
each pairing an id with the codec that reads it. A `tenet:custom` task or reward needs no new type
at all: it names a handler id, and the handler is registered by a mod or a script (see
[[tenet:authoring/kubejs]]). The lifecycle (quests starting and completing, tasks satisfied,
rewards handed over, stages granted and taken, canvas presses) is published as `TenetEvents` for
both. An economy mod pays `tenet:currency` rewards by setting a `CurrencyReward.CurrencyProvider`
with `CurrencyReward.CurrencyProviders.setActive`.

## From files to a running questline

<Steps>
  <Step title="Write the quest files">
    A pack is folders and JSON under `config/tenet/quests/`. The shape of that tree is the subject of
    [[tenet:authoring/quest-files]]; the short version is one folder per [[chapter]], one file per quest.
  </Step>
  <Step title="Load it">
    ```cmd
    /tenet reload
    ```

    Reads the folder without a restart and reports what loaded, what it refused, and why.
  </Step>
  <Step title="Look at it">
    Press `B`, or use the Quest Book item. The book draws exactly what the server loaded, and a task's
    progress ticks as you play.
  </Step>
</Steps>

## The book, briefly

A player opens the quest book from the **Quest Book** item or the `B` key. It is a pannable, zoomable
canvas of nodes: a quest's `x`/`y` is its place, its shape, size, rotation and icon are all authored,
and the lines between nodes are its dependencies. From a quest's card a player submits tasks, claims
[[reward]]s, and picks between a choice reward's entries. A player in a party sees the roster there
too, and the counts the party's mode combines.

An operator gets a band of five controls under the title row (`Author`, `Assets`, `✎ Edit`,
`Advanced` and `Preview`), and the header itself carries no authoring control. `Author` latches the author's dock, which
is where the Book and Chapter tabs live; `Assets` opens the pack's own files; `Edit` latches edit mode and
nothing else; and `Advanced` says how much of the editor to show. `Preview` shows the player's view:
the panel shrinks to the reader's card at the reader's size, hidden chapters and quests are hidden
again, editing is paused, and quest cards show what a player would see, while edit mode stays on
underneath, so leaving the preview resumes exactly where it was left. The way back out is the lit
`Preview` pill floating over the canvas's top-right corner, since the reader's card has no author
band to press. With Advanced off (the default), every menu
carries the essentials: a quest's shape, size, placement, what it needs and what it gives, the text on a
task and a reward, a chapter's name and quest list, and the book's own name and icon. With it on, every
field, section and menu the editor has is drawn as well, which is where the reward tables, the visibility
and progression rules, a chapter's line styles, the conditions and the theme editor live. That is the
in-game editor, and it writes the same files these pages describe: the server re-checks the permission and
validates before anything lands on disk, so no quest file is written by the client. The player's own look
and text size live in files the client owns.

An author playtesting a pack resets progress from the same book rather than the command line:
right-click a quest node for `Reset progress`, a chapter's sidebar row for `Reset chapter progress`,
and the sidebar's empty list for `Reset whole book progress`. All three need edit mode and
permission level 2, and all three clear the author's own team, parties included, since progress
belongs to the team. The wide two ask twice; the quest's is one press, because progress is
replayable and the playtesting loop is press, check the reveal, press again. Every press answers
in the book (`Progress cleared`, or `There was no progress to clear` when nothing was held),
so a quiet press is a press that found nothing, never one that went nowhere.

## Removing something, and getting it back

**Every control that takes something away asks twice.** The quest card's `Delete`, the node and
dependency menus, the chapter and group menus, an entry's `×`, the table browser's `×`, the Assets
panel's `×` and the `Delete` key all arm on the first press and act on the second, and the ones that
remove a whole chapter or group say what goes with it (`Really delete? (70 quests, 3 dependent)`) before
they do. Nothing is erased when they act: a quest file is renamed to `<name>.json.deleted`, a chapter's
or a group's whole folder becomes `<name>.deleted`, and a reward table becomes `<name>.json.deleted`. The
loader skips that suffix everywhere, so a pack with removed content in it still loads.

**Ctrl+Z takes it back**, and the restore is one history step per chapter, the same key as any other
edit. A **table** delete is the exception: it is not on that trail, and the pair below is how it comes
back. The history is the *server's*, though, so it does not survive a restart, and `/tenet reload` drops
it on purpose; after sixty further edits in that chapter the step has fallen off the end. The copy on
disk survives all three, which is what makes the pair below the way back:

| Command | What it does |
|---|---|
| `/tenet removed` | Everything set aside under the quest folder, with the name each would come back as. |
| `/tenet restore <path>` | Puts one back, exactly as it was, and re-lists it where it belongs. |

Neither is offered in the book, because a tombstone is skipped by every walk the editor draws from: the
tree does not know it is there, and a panel that listed things the tree cannot see would be a
second opinion about the pack. Both take permission level 2, like `reload`.
