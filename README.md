# Tenet

The modern questing engine for Fabric and NeoForge.

A pack author describes a quest line in JSON, Tenet draws it in a quest book, and the
server decides what counts as done. Tenet ships no quests of its own — it is an engine,
not content.

> **Before its first stable release.** The format and the API may still break, so read
> the release notes before upgrading.

## Why Tenet

- **The server decides what counts.** The client draws what it is told and nothing more, so editing your own game completes nothing.
- **[Validator points at file and line](https://ellipog.dev/docs/tenet/authoring/validation).** A bad file is reported with its path and skipped — one broken quest never takes the server down.
- **[Seventeen task, twelve reward, six condition types](https://ellipog.dev/docs/tenet/authoring/tasks).** Tasks ask, rewards pay, conditions gate either — see the [task](https://ellipog.dev/docs/tenet/authoring/tasks), [reward](https://ellipog.dev/docs/tenet/authoring/rewards), and [condition](https://ellipog.dev/docs/tenet/authoring/conditions) guides.
- **[Editor with undo, and nothing really deletes](https://ellipog.dev/docs/tenet#removing-something-and-getting-it-back).** Bulk-select nodes, edit as one batch per chapter with one undo, and every delete is a `.deleted` tombstone that `/tenet restore` brings back.
- **[Parties share progress](https://ellipog.dev/docs/tenet/commands#parties).** Built on Armature teams — `ONE_MEMBER`, `POOLED`, or `OWNER_ONLY` counting, server-authoritative throughout.

## Playing

Install **Tenet and Armature** together, on client and server. The loader will name
Armature if it is missing. See the Modrinth page for the supported Minecraft version
and which Armature release to pair with this one.

Open the book with the Quest Book item or the `B` key. Details live in the manual:

- [The quest book and HUD](https://ellipog.dev/docs/tenet#the-book-briefly) — canvas, claims, parties
- [Commands](https://ellipog.dev/docs/tenet/commands) — every `/tenet` command
- [HUD](https://ellipog.dev/docs/tenet/hud) — pinning quests and notices

## Making quests

Quests are JSON under `config/tenet/quests/` — one folder per group, one file per
quest. Run `/tenet reload`, then press `B`.

Start here:

- [Manual front door](https://ellipog.dev/docs/tenet)
- [Folder format](https://ellipog.dev/docs/tenet/authoring/quest-files)
- [Quest fields](https://ellipog.dev/docs/tenet/authoring/quests)
- [Tasks](https://ellipog.dev/docs/tenet/authoring/tasks) · [Rewards](https://ellipog.dev/docs/tenet/authoring/rewards) · [Conditions](https://ellipog.dev/docs/tenet/authoring/conditions)
- [Validation](https://ellipog.dev/docs/tenet/authoring/validation) · [Languages](https://ellipog.dev/docs/tenet/authoring/languages) · [KubeJS](https://ellipog.dev/docs/tenet/authoring/kubejs) · [Legacy mapping](https://ellipog.dev/docs/tenet/authoring/ftb-mapping)

The worked example in `tools/quests/` (see `tools/README.md`) carries every field the
format has, and the schemas under `tools/quests/_schema/` are the machine-readable
reference.

For addon mods: `TenetEvents` plus the task, reward, and condition registries. See the
releases page for the current Maven coordinate — it follows the shape
`dev.ellipog:tenet-common-<minecraft-line>:<release>` on `https://maven.ellipog.dev`.

## Building

```cmd
gradlew build
```

Jars land in `fabric/build/libs` and `neoforge/build/libs`. The pin Tenet compiles
against is `armature_version` in `gradle.properties` — that Armature release has to
exist first. For an unreleased Armature, publish it locally first (`publishToMavenLocal`
in the Armature checkout; `mavenLocal` wins), then build here.

## Licence

MIT — see [LICENSE](LICENSE).
