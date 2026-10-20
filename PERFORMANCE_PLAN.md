# Tenet performance plan

Written 2026-10-20 from a read-only review of `main` @ `bc645cd` (with Armature `main` @ `45638f7`). Nothing here is implemented yet. Line numbers are from that commit and will drift.

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
- **`entryFor` stream scans.** About 50 call sites, but in the render path it only runs on hover, drag or tooltip, so it's cheap. If it is ever indexed, keep the case-insensitive alias and `#tag` matching and the first-match rule, because `ClientQuestCache.entry()`/`byId()` is exact-case only.

## Build note

`gradle.properties:113` hardcodes `org.gradle.java.home` to a Windows path, and Armature 1.0.0 only resolved after publishing it to Maven local. Both get in the way of building anywhere other than this PC.