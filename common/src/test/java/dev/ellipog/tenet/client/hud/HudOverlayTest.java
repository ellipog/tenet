package dev.ellipog.tenet.client.hud;

import dev.ellipog.armature.client.ArmatureTheme;
import dev.ellipog.armature.client.ui.kit.Colour;
import dev.ellipog.armature.client.ui.kit.Measure;
import dev.ellipog.tenet.client.BookGeometry;
import dev.ellipog.tenet.client.ClientQuestCache;
import dev.ellipog.tenet.client.ToastArt;
import dev.ellipog.tenet.client.ToastStack;
import dev.ellipog.tenet.client.render.RecordingRenderer;
import dev.ellipog.tenet.net.QuestSync;
import dev.ellipog.tenet.quest.Fixtures;
import dev.ellipog.tenet.quest.MinecraftTestBootstrap;
import dev.ellipog.tenet.quest.QuestIndex;

import net.minecraft.network.chat.Component;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The HUD's own two elements, drawn through a recording renderer.
 *
 * <h2>Why these are drawn rather than reasoned about</h2>
 *
 * <p>Because the interesting faults here are visible only in the calls: a box that measured one rect and
 * blurred another, a fill drawn where the world should show through, a notice row drawn after it had
 * faded, a sample that stopped being drawn the moment nobody had pinned anything — which is the one
 * arrangement where an element cannot be found <i>or</i> grabbed. {@code RecordingRenderer} is the second
 * implementation of the drawing seam, so all of them are assertable with no client at all.
 *
 * <h2>What it cannot see</h2>
 *
 * <p>Whether any of it reads well: the colours are the theme's, the glyphs are the font's, and this renderer
 * fixes a character at six pixels. That is a screenshot's job, and {@code TESTING.md} says so. It also
 * cannot see an actual blur -- the recorder answers the call without softening anything -- so what is
 * asserted is <i>where</i> the blur was asked for and in what order, which is the half of the contract
 * that decides whether the right pixels go soft in game.
 */
@DisplayName("the HUD's own drawing")
class HudOverlayTest {

    /** The same metric the recording renderer reports, so a measured box and a drawn one can be compared. */
    private static final Measure FONT = Measure.monospace(6, 10);

    /** A time, so that "on screen" and "faded" are two different numbers rather than two clocks. */
    private static final long NOW = 1_000L;

    private static final String A = """
            {"id": "a", "title": "Punch a tree", "tasks": [
              {"type": "tenet:item", "item": "minecraft:oak_log", "count": 8}]}
            """;
    private static final String B = """
            {"id": "b", "title": "Craft a table", "dependsOn": ["a"], "tasks": [
              {"type": "tenet:checkmark", "title": "Say hello"}]}
            """;
    private static final String C = """
            {"id": "c", "title": "Claim the axe", "tasks": [
              {"type": "tenet:checkmark", "title": "done"}],
             "rewards": [
              {"type": "tenet:item", "item": "minecraft:wooden_axe", "count": 1}]}
            """;
    private static final String KILL = """
            {"id": "k", "title": "The Hunt", "tasks": [
              {"type": "tenet:kill", "entity": "minecraft:cow", "value": 8}]}
            """;

    /** The player these hide assertions collect as. Fixed, so a claim names somebody. */
    private static final UUID ME = UUID.fromString("12345678-1234-1234-1234-1234567890ab");

    @BeforeAll
    static void bootstrap() {
        MinecraftTestBootstrap.boot();
    }

    @BeforeEach
    @AfterEach
    void forget() {
        HudOverlay.clear();
        PinnedQuests.reset();
        HudSettings.reset();
        ClientQuestCache.clear();
    }

    private static void accept(String... quests) {
        QuestIndex index = Fixtures.indexOf(Fixtures.file(quests));
        ClientQuestCache.acceptTree(index.questCount(), index.chapterCount(), QuestSync.treeAsJson(index));
    }

    /** A progress message that finishes the first task of {@code a}, which asks for eight of something. */
    private static void finishTheFirstTask() {
        ClientQuestCache.acceptProgress(java.util.UUID.randomUUID(), 100L,
                "{\"quests\":{\"a\":{\"state\":\"STARTED\",\"tasks\":[8]}}}"
                        .getBytes(StandardCharsets.UTF_8), 50L);
    }

    private static BookGeometry.Rect boxOf(HudElement element, int width, int height) {
        HudOverlay.Size size = HudOverlay.size(element, FONT, HudOverlay.Face.LIVE, NOW);
        return HudLayout.boxAt(element, width, height,
                HudSettings.x(element), HudSettings.y(element), size.width(), size.height());
    }

    /** Everything a recorder drew, for a failure message: which of these is missing is the whole question. */
    private static String drawn(RecordingRenderer r) {
        return r.calls().toString();
    }

    @Test
    @DisplayName("the pinned boxes draw nothing live until something is pinned, and a sample in the editor")
    void thePanelIsEmptyUntilSomethingIsPinned() {
        RecordingRenderer live = RecordingRenderer.create();
        HudOverlay.paint(HudElement.PINNED_QUESTS, live, BookGeometry.Rect.at(4, 4, 100, 20), FONT,
                HudOverlay.Face.LIVE, NOW);
        assertTrue(live.calls().isEmpty(), "an element with nothing to hold is not an empty frame");

        RecordingRenderer editor = RecordingRenderer.create();
        HudOverlay.paint(HudElement.PINNED_QUESTS, editor, BookGeometry.Rect.at(4, 4, 100, 20), FONT,
                HudOverlay.Face.EDITOR, NOW);
        assertFalse(editor.shadowedTexts().isEmpty(),
                "and in the editor it has a surface to be grabbed by: " + drawn(editor));
        // A prefix rather than the whole sentence, and the reason is the box's own rule: the sample is longer
        // than the box can be, so what is drawn is a truncated form of it. Asserting the whole string would be
        // asserting that the box does *not* do the one thing it must -- and whether the sentence resolves to
        // English or to its own key here depends on whether a language was loaded, so the prefix is the half
        // that is the same either way.
        String sample = Component.translatable("tenet.hud.pinned_sample").getString();
        String opening = sample.substring(0, Math.min(8, sample.length()));
        assertTrue(editor.shadowedTexts().stream().anyMatch(call -> call.text().startsWith(opening)),
                "and a sentence saying why it is empty, beginning " + opening + ": " + drawn(editor));
    }

    @Test
    @DisplayName("each box dims its own rect, at the slider's strength")
    void eachBoxDimsItsOwnRect() {
        accept(A, B);
        PinnedQuests.pin("a");
        PinnedQuests.pin("b");

        RecordingRenderer r = RecordingRenderer.create();
        HudOverlay.render(r, 640, 480, NOW);

        // The dim the slider ships at, through the same alpha helper the painter uses: what is asserted
        // is the wiring and the rect, not the theme's own numbers.
        int wash = Colour.alphaOf(ArmatureTheme.raised(), (float) HudSettings.dim(HudElement.PINNED_QUESTS));
        List<RecordingRenderer.Call> washes = r.calls().stream()
                .filter(call -> call.op() == RecordingRenderer.Op.FILL && call.argb() == wash).toList();
        assertEquals(2, washes.size(), "one dim fill per box, not one for the stack: " + drawn(r));

        BookGeometry.Rect stack = boxOf(HudElement.PINNED_QUESTS, 640, 480);
        // The two boxes tile the stack with a gap between them: the second starts after the first plus
        // BOX_GAP, and neither overlaps the other.
        RecordingRenderer.Call first = washes.get(0);
        RecordingRenderer.Call second = washes.get(1);
        assertEquals(stack.x(), first.x(), "the first box starts the column");
        assertEquals(stack.width(), first.x2() - first.x(), "and spans it");
        assertTrue(second.y() >= first.y2() + PinnedPanelLayout.BOX_GAP, "with a gap between the two");
        assertTrue(second.y2() <= stack.y() + stack.height(), "and both inside the measured stack");
    }

    @Test
    @DisplayName("dim zero draws edge only: the transparent look stays reachable")
    void dimZeroDrawsEdgeOnly() {
        accept(A);
        PinnedQuests.pin("a");
        HudSettings.setDim(HudElement.PINNED_QUESTS, 0.0);

        RecordingRenderer r = RecordingRenderer.create();
        HudOverlay.render(r, 640, 480, NOW);

        BookGeometry.Rect stack = boxOf(HudElement.PINNED_QUESTS, 640, 480);
        int centreX = stack.x() + stack.width() / 2;
        int centreY = stack.y() + stack.height() / 2;
        assertFalse(r.covered(centreX, centreY),
                "a zero-alpha fill would be a submission that draws nothing: " + drawn(r));
        assertTrue(r.covered(stack.x(), stack.y()),
                "while its own top-left corner is still the edge ink: " + drawn(r));
        assertTrue(r.shadowedTexts().stream().anyMatch(call -> call.text().equals("Punch a tree")),
                "and the name is still drawn: " + drawn(r));
    }

    @Test
    @DisplayName("the notices dim through the same slider, without changing the book's stack")
    void noticesDimThroughTheSlider() {
        HudOverlay.notice("Task done: Oak Log", false, NOW);
        HudSettings.setDim(HudElement.NOTIFICATIONS, 0.5);

        // Drawn settled rather than on the frame it was said: the arrival's own fade is `ToastStack`'s
        // arithmetic to assert, and here it would only multiply the wash this test is actually about.
        long settled = NOW + ToastStack.FADE_IN_MILLIS + 1;
        RecordingRenderer r = RecordingRenderer.create();
        HudOverlay.render(r, 320, 240, settled);

        // The fade is full once it has landed whatever motion says, so the expected wash is the theme's
        // raised surface at half strength -- computed the way the painter computes it. It sits one pixel
        // inside the box, because `ToastArt` draws a panel and a panel is a border footprint with its
        // fill inset; the corner fill is the edge ink, so matching the corner would be asserting the
        // wrong color.
        int wash = Colour.alphaOf(ArmatureTheme.raised(), 0.5F);
        BookGeometry.Rect box = boxOf(HudElement.NOTIFICATIONS, 320, 240);
        int centreX = box.x() + box.width() / 2;
        int centreY = box.y() + box.height() / 2;
        assertTrue(r.calls().stream().anyMatch(call -> call.op() == RecordingRenderer.Op.FILL
                        && call.argb() == wash && call.covers(centreX, centreY)),
                "the notice box dims with its slider: " + drawn(r));
    }

    @Test
    @DisplayName("every glyph on a pin box is shadowed, because there is no backdrop behind it")
    void pinTextIsShadowed() {
        accept(A, B);
        PinnedQuests.pin("a");
        PinnedQuests.pin("b");

        RecordingRenderer r = RecordingRenderer.create();
        HudOverlay.render(r, 640, 480, NOW);

        assertTrue(r.calls().stream().noneMatch(call -> call.op() == RecordingRenderer.Op.TEXT),
                "a plain glyph on a transparent box is unreadable -- which is what shadowedText is for: "
                        + drawn(r));
        assertTrue(r.shadowedTexts().stream().anyMatch(call -> call.text().equals("Punch a tree")),
                "the quest's name is drawn, with a shadow: " + drawn(r));
    }

    @Test
    @DisplayName("the notice element has no box until there is something to say, and a sample in the editor")
    void theNoticesHaveNoSizeUntilTheySpeak() {
        assertTrue(HudOverlay.size(HudElement.NOTIFICATIONS, FONT, HudOverlay.Face.LIVE, NOW).empty(),
                "nothing to say is nothing to draw");

        HudOverlay.Size editor = HudOverlay.size(HudElement.NOTIFICATIONS, FONT, HudOverlay.Face.EDITOR, NOW);
        assertFalse(editor.empty(), "and the editor still has a box to grab");
        assertEquals(ToastArt.height(1), editor.height(), "one sample row");
        assertTrue(editor.width() >= ToastArt.MIN_WIDTH && editor.width() <= ToastArt.MAX_WIDTH,
                "as wide as the sentence needs, inside the notice's own bounds: " + editor.width());

        RecordingRenderer r = RecordingRenderer.create();
        HudOverlay.paint(HudElement.NOTIFICATIONS, r, BookGeometry.Rect.at(4, 4, editor.width(), editor.height()),
                FONT, HudOverlay.Face.EDITOR, NOW);
        assertTrue(r.drewText(Component.translatable("tenet.hud.notifications_sample").getString()));
    }

    @Test
    @DisplayName("a notice gets a box and is drawn in it, and it is drawn where the player put it")
    void aNoticeIsDrawnWhereTheElementIs() {
        HudOverlay.notice("Task done: Oak Log", false, NOW);

        HudOverlay.Size size = HudOverlay.size(HudElement.NOTIFICATIONS, FONT, HudOverlay.Face.LIVE, NOW);
        assertFalse(size.empty(), "a sentence is a box");
        assertEquals(ToastArt.height(1), size.height());

        // Settled: on its first frame the notice is still climbing its arrival rise (see `ToastArt`),
        // so the resting position is what a later frame draws.
        long settled = NOW + ToastStack.FADE_IN_MILLIS + 1;
        RecordingRenderer r = RecordingRenderer.create();
        HudOverlay.render(r, 320, 240, settled);
        assertTrue(r.drewText("Task done: Oak Log"), "the sentence is the notice");

        BookGeometry.Rect box = boxOf(HudElement.NOTIFICATIONS, 320, 240);
        RecordingRenderer.Call drawn = r.callFor("Task done: Oak Log");
        assertEquals(box.x() + ToastArt.PAD, drawn.x(), "the row is drawn inside the box the size was for");
        assertEquals(box.y() + (ToastArt.LINE - 8) / 2, drawn.y());
    }

    @Test
    @DisplayName("a notice that has had its time stops being drawn, and stops being a box")
    void aFadedNoticeIsGone() {
        HudOverlay.notice("Task done: Oak Log", false, NOW);

        long later = NOW + ToastArt.height(1) + 10_000L;
        assertTrue(HudOverlay.size(HudElement.NOTIFICATIONS, FONT, HudOverlay.Face.LIVE, later).empty(),
                "a notice older than its life is not on screen");
        RecordingRenderer r = RecordingRenderer.create();
        HudOverlay.render(r, 320, 240, later);
        assertFalse(r.drewText("Task done: Oak Log"));
    }

    @Test
    @DisplayName("clear forgets everything, faded or not")
    void clearForgets() {
        HudOverlay.notice("Task done: Oak Log", false, NOW);
        assertEquals(1, HudOverlay.held());

        HudOverlay.clear();
        assertEquals(0, HudOverlay.held(), "a disconnect does not wait for a timer");
        RecordingRenderer r = RecordingRenderer.create();
        HudOverlay.render(r, 320, 240, NOW);
        assertTrue(r.calls().isEmpty(), "and nothing of the world just left is drawn in the next one");
    }

    @Test
    @DisplayName("a switched-off element is not drawn at all")
    void aSwitchedOffElementIsNotDrawn() {
        HudSettings.setOn(HudElement.NOTIFICATIONS, false);
        HudOverlay.notice("Task done: Oak Log", false, NOW);

        RecordingRenderer r = RecordingRenderer.create();
        HudOverlay.render(r, 320, 240, NOW);
        assertFalse(r.drewText("Task done: Oak Log"), "the switch is the way back to a vanilla toast");
    }

    @Test
    @DisplayName("the control element is not the HUD's to draw, in either face")
    void theControlIsNotDrawnHere() {
        for (HudOverlay.Face face : HudOverlay.Face.values()) {
            RecordingRenderer r = RecordingRenderer.create();
            HudOverlay.paint(HudElement.INVENTORY_BUTTON, r, BookGeometry.Rect.at(2, 2, 16, 16), FONT, face,
                    NOW);
            assertTrue(r.calls().isEmpty(), "the button draws itself on the screen that owns it, in " + face);
            assertEquals(HudElement.INVENTORY_BUTTON.width(),
                    HudOverlay.size(HudElement.INVENTORY_BUTTON, FONT, face, NOW).width(),
                    "and its size is the constant in the table");
        }
    }

    @Test
    @DisplayName("a pinned quest draws its name, its task and its count, in its own box")
    void aPinnedQuestIsDrawnInFull() {
        accept(A, B);
        PinnedQuests.pin("a");

        RecordingRenderer r = RecordingRenderer.create();
        HudOverlay.render(r, 640, 480, NOW);

        assertTrue(r.shadowedTexts().stream().anyMatch(call -> call.text().equals("Punch a tree")),
                "the quest's name: " + drawn(r));
        assertTrue(r.shadowedTexts().stream().anyMatch(call -> call.text().equals("0 / 8")),
                "its task's count, clamped to the target: " + drawn(r));

        // The task's own sentence, taken from the accessor the painter reads -- which is the claim: there is
        // one derivation of what a task row says, and the HUD's is the same one the book's rows use. What it
        // resolves to in this test is the item's translation key, because nothing here has a language file.
        String sentence = ClientQuestCache.entry("a").tasks().get(0).text().getString();
        assertTrue(r.shadowedTexts().stream().anyMatch(call -> call.text().equals(sentence)),
                "the task's own sentence: " + sentence);
    }

    @Test
    @DisplayName("an untouched task draws an empty grey track and no fill")
    void untouchedTasksDrawAnEmptyTrack() {
        accept(A);
        PinnedQuests.pin("a");

        RecordingRenderer r = RecordingRenderer.create();
        HudOverlay.render(r, 640, 480, NOW);

        assertTrue(r.shadowedTexts().stream().anyMatch(call -> call.text().equals("0 / 8")),
                "the count is there: " + drawn(r));
        List<RecordingRenderer.Call> bars = r.calls().stream()
                .filter(call -> call.op() == RecordingRenderer.Op.FILL
                        && call.y2() - call.y() == PinnedPanelLayout.BAR_HEIGHT)
                .toList();
        assertEquals(1, bars.size(), "track alone, no fill: empty reads as empty: " + drawn(r));
        assertEquals(dev.ellipog.tenet.client.viewer.PagePalette.BAR_TRACK, bars.get(0).argb(),
                "the empty groove is the grey remainder, not the world showing through: " + drawn(r));
    }

    @Test
    @DisplayName("a kill task names the mob prettified, like the book does")
    void killTaskPrettifiesItsEntity() {
        accept(KILL);
        PinnedQuests.pin("k");

        RecordingRenderer r = RecordingRenderer.create();
        HudOverlay.render(r, 640, 480, NOW);

        assertTrue(r.shadowedTexts().stream().anyMatch(call -> call.text().equals("Kill Cow")),
                "minecraft:cow reads Cow: " + drawn(r));
        assertTrue(r.shadowedTexts().stream().noneMatch(call -> call.text().contains("minecraft:cow")),
                "and no raw id survives on the stack: " + drawn(r));
    }

    @Test
    @DisplayName("chapters share one right edge, whatever their titles' lengths")
    void chaptersAreRightAligned() {
        // Two chapters, because one chapter's pins carry no chapter at all -- see the test below.
        String file = """
                {"version": 1, "chapterGroups": [{"id": "group", "title": "Group", "chapters": [ %s, %s ]}]}
                """.formatted(Fixtures.chapter("north", A), Fixtures.chapter("southwest", B));
        QuestIndex index = Fixtures.indexOf(file);
        ClientQuestCache.acceptTree(index.questCount(), index.chapterCount(), QuestSync.treeAsJson(index));
        PinnedQuests.pin("a");
        PinnedQuests.pin("b");

        HudOverlay.Size size = HudOverlay.size(HudElement.PINNED_QUESTS, FONT, HudOverlay.Face.LIVE, NOW);
        BookGeometry.Rect stack = boxOf(HudElement.PINNED_QUESTS, 640, 480);

        RecordingRenderer r = RecordingRenderer.create();
        HudOverlay.render(r, 640, 480, NOW);

        int right = stack.x() + size.width() - PinnedPanelLayout.PAD;
        for (String chapter : List.of("north", "southwest")) {
            List<RecordingRenderer.Call> notes = r.shadowedTexts().stream()
                    .filter(call -> call.text().equals(chapter)).toList();
            assertEquals(1, notes.size(), "one chapter per box: " + drawn(r));
            assertEquals(right - FONT.width(chapter), notes.get(0).x(),
                    "the chapter ends on the content's right edge: " + drawn(r));
        }
    }

    @Test
    @DisplayName("pins from one chapter do not repeat its name on every box")
    void oneChapterIsNotRepeated() {
        accept(A, B);
        PinnedQuests.pin("a");
        PinnedQuests.pin("b");

        RecordingRenderer r = RecordingRenderer.create();
        HudOverlay.render(r, 640, 480, NOW);

        assertTrue(r.shadowedTexts().stream().noneMatch(call -> call.text().equals("Chapter")),
                "a column of the same word tells no pin apart: " + drawn(r));
    }

    @Test
    @DisplayName("a started task draws one hairline fill over a grey track, in the row's ink")
    void taskBarsAreHairlines() {
        accept(A, B);
        PinnedQuests.pin("a");
        PinnedQuests.pin("b");
        finishTheFirstTask();

        RecordingRenderer r = RecordingRenderer.create();
        HudOverlay.render(r, 640, 480, NOW);

        assertTrue(r.shadowedTexts().stream().anyMatch(call -> call.text().equals("8 / 8")),
                "a counter that reached its target: " + drawn(r));
        List<RecordingRenderer.Call> bars = r.calls().stream()
                .filter(call -> call.op() == RecordingRenderer.Op.FILL
                        && call.y2() - call.y() == PinnedPanelLayout.BAR_HEIGHT)
                .toList();
        assertEquals(2, bars.size(), "track and fill: the checkmark task has no count and so none: "
                + drawn(r));
        RecordingRenderer.Call track = bars.get(0);
        RecordingRenderer.Call bar = bars.get(1);
        assertEquals(dev.ellipog.tenet.client.viewer.PagePalette.BAR_TRACK, track.argb(),
                "the remainder is a grey track, not the world showing through: " + drawn(r));
        assertEquals(ArmatureTheme.complete(), bar.argb(), "a finished task wears the completion ink");
        assertEquals(track.x(), bar.x(), "the fill starts where the track does");
        assertTrue(track.x2() >= bar.x2(), "and the track is what is left when the fill ends");

        int textY = r.shadowedTexts().stream().filter(call -> call.text().equals("8 / 8")).findFirst()
                .orElseThrow(() -> new AssertionError("no count text: " + drawn(r))).y();
        assertTrue(bar.y() > textY, "the bar sits under the sentence it belongs to");
        assertTrue(bar.x2() - bar.x() > 1, "wider than a pixel: a finished task fills its bar");
    }

    @Test
    @DisplayName("a half-done task fills half its bar in the available ink, over the grey track")
    void taskBarFillsWithProgress() {
        accept(A);
        PinnedQuests.pin("a");
        ClientQuestCache.acceptProgress(java.util.UUID.randomUUID(), 100L,
                "{\"quests\":{\"a\":{\"state\":\"STARTED\",\"tasks\":[4]}}}"
                        .getBytes(StandardCharsets.UTF_8), 50L);

        RecordingRenderer r = RecordingRenderer.create();
        HudOverlay.render(r, 640, 480, NOW);

        List<RecordingRenderer.Call> bars = r.calls().stream()
                .filter(call -> call.op() == RecordingRenderer.Op.FILL
                        && call.y2() - call.y() == PinnedPanelLayout.BAR_HEIGHT)
                .toList();
        assertEquals(2, bars.size(), "track and fill: " + drawn(r));
        assertEquals(dev.ellipog.tenet.client.viewer.PagePalette.BAR_TRACK, bars.get(0).argb(),
                "the track first, full width: " + drawn(r));
        assertEquals(ArmatureTheme.available(), bars.get(1).argb(),
                "going, not done: " + drawn(r));
    }

    @Test
    @DisplayName("a new bar starts at its target, then glides when progress moves")
    void taskBarsGlideToNewProgress() {
        accept(A);
        PinnedQuests.pin("a");
        setProgress(2);

        // A quarter of the run, drawn the very first frame: no sweep up from zero.
        int first = barWidthAt(NOW);
        assertTrue(first > 1, "a visible bar at a quarter, not a glide from nothing: " + first);

        setProgress(6);
        int mid = barWidthAt(NOW + 100);
        assertTrue(mid > first, "the motion starts on the frame new data lands: " + mid + " > " + first);
        int settled = barWidthAt(NOW + 5000);
        assertTrue(mid < settled, "and it glides rather than jumps: " + mid + " < " + settled);
        assertTrue(Math.abs(settled - 3 * first) <= 2,
                "converged from a quarter to three quarters of the same run: " + settled);
    }

    /**
     * The 2px fill's drawn width at one moment, or -1 when no fill is drawn.
     *
     * <p>The track does not count: it is full width from the first frame, so measuring it would report
     * a converged bar while the fill is still gliding.
     */
    private static int barWidthAt(long now) {
        RecordingRenderer r = RecordingRenderer.create();
        HudOverlay.render(r, 640, 480, now);
        return r.calls().stream()
                .filter(call -> call.op() == RecordingRenderer.Op.FILL
                        && call.y2() - call.y() == PinnedPanelLayout.BAR_HEIGHT
                        && call.argb() != dev.ellipog.tenet.client.viewer.PagePalette.BAR_TRACK)
                .mapToInt(call -> call.x2() - call.x())
                .findFirst().orElse(-1);
    }

    /** Reports this client's progress for the fixture quest's first task, like the server would. */
    private static void setProgress(int n) {
        ClientQuestCache.acceptProgress(java.util.UUID.randomUUID(), 100L,
                ("{\"quests\":{\"a\":{\"state\":\"STARTED\",\"tasks\":[" + n + "]}}}")
                        .getBytes(StandardCharsets.UTF_8), 50L);
    }

    @Test
    @DisplayName("a finished task wears the tick, and the row is drawn either way")
    void aFinishedTaskWearsATick() {
        accept(A, B);
        PinnedQuests.pin("a");

        RecordingRenderer before = RecordingRenderer.create();
        HudOverlay.render(before, 640, 480, NOW);
        assertFalse(before.shadowedTexts().stream()
                        .anyMatch(call -> call.text().equals(PinnedPanelLayout.TICK)),
                "an unmet task has no tick");

        finishTheFirstTask();

        RecordingRenderer done = RecordingRenderer.create();
        HudOverlay.render(done, 640, 480, NOW);
        assertTrue(done.shadowedTexts().stream().anyMatch(call -> call.text().equals("8 / 8")),
                "a counter that reached its target");
        assertTrue(done.shadowedTexts().stream()
                        .anyMatch(call -> call.text().equals(PinnedPanelLayout.TICK)),
                "and a tick beside it");
        assertTrue(ClientQuestCache.taskDone("a", 0), "which is the cache's own answer, not a second one");
    }

    @Test
    @DisplayName("every pin is drawn in full, and one the tree does not hold is skipped")
    void everyPinIsInFull() {
        accept(A, B);
        PinnedQuests.pin("a");
        PinnedQuests.pin("b");
        PinnedQuests.pin("a_quest_this_server_has_never_heard_of");

        RecordingRenderer r = RecordingRenderer.create();
        HudOverlay.render(r, 640, 480, NOW);

        assertTrue(r.shadowedTexts().stream().anyMatch(call -> call.text().equals("Punch a tree")),
                "the first pin is on the stack: " + drawn(r));
        assertTrue(r.shadowedTexts().stream().anyMatch(call -> call.text().equals("Craft a table")),
                "and so is the second -- no pin is a bare name any more: " + drawn(r));
        assertTrue(r.shadowedTexts().stream().anyMatch(call -> call.text().equals("Say hello")),
                "with its task: " + drawn(r));
        assertFalse(r.shadowedTexts().stream()
                        .anyMatch(call -> call.text().contains("a_quest_this_server_has_never_heard_of")),
                "and an id the tree does not hold is not drawn as anything");
        assertTrue(PinnedQuests.isPinned("a_quest_this_server_has_never_heard_of"),
                "it is still pinned -- the stack is what skips it, and the list is what keeps it for the "
                        + "server that has it");
        assertTrue(PinnedQuests.isPinned("a"), "and the pins the tree does hold are all still there");
    }

    @Test
    @DisplayName("the stack sits middle-left, centred on the window's own middle")
    void theStackIsCentredVertically() {
        accept(A, B);
        PinnedQuests.pin("a");

        BookGeometry.Rect stack = boxOf(HudElement.PINNED_QUESTS, 640, 480);
        assertEquals(4, stack.x(), "against the left edge, where it shipped");
        assertEquals(240, stack.y() + stack.height() / 2,
                "with its middle on the window's: " + stack);
    }

    @Test
    @DisplayName("the empty state is the whole stack: the boxes are gone as well as the rows")
    void theEmptyStateTakesTheBoxWithIt() {        assertTrue(HudOverlay.size(HudElement.PINNED_QUESTS, FONT, HudOverlay.Face.LIVE, NOW).empty());

        accept(A, B);
        PinnedQuests.pin("a");
        HudOverlay.Size size = HudOverlay.size(HudElement.PINNED_QUESTS, FONT, HudOverlay.Face.LIVE, NOW);
        assertFalse(size.empty());
        assertNotEquals(0, size.width());
        assertTrue(size.width() >= PinnedPanelLayout.MIN_WIDTH
                        && size.width() <= PinnedPanelLayout.MAX_WIDTH,
                "and the measured size is inside the column's own bounds: " + size.width());
    }

    /** Reports progress for the rewarded fixture quest, collected by nobody unless named. */
    private static void reportRewarded(String state, UUID collector) {
        String claims = collector == null ? ""
                : ",\"claims\":{\"" + collector + "\":[0]}";
        ClientQuestCache.acceptProgress(UUID.randomUUID(), 100L,
                ("{\"quests\":{\"c\":{\"state\":\"" + state + "\"" + claims + "}}}")
                        .getBytes(StandardCharsets.UTF_8), 50L);
    }

    @Test
    @DisplayName("a collected quest hides for the collector, and stays pinned")
    void collectedHidesForTheCollector() {
        accept(C);
        PinnedQuests.pin("c");
        reportRewarded("COMPLETED", ME);

        RecordingRenderer r = RecordingRenderer.create();
        HudOverlay.render(r, 640, 480, NOW, ME);

        assertFalse(r.shadowedTexts().stream().anyMatch(call -> call.text().equals("Claim the axe")),
                "collected is watched no longer: " + drawn(r));
        assertTrue(HudOverlay.size(HudElement.PINNED_QUESTS, FONT, HudOverlay.Face.LIVE, NOW, ME).empty(),
                "and the box goes with it");
        assertTrue(PinnedQuests.isPinned("c"),
                "but the pin stays: hiding is not unpinning, and the list still names it");
    }

    @Test
    @DisplayName("a collected quest stays for a teammate with their own copy to collect")
    void collectedStaysForATeammate() {
        accept(C);
        PinnedQuests.pin("c");
        reportRewarded("COMPLETED", ME);

        RecordingRenderer r = RecordingRenderer.create();
        HudOverlay.render(r, 640, 480, NOW, UUID.randomUUID());

        assertTrue(r.shadowedTexts().stream().anyMatch(call -> call.text().equals("Claim the axe")),
                "their copy is still waiting: " + drawn(r));
    }

    @Test
    @DisplayName("a finished quest with rewards still out stays, and so does an unfinished one")
    void unclaimedStays() {
        accept(C);
        PinnedQuests.pin("c");

        reportRewarded("COMPLETED", null);
        RecordingRenderer waiting = RecordingRenderer.create();
        HudOverlay.render(waiting, 640, 480, NOW, ME);
        assertTrue(waiting.shadowedTexts().stream().anyMatch(call -> call.text().equals("Claim the axe")),
                "finished but uncollected is what the pin is for: " + drawn(waiting));

        reportRewarded("STARTED", null);
        RecordingRenderer going = RecordingRenderer.create();
        HudOverlay.render(going, 640, 480, NOW, ME);
        assertTrue(going.shadowedTexts().stream().anyMatch(call -> call.text().equals("Claim the axe")),
                "and underway is too: " + drawn(going));
    }

    @Test
    @DisplayName("switching the auto-hide off keeps a collected quest on the stack")
    void switchedOffKeepsCollected() {
        accept(C);
        PinnedQuests.pin("c");
        reportRewarded("COMPLETED", ME);
        PinnedQuests.setHideClaimed(false);

        RecordingRenderer r = RecordingRenderer.create();
        HudOverlay.render(r, 640, 480, NOW, ME);

        assertTrue(r.shadowedTexts().stream().anyMatch(call -> call.text().equals("Claim the axe")),
                "off means off: " + drawn(r));
    }

    @Test
    @DisplayName("without a player nothing hides, which is the direction that cannot lose a quest")
    void nobodyInParticularHidesNothing() {
        accept(C);
        PinnedQuests.pin("c");
        reportRewarded("COMPLETED", ME);

        RecordingRenderer r = RecordingRenderer.create();
        HudOverlay.render(r, 640, 480, NOW);

        assertTrue(r.shadowedTexts().stream().anyMatch(call -> call.text().equals("Claim the axe")),
                "callers without a player see everything: " + drawn(r));
    }

    @Test
    @DisplayName("a finished quest with rewards out says Claimable rather than Complete")
    void finishedWithRewardsOutSaysClaimable() {
        accept(C);
        PinnedQuests.pin("c");
        reportRewarded("COMPLETED", null);

        RecordingRenderer r = RecordingRenderer.create();
        HudOverlay.render(r, 640, 480, NOW, ME);

        assertTrue(r.shadowedTexts().stream().anyMatch(call -> call.text().equals("Claimable")),
                "done is not the news, collectable is: " + drawn(r));
        assertFalse(r.shadowedTexts().stream().anyMatch(call -> call.text().equals("Complete")),
                "and Complete is not said beside it: " + drawn(r));
    }

    @Test
    @DisplayName("a finished and collected quest says Complete while the hiding is off")
    void collectedSaysCompleteWhenShown() {
        accept(C);
        PinnedQuests.pin("c");
        reportRewarded("COMPLETED", ME);
        PinnedQuests.setHideClaimed(false);

        RecordingRenderer r = RecordingRenderer.create();
        HudOverlay.render(r, 640, 480, NOW, ME);

        assertTrue(r.shadowedTexts().stream().anyMatch(call -> call.text().equals("Complete")),
                "shown but over: " + drawn(r));
        assertFalse(r.shadowedTexts().stream().anyMatch(call -> call.text().equals("Claimable")),
                "with nothing out: " + drawn(r));
    }

    @Test
    @DisplayName("a press on a pin box names its quest, and one elsewhere names nothing")
    void pinBoxesAnswerPresses() {
        accept(C);
        PinnedQuests.pin("c");

        List<HudOverlay.PinHit> hits = HudOverlay.pinHits(FONT, 640, 480, NOW, null);
        assertEquals(1, hits.size(), "one pin, one box");
        BookGeometry.Rect box = hits.get(0).box();
        assertEquals("c", hits.get(0).questId());
        assertEquals("c", HudOverlay.pinAt(FONT, 640, 480, NOW, null,
                box.x() + box.width() / 2, box.y() + box.height() / 2));

        assertTrue(HudOverlay.pinAt(FONT, 640, 480, NOW, null, 639, 479) == null,
                "a press in the far corner is nobody's quest");
        assertTrue(HudOverlay.pinAt(FONT, 640, 480, NOW, null, box.x() - 1, box.y()) == null,
                "nor one pixel off its edge");

        // And a hidden quest answers nothing for the player it hides from, while staying clickable
        // for callers without one: the hook passes the live player, so this is the live answer.
        reportRewarded("COMPLETED", ME);
        assertTrue(HudOverlay.pinAt(FONT, 640, 480, NOW, ME,
                box.x() + box.width() / 2, box.y() + box.height() / 2) == null);
        assertEquals("c", HudOverlay.pinAt(FONT, 640, 480, NOW, null,
                box.x() + box.width() / 2, box.y() + box.height() / 2));
    }

    @Test
    @DisplayName("the hovered pin box wears an outline, and empty space wears nothing")
    void hoveredPinBoxWearsAnOutline() {
        accept(C);
        PinnedQuests.pin("c");
        BookGeometry.Rect box = HudOverlay.pinHits(FONT, 640, 480, NOW, null).get(0).box();

        RecordingRenderer on = RecordingRenderer.create();
        HudOverlay.drawPinHover(on, FONT, 640, 480, NOW, null,
                box.x() + box.width() / 2, box.y() + box.height() / 2);
        List<RecordingRenderer.Call> ring = on.calls().stream()
                .filter(call -> call.op() == RecordingRenderer.Op.FILL).toList();
        assertEquals(4, ring.size(), "one pixel per side: " + drawn(on));
        assertTrue(ring.stream().allMatch(call -> call.argb() == ArmatureTheme.selectedRing()),
                "the selection ring, which is what hover means everywhere else");

        RecordingRenderer off = RecordingRenderer.create();
        HudOverlay.drawPinHover(off, FONT, 640, 480, NOW, null, 639, 479);
        assertTrue(off.calls().stream().noneMatch(call -> call.op() == RecordingRenderer.Op.FILL),
                "empty space draws nothing: " + drawn(off));
    }

    @Test
    @DisplayName("one shared assembly draws exactly what size plus paint draw")
    void sharedAssemblyDrawsWhatSizePlusPaintDraw() {
        // The frame's contract: render() assembles the pins and the column once and shares them
        // between measuring and painting, where it used to assemble twice. Replaying the old
        // shape — size, place, paint, separately — must issue the identical calls, or the sharing
        // changed a pixel rather than an allocation.
        accept(A, B);
        PinnedQuests.pin("a");
        PinnedQuests.pin("b");

        RecordingRenderer shared = RecordingRenderer.create();
        HudOverlay.render(shared, 640, 480, NOW, ME);

        RecordingRenderer split = RecordingRenderer.create();
        Measure measure = Measure.of(split::textWidth, split.lineHeight());
        for (HudElement element : HudElement.values()) {
            if (element.kind() != HudElement.Kind.HUD || !HudSettings.on(element)) {
                continue;
            }
            HudOverlay.Size size = HudOverlay.size(element, measure, HudOverlay.Face.LIVE, NOW, ME);
            if (size.empty()) {
                continue;
            }
            BookGeometry.Rect box = HudLayout.boxAt(element, 640, 480,
                    HudSettings.x(element), HudSettings.y(element), size.width(), size.height());
            HudOverlay.paint(element, split, box, measure, HudOverlay.Face.LIVE, NOW, ME);
        }

        assertEquals(shared.calls(), split.calls(),
                "the shared frame must draw exactly what the separate size+paint path draws");
    }
}
