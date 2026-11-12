# Tenet

The modern questing engine for Fabric and NeoForge, on Minecraft 26.3 and 1.21.1.

You write a quest line as JSON files, Tenet draws it in a quest book, and the server decides what
counts as done. Tenet ships no quests of its own. It's an engine for pack authors, not content.

**[Download on CurseForge](https://www.curseforge.com/minecraft/mc-mods/tenet)** ·
**[Manual](https://ellipog.dev/docs/tenet)** ·
**[Discord](https://discord.gg/nA6pm9JHd6)**

## Why Tenet

- **The server is the judge.** The client only draws what the server sends, so nobody completes a
  quest by editing their own game.
- **Mistakes point at a line.** The [validator](https://ellipog.dev/docs/tenet/authoring/validation)
  names the file and line of every problem and skips the broken file, so one bad quest never takes
  the server down.
- **Plenty to build with.** Eighteen [task types](https://ellipog.dev/docs/tenet/authoring/tasks),
  twelve [reward types](https://ellipog.dev/docs/tenet/authoring/rewards) and six
  [condition types](https://ellipog.dev/docs/tenet/authoring/conditions) that can gate any task or
  reward.
- **An in-game editor you can trust.** Select many quests and edit them in one go, undo any change,
  and get deleted quests back with `/tenet restore`, since a delete only marks the file.
- **Parties share progress.** Players [team up](https://ellipog.dev/docs/tenet/commands#parties) and
  count progress together, choosing whether one member's progress counts for everyone, everyone's
  adds up, or only the owner's counts. Open Parties and Claims parties work too.
- **Light on the server.** Kills, mined blocks and party updates are checked cheaply and batched, so
  mob farms and vein miners don't flood the network.

## Playing

Install **Tenet** and **[Armature](https://www.curseforge.com/minecraft/mc-mods/armature-lib)** on
both the client and the server, using the same Minecraft version and loader for each. Every
Minecraft version has its own jar, and Tenet needs the Armature release with the same number or
newer. If Armature is missing, the loader tells you.

| | 26.3 | 1.21.1 |
|---|---|---|
| Fabric | Loader 0.19.5+, Fabric API 0.162.0+26.3 | Loader 0.16.9+, Fabric API 0.109.0+1.21.1 |
| NeoForge | 26.3.0.76+ | 21.1.252+ |
| KubeJS (optional, NeoForge) | 26.1.2-8.0.6+ | 2101.7.2+ |

Open the quest book with the **B** key or the Quest Book item. The manual covers the rest:

- [The quest book](https://ellipog.dev/docs/tenet#the-book-briefly): the canvas, claiming rewards, parties
- [The HUD](https://ellipog.dev/docs/tenet/hud): pinning quests and the notices it shows
- [Commands](https://ellipog.dev/docs/tenet/commands): every `/tenet` command

## Making quests

Quests live in `config/tenet/quests/`, with a folder for each chapter and one JSON file for each
quest. Edit them, run `/tenet reload`, and press **B** to see the result. You can also build quests
in the book's editor, which writes the same files.

Good places to start:

- [The manual's front page](https://ellipog.dev/docs/tenet)
- [Folder format](https://ellipog.dev/docs/tenet/authoring/quest-files) and [quest fields](https://ellipog.dev/docs/tenet/authoring/quests)
- [Tasks](https://ellipog.dev/docs/tenet/authoring/tasks), [rewards](https://ellipog.dev/docs/tenet/authoring/rewards) and [conditions](https://ellipog.dev/docs/tenet/authoring/conditions)
- [Validation](https://ellipog.dev/docs/tenet/authoring/validation), [languages](https://ellipog.dev/docs/tenet/authoring/languages) and [KubeJS](https://ellipog.dev/docs/tenet/authoring/kubejs)
- [Coming from FTB Quests](https://ellipog.dev/docs/tenet/authoring/ftb-mapping)

For a complete example, [`tools/quests/`](tools/quests) holds a worked chapter that uses every field
the format has (see [`tools/README.md`](tools/README.md)), and the JSON schemas in
[`tools/quests/_schema/`](tools/quests/_schema) work with any editor that understands them.

## For addon mods

Mods can add their own task, reward and condition types through `TaskTypes`, `RewardTypes` and
`ConditionTypes`, and follow quests starting, completing and paying out through `TenetEvents`. The
[manual](https://ellipog.dev/docs/tenet#for-addon-mods) has the details.

Tenet is published to `https://maven.ellipog.dev` as
`dev.ellipog:tenet-common-<minecraft version>:<version>`, for example
`dev.ellipog:tenet-common-26.3:1.3.0`, with the matching `tenet-fabric` and `tenet-neoforge` artifacts beside it.

## Building

```cmd
gradlew build
```

The jars land in `fabric/build/libs` and `neoforge/build/libs`. This branch, `main`, builds for 26.3
and needs Java 25. The `mc-1.21.1` branch builds for 1.21.1 and needs Java 21.

Tenet compiles against the Armature release named by `armature_version` in `gradle.properties`, and
fetches it from maven.ellipog.dev. To build against an Armature that isn't released yet, run
`gradlew publishToMavenLocal` in your Armature checkout first. Maven Local takes priority.

## License

MIT. See [LICENSE](LICENSE).
