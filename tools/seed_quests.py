"""
seed_quests.py - put the example questlines into a config directory.

Why this is a script and not something the mod does
---------------------------------------------------
Tenet ships no quests. It is a quest *engine*, and a mod that installs example chapters into
every player's config directory has decided something that is not its to decide: the first thing a
pack author would have to do is delete somebody else's content, and every file a mod ships is a file
that has to keep working forever against a format that is still moving.

So the worked examples live here, beside this script, and they reach a config directory when somebody
asks for them:

    python tenet/tools/seed_quests.py <dir> [<dir>...]     any config/tenet/quests directories
    python tenet/tools/seed_quests.py --workspace          this repo's own test setup

A `<dir>` is a `config/tenet/quests` directory - the one the mod reads. It is created if it is not
there.

Nothing is overwritten unless you say so
----------------------------------------
An existing file is left exactly as it is, and that is the point rather than a nicety. This directory
is where an author's own questline lives, so a tool that refreshed it on every run would destroy work
and be indistinguishable from a bug -- which is the same failure the mod used to have, arriving from
the other direction.

So there are two ways to get a pristine copy, and neither is silent: delete the file and run this
again, or pass `--force`. `--force` names each file it replaces, so a replace is never something that
happened while you were not looking.

**Two files are merged rather than replaced, even under `--force`: the two manifests the editor also
writes.** `group.json` gains a folder in `chapters` when a chapter is created, and `chapter.json` gains a
file in `quests` when a quest is duplicated or pasted. The loader refuses the whole group when a folder is
present and unlisted, and refuses a chapter when a quest file is present and unlisted -- both reading as a
broken pack rather than as a stale list. Replacing either would orphan the author's own entries, so a forced
copy keeps the destination's own alongside the shipped ones. See `merge_group_chapters` and
`merge_chapter_quests`; the second was added after a forced refresh orphaned 280 copied quests in one
chapter and the book loaded 211 of 213 files.

`--reset` is the third way, and the loudest: it deletes everything in each target's quest directory
-- every file and folder, not only the ones this script wrote -- and then copies the examples in
fresh. That is the flag for a wholesale replacement like the one that collapsed twelve example
questlines into one chapter: without it the old groups sit beside the new one and load as extra
content, and with `--force` alone they would survive as stale folders. It is never implied by any
other flag, it names every entry it removes, and `--dry-run` reports exactly the same removals while
touching nothing. `--force` is still the flag for replacing files; `--reset` is for starting over.

`--reset` keeps `_`-prefixed entries, because the `_` prefix means the same thing here as
everywhere else in this project: deliberately out of the loader's way. A test combo's
`_example_typos.json` and friends are fixtures a person renames to run, not stale content, and a
reset that deleted them would be the one place the convention did not hold.

`--workspace` seeds every test combo and both Modrinth profiles, and is the mode this project uses.
It is also why this script is here rather than in `.utils/`: it belongs to the Tenet repository, so
it travels with the examples it copies, and a separate checkout of Tenet gets both.

Files beginning with `_` are **not** copied, at any level. Those are deliberately-broken fixtures -
the loader skips them by that prefix - and copying one would install a questline whose only purpose is
to fail. The same prefix is why `_schema/` is here at all: it is a folder of editor schemas beside the
content, and the rule is what keeps the loader from error-walling on a folder the mod ships itself.

It also **removes the version-1 files it has replaced.** Converting an install means the same
questline exists twice -- once as `01_stone_age.json`, once as `getting_started/` -- and leaving both
is not a stale copy, it is two questlines: every group, chapter and quest in the pair is reported as a
duplicate id. So a root-level `.json` whose declared chapter groups are all provided by folders that
were just copied is deleted, and anything else at that level is **kept and named**, with the reason.
See `sweep_legacy_flat_files` for the rule and why it is stated in ids rather than in file names.
"""

import argparse
import json
import pathlib
import shutil
import sys

# This script lives in tenet/tools/, so the repository is one level up and the workspace two.
TOOLS = pathlib.Path(__file__).resolve().parent
QUESTS = TOOLS / "quests"
WORKSPACE = TOOLS.parent.parent

# The manifest names the loader fixes, so the sweep below can tell a manifest from a quest file
# without asking the mod. Must match `QuestFiles.INDEX_MANIFEST`, `GROUP_MANIFEST` and
# `CHAPTER_MANIFEST` -- all three, which is what this list was missing: the root manifest is a
# legitimate root-level JSON file, and without it here the sweep reported the author's own
# `index.json` as a stray version-1 file that would duplicate their whole questline.
INDEX_MANIFEST = "index.json"
GROUP_MANIFEST = "group.json"
CHAPTER_MANIFEST = "chapter.json"

# Root folders the loader reserves by name: read outside the book's own walk, so they are not book
# content and an `index.json` must not list them. Must match `QuestFiles.isReservedName`.
RESERVED_ROOT_NAMES = ("reward_tables", "lang")

# The workspace's own test setup. Both are absent on a fresh clone, and neither is an error.
COMBOS = WORKSPACE / "testserver" / "combos"
LOADERS = ("fabric", "neoforge")

# The launcher's profiles, where the mod actually runs for a person. Resolved from the home
# directory rather than written down, so nothing here is a path to this machine.
LAUNCH_PROFILES = pathlib.Path.home() / "AppData" / "Roaming" / "ModrinthApp" / "profiles"
PROFILE_NAMES = (
    "Mod Testing Fabric 1.21.1",
    "Mod Testing NeoForge 1.21.1",
    "Fabric 26.3",
    "NeoForge 26.3",
)

# Relative to a config directory. Must match QuestLoader.DIRECTORY in the mod.
QUEST_DIRECTORY = pathlib.Path("config") / "tenet" / "quests"


def example_files():
    """
    Every example file to copy, in a stable order, as paths relative to `quests/`.

    Recursive, and it had to become recursive when the format changed. It was `QUESTS.glob("*.json")`
    — right for the flat format, where a file *is* a whole tree, and silently empty under the folder
    format, where every quest is four levels down inside `getting_started/first_steps/`. A glob that
    matches nothing is not an error: this would have copied no files at all, printed a cheerful
    summary of zero, and left the caller with an empty quest book and nothing in any log to explain
    it.

    **Relative paths, not names**, because a name is not unique in the tree this was written for:
    `group.json` appears once per group and `chapter.json` once per chapter. Returning bare names
    would have overwritten eleven group manifests with twelve others, in whatever order `sorted`
    produced -- the twelve-group era, kept here because the reason has not changed even though the
    exhibition is one group now.

    The `_` rule applies to **every** segment, not just the last. `_schema/` is a directory, so a rule
    that tested only the file's own name would copy all three schema files into the config directory —
    where the loader's own walk would skip them, making the mistake invisible from here and visible
    only as three stray files in somebody's install.
    """
    if not QUESTS.is_dir():
        return []
    out = []
    for path in sorted(QUESTS.rglob("*")):
        if not path.is_file():
            continue
        relative = path.relative_to(QUESTS)
        if any(segment.startswith("_") for segment in relative.parts):
            continue
        out.append(relative)
    return out


def workspace_targets():
    """
    Every config directory this workspace cares about.

    The combos are seeded because the test servers read them, and the profiles because that is where
    the mod is actually played. Profiles are only used when the profile directory exists: creating
    `config/tenet/quests` inside a profile you have is a kindness, and conjuring a profile folder
    the launcher does not know about is not.

    `_tools` and `_loaders` are skipped by name. They sit in the same folder as the combos and are
    infrastructure rather than combos, and `testserver/README.md` documents that convention.
    """
    targets = []

    if COMBOS.is_dir():
        for combo in sorted(p for p in COMBOS.iterdir() if p.is_dir()):
            if combo.name.startswith("_"):
                continue
            # Both the combo's own config and each loader's copy of it. `setservers.ps1` seeds the
            # former into the latter, and the loaders read their own, so both have to be current.
            targets.append(combo / QUEST_DIRECTORY)
            for loader in LOADERS:
                targets.append(combo / loader / QUEST_DIRECTORY)

    for name in PROFILE_NAMES:
        profile = LAUNCH_PROFILES / name
        if profile.is_dir():
            targets.append(profile / QUEST_DIRECTORY)

    return targets


def display(path: pathlib.Path) -> str:
    """A path as a person should read it: relative to the workspace when it is inside it."""
    try:
        return str(path.relative_to(WORKSPACE))
    except ValueError:
        return str(path)


def seed(target: pathlib.Path, files, force: bool, dry_run: bool, reset: bool = False):
    """
    Copy the examples into one target, then clear away the flat files they replaced.

    With `reset`, everything already in the target's quest directory is removed first — files and
    folders alike, named one by one — because that is what "replace the exhibition" means when the
    old exhibition was a different set of folders. See the module docstring on why this is a flag
    of its own rather than something `--force` grew into.

    Returns `(created, replaced, kept, removed, reset_entries, survivors)`. `survivors` is the list
    of root-level JSON files that were left alone — see `sweep_legacy_flat_files`, which is where
    the interesting decision lives.
    """
    created = replaced = kept = reset_entries = 0
    print(f"\n{display(target)}")

    reset_names: set[str] = set()
    if reset and target.is_dir():
        existing = sorted(target.iterdir())
        removable = [entry for entry in existing if not entry.name.startswith("_")]
        reset_names = {entry.name for entry in removable}
        if removable:
            print("  --reset: removing everything here except `_`-prefixed fixtures")
        for entry in existing:
            if entry.name.startswith("_"):
                print(f"  ! {entry.name}  kept by --reset: the `_` prefix is how a fixture is"
                      " deliberately kept out of the loader's way")
                continue
            kind = "folder" if entry.is_dir() else "file"
            if not dry_run:
                if entry.is_dir() and not entry.is_symlink():
                    shutil.rmtree(entry)
                else:
                    entry.unlink()
            print(f"  x {entry.name}  removed by --reset ({kind})")
            reset_entries += 1

    if not dry_run:
        target.mkdir(parents=True, exist_ok=True)

    for relative in files:
        source = QUESTS / relative
        # The relative path is preserved rather than flattened. Under the folder format the path *is*
        # information: `getting_started/first_steps/punch_a_tree.json` is a quest, and a bare
        # `punch_a_tree.json` written to the quest root is a version-1 file the loader would try to read
        # as a whole tree — and report a typo about, at a line that does not exist.
        destination = target / relative
        # A `--dry-run` does not perform the reset's deletions, so the copy step has to know what
        # they would have taken: without this the dry run reports 375 files "kept" for a run that
        # would create 375. Predicting differently from the run it predicts is the one thing a dry
        # run must never do.
        existed = destination.exists() and relative.parts[0] not in reset_names

        if existed and not force:
            print(f"  = {relative.as_posix()}  kept, already there")
            kept += 1
            continue

        # The two manifests are the example files the editor also writes to. See the docstring,
        # `merge_group_chapters` and `merge_chapter_quests`: replacing either list with the shipped one
        # orphans the author's own entries, and the loader refuses an unlisted file or folder.
        if relative.name == GROUP_MANIFEST:
            merged = merge_group_chapters(source, destination)
        elif relative.name == CHAPTER_MANIFEST:
            merged = merge_chapter_quests(source, destination)
        else:
            merged = None

        if not dry_run:
            destination.parent.mkdir(parents=True, exist_ok=True)
            if merged is None:
                shutil.copy2(source, destination)
            else:
                destination.write_text(json.dumps(merged[0], indent=2) + "\n", encoding="utf-8")

        verb = "~" if existed else "+"
        note = "  [replaced]" if existed else ""
        if merged is not None:
            note += kept_note(merged[1])
        print(f"  {verb} {relative.as_posix()}  ({source.stat().st_size} bytes){note}")

        if existed:
            replaced += 1
        else:
            created += 1

    removed, survivors = sweep_legacy_flat_files(target, files, dry_run)

    # And the root manifest, which is the third thing the loader reads and the second thing that
    # decides whether the files just copied are ever loaded at all. See `merge_root_index`.
    merged = merge_root_index(target, files)
    if merged is not None:
        document, added = merged
        if not dry_run:
            (target / INDEX_MANIFEST).write_text(json.dumps(document, indent=2) + "\n",
                                                 encoding="utf-8")
        print(f"  ~ {INDEX_MANIFEST}  [added {', '.join(added)}]")

    return created, replaced, kept, removed, reset_entries, survivors


def merge_root_index(target: pathlib.Path, files):
    """
    The root manifest with the groups and chapters just copied folded into it.

    ## Why the root needs this as much as a group manifest does

    `merge_group_chapters` exists because the loader refuses a group whose chapter folders are
    unlisted, and that refusal reads as "no quests loaded". At the root the same rule bites harder:
    with an `index.json` present the loader reads **only what it lists** and reports every other entry
    as an error that says so -- *"it will never load"*. So seeding a folder into a root that has a
    manifest produced a directory full of quests that the game never reads, and a complaint per
    folder, from the one tool whose whole job is to put quests where the game can see them.

    That is not a hypothetical: it is what happened on the first profile this was run against, and it
    is why this function exists rather than a note telling the reader to edit their own manifest.

    ## What it will not do

    It only ever **appends**, in the order the examples are walked, and it never reorders or removes
    the author's own entries -- the manifest is also the book's reading order, and an author's order is
    theirs. A target with no manifest is left alone entirely, because absence is not a fault: the tree
    is then read the old way, which loads every folder there is. A manifest it cannot read or make
    sense of is left alone too, and that is the one case worth saying out loud, since the copy beside
    it will not load.

    Returns `(document, added)` or None. `added` is the names it appended, for the caller's line.
    """
    manifest = target / INDEX_MANIFEST
    if not manifest.exists():
        return None
    try:
        document = json.loads(manifest.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError):
        print(f"  ! {INDEX_MANIFEST}  could not be read, so the folders just copied are unlisted and"
              " will not load -- add them to its \"entries\" by hand")
        return None
    if not isinstance(document, dict) or not isinstance(document.get("entries"), list):
        print(f"  ! {INDEX_MANIFEST}  has no \"entries\" list, so the folders just copied are"
              " unlisted and will not load -- add them by hand")
        return None

    listed = set()
    for entry in document["entries"]:
        if isinstance(entry, dict):
            listed.update(value for value in entry.values() if isinstance(value, str))

    # The top level of what was copied: a folder is a group when it carries a group manifest and a
    # chapter when it carries a chapter one, which is the same question `QuestFiles` asks. Reserved
    # names are not book content and must not be listed -- `QuestFiles.isReservedName` is the rule,
    # and `lang/` and `reward_tables/` are the two it names.
    kind_of_folder = {}
    for relative in files:
        if len(relative.parts) < 2:
            continue
        name = relative.parts[0]
        if name.startswith("_") or name in RESERVED_ROOT_NAMES:
            continue
        if relative.name == GROUP_MANIFEST:
            kind_of_folder[name] = "group"
        elif relative.name == CHAPTER_MANIFEST and name not in kind_of_folder:
            kind_of_folder[name] = "chapter"

    added = [name for name in kind_of_folder if name not in listed]
    if not added:
        return None
    for name in added:
        document["entries"].append({kind_of_folder[name]: name})
    return document, added


def merge_group_chapters(source: pathlib.Path, destination: pathlib.Path):
    """
    A group manifest with the destination's own chapter list folded into the source's.

    ## Why this one file is merged rather than replaced

    Every other file here is content: replacing it replaces the example. `group.json` is also a
    *manifest* the editor writes -- creating a chapter adds its folder to `chapters` -- and the loader
    refuses a whole group when a folder is present and unlisted. That refusal reads as "no quests
    loaded", the reason one line further up, so a `--force` that clobbered the list would empty the
    book the author was working in: the very failure this script's "nothing is overwritten unless you
    say so" rule exists to prevent, arriving through the flag that was supposed to be the safe way to
    refresh. The union is what the format's own rule asks for -- every chapter folder is listed, or
    `_`-prefixed to be deliberately out of the way.

    Returns `(manifest, kept)`, or None when the destination is absent, unreadable, or added nothing.
    """
    if not destination.exists():
        return None
    try:
        theirs = json.loads(destination.read_text(encoding="utf-8"))
        ours = json.loads(source.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError):
        return None
    listed = list(ours.get("chapters", []))
    kept = [chapter for chapter in theirs.get("chapters", [])
            if isinstance(chapter, str) and chapter not in listed]
    if not kept:
        return None
    ours["chapters"] = listed + kept
    return ours, kept


def kept_note(kept):
    """
    The "[kept the author's ...]" note, as a count with a sample rather than the whole list.

    ## Why this is not the full list any more

    It was, and a chapter with 280 recovered quests printed all 280 on one line -- several thousand
    characters, wrapped over the terminal, burying the line it was attached to. The note exists to say *that*
    the author's entries survived and roughly how many; the names are on disk and in the manifest, and a
    reader who wants them can open the file. A summary that cannot be read is not a summary.
    """
    if len(kept) <= 4:
        return "  [kept the author's: " + ", ".join(kept) + "]"
    return f"  [kept the author's: {len(kept)}, e.g. " + ", ".join(kept[:3]) + ", ...]"


def merge_chapter_quests(source: pathlib.Path, destination: pathlib.Path):
    """
    A chapter manifest with the destination's own quest list folded into the source's.

    ## Why this file is merged too, and what it cost not to

    `group.json` was the only manifest this script merged, and that was half the rule. `chapter.json` is
    the other one the editor writes -- **duplicating or pasting a quest adds its file to `quests`** -- and
    the loader refuses a chapter when a quest file is present and unlisted:

        the quest file "the_triumph_copy.json" is here, and this chapter's "quests" list does not mention
        it - so it will never load.

    So a `--force` that replaced this list with the shipped one did not merely lose the author's order: it
    orphaned **every quest they had made a copy of**. On the profile this was found on, 280 files went
    unlisted in one chapter and the book loaded 211 of 213 files with 281 errors -- for a refresh flag
    that is supposed to be the safe way to bring the examples up to date.

    The union is what the format asks for, exactly as it is for a group: every quest file is listed, or
    `_`-prefixed to be deliberately out of the way. The shipped names keep their order and the author's go
    after them, because the shipped list is the example's sequence and an appended copy has no place in it.

    Returns `(manifest, kept)`, or None when the destination is absent, unreadable, or added nothing.
    """
    if not destination.exists():
        return None
    try:
        theirs = json.loads(destination.read_text(encoding="utf-8"))
        ours = json.loads(source.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError):
        return None
    listed = list(ours.get("quests", []))
    kept = [quest for quest in theirs.get("quests", [])
            if isinstance(quest, str) and quest not in listed]

    # **And the files on disk that neither list mentions**, which is the repair rather than the guard.
    #
    # The guard above only helps while the destination still remembers its own list. A `--force` run by a
    # build of this script that replaced the list instead of merging destroyed it: the author's quests are
    # still on disk and now in no list at all, so there is nothing left to merge and the next run would
    # happily write the shipped list again. That is exactly the state this was found in -- 280 copied quest
    # files unlisted in one chapter -- and a guard that cannot repair the damage it was added to prevent is
    # half a fix.
    #
    # The directory is the record: a `*.json` in the chapter's own folder is a quest, whatever any list
    # says. `chapter.json` is the manifest itself and the `_` prefix means deliberately out of the way, so
    # both are left out, which is the same rule the loader applies. Sorted, because the order of a repair
    # is not the author's order and a stable one is worth more than an arbitrary one.
    known = set(listed) | set(kept)
    try:
        on_disk = sorted(entry.name for entry in destination.parent.iterdir()
                         if entry.is_file()
                         and entry.name.endswith(".json")
                         and entry.name != destination.name
                         and not entry.name.startswith("_"))
    except OSError:
        on_disk = []
    recovered = [name for name in on_disk if name not in known]
    kept = kept + recovered

    if not kept:
        return None
    ours["quests"] = listed + kept
    return ours, kept


def sweep_legacy_flat_files(target: pathlib.Path, files, dry_run: bool):
    """
    Delete the version-1 flat files that the folder tree beside them has replaced.

    ## Why this is a deletion, and why it is not `--force`'s job

    Converting an install means the same questline exists twice: once as `01_stone_age.json`, and once
    as `getting_started/`. Leaving both is worse than a stale copy — it is *two questlines*, and every
    group, chapter and quest in the pair is reported as a duplicate id. The message an author gets
    names 01_stone_age.json and a file inside the folder as the two sides of a clash, which reads as a
    broken conversion rather than as a file that needs deleting.

    So the script tidies up after itself, and it is the only thing in this repository that deletes a
    quest file. That is why the rule it deletes by is narrow, and why every case it *declines* is
    named out loud rather than passed over.

    ## The rule

    A `*.json` directly at the target's quest root is deleted **only if** every chapter-group id it
    declares is also provided by a group folder that was just copied. So:

      * `01_stone_age.json`, declaring `getting_started`, goes — because `getting_started/group.json`
        is now there. That is exactly the stale copy.
      * An author's own `my_questline.json`, declaring a group of its own, **stays**, and is named.
        Nothing copied declares that group, so it is not a copy of anything this script wrote, and it
        is not this script's to remove.

    Matching on ids rather than on file names is the point. A name list would be a thing that goes
    stale — the four names live in this file's history and nowhere else now — and it would delete
    `01_stone_age.json` even if an author had rewritten it into something of their own.

    ## What it will not do

    No directory is deleted. An empty group folder is a legitimate placeholder, and a script that
    removed folders would eventually remove one an author had just made.

    `_`-prefixed files are left alone entirely: that prefix is how a file is deliberately kept out of
    the loader's way, so removing one would defeat the convention.

    A file that will not parse is left alone and named. It is not a v1 file this can recognise, and
    deleting a file this cannot read is the one thing a tidy-up must never do.
    """
    # Which group ids the folder tree now provides, read from the manifests that were just copied.
    #
    # Read from the *copy list* rather than from the target directory, so `--dry-run` reports exactly
    # the same deletions a real run would perform while touching nothing. A dry run that predicted
    # differently from the run it was predicting would be worse than no dry run at all.
    provided = set()
    for relative in files:
        if relative.name != GROUP_MANIFEST:
            continue
        try:
            declared = json.loads((QUESTS / relative).read_text(encoding="utf-8")).get("id")
        except (OSError, json.JSONDecodeError):
            continue
        if isinstance(declared, str):
            provided.add(declared)

    if not target.is_dir():
        return 0, []

    removed = 0
    survivors = []
    for candidate in sorted(target.glob("*.json")):
        if candidate.name.startswith("_"):
            continue
        if candidate.name == INDEX_MANIFEST:
            # The root manifest. Not a version-1 file, not a copy of anything this script wrote, and
            # the one root-level JSON the loader reads as a manifest rather than as content -- so it
            # is passed over silently rather than named as a survivor. `merge_root_index` is where it
            # is *maintained*; naming it here as a possible duplicate of the tree would be the same
            # false alarm this sweep exists to avoid making in the other direction.
            continue
        try:
            data = json.loads(candidate.read_text(encoding="utf-8"))
        except (OSError, json.JSONDecodeError) as problem:
            survivors.append((candidate.name, f"it will not parse ({problem})"))
            continue

        if not isinstance(data, dict) or "chapterGroups" not in data:
            survivors.append((candidate.name, "it is not a version-1 file"))
            continue

        groups = data.get("chapterGroups") or []
        ids = [g.get("id") for g in groups if isinstance(g, dict)]
        if not ids:
            survivors.append((candidate.name, "it declares no chapter groups"))
            continue

        missing = [group_id for group_id in ids if group_id not in provided]
        if missing:
            survivors.append((candidate.name,
                              "it declares " + ", ".join(repr(m) for m in missing)
                              + ", which no copied group folder provides"))
            continue

        if not dry_run:
            candidate.unlink()
        print(f"  - {candidate.name}  removed: a version-1 file whose group(s) "
              + ", ".join(repr(g) for g in ids) + " are now in folders beside it")
        removed += 1

    for name, reason in survivors:
        print(f"  ! {name}  KEPT -- {reason}. A version-1 file beside the folders would load as a"
              "\n      second copy of whatever it declares, so every shared id is reported as a"
              "\n      duplicate. Delete it, or move it somewhere the loader does not read.")

    return removed, survivors


def main() -> int:
    parser = argparse.ArgumentParser(
        prog="seed_quests.py",
        description="Copy the example questlines into config/tenet/quests directories. "
                    "Existing files are left alone unless --force is given.")
    parser.add_argument("targets", nargs="*", metavar="DIR",
                        help="a config/tenet/quests directory, created if it is not there")
    parser.add_argument("--workspace", action="store_true",
                        help="seed this repo's test combos and both Modrinth profiles")
    parser.add_argument("--force", action="store_true",
                        help="overwrite files that are already there, naming each one")
    parser.add_argument("--reset", action="store_true",
                        help="delete each target's quest directory first (except `_`-prefixed"
                             " fixtures), then copy the examples in fresh. For replacing one"
                             " exhibition with another; never implied by another flag")
    parser.add_argument("--dry-run", action="store_true",
                        help="say what would happen and change nothing")
    args = parser.parse_args()

    files = example_files()
    if not files:
        print(f"no example quests at {display(QUESTS)}")
        return 1

    targets = [pathlib.Path(t) for t in args.targets]
    if args.workspace:
        found = workspace_targets()
        if not found:
            print("--workspace found nothing to seed: no testserver/combos, and neither launch "
                  "profile exists")
            return 1
        targets.extend(found)

    if not targets:
        parser.error("give at least one directory, or --workspace")

    print(f"examples in {display(QUESTS)}")
    for relative in files:
        print(f"  {relative.as_posix()}  ({(QUESTS / relative).stat().st_size} bytes)")

    created = replaced = kept = removed = reset_entries = 0
    survivors = []
    for target in targets:
        a, b, c, d, e, s = seed(target, files, args.force, args.dry_run, args.reset)
        created += a
        replaced += b
        kept += c
        removed += d
        reset_entries += e
        survivors.extend(s)

    print()
    if args.dry_run:
        print("dry run: nothing was written")
    print(f"created {created}, replaced {replaced}, kept {kept}, removed {removed} file(s) across "
          f"{len(targets)} director{'y' if len(targets) == 1 else 'ies'}")
    if args.reset:
        print(f"--reset removed {reset_entries} pre-existing entr"
              f"{'y' if reset_entries == 1 else 'ies'} before copying. If that was not what you"
              " wanted, the removed files are not recoverable from here.")

    if survivors:
        print(f"{len(survivors)} file(s) were kept and named above. Each is a version-1 file sitting"
              " beside the folders that replaced it, which loads as a second copy of whatever it"
              " declares -- so every shared id is reported as a duplicate. Nothing here deletes them,"
              " because a file this script cannot match to a folder it wrote might be yours.")

    if kept and not args.force:
        print("Existing files were left alone. That is the default: delete one and run this again "
              "for a fresh copy, or pass --force.")
    print("A running server reads these on /tenet reload, or on its next start.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
