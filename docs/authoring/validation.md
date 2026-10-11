# Validation

A problem in a quest file is cheap to make and expensive to find — a typo'd field name, a dependency
that points at a quest that was renamed, a cycle nobody can unlock. Tenet's answer is to read the
files before anything tries to trust them, and to say what is wrong in the format a compiler uses:

```
quests/first_light/first_steps/punch_a_tree.json:14:9: error: unknown field "titl" - did you mean "title"?
    valid fields here: description, icon, id, title
```

File, line, column, severity, message — and, for a field nobody declares, the suggestions on the same line
plus a second line naming the fields that would have worked. That is `DataProblem.render()` and the
message beside it, and it is what the log carries for every problem a reload finds.

## Two passes, because they catch different mistakes

**The per-file [[validator]] runs before the codecs do.** It checks structure and values: every
required field present, every field some type or the common set declares, every enum name valid, every
id well-formed, every item that exists. Codecs alone cannot do this job — they ignore a field they do not
recognise, so `"titl": "Punch a Tree"` produces a quest with a blank title, no error, and nothing in
any log. And a codec failure names the file but not the line, which is most of what an author needs.

Because a file with a structural error is **not decoded at all**, one mistake produces one message
rather than a validator complaint followed by a codec complaint about the same thing. The same holds
one level down: a broken entry in a task's or reward's `conditions` list is reported at the entry's
own line, and the enclosing task's codec does not repeat it — a condition whose `type` no build knows
is one warning listing the condition types, not two messages saying it twice.

**Cross-file checks happen when the whole tree is known.** A file on its own cannot know that an id is
duplicated in another file — or that it collides with another thing's `aliases`, which the loader claims
in the same namespace per kind — or that a `dependsOn` names a quest nobody wrote, or that two quests
depend on each other in a circle. Those are checked against the assembled tree, after every file has been
read.

**A chapter's own gate is checked in both passes, for the same split.** The per-file validator checks
the five fields' shape — a well-formed id in `dependsOn` and `completesWhen`, a known
`prerequisiteMode`, a `minRequired` inside its bounds, a boolean for `hideUntilDependenciesComplete` and
for the two `defaultHideUntilDependencies*` fields beside it — as well as the two reveal flags on a
quest, which are booleans when present and absent when the chapter decides. The cross-file pass is what
can say the thing that matters, because a chapter reference, a completes-quest and the graph they form
are only visible once every file is read:

- a `dependsOn` naming a chapter that does not exist — reported with a "did you mean", like a quest's;
- a chapter waiting on itself, and `minRequired` above the size of its own list;
- a `completesWhen` naming a quest that does not exist, since such a chapter never reports completed;
- **a dependency that asks for *completed* while the chapter it points at declares no
  `completesWhen`** — counted rather than reported per edge, because `one_completed` over three
  chapters needs one of them to be completable and `minRequired: 2` needs two. Each of these is an
  error rather than a warning: every one of them produces a chapter that can never be opened, and a
  chapter that never opens is the quietest failure a questline has;
- a **cycle** in the chapter graph, printed as a chain like a quest cycle. That includes the one no
  single file shows: chapter A waits on chapter B while B is finished by a quest inside A.

> [!TIP]
> `/tenet types` prints every task, reward and [[tenet:authoring/conditions|condition]] type this
> build has, and the field names each one takes — the fastest way to check a field name without
> opening a schema, and the list the validator itself uses.

## What happens when a file is wrong

The file is **skipped, not fatal**, and the scope of the skip is that file's own contents. A quest file
with an error costs that quest: the chapter it sits in still opens with the rest of its list. The reload
reports the tally:

```cmd
/tenet reload
```

Two things cost more than one file, and both are said in the log rather than left to be discovered:

- **A name with nothing behind it.** A `chapters` or `quests` entry that resolves to no folder or file
  is an error against *that name*, not against the manifest that listed it — so a quest file deleted by
  hand, or a chapter folder renamed on one side only, costs exactly the one thing that is missing. It
  used to be reported against the manifest, and a manifest carrying an error is refused, which took the
  chapter — or the whole group — with it.
- **A manifest that cannot say what it holds.** A `group.json` or `chapter.json` that will not parse,
  names no `id`, or has a `chapters`/`quests` that is not a list of names cannot be read at all, so its
  subtree does not load and the error is against that file. That is the only case where one file costs
  more than itself.

A root `index.json` is a third, and it is the one that is read *around* rather than skipped: one that
cannot declare the root is read as if it were not there, and the tree loads by folder name — see
[[tenet:authoring/quest-files]].

> [!WARNING]
> A file can decode cleanly and still be wrong — a circular dependency is the usual one. Reload counts
> files that had *problems*, not files that parsed, so "loaded" never means "correct".

## Ghosts, crowding and duplicates

A quest with no tasks and no rewards is only a warning when **nothing depends on it** — a ghost,
almost always a quest someone started and did not finish writing. A taskless quest with a dependant
is a junction, a milestone or a chapter gate, and it loads quietly: legacy authors use empty quests
that way, and warning about them would be noise on every converted pack. Quest links and image
gates pointing at the quest do not count — only `dependsOn` makes a dependant — and a taskless
quest completes on the tick its gate opens, with no press on it.

Two quests at exactly the same position warn as a duplicate — one will be drawn over the other. Two
titled quests in one row warn when they are closer than 64 pixels, the grid a converted pack is laid
out on: below that even short titles collide, while at 64 and above the book truncates what does not
fit. Pairs with no drawn titles never warn, and a gap of zero is reported once, as a duplicate rather
than as crowding.

## Unknown task, reward and condition types

A `type` no build here registers — an addon that is not installed, or a third-party type like
`eternalcurrencies:currency`, `questsadditions:*` or `quest_loot` — is a **warning**, and the node is
kept as an unknown placeholder that carries the id. The file still loads, and the row says which mod
is missing. An unknown task can never satisfy and offers no button; an unknown reward is never
auto-granted, so an automatic path leaves it for the claim rather than marking it collected unpaid;
an unknown condition reads as unmet, so a gate it guards stays shut. An unknown *field* on a known
type is still an error — a typo must fail loudly, and there is no way to tell a misspelling from a
field a future build adds.

## Stale translations and retired fields

A `lang/` key for a quest that was deleted, or written against the wrong id, warns rather than failing
the load — one warning per file, naming the keys and, where the spelling is close, the id that was
meant. A key written against an alias or a tag warns too: readers build their lookups from the
object's own id, so the warning names the id the key should use. Keys in no book namespace are left
alone — the overlay serves any key, and a script may own the ones no reader looks up. See
[[tenet:authoring/languages]] for the key shapes.

A `version` in a per-kind file, and an `id` or `loot_crate` in a reward-table file, warn the same way:
leftovers the codec never reads, from an older schema or a converted file, which is why refusing the
file over them would be wrong. Anything else unknown is still an error. The editor schemas describe
what to write rather than what loads — so a leftover shows an editor hint as well as the load warning,
and both say the same thing.

The in-game editor refuses a write that would not validate, and reports the first problem's line, so
the round trip through the book cannot produce a file the loader would reject.

## Finding the problem when the message is not enough

`/tenet quest <id>` prints one quest as the engine sees it — where it is, what it depends on, which
flags are set — which is what tells you whether a file loaded as written or was overridden by an
inherited default:

```cmd
/tenet quest punch_a_tree
```

`/tenet progress` prints the tree from the player's point of view: each quest's state, and every task
with its recorded count against what it needs. A task that shows `0/8` while the player carries eight
logs is a matching or consumption question, not a loading one.

> [!NOTE]
> Problems are reported, not enforced on old files: a warning never stops a file from loading, and
> nothing here renames or rewrites your files. The editor's writes are the only path that changes a
> quest file, and only when a player with permission asks it to.
