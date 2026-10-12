# JSON `clickEvent` research (the legacy mod 1.21.1 · vanilla 1.21.1 · Tenet)

> [!NOTE]
> **A research note, not the manual.** Sections 1–3 record what the legacy mod and vanilla 1.21.1 do,
> which still stands. Sections 4–5 describe Tenet before JSON clicks were implemented: a whole
> JSON paragraph now keeps its colours, emphasis, `open_url` links and `change_page` quest refs
> instead of dropping them. For what the reader's card does today, see
> [[tenet:authoring/quests#rich-text-codes]].

Primary sources only: the legacy mod's `1.21.1/main` GitHub source, local legacy jars
(`ftb-quests-neoforge-2101.1.34.jar`, `ftb-library-neoforge-2101.1.35.jar`) via
`javap -c`, Mojang-mapped 1.21.1 merged jar in `tenet/.gradle/loom-cache` via
`javap -c`, and Tenet/Armature sources below.

## 1. the legacy mod: raw JSON paragraphs keep real `Component`s

- `Quest.getDescription()` parses each raw line with `TextUtils.parseRawText`
  (`Quest.java` → `getDescription()`; `TextUtils.java` → `parseRawText`).
- `parseRawText`: trimmed `{…}`/`[…]` → vanilla
  `Component.Serializer.fromJson(UNESCAPER.translate(s), provider)`; anything
  else (or JSON failure) → legacy `ClientTextComponentUtils.parse`
  (`TextUtils.java` → `parseRawText`). So a JSON paragraph keeps
  `color`/`underlined`/`clickEvent` as a real `Style`; legacy codes never
  produce click events.
- Render: `ViewQuestPanel.addDescriptionText(boolean, Component)` shows the
  current `{@pagebreak}` page (`pageIndices`/`getCurrentPage`), one
  `QuestDescriptionField` (legacy `TextField`) per `Component` via
  `setText(component)`; image-contents go to `ImageComponentWidget`
  (`ViewQuestPanel.java` → `addDescriptionText`, `findImageComponent`).
  legacy pages are description-line ranges (`Quest.java` → `buildDescriptionIndex`).

## 2. legacy click handling: `change_page` means *quest id*, not page

- Press path: `QuestDescriptionField.mousePressed` → `getComponentStyleAt(…)`
  → `handleCustomClickEvent(style)` first, else vanilla
  `Screen.handleComponentClicked(style)`
  (`ViewQuestPanel.java` → `QuestDescriptionField.mousePressed`).
- `handleCustomClickEvent` (`ViewQuestPanel.java` → inner `QuestDescriptionField`):
  - `CHANGE_PAGE` → `new ImageClickAction(OPEN_QUEST, value).run()`.
  - `OPEN_URL` + value starts `docs:` → `SHOW_DOCS(value.substring(5))`, else
    `OPEN_URI(value)`; exception → `errorToPlayer("Can't open url for %s (%s)")`.
  - Anything else returns false → vanilla dispatch runs.
- `ImageClickAction.openQuest` (`ImageClickAction.java` → `openQuest`): split
  value on `/`; `parseHexId(fields[0])`; unknown → `Unknown quest object id`,
  unparsable → `Invalid quest object id`; numeric `fields[1]` + viewed quest →
  `viewQuestPanel.setCurrentPage(questId, parseInt−1)`, then
  `questScreen.open(qo, false)`. Value = legacy code string `%016X` uppercase hex
  (`QuestObjectBase.getCodeString`, `.ftbq-src/QuestObjectBase.java:82-84`),
  optionally `HEXID/1-based-page`.
- Editor proof: `MultilineTextEditorScreen.doLinkInsertion` inserts
  `{"text": "%s", "underlined": true, "clickEvent": {"action": "%s",
  "value": "%s"}}`; quest-link dialog passes `change_page` +
  `getCodeString(quest.id)`, URL dialog passes `open_url` (jar:
  `lambda$openQuestLinkInsertScreen$10`, `lambda$openTextLinkInsertScreen$11`).
- Browser path: `ActionType.OPEN_URI` consumer = `FTBQuestsClient.openUri`
  (`ImageClickAction.java` → `ActionType`): `URI.create` → if `chatLinksPrompt`
  show `ConfirmLinkScreen(cb, url, trusted=false)`, else
  `Util.getPlatform().openUri` directly; confirm restores prior screen (jar
  `FTBQuestsClient` → `openUri`, `lambda$openUri$1`). No legacy scheme allowlist,
  because the vanilla prompt is the gate.

## 3. Vanilla 1.21.1 vocabulary and `change_page` page semantics

- Actions (`ClickEvent$Action`, merged jar): `OPEN_URL`, `OPEN_FILE`,
  `RUN_COMMAND`, `SUGGEST_COMMAND`, `CHANGE_PAGE`, `COPY_TO_CLIPBOARD`;
  serialised `open_url`/`open_file`/`run_command`/`suggest_command`/`change_page`/
  `copy_to_clipboard`; `allowFromServer` true for all **except** `OPEN_FILE`.
- `Screen.handleComponentClicked` (merged jar → `Screen`): shift+insertion →
  chat insert; `OPEN_URL` gated on `chatLinks` → `Util.parseAndValidateUntrustedUri`
  → `chatLinksPrompt` ? `ConfirmLinkScreen(cb, value, trusted=false)` : direct
  `openUri` (failure logs `Can't open url for {}`); `OPEN_FILE` → `openFile`;
  `SUGGEST_COMMAND` → `insertText(filterText, true)`; `RUN_COMMAND` must start
  `/` → `sendUnsignedCommand(rest)` else error log; `COPY_TO_CLIPBOARD` →
  `keyboardHandler.setClipboard`; unknown logs `Don't know how to handle {}`.
- `Util.parseAndValidateUntrustedUri` (merged jar → `Util`): scheme must be in
  `ALLOWED_UNTRUSTED_LINK_PROTOCOLS` = **`http`, `https`** only, else
  `URISyntaxException`.
- `BookViewScreen.handleComponentClicked` override (merged jar →
  `BookViewScreen`): `CHANGE_PAGE` → `forcePage(Integer.parseInt(value) − 1)`,
  parse failure → false; else super (closes book if super ran `RUN_COMMAND`).
  `setPage` clamps with `Mth.clamp(0, pageCount−1)`. **Vanilla value = 1-based
  decimal page-number string; garbage is ignored, out-of-range clamps.**
- Crux: the legacy mod reuses the `change_page` *action name* with **quest-id values**;
  vanilla books use it with **page-index values**. Tenet must implement the legacy mod's
  reading.

## 4. Tenet today: JSON keeps words, drops everything else

- `RichText.parse` (`tenet/.../client/RichText.java:505`): whole-string JSON →
  `asComponent` (`:797`) → single `JsonText(raw, fallback)`; `flatten` (`:833`)
  keeps only `text`/`translate`/extra words, dropping `color`, `underlined`,
  `clickEvent`, `hoverEvent`.
- **Drop point: `appendRendered` JsonText arm (`RichText.java:211-212`):**
  `span(residual, spans, json.fallback(), WHITE, "", false, false, false)`: the
  `link` arg is hardcoded `""`, so no span is ever clickable and ink is white.
- `Span(start, end, argb, link, bold, italic, underline)` (`:112`); `link` is a
  URL-or-empty; `spanAt` (`:235`). `OpenUrl` `{open_url:}` segments do populate
  `link` (`:204-205`), but JSON never reaches that path.
- Draw (`QuestBookScreen.java:26949-26976`): `link = piece.link() ?? look.link()`,
  underline when linked, `linkRects.add(new LinkRect(box, link))` (`:26976`).
  `LinkRect(box, url)` (`:589`); `linkRects` (`:603`); `pressedLink` (`:606`).
- Press `mouseClicked` (`:28048-28055`, button 0 + QUEST panel + not editing);
  release (`:29208-29216`) → `openLink(url)`; hover URL tooltip
  `drawLinkTarget` (`:22616-22623`).
- `openLink` (`:7827-7842`): **http/https only**, else status message, then
  `Util.getPlatform().openUri`, which matches vanilla's scheme rule, minus prompt.
- Markdown `[label](url)` also lands here via `RichText.Piece.link`
  (`armature/.../ui/kit/RichText.java:84`, read at `:26952`).
- Images already solve the quest-open half: `ClickAction` 7 types
  (`tenet/.../quest/ClickAction.java:29-74`); `pressElement` (`:23821-23858`)
  `OPEN_QUEST` → `entryFor` (`:4474`) → `cacheEntryFor` (`:4479`, id/alias,
  case-insensitive; `ClientQuestCache.Entry.matches`, `ClientQuestCache.java:581-603`)
  → `selectedQuest` + `openOverlay`; `OPEN_URI` reuses `openLink`.
- Pinned by test: `RichTextTest.java:245-247` (`{open_url:}` span link),
  `:259` (nothing else opens), `:267-269` (component → fallback words).

## 5. Recommended approach

1. **Parse, don't flatten, two actions.** In `asComponent`/`flatten`, extract
   per-node `(action, value)` alongside words: support `open_url` → URL and
   `change_page` → quest ref; keep flattening `run/suggest/copy/open_file` to
   plain words (no chat box, commands, or clipboard role in a quest book).
2. **Carry it on the span.** Widen `Span` with a `click` (e.g.
   `Url(url)` / `Quest(ref)`), populated from JSON nodes (and map JSON `color`
   incl. `#hex` → `Span.argb`, so JSON links look underlined+coloured like the legacy reader).
   `emitWords` (`:26850-26875`) rebases spans already, so no change needed there.
3. **Dispatch beside `LinkRect`.** Widen the rect to carry the click (URL vs
   quest): URL → existing `openLink` http/https gate; quest → `entryFor(ref)`
   + the `pressElement` `OPEN_QUEST` open path (`:23827-23838`), same
   unknown-id status on miss. Keep press-and-release-on-same-rect + tooltip.
4. **Map `change_page` values at conversion.** legacy values are `%016X` hex
   (`HEXID[/1-based-page]`); Tenet ids are lowercase strings remapped by the
   converter (`docs/authoring/legacy-mapping.md:97`). Rewrite values through that
   same id table; drop the `/page` suffix (Tenet prose scrolls, so no pages).
   Runtime `entryFor` already resolves id/alias case-insensitively, so it also
   accepts hand-written Tenet ids.
5. **Document the gate:** JSON clicks only work when the *whole paragraph* is
   one component (`asComponent` whole-string rule, `:505-510`); other actions
   degrade to their visible words, matching today's fallback.
