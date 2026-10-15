package dev.ellipog.tenet.client.render;

import dev.ellipog.tenet.client.CanvasSettings;
import dev.ellipog.tenet.client.QuestNodeArt;
import dev.ellipog.tenet.quest.ItemStacks;
import dev.ellipog.tenet.quest.MinecraftTestBootstrap;
import dev.ellipog.tenet.quest.QuestShape;

import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The order a node's furniture is drawn in: panel (wash composited in), then icon.
 *
 * <h2>Why the order is the thing to test</h2>
 *
 * <p>Because it is what the drawing means. The panel is drawn first so the icon sits on it; the wash
 * is composited <i>into</i> the panel's fill rather than drawn over the icon, so the item stays
 * readable over the dimmed shape. Get that order wrong and the picture is wrong in a way no
 * assertion about fills or pixels notices — which is exactly what happened twice: the flat-icon
 * path drew nothing while reporting an item, and the port kept the wash-over-icon order whose
 * flush-based reasoning died with 1.21.1, burying every locked node's item under its own dim.
 * Both times the screenshot was the only instrument that saw it.
 *
 * <p>So this asserts the **interleaving**, not the membership: an ordered log of every call, with the
 * icon's position after the panel's fills — and the wash's colour appearing in no fill of its own.
 * Nothing here needs a client, and that matters, because the preview harness draws no item icons at
 * all — it has no item registry — so the drawn result of an icon is not observable in any automated
 * way in this project.
 *
 * <h2>Which recording renderer, and why it is worth saying</h2>
 *
 * <p>{@code dev.RecordingRenderer} — the one the panel tests use — records **per kind**: a list of fills, a
 * list of texts, a list of scales. Interleaving cannot be asserted against that, and believing it was the
 * only one available is why this test was called blocked and went unwritten for a round. The render
 * package's own recorder keeps one ordered {@code calls()} list with an {@code Op} per call, and its
 * {@code firstIndex}/{@code lastIndex} are documented as being "for asserting relative order".
 */
@DisplayName("the order a node is drawn in")
class NodeArtOrderTest {

    /**
     * The wash's colour, which is how the one fill that must not exist is recognised. Translucent,
     * like every real state wash: an opaque wash composites to itself, which would make "no raw
     * wash fill" true without exercising the merge at all.
     */
    private static final int WASH = 0x9C203040;

    @BeforeAll
    static void bootstrapMinecraft() {
        // The icon path resolves an ItemStack, so the registry has to exist.
        MinecraftTestBootstrap.boot();
    }

    /** A node with a panel, an icon and a wash, which is every node in the reader's book. */
    private static QuestNodeArt.Look look(int wash) {
        return look(wash, 48, 0.75);
    }

    /** The same, at a size and icon scale of the caller's choosing: the icon box follows both. */
    private static QuestNodeArt.Look look(int wash, int size, double iconScale) {
        return new QuestNodeArt.Look(size, QuestShape.ROUNDED, QuestShape.ROUNDED.geometry(),
                ItemStacks.stackOf(Items.STICK), iconScale, 0xFF1069B4, 0, wash);
    }

    /** The index of the first fill in this colour, or -1: the wash is the only fill drawn in its own. */
    private static int firstFillIn(RecordingRenderer r, int argb) {
        for (int i = 0; i < r.calls().size(); i++) {
            RecordingRenderer.Call call = r.calls().get(i);
            if (call.op() == RecordingRenderer.Op.FILL && call.argb() == argb) {
                return i;
            }
        }
        return -1;
    }

    @Test
    @DisplayName("the icon is drawn after the panel, and the wash is composited, not drawn")
    void theIconIsDrawnAfterThePanelAndTheWashIsMerged() {
        RecordingRenderer r = RecordingRenderer.create();
        QuestNodeArt.draw(r, 0, 0, look(WASH));

        int icon = r.firstIndex(RecordingRenderer.Op.ICON);
        assertTrue(icon >= 0, "the node's icon was not drawn at all");
        assertTrue(r.firstIndex(RecordingRenderer.Op.FILL) >= 0, "and neither was its panel");
        assertTrue(r.firstIndex(RecordingRenderer.Op.FILL) < icon,
                "the panel must be drawn *before* the icon, or the icon is behind its own node's border");

        // The wash never appears as its own fill: it is composited into the panel's fill (see
        // Colour.over), so a locked node costs one shape fill rather than two. A wash drawn as
        // its own fill after the panel would paint over the icon it exists to frame.
        assertTrue(firstFillIn(r, WASH) < 0, "the wash was drawn raw instead of composited");
    }

    @Test
    @DisplayName("the node's own box decides whether its icon is drawn")
    void theIconIsRefusedByTheBoxAndNotByTheZoom() {
        // What replaced the zoom tier, and the property a screenshot had to find: an icon is refused when
        // the box it would fill is too small — measured from the node's own outline — and never because of
        // how far out the canvas happens to be zoomed. A landmark is large at any zoom and keeps its icon;
        // this small node runs out of room at a specific size.
        //
        // The threshold is loaded from a file rather than assumed, because it is the client's: a machine
        // whose `canvas.json` asks for tiny icons would otherwise make this test pass or fail by itself.
        Path settings = null;
        try {
            settings = Files.createTempFile("tenet-canvas-test", ".json");
            Files.writeString(settings, "{\"iconMinBox\": 12}", StandardCharsets.UTF_8);
            CanvasSettings.load(settings);
        }
        catch (java.io.IOException e) {
            throw new AssertionError("could not write the test's canvas.json", e);
        }

        try {
            RecordingRenderer big = RecordingRenderer.create();
            QuestNodeArt.draw(big, 0, 0, look(0, 48, 0.75));
            assertTrue(big.firstIndex(RecordingRenderer.Op.ICON) >= 0,
                    "a 48-pixel node has room for its icon");

            RecordingRenderer small = RecordingRenderer.create();
            QuestNodeArt.draw(small, 0, 0, look(0, 12, 0.5));
            assertTrue(small.firstIndex(RecordingRenderer.Op.ICON) < 0,
                    "a node too small for a 12-pixel box must not draw an item: it would be a smudge");
            assertTrue(small.firstIndex(RecordingRenderer.Op.FILL) >= 0,
                    "and it must still be drawn — its stand-in block is what makes an icon-less node visible");
        }
        finally {
            // The setting is static state shared by every test in this JVM, so it is put back to the
            // defaults the moment this one is done.
            CanvasSettings.load(Path.of("no", "such", "canvas.json"));
            if (settings != null) {
                try {
                    Files.deleteIfExists(settings);
                }
                catch (java.io.IOException ignored) {
                    // A temp file that will not delete is not this test's business.
                }
            }
        }
    }

    @Test
    @DisplayName("with no wash, and with the icon refused, the panel is still first and the node is drawn")
    void thePanelIsFirstEitherWay() {
        // Two more arms of the same rule, and the ones a regression would break silently: a node with no
        // wash has nothing to bracket the icon, so panel-before-icon is the only thing keeping it visible —
        // and a node whose icon is *refused* must still draw its panel and its stand-in block rather than
        // nothing at all, which is the other half of the flat-icon lesson.
        RecordingRenderer plain = RecordingRenderer.create();
        QuestNodeArt.draw(plain, 0, 0, look(0));
        assertTrue(plain.firstIndex(RecordingRenderer.Op.FILL) < plain.firstIndex(RecordingRenderer.Op.ICON),
                "with no wash, the panel is still first");

        RecordingRenderer refused = RecordingRenderer.withoutIcons();
        QuestNodeArt.draw(refused, 0, 0, look(WASH));
        assertTrue(refused.firstIndex(RecordingRenderer.Op.FILL) >= 0,
                "a refused icon must still leave a drawn node, not an invisible one");
    }
}
