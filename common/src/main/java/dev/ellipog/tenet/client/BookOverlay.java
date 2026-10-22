package dev.ellipog.tenet.client;

import dev.ellipog.armature.api.net.ArmatureNetwork;
import dev.ellipog.armature.client.ArmatureButton;
import dev.ellipog.armature.client.ArmatureTheme;
import dev.ellipog.armature.client.render.GuiRenderer;
import dev.ellipog.armature.client.ui.inspect.InspectLayout;
import dev.ellipog.armature.client.ui.inspect.InspectRow;
import dev.ellipog.armature.client.ui.kit.Colour;
import dev.ellipog.armature.client.ui.kit.Hover;
import dev.ellipog.armature.client.ui.kit.Layout;
import dev.ellipog.armature.client.ui.kit.Measure;
import dev.ellipog.armature.client.ui.kit.ScrollView;
import dev.ellipog.armature.client.ui.kit.Slot;
import dev.ellipog.armature.client.ui.kit.Viewport;
import dev.ellipog.armature.client.ui.party.PartyRoster;
import dev.ellipog.tenet.client.dev.ChapterPanelLayout;
import dev.ellipog.tenet.client.dev.Labels;
import dev.ellipog.tenet.client.dev.ProblemText;
import dev.ellipog.tenet.client.dev.QuestPanel;
import dev.ellipog.tenet.client.dev.ToolsLayout;
import dev.ellipog.tenet.client.dev.ToolsPanel;
import dev.ellipog.tenet.net.ChoiceRewardPayload;
import dev.ellipog.tenet.net.ClaimChoicePayload;
import dev.ellipog.tenet.net.ClaimChoiceResultPayload;
import org.joml.Matrix3x2fStack;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * The quest book's overlay and choice offer: the card drawn over the canvas, and the offer a choice
 * reward makes.
 *
 * <p>Moved out of {@link QuestBookScreen} as it stood, with the state only it reads; the screen
 * keeps the state it shares and reaches this through its {@code bookOverlay} field.
 */
final class BookOverlay {

    private final QuestBookScreen book;

    BookOverlay(QuestBookScreen book) {
        this.book = book;
    }

    private Layout choiceLayout;

    /** The key prefix of a choice offer's rows; the entry's index in the table follows. */
    private static final String CHOICE_PREFIX = "choice:";

    /** Whether a pick is in flight: the choice card's rows and its Keep-it-for-later are locked. */
    private boolean choicePending;

    

    /** The entry the pending pick named, so a refusal can say which one there was no room for. */
    private int pressedChoiceEntry = -1;

    /** The refusal drawn on the choice card, or null. Cleared by the next press or the next offer. */
    private String choiceError;

    /**
     * The sidebar's scrollbar, when there is more to scroll than fits.
     *
     * <h2>The colours are the theme's own tokens now, and they were not</h2>
     *
     * <p>This passed {@code ArmatureTheme.panelEdge()} and {@code ArmatureTheme.available()} — a border
     * colour and the <b>bright blue that means "this quest can be started"</b>. The bar came out as a
     * saturated strip down the side of a dark panel, and the report was immediate: <i>"make it not such
     * an obnoxious colour by default"</i>.
     *
     * <p>The theme had {@code scrollTrack} and {@code scrollThumb} the whole time, and their own
     * javadoc says why this happened: <i>"a colour borrowed for a job it was not chosen for is a colour
     * that will be wrong for one of the two jobs."</i> That is exactly what {@code available} was —
     * chosen to read as a ring around a node, used as a scrollbar grip.
     *
     * <p>The replacement pair is a token per theme, so a light theme gets a bar that reads on light.
     * That is the whole point of the token, and it was being bypassed.
     */
    private void drawSidebarScrollbar(GuiRenderer r, int mouseX, int mouseY) {
        // Re-bound here, not assumed. The scrollbar's geometry comes from the viewport, so the viewport
        // has to describe the current window before it is drawn -- and this is the first thing in the
        // frame that needs it, because the drawing itself happens before the widget pass.
        book.sidebarViewport();
        QuestBookScreen.drawBar(r, book.bookState.sidebarView.bar(), mouseX, mouseY);
    }
    /**
     * The party panel, whichever face it is showing.
     *
     * <h2>Two columns, two viewports, one rectangle each</h2>
     *
     * <p>Every position below comes from a layout the build pass recorded, through the viewport that
     * owns the scroll \u2014 the same rule the panel always had, now per column. The clip is the
     * column's, so a row scrolled past its edge is cut off rather than drawn over the other column.
     */
    private void drawPartyOverlay(GuiRenderer r, int mouseX, int mouseY, long now) {
        BookGeometry.Rect card = book.bookState.partyCard;
        if (card == null || book.bookState.partyLeftFace == null) {
            return;
        }

        // The armed Disband lapses on its own: a control still saying "press again" a minute after the
        // first press is a control that lies about what it will do next.
        if (book.bookState.disbandPress.lapsed(now)) {
            book.questEditor.disarmDisband();
        }

        // No dim: `renderWith` fills one before dispatching to an overlay, and a second put two
        // translucent blacks over a world that is not otherwise drawn.
        //
        // **The surface is this call's, docked or not.** `drawPanels` clips and dispatches and fills
        // nothing: every kind paints its own surface, and a column's rectangle is only the rectangle. I
        // removed this when docking the party, on the assumption that the frame drew it -- which is how the
        // party column came to have no background at all.
        ArmatureTheme.panel(r, card.x(), card.y(), card.width(), card.height(),
                ArmatureTheme.panel(), ArmatureTheme.panelEdge());

        drawPartyFace(r, book.bookState.partyLeftFace, book.bookState.partyLeftLayout,
                book.bookState.partyLeftView, book.partyColumn(true),
                mouseX, mouseY,
                now);
        if (book.bookState.partyRightFace != null) {
            drawPartyFace(r, book.bookState.partyRightFace, book.bookState.partyRightLayout,
                    book.bookState.partyRightView,
                    book.partyColumn(false), mouseX,
                    mouseY, now);
        }

        // The roster rows are the left column's own composition rather than `Line`s -- see
        // `buildPartyActive` -- so they are drawn here, against the same layout their buttons were
        // registered against.
        //
        // **Not gated on there being a second face.** That test said "this is the active panel rather than
        // the notice or a phase", which the other three conditions already say -- and docked, the two faces
        // are stacked into one, so a second face is exactly what there is not, and the roster's rows and
        // their kick/transfer buttons would have stopped being drawn.
        PartyRoster roster = book.partyRoster();
        if (roster.isReal() && book.bookState.partyPhase == QuestBookScreen.PartyPhase.NONE && book.partiesAvailable()
                && book.bookState.partyLeftLayout != null) {
            drawPartyMembers(r, book.bookState.partyLeftLayout, roster, book.partyColumn(true), mouseX, mouseY, now);
        }
    }

    /**
     * One column: its rows' text, and the scrollbar when there is more than fits.
     *
     * <p>Re-applied every frame rather than only at build time, like the quest overlay's body: a
     * scroll that happened since the last frame is already in the widget positions, and a resize
     * cannot leave the clamp measuring the previous window.
     */
    private void drawPartyFace(GuiRenderer r, PartyPanelLayout.Face face, Layout layout,
                               ScrollView view, Viewport body, int mouseX, int mouseY, long now) {
        if (layout == null) {
            return;
        }
        view.apply(layout, body.viewWidth());
        // Placement first, then the drawing: every widget this column owns gets its rectangle from the
        // same layout the rows are drawn from, so a switch cannot sit beside the wrong label.
        // The rail's arrival travels with them: the surface draws shifted by this offset inside its
        // clip (see `drawPanels`), so a widget placed without it spawns at its destination while the
        // sheet glides in -- the input field's pop-in, and the row buttons' too.
        int slideDx = PanelLayout.slideOffset(book.panelRail(PanelKind.PARTY),
                book.panelRevealElapsed(PanelKind.PARTY, now), book.panelRevealMillis());
        book.questEditor.placePartyLineControls(face, layout, body, slideDx);
        book.questEditor.placePartyField(book.bookState.partyCreateField, face, layout, "solo:create", body, slideDx);
        book.questEditor.placePartyField(book.bookState.partyNameField, face, layout, "left:name", body, slideDx);
        book.questEditor.placePartyField(book.bookState.partySearchField, face, layout, "right:search", body, slideDx);
        try (GuiRenderer.Scoped clip = r.clip(body)) {
            for (PartyPanelLayout.Line line : face.lines()) {
                Slot slot = layout.slot(line.key());
                if (slot == null) {
                    continue;
                }
                if (line.isRule()) {
                    Slot onScreen = BookQuestEditor.screenSlot(body, slot);
                    r.fill(onScreen.x() + 4, onScreen.y(), onScreen.right() - 4, onScreen.y() + 1,
                            ArmatureTheme.panelEdge());
                    continue;
                }
                if (line.label().isEmpty()) {
                    continue;
                }
                Slot onScreen = BookQuestEditor.screenSlot(body, slot);
                int colour;
                if (line.header()) {
                    colour = ArmatureTheme.heading();
                }
                else if (line.isEmptyState() || line.key().endsWith(":hint")
                        || line.key().equals("solo:subtitle")) {
                    colour = ArmatureTheme.faint();
                }
                else {
                    colour = ArmatureTheme.body();
                }
                String label = QuestBookScreen.partyText(line.label());
                // Detail-first: the detail (counts, ages, On/Off) is the answer, so reserve its width
                // before truncating the label. The old code dropped the detail when room ran out,
                // losing data with no ellipsis signal.
                String detail = line.detail() != null && !line.detail().isEmpty()
                        ? QuestBookScreen.partyText(line.detail()) : "";
                int detailWidth = detail.isEmpty() ? 0 : r.textWidth(detail);
                int labelRoom = Math.max(0, onScreen.width() - line.controlRoom() - 8
                        - (detail.isEmpty() ? 0 : detailWidth + 8));
                // Never squeeze the label into nothing when a detail exists: keep a 40px minimum so
                // the row degrades to truncated label + detail rather than detail alone vanishing.
                if (!detail.isEmpty() && labelRoom < 40) {
                    labelRoom = 40;
                }
                r.text(Measure.truncate(label, labelRoom, book.textMeasure(r)),
                        onScreen.x() + 4, onScreen.y() + (onScreen.height() - r.lineHeight()) / 2,
                        colour);
                if (!detail.isEmpty()) {
                    int detailX = onScreen.right() - line.controlRoom() - 4 - detailWidth;
                    if (detailX > onScreen.x() + 4) {
                        boolean stateOn = line.detail().equals("tenet.screen.on");
                        r.text(detail, detailX,
                                onScreen.y() + (onScreen.height() - r.lineHeight()) / 2,
                                stateOn ? ArmatureTheme.title() : ArmatureTheme.faint());
                    }
                }
            }
        }
        QuestBookScreen.drawBar(r, view.bar(), mouseX, mouseY);
    }

    /**
     * The roster's member rows: portrait, presence, name, and the role chip.
     *
     * <h2>The chip and the hover controls share one strip</h2>
     *
     * <p>Both belong at the row's right edge and only one can matter at a time: the chip says who
     * somebody is until the pointer is on their row, and then the owner's Transfer and Remove replace
     * it \u2014 which is how two controls fit in a column that also has to hold a name. The wash, the
     * chip and the buttons all read the same {@link Hover}, so the row cannot be hovered for one of
     * them and not the others.
     */
    private void drawPartyMembers(GuiRenderer r, Layout layout, PartyRoster roster, Viewport body,
                                  int mouseX, int mouseY, long now) {
        // Same arrival as the face rows: these buttons are placed per frame, after the slide pass,
        // so they need the offset here rather than from it. See `placePartyWidget`.
        int slideDx = PanelLayout.slideOffset(book.panelRail(PanelKind.PARTY),
                book.panelRevealElapsed(PanelKind.PARTY, now), book.panelRevealMillis());
        // One hovered row per frame, hit-tested: updating the tracker per row left it stuck on the
        // last member with a permanent full wash. There is no wash at all now -- the hover only
        // swaps the role chip for the row's actions. The pointer is tested against the unshifted
        // layout, so it is shifted back by the arrival first.
        Object hovered = null;
        int pointerX = mouseX - slideDx;
        for (PartyRoster.Member member : roster.members()) {
            Slot slot = layout.slot(member.key());
            if (slot == null) {
                continue;
            }
            Slot onScreen = BookQuestEditor.screenSlot(body, slot);
            if (hovered == null && pointerX >= onScreen.x() && pointerX < onScreen.right()
                    && mouseY >= onScreen.y() && mouseY < onScreen.bottom()
                    && onScreen.y() < body.viewBottom() && onScreen.bottom() > body.originY()) {
                hovered = member.key();
            }
        }
        book.bookState.rowHover.update(hovered, now);
        for (PartyRoster.Member member : roster.members()) {
            Slot slot = layout.slot(member.key());
            if (slot == null) {
                continue;
            }
            Slot onScreen = BookQuestEditor.screenSlot(body, slot);

            float hover = book.bookState.rowHover.amount(member.key(), now);

            int portraitX = onScreen.x() + PartyPanelLayout.HEAD_INSET;
            r.face(member.id(), portraitX,
                    onScreen.y() + (onScreen.height() - PartyPanelLayout.HEAD_BOX) / 2,
                    PartyPanelLayout.HEAD_BOX);

            int markerX = portraitX + PartyPanelLayout.HEAD_BOX + PartyPanelLayout.HEAD_GAP;
            int dotY = onScreen.y() + (onScreen.height() - PartyPanelLayout.STATUS_DOT) / 2;
            r.fill(markerX, dotY, markerX + PartyPanelLayout.STATUS_DOT,
                    dotY + PartyPanelLayout.STATUS_DOT,
                    member.online() ? ArmatureTheme.title() : ArmatureTheme.faint());

            int nameX = markerX + PartyPanelLayout.STATUS_DOT + PartyPanelLayout.STATUS_GAP;
            int textY = onScreen.y() + (onScreen.height() - r.lineHeight()) / 2
                    + PartyPanelLayout.NAME_DROP;
            // The name may use the whole slot: the slot is already narrowed by the action strip, so
            // subtracting the strip again here would cost the name its room twice -- which it did,
            // truncating every name to three characters beside three owner controls that are only
            // *sometimes* drawn.
            r.text(Measure.truncate(member.label(),
                            Math.max(0, onScreen.width() - (nameX - onScreen.x()) - 4),
                            book.textMeasure(r)),
                    nameX, textY, member.self() ? ArmatureTheme.title() : ArmatureTheme.body());

            // The chip, in the same strip the hover controls occupy. It steps aside while the row's
            // actions are showing -- a hover reads as "these are the actions" rather than as a third
            // state -- and stays put when there is nothing to step aside for, so hovering your own
            // row does not blink your own badge out. When the row has owner actions, a faint
            // overflow mark left of the chip signals that hovering reveals more — otherwise the
            // hover-only buttons are undiscoverable, and on touch there is no hover to discover
            // them with.
            Slot rowSlot = layout.slot(member.key());
            ArmatureButton remove = book.bookState.partyRemoveButtons.get(member.id());
            ArmatureButton transfer = book.bookState.partyTransferButtons.get(member.id());
            boolean actionsShown = (remove != null || transfer != null)
                    && (hover > 0.05F
                            || (remove != null && remove.isHoveredOrFocused())
                            || (transfer != null && transfer.isHoveredOrFocused()));
            if (!actionsShown) {
                drawRoleChip(r, member, onScreen, textY);
                if ((remove != null || transfer != null) && hover <= 0.05F) {
                    String dots = "···";
                    int dotsWidth = r.textWidth(dots);
                    String role = member.roleLabel().toUpperCase(java.util.Locale.ROOT);
                    int chipWidth = r.textWidth(role) + PartyPanelLayout.CHIP_PAD * 2;
                    int chipX = onScreen.right() + PartyRoster.actionStrip()
                            - PartyPanelLayout.CONTROL_INSET - chipWidth;
                    int dotsX = chipX - 4 - dotsWidth;
                    if (dotsX > nameX) {
                        r.text(dots, dotsX, textY, ArmatureTheme.faint());
                    }
                }
            }

            // And the controls' visibility follows the same hover. Placed first -- the rectangle comes
            // from the row's own strip, like every other control here -- and then hidden unless the row
            // is hovered, so a scrolled-out button is neither drawn nor clickable.
            if (remove != null) {
                book.questEditor.placePartyWidget(remove, rowSlot == null ? null
                        : PartyRoster.removeSlot(member, rowSlot), body, slideDx);
                remove.visible = remove.visible && (hover > 0.05F || remove.isHoveredOrFocused());
            }
            if (transfer != null) {
                book.questEditor.placePartyWidget(transfer, rowSlot == null ? null
                        : PartyRoster.transferSlot(member, rowSlot), body, slideDx);
                transfer.visible = transfer.visible && (hover > 0.05F || transfer.isHoveredOrFocused());
            }
        }
    }

    /**
     * The role chip: a small bordered badge with the role's word.
     *
     * <h2>Why the colours are tokens rather than the spec's gold and slate</h2>
     *
     * <p>Armature's themes carry no gold, and a colour written here would be the one value in this
     * screen a theme could not reach \u2014 the mistake every other drawing in this file avoids. So the
     * owner wears the brightest ink and everybody else the faintest, which reads as the same
     * hierarchy on every palette instead of on one.
     */
    private void drawRoleChip(GuiRenderer r, PartyRoster.Member member, Slot onScreen, int textY) {
        String role = member.roleLabel().toUpperCase(java.util.Locale.ROOT);
        int width = r.textWidth(role) + PartyPanelLayout.CHIP_PAD * 2;
        // Right-aligned in the strip the row reserved, where the hover buttons also go: the chip and
        // the controls share one place because only one of them is ever the answer.
        int x = onScreen.right() + PartyRoster.actionStrip() - PartyPanelLayout.CONTROL_INSET - width;
        if (x <= onScreen.right() + 1) {
            return;
        }
        int chipY = onScreen.y() + (onScreen.height() - PartyPanelLayout.CHIP_HEIGHT) / 2;
        int ink = member.owner() ? ArmatureTheme.title() : ArmatureTheme.faint();
        ArmatureTheme.panel(r, x, chipY, width, PartyPanelLayout.CHIP_HEIGHT,
                ArmatureTheme.recessed(), ArmatureTheme.panelEdge());
        // One pixel below arithmetic centre: a centred line box carries descender space under the
        // baseline, so the word reads high in an 11px chip -- the same optical point as
        // `PartyPanelLayout.NAME_DROP` one row up, at this chip's own smaller scale.
        r.text(role, x + PartyPanelLayout.CHIP_PAD,
                chipY + (PartyPanelLayout.CHIP_HEIGHT - r.lineHeight()) / 2 + 1, ink);
    }

    /**
     * The tools panel, over the canvas.
     *
     * <p>Drawn after the book and before the widget pass, which is the only order that works: the panel's
     * own fills and text are {@link ToolsPanel}'s, and its controls are widgets the base class draws
     * afterwards -- the same split every panel here has.
     *
     * <p>Called from `render`'s raised-Z chrome band rather than from `renderBook`, and that is not a
     * preference: the canvas's node icons write depth at Z = 150, so the panel's fills drawn at Z = 0
     * were rejected wherever an icon sat and the icon showed through the panel. It is the same move the
     * view cluster's backing panel made, and the whole of the arithmetic is at the chrome layer's pose
     * push.
     *
     * <p>Shut while a modal is open. The panel and a card are both "the thing in front", and two of them
     * at once is a stack nobody asked for; the panel keeps its state, so closing the card brings it back
     * exactly as it was.
     */
    private void drawTools(GuiRenderer r, int mouseX, int mouseY) {
        // No guard on edit mode or on the overlay: this is `drawSurface`'s arm for `PanelKind.TOOLS`, so it is
        // reached exactly when the dock is a presented column -- which is the same question its guard used to
        // re-ask, one frame later and in a second place. `toolsFrame` is the build's, and a frame that has not
        // built one has nothing to draw.
        if (book.bookState.toolsFrame == null) {
            return;
        }
        // A picker belongs to the chapter it was opened for: switching chapters under it would aim its
        // commit at the wrong file, so it closes here rather than at each of the three switch paths.
        if (book.bookState.colourPopover.isOpen()
                && !java.util.Objects.equals(book.bookState.popoverChapter, QuestBookScreen.effectiveChapter())) {
            book.closeColourPopover();
        }
        // And it belongs to the chip it was opened from: a row scrolled out of the drawer's viewport
        // would leave the picker anchored to a chip that is no longer drawn, floating over whatever is
        // there instead. The same `onScreen` derivation the chip's own press uses, so the two agree about
        // where the chip is -- and only when the chip's row is no longer on screen at all.
        //
        // The list is an argument because a picker can be anchored in another one: the element panel draws an
        // element row at the same key this tab does, and its own chip is not this tab's business. See
        // `chipOnScreen`, which answers true for an anchor that belongs elsewhere.
        if (book.bookState.colourPopover.isOpen() && !book.chipOnScreen(book.bookState.toolsView)) {
            book.closeColourPopover();
        }
        if (book.bookState.toolsTab == ToolsLayout.Tab.CHAPTER) {
            if (book.bookState.chapterLayout == null) {
                return;
            }
            // The panel's own surface, drawn by the same method the book tab uses.
            ToolsPanel.State state = new ToolsPanel.State(book.questEditor.editedTheme(),
                    book.editedBackground(), book.questEditor.currentPalette());
            ToolsPanel.drawChrome(r, book.bookState.toolsFrame, state);
            // The chapter's own rows, identity first: the band that used to stand here was a second,
            // unclickable copy of the identity rows below it, and it is gone -- the icon, title and
            // subtitle are edited in the fold at the top of this list.
            //
            // **And they are the drawer's own rows now.** They came through `QuestPanel.drawRows` with the
            // cycling rows' arrows drawn by a third class beside it and hit-tested by a fourth path; all of
            // that is `ToolsPanel`'s one dispatch, which is what "one panel" means. STACKED, because a
            // chapter's labels are wider than any strip this drawer reserves -- see `ToolsLayout.stack`.
            ToolsPanel.drawRows(r, book.bookState.toolsFrame.list(), book.bookState.toolsView.viewport(),
                    book.bookState.chapterLayout,
                    book.bookState.chapterRows,
                    state, mouseX, mouseY, InspectLayout.Mode.STACKED);
            if (book.toolsAppearanceVisible()) {
                ToolsPanel.drawRows(r, book.bookState.toolsFrame.list(), book.bookState.toolsView.viewport(),
                        book.bookState.chapterLayout,
                        book.bookState.chapterAppearanceRows, state, mouseX, mouseY);
            }
            // The rows the drag reorders, in the order they are drawn, and the line over them. Only the
            // quest rows take part -- the identity fields above them are not a list.
            try (GuiRenderer.Scoped clip = r.clip(book.bookState.toolsFrame.list().x(),
                    book.bookState.toolsFrame.list().y(),
                    book.bookState.toolsFrame.list().right(), book.bookState.toolsFrame.list().bottom())) {
                book.bookState.dragRowSlots.put("chapter", book.rowDrag.chapterQuestRowRects());
                if (book.bookState.dragRowLive && "chapter".equals(book.bookState.dragRowMember)) {
                    book.bookRewards.drawRowDragIndicator(r, book.bookState.dragRowSlots.get("chapter"),
                            book.bookState.dragRowPointerY,
                            BookRewards.onScreenBand(book.bookState.toolsView.viewport()));
                }
            }
        }
        else {
            if (book.bookState.toolsLayout == null) {
                return;
            }
            ToolsPanel.draw(r, book.bookState.toolsFrame, book.bookState.toolsView.viewport(),
                    book.bookState.toolsLayout, book.bookState.toolsRows,
                    new ToolsPanel.State(book.questEditor.editedTheme(), book.editedBackground(),
                            book.questEditor.currentPalette()), mouseX, mouseY);
        }
        // The row under the pointer, collected here and painted in the tooltip band after the panel:
        // the list's own clip would cut a box that reaches past the row's edge, which is the contract
        // `rowTooltips` already has for the card's rows. The list is the one the active tab actually
        // drew, and `helpAt` reads the same layout and viewport the drawing just used, so the sentence
        // belongs to the row the pointer is over, scrolled or not. A row with no help says itself, and
        // `helpAt` answers null.
        //
        // The chapter tab's sentences are its own (`ChapterPanelLayout.help`, which explains a file's
        // fields) and the appearance rows' are the drawer's, so the two tables are asked in turn by
        // `chapterHelp`. That is the second half of the move: the chapter's rows used to need a tooltip
        // loop of their own because they were a different kind of row.
        ToolsLayout.Hovered hovered = ToolsLayout.helpAt(book.activeRows(), book.activeLayout(),
                book.bookState.toolsView.viewport(), mouseX, mouseY,
                book.bookState.toolsTab == ToolsLayout.Tab.CHAPTER ? this::chapterHelp : ToolsLayout::help);
        if (hovered != null && book.questEditor.inTools(mouseX, mouseY)) {
            // A fault's own sentence is the fault, not a key: it bypasses `Labels` (whose
            // `tenet.`-prefix scan would mangle text naming this mod) and reaches the box
            // pre-split, header first. Every other row resolves as before.
            List<String> tip = isProblemRow(hovered.key())
                    ? ProblemText.tooltipLines(hovered.help())
                    : List.of(Labels.of(hovered.help()));
            if (!tip.isEmpty()) {
                book.bookState.rowTooltips.add(new QuestBookScreen.RowTooltip(
                        ToolsLayout.onScreen(book.bookState.toolsView.viewport(),
                                book.activeLayout().slot(hovered.key())),
                        tip));
            }
        }
        // The bar, from the kit's own rectangles and drawn only when there is more than fits -- the same
        // call the sidebar and the party panel make. It was missing entirely, which left a list that
        // scrolled with nothing on screen saying so.
        QuestBookScreen.drawBar(r, book.bookState.toolsView.bar(), mouseX, mouseY);
    }

    /**
     * The help a chapter tab row offers: the chapter's own sentences for its own keys, the drawer's for
     * the appearance rows it shares with the book tab.
     *
     * <p>One lookup for one list, because the pointer cannot tell the two provenances apart -- and should
     * not have to: after the move they are rows of the same list, drawn by the same dispatch.
     */
    private String chapterHelp(String key) {
        String problem = problemLine(key);
        if (problem != null) {
            return problem;
        }
        String sentence = ChapterPanelLayout.help(key);
        return sentence != null ? sentence : ToolsLayout.help(key);
    }

    /**
     * Whether a chapter-tab row key names one fault of the pack's report.
     *
     * <p>The count row ({@code v:problems:more}) is not one: it is the tally of what the
     * payload could not carry, and neither hovering nor pressing it says anything.
     */
    private static boolean isProblemRow(String key) {
        return key != null && key.startsWith(ChapterPanelLayout.VALUE_PREFIX + "problem:");
    }

    /**
     * The fault a problem row carries, or null when the key names no such row.
     *
     * <p>Read from the rows just drawn rather than from the report: the row's own value is the
     * server's sentence, so the tooltip describes the row the pointer is over however the
     * report has moved since. Null doubles as "no help", which is the shape {@code helpAt}
     * already skips.
     */
    private String problemLine(String key) {
        if (!isProblemRow(key)) {
            return null;
        }
        for (ToolsLayout.Action row : book.bookState.chapterRows) {
            if (key.equals(row.key())) {
                return row.value();
            }
        }
        return null;
    }

    // ------------------------------------------------------------------
    // The overlay
    // ------------------------------------------------------------------

    /**
     * The full-screen quest view.
     *
     * <p>Laid out as a fixed header, a scrolling body and a fixed footer, because the body can be
     * arbitrarily long and the controls that close it must not scroll away. The body's clipping rect is
     * what makes that work: a clip around the body cuts a long description off at the footer rather
     * than letting it draw over the controls, and being scoped it cannot be left open by an early
     * return.
     */
    /**
     * Every rail that is presented, each drawn into its own rectangle — and every rail that just left,
     * sinking back out.
     *
     * <p>The content is {@link #drawSurface}'s, unchanged — this method decides only <i>where</i>, and that
     * is the whole of what the rail arrangement adds. Three things are its own and none is cosmetic: the
     * rails are drawn in {@code PanelStack.presented}'s order (innermost first, so the outer rail's edge and
     * its rounded corners sit over the inner one's), <b>each is clipped to its own arrival</b>, so a rail
     * still on its way shows the canvas in the part that has not arrived rather than a half-drawn surface,
     * and the pressable-target list is cleared once for the frame.
     *
     * <p>The targets are cleared <b>here rather than in each kind's draw</b>, which is a fix with a history:
     * it used to be cleared by the quest panel's editor alone, and when the table panels arrived their
     * targets accumulated for as long as a panel stayed open while both press handlers scan the list from
     * the front — so the oldest stale box won the click, and a press was either swallowed or acted on the
     * row that used to be under the pointer.
     *
     * <p>Departed rails draw after the live ones, from their snapshotted rectangles and retained content,
     * sinking into the edge they arrived from. Surface only: their widgets rebuilt with the close, so there
     * is nothing travelling to place — and the live target list is snapshotted around them, because their
     * listings clear before they fill and a press must never land on a panel that is already gone.
     */
    void drawPanels(GuiRenderer r, Matrix3x2fStack pose, int mouseX, int mouseY, long now) {
        book.bookState.editTargets.clear();
        int duration = book.panelRevealMillis();
        for (PanelKind kind : PanelStack.presented(book.columns(), book.panelFolded())) {
            BookGeometry.Rect rail = book.panelRail(kind);
            // The clip's four edges rather than a rectangle: `GuiRenderer` takes a `Slot`, a `Viewport`
            // or four ints, and the reveal is arithmetic this class produced -- wrapping it in a kit type
            // to hand it straight back would be a conversion with no reader.
            BookGeometry.Rect shown = PanelLayout.revealRect(rail, book.panelRevealElapsed(kind, now), duration);
            // The same offset the widgets travel by, so the sheet and its controls are one thing moving
            // left rather than a wipe the buttons pop onto afterwards. The clip is opened first and stays
            // where the rail lives; only the surface shifts inside it, and the grip below stays with the
            // frame rather than with the arrival.
            int offset = PanelLayout.slideOffset(rail, book.panelRevealElapsed(kind, now), duration);
            try (GuiRenderer.Scoped clip = r.clip(shown.x(), shown.y(), shown.right(), shown.bottom())) {
                if (offset != 0) {
                    pose.pushMatrix();
                    pose.translate(offset, 0F);
                    try {
                        drawSurface(r, pose, kind, mouseX, mouseY, now);
                    }
                    finally {
                        pose.popMatrix();
                    }
                }
                else {
                    drawSurface(r, pose, kind, mouseX, mouseY, now);
                }
                // After the surface, because it belongs to the frame rather than to what the column holds:
                // the grip on the inner edge is this presentation's one affordance, and the panel's own
                // drawing knows nothing about it.
                drawPanelGrip(r, rail, shown, mouseX, mouseY);
            }
        }
        drawDepartedPanels(r, pose, mouseX, mouseY, now);
    }

    /**
     * The rails that left the arrangement and have not finished sinking.
     *
     * <p>The enter in reverse, from the snapshot each close took: the clip shrinks into the rail's outer
     * edge while the surface shifts right inside it. No grip — the frame's affordance belongs to rails
     * that answer — and no targets survive: anything the kinds register while drawing is cut back off,
     * because the panels are gone however drawn they still are. The rail override ({@link QuestBookScreen#panelRail}
     * answering the snapshot) is scoped to each draw and cleared after, so a throw cannot leak one
     * kind's rectangle into another's.
     */
    private void drawDepartedPanels(GuiRenderer r, Matrix3x2fStack pose, int mouseX, int mouseY, long now) {
        int exitMs = book.panelExitMillis();
        if (exitMs <= 0 || book.bookState.panelDepartStarts.isEmpty()) {
            return;
        }
        // Snapshotted, not truncated: a departed draw re-runs its kind's listing, and listings clear
        // before they fill — the quest editor most of all. Truncating back to a size would keep a clear
        // that wiped the live rails' targets while leaving the departed ones standing: presses landing
        // on invisible rows. Restoring the exact list keeps the exit press-dead by construction.
        java.util.List<QuestBookScreen.EditTarget> keptTargets = java.util.List.copyOf(book.bookState.editTargets);
        // drawSurface names its kind as it draws; the live loop above leaves whatever it drew last,
        // and this loop must leave the same rather than a rail that is already gone.
        PanelKind keptSurface = book.bookState.surfaceKind;
        try {
            for (PanelKind kind : java.util.List.copyOf(book.bookState.panelDepartStarts.keySet())) {
                BookGeometry.Rect rail = book.bookState.departRails.get(kind);
                if (rail == null) {
                    continue;
                }
                long elapsed = book.panelDepartElapsed(kind, now);
                BookGeometry.Rect shown = PanelLayout.departRect(rail, elapsed, exitMs);
                if (shown.width() <= 0) {
                    continue;
                }
                int offset = PanelLayout.departOffset(rail, elapsed, exitMs);
                book.bookState.departDrawKind = kind;
                book.bookState.departDrawRail = rail;
                try (GuiRenderer.Scoped clip =
                        r.clip(shown.x(), shown.y(), shown.right(), shown.bottom())) {
                    if (offset != 0) {
                        pose.pushMatrix();
                        pose.translate(offset, 0F);
                        try {
                            drawSurface(r, pose, kind, mouseX, mouseY, now);
                        }
                        finally {
                            pose.popMatrix();
                        }
                    }
                    else {
                        drawSurface(r, pose, kind, mouseX, mouseY, now);
                    }
                }
                finally {
                    book.bookState.departDrawKind = null;
                    book.bookState.departDrawRail = null;
                }
            }
        }
        finally {
            // Whatever the departed kinds registered while drawing goes — including a clear that wiped
            // the live rails' targets: the snapshot above puts the live list back whole, so a press
            // must never land on a panel that is already gone.
            book.bookState.editTargets.clear();
            book.bookState.editTargets.addAll(keptTargets);
            book.bookState.surfaceKind = keptSurface;
        }
    }

    /**
     * What a panel draws <b>after</b> the widget pass, because the pass has no widget for it.
     *
     * <p>Two things, and they are the same two for every kind: the placeholders registered by
     * {@link QuestBookScreen#panelOverlays} — ink over a field's own fill, which no widget owns — and the inline editor
     * {@code drawOpenEditor} draws, which is a widget deliberately kept off the renderable list so that it
     * can be clipped. Both used to happen only for a card, which is why a rail's search boxes had no
     * placeholder and the field an author was typing into was never painted.
     *
     * <p>Called from inside the chrome band, so both land at the same depth as the widget pass drew the
     * fields themselves — the relationship they need, and the reason there is no Z step here.
     */
    void drawPanelOverlays(GuiRenderer renderer) {
        for (java.util.function.Consumer<GuiRenderer> overlay : book.bookState.panelOverlays) {
            overlay.accept(renderer);
        }
        book.editorControls.drawOpenEditor(renderer);
    }

    /**
     * The drag affordance on a column's inner edge: three ridges, and the strip lit while the pointer is on
     * it.
     *
     * <p>In the vocabulary the scrollbar already uses — a quiet mark that brightens under the pointer — for
     * the same reason: a thing you can grab has to look grabbable before you grab it. The hit test is the
     * same {@code handle.contains} the press uses, so the strip that lights is exactly the strip that
     * answers, and it is taken from the <b>revealed</b> rectangle so a column that is still arriving cannot
     * light a mark that is not on screen yet.
     */
    private void drawPanelGrip(GuiRenderer r, BookGeometry.Rect rail, BookGeometry.Rect shown,
            int mouseX, int mouseY) {
        BookGeometry.Rect handle = BookGeometry.panelHandle(rail);
        if (!handle.contains(mouseX, mouseY) || !shown.contains(mouseX, mouseY)) {
            // Resting: the mark alone, in the colour the scrollbar's own grip uses.
            for (BookGeometry.Rect ridge : PanelLayout.grip(rail)) {
                r.fill(ridge.x(), ridge.y(), ridge.right(), ridge.bottom(), ArmatureTheme.scrollThumb());
            }
            return;
        }
        r.fill(handle.x(), handle.y(), handle.right(), handle.bottom(),
                Colour.translucent(ArmatureTheme.rowHover(), 1F));
        for (BookGeometry.Rect ridge : PanelLayout.grip(rail)) {
            r.fill(ridge.x(), ridge.y(), ridge.right(), ridge.bottom(), ArmatureTheme.controlEdgeBright());
        }
    }

    /**
     * The same affordance on the chapter list's inner edge — and on the floating tab, where the
     * ridges are the texture that invites the hover.
     *
     * <p>The hit test is the same {@code handle.contains} the press uses, so the strip that lights
     * is exactly the strip that answers. The pinned rail keeps none: its rows are the affordance,
     * and ridges would promise a drag the strip has no width for.
     */
    private void drawSidebarGrip(GuiRenderer r, BookGeometry.Rect surface, int mouseX, int mouseY) {
        BookGeometry.Rect handle = BookGeometry.sidebarHandle(surface);
        if (handle.width() <= 0 || handle.height() <= 0) {
            return;
        }
        if (!handle.contains(mouseX, mouseY)) {
            for (BookGeometry.Rect ridge : PanelLayout.sidebarGrip(surface)) {
                r.fill(ridge.x(), ridge.y(), ridge.right(), ridge.bottom(), ArmatureTheme.scrollThumb());
            }
            return;
        }
        r.fill(handle.x(), handle.y(), handle.right(), handle.bottom(),
                Colour.translucent(ArmatureTheme.rowHover(), 1F));
        for (BookGeometry.Rect ridge : PanelLayout.sidebarGrip(surface)) {
            r.fill(ridge.x(), ridge.y(), ridge.right(), ridge.bottom(), ArmatureTheme.controlEdgeBright());
        }
    }

    /**
     * The chapter list's own chrome: its surface, its divider, its grip and its scrollbar.
     *
     * <h2>Why this is in the chrome layer rather than in {@code drawBook}</h2>
     *
     * <p>Because the canvas is drawn after {@code drawBook} and fills its whole rect. A docked
     * list never noticed — the canvas starts past it — but a floating list, a strip and every
     * wipe in between sit over the graph, so a surface drawn down there is buried under it: rows
     * floating over the map with no card behind them, which reads as the book having no
     * background at all. Up here, past the canvas and at {@code CHROME_Z}, the surface beats both
     * the canvas fill and the node icons' depth, exactly like the rails beside it.
     *
     * <p>Before the rails and before the widget pass, so a maximally wide rail still wins over
     * the list and the list's own rows sit on its surface. The header stays in {@code drawBook}:
     * it is above the canvas and was never buried.
     */
    void drawSidebarChrome(GuiRenderer r, int mouseX, int mouseY, long now) {
        SidebarState shown = book.geometry().sidebarState();
        // Docked, the list is the panel's own left column; floating — box or sliver — it is a
        // panel over the graph. The old mode has no other shape: docked is always expanded.
        boolean tall = !shown.floating();
        BookGeometry.Rect surface =
                book.sidebarSliding(now) ? book.sidebarWipeRect(now) : book.geometry().sidebar();
        int innerRadius = Math.max(0, ArmatureTheme.current().cornerRadius() - 1);

        // Clipped to the panel: the sliver and every wipe in between hang past its left edge by
        // construction, and what hangs past it is not a preview but an overflow — a slab floating
        // over the margin the dim exists to keep empty. The window cuts further out on its own.
        BookGeometry.Rect panel = book.geometry().panel();
        try (GuiRenderer.Scoped clip = r.clip(panel.x(), panel.y(), panel.right(), panel.bottom())) {
            if (tall) {
                // The panel's own left column down the full height — today's recess, at the wipe's
                // rectangle while it travels.
                ArmatureTheme.fillSurface(r, surface.x(), surface.y(), surface.width(),
                        surface.height(), ArmatureTheme.recessed(), innerRadius,
                        ArmatureTheme.CORNERS_LEFT);
                // The divider, at the travelling edge: down in `drawBook` the canvas fill buried it,
                // which is why the old one never showed. Up here it reads as the moving edge it is.
                r.fill(surface.right(), book.geometry().bodyTop(), surface.right() + 1,
                        book.geometry().panel().bottom() - 1, ArmatureTheme.panelEdge());
            }
            else {
                // A box on the canvas, like the rails beside it: inset on every side with its edge all
                // round, so it reads as a panel floating over the graph rather than as a column the
                // graph was cut around.
                ArmatureTheme.panel(r, surface.x(), surface.y(), surface.width(), surface.height(),
                        ArmatureTheme.recessed(), ArmatureTheme.panelEdge());
                // The box's title, in its own header band: truncated short of the pin dot, like every
                // other title in this screen that shares a band with controls.
                if (shown.expanded()) {
                    int titleRoom = Math.max(0, surface.right() - 4 - BookGeometry.ROW_HEIGHT
                            - BookGeometry.ROW_GAP - (surface.x() + BookGeometry.EDGE));
                    r.text(Measure.truncate(
                                    Component.translatable("tenet.screen.sidebar_title").getString(),
                                    titleRoom, book.textMeasure(r)),
                            surface.x() + BookGeometry.EDGE,
                            surface.y() + (BookGeometry.SIDEBAR_BOX_HEADER - 9) / 2,
                            ArmatureTheme.title());
                    // The pin beside the title: the circle glyph itself, with no button around
                    // it — filled while pinned, a ring while not. Drawn chrome rather than a
                    // widget, the scrollbar's own arrangement — the settings switch is the
                    // focusable, narrated road to the same choice.
                    BookGeometry.Rect pinZone = BookGeometry.sidebarBoxPinFor(surface);
                    String pinGlyph = DevMode.isSidebarPinned() ? "●" : "○";
                    int pinWidth = book.textMeasure(r).width(pinGlyph);
                    r.shadowedText(pinGlyph, pinZone.x() + (pinZone.width() - pinWidth) / 2,
                            pinZone.y() + (pinZone.height() - 8) / 2,
                            DevMode.isSidebarPinned() ? ArmatureTheme.title()
                                    : pinZone.contains(mouseX, mouseY) ? ArmatureTheme.body()
                                            : ArmatureTheme.faint(),
                            1F);
                }
            }

            // The grip on the list's inner edge: quiet ridges that brighten under the pointer, because
            // a thing you can grab has to look grabbable before you grab it. On the expanded list for
            // resizing, on the sliver as the texture that invites the hover.
            drawSidebarGrip(r, surface, mouseX, mouseY);

            // The scrollbar: chrome rather than a widget, though clickable, and every number it needs
            // comes from the viewport, so the grip and the rows it describes come from one object.
            // Only while the list is expanded and settled: the sliver has no rows at all, and mid-wipe
            // the bar would draw at its target while the surface travels.
            if (shown.expanded() && book.bookState.sidebarSlideStart < 0) {
                drawSidebarScrollbar(r, mouseX, mouseY);
            }
        }
    }

    /**
     * What one kind draws, wherever it is drawn.
     *
     * <h2>Why the dispatcher takes a kind rather than reading the field</h2>
     *
     * <p>Because the kind in the inner rail is not the field's, and it is the <b>kind</b> that decides what
     * is drawn — never the rail it happens to be in. Every branch below asks {@link QuestBookScreen#surfaceCard} where it
     * is, so a kind draws itself into whichever rail it was given with no arithmetic of its own changing.
     * That is the seam the whole arrangement rests on, and it is why this is a parameter rather than a read
     * of {@code overlay}.
     */
    private void drawSurface(GuiRenderer r, Matrix3x2fStack pose, PanelKind kind, int mouseX, int mouseY,
                             long now) {
        // What the four accessors answer for, for the whole of this kind's drawing: its body's clip, its
        // rows, its footer. Set here rather than by the caller, so a second caller cannot forget it.
        book.bookState.surfaceKind = kind;
        if (kind == PanelKind.PARTY) {
            // The party: its faces fill the rail's body band and its controls sit in the shared footer.
            // `drawPartyOverlay` paints the surface itself, like every other kind -- the frame fills nothing.
            drawPartyOverlay(r, mouseX, mouseY, now);
        }
        else if (kind == PanelKind.QUEST) {
            // The quest panel is content: it describes the quest you opened, so it is drawn in the
            // palette that quest belongs to rather than in the chrome's. The controls stay chrome and
            // are drawn by the widget pass, which is outside this scope -- so the button that leaves a
            // chapter cannot be recoloured by the chapter. That is the property the feature rests on.
            try (ArmatureTheme.Scope ignored = ArmatureTheme.scope(QuestBookScreen.viewportTheme())) {
                book.bookSettings.drawOverlay(r, mouseX, mouseY, now);
            }
        }
        else if (kind == PanelKind.PICKER) {
            // A list of items is not a chapter's content, so this rail is chrome, like the party
            // panel's: it is about the item registry rather than about the chapter it was opened from.
            book.bookSettings.drawPickerOverlay(r, mouseX, mouseY);
        }
        // (the table panels clear the targets at the top of their own draws -- see `drawTableBrowser`)
        else if (kind == PanelKind.TEXTURE) {
            // The same: a list of the client's own files is chrome rather than a chapter's content, so
            // it is drawn outside every chapter scope, exactly as the item picker's rail is.
            book.texturePicker.drawTextureOverlay(r, mouseX, mouseY);
        }
        else if (kind == PanelKind.CHOICE) {
            // A question about a reward, not a chapter's content: chrome, like the picker's.
            drawChoiceOverlay(r, mouseX, mouseY);
        }
        else if (kind == PanelKind.REWARDS) {
            // The same: a list of what the server owes is about the player, not about the chapter behind it.
            book.bookRewards.drawRewardsOverlay(r, mouseX, mouseY);
        }
        else if (kind == PanelKind.NAMING) {
            book.bookSidebar.drawNamingOverlay(r);
        }
        else if (kind == PanelKind.ELEMENT) {
            // An element is the chapter's own content -- it lives in that chapter's file and is drawn on its
            // canvas -- so it is drawn in the chapter's palette, like the quest panel beside it.
            try (ArmatureTheme.Scope ignored = ArmatureTheme.scope(QuestBookScreen.viewportTheme())) {
                book.bookPins.drawElementPanel(r, mouseX, mouseY);
            }
        }
        else if (kind == PanelKind.TABLE_BROWSER) {
            // A list of the pack's tables is chrome, like the item picker's list of the registry's
            // items: it is about the pack rather than about the chapter behind it.
            book.bookRewards.drawTableBrowser(r, mouseX, mouseY);
        }
        else if (kind == PanelKind.FILTER) {
            // A list of what one filter row accepts: chrome, like the picker's — about the row
            // rather than about the chapter behind it.
            book.bookSettings.drawFilterPanel(r, mouseX, mouseY);
        }
        else if (kind == PanelKind.TABLE_EDITOR) {
            book.bookRewards.drawTableEditor(r, pose, mouseX, mouseY);
        }
        else if (kind == PanelKind.ASSETS) {
            // The pack, which is chrome: it belongs to the book rather than to any chapter, so it is drawn
            // outside the chapter's theme scope -- see `chapterBoundOverlay`, which does not name it.
            book.bookAssets.drawAssets(r, mouseX, mouseY);
        }
        else if (kind == PanelKind.SETTINGS) {
            // A player's own panel: chrome, like the rewards panel's, not a chapter's content.
            book.bookSettings.drawSettingsOverlay(r, mouseX, mouseY);
        }
        else if (kind == PanelKind.PINNED) {
            // The player's watchlist: chrome, like the settings card beside it -- pins belong to the
            // player rather than to any chapter, so the chapter's palette never touches them.
            book.bookPins.drawPinnedPanel(r, mouseX, mouseY);
        }
        else if (kind == PanelKind.TOOLS) {
            // The author's dock: the Book and Chapter tabs, chrome rather than any chapter's content -- its
            // own appearance sections are scoped by the screen, not by this dispatch.
            drawTools(r, mouseX, mouseY);
        }
    }

    // ------------------------------------------------------------------
    // The choice offer
    // ------------------------------------------------------------------

    /**
     * Opens the choice card on the offer the server is holding, at the top of its list.
     *
     * <p>Called from {@link QuestBookScreen#tick} when an offer is waiting, and from nowhere else: the offer is not
     * this screen's state, and a second way in would be a second answer to "is a question waiting".
     */
    void openChoiceOffer() {
        // Whatever card was open is closed first, through the one method every other close uses: an
        // armed type picker or a half-typed field left standing behind the question would greet the
        // next card as if it still belonged to the one it was opened from. That rebuilds, and this
        // rebuilds again after it, because the overlay it left is `NONE` and this one is not.
        book.closeOverlay();
        book.applyColumns(PanelStack.asRoot(book.columns(), PanelKind.CHOICE));
        // A fresh question: no pick in flight, and no refusal left over from the last one.
        choicePending = false;
        choiceError = null;
        pressedChoiceEntry = -1;
        book.bookState.choiceView.scrollTo(0);
        book.rebuildWidgets();
    }

    /**
     * Leaves the choice card: Escape, the outside of the card, or the connection going away.
     *
     * <p>The whole walk ends -- every queued offer goes, not just the one on screen. Dismissing is safe
     * by design: {@link ChoiceRewardPayload} is only an offer, and a reward is not marked claimed until
     * an answer arrives, so the same question comes back the next time its Choose is pressed. That is
     * what makes Escape a way out rather than a way to lose a reward.
     *
     * <p>Keeping one for later is a different thing and does not come through here: see
     * {@link #skipChoice}.
     */
    void closeChoice() {
        ClientChoiceOffers.clear();
        choicePending = false;
        choiceError = null;
        book.applyColumns(PanelStack.afterClose(book.columns(), false));
        book.rebuildWidgets();
    }

    /**
     * Keeps the current choice for later: it leaves the walk, and the next queued offer comes up.
     *
     * <p>Not a rotation to the back of the queue — a player who says "not now" has said they do not
     * want to decide during this session, and a queue that came back around to the same question would
     * be a modal loop. The reward stays outstanding in the inbox with its Choose button, so nothing is
     * lost and nothing is asked twice.
     */
    private void skipChoice() {
        if (choicePending) {
            // The pick is in flight; answering it and skipping it at once would be two answers to one
            // question. The button is drawn disabled while pending; this is the second guard.
            return;
        }
        ClientChoiceOffers.pop();
        choiceError = null;
        book.applyColumns(PanelStack.afterClose(book.columns(), false));
        book.rebuildWidgets();
    }

    /**
     * The server's verdict on the pick that was in flight.
     *
     * <p>Matched against the offer on screen by quest and reward index. A verdict for anything else is
     * dropped rather than acted on: Escape during the round trip clears the queue, and popping then
     * would pop whatever slid into the slot -- a different question's offer.
     */
    void notifyChoiceResult(String questId, int rewardIndex, ClaimChoiceResultPayload.Result result) {
        ClientChoiceOffers.Offer offer = ClientChoiceOffers.current();
        if (offer == null || !offer.questId().equals(questId) || offer.rewardIndex() != rewardIndex) {
            return;
        }
        choicePending = false;
        if (result == ClaimChoiceResultPayload.Result.OK) {
            // Granted: this offer leaves the walk, and the next one (if any) opens on the next tick.
            ClientChoiceOffers.pop();
            choiceError = null;
            book.applyColumns(PanelStack.afterClose(book.columns(), false));
            book.rebuildWidgets();
            return;
        }
        // Refused. The card stays open with the player's question still on it: the pick is not
        // discarded and the queue does not advance.
        String entry = pressedChoiceEntry >= 0 && pressedChoiceEntry < offer.entries().size()
                ? choiceLabel(offer.entries().get(pressedChoiceEntry))
                : "";
        choiceError = Component.translatable(result == ClaimChoiceResultPayload.Result.NO_SPACE
                ? "tenet.screen.choice.no_space" : "tenet.screen.choice.refused", entry).getString();
        book.rebuildWidgets();
    }

    /**
     * The card's rows: one per entry, laid out but <b>drawn</b> by this screen.
     *
     * <p>They used to be widget buttons — and the icons they were given never appeared, while their
     * labels did, so the one card whose whole job is "pick one of these" listed its rewards as bare
     * words. The rewards inbox draws its rows itself, icons and all, and that is what this does now:
     * one drawing path, one hit test, and the item's own sprite where the player can see it.
     */
    void buildChoiceWidgets() {
        book.bookState.choiceRows = List.of();
        choiceLayout = null;
        ClientChoiceOffers.Offer offer = ClientChoiceOffers.current();
        if (offer == null) {
            return;
        }
        List<InspectRow> rows = new ArrayList<>();
        for (int i = 0; i < offer.entries().size(); i++) {
            rows.add(InspectRow.action(CHOICE_PREFIX + i, choiceLabel(offer.entries().get(i))));
        }
        book.bookState.choiceRows = List.copyOf(rows);

        Viewport body = book.overlayBody();
        choiceLayout = InspectLayout.build(book.bookState.choiceRows, body.viewWidth(), Measure.monospace(6, 9));
        book.bookState.choiceView.clear();
        book.bookState.choiceView.whole(true);
        book.bookState.choiceView.viewport().bounds(body.originX(), body.originY(), body.viewWidth(),
                body.viewHeight());
        book.bookState.choiceView.apply(choiceLayout, body.viewWidth());

        // The footer's one control, on the rectangle every card's footer uses. Dismissing is not a
        // refusal -- see `closeChoice` -- and saying so is the whole point of labelling it rather than
        // leaving Escape as the only way out.
        ArmatureButton later = book.control(book.overlayControls(false).get("back"),
                Component.translatable("tenet.screen.keep_for_later"), this::skipChoice);
        if (later != null) {
            later.ink(ArmatureButton.Ink.BODY)
                    .tooltip(Component.translatable("tenet.screen.the_reward_stays_yours_to_collect_and_claim_asks"));
            if (choicePending) {
                // Locked with the rows: skipping a question whose answer is in flight would send the
                // pop racing the reply. See `notifyChoiceResult` for why that matters.
                later.active = false;
            }
        }
    }

    /**
     * A press on an entry: the answer goes to the server, and the card <b>waits</b> for the verdict.
     *
     * <p>It used to close on the press. That is what made a refused pick invisible: the card vanished,
     * the player's decision with it, and the next question appeared as if the first had never been
     * asked. Now the card holds, its rows lock, and the server's answer either advances it or is drawn
     * on its face.
     */
    void pressChoiceRow(String key) {
        ClientChoiceOffers.Offer offer = ClientChoiceOffers.current();
        int index = choiceIndex(key);
        if (offer == null || choicePending || index < 0 || index >= offer.entries().size()) {
            return;
        }
        choicePending = true;
        choiceError = null;
        pressedChoiceEntry = index;
        book.rebuildWidgets();
        // The index, not the reward: the server re-resolves it against its own files, so the worst a
        // modified client can do is pick entry 2 instead of entry 1 of a table it was offered.
        ArmatureNetwork.sendToServer(
                new ClaimChoicePayload(offer.questId(), offer.rewardIndex(), index));
    }

    /** The entry index a row's key names, or -1 for a key that names nothing. */
    private static int choiceIndex(String key) {
        if (key == null || !key.startsWith(CHOICE_PREFIX)) {
            return -1;
        }
        try {
            return Integer.parseInt(key.substring(CHOICE_PREFIX.length()));
        }
        catch (NumberFormatException e) {
            return -1;
        }
    }

    /**
     * An entry's label, resolved the way every other reward label is: the item when there is one, the
     * author's key with its subject when there is not, and the fallback text after that.
     *
     * <p>The same rule {@code ClientQuestCache.RewardEntry.text} states and the item picker's rows
     * follow, including the count in front -- the three lists show the same prizes, and a prize that
     * read differently in each would be a list an author has to learn twice.
     *
     * <p>Through {@link dev.ellipog.tenet.client.RewardText}, which knows that {@code label} is a
     * translation key and {@code labelArg} is the subject it is formatted with. Reading the count into
     * the key is what printed "Roll the 1 table" for a table called {@code dice}.
     */
    private static String choiceLabel(ChoiceRewardPayload.Entry entry) {
        return dev.ellipog.tenet.client.RewardText.withCount(
                dev.ellipog.tenet.client.RewardText.of(entry.label(), entry.labelFallback(),
                        entry.labelArg(), entry.count(), entry.item()),
                entry.count());
    }

    /**
     * The choice card: the question, and one row per entry the server offered.
     *
     * <p>Drawn like the picker's card -- the panel, the header strip, the rule under it -- with the
     * quest's own title for a header, because the question is about a quest and a card that did not name
     * it would ask the player to remember which reward they were collecting.
     */
    private void drawChoiceOverlay(GuiRenderer r, int mouseX, int mouseY) {
        int left = book.overlayLeft();
        int top = book.overlayTop();
        int w = book.overlayWidth();
        int h = book.overlayHeight();

        ArmatureTheme.panel(r, left, top, w, h, ArmatureTheme.panel(), ArmatureTheme.panelEdge());
        ArmatureTheme.fillSurface(r, left + 1, top + 1, w - 2, 45, ArmatureTheme.raised(),
                Math.max(0, ArmatureTheme.current().cornerRadius() - 1), ArmatureTheme.CORNERS_TOP);
        r.fill(left + 1, top + 46, left + w - 1, top + 47, ArmatureTheme.panelEdge());

        ClientChoiceOffers.Offer offer = ClientChoiceOffers.current();
        ClientQuestCache.Entry quest = offer == null ? null : ClientQuestCache.entry(offer.questId());
        String heading = Component.translatable("tenet.quest.choose").getString();
        int iconBox = QuestBookScreen.HEADER_ICON;
        int iconX = left + 14;
        int iconY = top + (46 - iconBox) / 2;
        if (quest != null && !quest.icon().isEmpty()) {
            r.icon(quest.icon(), iconX, iconY, iconBox);
        }
        int textX = iconX + iconBox + 6;
        Measure measure = book.textMeasure(r);
        r.text(Measure.truncate(quest == null ? heading : quest.titleText(), w - (textX - left) - 8, measure),
                textX, top + 12, ArmatureTheme.title());
        if (quest != null) {
            // The subtitle slot is also where a refusal is drawn: the card has to say what went wrong
            // without the player leaving it, and this is the line the eye is already on.
            r.text(Measure.truncate(choiceError == null ? heading : choiceError, w - (textX - left) - 8,
                            measure), textX, top + 26,
                    choiceError == null ? ArmatureTheme.faint() : ArmatureTheme.blocked());
        }

        if (choiceLayout == null) {
            return;
        }
        // The offer is the one this card was built from: read again here rather than carried, because
        // the rows and the entries they name are drawn in the same pass.
        ClientChoiceOffers.Offer entries = ClientChoiceOffers.current();
        Viewport body = book.overlayBody();
        book.bookState.choiceView.apply(choiceLayout, body.viewWidth());
        book.bookState.choiceRowRects.clear();
        try (GuiRenderer.Scoped clip = r.clip(body.originX(), body.originY(), body.originX() + body.viewWidth(),
                body.originY() + body.viewHeight())) {
            for (int i = 0; i < book.bookState.choiceRows.size(); i++) {
                var slot = choiceLayout.slot(book.bookState.choiceRows.get(i).key());
                if (slot == null) {
                    continue;
                }
                var onScreen = InspectLayout.onScreen(book.bookState.choiceView.viewport(), slot);
                BookGeometry.Rect rect = BookGeometry.Rect.at(onScreen.x(), onScreen.y(),
                        onScreen.width(), onScreen.height());
                // Registered where it is drawn: the rail's slide shifts the drawing, so the press
                // follows it through the same shift. See `shiftHit`.
                book.bookState.choiceRowRects.add(book.shiftHit(rect));
                if (entries != null && i < entries.entries().size()) {
                    drawChoiceRow(r, entries.entries().get(i), rect, mouseX, mouseY);
                }
            }
        }
        QuestBookScreen.drawBar(r, book.bookState.choiceView.bar(), mouseX, mouseY);
    }

    /**
     * One offer, as the player reads it: the item's sprite, what it gives, and the item's id on hover.
     *
     * <p>A table entry has no item of its own — it rolls one — so it gets the type's icon and the name
     * the label resolves to, which after the fix above names the table it rolls rather than counting it.
     */
    private void drawChoiceRow(GuiRenderer r, ChoiceRewardPayload.Entry entry, BookGeometry.Rect rect,
                               int mouseX, int mouseY) {
        boolean hot = rect.contains(mouseX, mouseY);
        boolean shut = choicePending;
        if (hot && !shut) {
            r.fill(rect.x(), rect.y(), rect.right(), rect.bottom(), ArmatureTheme.raised());
        }
        ItemStack icon = QuestBookScreen.itemStack(entry.item());
        int textX = rect.x() + 4;
        if (!icon.isEmpty()) {
            r.icon(icon, rect.x() + 2, rect.y() + (rect.height() - 16) / 2, 16);
            textX = rect.x() + 22;
        }
        String label = choiceLabel(entry);
        int room = Math.max(0, rect.right() - textX - (hot && !shut ? 40 : 6));
        r.text(Measure.truncate(label, room, book.textMeasure(r)), textX,
                rect.y() + (rect.height() - 8) / 2,
                shut ? ArmatureTheme.blocked() : ArmatureTheme.body());
        if (hot && !shut) {
            String pick = "Pick";
            r.text(pick, rect.right() - 4 - r.textWidth(pick), rect.y() + (rect.height() - 8) / 2,
                    ArmatureTheme.title());
            if (!entry.item().isEmpty()) {
                // The id on hover, because an entry is otherwise only as identifiable as its label --
                // and a label an author wrote can be as vague as "a surprise".
                book.bookState.pendingLabels.add(new QuestBookScreen.PendingLabel(rect, entry.item()));
            }
        }
    }
}
