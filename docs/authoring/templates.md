# Templates

A template is a task, reward or condition setup kept on a shelf and applied to any quest as a
copy. The tenth gather quest in a pack should not be written by hand, and it should not be
copied field by field from the ninth: save the ninth's setup once, then apply it with one press
wherever it belongs.

Templates live in the **Templates** section of the [[tenet:index#the-book-briefly|Assets panel]],
under two headings: **Shipped** — the ten setups Tenet carries, covering the common few-types
case — and **Yours** — what this client saved. The shelf is per client, not per pack or server:
it is author tooling, so it needs no sync, works offline, and never leaks one author's scratch
work into another's game. Sharing a template is copying its JSON, the format's own lingua franca.

## Saving one

Every task and reward row in the quest card carries a **★** between `Copy` and the fold, and
every condition row carries one beside its `×`. One press saves the setup as it stands —
conditions on the entry included — onto the shelf under Yours, named for what it does
(`Gather 8 × Oak log`). Saving touches no quest file and is never an undo step: the quest is
exactly as it was, and the shelf has one more row.

## Applying one

A row press **is** the apply. Applying appends a copy to the open quest — a task or reward to
the quest's own list, a condition to the last entry touched — through the same validated edit a
hand-made change takes, so undo is one step and the server checks the copy like anything else.
Copies share nothing with their source: the shelf keeps the setup, the quest gets its own, and
editing one never reaches the other.

There are three places to press:

| Where | What it does |
|---|---|
| The Templates page | The shelf itself: every template, shipped and saved, with the row's sentence beside its name. Hovering a row says where the press lands; with no quest open it says to open one first. |
| `Add task` / `Add reward` / `+ Condition` | The shelf first, above the fresh types: applying where the author already is, filtered by the same search box. The fastest way to reuse a setup mid-quest. |
| The quest card's `Copy` | Duplicates the entry beside itself — the shelf's older sibling for the one-quest case. |

A template whose type this build does not know — an addon installed when it was saved and
missing now — is listed with its reason (`Needs othermod:gizmo`) and cannot be applied. It can
still be deleted: its `×` stays, because a template for a removed addon must be removable.

## Ordering the shelf

The footer's **Sort** cycles three orders, remembered per client:

| Order | What it does |
|---|---|
| `Recent` | Recently applied first — the shelf tidies itself around what is actually reached for. |
| `Name` | By display name, in the client's locale. |
| `Type` | Grouped by kind, then type, then name: every task together, every reward together. |

## One setup, one row

Pressing the ★ on an identical setup does not shelve it twice: the shelf recognises the setup
— kind, type and fields, whatever it is called — and answers with the row it already has. Ten
presses list it once.

## Renaming one

A saved row carries a **✎** beside its `×`. One press edits the name in place, through the same
inline box the quest card's fields use; Enter keeps it, clicking away keeps it, and an empty name
keeps the old one. The id never changes, so nothing that names the template moves while its
words do. Shipped rows have no pencil: their words ship with the mod.

To change what a template *does* rather than what it is called, apply it, edit the copy in the
quest, and save it again — then `×` the old row if it has served its purpose.

## Removing one

Only Yours deletes — shipped rows have nothing to delete. Like every control that takes
something away, the `×` asks twice: the first press arms it, the second removes the row from
the shelf and the file.
