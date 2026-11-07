# Tenet performance plan

Written 2026-10-20 from a read-only review of `main` @ `bc645cd` (with Armature `main` @ `45638f7`). Nothing here is implemented yet. Line numbers are from that commit and will drift.

## Done since (2026-11-07): the server's event and push paths

From a perf sweep of the server side, TEN-66. Every change keeps the same states, messages and stored progress.

- **A resolve builds what the index alone decides once per index.** The cap's map from each quest to its dependents, every dependency's target, and the exclusive groups were rebuilt on every resolve (`ProgressionEngine.IndexFacts`). The cap pass alone was over half of a resolve.
- **Kills and breaks check cheapest first.** `onEntityDeath` and `onBlockBroken` resolved the whole graph before the pure checks that turn most events away. They now ask for a resolution only when a site would count, and take the tick's cached one while it is current (`ProgressService.resolutionFor`).
- **The handlers no longer hash chapters.** They kept the quests an event moved in a map keyed by `QuestEntry`, a record that holds its whole chapter, so every lookup hashed every quest in that chapter: 0.1 to 0.16 ms per open site per event on a 960-quest chapter. Keyed by quest id now.
- **Event pushes wait for the player tick.** A counted kill or break pushed straight away, so a grinder sweep or a vein miner's burst built a whole push per event. They are collected in `EventPushes` and sent with the tick's own push, one per team. The client hears at most one tick later.
- **A push encodes the team's text once.** Every quest was encoded again for every member (`QuestSync.TeamText`), and a push now takes the tick's cached resolution when it still describes the progress.
- **Snapshots and the index's quest lookup are hash maps.** `Map.copyOf` probes linearly, and ids that differ only in a trailing number cluster in it: looking up every id of a 960-quest pack of numbered ids took 0.11 to 0.19 ms there against 5 us in a `HashMap`. The client's cache has the same shape (TEN-69).
- **`progressOwner` asks Armature 1.3.0's `TeamManager.teamIdOf`**, which names the team without building it.
- **Measured** with `PerfBenchTest` (`TENET_BENCH=1`, see its javadoc): a 960-quest pack shaped like the stress pack, a team part way through, warm JIT. Main and this change were run alternately, six runs of main and three of the final change; the ranges are the medians of those runs.

  | Scenario | Before | After |
  |---|---|---|
  | Resolve the quest graph | 553 to 782 us | 87 to 134 us |
  | Progress delta to one player, nothing moved | 848 to 1387 us | 437 to 575 us |
  | Push to a party of four | 3628 to 6049 us | 652 to 860 us |

  Not measured: the kill and break handlers themselves, which need a server. A death nothing counts no longer resolves or hashes anything, so it costs the site loop's pure checks.

## Done since (2026-10-31)

- **Part of item 5, the line primitive.** Armature's `GuiRenderer.hairline` takes a one-pixel route. On 26.3 a diagonal segment is one quad, and its shader keeps the same pixels the fills drew. Tenet sends a dependency line this way when it is thin and solid and the route is fewer calls than its pieces (`FrameEdge.hairlineOf`), which covers straight lines and the chamfered default's bevels. Heavier, broken and curved lines are still fills.
- **Line pieces outside the canvas are not sent.** Each edge keeps the box around its ink, so a line wholly off the canvas costs one test, and only a line crossing the canvas edge tests each piece.
- **Node icons in one pass.** When no two nodes on the canvas overlap, the icons are held back (`iconsLast`) and drawn after all the shapes, so the shapes stay one batch. Before, each icon split the batch, so N icons were about 2N GUI layers.
- **`entryFor` is indexed** (see Ruled out).
- **The world behind the book can freeze.** `config/tenet/backdrop.json` (`off`, `paused` by default, or `always`) keeps the last world frame and skips both the world's extract and its render while the book is open. See `docs/hud.md`.
- **Measured** with the Fabric client game test tour, on a flat test world at 1920x1080, as the median CPU frame time over 300 frames per scene. The scenes are three converted ATM10 chapters and the stress chapter, each at near and far zoom. The screenshots match the old build pixel for pixel, apart from animated icons.

  | Scene | Before | All five, freeze off | Freeze `always` |
  |---|---|---|---|
  | Productive Bees, near | 925 µs | 1009 µs | 946 µs |
  | Productive Bees, far | 856 µs | 864 µs | 826 µs |
  | Eternal Starlight, near | 1536 µs | 1351 µs | 1183 µs |
  | Eternal Starlight, far | 1438 µs | 1455 µs | 1309 µs |
  | Mystical Agriculture, near | 1502 µs | 1303 µs | 1114 µs |
  | Mystical Agriculture, far | 1708 µs | 1446 µs | 1376 µs |
  | Stress, near | 6608 µs | 4449 µs | 4466 µs |
  | Stress, far | 5031 µs | 4886 µs | 4667 µs |

  Run to run noise is about 10%. The default `paused` mode changed nothing in this run, because the book only pauses the game when the pack turns on `pauseGame`. A flat world is cheap to draw, so the freeze saves more in a real modpack world than it does here.

## Step 0: find out which lag it is

Profile before changing anything.

- Low FPS with the quest book open: the client items below apply. The Vitals overlay already counts fills.
- Server TPS drop: the server items apply. Take a spark profile.

## Order of work

1. **Per-frame geometry snapshot** (cheap, low risk, medium payoff)
   - `viewport()` (`QuestBookScreen.java:5928`) calls `geometry()` 6 times.
   - Each `geometry()` builds two `SidebarState` objects and runs two permission checks (`:294`, `:322`, `DevMode:382`, `QuestAuthority:47`).
   - The stamp cache key alone costs about 23 `geometry()` calls.
   - The worst case is `nodeAt`, which runs every frame over every quest (`:27338`, `:29192`) and calls `viewport()` 3 times per node. That's about 18 `geometry()` calls per quest per frame, and the same per node on every re-stamp.
   - Fix: compute geometry, viewport, mayEdit and the effective chapter once at the top of `extractRenderState` and pass them down. Switch `nodeAt` to the already-built `frameNodes`. Invalidate on resize and on sidebar drag/peek changes.

2. **Stop full widget rebuilds on progress** (medium payoff, removes hitches)
   - `sidebarStale()` (`:4525-4531`) includes `progressRevision`, and `renderBook` calls `rebuildWidgets()` when it changes (`:26056-26057`).
   - Every progress message bumps it, including item-count ticks and lock refreshes (`ClientQuestCache:2677`, `:3440`; `QuestSync:1935-1956`).
   - Fix: give the sidebar structure, the badges and node states, and the canvas their own staleness.
   - Careful: gated chapters show and hide on progress (javadoc `:4512`). A progress change must still rebuild the rows when chapter visibility actually changes, or the rows and the click targets will disagree.

3. **Don't re-stamp on pan; re-stamp less on zoom** (medium for pan, high for zoom)
   - The `CanvasState` key includes scale, offsetX and offsetY (`:28201-28204`).
   - Panning is already partly cached: `drawStyledPath` uses `fillsRelative` (`:28824-28833`, `LineArt:430`). Zoom, including the zoom glide, re-walks every line.
   - Fix: drop the offsets from the key and apply pan as a whole-pixel translate at draw time. During zoom, re-stamp only when the zoom settles or the LOD tier changes.
   - Do not use a non-integer scale transform. It blurs hairlines, breaks pixel-exact dashes, scales the text, and changes the LOD and node-size floors (`nodeSize` max 12, `:5961`).
   - Also fix the FILLS cache. It is cleared completely when it hits 4096 entries (`LineArt:411`, `:530`), which can thrash when zooming with many edges. Use LRU eviction, or key it by zoom tier.

4. **Server: stop resolving the whole graph every tick** (low to medium payoff unless there are many quests or teams)
   - `PLAYER_TICK` (`Tenet.java:208-225`) leads to `ProgressService.tick`, which is deduped to once per game tick (`:340`) but runs for every team.
   - `evaluateTeam` builds `asksFor` (`:2626`) and calls `ProgressionEngine.resolve` (`:686`), which makes several full passes over every quest (`ProgressionEngine:130-178`).
   - `isDue` (`:761`) only runs after the playable, satisfied and unlocked checks, `behaviourOf` and `chapterOf`.
   - Fix: a time-ordered due queue per team, plus a cached resolution.
   - The cache must be invalidated on progress changes, reloads and party changes, and on time too, since cooldowns use `now`.
   - Related: `refreshLocks` computes `lockView` for every player once a second. Make that change-driven as well.

5. **Real line primitive in Armature** (biggest job, high payoff at high zoom with many curves)
   - `LineArt` has no line primitive (`LineArt.java:25-28`). `mergeRows` (`:619-621`, `:867-876`) merges straight runs, but curves and diagonals still become about one fill per staircase step.
   - On 26.x, Armature's `batched()` is a pass-through (`GuiGraphicsRenderer.java:41-43, 114-116`).
   - Each vanilla fill allocates a `Matrix3x2f` and a `ColoredRectangleRenderState`, then goes through `GuiRenderState.addGuiElement`, and vanilla sorts the elements. The comment at `QuestBookScreen.java:27304` notes tens of thousands of fills per chapter at 2.2x.
   - Fix: a custom `GuiElementRenderState` that writes its own vertices (`buildVertices(VertexConsumer)`). A segment becomes one quad and a curve about 20.
   - This needs a new method on Armature's `GuiRenderer`, because `GuiGraphicsExtractor.guiRenderState` is private. That means a Fabric access widener plus a NeoForge access transformer, and updates to `RecordingRenderer`, `CountingRenderer` and the 1.21.1 port.
   - The tone and conduit shading (`LineArt:541`, `:751`) and the dash chips have to be redone, because rotated quads won't match the pixel staircase look. Edge hover is safe, because it uses distance to the path (`frameCandidates` `:28813`).
   - A bigger alternative is rendering the static layer into an offscreen texture via `addPicturesInPictureState`. That needs a picture-in-picture renderer on each loader, plus clip and theme handling. It's a lot of effort.

6. **Minor**: progress sync is JSON in a byte array (`ProgressSyncPayload:57,78`, parsed at `ClientQuestCache:3325`). A binary codec would be cheaper for big packs, but it isn't a priority.

## Ruled out

- **Huge methods not getting JIT-compiled.** Measured with Temurin 25.0.4: the class has 1,348 methods and none reaches the 8000-byte HotSpot limit. The biggest are `mouseClicked` 4158, `stampCanvas` 1798, `keyPressed` 1486, `extractRenderState` 1339 and `drawCanvasContents` 934, and nothing in `common` is over 8000.
- Splitting `QuestBookScreen` (38,016 lines, mostly comments) is worth doing for maintainability, not speed.
- **`entryFor` stream scans.** Ruled out on 2026-10-20 as hover-only, but an open quest panel asks for it many times a frame (a rail's width depends on the quest), and on a pack of a few thousand quests the scans were about a tenth of the frame. So it is now indexed: `ClientQuestCache.named` looks ids and aliases up in a map with folded keys, built with the id index. It keeps the first-match rule and answers exactly what `equalsIgnoreCase` would. A `#tag` still scans.

## Build note

`gradle.properties:113` hardcodes `org.gradle.java.home` to a Windows path, and Armature 1.0.0 only resolved after publishing it to Maven local. Both get in the way of building anywhere other than this PC.