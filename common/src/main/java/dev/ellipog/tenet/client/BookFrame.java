package dev.ellipog.tenet.client;

import dev.ellipog.armature.client.ArmatureButton;
import dev.ellipog.armature.client.ArmatureTheme;
import dev.ellipog.armature.client.Tooltips;
import dev.ellipog.armature.client.render.GuiRenderer;
import dev.ellipog.armature.client.ui.kit.Colour;
import dev.ellipog.armature.client.ui.kit.Layout;
import dev.ellipog.armature.client.ui.kit.Measure;
import dev.ellipog.armature.client.ui.kit.Motion;
import dev.ellipog.armature.client.ui.kit.ScrollView;
import dev.ellipog.armature.client.ui.kit.Slot;
import dev.ellipog.armature.client.ui.kit.Viewport;
import dev.ellipog.armature.client.ui.party.PartyRoster;
import dev.ellipog.tenet.Tenet;
import dev.ellipog.tenet.net.EditorReplyPayload;
import dev.ellipog.tenet.net.TenetNetworking;
import dev.ellipog.tenet.client.ClientChapterReplica;
import dev.ellipog.tenet.client.ClientEditReplies;
import dev.ellipog.tenet.client.dev.ChapterPanelLayout;
import dev.ellipog.tenet.client.dev.CanvasReveal;
import dev.ellipog.tenet.client.dev.QuestPanelLayout;
import dev.ellipog.tenet.client.dev.ToolsLayout;
import dev.ellipog.tenet.Constants;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Util;
import net.minecraft.world.item.ItemStack;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The quest book's frame: what each client tick advances, and the drawing a frame does once the
 * screen has wrapped Minecraft's context in a renderer ({@code renderWith}).
 *
 * <p>Moved out of {@link QuestBookScreen} as it stood. The screen keeps {@code extractRenderState},
 * the one place a {@code GuiGraphicsExtractor} arrives and is wrapped, and the widget pass that has to
 * draw from it; it reaches this through its {@code bookFrame} field.
 */
final class BookFrame {

    private final QuestBookScreen book;

    BookFrame(QuestBookScreen book) {
        this.book = book;
    }

    // Every one of these is an alias, not a number. The number lives in BookGeometry, and this class
    // reads it from there -- because a second copy of "the sidebar is 132 wide" is a copy that can
    // disagree with the one the overlap test checks. That disagreement is precisely how two buttons
    // ended up drawn on top of each other: "near the bottom right" written twice.
    //
    // There is deliberately no alias for the sidebar's width any more: the list is the player's
    // width now, read from the geometry that resolved it, and an alias would be a second copy of a
    // number that moves.
    private static final int HEADER_HEIGHT = BookGeometry.HEADER_HEIGHT;

    /**
     * The tree and replica revisions the quest panel's rows were built at.
     *
     * <p>Two, because the panel reads the <b>replica</b> and the canvas reads the tree, and they move at
     * different times: an op's answer moves the tree first, and the replica it asked for arrives a round
     * trip later. Rebuilding on the tree alone would build rows from a copy that is about to be replaced,
     * and nothing would ever rebuild again -- the panel would show the edit it just committed as it was
     * before it. Either number moving rebuilds the rows.
     */
    private long questPanelRevision = -1;
    private long questReplicaRevision = -1;

    // Every position below now comes from BookGeometry, which has no Minecraft in it and so can be
    // tested. These stay as named methods because the drawing code reads better for them --
    // `canvasRight()` says more at a call site than `geometry().canvas().right()` -- and because
    // renaming them all would be a large diff for no behavioural gain. The *bodies* are what had to
    // change, and that is the fix: there is now exactly one expression per position, and the tests
    // assert the properties of it.

    private int panelWidth() {
        return book.geometry().panel().width();
    }

    private int panelHeight() {
        return book.geometry().panel().height();
    }

    private int panelLeft() {
        return book.geometry().panel().x();
    }

    private int panelTop() {
        return book.geometry().panel().y();
    }

    // --- the shared positions. One expression each, used by drawing and by the controls ---

    /**
     * Where the header's right-hand text has to stop, so the quest count does not run under Close.
     *
     * <p>There is no footer to ask about any more. The four controls that were in it are three square
     * map buttons in the canvas's own corner and a close button in the header — see
     * {@link BookGeometry}'s class comment for why each moved, and what each replaced.
     */
    private int headerRightLimit() {
        // One expression for every player now. It used to ask whether the author's pair would be built,
        // because that pair sat in the header and the count had to stop short of it; the pair is a pill
        // over the canvas now, and the header is the same four controls whoever is looking at it.
        return book.geometry().headerRightLimit();
    }

    /**
     * The header's right-hand summary: the book's total quests, the open chapter's own count, and how
     * much of that chapter is done.
     *
     * <h2>Three draws rather than one string</h2>
     *
     * <p>The total is deliberately the faintest of the three. The numbers a player can act on are the
     * open chapter's own, and the pack-wide count is context for them rather than a figure in its own
     * right — so it recedes, and the chapter's pair stays the ink the summary always was. One
     * {@code text} call cannot carry two colours, so the string is drawn in segments, right-aligned to
     * the same edge the old summary used.
     *
     * <h2>Dropped from the left</h2>
     *
     * <p>Too narrow for all three drops the total first, then — still too narrow — the whole summary:
     * a missing count is a smaller fault than a title with a number drawn through it, and the
     * chapter's own pair is the part worth keeping. The zoom percentage this replaced is simply gone;
     * the view cluster already answers what the zoom is by what it does, and the slot says something
     * about the chapter instead.
     */
    private void drawHeaderSummary(GuiRenderer r, int left, int top) {
        String chapter = QuestBookScreen.effectiveChapter();
        ChapterProgress progress = chapter == null ? null : book.chapterCounts().byChapter().get(chapter);
        int done = progress == null ? 0 : progress.done();
        int quests = progress == null ? 0 : progress.total();
        int percent = progress == null ? -1 : progress.percent();

        String sep = " \u00b7 ";
        String totalText = Integer.toString(ClientQuestCache.questCount());
        String countText = Integer.toString(quests);
        String percentText = percent < 0 ? null : percent + "%";

        int titleLimit = headerTitleX(left) + r.textWidth(book.bookTitle().getString()) + 12;
        int width = r.textWidth(totalText) + r.textWidth(sep) + r.textWidth(countText)
                + (percentText == null ? 0 : r.textWidth(sep) + r.textWidth(percentText));
        int x = headerRightLimit() - width;
        boolean withTotal = true;
        if (x <= titleLimit) {
            withTotal = false;
            width = r.textWidth(countText)
                    + (percentText == null ? 0 : r.textWidth(sep) + r.textWidth(percentText));
            x = headerRightLimit() - width;
            if (x <= titleLimit) {
                return;
            }
        }

        if (withTotal) {
            r.text(totalText, x, top + 9, Colour.shade(ArmatureTheme.faint(), -0.4F));
            x += r.textWidth(totalText);
            r.text(sep, x, top + 9, ArmatureTheme.faint());
            x += r.textWidth(sep);
        }
        r.text(countText, x, top + 9, ArmatureTheme.faint());
        x += r.textWidth(countText);
        if (percentText != null) {
            r.text(sep, x, top + 9, ArmatureTheme.faint());
            x += r.textWidth(sep);
            r.text(percentText, x, top + 9, ArmatureTheme.faint());
        }
    }

    /**
     * The book's identity in the header: the pack's icon when it declares one, then its name.
     *
     * <p>Both come from the tree, so a pack that names its book is announced as that book rather than
     * as the generic one; a pack that declares nothing gets the translatable title and no icon, which
     * is exactly what every pack predating the fields gets. An icon id that did not resolve draws the
     * same "missing item" mark the chapter header draws — a "?" in the blocked colour — so a typo in
     * the file reads as a missing item rather than as an empty space.
     */
    private void drawHeaderIdentity(GuiRenderer r, int left, int top) {
        int icon = headerIconSize();
        if (icon > 0) {
            int iconY = top + 9 - (icon - 8) / 2;
            ItemStack stack = ClientQuestCache.bookIcon();
            String texture = ClientQuestCache.bookTextureIcon();
            String sprite = ClientQuestCache.bookSpriteIcon();
            if (!stack.isEmpty()) {
                r.icon(stack, left + BookGeometry.HEADER_INSET, iconY, icon);
            }
            else if (!texture.isEmpty()
                    && net.minecraft.resources.Identifier.tryParse(texture) != null) {
                r.texture(net.minecraft.resources.Identifier.parse(texture),
                        left + BookGeometry.HEADER_INSET, iconY, icon, icon);
            }
            else if (!sprite.isEmpty()
                    && net.minecraft.resources.Identifier.tryParse(sprite) != null) {
                r.sprite(net.minecraft.resources.Identifier.parse(sprite),
                        left + BookGeometry.HEADER_INSET, iconY, icon, icon, 0xFFFFFFFF);
            }
            else {
                int x = left + BookGeometry.HEADER_INSET;
                r.fill(x, iconY, x + icon, iconY + icon, ArmatureTheme.blocked());
                r.centredText("?", x + icon / 2, iconY + (icon - 8) / 2, ArmatureTheme.title());
            }
        }
        // Truncated to the room the header has, because the name is a pack's string now: an overlong one
        // must not run under the close button or the summary beside it.
        String name = Measure.truncate(book.bookTitle().getString(),
                Math.max(0, headerRightLimit() - headerTitleX(left) - 12),
                Measure.of(r::textWidth, r.lineHeight()));
        r.text(name, headerTitleX(left), top + 9, ArmatureTheme.title());
    }

    /** The header's icon box, or zero when the pack declares no icon at all. */
    private static int headerIconSize() {
        return ClientQuestCache.bookIconId().isEmpty() && ClientQuestCache.bookTextureIcon().isEmpty()
                && ClientQuestCache.bookSpriteIcon().isEmpty()
                ? 0 : 12;
    }

    /** Where the header's title starts: after the inset, and after the icon when there is one. */
    private static int headerTitleX(int left) {
        int icon = headerIconSize();
        return left + BookGeometry.HEADER_INSET + (icon > 0 ? icon + 4 : 0);
    }

    /**
     * Keeps the held bar honest, once a frame: the hold-repeat, and the release that never arrived.
     *
     * <h2>Why a frame is involved at all</h2>
     *
     * <p>Because a groove held down has to keep paging, and Minecraft has no mouse auto-repeat: a press
     * is one event and a hold is silence. So the repeat is driven from here, with the frame's own clock
     * — the same "time is passed in" rule every animation in this toolkit follows, which is why the
     * repeat can be asserted by passing a larger number rather than by waiting.
     *
     * <h2>And why the window's focus ends the gesture</h2>
     *
     * <p>Because a drag can end without a mouse-up. {@code MouseHandler} stops dispatching
     * {@code mouseDragged} the moment the window is inactive, and a button released while it is inactive
     * is an event no screen is ever handed — so alt-tabbing away mid-drag used to leave the bar held,
     * which is invisible until the pointer comes back and the list moves without a button being down.
     * The condition that stops the events is therefore the condition that ends the gesture, and it is the
     * same one Minecraft asks ({@code Minecraft.isWindowActive}) before it dispatches a drag at all.
     */
    private void maintainHeldBar(long now) {
        if (book.heldBar == null) {
            return;
        }
        if (!Minecraft.getInstance().isWindowActive()) {
            book.heldBar.release();
            book.heldBar = null;
            return;
        }
        book.heldBar.advance(now);
    }

    /** Whether any rail that is on screen is still arriving, which is when a rebuild has to wait. */
    private boolean anyPanelRevealing(long now) {
        for (PanelKind kind : PanelStack.presented(book.columns(), book.panelFolded())) {
            if (book.panelRevealing(kind, now)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Creates a control in the header: added for <b>input</b>, drawn by hand instead.
     *
     * <h2>Why a second registration path exists at all</h2>
     *
     * <p>{@code Screen} keeps two lists, and until now nothing here cared. {@code addRenderableWidget}
     * puts a widget in both: {@code children} for input — clicks, hover, keyboard, narration — and
     * {@code renderables} for the base class's single drawing pass. {@code addWidget} puts it in
     * {@code children} only, which is exactly the arrangement a control in the header needs.
     *
     * <p>The reason is the clip. The widget pass is now clipped to start at the sidebar's list top, so
     * that a scrolled row cannot be drawn through the title bar — see {@code render}. Close sits above
     * that line, so the base pass would never draw it. Registering it here keeps every part of it that
     * is about <i>behaviour</i> with the base class, and moves only the drawing to the chrome layer,
     * which is also where a close button belongs: above the canvas, at the raised Z.
     *
     * <p>The alternative was to duplicate press and hover handling for one button, which would be a
     * second implementation of {@code AbstractWidget}'s own logic — the class of mistake this screen
     * has already been bitten by once, when the preview carried its own copy of the control styles.
     *
     * <p>Remembered in {@link QuestBookScreen#buttons} as well, so tooltips still find it.
     */
    /**
     * The party panel's controls: a Remove button per member the roster permits, plus Leave, Disband and
     * Back.
     *
     * <h2>What is deliberately not a widget, and why</h2>
     *
     * <p>The member <b>rows</b>. A press on a member would either do nothing or remove somebody, and a
     * control that removes a player on one click with no confirmation is not something to add merely
     * because a row is clickable. So the rows are drawn and the Remove buttons are controls, which is
     * the shape the roster itself describes -- {@code PartyRoster.removeSlot} returns null for a member
     * the viewer may not remove, and this makes a widget only where a slot exists.
     *
     * <h2>Why the member geometry is not from BookGeometry</h2>
     *
     * <p>Because a roster's rows are not a fixed layout: how many there are depends on who is in the
     * party. {@code BookGeometry} holds what is true of every window; this is what is true of one roster
     * in one window, and {@code PartyPanelLayout} -- Armature's, and game-free -- produces it. The
     * footer's own controls <i>are</i> fixed, so those do come from the geometry map, as every other
     * fixed control does.
     *
     * <h2>A limit, stated rather than implied</h2>
     *
     * <p>A roster taller than the card is <b>clipped rather than scrollable</b>, and the drawing is what
     * enforces it -- it skips a row that would fall outside the body rather than drawing it over the
     * header. That is a real limitation and an acceptable one: a party of more than six or seven is not
     * the case this is for, and the alternative is a second {@code ScrollView} that moves these widgets
     * the way the sidebar's does. Named here so whoever raises the limit knows what they are raising.
     */
    /**
     * The party button's tooltip: the roster, in words.
     *
     * <h2>Why the state lives here and not on the label</h2>
     *
     * <p>A label is fixed at construction and a tooltip is read per frame, and the roster is the thing
     * that changes. So the button says "Party", true whether you are in one or not, and the answer to
     * which party is one hover away. That also keeps the button's width a property of its label rather
     * than of the longest possible roster, which is what made the hand-written widths in this file wrong
     * in the first place.
     *
     * <p>Three states, and the middle one is why {@code isReal} exists rather than a member count: alone
     * in a party of one is not the same as having no party, and the two have the same number of members.
     */
    private List<Component> partyTooltip() {
        PartyRoster roster = book.partyRoster();

        if (!roster.isReal()) {
            return List.of(Component.translatable("tenet.screen.party.none"),
                    Component.translatable("tenet.screen.party.hint"));
        }

        List<Component> lines = new ArrayList<>();
        lines.add(roster.memberCount() == 1
                ? Component.translatable("tenet.screen.party.solo")
                : Component.translatable("tenet.screen.party.count", roster.memberCount()));
        for (PartyRoster.Member member : roster.members()) {
            lines.add(Component.translatable("tenet.screen.party.member", memberLabel(member),
                    QuestBookScreen.roleName(member)));
        }
        return List.copyOf(lines);
    }

    /**
     * A member's name, marked when it is the reader.
     *
     * <p>Composed here rather than taken from Armature's {@code Member.label()}, which returns English
     * — "you" in parentheses — from a library that deliberately ships no language file. The library
     * owns the facts (a name, a role, whether this is you); the words are this mod's.
     */
    private static Component memberLabel(PartyRoster.Member member) {
        return member.self()
                ? Component.translatable("tenet.screen.party.you", member.name())
                : Component.literal(member.name());
    }

    // ------------------------------------------------------------------
    // Render
    // ------------------------------------------------------------------

    /** How far the dev overlay's panel sits from the frame's top-left corner. */
    private static final int DEV_OVERLAY_INSET = 4;

    /**
     * The dev overlay: the frame rate, then the counting renderer's last report, over the top-left corner.
     *
     * <h2>Why an overlay rather than only the log line</h2>
     *
     * <p>Because the numbers describe a frame, and the frame a person needs to read them on is the one
     * they are changing: zoom a canvas in, pan it, drag a node. Reading a log line after each of those
     * and holding four numbers in your head to compare them is a feedback loop nobody completes, and
     * the two decisions this instrument was added for — whether the canvas's batching holds at high
     * zoom, and whether a measurement cache would have anything to hit — are exactly the kind that are
     * answered by watching a number move while you do the thing.
     *
     * <h2>The frame rate, and whose number it is</h2>
     *
     * <p>{@code Minecraft.getFps()} rather than a counter of this screen's own. The game already measures
     * it once a second for the debug screen, so this is a read of that rather than a second answer to the
     * same question — and a second answer is one that <i>disagrees</i> with F3, which makes both numbers
     * suspect. It is also the number that matters while authoring: not how long this screen took, but
     * whether the client is still smooth with the book open.
     *
     * <p>It is drawn whether or not there is anything counted yet, which is what the first line of the
     * overlay is for. The counters arrive a second into the frame after the first, and a frame rate that
     * appeared a second late would be one you had already finished the gesture without.
     *
     * <p>That is also the whole of its cost on a client with the mode off: this returns immediately, and
     * nothing else here is built — the summary is only ever written by a counting renderer, and one is
     * only built when the mode is on.
     */
    void drawDevOverlay(GuiRenderer r) {
        // **One switch, and it is the operator's.** The frame rate and the counters are one instrument now:
        // `/tenet vitals` draws both, and edit mode draws neither. The counters used to be edit mode's --
        // they were the whole reason a counting renderer was built whenever the tools were on -- which put a
        // panel of numbers over the canvas for every author, measuring nothing in particular. See Vitals for
        // why the switch is an operator's, and why it is re-checked here rather than trusted from the server.
        if (!Vitals.showing()) {
            return;
        }
        String[] lines = devOverlayLines(true, dev.ellipog.tenet.client.dev.CountingRenderer.summary(),
                book.minecraft().getFps(), book.minecraft().getFrameTimeNs(),
                Math.round(book.viewport().scale() * 100F));
        if (lines.length == 0) {
            return;
        }

        int lineHeight = r.lineHeight();
        int width = 0;
        for (String line : lines) {
            width = Math.max(width, r.textWidth(line));
        }
        ArmatureTheme.panel(r, DEV_OVERLAY_INSET, DEV_OVERLAY_INSET, width + 10,
                lines.length * lineHeight + 6, ArmatureTheme.panel(), ArmatureTheme.panelEdge());
        for (int i = 0; i < lines.length; i++) {
            r.text(lines[i], DEV_OVERLAY_INSET + 5, DEV_OVERLAY_INSET + 3 + i * lineHeight,
                    // The frame rate reads as a heading rather than as one more counter: it is the one line
                    // here that is about the whole client rather than about this screen's drawing.
                    i == 0 ? ArmatureTheme.title() : ArmatureTheme.body());
        }
    }

    /**
     * The dev overlay's lines: the frame rate and frame time, then the counters.
     *
     * <h2>Why this is a method rather than four lines inside the drawing</h2>
     *
     * <p>Because the one thing worth pinning about the overlay is that <b>edit mode alone draws nothing</b>,
     * and that is a fact about the lines rather than about the panel drawn around them — so it can be
     * asserted here, with no client, no window and no pixels. The drawing then has one job left: draw what
     * it is handed, or nothing when it is handed nothing.
     *
     * <p>The frame rate comes first because it is the one line about the whole client rather than about this
     * screen's drawing, and it is drawn even before the first counter report arrives — a frame rate that
     * appeared a second late would be one you had finished the gesture without.
     *
     * @param vitals     whether an operator has asked for the overlay; false returns no lines at all, which
     *                   is the whole of "edit mode does not draw it"
     * @param counted    the last reported frame's counters, from {@code CountingRenderer.summary()}
     * @param fps        {@code Minecraft.getFps()} — the game's own number, not a second answer to it
     * @param frameNanos the whole frame in nanoseconds, which has no ceiling where the frame rate does
     * @param zoom       the canvas's scale, as a percentage, or -1 when no canvas is drawn
     */
    static String[] devOverlayLines(boolean vitals, String[] counted, int fps, long frameNanos, int zoom) {
        if (!vitals) {
            return new String[0];
        }
        String[] lines = new String[1 + counted.length];
        // The frame rate **and the frame time**, and the second is the one to read. Frames per second is
        // capped: with vsync on, or a maximum frame rate set, it reports the cap rather than the work, so
        // a change that halves the frame's cost can leave the number exactly where it was.
        // `getFrameTimeNs` is the whole frame in milliseconds and has no ceiling.
        //
        // The zoom is on this line rather than in the counters because it is the same kind of number: a fact
        // about the frame rather than a count of what it drew. It is here at all because every reading this
        // overlay exists for is taken *at a zoom* -- the canvas's whole cost is a function of one -- and the
        // header's own percentage is quest completion, not scale. Reading the wrong number off the screen and
        // filing a row under it is a mistake this project has already made once.
        String scale = zoom < 0 ? "" : "   " + zoom + "%";
        lines[0] = "fps " + fps + "   " + (frameNanos / 1_000_000L) + " ms" + scale;
        System.arraycopy(counted, 0, lines, 1, counted.length);
        return lines;
    }

    /**
     * The frame's layer order, latest drawn on top.
     *
     * <p>On 1.21.1 these were three Z bands ({@code CHROME_Z}, {@code POPOVER_Z},
     * {@code TOOLTIP_Z}): item icons wrote depth 150 while fills drew at 0 without writing any,
     * so a later fill lost the depth test wherever an icon sat, and only height separated the
     * bands. Extraction records every draw in call order, so draw order <i>is</i> the layering
     * now: the book, then the chrome, then the picker, then tooltips, then the dev overlay. The
     * constants are gone with the depth buffer they were measured against; what remains is the
     * sequence in {@code extractRenderState}, which is the thing to keep in order.
     *
     * <p>Kept as a named idea rather than deleted outright, because the ordering constraints are
     * still real: tooltips describe what is under the pointer and must come after it, the picker
     * floats over panels whose controls can carry icons, and the dev overlay reads last so its
     * numbers are the frame's own. A reorder that breaks one of those reads as a layering bug,
     * and this is where that is written down.
     */

    /** The whole book, as a rectangle. Used by the render clip and by nothing else. */
    BookGeometry.Rect panelRect() {
        return book.geometry().panel();
    }

    /**
     * Draws the book. Takes a renderer, so this is the method a test can drive.
     *
     * <p>Separated from {@link #render} for exactly the reason the rest of this round exists: the
     * forced override cannot be tested — it needs a real {@code Screen} with a real client behind it —
     * and everything that decides <i>what is drawn</i> can be, once it is expressed in terms of the
     * seam. {@code RecordingRenderer} feeds this method and asserts on the result without a window.
     *
     * What it does <b>not</b> draw is the controls, and the honest reason is that they belong to
     * {@code AbstractWidget}: the base class iterates its renderables and hands each one the context it
     * was given. So a test driving this method sees the book and not its buttons. That is a real limit
     * worth stating rather than hiding — what it means is that button <i>placement</i> is
     * {@code BookGeometryTest}'s job, and button <i>appearance</i> is {@code ArmatureButton.draw}'s,
     * which does take a renderer and is testable on its own.
     *
     * <h2>Two palettes in one frame, and a test can tell</h2>
     *
     * <p>This is where the black is drawn and where the chapter's colours are drawn, and they are
     * different palettes on purpose — see {@code ArmatureTheme.scope}. The consequence for a test driving
     * this method is worth knowing: every colour it records is the one in force <i>at the point it was
     * drawn</i>, so a scope that leaks shows up as chrome drawn in a chapter's colours rather than as a
     * missing call. {@code ArmatureTheme.scopeDepth} is the blunt check for the same thing.
     */
    public BookCanvas.CanvasCaption renderWith(GuiRenderer renderer, int mouseX, int mouseY, float partialTick) {
        // **Tenet's look, scoped to Tenet's drawing.** The theme is an instance this mod owns now, and
        // this is where it reaches the toolkit: a scope opened around the whole of the book's own drawing,
        // closed however this returns. Another mod's screens open their own and see their own -- which is
        // the property the library had made impossible by holding one global theme for everybody.
        //
        // The canvas caption comes back up rather than drawing here: tests driving this method ignore
        // the return, `render` draws it at TOOLTIP_Z, and drawing order over widgets is not something
        // RecordingRenderer observes (see the tooltips note in renderBook).
        boolean ownFrame = !book.frameActive;
        if (ownFrame) {
            book.snapshotFrame();
        }
        try (ArmatureTheme.Scope look = ArmatureTheme.scope(ClientAppearance.LOOK.main())) {
            return renderBook(renderer, mouseX, mouseY, partialTick);
        }
        finally {
            if (ownFrame) {
                book.clearFrameSnapshot();
            }
        }
    }

    /** The book's own drawing, inside the look's scope. See {@link #renderWith}. */
    private BookCanvas.CanvasCaption renderBook(GuiRenderer renderer, int mouseX, int mouseY, float partialTick) {
        book.bookCanvas.centreCanvas();

        // A tree that arrived since the sidebar was built means the outline is describing a questline
        // this client no longer holds — and the same is true of a gate that opened, an author's view
        // changing, or a language arriving, which are the other three things the outline's rows depend
        // on. All four go through `sidebarStale`, the same question the builder asks, so the outline and
        // the widgets over it can never be built from different answers. See its javadoc.
        //
        // Rebuilt here rather than from a payload handler, because the handlers run before this screen
        // exists as often as after it, and because this is the first point in a frame where clearing and
        // recreating the widgets is safe — `super.render` has not started iterating them yet.
        //
        // No guard: the sidebar is built, drawn and answering in every arrangement there is, so it follows a
        // reload exactly as it does with nothing open. It used to skip while a card was up, on the reasoning
        // that `init` built only the card's controls -- and a rail beside the sidebar is not that case, so
        // the guard's only remaining effect would be a sidebar that went stale whenever a panel was open.
        if (BookSidebar.sidebarStale()) {
            book.rebuildWidgets();
            book.snapshotFrame();
        }

        // The animation clock, read once per frame and handed down. Nothing in this screen or in the
        // toolkit reads a clock itself -- see Tween's javadoc for why that is the property that makes
        // every animation here testable, and `Motion.tween` for how the client's setting reaches it.
        long now = Util.getMillis();

        // The one gesture here with no event to hang off, and the one release that never arrives.
        maintainHeldBar(now);

        // The book first, always, and a scrim over it when a modal is open. The scrim used to be drawn
        // *only* when no overlay was open, so opening a modal replaced the whole book with a flat dim
        // -- and the request was to keep it: "dont close whats behind them, just have it in the
        // background". It is no longer drawn from here -- see below -- but the decision it records is
        // the one the chrome layer still implements.
        //
        // A scrim under a blur, and the two are one decision made in two places: the scrim is what says
        // the book is inert, and the blur -- the call in `render`, once this method has drawn the book --
        // is what makes the card readable over it.
        //
        // This note used to say the blur was a thing deliberately not done, and then that it had been
        // tried and erased the card. Both are worth keeping in view: the fault was real, and its cause
        // was that a post-process leaves the pipeline set for its own passes, so the one layer drawn
        // with no clip of its own had nothing to draw into. `GuiRenderer.blur` puts the pipeline back.
        BookCanvas.CanvasCaption captioned = drawBook(renderer, mouseX, mouseY, now);

        // The scrim and the tools panel used to be drawn here, and both moved into `render`'s raised-Z
        // layer -- the same move, for the same reason, as the view cluster's backing panel: a fill at
        // Z = 0 fails the depth test wherever a node's item icon wrote depth 150, so the panel's own
        // pixels were missing under every icon ("items from nodes render over the tools panel") and the
        // scrim dimmed everything except the icons. Neither can be raised from here: this method sees
        // only a `GuiRenderer`, and the pose is a `GuiGraphics` thing. See the chrome layer.

        // The overlay is NOT drawn here any more. It is chrome, and chrome is drawn by
        // `render`, in the raised-Z layer, after the widget pass -- see `drawModal`. Two things
        // follow, and both were reported faults: the card is genuinely on top of the quest
        // canvas's item icons (which write depth at Z = 150 and so beat a card at Z = 0 whatever
        // the draw order), and the sidebar's rows behind it are *under* it rather than over it.
        //
        // The scrim went with it, in the same move and for the same arithmetic: at Z = 0 it dimmed
        // everything except the icons, which then read as bright smudges under the blur. It is the
        // first thing the raised layer draws, so it is still under the panel, the cluster and the
        // widget pass -- and still before the blur, which is the one ordering it cannot break.

        // Tooltips are deliberately NOT drawn here, and that is a bug fix rather than a preference.
        //
        // They belong over the controls, and the controls are drawn by `render` *after* this returns.
        // A tooltip drawn at this point is therefore underneath the widget it describes — which is
        // what it looked like on screen: hovering a chapter drew its name in a box that the chapter
        // button then painted over, so the tooltip showed as a ghost of text on the button's top edge
        // and read as a rendering fault rather than as a tooltip.
        //
        // Worth recording how it got here, because the comment that caused it is worth reading: this
        // method used to end with drawTooltips, annotated "kept here as well because a test driving
        // renderWith wants to see them". That is a rationalisation rather than a reason — a test can
        // call drawTooltips itself, and now does — and the cost was a visible defect that no test
        // could catch, because drawing order over widgets is not something RecordingRenderer observes.
        // A screenshot found it in five seconds, which is the honest argument for looking at the UI
        // as well as testing it.
        //
        // The canvas caption travels the same way: what drawBook names hoverable comes back up so
        // `render` can draw it at TOOLTIP_Z, where it beats depth-writing pictures by depth rather
        // than by order. See CanvasCaption.
        return captioned;
    }
    /**
     * One row's label, drawn where its slot is.
     *
     * <p>Centred in the row and truncated to the row's own width, with a small pad either side. Every row
     * of the party panel goes through here, so a title and an action label cannot disagree about where a
     * line of text sits in its row.
     */
    private void rowLabel(GuiRenderer r, Slot onScreen, String text, int colour) {
        int textY = onScreen.y() + (onScreen.height() - r.lineHeight()) / 2;
        r.text(Measure.truncate(text, Math.max(0, onScreen.width() - 8), book.textMeasure(r)),
                onScreen.x() + 4, textY, colour);
    }

    /**
     * The same, for a row looked up by its key in the panel's own layout.
     *
     * <p>Rows past the body are clipped by the caller's scissor rather than skipped here, because a
     * scrolled row is partly visible: a test that only asked "is it past the bottom" could not describe
     * one. A key the layout does not hold draws nothing, as before.
     */
    private void rowLabel(GuiRenderer r, Layout layout, String key, Viewport body, String text,
                          int colour) {
        Slot slot = layout.slot(key);
        if (slot == null) {
            return;
        }
        rowLabel(r, BookQuestEditor.screenSlot(body, slot), text, colour);
    }

    private BookCanvas.CanvasCaption drawBook(GuiRenderer r, int mouseX, int mouseY, long now) {
        // The server's own answer for a moved node arrives with a tree, and that is the moment the editor's
        // remembered position is no longer needed. Noticed here rather than in a handler because this is a
        // comparison of two numbers on the frame path that already reads them.
        long revision = ClientQuestCache.treeRevision();
        book.editors.onRevision(revision);
        // The settings page's pending values expire on the same signal: the tree arriving is what makes
        // the file's own answer the current one, and the draft is only there to cover the round trip.
        book.settingsDraft.onRevision(revision);
        // A drag outlives revisions: what the pointer is showing is the author's current ask, so the
        // expiry must not snap it back mid-gesture — the release commits the dragged value.
        book.bookRewards.stampDragging(revision);
        // The card fields' drafts expire on the copy instead, because these values are drawn from the
        // replica and the replica lags: a draft is dropped when the copy holds what was asked for, and
        // a copy that disagrees past the backstop wins. See FieldDraft for why the two rules differ.
        ClientChapterReplica.Copy copy = ClientChapterReplica.of(QuestBookScreen.effectiveChapter());
        QuestBookScreen.fieldDraft.reconcile(QuestBookScreen.effectiveChapter(),
                copy == null ? null : copy.revision(),
                (owner, path) -> {
                    if (dev.ellipog.tenet.client.dev.FieldDraft.BOOK_OWNER.equals(owner)) {
                        // The book's values are in no chapter copy: the tree is their only source, so
                        // the tree's own value is what a book draft converges against. Empty means the
                        // key is absent, which is the null a cleared draft is waiting for. Raw, not
                        // resolved: a translation written back would replace the author's own words.
                        String value = "bookTitle".equals(path) ? ClientQuestCache.bookTitleRaw()
                                : "bookIcon".equals(path) ? ClientQuestCache.bookIconId() : null;
                        if (value == null) {
                            return null;
                        }
                        return value.isEmpty() ? com.google.gson.JsonNull.INSTANCE
                                : new JsonPrimitive(value);
                    }
                    JsonObject tree = copy == null ? null
                            : dev.ellipog.tenet.client.dev.FieldDraft.CHAPTER_OWNER.equals(owner)
                                    ? copy.chapterTree()
                                    : owner.startsWith(QuestBookScreen.ELEMENT_OWNER_PREFIX)
                                            // An element's values are in the chapter's own copy, because the
                                            // element is a member of that file. Absent means the element is
                                            // gone -- deleted, or the id renamed -- which is the null a cleared
                                            // draft is waiting for.
                                            ? ChapterPanelLayout.elementById(copy.chapterTree(),
                                                    owner.substring(QuestBookScreen.ELEMENT_OWNER_PREFIX.length()))
                                            : copy.quests().get(owner);
                    return tree == null ? null : QuestPanelLayout.get(tree, path);
                },
                Util.getMillis());
        // And the shape draft, against the same revision: an element the tree now holds is one whose insert
        // arrived, and one it no longer holds is a removal that did. See `ElementDraft` for why that exact
        // agreement needs no revision number, and why the backstop is what ends a belief the server renamed.
        QuestBookScreen.elementDraft.reconcile(QuestBookScreen.effectiveChapter(), ClientQuestCache.elements(QuestBookScreen.effectiveChapter()),
                Util.getMillis());
        // And the links' shape draft, against the same revision and for the same reason: a marker the
        // tree now holds is one whose insert arrived, and one it no longer holds is a removal that did.
        QuestBookScreen.linkDraft.reconcile(QuestBookScreen.effectiveChapter(), ClientQuestCache.links(QuestBookScreen.effectiveChapter()),
                Util.getMillis());
        // A pending look is spent once the draft behind it is: the preview and the write carry the same
        // patch, so a pending patch that outlived its draft would mask whatever changed the chapter next.
        if (QuestBookScreen.pendingChapterTheme != null
                && QuestBookScreen.fieldDraft.value(QuestBookScreen.effectiveChapter(),
                        dev.ellipog.tenet.client.dev.FieldDraft.CHAPTER_OWNER, "themePatch") == null) {
            QuestBookScreen.pendingChapterTheme = null;
        }
        // And the selection, because an id this tree does not hold is a phantom the next gesture would
        // act on -- see `pruneSelection` for why the revision is the right moment and the only one.
        BookCanvas.pruneSelection(revision);
        // And the same revision decides whether a panel's copy of the chapter is current. `claim` is what
        // keeps this from being one request per frame: it says yes only while no usable copy exists, and
        // no faster than its retry window -- so a refusal or an empty answer is retried rather than
        // leaving the panel stuck, without turning a broken chapter into a request flood.
        if (book.mayEditNow() && ClientChapterReplica.claim(QuestBookScreen.effectiveChapter(), revision, Util.getMillis())
                && ClientEditReplies.noteSent(QuestBookScreen.REPLICA_SENTINEL) != null) {
            TenetNetworking.requestReplica(QuestBookScreen.effectiveChapter());
        }

        int left = panelLeft();
        int top = panelTop();
        int panelW = panelWidth();
        int panelH = panelHeight();

        ArmatureTheme.panel(r, left, top, panelW, panelH, ArmatureTheme.panel(), ArmatureTheme.panelEdge());

        // The two surfaces inside the panel, and both of them are now rounded on the corners they share
        // with it. Their rounds are one pixel tighter than the panel's, which is what keeps the gap
        // between the two curves even: reusing the panel's radius would make the panel's border thicker
        // at the corners than along its sides.
        //
        // The masks are the part that has to be right, and each says which corners the surface is
        // actually touching. The sidebar runs the full height of the left edge, so it rounds its two
        // left corners and leaves the right-hand pair square -- its right edge is interior, and rounding
        // it would leave two notches in the middle of the header strip. The header starts where the
        // sidebar ends and runs to the right edge, so it rounds its top-right corner and nothing else.
        // The author's band is under the header and touches nothing, so it rounds nothing.
        //
        // Getting a mask wrong is visible and diagnosable rather than subtle, which is the whole reason
        // the mask is a value at the call site instead of a guess inside `fillSurface`.
        int innerRadius = Math.max(0, ArmatureTheme.current().cornerRadius() - 1);
        // The wipe the header travels on when the list is docked: read here because the header
        // starts past the list. The list's own surface is NOT drawn here — it lives in the chrome
        // layer (`drawSidebarChrome`), because the canvas is drawn after this method and fills its
        // whole rect: a surface drawn here would be buried under the graph the moment the list
        // floats or slides, which is exactly the "no background" fault that moved it. Rails moved
        // for the same reason; see the chrome layer's own note.
        SidebarState sidebarShown = book.geometry().sidebarState();
        boolean sidebarTall = !sidebarShown.floating();
        // The wipe the header travels on when the list is docked: read here because the header
        // starts past the list.
        int sidebarWipeWidth = book.sidebarSliding(now) ? book.sidebarWipeRect(now).width()
                : book.geometry().sidebar().width();
        // The header beside (or above) it: docked, it starts past the list with a square left edge
        // against the travelling surface; floating, it spans the panel with both top corners round.
        int headerLeft = sidebarTall ? left + sidebarWipeWidth : left + 1;
        int headerWidth = Math.max(0, left + panelW - 1 - headerLeft);
        ArmatureTheme.fillSurface(r, headerLeft, top + 1, headerWidth,
                HEADER_HEIGHT - 2, ArmatureTheme.raised(), innerRadius,
                sidebarTall ? ArmatureTheme.TOP_RIGHT : ArmatureTheme.CORNERS_TOP);
        r.fill(left + 1, top + HEADER_HEIGHT - 1, left + panelW - 1, top + HEADER_HEIGHT,
                ArmatureTheme.panelEdge());
        // The author's band, which is the second chrome strip: the same raised surface as the header, the
        // same rule under it, and no round corners because it is interior on all four sides. It is drawn
        // over the sidebar's recess, which is what makes the recess read as starting below it.
        //
        // Full width, and that is the point of the band rather than a detail: it is the panel's chrome, so
        // it spans the sidebar and the canvas both, and no rail can reach it -- every rail is anchored to
        // the canvas, whose top edge is the band's bottom. The buttons on it are built by
        // `buildHeaderChrome` and painted by the chrome layer, because the widget pass's clip starts below
        // this.
        int bodyTop = book.geometry().bodyTop();
        if (bodyTop > top + HEADER_HEIGHT) {
            ArmatureTheme.fillSurface(r, left + 1, top + HEADER_HEIGHT, panelW - 2,
                    bodyTop - (top + HEADER_HEIGHT) - 1, ArmatureTheme.raised(), innerRadius,
                    ArmatureTheme.CORNERS_NONE);
            r.fill(left + 1, bodyTop - 1, left + panelW - 1, bodyTop, ArmatureTheme.panelEdge());
        }
        // A divider between the sidebar and everything else is drawn with the list's own surface
        // in the chrome layer (`drawSidebarChrome`): down here the canvas fill would bury it, which
        // is why the old one never showed. The band's rule above stays: it is above the canvas, so
        // this method's own fills still reach it.

        // The sidebar's surface, divider, grip and scrollbar all live in the chrome layer now
        // (`drawSidebarChrome`): the canvas is drawn after this method and fills its whole rect, so
        // anything of the list's drawn here would be buried under the graph the moment the list
        // floats or slides. See that method for the full reason.

        // The party strip, drawn here beside the sidebar's scrollbar because it is chrome rather than
        // content: it describes the player's own situation, not the questline on screen, so no chapter's
        // theme may reach it. Drawing it in the book's pass is what keeps it outside every scope.
        // The party button's tooltip, refreshed here, because that is where the roster's state goes now:
        // the label says "Party" and the tooltip says who is in it. A tooltip the drawing rebuilds cannot
        // go stale, which is the one thing a label set at construction can do.
        if (book.partyButton != null) {
            book.partyButton.tooltip(partyTooltip());
        }

        // The header's own inset, shared with Close's rectangle -- see `BookGeometry.HEADER_INSET`,
        // where the two ends of the header are one number rather than a literal here and a margin there.
        // The pack's icon and name, when its index declares them; the translatable title and no icon
        // otherwise. See `drawHeaderIdentity`.
        drawHeaderIdentity(r, left, top);
        // Which chapter is showing, read before the empty states because one of them is about it: "every
        // chapter is withheld from you" is not "this server has no quests", and it is the sentence a
        // reader of a pack that hides its content behind prerequisites actually sees.
        String chapter = QuestBookScreen.effectiveChapter();
        if (ClientQuestCache.hasData() && chapter != null) {
            drawHeaderSummary(r, left, top);
        }

        if (!ClientQuestCache.hasData() || chapter == null) {
            // Three empty states, and each is a different thing to do about it. Saying "waiting" when it
            // is really "nothing loaded" sends someone hunting a sync bug that does not exist; saying
            // "nothing loaded" when the pack is simply holding everything back sends them to the server
            // log for a fault that is not there either. The count in the header is deliberately not drawn
            // in the third case, because "0" beside "nothing is visible yet" is two answers to one
            // question -- see `drawHeaderSummary`.
            String key = !ClientQuestCache.hasData()
                    ? (ClientQuestCache.hasTree()
                            ? "tenet.screen.quest_book.no_quests"
                            : "tenet.screen.quest_book.waiting")
                    : "tenet.screen.quest_book.nothing_visible";
            Component message = Component.translatable(key);
            // Centred on the graph as drawn — full-body when the list floats or has slid away, beside
            // it when docked — rather than on an assumed 156-pixel column.
            BookGeometry.Rect emptyCanvas = book.geometry().canvas();
            r.centredText(message.getString(), emptyCanvas.x() + emptyCanvas.width() / 2,
                    top + panelH / 2, ArmatureTheme.body());
            return null;
        }

        // **Skipped for a modal, and drawn beside a docked column**, which is the whole of the difference
        // between the two presentations and the fault this condition used to cause.
        //
        // A card wants the graph gone, for two reasons that are both about the card: it is drawn above the
        // canvas so a graph behind it is invisible anyway, and a graph *moving under a scrim* is a
        // distraction with no reader. A column wants the opposite -- it sits beside the graph, and the graph
        // beside it is the thing the mode exists to keep usable, so hiding it leaves an empty field with a
        // view cluster floating in it.
        //
        // The depth worry that kept this closed does not apply to a column either: the canvas's item icons
        // are 3D renders that write depth 150 above their pose, and the column is drawn at `CHROME_Z` (400),
        // which clears them by the arithmetic that constant already documents. The sidebar and the header
        // stay in both arrangements, because those are what "have it in the background" is about: where you
        // are, not what you were looking at. Null unless the canvas below names something hoverable.
        BookCanvas.CanvasCaption captioned = null;
        if (chapter != null) {
            captioned = book.bookCanvas.drawCanvas(r, mouseX, mouseY, book.questsIn(chapter), now);
        }

        // The canvas is done, and the view cluster's backing panel is *not* drawn here any more.
        //
        // It used to be, with a `flush()` in front of it and a comment claiming the flush was what put
        // the panel over the nodes. That comment was wrong and the flush was not the fix: a node's icon
        // is at Z=150 and writes depth, so a panel drawn after it at Z=0 is rejected by the depth test
        // whatever the draw order is. The panel now lives in `render`, inside the raised chrome Z, which
        // is where the ordering it actually needs can be expressed. See CHROME_Z.
        //
        // The flush stays, because draining the canvas here is still right: it puts the nodes on screen
        // before the chrome layer is queued, so the two are in separate batches and nothing about the
        // chrome depends on how the canvas happened to batch.
        r.flush();
        // No canvas, no caption: the modal and the empty states above name nothing hoverable.
        return captioned;
    }

    /**
     * Draws the hovered control's tooltip, last of all.
     *
     * <p>Not vanilla's tooltip: that would draw in vanilla's style, which is the thing this UI avoids.
     * Drawn here, after the widgets, so it is over everything and not clipped by the canvas scissor.
     */
    /**
     * A hovered link's target, in the same box a control's tooltip uses.
     *
     * <p>Because a link's whole affordance is an underline and a change of ink: without the address, the
     * reader knows something is clickable and not where it goes, and the address is the one thing a reader
     * of a link wants before pressing it.
     */
    void drawLinkTarget(GuiRenderer r, int mouseX, int mouseY) {
        for (QuestBookScreen.LinkRect link : book.linkRects) {
            if (link.box().contains(mouseX, mouseY)) {
                if (link.quest().isEmpty()) {
                    drawTooltip(r, List.of(link.url()), mouseX, mouseY);
                }
                else {
                    // The quest's own title, where there is one: the reader wants where it goes.
                    ClientQuestCache.Entry target = book.entryFor(link.quest());
                    drawTooltip(r, List.of(target == null ? link.quest() : target.titleText()),
                            mouseX, mouseY);
                }
                return;
            }
        }
    }

    /**
     * The type name beside a hovered icon, drawn with the tooltips rather than with the panel.
     *
     * <h2>Why "last in the panel" was not last enough</h2>
     *
     * <p>These were the last thing the panel drew, and that was not enough: an <b>item's</b> own render
     * writes depth, so a box drawn *after* an icon at the *same* Z can still lose to it -- and the report
     * was exactly that, *"items render over tooltips"*. A panel's contents are at {@code CHROME_Z}; what
     * belongs over everything is drawn two levels above it, which is where the tooltips already were and
     * where this now goes (see the call site in {@code render}).
     *
     * <p>The anchors are in screen coordinates and are recorded while the panel is drawn, so the only
     * thing left to do here is place the box: it opens under the control it names, moves when there is no
     * room below, and is folded and clamped by the toolkit, so the panel's own edges -- and the window's,
     * which the panel's are not always -- are answered in one place rather than eleven.
     */
    void drawPendingLabels(GuiRenderer r) {
        for (QuestBookScreen.PendingLabel label : book.pendingLabels) {
            BookGeometry.Rect anchor = label.anchor();
            // The toolkit's box, the same one a pointer tooltip gets: the theme's three tooltip tokens,
            // the fold, the move to a side that fits and the clamp -- so a caption at an icon by the
            // window's edge repositions rather than leaves, and a long id folds rather than runs off.
            Tooltips.drawAt(r, List.of(label.text()), anchor.x(), anchor.y(), anchor.width(),
                    anchor.height(), book.width, book.height);
        }
    }

    /**
     * The type's own explanation, under a hovered task or reward row.
     *
     * <h2>What the row cannot say, and what the sentence therefore leaves out</h2>
     *
     * <p>A row is one sentence: "Kill Zombie", "Grant the stage my_pack:inducted". That is the right
     * length for a list, and it is not enough to <i>act</i> on -- the reader who has never met the type
     * cannot tell a stage from a statistic from a command, and the row has no room to say. The tooltip
     * is where that answer goes: the type's name, what it is for, the fields it takes, and the id a
     * file spells.
     *
     * <p>Collected while the card draws and painted here, with the tooltips -- see
     * {@code rowTooltips} for why the card cannot draw it where the row is.
     */
    void drawRowTooltips(GuiRenderer r, int mouseX, int mouseY) {
        for (QuestBookScreen.RowTooltip tooltip : book.rowTooltips) {
            if (tooltip.box().contains(mouseX, mouseY)) {
                drawTooltip(r, tooltip.lines(), mouseX, mouseY);
                return;
            }
        }
    }

    public void drawTooltips(GuiRenderer r, int mouseX, int mouseY) {
        // **Every control is live, so every control's tooltip belongs here.** This used to start the walk
        // past the book's own controls while a modal was up, because they were drawn behind a scrim and
        // must not answer a hover. Nothing is behind anything now: a rail covers no control, and the
        // sidebar, the header and the view cluster are live whatever is open -- so skipping them would be
        // those three losing their tooltips for no reason at all.
        //
        // The hovered element's title comes first, and the order is the point: the pointer is over the canvas
        // rather than over a control, and a title suppressed by a button that happened to sit above the canvas
        // would be a tooltip that came and went. Only a title the author did not paint into the picture gets
        // one -- see `hoveredElementTitle`.
        String elementTitle = book.bookCanvas.hoveredElementTitle();
        if (elementTitle != null) {
            drawTooltip(r, List.of(elementTitle), mouseX, mouseY);
            return;
        }
        // The floating box's pin dot is chrome rather than a widget, so it owns no tooltip of
        // its own: one short line here, under the same hover the press answers, saying what the
        // press does.
        if (book.geometry().sidebarState().floating() && book.geometry().sidebarState().expanded()
                && book.geometry().sidebarBoxPin().contains(mouseX, mouseY)) {
            drawTooltip(r, List.of(Component.translatable(DevMode.isSidebarPinned()
                    ? "tenet.screen.sidebar_unpin" : "tenet.screen.sidebar_pin").getString()),
                    mouseX, mouseY);
            return;
        }
        for (ArmatureButton button : book.buttons) {
            if (button.tooltip() != null && button.isMouseOver(mouseX, mouseY)) {
                drawTooltip(r, button.tooltip(), mouseX, mouseY);
                return;
            }
        }
    }

    /**
     * A themed tooltip box.
     *
     * <p>Hand-drawn for the same reason the buttons are: vanilla's renderer paints its own frame and
     * background, and a tooltip is the most visible piece of chrome a screen has. Flipped to the other
     * side of the pointer when it would run off the right or the bottom, which is the one piece of
     * behaviour worth copying from vanilla's positioner.
     *
     * <p>Lines are plain strings. They were {@code FormattedCharSequence} until the seam landed, which
     * is a shape that cannot cross it without dragging {@code Style} along — and every tooltip in both
     * mods is built from {@code Component.literal}, so there was no style to lose. See
     * {@code ArmatureButton.tooltip} for the same note on the other side of the call.
     */
    /**
     * The box near the pointer, from the toolkit: the theme's tooltip tokens and the flip geometry
     * every screen shares. The wrapper stays because the call sites read better with the screen's own
     * size folded in.
     */
    void drawTooltip(GuiRenderer r, List<String> lines, int mouseX, int mouseY) {
        Tooltips.draw(r, lines, mouseX, mouseY, book.width, book.height);
    }

    // ------------------------------------------------------------------
    // The multi-selection's whole-hand gestures
    // ------------------------------------------------------------------

    /**
     * Reads whatever the server said since the last frame, and rebuilds the quest panel when its sources
     * have moved under it.
     *
     * <p>The only place an author is told about an edit, because the screen is the only thing that can tell
     * them: the payload handler keeps the answer where this finds it, since the reply arrives on the game
     * thread with whatever is open — which may be nothing. See {@code ClientEditReplies}.
     *
     * <p>The panel's rows are built from the replica at rebuild time, so a tree or a copy arriving after
     * they were built leaves the panel showing the past. The rebuild is here rather than on the frame
     * path, because a rebuild inside a render destroys the very field being typed into -- and tick runs
     * between frames, where a widget can be replaced safely.
     */
    void tick() {
        book.superTick();

        // The rails have finished arriving, so their controls stop travelling and go live. One rebuild,
        // on the frame the last of them settles, and never on the frame path: a reveal read while drawing
        // would be a state read and a widget list changed inside a render.
        //
        // The two ways this is skipped are both deliberate: with motion off the duration is zero, so a rail
        // is already settled on the frame it opens and `init` built its controls with everything else; and a
        // rebuild that is not owed is not done. `panelBuildPending` is set by the per-frame slide for exactly
        // the case this exists for -- a rail whose widgets travelled and are still marked inert.
        //
        // **Per rail, and asked of the arrangement rather than of a field.** One timestamp for the whole
        // arrangement meant opening a panel re-slid the dock beside it, which reads as the dock closing and
        // reopening; and "is anything still arriving" has to be asked of every rail that is on screen, or a
        // rail that settles last would never go live.
        long now = Util.getMillis();
        if (book.panelBuildPending && !anyPanelRevealing(now)) {
            book.panelBuildPending = false;
            book.rebuildWidgets();
        }
        // The list's wipe has landed, so it forgets when it started: a toggle closed and reopened
        // later begins its second wipe at the beginning rather than part-way through the first. No
        // rebuild — the widgets were built for the target on the action's own frame, so settling
        // only ends the press-absorb and the travel, both of which read this flag. A departure
        // is the exception that needs nothing: its rows were filed as orphans when it started
        // and the sliver stands behind them, so settling only discards them — and rebuilding
        // every column here would recreate the rails and the header under a stationary pointer,
        // and every hovered control would blink the frame it lands.
        if (book.sidebarSlideStart >= 0 && !book.sidebarSliding(now)) {
            book.sidebarSlideStart = -1;
        }
        if (book.sidebarDeparting && book.sidebarSlideStart < 0) {
            book.sidebarDeparting = false;
            book.sidebarDeparted.clear();
            book.sidebarDepartAnchor = null;
        }
        // Everything settled forgets when it started, so a kind closed and reopened later begins its second
        // arrival at the beginning rather than part-way through the first.
        book.panelRevealStarts.entrySet().removeIf(entry -> !book.panelRevealing(entry.getKey(), now));
        // And everything sunk is owed its cleanup — selectively. A cleanup stamped under the
        // arrangement still standing runs: the close is the latest thing that happened, so nothing
        // fresh stands where its nullings land. One stamped under an older arrangement stays parked
        // for its own kind's reopen (or the screen's end): something moved since, and running it under
        // the new arrival would null state that arrival just set. The rail is forgotten either way, so
        // a later close schedules fresh rather than re-running the old.
        java.util.Iterator<java.util.Map.Entry<PanelKind, Long>> sunk =
                book.panelDepartStarts.entrySet().iterator();
        boolean settledExit = false;
        while (sunk.hasNext()) {
            PanelKind kind = sunk.next().getKey();
            if (!book.panelDeparting(kind, now)) {
                java.util.List<QuestBookScreen.StampedTeardown> owed = book.pendingTeardowns.get(kind);
                if (owed != null) {
                    int before = owed.size();
                    owed.removeIf(stamped -> {
                        if (stamped.epoch() == book.arrangementEpoch) {
                            stamped.teardown().run();
                            return true;
                        }
                        return false;
                    });
                    settledExit |= owed.size() != before;
                    if (owed.isEmpty()) {
                        book.pendingTeardowns.remove(kind);
                    }
                }
                sunk.remove();
                book.departRails.remove(kind);
            }
        }
        if (settledExit) {
            // The mute was computed at close time, from state the teardown just took away: a settings
            // page that covered the body is gone, so recompute who answers rather than leaving the new
            // arrangement's controls muted behind it. No widget list changes, so tick may do it.
            book.refreshOverlayMute();
        }

        // A choice the server is holding comes to the front. It is a question the player asked by
        // claiming, so it is not left waiting behind a panel they opened since -- and it is read here
        // rather than in the drawing, because opening a panel re-places every widget.
        if (ClientChoiceOffers.current() != null && book.overlay != PanelKind.CHOICE) {
            book.bookOverlay.openChoiceOffer();
        }

        // The book's own notices: what has faded goes. A quest that has just completed is not read here
        // any more -- QuestNotifier owns that diff and calls `notifyQuestCompleted` -- and expiring is
        // bookkeeping, which is why it lives in tick rather than in the drawing.
        long millis = Util.getMillis();
        book.toasts.expire(millis);
        // The locate flash's own expiry, for the same reason: the drawing asks `CanvasReveal.flash`
        // every frame and draws nothing once it is over, and the state that named the node goes when
        // the flash does rather than outliving it.
        if (book.flashQuest != null && millis - book.flashStart >= CanvasReveal.FLASH_MILLIS) {
            book.flashQuest = null;
        }

        // The rewards panel is a list of what the server owes, so a progress sync changes it: a claim from
        // anywhere -- a row, Claim all, the command, a teammate's team reward -- lands here as a row that
        // should not be there any more. Rebuilt on the revision, the same way the dock's panel follows the
        // tree, and only while the card is open because that is the only time the rows are read.
        //
        // A **mark** moving rebuilds it too, and that is the second half of the fix: a mark that has been
        // answered or has expired changes which buttons are live, and the drawing alone cannot take a
        // button's label or its `active` flag back. The marks are no longer cleared here -- the ones still
        // in flight belong to presses nobody has answered yet, which is exactly what the wholesale clear
        // used to get wrong.
        book.bookRewards.reconcilePendingClaims(millis);
        // The sidebar's waiting counts ride the row buttons as suffixes, and a claim lands as a
        // changed map — see syncSidebarSuffixes. Here rather than in the drawing, because setting
        // a widget is state and state does not belong inside a render.
        book.bookSidebar.syncSidebarSuffixes();
        if (book.overlay == PanelKind.REWARDS) {
            long progress = ClientQuestCache.progressRevision();
            boolean marksMoved = book.rewardMarksRevision != book.pendingClaims.revision();
            if (progress != book.rewardsRevision || marksMoved) {
                book.rewardsRevision = progress;
                book.rewardMarksRevision = book.pendingClaims.revision();
                book.rebuildWidgets();
            }
        }
        else if (book.overlay == PanelKind.QUEST && !book.mayEditNow()) {
            ClientQuestCache.Entry entry = book.entryFor(book.overlayQuest);
            if (entry != null) {
                int taskIndex = book.bookAssets.firstManualTask(entry);
                java.util.UUID self = book.minecraft().player == null ? null : book.minecraft().player.getUUID();
                boolean claimable = self != null && book.claimableWithMarks(self, entry);
                if (claimable != book.bookSidebar.readerFooterClaimable || taskIndex != book.bookSidebar.readerFooterTaskIndex) {
                    book.rebuildWidgets();
                }
            }
        }

        long revision = ClientQuestCache.treeRevision();
        ClientChapterReplica.Copy copy = ClientChapterReplica.of(QuestBookScreen.effectiveChapter());
        long replicaRevision = copy == null ? -1 : copy.revision();
        if (revision != questPanelRevision || replicaRevision != questReplicaRevision) {
            questPanelRevision = revision;
            questReplicaRevision = replicaRevision;
            // `dockOpen` rather than a second spelling of it: the question here is exactly "is the author's
            // dock up on the Chapter tab", which is the one rebuild a replica arriving has to trigger for a
            // tab nobody is looking at otherwise.
            boolean dockPanel = book.dockOpen && book.toolsTab == ToolsLayout.Tab.CHAPTER;
            // **And the element panel, which is the third reader of a chapter's own file.** Its rows are built
            // from the drafted element, so a flag, a colour or a word the server has just confirmed is a row
            // whose *value* has changed while its widget still holds the old one -- and a button cannot repaint
            // itself from a tree. Without this the panel a click opens was the one place in the editor that did
            // not follow an edit: the flag it had just written read the old state until something else happened
            // to rebuild the list.
            boolean elementPanel = book.overlay == PanelKind.ELEMENT;
            // The dock's picker counts as an editor for this purpose: a replica arriving while it is
            // open changes the chapter under the list, and the card's title and the "current" row are
            // read from that chapter. Rebuilding keeps the typed query -- `buildPickerWidgets` carries
            // the box's value across a rebuild -- so this cannot eat what is being searched for.
            boolean modalEditor = (book.overlay == PanelKind.QUEST || book.overlay == PanelKind.PICKER) && book.mayEditNow();
            if (dockPanel || elementPanel || modalEditor) {
                book.rebuildWidgets();
            }
        }

        // The card follows the **caret**, and only when the caret has moved: an editor below the fold is
        // an editor typed into blind, but a view that re-scrolls to its own box every tick is a view that
        // fights the wheel -- and with a description taller than the body it did not even settle, because
        // the "bottom is below the fold" and "top is above it" branches took turns. That was the report
        // (*"scrolling while having the cursor somewhere in the text works super gimmicky"*), and it asked
        // for exactly this split: the wheel wins, the caret follows when the caret is what moved.
        //
        // In tick rather than in the drawing, because scrolling re-places widgets and that is not a thing
        // to do inside a render.
        //
        // And not while the rail is still sliding in: the caret cannot have moved inside an editor the
        // player has had for a tenth of a second, while a scroll now would re-place widgets the slide is
        // about to move again — the two passes fighting over one list for the whole of the arrival.
        if (book.overlay == PanelKind.QUEST && book.mayEditNow()
                && !book.panelRevealing(PanelKind.QUEST, now)) {
            book.editorControls.followCaret();
        }

        // And the sidebar scrolls itself while a row is held near an edge. The flat lists deliberately
        // do not auto-scroll -- they are short enough to wheel mid-drag -- but the tree is the one list
        // that routinely does not fit, and "drag it to the group at the bottom" has to be possible
        // without letting go. In tick rather than in the drag's follow, because a pointer held still at
        // the edge stops producing mouse events and the list has to keep moving.
        if (book.sidebarDragLive) {
            book.bookSidebar.autoScrollSidebar();
        }

        // The server's undo history can be thrown away without this client having asked for anything --
        // `/tenet reload` drops every open editor. The *counters* behind the undo button are this screen's,
        // so they have to be reset here or the book keeps offering an undo over a history that is gone: a
        // Ctrl+Z that sends an op, gets nothing back, and says nothing. See `ClientEditHistory`.
        if (ClientEditHistory.takeDiscarded()) {
            book.tableApplied = 0;
            book.tableUndone = 0;
            // The drafts go with it, for the same reason the server's own `forget` drops the model: a pending
            // value is an edit recorded against the files as they were, and the reload has replaced them.
            QuestBookScreen.fieldDraft.forgetChapter(QuestBookScreen.effectiveChapter());
        QuestBookScreen.elementDraft.clear(QuestBookScreen.effectiveChapter());
        QuestBookScreen.linkDraft.clear(QuestBookScreen.effectiveChapter());
            book.settingsDraft.clear();
            book.bookInput.authorReport("The server reloaded, so the undo history was discarded");
        }

        // What the reload found wrong with the pack -- a dangling dependency, a cycle between files, an id in
        // two chapters. None of them is visible to the per-file validator that guards a save, so before this
        // the edit succeeded, the fault was written, and only the server log knew. Reported after the tree, so
        // the canvas is already drawing the pack these problems are about.
        //
        // **One short sentence per report, not one per line.** The lines used to be toasted one by one, which
        // put a `file:line:col: severity: message` on screen for each fault -- a sentence far longer than a
        // toast box, so it was truncated -- and, because the payload's text carried the validator's own `at
        // $.tasks[2]` continuation as a second line, a bare "at $" card beside it. The lines belong where
        // there is room for them: the Chapter tab lists them under `Problems`, and the server log has every
        // one with its file and line. A toast is a pointer, and a pointer has to fit.
        for (ClientEditProblems.Report report : ClientEditProblems.drain()) {
            for (String line : report.lines()) {
                Constants.LOG.info("the pack has a problem: {}", line);
            }
            book.bookInput.authorToast(Component.translatable("tenet.dev.problems.toast", report.count()).getString(), true);
        }

        // Every answer nobody has read yet, oldest first. A burst of quick edits -- spamming a stepper
        // -- puts several between two ticks, and the store used to keep only the last, so a refusal in
        // the middle was lost. A refusal also drops that chapter's pending values: the edit did not
        // stick, and the copy's own answer is the truth.
        for (EditorReplyPayload reply : ClientEditReplies.drain()) {
            // Which request this answers, and what it means for the table panel. **The id is preferred and
            // the position is the fallback**, not the other way round: an answer naming its request is
            // matched to that request however many others are in flight, and only an answer carrying no id
            // -- a broadcast, or a server that replied before reading one -- is matched to the oldest.
            //
            // Both doors consume from one queue, so a request matched by id is not then matched again by
            // position. That is why this is a choice between two calls rather than two lookups.
            ClientEditReplies.Request matched = reply.requestId() == EditorReplyPayload.NO_REQUEST
                    ? null
                    : ClientEditReplies.takeById(reply.requestId());
            String answered = reply.requestId() == EditorReplyPayload.NO_REQUEST
                    ? ClientEditReplies.takeSent()
                    : (matched == null ? null : matched.marker());
            boolean replicaAnswer = QuestBookScreen.REPLICA_SENTINEL.equals(answered);
            if (answered != null && !answered.isEmpty() && !replicaAnswer) {
                book.bookInput.applyTableReply(answered, reply);
                if (!reply.ok()) {
                    // The edit did not stick, so nothing should pretend it did: the drafted value goes
                    // and the panel draws what the file actually holds.
                    BookRewards.tableDraft.forgetOwner(QuestBookScreen.effectiveChapter(), book.bookRewards.tableOwnerKey());
                }
            }
            if (!reply.ok()) {
                QuestBookScreen.fieldDraft.forgetChapter(reply.chapter());
            QuestBookScreen.elementDraft.clear(reply.chapter());
            QuestBookScreen.linkDraft.clear(reply.chapter());
                // The settings page's pending values are the same kind of ask and end the same way. A
                // refusal does not move the tree, so `onRevision` would never drop them and the preview
                // would keep drawing — and the next arrow press would accumulate from — the value the
                // server just said no to. The page belongs to the effective chapter, so only that
                // chapter's refusal clears it; a drag re-stamps itself on the next frame either way.
                if (reply.chapter().equals(QuestBookScreen.effectiveChapter())) {
                    book.settingsDraft.clear();
                }
            }
            // A table op's chapter is empty by design -- a table is a file, not a chapter's -- so the
            // comparison below would classify every table reply as "about a chapter you navigated away
            // from" and skip it. That is what swallowed every table refusal and every import report: the
            // only thing an author saw was the optimistic line the client said before asking.
            boolean tableReply = answered != null && !answered.isEmpty() && !replicaAnswer;
            if (!tableReply && !reply.chapter().equals(QuestBookScreen.effectiveChapter())) {
                // The answer is about a chapter the author has navigated away from. It is still news about
                // that chapter's copy -- a refusal is exactly why its panel would keep saying "has not
                // arrived yet" -- so it is recorded and logged rather than dropped in silence, which is what
                // made the placeholder's lie impossible to diagnose.
                if (!reply.ok()) {
                    String said = String.join(" ", reply.lines());
                    // A table replica first, because it is the one that arrives with no chapter to its name:
                    // recorded against the table it was asked for, which is the only way the panel that is
                    // waiting for that table can say what the server said instead of saying nothing.
                    if (replicaAnswer && book.replicaAskedFor != null) {
                        dev.ellipog.tenet.client.ClientTableReplica.refuse(book.replicaAskedFor, said);
                        Constants.LOG.info("table replica for \"{}\" was refused: {}",
                                book.replicaAskedFor, said);
                        book.replicaAskedFor = null;
                    }
                    ClientChapterReplica.refuse(reply.chapter(), said);
                    Constants.LOG.info("replica for \"{}\" was refused while another chapter was open: {}",
                            reply.chapter(), said);
                }
                continue;
            }
            for (String line : reply.lines()) {
                if (reply.ok()) {
                    book.bookInput.authorReport(line);
                }
                else {
                    // Recorded, so the Chapter tab's placeholder can say what the server said instead of
                    // claiming a copy is still on its way. Not for a table reply: there is no chapter to
                    // record it against, and the refusal is the panel's own answer.
                    if (!tableReply) {
                        ClientChapterReplica.refuse(reply.chapter(), line);
                    }
                    Constants.LOG.info("reply was refused: {}", line);
                    book.bookInput.authorToast(line, true);
                }
            }
            if (reply.ok() && !reply.questId().isEmpty() && (answered == null || answered.isEmpty())) {
                // A quest the server made: created, or duplicated. Selecting it here rather than when the op was
                // sent, because the id is the server's to choose and this is the first moment the author has it.
                // Only for a chapter's op: a table op's id is a table's, and selecting a quest named after a
                // table would move the canvas to a quest that does not exist.
                QuestBookScreen.selectedQuest = reply.questId();
                book.bookInput.authorReport("Now editing " + reply.questId());
            }
            if (!tableReply) {
                // The intro's gates: a made quest, task or reward advances the tour. Chapter ops only —
                // a table op's id is a table's, and the tour's rungs are quests. Advisory rather than
                // exact (see IntroGuide): any accepted chapter edit on the task and reward rungs moves it.
                IntroGuide.noteReply(reply.ok(), reply.questId());
            }
        }

        // The first-run intro, evaluated on the counts the replies above just moved: an author facing
        // an empty book starts the guided making of one chapter, quest, task and reward, and the rungs
        // the tree satisfies advance. Here rather than in the drawing, because starting is state and
        // state does not belong inside a render.
        IntroGuide.tick(book.questEditor.mayEdit(), DevMode.on(), book.bookCanvas.previewing(), ClientQuestCache.hasTree(),
                ClientQuestCache.chapters().size(), ClientQuestCache.entries().size());
    }
}
