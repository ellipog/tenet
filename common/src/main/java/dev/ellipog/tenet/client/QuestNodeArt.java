package dev.ellipog.tenet.client;

import dev.ellipog.armature.client.ArmatureTheme;
import dev.ellipog.armature.client.render.GuiRenderer;
import dev.ellipog.armature.client.ui.kit.Colour;
import dev.ellipog.armature.client.ui.shape.Shape;
import dev.ellipog.tenet.progress.QuestState;
import dev.ellipog.tenet.quest.QuestShape;

import net.minecraft.world.item.ItemStack;

/**
 * How a quest node is drawn: one description, shared by the canvas and the settings preview.
 *
 * <h2>Why this is its own class</h2>
 *
 * <p>The node's drawing used to be a private method of {@code QuestBookScreen}, which is a class no test
 * can instantiate and no preview can call. The settings page wants to draw the same node — a preview
 * that drew its own version would be a second description of the thing the author is choosing, and the
 * second description is always the one that drifts: the canvas would gain a wash and the preview would
 * not, or a shape would be added and the preview would keep drawing a square. That is the same fault
 * the tools panel's preview was built to avoid, one level up.
 *
 * <p>So the drawing takes a {@link GuiRenderer} and plain numbers, exactly like {@code QuestPanel} and
 * {@code ToolsPanel}, and both callers hand it the same {@link Look}.
 *
 * <h2>What the caller decides and what this decides</h2>
 *
 * <p>The caller decides <i>state</i>: which edge colour a node's progress gives it, how hovered it is,
 * whether it is selected. This decides <i>art</i>: the ring outside the panel, the panel in the shape,
 * the icon at its fitted box, the block that stands in when there is no icon, and the wash that dims a
 * finished or locked node. Those five steps are the whole of what a node looks like, and they are in
 * this order because each one is drawn over the last.
 */
public final class QuestNodeArt {

    /**
     * The smallest icon box worth drawing an item into.
     *
     * <p>Below this an item is a smudge, and a block in the state colour reads as a node where a smudge
     * reads as a rendering fault. Twelve because an item is sixteen pixels wide at full size and a
     * three-quarter box is twelve.
     */
    /**
     * The default smallest an item's box may be. The value in force is the client's own
     * ({@code canvas.json}'s {@code iconMinBox}, see {@link CanvasSettings}), and this is what it defaults
     * to.
     */
    public static final int MIN_ITEM_BOX = CanvasSettings.DEFAULT_ICON_MIN_BOX;

    private QuestNodeArt() {
    }

    /**
     * Everything about how one node looks, as plain values.
     *
     * @param size       the node's drawn size in pixels
     * @param shape      the outline's <i>name</i>, which is what says whether a panel is drawn at all
     * @param geometry   the outline's geometry, <b>with any rotation already applied</b>. Resolved by
     *                   the caller rather than here, because a rotation is applied by sampling the shape
     *                   again and a node drawn per frame must not rebuild its table per frame
     * @param icon       the item drawn in the middle; null or empty for no item, which is what the
     *                   stand-in block is for. Null is accepted rather than required to be
     *                   {@code ItemStack.EMPTY} because the game-free tests cannot build a stack at all
     *                   — touching that class bootstraps the item registry, which there is no registry
     *                   to bootstrap — and "no item" is a real state a caller may want to draw.
     * @param texture    the texture path drawn in the middle when the icon is a texture, and empty
     *                   otherwise. A texture wins over the item: the two never arrive together, because
     *                   the cache resolves one arm and keeps the other's half empty. Drawn stretched into
     *                   the icon's box; a path nothing holds draws the game's missing texture, like an
     *                   image element's missing file.
     * @param sprite     the atlas region drawn in the middle when the icon is a sprite, and empty
     *                   otherwise. Drawn from the atlas after the texture arm is asked and before the
     *                   item arm: one picture wins, in the order the cache resolves, and a region
     *                   nothing holds draws the missing mark like an unheld file.
     * @param iconScale  how much of the node the icon is asked to fill; the outline caps it
     * @param alwaysIcons whether the icon is drawn whatever the box measures: the per-node
     *                   answer of a chapter or book that disabled the canvas LOD. False keeps
     *                   the box-size gate below, which is what every tiered chapter draws with
     * @param edge       the panel's border colour, from the node's state
     * @param ring       the hover or selection ring's colour, or 0 for none
     * @param wash       the state wash's colour, or 0 for none
     * @param drawsPanel whether the panel is drawn at all — false for {@link QuestShape#NONE}, whose
     *                   node is its icon and whose geometry is still a square for the hit test
     */
    public record Look(int size, QuestShape shape, Shape geometry, ItemStack icon, String texture,
                        String sprite, double iconScale, boolean alwaysIcons, int edge, int ring, int wash) {

        /** The item-arm shape, for a caller with no texture: the texture is empty, not null. */
        public Look(int size, QuestShape shape, Shape geometry, ItemStack icon, double iconScale,
                    int edge, int ring, int wash) {
            this(size, shape, geometry, icon, "", "", iconScale, false, edge, ring, wash);
        }
    }

    /**
     * The panel's border colour for a state.
     *
     * <p>The border reads the node-edge tokens rather than the state inks, and that is the whole of
     * what those four tokens are for. One description rather than one per caller: the canvas's nodes
     * and its links wear the same edges, and two switches answering one question is how one of them
     * stops meaning it.
     */
    public static int edgeFor(QuestState state) {
        return switch (state) {
            case COMPLETED -> ArmatureTheme.nodeEdgeComplete();
            case STARTED -> ArmatureTheme.nodeEdgeInProgress();
            case UNLOCKED -> ArmatureTheme.nodeEdgeAvailable();
            case LOCKED -> ArmatureTheme.nodeEdgeBlocked();
        };
    }

    /**
     * The state wash's colour for a state, or 0 for none.
     *
     * <p>Like {@link #edgeFor}: one description for both callers. A finished node is dimmed, a
     * locked one more so, and the rest wear nothing — the wash follows the shape, so it dims what
     * is there without hiding what the quest is.
     */
    public static int washFor(QuestState state) {
        return switch (state) {
            case LOCKED -> ArmatureTheme.nodeDim();
            case COMPLETED -> ArmatureTheme.nodeDoneWash();
            case STARTED, UNLOCKED -> 0;
        };
    }

    /** Draws one node with its corner at {@code x, y}. */
    public static void draw(GuiRenderer r, int x, int y, Look look) {        int size = look.size();
        Shape geometry = look.geometry();

        // The hover and selection ring, drawn FIRST and one pixel larger, so the node's own panel
        // covers all but its outer edge. What shows is a one-pixel ring that follows the shape.
        //
        // It used to be `ArmatureTheme.outline(...)`, a rectangle drawn around a circle. On a round or
        // hexagonal node that is a box drawn round a disc -- which reads as two unrelated things
        // stacked. Following the shape is also what legacy packs do, and for the same reason: the ring
        // is the node saying "this one", so it has to be the node's shape saying it.
        // The ring is the panel's own outline one pixel out, not the same shape function sampled at
        // `size + 2`: a second sampling is a second silhouette, and where a size-dependent feature steps
        // between the two sizes -- a gear's tooth count, a rounded rectangle's radius -- the ring had
        // gaps where the panel had material. See `Outlines`.
        //
        // Drawn as two fills rather than as a panel: the halo in the ring's colour, and then the panel's
        // own outline in the node's fill. What survives is a band exactly one pixel wide all the way
        // round, which is the whole point of a ring. A panel would paint its own eroded table inside the
        // halo instead, and that closing paints over the panel's gaps -- the space between two gear
        // teeth, a tome's notch -- leaving the ring broken and fill colour outside the outline.
        if (look.ring() != 0) {
            // The shapes themselves rather than their row lookups: `fillShape` remembers a shape's
            // rectangles by the shape and the size, so passing the layer is what makes them findable
            // again next frame. See Plans.
            ArmatureTheme.fillShape(r, x - 1, y - 1, size + 2, look.ring(), geometry.outer());
            ArmatureTheme.fillShape(r, x, y, size, ArmatureTheme.nodeFill(), geometry);
        }

        // The panel, in the node's own shape. A shape is a row-to-span lookup and nothing else, so the
        // fill, the border, the ring and the hit test all come from one place -- which is why a click
        // lands on exactly the pixels that were drawn and not on a bounding box around them.
        //
        // `none` is the one shape that draws no panel: its node is its icon, and the square its
        // geometry describes is for the hit test and the icon's fit. See `QuestShape.drawsPanel`.
        if (look.shape().drawsPanel()) {
            // The shape, not its span lookup: `shapePanel` asks the shape for its own eroded layer, and
            // a layer is a table once it has been asked for a size. See ArmatureTheme.shapePanel.
            //
            // The wash is composited into the panel's fill rather than drawn over it: one shape fill
            // instead of two, for the same pixels — over is associative, so a wash drawn over a panel
            // shows what drawing the composite shows (see Colour.over). Two fills per node per frame
            // across seventy nodes is what the frame overlay counted, and on 26.x every fill is its
            // own extraction record. The edge still draws over the composite, so the border the old
            // separate wash spared stays crisp.
            int face = ArmatureTheme.nodeFill();
            if (look.wash() != 0) {
                face = Colour.over(face, look.wash());
            }
            ArmatureTheme.shapePanel(r, x, y, size, face, look.edge(), geometry);
        }

        // The icon's corner and its size, from ONE inset -- `iconBox`, not two numbers here.
        //
        // The version that shipped took the size from `shape.iconInset(size)` and the position from the
        // constant `NODE_INSET`, so a 36-pixel item was drawn 3 pixels in from the corner instead of 6:
        // off centre in both axes, with its corner through the rounded outline. Same mistake as the
        // colliding buttons and the label and its room -- one value, two places -- and the fix is the
        // same: compute the pair together, somewhere a caller cannot take one and invent the other.
        int[] iconBox = look.geometry().iconBox(x, y, size, look.iconScale());
        // The node's **own box** decides, measured from its own outline, and the threshold is the client's.
        // A landmark has room at any zoom; a small node runs out of room at a specific *size*. Asking the
        // zoom instead is what drew a large gear node as an empty outline with a stand-in block — the box is
        // the honest question and it was already being asked. See CanvasSettings.
        //
        // A chapter or book that disabled the LOD answers the question before it is asked
        // (`alwaysIcons`): legacy packs drew every icon at every zoom, and a migrated pack reads
        // the same way. The stand-in block below is then only for icons the client cannot
        // resolve, never for ones it judged too small.
        boolean iconFits = look.alwaysIcons() || iconBox[2] >= CanvasSettings.iconMinBox();
        //
        // A texture icon draws through the blit rather than the stack: the cache keeps the stack empty
        // for a texture, so the item path below would read it as "no icon" and draw the block. A path
        // nothing holds draws the game's missing texture, which names the picture nobody can draw.
        boolean drewItem = false;
        if (!look.texture().isEmpty() && iconFits) {
            net.minecraft.resources.Identifier texture =
                    net.minecraft.resources.Identifier.tryParse(look.texture());
            if (texture != null) {
                r.texture(texture, iconBox[0], iconBox[1], iconBox[2], iconBox[2]);
                drewItem = true;
            }
        }
        if (!drewItem && !look.sprite().isEmpty() && iconFits) {
            // An atlas region, drawn like a chapter element's sprite: the cache keeps the stack
            // empty for a sprite for the same reason it does for a texture. A region nothing
            // holds draws the missing mark through the sprite call itself.
            net.minecraft.resources.Identifier sprite =
                    net.minecraft.resources.Identifier.tryParse(look.sprite());
            if (sprite != null) {
                r.sprite(sprite, iconBox[0], iconBox[1], iconBox[2], iconBox[2], 0xFFFFFFFF);
                drewItem = true;
            }
        }
        if (!drewItem) {
            drewItem = look.icon() != null && !look.icon().isEmpty()
                    && iconFits
                    && r.icon(look.icon(), iconBox[0], iconBox[1], iconBox[2]);
        }

        if (!drewItem) {
            // No icon, or one the client cannot resolve, or a node too small to hold one. A block in the
            // state colour still reads as a node in a graph, where an empty one reads as a bug -- and it
            // follows the shape, so a small circle is a small circle rather than a square inside it. The
            // block is the panel's own outline inset by the same amount, for the reason the fill is.
            int inset = Math.max(1, size / 4);
            ArmatureTheme.fillShape(r, x + inset, y + inset, size - inset * 2,
                    (look.edge() & 0x00FFFFFF) | 0xB0000000, geometry.inner(inset));
        }
    }

    /**
     * A node's name, in a box under it — the hover caption and the settings preview's label.
     *
     * <p>Clamped into {@code [left, right]} horizontally and flipped above the node when there is no
     * room below, because the caller that draws a caption is always drawing it inside something. The
     * canvas passes its own rectangle; the settings preview passes the preview pane.
     *
     * @param left   the left edge of the room the caption may use, inclusive
     * @param right  its right edge, exclusive
     * @param bottom its bottom edge, exclusive
     */
    public static void caption(GuiRenderer r, int x, int y, int size, String title, int left,
                               int right, int bottom) {
        int boxWidth = r.textWidth(title) + 10;
        int boxX = Math.max(left + 2, Math.min(x + size / 2 - boxWidth / 2, right - boxWidth - 2));
        int boxY = y + size + 4;
        if (boxY + 14 > bottom) {
            boxY = y - 18;
        }

        ArmatureTheme.panel(r, boxX, boxY, boxWidth, 14, ArmatureTheme.panel(),
                ArmatureTheme.controlEdgeBright());
        r.text(title, boxX + 5, boxY + 3, ArmatureTheme.title());
    }
}
