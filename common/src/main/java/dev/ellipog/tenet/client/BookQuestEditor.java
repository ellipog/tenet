package dev.ellipog.tenet.client;

import dev.ellipog.armature.client.ArmatureButton;
import dev.ellipog.armature.client.ArmatureSlider;
import dev.ellipog.armature.client.ArmatureSwitch;
import dev.ellipog.armature.client.ArmatureTextField;
import dev.ellipog.armature.client.Look;
import dev.ellipog.armature.client.TextScale;
import dev.ellipog.armature.client.ArmatureTheme;
import dev.ellipog.armature.client.ui.ArmatureLive;
import dev.ellipog.armature.client.ui.Theme;
import dev.ellipog.armature.client.ui.Themes;
import dev.ellipog.armature.client.ui.CanvasBackground;
import dev.ellipog.armature.client.render.GuiRenderer;
import dev.ellipog.armature.client.render.TextEpoch;
import dev.ellipog.armature.client.ui.inspect.InspectField;
import dev.ellipog.armature.client.ui.inspect.InspectLayout;
import dev.ellipog.armature.client.ui.inspect.InspectRow;
import dev.ellipog.armature.client.ui.kit.Colour;
import dev.ellipog.armature.client.ui.kit.Layout;
import dev.ellipog.armature.client.ui.kit.Measure;
import dev.ellipog.armature.client.ui.kit.ScrollBar;
import dev.ellipog.armature.client.ui.kit.ScrollView;
import dev.ellipog.armature.client.ui.kit.Slot;
import dev.ellipog.armature.client.ui.kit.Stack;
import dev.ellipog.armature.client.ui.kit.TextWrap;
import dev.ellipog.armature.client.ui.kit.Viewport;
import dev.ellipog.armature.client.ui.party.PartyRoster;
import dev.ellipog.tenet.editor.EditorOp;
import dev.ellipog.tenet.net.TenetNetworking;
import dev.ellipog.tenet.client.ClientChapterReplica;
import dev.ellipog.tenet.client.ClientEditReplies;
import dev.ellipog.tenet.client.hud.HudEditScreen;
import dev.ellipog.tenet.client.hud.PinnedQuests;
import dev.ellipog.tenet.client.dev.HexColour;
import dev.ellipog.tenet.quest.CanvasElement;
import dev.ellipog.tenet.quest.QuestLink;
import dev.ellipog.tenet.client.dev.ChapterPanelLayout;
import dev.ellipog.tenet.client.dev.ChapterTheme;
import dev.ellipog.tenet.client.dev.ClientEditorClipboard;
import dev.ellipog.tenet.client.dev.EntryFormLayout;
import dev.ellipog.tenet.quest.EditorField;
import dev.ellipog.tenet.quest.EditorSpecs;
import dev.ellipog.tenet.client.dev.InlineEdit;
import dev.ellipog.tenet.client.dev.ItemPickerLayout;
import dev.ellipog.tenet.client.dev.Labels;
import dev.ellipog.tenet.client.dev.MenuPlacement;
import dev.ellipog.tenet.client.dev.ProblemText;
import dev.ellipog.tenet.client.dev.QuestSettingsLayout;
import dev.ellipog.tenet.client.dev.QuestSettingsPanel;
import dev.ellipog.tenet.quest.QuestShape;
import dev.ellipog.tenet.client.dev.QuestPanelLayout;
import dev.ellipog.tenet.client.dev.ToolsLayout;
import dev.ellipog.tenet.client.dev.ToolsPanel;
import dev.ellipog.tenet.net.PartySnapshot;
import dev.ellipog.tenet.client.ClientPartyCache;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Util;
import net.minecraft.util.Mth;
import net.minecraft.world.item.ItemStack;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import dev.ellipog.tenet.QuestAuthority;

/**
 * The quest book's quest panel edits: what changing a quest's fields, tasks, rewards, conditions and
 * dependencies sends, and how the panel's rows are built for an author.
 *
 * <p>Moved out of {@link QuestBookScreen} as it stood, with the state only it reads; the screen
 * keeps the state it shares and reaches this through its {@code questEditor} field.
 */
final class BookQuestEditor {

    private final QuestBookScreen book;

    BookQuestEditor(QuestBookScreen book) {
        this.book = book;
    }

    private UUID partyPhaseTarget;

    /** Whether the owner has the inline rename field open, on the left column's name row. */
    private boolean partyRenaming;

    /** The header's way into the player's settings; for every player, unlike the author's pair. */
    private ArmatureButton settingsHeaderButton;

    /** The header's way into the player's pinned quests; beside Settings, for every player like it. */
    private ArmatureButton pinnedHeaderButton;
    private List<SettingsLayout.Entry> appearanceEntries = List.of();

    /** The colour the band is editing, or null. */
    private String toolsSelected;

    /** Whether the colour section is unfolded. Open by default: a folded section is one the author
     *  cannot see is there. */
    private boolean toolsColoursOpen = true;

    /**
     * Whether the palette section is unfolded. Open by default, unlike the colours: it is the selector
     * the tab exists for, and the wall it would make is the reason the colours fold instead.
     */
    private boolean toolsPaletteOpen = true;

    /**
     * Whether the chapter tab's appearance section is unfolded. Open by default: the fold exists so an
     * author can put it away, not so the palette hides until found.
     */
    private boolean toolsAppearanceOpen = true;

    /**
     * Whether the book tab's Book section is unfolded — the pack's own name and icon for the book.
     * Open by default, like the other sections: the fold exists so an operator theming the book can put
     * the pack's fields away.
     */
    private boolean toolsBookOpen = true;

    /**
     * Whether the canvas section is unfolded. Open by default, like the rest: the fold exists so an
     * author who came for the colours can put the surface away, not so it hides until found.
     */
    private boolean toolsCanvasOpen = true;

    /** The quest panel's folded sections, by heading key. Selection is not one of them: that is shared. */
    private final Set<String> questFolded = new LinkedHashSet<>();
    private ArmatureButton settingsButton;

    /** The settings preview's resolved outline, and the shape and angle it was resolved from. */
    private QuestShape previewShape;
    private int previewRotation = Integer.MIN_VALUE;
    private dev.ellipog.armature.client.ui.shape.Shape previewGeometry;

    /**
     * The Chapter tab's header: its name, its icon, and the id that icon was resolved from.
     *
     * <p>Recorded when the rows are built, from the same chapter tree, so the band at the top and the
     * fields below cannot describe two different chapters. The stack is kept beside the id because a
     * missing item is a fact worth drawing -- an empty box reads as "no icon set" -- and the id is how
     * the drawing tells the two apart.
     */
    private ChapterPanelLayout.Header chapterHeader = new ChapterPanelLayout.Header("", "");
    private ItemStack chapterIcon = ItemStack.EMPTY;
    private String chapterIconId = "";

    /**
     * The band's hex field, while there is one.
     *
     * <p>Held because {@code applyHex} has to write back to it, and because writing back is not rebuilding:
     * the first version answered a hex edit with {@code rebuildWidgets()}, which cleared this field, which
     * blurred it, which submitted it — and a submit is what asked for the rebuild. The game crashed with a
     * StackOverflowError five hundred frames deep, on the first hex anyone typed.
     */
    private dev.ellipog.armature.client.ArmatureTextField hexField;

    /**
     * The author's band: four controls in a strip of the panel's chrome, under the title row.
     *
     * <p>Built only for a player who may edit, like the header pair before them -- and, unlike the pills
     * they replace, <b>drawn by hand in the chrome layer</b> rather than by the widget pass. The pills
     * floated over the canvas, which is inside the pass's clip, so they painted themselves. The band is
     * above that clip -- the pass starts at {@code chapterListTop()}, below the header and the band -- so
     * each of these needs a line where the header's four controls have theirs, and {@code hoverTold} with
     * it, which is what makes a control above the clip fade under the pointer.
     *
     * <p>Three of them are surfaces and two are modes: {@link QuestBookScreen#pressAuthorPill} opens the dock,
     * {@link QuestBookScreen#openAssets} opens the pack's files, {@link #pressEditPill} latches edit mode,
     * {@link #setAdvanced} says how much of every editor menu to draw, and {@link #pressPreviewPill}
     * latches the player's view. The two latches are built only while edit
     * mode is on: with the mode off there is no editor to be shallow or deep about and no authoring
     * to preview, and a lit control that changed nothing on screen would be the fault this book's
     * chrome keeps designing out.
     */
    private ArmatureButton authorButton;
    /** The Assets button, beside it. Built for the same readers and for no others. */
    private ArmatureButton assetsButton;
    /** The Edit button: the latch over edit mode. */
    private ArmatureButton editButton;
    /** The Advanced button: the latch over the editor's depth. Null while edit mode is off. */
    private ArmatureButton advancedButton;
    /** The Preview button: the way into the player's view. Null while edit mode is off. */
    private ArmatureButton previewButton;
    /**
     * The way back out of the player's view: a floating pill over the canvas's top-right corner.
     *
     * <p>Built only while previewing, because only then is there no band to press: the preview uses
     * the reader's geometry, which has no band in it. An ordinary control rather than hand-drawn
     * chrome, like the view cluster beside it — the widget pass draws the canvas's furniture, and a
     * rail never covers a control, so it answers over a panel exactly as it does over bare canvas.
     */
    private ArmatureButton previewExitButton;

    /**
     * Floats the list onto the canvas as a box, or docks it back into the panel's left.
     *
     * <p>Both landings are expanded: docking clears a stale collapse the old mode could never
     * have produced, and floating shows the box rather than the sliver the last session may have
     * left — the mode switch demonstrates itself instead of needing a second press to prove it.
     */
    private void setSidebarFloating(boolean floating) {
        if (DevMode.isSidebarFloating() == floating) {
            return;
        }
        book.beginSidebarSlide();
        DevMode.setSidebarFloating(floating);
        DevMode.setSidebarCollapsed(false);
        book.bookState.sidebarPeeking = false;
        book.bookState.sidebarPeekCooling = false;
        book.bookState.sidebarBoxHovered = false;
        book.bookState.sidebarPeekLeaveStart = -1;
        book.bookState.sidebarPeekHoverStart = -1;
        // The rows are rebuilt below for the new target, so no departure survives the switch: kept
        // rows belong to the expanded box the collapse found, and a docked column (or a new box) is
        // not that rectangle — riding them out here would flash them where the old mode was.
        book.bookState.sidebarDeparting = false;
        book.rebuildWidgets();
        book.cancelSidebarSlideIfStatic();
    }

    /**
     * The settings rows with each control's width measured against its own label.
     *
     * <h2>Why the answer rides on the row</h2>
     *
     * <p>Because three things place a control — the widget build, the drawing and the press — and they must
     * agree to the pixel, which is the one rule this screen records more faults about than any other. A map
     * passed to each of them would be three chances to pass the wrong one or forget; a row that carries its
     * own width cannot be read any other way.
     *
     * <p>The measurement runs where the layout is already being built, so it is paid for once per rebuild and
     * never on the frame path. The label is resolved through the game's language, which is why this is the
     * screen's job and not the layout's: a dump has no language to resolve in and keeps the unmeasured rows.
     */
    private List<dev.ellipog.tenet.client.dev.QuestSettingsLayout.Row> measuredSettingsRows(
            List<dev.ellipog.tenet.client.dev.QuestSettingsLayout.Row> rows, int columnWidth) {
        java.util.Map<String, Integer> widths =
                dev.ellipog.tenet.client.dev.QuestSettingsLayout.controlWidths(rows, columnWidth,
                        Measure.monospace(6, 9), key -> Labels.of(key));
        List<dev.ellipog.tenet.client.dev.QuestSettingsLayout.Row> measured =
                new ArrayList<>(rows.size());
        for (dev.ellipog.tenet.client.dev.QuestSettingsLayout.Row row : rows) {
            measured.add(new dev.ellipog.tenet.client.dev.QuestSettingsLayout.Row(row.key(), row.kind(),
                    row.label(), widths.getOrDefault(row.key(), row.controlWidth())));
        }
        return List.copyOf(measured);
    }

    /**
     * The one press that pages between two columns and one.
     *
     * <h2>Two labels, because it is two acts</h2>
     *
     * <p>With a child showing it says <b>Fold</b> and pages the child into its parent; folded, it says
     * <b>Back</b> and brings the parent out again. Which one it is follows from {@link QuestBookScreen#panelFolded}, so the
     * word and the act cannot disagree — the rule the tools panel's switches already follow.
     *
     * <p>It sits in the header of the column that holds the child, taken from the frame's own cell
     * ({@code PanelLayout.frame(rail, true).fold()}) rather than from arithmetic here, so the button is where
     * the layout says the frame's control is.
     *
     * <p><b>The breadcrumb names the parent in its tooltip</b>, because "Back" alone does not say back to
     * what. Its press asks for both columns <i>even if the second covers the graph</i>: a reader who presses
     * it wants the parent, and the alternative is a button that looks like it did nothing because the window
     * is narrow.
     */
    private void buildFoldControl() {
        if (!book.carriesFold(book.bookState.overlay2)) {
            return;
        }
        // Filed under the child rail it pages: the fold control sits in that rail's header and travels
        // with it while the rail arrives, like everything else the rail owns.
        PanelKind outer = book.bookState.slideBucket;
        book.bookState.slideBucket = book.bookState.overlay2;
        try {
            buildFoldControlIn();
        }
        finally {
            book.bookState.slideBucket = outer;
        }
    }

    private void buildFoldControlIn() {
        boolean folded = book.panelFolded();
        BookGeometry.Rect cell = PanelLayout.frame(book.panelRail(book.bookState.overlay2), true).fold();
        if (cell == null) {
            return;
        }
        ArmatureButton foldButton = folded
                ? book.control(cell, Component.translatable("tenet.screen.back"), this::unfoldPanel)
                : book.control(cell, Component.translatable("tenet.screen.fold"), this::foldPanel);
        if (foldButton != null) {
            foldButton.ink(ArmatureButton.Ink.BODY);
            if (folded) {
                // What it goes back to, in the one place there is room to say it.
                ClientQuestCache.Entry parent = book.entryFor(book.bookState.overlayQuest);
                if (parent != null) {
                    foldButton.tooltip(Component.literal(QuestBookScreen.titleOf(parent)));
                }
            }
        }
    }

    /**
     * The Edit pill's press: edit mode on or off, and nothing else.
     *
     * <h2>What it used to be, and why that was wrong</h2>
     *
     * <p>It was not simply a toggle. Because the dock and a docked panel share column 1 — the choice, so that
     * a column never becomes two — and the dock is not drawn while a panel is in it, this press meant "close
     * the panel and give me the dock back" whenever a panel was open. So an author who pressed Edit to leave
     * edit mode got the panel closed and edit mode left <i>on</i>, and had to press again to do the thing
     * they asked for. One control, two meanings, and the second one was not what its label said.
     *
     * <p>The dock has its own pill now ({@link QuestBookScreen#pressAuthorPill}), which is what made the press one thing:
     * the panel is the panel's business, the dock is the dock's, and this is the mode's. Turning edit mode
     * off still closes the arrangement — see {@link #setEditing} for why the columns go with it — but that is
     * a consequence of leaving the mode rather than a second job for this press.
     */
    private void pressEditPill() {
        setEditing(!DevMode.on());
    }

    /**
     * The Preview pill's press: the player's view on or off, and nothing else.
     *
     * <p>On, the book becomes what a player without the edit permission sees — the reader's card at
     * the reader's size, hidden chapters and quests filtered out, editing gestures off, quest cards
     * the reader's — while edit mode itself stays on underneath, so leaving the preview restores
     * exactly the mode that was left. Turning it on puts the editor's columns away like leaving edit
     * mode does, because a panel whose every field just went inert is not a panel worth standing in.
     * Off is just the latch back: closed panels stay closed, the same way they do when edit mode is
     * left. The way out of a pill-entered preview is the floating pill over the canvas, since the
     * band it was entered from belongs to a geometry this preview does not use (a command-entered
     * one has no pill, and its way out is the command again).
     */
    private void pressPreviewPill() {
        book.bookCanvas.previewAsPlayer = !book.bookCanvas.previewAsPlayer;
        // The pill is its own way out, so a preview entered here always shows it — and a
        // command-entered one keeps hiding it until the command (or closing the book) ends it.
        book.bookCanvas.previewFromCommand = false;
        if (book.bookCanvas.previewAsPlayer) {
            book.bookMenu.closeMenu();
            book.closeColourPopover();
            book.applyColumns(PanelStack.afterClose(book.columns(), false));
        }
        book.bookInput.authorReport(book.bookCanvas.previewAsPlayer ? "Previewing as a player" : "Back to editing");
        book.rebuildWidgets();
    }

    /** Pages the child into the first column, and remembers that the player asked for it. */
    private void foldPanel() {
        setFoldChoice(PanelStack.Fold.ALWAYS);
    }

    /** Pages back out to the parent: both columns, even when the second one covers the graph. */
    private void unfoldPanel() {
        setFoldChoice(PanelStack.Fold.NEVER);
    }

    /**
     * Remembers how the player wants the second column, in the session and in the file.
     *
     * <p>Both, in that order: the field is what this frame reads and the file is what the next session reads.
     * A fold that changed only the field would forget itself, which is the one thing a preference must not
     * do. The rebuild is what makes the fold take effect — the columns' rectangles all follow from it.
     */
    private void setFoldChoice(PanelStack.Fold choice) {
        book.bookState.foldChoice = choice;
        DevMode.setPanelFold(choice);
        // Through the arrangement writer rather than straight to a rebuild: paging the child in or out
        // shows and hides rails, so the fold is an arrival and a departure like any other — stamped and
        // snapshotted here, drawn by the same slides. The rebuild that takes effect follows.
        book.applyColumns(book.columns());
        book.rebuildWidgets();
    }

    /**
     * The rows the panel shows, as the panel's own class decides them.
     *
     * <p>The rule moved to {@link PartyPanelLayout#actions}, and it moved because a screen cannot be
     * asked anything: while it lived here, the only way to find out what a party of three with two
     * players online offers was to open the book and look. What stays is the one thing this class knows
     * and that method cannot -- who is looking.
     */
    private void buildPartyWidgets() {
        book.bookState.partyLeftView.clear();
        book.bookState.partyRightView.clear();
        book.bookState.partyLeftLines = new ArrayList<>();
        book.bookState.partyRightLines = new ArrayList<>();
        book.bookState.partyNameField = null;
        book.bookState.partyCreateField = null;
        book.bookState.partySearchField = null;
        book.bookState.partyRemoveButtons.clear();
        book.bookState.partyTransferButtons.clear();
        book.bookState.partyControls.clear();

        PartyRoster roster = book.partyRoster();
        PartySnapshot snapshot = ClientPartyCache.snapshot();

        if (!book.partiesAvailable()) {
            // Singleplayer with LAN closed. The panel stays reachable and says why, rather than a
            // button greyed out with a tooltip: a disabled control explains nothing about the one
            // action that changes the state, and the notice's second line is that action.
            buildPartyNotice();
            return;
        }
        if (book.bookState.partyPhase != QuestBookScreen.PartyPhase.NONE) {
            buildPartyPhase(roster);
            return;
        }
        if (!roster.isReal()) {
            buildPartySolo(roster, snapshot);
            return;
        }
        buildPartyActive(roster, snapshot);
    }

    /**
     * The notice face: one column of sentences with one way out.
     *
     * <h2>Wrapped at the width the rail will really have</h2>
     *
     * <p>The rail is clamped to the canvas, so wrapping at the preferred 320 would under-count the lines in
     * a narrow window and the sentences would be cut. The width is asked of the rail's own rectangle -- and
     * the same number sizes the face, wraps the text and builds the rows, so the three cannot disagree.
     */
    private void buildPartyNotice() {
        int cardWidth = Math.min(BookGeometry.PARTY_MODAL_WIDTH, book.panelRail(PanelKind.PARTY).width());
        int textWidth = Math.max(0, cardWidth - BookGeometry.MODAL_INSET * 2);
        // No heading: the card's band already reads "Not available in this world".
        PartyPanelLayout.Face face = PartyPanelLayout.notice("",
                QuestBookScreen.partyText("tenet.screen.party.unavailable.why"),
                QuestBookScreen.partyText("tenet.screen.party.unavailable.how"),
                textWidth, BookScreenState.TEXT_MEASURE);
        book.bookState.partyCard = partySurface();
        book.bookState.partyLeftFace = face;
        book.bookState.partyLeftLines = face.lines();
        book.bookState.partyLeftLayout = face.build(partyBodyWidth(), BookScreenState.TEXT_MEASURE);
        book.bookState.partyLeftView.apply(book.bookState.partyLeftLayout, partyBodyWidth());
        placePartyControls(book.bookState.partyLeftFace);
        singleFooter("tenet.screen.party.done", book::closeOverlay);
    }

    /**
     * A card for a question the panel asked: the transfer confirmation and the successor picker.
     *
     * <h2>Why these are separate cards rather than rows in the party</h2>
     *
     * <p>Because both interrupt: neither is information about the party, they are questions about what
     * to do with it, and one of them — the successor picker — is reached by pressing Leave, so
     * answering "no" has to put the player back where they were rather than into some changed panel.
     * A phase with its own card is that, and it is also what keeps the main panel's controls from
     * staying live under a question.
     */
    private void buildPartyPhase(PartyRoster roster) {
        List<PartyPanelLayout.Line> lines = new ArrayList<>();
        List<PartyPanelLayout.Control> actions = new ArrayList<>();

        if (book.bookState.partyPhase == QuestBookScreen.PartyPhase.CONFIRM_TRANSFER) {
            String target = nameOfMember(roster, partyPhaseTarget);
            lines.add(PartyPanelLayout.Line.header("confirm:title",
                    Component.translatable("tenet.screen.party.confirm.transfer.title").getString()));
            lines.add(PartyPanelLayout.Line.plain("confirm:body",
                    Component.translatable("tenet.screen.party.confirm.transfer.body", target)
                            .getString()));
            actions.add(PartyPanelLayout.Control.primary("phase:confirm",
                    "/tenet party transfer " + target));
            actions.add(PartyPanelLayout.Control.small("phase:cancel", null));
        }
        else if (book.bookState.partyPhase == QuestBookScreen.PartyPhase.CONFIRM_DISBAND) {
            String name = ClientPartyCache.snapshot().teamName();
            if (name == null || name.isEmpty()) {
                name = Component.translatable("tenet.screen.party.unnamed").getString();
            }
            lines.add(PartyPanelLayout.Line.header("confirm:title",
                    Component.translatable("tenet.screen.party.confirm.disband.title", name).getString()));
            lines.add(PartyPanelLayout.Line.plain("confirm:body",
                    Component.translatable("tenet.screen.party.confirm.disband.body").getString()));
            actions.add(PartyPanelLayout.Control.primary("phase:disband", "/tenet party disband"));
            actions.add(PartyPanelLayout.Control.small("phase:cancel", null));
        }
        else {
            lines.add(PartyPanelLayout.Line.header("confirm:title",
                    Component.translatable("tenet.screen.party.confirm.successor.title").getString()));
            lines.add(PartyPanelLayout.Line.plain("confirm:body",
                    Component.translatable("tenet.screen.party.confirm.successor.body").getString()));
            for (PartyRoster.Member member : roster.members()) {
                if (member.self()) {
                    continue;
                }
                lines.add(PartyPanelLayout.Line.controls("succeed:" + member.id(), member.name(),
                        PartyPanelLayout.Control.primary("succeed-go:" + member.id(),
                                "/tenet party handover " + member.name())));
            }
            actions.add(PartyPanelLayout.Control.small("phase:disband", "/tenet party disband"));
            actions.add(PartyPanelLayout.Control.small("phase:cancel", null));
        }
        lines.add(PartyPanelLayout.Line.controls("confirm:actions", "",
                actions.toArray(new PartyPanelLayout.Control[0])));

        PartyPanelLayout.Face face = PartyPanelLayout.face(lines);
        book.bookState.partyCard = partySurface();
        book.bookState.partyLeftFace = face;
        book.bookState.partyLeftLines = face.lines();
        book.bookState.partyLeftLayout = face.build(partyBodyWidth(), BookScreenState.TEXT_MEASURE);
        book.bookState.partyLeftView.apply(book.bookState.partyLeftLayout, partyBodyWidth());
        placePartyControls(book.bookState.partyLeftFace);
        singleFooter("tenet.screen.party.back", this::cancelPartyPhase);
    }

    /** The solo onboarding: start a party on the left, join one on the right. */
    private void buildPartySolo(PartyRoster roster, PartySnapshot snapshot) {
        if (book.bookState.partyCreateName.isEmpty()) {
            book.bookState.partyCreateName = defaultPartyName();
        }
        // The create row wraps below NARROW_WRAP_WIDTH so the wider Create button never squeezes the
        // field away. Measured from the rail the card will take, like the notice face does.
        int soloWidth = Math.max(0, book.panelRail(PanelKind.PARTY).width() - BookGeometry.MODAL_INSET * 2);
        book.bookState.partyLeftFace = PartyPanelLayout.soloLeft(roster, snapshot, selfName(),
                book.bookState.partyCreateName, soloWidth);
        book.bookState.partyRightFace = PartyPanelLayout.soloRight(snapshot);
        buildTwoColumns();
        buildPartyFooter(Footer.doneOnly(), roster);

        book.bookState.partyCreateField = new ArmatureTextField(0, 0, 0, 0, book.bookState.partyCreateName);
        book.bookState.partyCreateField.onSubmit(text -> {
            book.bookState.partyCreateName = text;
            String trimmed = text == null ? "" : text.trim();
            if (trimmed.isEmpty()) {
                return;
            }
            runPartyCommand("/tenet party create " + trimmed);
        });
        book.addRenderableWidget(book.bookState.partyCreateField);
        if (book.bookState.partyCreateFocused) {
            book.setFocused(book.bookState.partyCreateField);
        }
    }

    /** The active party: the roster on the left, its management on the right. */
    private void buildPartyActive(PartyRoster roster, PartySnapshot snapshot) {
        book.bookState.partyLeftFace = PartyPanelLayout.activeLeft(roster, snapshot);
        book.bookState.partyRightFace = PartyPanelLayout.activeRight(roster, snapshot, selfName(),
                book.bookState.partySearch);
        buildTwoColumns();
        buildPartyFooter(Footer.of(roster.canDisband(), roster.canLeave() && roster.memberCount() > 1),
                roster);

        // The roster rows are not `Line`s: they are `PartyRoster`'s own composition, nested into the
        // left column's stack in `activeLeft`. Their buttons are registered here against that stack.
        for (PartyRoster.Member member : roster.members()) {
            if (member.canRemove()) {
                ArmatureButton remove = book.control(0, 0, 0, 0,
                        Component.translatable("tenet.screen.party.remove"),
                        () -> runPartyCommand("/tenet party kick " + member.name()));
                if (remove != null) {
                    remove.tooltip(List.of(
                            Component.translatable("tenet.screen.party.remove_hint", member.name()),
                            Component.translatable("tenet.screen.party.keep_progress")));
                    book.bookState.partyRemoveButtons.put(member.id(), remove);
                }
            }
            if (member.canTransfer()) {
                ArmatureButton transfer = book.control(0, 0, 0, 0,
                        Component.translatable("tenet.screen.party.transfer"),
                        () -> openPartyPhase(QuestBookScreen.PartyPhase.CONFIRM_TRANSFER, member.id()));
                if (transfer != null) {
                    transfer.tooltip(List.of(
                            Component.translatable("tenet.screen.party.transfer_hint", member.name()),
                            Component.translatable("tenet.screen.party.stay_member")));
                    book.bookState.partyTransferButtons.put(member.id(), transfer);
                }
            }
        }

        // The rename field replaces the name row's label while it is open, so there is one thing in
        // that row rather than a field over a name. The Rename button itself comes from the layout's
        // left:name row (owner only); this only owns the field.
        if (partyRenaming) {
            book.bookState.partyNameField = new ArmatureTextField(0, 0, 0, 0, snapshot.teamName());
            book.bookState.partyNameField.onSubmit(text -> {
                partyRenaming = false;
                runPartyCommand("/tenet party rename " + text.trim());
            });
            book.addRenderableWidget(book.bookState.partyNameField);
            book.setFocused(book.bookState.partyNameField);
        }

        // The search field, on the right column's own row.
        book.bookState.partySearchField = new ArmatureTextField(0, 0, 0, 0, book.bookState.partySearch);
        book.bookState.partySearchField.onSubmit(text -> {
            book.bookState.partySearch = text;
            book.bookState.partySearchFocused = false;
            book.rebuildWidgets();
        });
        book.addRenderableWidget(book.bookState.partySearchField);
        if (book.bookState.partySearchFocused) {
            book.setFocused(book.bookState.partySearchField);
        }
    }

    /** Builds the panel's column from the faces and registers every line's controls. */
    private void buildTwoColumns() {
        book.bookState.partyCard = partySurface();
        // **Stacked, because the sidebar is tall.** The two faces are one line list here -- the roster's
        // lines, a gap, and the management's -- so the panel is one column of content: one layout, one
        // view, one bar, and the whole of the canvas's height to spend instead of the width the faces
        // used to share. The roster's own rows are not lines at all: they live in the left face's stack
        // (appended composition), so the combined stack must append the two faces' stacks rather than
        // rebuilding from the line list alone — rebuilding from lines drops every member row, which is
        // exactly how a party of 1 drew no roster at all.
        List<PartyPanelLayout.Line> stacked = new ArrayList<>(book.bookState.partyLeftFace.lines());
        stacked.add(PartyPanelLayout.Line.spacer("stack:gap"));
        stacked.addAll(book.bookState.partyRightFace.lines());
        dev.ellipog.armature.client.ui.kit.Stack combined = book.bookState.partyLeftFace.stack()
                .gap(PartyPanelLayout.ROW_GAP)
                .append(PartyPanelLayout.stack(
                        List.of(PartyPanelLayout.Line.spacer("stack:gap"))))
                .gap(PartyPanelLayout.ROW_GAP)
                .append(book.bookState.partyRightFace.stack());
        book.bookState.partyRightFace = null;
        book.bookState.partyLeftFace = new PartyPanelLayout.Face(combined, List.copyOf(stacked));

        book.bookState.partyLeftLayout = book.bookState.partyLeftFace.build(book.partyColumn(true).viewWidth(),
                BookScreenState.TEXT_MEASURE);
        book.bookState.partyLeftView.apply(book.bookState.partyLeftLayout, book.partyColumn(true).viewWidth());
        book.bookState.partyLeftLines = book.bookState.partyLeftFace.lines();
        placePartyControls(book.bookState.partyLeftFace);
        if (book.bookState.partyRightFace != null) {
            book.bookState.partyRightLayout = book.bookState.partyRightFace.build(book.partyColumn(false).viewWidth(),
                    BookScreenState.TEXT_MEASURE);
            book.bookState.partyRightView.apply(book.bookState.partyRightLayout, book.partyColumn(false).viewWidth());
            book.bookState.partyRightLines = book.bookState.partyRightFace.lines();
            placePartyControls(book.bookState.partyRightFace);
        }
        else {
            // One view: the second's state is cleared rather than left showing the last card's lines.
            book.bookState.partyRightLayout = null;
            book.bookState.partyRightLines = null;
            book.bookState.partyRightView.clear();
        }
    }

    /**
     * The rectangle the party panel draws in: its rail.
     *
     * <p>One function for the three places that size it — the notice, a phase, and the two-column panel —
     * because they are the same panel, and one that took the rail in two of the three would be a panel built
     * against one rectangle and drawn in another. It took an argument while there were two presentations to
     * choose between; there is one, so it takes none, and the body band the faces fill is the rail's own —
     * see {@link QuestBookScreen#partyBody}.
     */
    private BookGeometry.Rect partySurface() {
        return book.surfaceCard(PanelKind.PARTY);
    }

    /**
     * Registers a face's controls: a button per command, a switch per toggle, a screen action per
     * key.
     *
     * <p>Each control's rectangle is derived from its own row's slot — one derivation for all three
     * kinds, so a switch and a button in the same column cannot be placed by two arithmetics that
     * agree until one row grows a control.
     */
    private void placePartyControls(PartyPanelLayout.Face face) {
        for (PartyPanelLayout.Line line : face.lines()) {
            List<PartyPanelLayout.Control> controls = line.controls();
            for (int i = 0; i < controls.size(); i++) {
                PartyPanelLayout.Control spec = controls.get(i);
                if (spec.isToggle()) {
                    ArmatureSwitch toggle = new ArmatureSwitch(0, 0, toggleState(spec.key()));
                    toggle.onToggle(() -> runPartyCommand(toggleCommand(spec.key(), toggle.selected())));
                    // Settings are owner-only (PartyRoster.canSetPolicy): a member sees the state but
                    // cannot flip it. Disabled rather than hidden, so the policy is still readable.
                    toggle.active = book.partyRoster().canSetPolicy();
                    book.addRenderableWidget(toggle);
                    book.bookState.partyControls.put(spec.key(), toggle);
                }
                else if (spec.key() != null && spec.key().endsWith(":rename")) {
                    // Visible Rename button on the name row (owner only — the layout only emits it
                    // when the roster permits). Opens the inline rename field; the old invisible
                    // click-on-name overlay is gone because an affordance nobody can see is not one.
                    ArmatureButton rename = book.control(0, 0, 0, 0,
                            buttonLabel(spec, line), () -> {
                                partyRenaming = true;
                                book.rebuildWidgets();
                            });
                    if (rename != null) {
                        rename.tooltip(List.of(Component.translatable("tenet.screen.party.rename")));
                        book.bookState.partyControls.put(spec.key(), rename);
                    }
                }
                else if (spec.sendsCommand()) {
                    ArmatureButton button = book.control(0, 0, 0, 0,
                            buttonLabel(spec, line), () -> runPartyCommand(spec.command()));
                    if (button != null) {
                        button.accent(spec.accent());
                        // The label only. The slash command this press sends used to be the second
                        // line, and it is not a player's business: somebody pressing Disband wants to
                        // know what the press does, not which Command the client runs for them.
                        button.tooltip(List.of(Component.literal(QuestBookScreen.partyText(line.label()))));
                        book.bookState.partyControls.put(spec.key(), button);
                    }
                }
                else {
                    ArmatureButton cancel = book.control(0, 0, 0, 0,
                            Component.translatable("tenet.screen.party.cancel"),
                            this::cancelPartyPhase);
                    if (cancel != null) {
                        book.bookState.partyControls.put(spec.key(), cancel);
                    }
                }
            }
        }
    }

    /**
     * The footer, from which of the three controls exist.
     *
     * <h2>Why a record rather than two booleans</h2>
     *
     * <p>Because the three are a set the layout, the buttons and the preview all have to agree about —
     * a sole owner hides Leave and keeps Disband, a member sees Leave and Done, the notice sees Done
     * alone — and passing "hasDisband, hasLeave" around as two loose booleans is two chances to
     * transpose them.
     */
    private record Footer(boolean disband, boolean leave, boolean done) {

        static Footer of(boolean disband, boolean leave) {
            return new Footer(disband, leave, true);
        }

        static Footer doneOnly() {
            return new Footer(false, false, true);
        }
    }

    /** Places the footer's controls for a card, from the same map the preview reads. */
    private void buildPartyFooter(Footer wanted, PartyRoster roster) {
        Map<String, BookGeometry.Rect> footer = book.geometry().partyControls(
                book.bookState.partyCard, wanted.disband(), wanted.leave(), wanted.done());

        ArmatureButton leave = book.control(footer.get("leave"),
                Component.translatable("tenet.screen.party.leave"),
                () -> {
                    if (roster.canDisband() && roster.memberCount() > 1) {
                        // The owner may not simply leave: the party would be handed over by the
                        // fallback rule, to whoever the sort happens to name. The spec asks, and this
                        // is the ask.
                        openPartyPhase(QuestBookScreen.PartyPhase.PICK_SUCCESSOR, null);
                    }
                    else {
                        runPartyCommand("/tenet party leave");
                    }
                });
        if (leave != null) {
            leave.tooltip(List.of(Component.translatable("tenet.screen.leave_the_party"),
                    Component.translatable("tenet.screen.you_keep_the_progress_you_earned_here")));
        }

        ArmatureButton disband = book.control(footer.get("disband"),
                Component.translatable("tenet.screen.party.disband"),
                this::pressDisband);
        if (disband != null) {
            // Warning amber, not the blocked grey: the button is destructive, and a grey label was
            // read as "this one is unavailable to me". See ArmatureButton.Ink.DANGER.
            disband.ink(ArmatureButton.Ink.DANGER);
            disband.tooltip(List.of(Component.translatable("tenet.screen.dissolve_the_party"),
                    Component.translatable("tenet.screen.press_again_within_three_seconds_to_confirm")));
            book.bookState.disbandButton = disband;
        }

        ArmatureButton done = book.control(footer.get("done"),
                Component.translatable("tenet.screen.party.done"), book::closeOverlay);
        if (done != null) {
            done.accent(true);
        }
    }

    /** A one-button footer for the notice and the phases: Back, at Done's own place. */
    private void singleFooter(String labelKey, Runnable onPress) {
        Map<String, BookGeometry.Rect> footer = book.geometry().partyControls(book.bookState.partyCard, false,
                false, true);
        book.control(footer.get("done"), Component.translatable(labelKey), onPress);
    }

    /** The body width of a content-sized card, for a single-column face. */
    private int partyBodyWidth() {
        return Math.max(0, book.bookState.partyCard.width() - BookGeometry.MODAL_INSET * 2);
    }

    private String selfName() {
        return book.minecraft() == null || book.minecraft().player == null ? ""
                : book.minecraft().player.getScoreboardName();
    }

    /** The name the create field starts with: the player's own, made safe for a command argument. */
    private String defaultPartyName() {
        String self = selfName();
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < self.length(); i++) {
            char c = self.charAt(i);
            out.append(Character.isLetterOrDigit(c) || c == ' ' || c == '_' || c == '-' ? c : '_');
        }
        return (self.isEmpty() ? "My" : out.toString()) + " party";
    }

    private String nameOfMember(PartyRoster roster, UUID id) {
        for (PartyRoster.Member member : roster.members()) {
            if (member.id().equals(id)) {
                return member.name();
            }
        }
        return "?";
    }

    /** The label a line's button wears, from the control's key. */
    private Component buttonLabel(PartyPanelLayout.Control spec, PartyPanelLayout.Line line) {
        String key = spec.key();
        if (key.startsWith("accept:")) {
            return Component.translatable("tenet.screen.party.accept");
        }
        if (key.startsWith("decline:")) {
            return Component.translatable("tenet.screen.party.decline");
        }
        if (key.startsWith("invite-go:")) {
            return Component.translatable("tenet.screen.party.invite");
        }
        if (key.startsWith("join:")) {
            return Component.translatable("tenet.screen.party.join");
        }
        if (key.startsWith("cancel:")) {
            return Component.translatable("tenet.screen.party.cancel");
        }
        if (key.startsWith("succeed-go:")) {
            return Component.translatable("tenet.screen.party.choose");
        }
        if (key.startsWith("phase:confirm")) {
            return Component.translatable("tenet.screen.party.confirm");
        }
        if (key.startsWith("phase:disband")) {
            return Component.translatable("tenet.screen.party.disband");
        }
        if (key.startsWith("solo:create")) {
            return Component.translatable("tenet.screen.party.create");
        }
        if (key.endsWith(":rename")) {
            return Component.translatable("tenet.screen.party.rename.button");
        }
        return Component.literal(line.label().isEmpty() ? key : QuestBookScreen.partyText(line.label()));
    }

    /** Which switch a toggle key is: the member-invite policy, or the public one. */
    private boolean toggleState(String key) {
        PartyRoster roster = book.partyRoster();
        return key.startsWith("right:open") ? roster.openJoin() : roster.membersCanInvite();
    }

    /** The command a flipped switch sends: the policy in force with that one field changed. */
    private String toggleCommand(String key, boolean value) {
        PartyRoster roster = book.partyRoster();
        boolean open = key.startsWith("right:open") ? value : roster.openJoin();
        boolean memberInvites = key.startsWith("right:open") ? roster.membersCanInvite() : value;
        // Two commands rather than one that takes both, because each switch means one change and a
        // command carrying the other field's current value would be a way for a stale read to undo a
        // change made between the draw and the press.
        return open != roster.openJoin()
                ? "/tenet party open " + (open ? "on" : "off")
                : "/tenet party member-invites " + (memberInvites ? "on" : "off");
    }

    private void openPartyPhase(QuestBookScreen.PartyPhase phase, UUID target) {
        book.bookState.partyPhase = phase;
        partyPhaseTarget = target;
        book.rebuildWidgets();
    }

    private void cancelPartyPhase() {
        book.bookState.partyPhase = QuestBookScreen.PartyPhase.NONE;
        partyPhaseTarget = null;
        book.rebuildWidgets();
    }
    /**
     * The tools panel's controls: its switches, its rows, its channel steppers and its two actions.
     *
     * <h2>What is a widget and what is drawn</h2>
     *
     * <p>A row is a widget when pressing it does something -- a theme, a colour, a fold. The colour a row
     * *shows* is drawn by {@link ToolsPanel}, because a swatch is a rectangle and no widget draws those;
     * the rows are flat, so the two do not fight over the same pixels. That is the same split the
     * developer screen learned the hard way, one screen earlier.
     *
     * <p>The panel's geometry is {@link ToolsLayout}'s, and the widgets are placed from its layout by the
     * scroll view -- so a row added there appears here, and the two cannot disagree about where a row is.
     */
    private void buildToolsWidgets() {
        // The target follows from the tab: the chapter tab edits the chapter, the book tab edits the
        // player's own look. Derived here, once a build, so every helper that asks `themeTargetsChapter`
        // answers the same thing the panel is showing.
        book.bookState.themeTargetsChapter = book.bookState.toolsTab == ToolsLayout.Tab.CHAPTER;
        // **The dock's own rail, asked for by kind**: `panelRail` resolves the width, the anchor and the
        // folded case from the arrangement, so this is the same call a docked panel's surface makes and the
        // two cannot disagree about the edge they share. See `ToolsLayout.frame`.
        book.bookState.toolsFrame = ToolsLayout.frame(book.panelRail(PanelKind.TOOLS), book.bookState.toolsTab);

        // The tab strip: the Book panel and the Chapter panel, side by side above the status line -- the
        // arrangement the drawer had before the short-lived title menu, restored because a dropdown that
        // hides one of two options is a worse control than the two options themselves. The active one is
        // selected, the same pressed-look rule every other control in this screen follows.
        book.control(ToolsLayout.tabBook(book.bookState.toolsFrame.tabs()),
                Component.translatable(ToolsLayout.Tab.BOOK.label()),
                () -> setTab(ToolsLayout.Tab.BOOK)).selected(book.bookState.toolsTab == ToolsLayout.Tab.BOOK);
        book.control(ToolsLayout.tabChapter(book.bookState.toolsFrame.tabs()),
                Component.translatable(ToolsLayout.Tab.CHAPTER.label()),
                () -> setTab(ToolsLayout.Tab.CHAPTER)).selected(book.bookState.toolsTab == ToolsLayout.Tab.CHAPTER);

        if (book.bookState.toolsTab == ToolsLayout.Tab.CHAPTER) {
            // The chapter's own file, from the replica, as the rows' source. The quests are edited in
            // their own card -- the modal -- and this tab is the chapter: title, icon, rules, and the
            // authored order of the quests.
            //
            // Without edit mode there is no copy to show and no request has been made: the tools are the
            // author's, and the tab says so instead of reporting the copy as still coming. See
            // `ChapterPanelLayout.notEditing` for why that state is a plain value row -- the widget loop
            // below then builds nothing at all, so no field or toggle can look live and do nothing.
            boolean editing = book.mayEditNow();
            JsonObject chapter = editing ? ClientChapterReplica.chapterTree(QuestBookScreen.effectiveChapter())
                    : new JsonObject();
            // The pending chapter values, so a title or a rule shows the press's value before the
            // replica catches up -- every row below is built from this one tree.
            chapter = BookScreenState.fieldDraft.overlaid(QuestBookScreen.effectiveChapter(),
                    dev.ellipog.tenet.client.dev.FieldDraft.CHAPTER_OWNER, chapter);
            readChapterIdentity(chapter);
            ChapterPanelLayout.GroupInfo group = editing ? chapterGroupInfo(QuestBookScreen.effectiveChapter()) : null;
            book.bookState.chapterRows = editing
                    ? ChapterPanelLayout.rows(chapter, group, questFolded,
                            ClientChapterReplica.refusal(QuestBookScreen.effectiveChapter()), packProblems(),
                            // Which element the fields under the list belong to. Null is the ordinary state:
                            // an author who has not chosen one sees the list and nothing under it. And which
                            // link: the same ordinary state, for the same reason, one list below.
                            BookScreenState.selectedElement, BookCanvas.selectedLink)
                    : ChapterPanelLayout.notEditing(packProblems());
            // The appearance section: the same rows the book tab shows, under one fold, writing the
            // chapter's own theme and patch. Absent for a reader -- there are no controls for a file
            // the server would refuse -- and the content keeps the layout it has always had.
            book.bookState.chapterAppearanceRows = editing
                    ? ToolsLayout.chapterAppearanceRows(paletteOptions(), toolsAppearanceOpen,
                            toolsPaletteOpen, toolsColoursOpen, book.editedBackground(), toolsCanvasOpen,
                            canvasCopyLabel())
                    : List.of();
            // One list, two row models: the appearance actions on top, the chapter's own content below.
            // `Stack.append` moves elements between stacks, so each side keeps the heights and gaps its
            // own model computed, and a section gap joins the two.
            // The chapter's own rows first -- identity at the top -- and the shared appearance
            // sections last, which is the Book tab's own order (the panel's rows, then the appearance
            // fold) and therefore parity between the two tabs rather than two arrangements to learn.
            Stack combined = Stack.stack();
            combined.append(ToolsLayout.stack(book.bookState.chapterRows, InspectLayout.Mode.STACKED));
            if (!book.bookState.chapterAppearanceRows.isEmpty()) {
                combined.gap(ToolsLayout.SECTION_GAP);
                combined.append(ToolsLayout.stack(book.bookState.chapterAppearanceRows));
            }
            book.bookState.chapterLayout = combined.build(book.bookState.toolsFrame.list().width(),
                    Measure.monospace(6, 9));
            book.bookState.toolsView.clear();
            book.bookState.toolsView.whole(true);
            book.bookState.toolsView.viewport().bounds(book.bookState.toolsFrame.list().x(),
                    book.bookState.toolsFrame.list().y(),
                    book.bookState.toolsFrame.list().width(), book.bookState.toolsFrame.list().height());

            for (ToolsLayout.Action row : book.bookState.chapterRows) {
                switch (row.kind()) {
                    case TEXT -> {
                        ArmatureTextField field = new ArmatureTextField(0, 0, 0, 0, row.value());
                        String path = row.key();
                        field.onSubmit(text -> commitField(path, text, true));
                        field.colours(ArmatureTheme.title(), ArmatureTheme.recessed(),
                                ArmatureTheme.panelEdge());
                        book.bookState.toolsView.put(row.key(), field, InspectLayout::controlBand);
                        book.addRenderableWidget(field);
                    }
                    case BUTTON -> {
                        // **Three buttons arrive here, and the third is not an item at all.** The two icon
                        // rows are the chapter's own and its group's: the button shows the item it names --
                        // or, for a group that has none of its own, the word saying so, which is deliberately
                        // a different reading from what the sidebar draws in the meantime (see
                        // `chapterGroupInfo`) -- and opens the same picker the card's icon does.
                        //
                        // An element's is the picture's file, which is a **PNG in the pack** rather than a
                        // registry item, so it opens the texture picker and carries no item icon. It used to
                        // fall through to the chapter's item picker -- which listed items for a field that
                        // takes a path, and whose commit writes `icon` on the *chapter*: a press on a
                        // picture's "Choose a file..." replaced the chapter's icon. The element panel routes
                        // this row itself (`openTexturePickerFor`), and this is the same routing for the same
                        // row key.
                        String[] element = ChapterPanelLayout.elementFieldOf(row.key());
                        boolean groupIcon = (ChapterPanelLayout.GROUP_PREFIX
                                + ChapterPanelLayout.ICON).equals(row.key());
                        boolean own = group != null && !group.iconId().isEmpty();
                        boolean picture = element != null
                                && dev.ellipog.tenet.client.dev.ElementPanelLayout.PICK_TEXTURE
                                        .equals(element[1]);
                        // A link's press rows, before the icon rows below: pick names a target, jump opens
                        // one, delete asks twice, and add makes a marker. Routed here rather than through the
                        // icon pickers because none of them is choosing an item.
                        ArmatureButton linkButton = linkButton(row);
                        if (linkButton != null) {
                            book.bookState.toolsView.put(row.key(), linkButton, InspectLayout::controlBand);
                            break;
                        }
                        ArmatureButton pick = book.control(0, 0, 0, 0,
                                Component.literal(row.value().isEmpty()
                                        ? Labels.of(picture ? "tenet.dev.element.pick_file"
                                                : groupIcon ? "tenet.dev.chapter.group_icon_unset"
                                                        : "tenet.dev.chapter.pick_item")
                                        : row.value()),
                                picture ? () -> book.texturePicker.openTexturePickerFor(element[0])
                                        : groupIcon ? this::openGroupItemPicker
                                                : this::openChapterItemPicker);
                        if (groupIcon && !own) {
                            pick.textColour(ArmatureTheme.faint());
                        }
                        pick.ink(ArmatureButton.Ink.BODY).alignLeft(true);
                        if (!picture) {
                            // The two icon rows wear the icon they name, in any arm. A path has no icon
                            // to wear, and the chapter's own would be a lie about what the button is
                            // choosing. An entity arm arrives as its egg stack, so it wears the item
                            // arm like everywhere else; texture and sprite ride the button's own blit.
                            ItemStack worn = groupIcon ? groupIconStack(group) : chapterIcon;
                            String wornTexture = groupIcon ? groupTextureIcon(group)
                                    : iconString(chapter, "texture");
                            String wornSprite = groupIcon ? groupSpriteIcon(group)
                                    : iconString(chapter, "sprite");
                            if (!worn.isEmpty()) {
                                pick.icon(worn);
                            }
                            else if (!wornTexture.isEmpty()
                                    && net.minecraft.resources.Identifier.tryParse(wornTexture)
                                            != null) {
                                pick.texture(net.minecraft.resources.Identifier.parse(wornTexture));
                            }
                            else if (!wornSprite.isEmpty()
                                    && net.minecraft.resources.Identifier.tryParse(wornSprite)
                                            != null) {
                                pick.sprite(net.minecraft.resources.Identifier.parse(wornSprite));
                            }
                        }
                        book.bookState.toolsView.put(row.key(), pick, InspectLayout::controlBand);
                    }
                    // (The `CHOICE` arm stood here and is gone: every choice row in this list -- the
                    // chapter's nine axes and the element form's -- is built by `buildRowControls` at the
                    // foot of this branch, which is the one builder that knows the composition. Two arms
                    // meant two `ChoiceField`s per row: the second replaced the first in the view and left
                    // the first hidden in the screen's render list, which is a widget that exists, costs its
                    // layout height and answers nothing.)
                    case SWITCH -> {
                        // **Armature's switch, at its own width against the column's edge** -- see
                        // `ToolsLayout.switchSlot`. The state is the switch's position, so there is no word to
                        // resolve and no second place to read it from: the row already carries it (`buttonLabel`,
                        // built from the group's tree, the element's file or the chapter's own -- whichever the
                        // prefix names), and the handler sends the switch's own new state rather than inverting
                        // a tree that a press may not have reached yet.
                        ArmatureSwitch toggle = new ArmatureSwitch(0, 0,
                                ToolsLayout.ON.equals(row.buttonLabel()));
                        toggle.onToggle(() -> pressChapterToggle(row.key(), toggle.selected()));
                        book.bookState.toolsView.put(row.key(), toggle, slot -> ToolsLayout.switchSlot(slot,
                                InspectLayout.Mode.STACKED));
                        book.addRenderableWidget(toggle);
                    }
                    case HEADING -> {
                        ArmatureButton button = book.control(0, 0, 0, 0, Component.literal(""),
                                () -> foldQuestSection(row.key()));
                        button.flat(true);
                        book.bookState.toolsView.put(row.key(), button);
                    }
                    default -> {
                        // VALUE rows are drawn by the panel and hold no widget -- the chapter's quest rows
                        // among them, whose only press is the reorder drag below, read from the same
                        // rectangles the drawing used. The kinds that hold a numeric field or a chip are
                        // the appearance rows' business, not this tab's.
                    }
                }
            }

            if (!book.bookState.chapterAppearanceRows.isEmpty()) {
                buildAppearanceWidgets(book.bookState.chapterAppearanceRows);
            }
            // **The element form's numeric rows, which the chapter's own list carries.** They come from the
            // same `ElementPanelLayout` the element panel draws, so they need the same builder against this
            // tab's list and this tab's composition -- stacked, as `chapterRows` was built. Until this call
            // they had no widget at all here: the appearance builder below only ever sees the appearance rows.
            buildRowControls(book.bookState.toolsView, book.bookState.chapterRows, true);
            book.bookState.toolsView.apply(book.bookState.chapterLayout, book.bookState.toolsFrame.list().width());
            return;
        }

        // The Book section first: the pack's own name and icon, which are the book's rather than any
        // theme's -- see `ToolsLayout.bookRows`. Its own fold, so an operator theming the book can put
        // the pack's fields away.
        List<ToolsLayout.Action> bookRows = new ArrayList<>(
                ToolsLayout.bookRows(toolsBookOpen, book.bookInput.bookLodValue(), book.bookInput.bookStarterValue()));
        bookRows.addAll(ToolsLayout.rows(ClientAppearance.LOOK.motion(), DevMode.snap(),
                DevMode.progress(), toolsColoursOpen, paletteOptions(), toolsPaletteOpen,
                book.editedBackground(), toolsCanvasOpen, canvasCopyLabel()));
        book.bookState.toolsRows = List.copyOf(bookRows);
        book.bookState.toolsLayout = ToolsLayout.build(book.bookState.toolsRows,
                book.bookState.toolsFrame.list().width(), Measure.monospace(6, 9));

        book.bookState.toolsView.clear();
        // Wholly-visible rows only: the panel's widgets are drawn by the book's widget pass, which is
        // clipped to the book -- not to this list -- so a row half scrolled out of the list would be
        // painted over the sample above it. That is a report, and this is the flag for it.
        book.bookState.toolsView.whole(true);
        book.bookState.toolsView.viewport().bounds(book.bookState.toolsFrame.list().x(),
                book.bookState.toolsFrame.list().y(),
                book.bookState.toolsFrame.list().width(), book.bookState.toolsFrame.list().height());

        for (ToolsLayout.Action row : book.bookState.toolsRows) {
            if (row.hasButton()) {
                // **The toolkit's own switch, which is what a state row is for.** Not an On/Off button: the
                // state belongs in the widget's own position rather than in a word the reader has to find, and
                // a word built from the wrong field is a state that can be read wrong for ever -- which is
                // exactly what the element panel's flag rows did. See `ToolsLayout.switchSlot` for the size.
                ArmatureSwitch toggle = new ArmatureSwitch(0, 0, ToolsLayout.ON.equals(row.buttonLabel()));
                // And the handler reads the switch's own new state, which is the order `ArmatureSwitch`
                // documents: the flip happens first, so a handler that asks reads what the author just chose
                // rather than a tree the press has not reached yet.
                toggle.onToggle(() -> pressToolsSwitch(row.key(), toggle.selected()));
                book.bookState.toolsView.put(row.key(), toggle, slot -> ToolsLayout.switchSlot(slot,
                        InspectLayout.Mode.SIDE_BY_SIDE));
                book.addRenderableWidget(toggle);
            }
        }
        buildAppearanceWidgets(book.bookState.toolsRows);

        // Revert and Save are the book tab's alone: they write the player's own theme file, which is
        // not what a chapter is. The chapter's edits are the chapter file's, taken back with the
        // editor's own undo. And they are Advanced's, like the theme rows they act on: in Normal mode
        // the tab shows no theme at all, and a Save theme under no theme was a button about nothing.
        if (dev.ellipog.tenet.client.dev.Advanced.on()) {
            book.control(ToolsLayout.revert(book.bookState.toolsFrame.actions()),
                    Component.translatable("tenet.dev.reset"), this::revertSelected);
            book.control(ToolsLayout.save(book.bookState.toolsFrame.actions()),
                    Component.translatable("tenet.dev.save"), this::saveTheme);
        }

        book.bookState.toolsView.apply(book.bookState.toolsLayout, book.bookState.toolsFrame.list().width());
    }

    /**
     * The appearance widgets both tabs build: the section folds, the palette and colour rows, the
     * channel steppers and the hex field.
     *
     * <p>One builder for the two tabs, against whichever layout is showing -- the rows are placed by
     * key, so the same control lands on the book tab's list or on the chapter tab's combined one
     * without knowing which. The radius and canvas steppers keep no widgets at all, and that is the
     * design rather than an omission: their arrows are drawn by the panel from `ToolsLayout.stepper`
     * and pressed through `stepperStepAt`, which reads the same rectangles; a widget over them would
     * swallow every press. Building the arrows as widgets was the first version, at the slot's own
     * coordinates -- which are the *list's*, not the screen's -- so they were placed outside the panel
     * and the row read as a number with nothing to press.
     */
    private void buildAppearanceWidgets(List<ToolsLayout.Action> rows) {
        for (ToolsLayout.Action row : rows) {
            if (row.isControl()) {
                if (ToolsLayout.BOOK_TITLE.equals(row.key())) {
                    // The book's name: a field in the row's right-hand side, its label in the room
                    // `valueField` keeps. The value is the pending edit first, then the tree's own.
                    ArmatureTextField field = new ArmatureTextField(0, 0, 0, 0, book.bookInput.bookTitleValue());
                    field.onSubmit(book.bookInput::commitBookTitle);
                    // Empty means the default name, which the header is showing: the field says so
                    // rather than reading as a book with no name.
                    field.placeholder(book.getTitle().getString());
                    field.colours(ArmatureTheme.title(), ArmatureTheme.recessed(),
                            ArmatureTheme.panelEdge());
                    book.bookState.toolsView.put(row.key(), field,
                            slot -> ToolsLayout.valueField(slot, ToolsLayout.LABEL_ROOM));
                    book.addRenderableWidget(field);
                    continue;
                }
                if (ToolsLayout.BOOK_ICON.equals(row.key())) {
                    // The book's icon: the picker's button in the field's place, beside its own label,
                    // showing the item it names or the "None" word.
                    String id = ClientQuestCache.bookIconId();
                    ArmatureButton pick = book.control(0, 0, 0, 0,
                            Component.literal(id.isEmpty()
                                    ? Labels.of("tenet.dev.tools.book_icon_none") : id),
                            this::openBookItemPicker);
                    pick.ink(ArmatureButton.Ink.BODY).icon(ClientQuestCache.bookIcon()).alignLeft(true);
                    book.bookState.toolsView.put(row.key(), pick,
                            slot -> ToolsLayout.valueField(slot, ToolsLayout.LABEL_ROOM));
                    continue;
                }
                if (ToolsLayout.CANVAS_TEXTURE.equals(row.key())) {
                    // The canvas image's file: the id's field between the picture and the browse button,
                    // placed by the row's own `textureField` -- the same shape as the book's name, with
                    // the two boxes the row carries beside it. The value is canonical in the tree as well
                    // -- `CanvasBackground.Image` canonicalises on construction -- so the field shows the
                    // one spelling the renderer resolves.
                    ArmatureTextField field = new ArmatureTextField(0, 0, 0, 0,
                            book.editedBackground().image().texture());
                    field.onSubmit(this::commitCanvasTexture);
                    field.colours(ArmatureTheme.title(), ArmatureTheme.recessed(),
                            ArmatureTheme.panelEdge());
                    book.bookState.toolsView.put(row.key(), field, slot -> ToolsLayout.textureField(slot));
                    book.addRenderableWidget(field);
                    continue;
                }
                ArmatureButton button = book.control(0, 0, 0, 0,
                        Component.literal(Labels.of(row.label())),
                        () -> pressToolsRow(row.key()));
                button.alignLeft(true).flat(true);
                if (ToolsLayout.CANVAS_COPY.equals(row.key())) {
                    // Kept because its label is changed on it while the copy is armed -- see
                    // `pressCanvasCopy`, which follows `disarmDisband`'s reason for doing it in place.
                    book.bookState.canvasCopyButton = button;
                }
                // A palette row stays flat even when it is the current one: its selection box is
                // drawn by the panel under its swatches, because a selected widget draws its opaque
                // box in the later widget pass, over whatever the draw pass drew -- which is how the
                // current palette lost its preview in every theme.
                book.bookState.toolsView.put(row.key(), button);
            }
            else if (ToolsLayout.folds(row.key())) {
                // A section's heading is the only heading that is pressable: it folds. The predicate is
                // the layout's own, not a list written here, because the two were hand-written once and
                // drifted: the Canvas and Quest Book headings were drawn with a fold marker and given no
                // widget, so no press could fold them. A group's name inside the colours is drawn and
                // does nothing, because a row that selects nothing is a row that lies.
                ArmatureButton button = book.control(0, 0, 0, 0, Component.literal(""), () -> fold(row.key()));
                button.flat(true);
                book.bookState.toolsView.put(row.key(), button);
            }
        }

        // The row controls: a field per numeric row, a pair for the compact lines, a chooser per
        // choice -- and nothing for a chip, which the panel draws and the screen hit-tests its press for.
        buildRowControls(book.bookState.toolsView, rows, false);
    }

    /** Opens the shared menu under a rectangle: the pill's menu, the drawer's and a chooser's, one path. */
    private void openMenuAt(List<BookMenu.MenuItem> rows, int x, int y) {
        book.openMenu(rows);
        book.bookState.menuX = x;
        book.bookState.menuY = y;
    }

    /**
     * One widget per row that carries one: a field, a pair of them, or a chooser.
     *
     * <h2>Why the rows are widgets now</h2>
     *
     * <p>The old panel drew its own arrows and hit-tested them by hand, because a widget over a drawn
     * arrow swallowed the press. The redesign removes the arrows: a numeric row <i>is</i> a field, and the
     * widget owns its box, its drag, its typing and its caret -- so there is nothing left for a hand-rolled
     * hit test to disagree with. A chip is the exception: it has no keyboard and no drag beyond its own
     * press, so the panel draws it and the screen tests its one rectangle.
     *
     * <p>The chapter tab's rows come through the same builder as the book's, against whichever list the
     * tab put up, so the two cannot drift about which row holds which control.
     *
     * <h2>Why the view and the composition are arguments</h2>
     *
     * <p>Because there is more than one list now, and a widget registered in the wrong one is a widget that
     * is never placed: {@code ScrollView.put} hides what the last {@code apply} gave no slot to, and the
     * element panel's keys are in no layout the dock ever builds. The element form is <b>stacked</b> and the
     * appearance rows are not, so the shape a control is placed with is the composition's answer and not the
     * kind's -- see {@link ToolsLayout#chipOf}, which is the same distinction for the one control the panel
     * draws itself.
     *
     * @param view    the list this shape of row belongs to: the dock's, or the element panel's
     * @param rows    the rows that list was built from
     * @param stacked whether that list composed them stacked, which is where a row's control band is
     */
    private void buildRowControls(dev.ellipog.armature.client.ui.kit.ScrollView view,
                                  List<ToolsLayout.Action> rows, boolean stacked) {
        dev.ellipog.armature.client.ui.kit.ScrollView.Shape band = stacked
                ? dev.ellipog.armature.client.ui.inspect.InspectLayout::controlBand
                : dev.ellipog.armature.client.ui.kit.ScrollView.Shape.IDENTITY;
        for (ToolsLayout.Action row : rows) {
            switch (row.kind()) {
                case FIELD -> {
                    dev.ellipog.tenet.client.dev.ScrubField field = scrubField(row.key());
                    // Kept for the label half of the gesture. The list is cleared once per rebuild pass,
                    // in `buildColumns`, rather than here: two panels build in one pass, and a clear in
                    // this loop is one panel evicting the other's fields from it.
                    book.bookState.rowFields.add(field);
                    view.put(row.key(), field, band);
                    book.addRenderableWidget(field);
                }
                case PAIR -> {
                    // One widget for the row -- the scroll view places one widget per key -- with two
                    // controls inside it. See `ScrubPairField` for why the halves are not two widgets.
                    dev.ellipog.tenet.client.dev.ScrubPairField pair =
                            new dev.ellipog.tenet.client.dev.ScrubPairField(rowWidget(row),
                                    rowWidget(row.right()));
                    view.put(row.key(), pair, band);
                    book.addRenderableWidget(pair);
                }
                case CHOICE -> {
                    dev.ellipog.tenet.client.dev.ChoiceField choice = choiceFor(row);
                    view.put(row.key(), choice, stacked
                            ? band
                            : slot -> ToolsLayout.valueField(slot, ToolsLayout.LABEL_ROOM));
                    book.addRenderableWidget(choice);
                }
                default -> {
                    // A chip is drawn by the panel; a switch and a row are built by the caller's own
                    // loop; a heading folds. None of them is a field.
                }
            }
        }
        // The picker survives a rebuild -- a resize, a fold elsewhere -- by re-registering its fields;
        // a rebuild that changes what the picker is *about* closes it first (see `setTab`, `fold`).
        book.buildPopoverWidgets();
    }

    /**
     * One element field as a scrubbable number: its value, its range, and what a drag writes.
     *
     * <h2>Why this is the same control the appearance rows use</h2>
     *
     * <p>Because a number is a number: an author who has learnt that a value is dragged in the Book tab should
     * not meet a text box for the same kind of thing on the canvas. The value is read from the <b>drafted</b>
     * element, so a drag continues from what the canvas is showing rather than from what the server last sent
     * -- and the preview drafts as it goes, which is what makes the picture follow the drag.
     */
    private dev.ellipog.tenet.client.dev.ScrubField elementScrub(String element, String field) {
        CanvasElement now = book.elementNow(element);
        // The range is the **arm's** own, not the field name's: `width` is a line's thickness (1..16), a box's
        // reach and a picture's edge, so a control bounded by the wrong arm writes a number the codec clamps
        // away. See `ElementPanelLayout.rangeOf`.
        dev.ellipog.tenet.client.dev.ElementPanelLayout.Range range =
                dev.ellipog.tenet.client.dev.ElementPanelLayout.rangeOf(
                        now == null ? "" : now.type(), field);
        // **The value the field really holds**, not the number in the file: a field the file does not carry is
        // the codec's default, and the canvas draws that default -- so a box starting at 0 for an absent alpha
        // showed a transparent picture that was drawn opaque, and the first pixel of a drag made it so. See
        // `ElementPanelLayout.effectiveValueOf`.
        double start = now == null
                ? 0
                : dev.ellipog.tenet.client.dev.ElementPanelLayout.numberOf(CanvasElement.asJson(now), field);
        if (range == null) {
            // A field with no declared range is not one this form offers as a number -- which can only happen
            // if a row and the table have drifted. A field that moves by nothing and writes nothing is the
            // honest answer: it cannot write a value the codec would clamp away.
            return new dev.ellipog.tenet.client.dev.ScrubField(start, start, start, 1, "");
        }
        return new dev.ellipog.tenet.client.dev.ScrubField(start, range.min(), range.max(), range.step(),
                range.unit())
                .onPreview(next -> draftElementField(element, field, elementNumber(field, next)))
                .onCommit(next -> {
                    JsonElement written = elementNumber(field, next);
                    draftElementField(element, field, written);
                    book.send(new EditorOp.SetElement(element, field, written));
                    book.rebuildWidgets();
                });
    }

    /**
     * One link field as a scrubbable number: its value, its range, and what a drag writes.
     *
     * <p>The value is read from the <b>drafted</b> link, so a drag continues from what the canvas is
     * showing rather than from what the server last sent -- and the preview drafts as it goes, which
     * is what makes the marker follow the drag. The range is the link form's own table, which has no
     * arm dimension to get wrong: a link is one shape with three numbers.
     */
    private dev.ellipog.tenet.client.dev.ScrubField linkScrub(String link, String field) {
        QuestLink now = book.bookPins.linkNow(link);
        dev.ellipog.tenet.client.dev.LinkPanelLayout.Range range =
                dev.ellipog.tenet.client.dev.LinkPanelLayout.rangeOf(field);
        double start = now == null
                ? 0
                : dev.ellipog.tenet.client.dev.LinkPanelLayout.numberOf(QuestLink.asJson(now),
                        field);
        if (range == null) {
            // A field with no declared range is not one this form offers as a number -- which can only happen
            // if a row and the table have drifted. A field that moves by nothing and writes nothing is the
            // honest answer: it cannot write a value the codec would clamp away.
            return new dev.ellipog.tenet.client.dev.ScrubField(start, start, start, 1, "");
        }
        return new dev.ellipog.tenet.client.dev.ScrubField(start, range.min(), range.max(), range.step(),
                range.unit())
                .onPreview(next -> book.bookPins.draftLinkField(link, field,
                        new JsonPrimitive((long) Math.round(next))))
                .onCommit(next -> {
                    JsonElement written = new JsonPrimitive((long) Math.round(next));
                    book.bookPins.draftLinkField(link, field, written);
                    book.send(new EditorOp.SetLink(link, field, written));
                    book.rebuildWidgets();
                });
    }

    /**
     * A number a drag produced, in the shape the field's own file holds.
     *
     * <p>An integer field gets an integer and a decimal one a decimal, because the codec reads them that way:
     * writing {@code 1.0} where the file says a whole number is a value the loader would refuse, and the drag
     * produces a double whatever the field is.
     */
    private static JsonElement elementNumber(String field, double value) {
        boolean decimal = "scale".equals(field);
        return decimal ? new JsonPrimitive(value) : new JsonPrimitive((long) Math.round(value));
    }

    /** One half of a pair, or a whole row: a numeric field or a chooser, by the action's kind. */
    private net.minecraft.client.gui.components.AbstractWidget rowWidget(ToolsLayout.Action action) {
        if (action.kind() == ToolsLayout.Action.Kind.CHOICE) {
            return choiceFor(action);
        }
        dev.ellipog.tenet.client.dev.ScrubField field = scrubField(action.key());
        book.bookState.rowFields.add(field);
        return field;
    }

    /**
     * One chooser: its word, and the press that opens its menu under the field itself.
     *
     * <h2>Why the field is handed to the menu rather than the row's slot</h2>
     *
     * <p>Because the field <b>is</b> the rectangle, in every composition and in both halves of a pair: the
     * scroll view places it, {@code ScrubPairField} splits its children from its own bounds at the top of
     * every press, and a second derivation from the layout would be a second answer to "where is this
     * field" -- with the pair's second half the case that proves it, since the layout holds no slot for a
     * half's key at all. See {@link #openChoiceMenu}, which placed its menu at the last point a menu was
     * opened at before this.
     */
    private dev.ellipog.tenet.client.dev.ChoiceField choiceFor(ToolsLayout.Action row) {
        dev.ellipog.tenet.client.dev.ChoiceField choice =
                new dev.ellipog.tenet.client.dev.ChoiceField(choiceLabel(row));
        choice.onPress(() -> openChoiceMenu(row, choice));
        return choice;
    }

    /**
     * The numeric field a row's key stands for: its value, its range, its unit, and what a drag does.
     *
     * <p>One switch, so a row and its behaviour are written once. The units are the row's: a spacing is
     * pixels, an opacity is a percentage of the ink's alpha, a density is a count. The previews stream into
     * the pending chapter patch, so the canvas follows the drag; on the book tab they do nothing, because
     * the player's own file has no draft path -- the release still writes once, which is the contract that
     * matters.
     */
    private dev.ellipog.tenet.client.dev.ScrubField scrubField(String key) {
        String[] element = ChapterPanelLayout.elementFieldOf(key);
        if (element != null) {
            return elementScrub(element[0], element[1]);
        }
        String[] link = ChapterPanelLayout.linkFieldOf(key);
        if (link != null) {
            return linkScrub(link[0], link[1]);
        }
        CanvasBackground background = book.editedBackground();
        if (ToolsLayout.RADIUS.equals(key)) {
            return new dev.ellipog.tenet.client.dev.ScrubField(editedRadius(), Look.MIN_RADIUS,
                    Look.MAX_RADIUS, 1, " px")
                    .onPreview(value -> previewRadius((int) Math.round(value)))
                    .onCommit(value -> setRadius((int) Math.round(value)));
        }
        if (ToolsLayout.CANVAS_SPACING.equals(key)) {
            return new dev.ellipog.tenet.client.dev.ScrubField(background.spacing(),
                    CanvasBackground.MIN_SPACING, CanvasBackground.MAX_SPACING, 1, " px")
                    .onPreview(value -> previewSpacing((int) Math.round(value)))
                    .onCommit(value -> setCanvasSpacing((int) Math.round(value)));
        }
        if (ToolsLayout.CANVAS_SIZE.equals(key)) {
            return new dev.ellipog.tenet.client.dev.ScrubField(background.tuning().size(),
                    CanvasBackground.Tuning.MIN_SIZE, CanvasBackground.Tuning.MAX_SIZE, 1, " px")
                    .onPreview(value -> previewSize((int) Math.round(value)))
                    .onCommit(value -> setCanvasSize((int) Math.round(value)));
        }
        if (ToolsLayout.CANVAS_DENSITY.equals(key)) {
            return new dev.ellipog.tenet.client.dev.ScrubField(background.tuning().density(),
                    CanvasBackground.Tuning.MIN_DENSITY, CanvasBackground.Tuning.MAX_DENSITY, 1, "")
                    .onPreview(value -> previewDensity((int) Math.round(value)))
                    .onCommit(value -> setCanvasDensity((int) Math.round(value)));
        }
        if (ToolsLayout.CANVAS_TILE.equals(key)) {
            return new dev.ellipog.tenet.client.dev.ScrubField(background.image().tileSize(),
                    CanvasBackground.Image.MIN_TILE, CanvasBackground.Image.MAX_TILE, 1, " px")
                    .onPreview(value -> previewTile((int) Math.round(value)))
                    .onCommit(value -> setCanvasTile((int) Math.round(value)));
        }
        if (ToolsLayout.CANVAS_OPACITY.equals(key)) {
            int percent = Math.round(Colour.alpha(editedTheme().colour("canvasPattern")) / 255F * 100F);
            return new dev.ellipog.tenet.client.dev.ScrubField(percent, 0, 100, 1, "%")
                    .onPreview(value -> previewOpacity((int) Math.round(value)))
                    .onCommit(value -> setCanvasOpacity((int) Math.round(value)));
        }
        // A row key this switch does not know is a layout bug, not a runtime one: an empty field keeps the
        // panel drawing rather than crashing a screen, and the row's label still says what it is.
        return new dev.ellipog.tenet.client.dev.ScrubField(0, 0, 0, 1, "");
    }

    /** The word a chooser row is showing: the chapter's own for its axes, the background's otherwise. */
    private String choiceLabel(ToolsLayout.Action row) {
        String key = row.key();
        String[] element = ChapterPanelLayout.elementFieldOf(key);
        if (element != null) {
            // The element's own value, through the same reader the chip and the field use, named the way a menu
            // reads: the file's word with its underscores opened out. **The effective value**, because an
            // absent choice is the codec's default -- a caption's `hAlign` the file never set is `middle`, and
            // a box showing nothing for it offered no way to tell an unset field from a broken one.
            CanvasElement now = book.elementNow(element[0]);
            String held = now == null ? ""
                    : dev.ellipog.tenet.client.dev.ElementPanelLayout.effectiveValueOf(
                            CanvasElement.asJson(now), element[1]);
            return dev.ellipog.tenet.client.dev.ElementPanelLayout.valueName(held);
        }
        String[] link = ChapterPanelLayout.linkFieldOf(key);
        if (link != null) {
            // The link's own value, through the same reader its scrub rows use, named the way a menu
            // reads. **The effective value**, because an absent shape is the codec's default -- a marker
            // whose file never set one draws `rounded`, and a box showing nothing for it offered no way
            // to tell an unset field from a broken one.
            QuestLink now = book.bookPins.linkNow(link[0]);
            String held = now == null ? ""
                    : dev.ellipog.tenet.client.dev.LinkPanelLayout.effectiveValueOf(
                            QuestLink.asJson(now), link[1]);
            return dev.ellipog.tenet.client.dev.LinkPanelLayout.valueName(held);
        }
        if (ChapterPanelLayout.isChoiceKey(key)) {
            // The chapter's vocabulary is the *file's*: the row carries the raw value (or nothing, for the
            // unset state) and the class that reads the chapter names it -- including the fallback the
            // unset state defers to, which is a fact about the axis rather than about this screen.
            return ChapterPanelLayout.choiceLabel(ChapterPanelLayout.choiceForKey(key), row.value());
        }
        CanvasBackground background = book.editedBackground();
        if (ToolsLayout.CANVAS_PATTERN.equals(key)) {
            return ToolsPanel.patternLabel(background.kind());
        }
        if (ToolsLayout.CANVAS_DIRECTION.equals(key)) {
            return background.tuning().backslash() ? "\\" : "/";
        }
        if (ToolsLayout.CANVAS_SPACE.equals(key)) {
            return ToolsPanel.spaceLabel(background.space());
        }
        if (ToolsLayout.CANVAS_FIT.equals(key)) {
            return ToolsPanel.fitLabel(background.image().fit());
        }
        return Labels.of(key);
    }

    /**
     * The chooser's menu: every value the row can take, anchored under the field it was opened from.
     *
     * <h2>The field's own rectangle, and why not the layout's</h2>
     *
     * <p>Because the widget <i>is</i> the field: the scroll view placed it, {@code ScrubPairField} splits its
     * two children from its own bounds at the top of every press, and the band a stacked row puts it in came
     * from the same call the drawing used. Every other derivation is a second answer to "where is this
     * field", and each one has been wrong somewhere: the layout holds no slot at all for a pair's second
     * half, and it holds none for an element row in the element panel either -- whose rows live in another
     * view entirely. The <b>element branch used to return before any of this arithmetic ran</b> and pass
     * {@code menuX}/{@code menuY}, which are the canvas's right-click point: pressing "When Pressed" or
     * "Arrow Head" opened its menu wherever the author had last right-clicked, or in the screen's corner if
     * they never had.
     *
     * <p>Right-aligned to the field and one pixel under it, which is where this menu has always opened for
     * the rows that reached the arithmetic -- {@code MenuPlacement} clamps what that means at a screen edge.
     */
    private void openChoiceMenu(ToolsLayout.Action row, dev.ellipog.tenet.client.dev.ChoiceField field) {
        List<BookMenu.MenuItem> items = new ArrayList<>();
        String[] element = ChapterPanelLayout.elementFieldOf(row.key());
        if (element != null) {
            // Every value the field accepts, read from the model's own enum -- so the menu and the validator
            // cannot disagree, and a value added to one is offered by the other without being written twice.
            for (String value : dev.ellipog.tenet.client.dev.ElementPanelLayout.valuesOf(element[1])) {
                items.add(BookMenu.MenuItem.of(dev.ellipog.tenet.client.dev.ElementPanelLayout.valueName(value),
                        () -> commitElementField(element[0], element[1], value)));
            }
        }
        String[] link = ChapterPanelLayout.linkFieldOf(row.key());
        if (link != null) {
            // Every shape the file accepts, read from the model's own enum -- so the menu and the validator
            // cannot disagree, and a shape added to one is offered by the other without being written twice.
            for (String value : dev.ellipog.tenet.client.dev.LinkPanelLayout.valuesOf(link[1])) {
                items.add(BookMenu.MenuItem.of(dev.ellipog.tenet.client.dev.LinkPanelLayout.valueName(value),
                        () -> commitLinkField(link[0], link[1], value)));
            }
        }
        else if (ChapterPanelLayout.isChoiceKey(row.key())) {
            // The chapter's nine axes: one entry per value the file accepts, plus the unset state -- the
            // list `choiceValues` already builds, in its own order, named by the same labeller the field
            // shows. The arrows this replaces could only ever step to the neighbours of the current value.
            ChapterPanelLayout.Choice choice = ChapterPanelLayout.choiceForKey(row.key());
            for (String value : ChapterPanelLayout.choiceValues(choice)) {
                items.add(BookMenu.MenuItem.of(ChapterPanelLayout.choiceLabel(choice, value),
                        () -> setChapterChoice(choice, value)));
            }
        }
        else if (ToolsLayout.CANVAS_PATTERN.equals(row.key())) {
            for (CanvasBackground.Kind kind : CanvasBackground.Kind.values()) {
                items.add(BookMenu.MenuItem.of(ToolsPanel.patternLabel(kind), () -> setCanvasPattern(kind)));
            }
        }
        else if (ToolsLayout.CANVAS_DIRECTION.equals(row.key())) {
            items.add(BookMenu.MenuItem.of("/", () -> setCanvasDirection(false)));
            items.add(BookMenu.MenuItem.of("\\", () -> setCanvasDirection(true)));
        }
        else if (ToolsLayout.CANVAS_SPACE.equals(row.key())) {
            for (CanvasBackground.Space space : CanvasBackground.Space.values()) {
                items.add(BookMenu.MenuItem.of(ToolsPanel.spaceLabel(space), () -> setCanvasSpace(space)));
            }
        }
        else if (ToolsLayout.CANVAS_FIT.equals(row.key())) {
            for (CanvasBackground.Fit fit : CanvasBackground.Fit.values()) {
                items.add(BookMenu.MenuItem.of(ToolsPanel.fitLabel(fit), () -> setCanvasFit(fit)));
            }
        }
        if (items.isEmpty() || field == null) {
            // A row with no values to offer is a row that lies if it opens an empty menu, and a field that
            // was never placed has no rectangle to hang one under.
            return;
        }
        openMenuAt(items, field.getX() + Math.max(0, field.getWidth() - BookMenu.MENU_WIDTH),
                field.getY() + field.getHeight() + 2);
    }

    // --- the absolute setters: the step handlers' arithmetic, with the value given rather than a delta.

    private void setRadius(int value) {
        int clamped = net.minecraft.util.Mth.clamp(value, Look.MIN_RADIUS, Look.MAX_RADIUS);
        if (book.bookState.themeTargetsChapter) {
            writeThemeRadius(clamped);
        }
        else {
            ClientAppearance.LOOK.setRadius(clamped);
        }
        book.status("Border radius " + clamped, false);
        book.rebuildWidgets();
    }

    private void setCanvasPattern(CanvasBackground.Kind kind) {
        CanvasBackground current = book.editedBackground();
        writeThemeBackground(new CanvasBackground(kind, current.space(), current.spacing(),
                current.tuning(), current.image()));
        book.status("Canvas pattern: " + ToolsPanel.patternLabel(kind), false);
        book.rebuildWidgets();
    }

    private void setCanvasSpacing(int value) {
        CanvasBackground current = book.editedBackground();
        int spacing = net.minecraft.util.Mth.clamp(value, CanvasBackground.MIN_SPACING,
                CanvasBackground.MAX_SPACING);
        writeThemeBackground(new CanvasBackground(current.kind(), current.space(), spacing,
                current.tuning(), current.image()));
        book.status("Canvas spacing " + spacing, false);
        book.rebuildWidgets();
    }

    private void setCanvasSize(int value) {
        CanvasBackground current = book.editedBackground();
        CanvasBackground.Tuning tuning = current.tuning();
        int size = net.minecraft.util.Mth.clamp(value, CanvasBackground.Tuning.MIN_SIZE,
                CanvasBackground.Tuning.MAX_SIZE);
        writeThemeBackground(new CanvasBackground(current.kind(), current.space(), current.spacing(),
                new CanvasBackground.Tuning(size, tuning.density(), tuning.backslash()),
                current.image()));
        book.status("Canvas mark size " + size, false);
        book.rebuildWidgets();
    }

    private void setCanvasDensity(int value) {
        CanvasBackground current = book.editedBackground();
        CanvasBackground.Tuning tuning = current.tuning();
        int density = net.minecraft.util.Mth.clamp(value, CanvasBackground.Tuning.MIN_DENSITY,
                CanvasBackground.Tuning.MAX_DENSITY);
        writeThemeBackground(new CanvasBackground(current.kind(), current.space(), current.spacing(),
                new CanvasBackground.Tuning(tuning.size(), density, tuning.backslash()),
                current.image()));
        book.status("Canvas speckle density " + density, false);
        book.rebuildWidgets();
    }

    private void setCanvasDirection(boolean backslash) {
        CanvasBackground current = book.editedBackground();
        CanvasBackground.Tuning tuning = current.tuning();
        writeThemeBackground(new CanvasBackground(current.kind(), current.space(), current.spacing(),
                new CanvasBackground.Tuning(tuning.size(), tuning.density(), backslash),
                current.image()));
        book.status("Canvas hatch: " + (backslash ? "\\" : "/"), false);
        book.rebuildWidgets();
    }

    private void setCanvasSpace(CanvasBackground.Space space) {
        CanvasBackground current = book.editedBackground();
        writeThemeBackground(new CanvasBackground(current.kind(), space, current.spacing(),
                current.tuning(), current.image()));
        book.status("Canvas space: " + ToolsPanel.spaceLabel(space), false);
        book.rebuildWidgets();
    }

    private void setCanvasFit(CanvasBackground.Fit fit) {
        CanvasBackground current = book.editedBackground();
        CanvasBackground.Image image = current.image();
        writeThemeBackground(new CanvasBackground(current.kind(), current.space(), current.spacing(),
                current.tuning(),
                new CanvasBackground.Image(image.texture(), fit, image.tileSize())));
        book.status("Canvas image fit: " + ToolsPanel.fitLabel(fit), false);
        book.rebuildWidgets();
    }

    private void setCanvasTile(int value) {
        CanvasBackground current = book.editedBackground();
        CanvasBackground.Image image = current.image();
        // The image's own constructor clamps the tile, so the record in force is the clamped one.
        CanvasBackground.Image next = new CanvasBackground.Image(image.texture(), image.fit(), value);
        writeThemeBackground(new CanvasBackground(current.kind(), current.space(), current.spacing(),
                current.tuning(), next));
        book.status("Canvas tile " + next.tileSize() + " px", false);
        book.rebuildWidgets();
    }

    private void setCanvasOpacity(int percent) {
        int clamped = net.minecraft.util.Mth.clamp(percent, 0, 100);
        int ink = editedTheme().colour("canvasPattern");
        writeThemeToken("canvasPattern", (ink & 0x00FFFFFF) | ((clamped * 255 / 100) << 24));
        book.status("Canvas pattern ink " + clamped + "%", false);
        book.rebuildWidgets();
    }

    private void previewRadius(int radius) {
        JsonObject patch = book.previewBase();
        if (patch == null) {
            return;
        }
        patch.addProperty("cornerRadius", radius);
        book.previewChapterPatch(patch);
    }

    private void previewBackground(CanvasBackground background) {
        JsonObject patch = book.previewBase();
        if (patch == null) {
            return;
        }
        patch.add("canvasBackground", background.toJson());
        book.previewChapterPatch(patch);
    }

    private void previewSpacing(int spacing) {
        CanvasBackground current = book.editedBackground();
        previewBackground(new CanvasBackground(current.kind(), current.space(),
                net.minecraft.util.Mth.clamp(spacing, CanvasBackground.MIN_SPACING,
                        CanvasBackground.MAX_SPACING),
                current.tuning(), current.image()));
    }

    private void previewSize(int size) {
        CanvasBackground current = book.editedBackground();
        CanvasBackground.Tuning tuning = current.tuning();
        previewBackground(new CanvasBackground(current.kind(), current.space(), current.spacing(),
                new CanvasBackground.Tuning(net.minecraft.util.Mth.clamp(size,
                        CanvasBackground.Tuning.MIN_SIZE, CanvasBackground.Tuning.MAX_SIZE),
                        tuning.density(), tuning.backslash()),
                current.image()));
    }

    private void previewDensity(int density) {
        CanvasBackground current = book.editedBackground();
        CanvasBackground.Tuning tuning = current.tuning();
        previewBackground(new CanvasBackground(current.kind(), current.space(), current.spacing(),
                new CanvasBackground.Tuning(tuning.size(),
                        net.minecraft.util.Mth.clamp(density, CanvasBackground.Tuning.MIN_DENSITY,
                                CanvasBackground.Tuning.MAX_DENSITY),
                        tuning.backslash()),
                current.image()));
    }

    private void previewTile(int tile) {
        CanvasBackground current = book.editedBackground();
        CanvasBackground.Image image = current.image();
        previewBackground(new CanvasBackground(current.kind(), current.space(), current.spacing(),
                current.tuning(),
                new CanvasBackground.Image(image.texture(), image.fit(), tile)));
    }

    private void previewOpacity(int percent) {
        JsonObject patch = book.previewBase();
        if (patch == null) {
            return;
        }
        int ink = editedTheme().colour("canvasPattern");
        JsonObject colours = patch.has("colours") && patch.get("colours").isJsonObject()
                ? patch.getAsJsonObject("colours") : new JsonObject();
        colours.addProperty("canvasPattern", String.format("#%08X",
                (ink & 0x00FFFFFF)
                        | net.minecraft.util.Mth.clamp(percent, 0, 100) * 255 / 100 << 24));
        patch.add("colours", colours);
        book.previewChapterPatch(patch);
    }

    /** Switches the dock's tab. The other panel's state is not touched: see {@link QuestBookScreen#toolsTab}. */
    private void setTab(ToolsLayout.Tab tab) {
        if (book.bookState.toolsTab != tab) {
            book.bookState.toolsTab = tab;
            // Remembered, because "which tab was I reading" is a question that outlives the session: the
            // file is read once at client start and written here, and nothing else decides it.
            dev.ellipog.tenet.client.ClientWorking.rememberDrawerTab(
                    tab == ToolsLayout.Tab.CHAPTER
                            ? dev.ellipog.tenet.client.ClientWorking.CHAPTER_TAB
                            : dev.ellipog.tenet.client.ClientWorking.BOOK_TAB);
            // A picker belongs to the row it was opened from, and that row is about to be rebuilt away.
            // (This was called twice, with the same comment twice, which is what a copy and paste leaves
            // behind: the second call was dead -- the first had already closed it.)
            book.closeColourPopover();
            // The selection names a token in one target's palette; carrying it across would leave the
            // band showing one theme's value under the other tab's name.
            toolsSelected = null;
            book.rebuildWidgets();
        }
    }

    /**
     * One of the book tab's switches, set to the state the switch was moved to.
     *
     * <p>The wanted state rather than an inversion, for the reason {@code pressChapterToggle} gives: the switch
     * already flipped and the handler is told afterwards, so asking the preference again would be asking what
     * this press was about to change.
     */
    private void pressToolsSwitch(String key, boolean wanted) {
        if (key.equals(ToolsLayout.MOTION)) {
            ClientAppearance.LOOK.setMotion(wanted);
            book.status(wanted ? "Motion on" : "Motion off", false);
            book.rebuildWidgets();
        }
        else if (key.equals(ToolsLayout.SNAP)) {
            DevMode.setSnap(wanted);
            book.status(wanted ? "Snap on \u2014 Alt places freely" : "Snap off", false);
            book.rebuildWidgets();
        }
        else if (key.equals(ToolsLayout.PROGRESS)) {
            DevMode.setProgress(wanted);
            book.status(wanted ? "Chapter progress bars on" : "Chapter progress bars off", false);
            book.rebuildWidgets();
        }
        else if (key.equals(ToolsLayout.BOOK_LOD)) {
            // Explicit either way, like the chapter tab's toggles: a cleared draft is
            // indistinguishable from no draft, so writing null would show the old state
            // until the tree answers. The file saying `false` is the honest reading.
            book.bookInput.sendBookField("disableCanvasLod", new JsonPrimitive(wanted));
            book.status(wanted ? "Canvas LOD off \u2014 icons always drawn" : "Canvas LOD on", false);
            book.rebuildWidgets();
        }
        else if (key.equals(ToolsLayout.BOOK_STARTER)) {
            // Explicit either way, for the same reason as the LOD row above: the switch
            // shows the pending edit first, and null would read as the old state.
            book.bookInput.sendBookField("giveBookOnFirstJoin", new JsonPrimitive(wanted));
            book.status(wanted ? "First join grants a quest book" : "No book on first join", false);
            book.rebuildWidgets();
        }
    }

    // ------------------------------------------------------------------
    // The quest panel's edits
    // ------------------------------------------------------------------

    /** The same, for the subtitle. */
    static String subtitleOf(ClientQuestCache.Entry entry) {
        return BookScreenState.fieldDraft.text(entry.chapterId(), entry.id(), "subtitle", entry.subtitleText());
    }

    /** The subtitle with its legacy tokens still in it. See {@link QuestBookScreen#titleOfRaw}. */
    static String subtitleOfRaw(ClientQuestCache.Entry entry) {
        return BookScreenState.fieldDraft.text(entry.chapterId(), entry.id(), "subtitle", entry.subtitleTextRaw());
    }

    /**
     * Commits one field, as the text the player typed and the path it belongs to.
     *
     * <p>This is the whole of "per field, on commit": the field's submit brings the text here, the type
     * is re-asked of the tree as it stands now, and a refusal costs a status line and a reset -- the
     * field goes back to what the tree says, because a red line of text beside a field still holding the
     * bad value is two disagreements where one will do.
     *
     * <p>An <b>empty</b> commit removes the field rather than writing an empty string into it, which is
     * the decision {@code EditorOp.SetField} was given a null for. Required fields refuse on the server
     * with the validator's own message, which is the honest answer for clearing a title.
     */
    private void commitQuestField(String path, String text) {
        commitField(path, text, false);
    }

    /**
     * Commits one text field, as the op the surface sends: {@code SetField} for a quest, {@code
     * SetChapter} for the chapter.
     *
     * <p>One method, because the rules are the same on both files -- parse by the declared or inferred
     * kind, an empty field means <i>remove</i>, aliases are a comma list -- and only the target
     * differs. A second copy of those rules is a second place for them to drift.
     */
    private void commitField(String path, String text, boolean chapter) {
        if (path != null && path.startsWith(ChapterPanelLayout.GROUP_PREFIX)) {
            // A row from the Group section commits to the group's own file, whatever the panel's other
            // rows do -- the prefix is the whole of that distinction, so the two cannot be confused.
            commitGroupField(path.substring(ChapterPanelLayout.GROUP_PREFIX.length()), text);
            return;
        }
        if (path != null && path.startsWith(ChapterPanelLayout.ELEMENT_PREFIX)) {
            // And an element's row commits to the element it names, inside the chapter's own file. The prefix
            // is again the whole of the distinction, and it is tested before the `chapter` flag because an
            // element row *is* a chapter row -- it is the chapter's file, one member of it.
            String[] field = ChapterPanelLayout.elementFieldOf(path);
            if (field != null) {
                commitElementField(field[0], field[1], text);
            }
            return;
        }
        if (path != null && path.startsWith(ChapterPanelLayout.LINK_PREFIX)) {
            // And a link's row commits to the link it names, inside the chapter's own file, for the same
            // reason and tested in the same place: a link row *is* a chapter row too.
            String[] field = ChapterPanelLayout.linkFieldOf(path);
            if (field != null) {
                commitLinkField(field[0], field[1], text);
            }
            return;
        }
        if (!book.mayEditNow()) {
            return;
        }
        String target = chapter ? null : book.editTarget();
        if (!chapter && target == null) {
            return;
        }
        String typed = text == null ? "" : text.trim();
        if (!chapter && path.equals(QuestPanelLayout.DEPENDENCY_ADD)) {
            addDependency(typed);
            return;
        }
        JsonObject tree = chapter
                ? ClientChapterReplica.chapterTree(QuestBookScreen.effectiveChapter()) : book.replicaQuest();
        // The quest case goes through the draft, so a committed value shows at once; the chapter case
        // is the settings draft's territory and sends directly.
        java.util.function.Consumer<JsonElement> commit = chapter
                ? value -> book.sendChapterField(path, value)
                : value -> book.sendField(target, path, value);
        if (typed.isEmpty()) {
            // Empty means absent: the field is removed rather than written as nothing -- the same
            // decision the model's SetField was given a null for.
            commit.accept(null);
            return;
        }
        if (path.equals("aliases") || path.equals("tags") || path.endsWith(".tags")
                || (chapter && path.equals("dependsOn"))
                || (chapter && path.equals("completesWhen"))) {
            // One field for the list, because each entry is one word: commas between them, empties gone.
            // A chapter's two gate lists are a list of names like an alias list, so they take the same
            // treatment rather than three near-identical branches. Tags ride along because they are the
            // same shape -- one word per entry, on a quest, a chapter, a task or a reward -- and a tag
            // that breaks the pattern is the server's to report with the file and line, which is the
            // whole reason this can be a text row. A name that resolves to nothing is likewise the
            // server's to report.
            List<String> names = Arrays.stream(typed.split(","))
                    .map(String::trim).filter(name -> !name.isEmpty()).toList();
            commit.accept(QuestBookScreen.stringArray(names));
            return;
        }
        if (chapter && path.equals("minRequired")) {
            // A count of dependencies, so it is bounded by the list above it: "3 of 2" is not a rule, and
            // the validator refuses it -- the row should not be able to write one. The upper bound is the
            // depend list's size and the model's own cap, whichever is lower, and a value that is not a
            // number at all is refused here rather than written as a string the codec would reject.
            int count;
            try {
                count = Integer.parseInt(typed);
            }
            catch (NumberFormatException e) {
                book.status("a count of chapters has to be a number", true);
                book.rebuildWidgets();
                return;
            }
            int list = QuestPanelLayout.strings(tree, "dependsOn").size();
            int capped = Math.max(0, Math.min(Math.min(list,
                    dev.ellipog.tenet.quest.ChapterRules.MAX_COUNT), count));
            commit.accept(new JsonPrimitive((long) capped));
            return;
        }
        InspectField<?> field = QuestPanelLayout.fieldFor(tree, path);
        InspectField.Result<?> result = field.parse(typed);
        if (!result.ok()) {
            book.status(result.error(), true);
            book.rebuildWidgets();
            return;
        }
        commit.accept(jsonOf(result.value()));
    }

    /**
     * Commits one text field of one canvas element.
     *
     * <h2>Why the value's kind is asked of the chapter's tree</h2>
     *
     * <p>Because an element's fields are typed by what the file holds, and the panel's own reader already
     * answers that question: {@code QuestPanelLayout.fieldFor} resolves a declared field name, else the value
     * that is there — a whole number as whole, a decimal as one, a flag as a flag — else text. So the path is
     * spelled the way that reader walks it, {@code elements.<position>.<field>}, and the author gets the same
     * typing rules here as everywhere else in the panel. The position comes from the id, because an id is what
     * the row names and a position is a fact about the array that a drag could move under it.
     */
    private void commitElementField(String element, String field, String text) {
        if (!book.mayEditNow()) {
            return;
        }
        // From the drafted view rather than the replica, and that is what makes a second edit compose with
        // the first: the replica still holds what the server last sent, so typing `sealed` into a label's
        // caption and then its colour would build the second write on a tree without the first.
        CanvasElement now = book.elementNow(element);
        if (now == null) {
            return;
        }
        JsonObject tree = CanvasElement.asJson(now);
        String typed = text == null ? "" : text.trim();
        // An empty row means *absent*, which is the rule every other text row in this screen follows: a
        // required field refuses on the server with the validator's own message, and that is the honest
        // answer for clearing a picture's source or a label's words.
        if (typed.isEmpty()) {
            draftElementField(element, field, null);
            book.send(new EditorOp.SetElement(element, field, null));
            book.rebuildWidgets();
            return;
        }
        // A member of a nested object -- `image.texture`, `click.type`, `label.hAlign` -- is written as the
        // whole object, because the two arms of an image source are exclusive and the object may not exist
        // yet: writing `elements.0.image.texture` needs an `image` to write into, and a half-written file has
        // none. Every other field is the element's own and goes straight in.
        int dot = field.indexOf('.');
        if (dot > 0) {
            String container = field.substring(0, dot);
            JsonObject holder = QuestPanelLayout.get(tree, container) instanceof JsonObject object
                    ? object.deepCopy() : new JsonObject();
            holder.addProperty(field.substring(dot + 1), typed);
            draftElementField(element, container, holder);
            book.send(new EditorOp.SetElement(element, container, holder));
            book.rebuildWidgets();
            return;
        }
        // Typed against the element's own JSON: the kind comes from what the field is declared as, else from
        // the value that is there, which is the same rule the panel has always used -- and it needs no path
        // into the chapter, because an element's fields are its own.
        InspectField<?> declared = QuestPanelLayout.fieldFor(tree, field);
        InspectField.Result<?> result = declared.parse(typed);
        if (!result.ok()) {
            book.status(result.error(), true);
            book.rebuildWidgets();
            return;
        }
        JsonElement value = jsonOf(result.value());
        draftElementField(element, field, value);
        book.send(new EditorOp.SetElement(element, field, value));
        book.rebuildWidgets();
    }

    /**
     * Writes one pending value for an element, which is what makes the edit visible before the tree arrives.
     *
     * <p>Before the send, not after, and unconditional: the draft is the <i>preview</i>, so a value the author
     * asked for shows even if the request could not go out — and the backstop in {@code FieldDraft} is what
     * stops a value the server never accepted from being believed forever. See {@code sendField} for the same
     * argument on a quest's fields.
     */
    void draftElementField(String element, String field, JsonElement value) {
        BookScreenState.fieldDraft.set(QuestBookScreen.effectiveChapter(),
                QuestBookScreen.ELEMENT_OWNER_PREFIX + element, field, value,
                ClientQuestCache.treeRevision(), Util.getMillis());
    }

    /**
     * The link a press landed on in the Chapter tab's link list, or null.
     *
     * <p>Hit-tested from the same slots the list was drawn from — the arrangement the chapter's quest
     * rows and element rows already have, and for the same reason: these rows hold no widget, so the
     * press they answer is read from their rectangles rather than from a control. Computed here
     * rather than cached during the draw because a press happens once, and a cached copy would be one
     * more thing to keep in step with the fold state.
     */
    String chapterLinkRowAt(double mouseX, double mouseY) {
        if (book.bookState.chapterLayout == null || book.bookState.toolsTab != ToolsLayout.Tab.CHAPTER) {
            return null;
        }
        String prefix = ChapterPanelLayout.VALUE_PREFIX + "link:";
        Viewport view = book.bookState.toolsView.viewport();
        for (ToolsLayout.Action row : book.bookState.chapterRows) {
            if (!row.key().startsWith(prefix)) {
                continue;
            }
            Slot slot = book.bookState.chapterLayout.slot(row.key());
            if (slot == null) {
                continue;
            }
            Slot onScreen = ToolsLayout.onScreen(view, slot);
            if (BookGeometry.Rect.at(onScreen.x(), onScreen.y(), onScreen.width(), onScreen.height())
                    .contains(mouseX, mouseY)) {
                String id = row.key().substring(prefix.length());
                // The "and N more" row is not a link: it is the count of what the list did not show.
                return "more".equals(id) ? null : id;
            }
        }
        return null;
    }

    /**
     * The element a press landed on in the Chapter tab's element list, or null.
     *
     * <p>Hit-tested from the same slots the list was drawn from — the arrangement the chapter's quest rows
     * already have, and for the same reason: these rows hold no widget, so the press they answer is read from
     * their rectangles rather than from a control. Computed here rather than cached during the draw because a
     * press happens once, and a cached copy would be one more thing to keep in step with the fold state.
     */
    String chapterElementRowAt(double mouseX, double mouseY) {
        if (book.bookState.chapterLayout == null || book.bookState.toolsTab != ToolsLayout.Tab.CHAPTER) {
            return null;
        }
        String prefix = ChapterPanelLayout.VALUE_PREFIX + "element:";
        Viewport view = book.bookState.toolsView.viewport();
        for (ToolsLayout.Action row : book.bookState.chapterRows) {
            if (!row.key().startsWith(prefix)) {
                continue;
            }
            Slot slot = book.bookState.chapterLayout.slot(row.key());
            if (slot == null) {
                continue;
            }
            Slot onScreen = ToolsLayout.onScreen(view, slot);
            if (BookGeometry.Rect.at(onScreen.x(), onScreen.y(), onScreen.width(), onScreen.height())
                    .contains(mouseX, mouseY)) {
                String id = row.key().substring(prefix.length());
                // The "and N more" row is not an element: it is the count of what the list did not show.
                return "more".equals(id) ? null : id;
            }
        }
        return null;
    }

    /**
     * The fault a press landed on in the Chapter tab's problem list, as an index into the
     * listed lines, or -1.
     *
     * <p>Hit-tested from the same slots the list was drawn from — the arrangement the element
     * and link rows already have, and for the same reason: these rows hold no widget, so the
     * press they answer is read from their rectangles rather than from a control. The count
     * row ({@code v:problems:more}) answers nothing, like the lists' own "more" rows.
     */
    int chapterProblemRowAt(double mouseX, double mouseY) {
        if (book.bookState.chapterLayout == null || book.bookState.toolsTab != ToolsLayout.Tab.CHAPTER) {
            return -1;
        }
        String prefix = ChapterPanelLayout.VALUE_PREFIX + "problem:";
        Viewport view = book.bookState.toolsView.viewport();
        int index = 0;
        for (ToolsLayout.Action row : book.bookState.chapterRows) {
            if (!row.key().startsWith(prefix)) {
                continue;
            }
            Slot slot = book.bookState.chapterLayout.slot(row.key());
            if (slot == null) {
                continue;
            }
            Slot onScreen = ToolsLayout.onScreen(view, slot);
            if (BookGeometry.Rect.at(onScreen.x(), onScreen.y(), onScreen.width(), onScreen.height())
                    .contains(mouseX, mouseY)) {
                return index;
            }
            index++;
        }
        return -1;
    }

    /**
     * A chapter flag's press, to the state the switch was moved to.
     *
     * <h2>Why the wanted state is an argument rather than an inversion</h2>
     *
     * <p>Because the switch <i>is</i> the author's answer -- it flips before it tells the handler, which is the
     * order {@code ArmatureSwitch} documents -- and the tree it would have been inverted against is a round
     * trip behind. A second press before the first answer landed used to read the same stale tree and write the
     * same value again, so a double press was one edit and the switch appeared stuck.
     */
    private void pressChapterToggle(String path, boolean wanted) {
        if (!book.mayEditNow()) {
            return;
        }
        if (path.startsWith(ChapterPanelLayout.GROUP_PREFIX)) {
            pressGroupToggle(path.substring(ChapterPanelLayout.GROUP_PREFIX.length()), wanted);
            return;
        }
        if (path.startsWith(ChapterPanelLayout.ELEMENT_PREFIX)) {
            // An element's flag: `dev`, `corner`, `shadow` and `label.onImage` are the four, and the file is
            // written by id because an element is addressed by the name an author sees, not by a position.
            String[] field = ChapterPanelLayout.elementFieldOf(path);
            if (field != null) {
                book.send(new EditorOp.SetElement(field[0], field[1], new JsonPrimitive(wanted)));
                book.rebuildWidgets();
            }
            return;
        }
        book.sendChapterField(path, new JsonPrimitive(wanted));
        // The button's own label is built from this value, so the flip is visible on this press
        // rather than on the tree that follows it.
        book.rebuildWidgets();
    }

    /**
     * Answers a press on one fault of the pack's report: the file goes on the clipboard and the
     * whole line goes to the chat.
     *
     * <p>The row is a pointer at a message whose full text fits nowhere in the dock, so the
     * press delivers both halves where they fit: the file (what an external editor opens) on
     * the clipboard, and the line (what the log carries) in the chat, where it keeps full
     * width and a history. Client-local: no packet, no command, and nothing the validator sees.
     */
    void pressProblemRow(int problem) {
        String line = problemLineAt(problem);
        if (line == null || line.isEmpty()) {
            return;
        }
        String file = ProblemText.fileOf(line);
        Minecraft game = Minecraft.getInstance();
        if (game != null && game.keyboardHandler != null && !file.isEmpty()) {
            game.keyboardHandler.setClipboard(file);
        }
        if (game != null && game.player != null) {
            game.player.sendSystemMessage(Component.literal(line));
        }
        book.status(Labels.of("tenet.dev.chapter.problem_copied", file), false);
    }

    /**
     * Prints the pack's current faults to the chat, one line each: the heading's press.
     *
     * <p>The same sentences the payload carried — the log's own wording, not a rewording — so
     * an author can read them outside the narrow panel. Nothing to print is a status line
     * rather than silence, so the press always answers.
     */
    private void printProblemsToChat() {
        List<String> lines = ClientEditProblems.current()
                .map(ClientEditProblems.Report::lines)
                .orElse(List.of());
        Minecraft game = Minecraft.getInstance();
        if (!lines.isEmpty() && game != null && game.player != null) {
            for (String line : lines) {
                game.player.sendSystemMessage(Component.literal(line));
            }
        }
        book.status(Labels.of("tenet.dev.chapter.problems_to_chat", lines.size()), false);
    }

    /**
     * Steps one of the Chapter tab's cycling rows and sends the write it lands on.
     *
     * <p>The current value comes from the chapter's own tree -- the replica, not a local draft --
     * because the cycle has to wrap around the value the file actually holds, and the write goes
     * through {@link ChapterPanelLayout#choiceEdit}, which is where the two shapes of row (a plain
     * field, and an axis inside {@code dependencyStyle}) are told apart.
     */
    /**
     * One of the chapter's cycling rows, set to a value the author chose from its menu.
     *
     * <p>The value comes from {@link ChapterPanelLayout#choiceValues}, so the menu can only offer what the
     * file accepts; the write goes through {@link ChapterPanelLayout#choiceEdit}, which is where the two
     * shapes of row (a plain field, and an axis inside {@code dependencyStyle}) are told apart.
     *
     * <h2>Why this replaced a pair of arrows</h2>
     *
     * <p>The row used to be stepped one value at a time by two drawn arrows with a hit test of their own --
     * art in a third class and a press path in this one, for one row kind. It is the drawer's own chooser
     * now: press it, pick from the list. That is fewer presses for a value four positions away, and it is
     * the same control the book tab's choices use, which is the whole point of the move.
     */
    private void setChapterChoice(ChapterPanelLayout.Choice choice, String value) {
        if (!book.mayEditNow()) {
            return;
        }
        JsonObject chapter = BookScreenState.fieldDraft.overlaid(QuestBookScreen.effectiveChapter(),
                dev.ellipog.tenet.client.dev.FieldDraft.CHAPTER_OWNER,
                ClientChapterReplica.chapterTree(QuestBookScreen.effectiveChapter()));
        ChapterPanelLayout.Edit edit = ChapterPanelLayout.choiceEdit(chapter, choice, value);
        book.sendChapterField(edit.path(), edit.value());
        // The row is drawn from the rows built on this value, so it changes on this press.
        book.rebuildWidgets();
        book.status(choice.label() + ": " + ChapterPanelLayout.choiceLabel(choice, value), false);
    }

    /** One text field of the chapter's group: the group's own file, so its own op. */
    private void commitGroupField(String path, String text) {
        if (!book.mayEditNow()) {
            return;
        }
        String typed = text == null ? "" : text.trim();
        // Empty means absent, the same rule every field follows: the title a group is not allowed to
        // lose is refused by the validator on apply, not by a second rule here.
        book.send(new EditorOp.SetGroup(path, typed.isEmpty() ? null : new JsonPrimitive(typed)));
    }

    /** The group's collapsed flag, set to the state its switch was moved to. See `pressChapterToggle`. */
    private void pressGroupToggle(String path, boolean wanted) {
        ChapterPanelLayout.GroupInfo group = chapterGroupInfo(QuestBookScreen.effectiveChapter());
        if (!book.mayEditNow() || group == null) {
            return;
        }
        book.send(new EditorOp.SetGroup(path, new JsonPrimitive(wanted)));
    }

    /**
     * The pack's faults, as the Chapter tab's own record.
     *
     * <p>The one place the client's store and the layout's input meet, which is the whole reason the layout
     * declares its own {@code Problems} rather than reading the store: a layout that reached for the news
     * would have to be tested with the news, and this file is asserted without a client at all.
     *
     * <p>Empty rather than null when nothing has been reported — a player who joined before any load, or a
     * client that has just cleared on leaving a world. "Nothing heard" and "nothing wrong" draw the same,
     * which is correct: the panel has nothing to say in either case, and inventing a distinction would be a
     * line about a load the player was never told about.
     */
    private static ChapterPanelLayout.Problems packProblems() {
        return ClientEditProblems.current()
                .map(report -> new ChapterPanelLayout.Problems(report.count(), report.lines()))
                .orElse(ChapterPanelLayout.Problems.NONE);
    }

    /** The chapter's icon id as its file spells it, or "" when it declares none. */
    private static String chapterIconId(JsonObject chapter) {
        JsonElement item = ChapterPanelLayout.icon(chapter).get("item");
        return item != null && item.isJsonPrimitive() ? item.getAsString() : "";
    }

    /** The chapter's identity, read from its tree: the header's text, the icon, and the id behind it. */
    private void readChapterIdentity(JsonObject chapter) {
        chapterHeader = ChapterPanelLayout.header(chapter);
        chapterIcon = chapterIconStack(chapter);
        chapterIconId = chapterIconId(chapter);
    }

    /**
     * The selected chapter's group, as the Chapter tab's Group section needs it, or null when there is
     * none to show.
     *
     * <p>Read from the client's tree: the entries are what say which group a chapter hangs under -- the
     * heading list alone cannot -- and the headings are what say what that group is called, what icon it
     * has authored, and whether its chapters start collapsed. A chapter whose group the server did not
     * describe (an older server, or a tree with no entry for it) gets no section rather than one whose
     * edits could only be refused.
     */
    private static ChapterPanelLayout.GroupInfo chapterGroupInfo(String chapterId) {
        if (chapterId == null || chapterId.isEmpty()) {
            return null;
        }
        String groupId = "";
        for (ClientQuestCache.Entry entry : ClientQuestCache.entries()) {
            if (entry.chapterId().equals(chapterId)) {
                groupId = entry.chapterGroupId();
                break;
            }
        }
        if (groupId.isEmpty()) {
            return null;
        }
        for (ClientQuestCache.GroupEntry group : ClientQuestCache.groups()) {
            if (group.id().equals(groupId)) {
                // Whichever arm the group wears, like the chapter's own button: an item id, an
                // entity id (drawn as its egg), a texture path or a sprite region. The cache keeps
                // texture and sprite out of `iconId`, so those read from their own fields — otherwise
                // a group wearing a texture would show "unset" while the sidebar draws its picture.
                String worn = !group.iconId().isEmpty() ? group.iconId()
                        : !group.textureIcon().isEmpty() ? group.textureIcon()
                                : group.spriteIcon();
                return new ChapterPanelLayout.GroupInfo(group.id(), group.title(), worn,
                        group.collapsedByDefault());
            }
        }
        return null;
    }

    /**
     * The group's own icon, resolved: empty when it has none of its own and is borrowing a chapter's.
     *
     * <p>The authored stack alone, never the fallback: the Group section's button says "first chapter's"
     * while this is empty, and setting one is only meaningful as a distinct act if the button was not
     * already showing the borrowed icon as though it were the group's.
     */
    private static ItemStack groupIconStack(ChapterPanelLayout.GroupInfo group) {
        if (group == null) {
            return ItemStack.EMPTY;
        }
        for (ClientQuestCache.GroupEntry entry : ClientQuestCache.groups()) {
            if (entry.id().equals(group.id())) {
                return entry.icon();
            }
        }
        return ItemStack.EMPTY;
    }

    /** The group's own texture path, or "" when it authors an item, a sprite, or nothing. */
    private static String groupTextureIcon(ChapterPanelLayout.GroupInfo group) {
        if (group == null) {
            return "";
        }
        for (ClientQuestCache.GroupEntry entry : ClientQuestCache.groups()) {
            if (entry.id().equals(group.id())) {
                return entry.textureIcon();
            }
        }
        return "";
    }

    /** The group's own atlas region, or "" when it authors an item, a texture, or nothing. */
    private static String groupSpriteIcon(ChapterPanelLayout.GroupInfo group) {
        if (group == null) {
            return "";
        }
        for (ClientQuestCache.GroupEntry entry : ClientQuestCache.groups()) {
            if (entry.id().equals(group.id())) {
                return entry.spriteIcon();
            }
        }
        return "";
    }

    /** The chapter's icon, resolved for drawing; empty when absent or unknown to this client. */
    private static ItemStack chapterIconStack(JsonObject chapter) {
        JsonObject icon = ChapterPanelLayout.icon(chapter);
        return ClientQuestCache.iconOf(chapterIconId(chapter), icon.get("components"));
    }

    /** One arm of an `icon` object as stored, or empty when it names none. */
    private static String iconString(JsonObject owner, String arm) {
        if (owner == null || !owner.has("icon") || !owner.get("icon").isJsonObject()) {
            return "";
        }
        JsonElement found = owner.getAsJsonObject("icon").get(arm);
        return found != null && found.isJsonPrimitive() ? found.getAsString() : "";
    }

    /**
     * Opens the item picker on the chapter's icon, from the dock.
     *
     * <p>The picker's own overlay rather than the quest card's: the Chapter tab is not a quest, and the
     * card this is drawn over is the same card every other picker uses -- see {@link PanelKind#PICKER}.
     * The current value is read from the replica's chapter tree, which is the same source the rows and
     * the header came from, so the picker's "current" line cannot name an item the panel is not showing.
     */
    private void openChapterItemPicker() {
        if (!book.mayEditNow()) {
            return;
        }
        JsonObject chapter = BookScreenState.fieldDraft.overlaid(QuestBookScreen.effectiveChapter(),
                dev.ellipog.tenet.client.dev.FieldDraft.CHAPTER_OWNER,
                ClientChapterReplica.chapterTree(QuestBookScreen.effectiveChapter()));
        readChapterIdentity(chapter);
        book.bookState.pickTarget = QuestBookScreen.PickTarget.CHAPTER;
        book.bookState.pickIcon = chapterIcon;
        book.bookState.pickTexture = iconString(chapter, "texture");
        book.bookState.pickSprite = iconString(chapter, "sprite");
        book.bookState.pickName = chapterHeader.title();
        book.bookState.pickingItemPath = ChapterPanelLayout.ICON;
        book.bookState.pickingItemCurrent = chapterIconId;
        // Clearing removes the whole `icon` object rather than its `item` member, so the path here is
        // the object's: `remove("icon.item")` would leave `"icon": {}` behind, and an item-less icon
        // object is a file the codec refuses. The clear row is drawn from this being non-null.
        book.bookState.pickingItemClearPath = "icon";
        openPickerOverlay();
    }

    /**
     * The same, on the chapter's <b>group's</b> icon.
     *
     * <p>The group's own icon and no fallback: the picker's current line is the authored item, and an
     * empty one is what makes the clear row absent and the card's title honest about a group that has
     * none of its own. A chapter whose group the tree does not describe has nothing to edit, and says so
     * rather than opening a picker that could only be refused.
     */
    private void openGroupItemPicker() {
        if (!book.mayEditNow()) {
            return;
        }
        ChapterPanelLayout.GroupInfo group = chapterGroupInfo(QuestBookScreen.effectiveChapter());
        if (group == null) {
            book.status("tenet.status.this_chapter_s_group_is_not_editable_from_here", true);
            return;
        }
        book.bookState.pickTarget = QuestBookScreen.PickTarget.GROUP;
        book.bookState.pickIcon = groupIconStack(group);
        book.bookState.pickTexture = "";
        book.bookState.pickSprite = "";
        // The authored arms, from the tree's own group list: the info above carries only the item
        // id, and a group wearing a texture would otherwise greet with a blank header.
        for (ClientQuestCache.GroupEntry listed : ClientQuestCache.groups()) {
            if (listed.id().equals(group.id())) {
                book.bookState.pickTexture = listed.textureIcon();
                book.bookState.pickSprite = listed.spriteIcon();
                break;
            }
        }
        book.bookState.pickName = group.title();
        book.bookState.pickingItemPath = ChapterPanelLayout.ICON;
        book.bookState.pickingItemCurrent = group.iconId();
        book.bookState.pickingItemClearPath = "icon";
        openPickerOverlay();
    }

    /**
     * The same, on the book's own icon: the pack's {@code index.json}, not any chapter's file.
     *
     * <p>No path and no current file to read — the value is the id the tree carried, which is what the
     * header draws — so the picker's "current" line cannot name an item the book is not wearing.
     */
    private void openBookItemPicker() {
        if (!book.mayEditNow()) {
            return;
        }
        book.bookState.pickTarget = QuestBookScreen.PickTarget.BOOK;
        book.bookState.pickIcon = ClientQuestCache.bookIcon();
        book.bookState.pickTexture = ClientQuestCache.bookTextureIcon();
        book.bookState.pickSprite = ClientQuestCache.bookSpriteIcon();
        book.bookState.pickName = book.bookTitle().getString();
        book.bookState.pickingItemPath = "bookIcon";
        book.bookState.pickingItemCurrent = ClientQuestCache.bookIconId();
        book.bookState.pickingItemClearPath = "bookIcon";
        openPickerOverlay();
    }

    /** The shared last half of the two opens above: the picker's state, then the card. */
    void openPickerOverlay() {
        book.bookState.pickerFromSettings = false;
        book.bookState.pickingEntryType = null;
        book.bookState.pickerEntries = BookItemPicker.catalogue();
        book.bookState.pickerInventory = BookItemPicker.carried();
        book.bookState.pickerMatches = List.of();
        book.bookState.pickerRows = List.of();
        book.bookState.pickerFrame = null;
        book.bookState.pickerQuery = "";
        book.bookState.pickerSelected = -1;
        book.bookState.pickerBody.setScrollY(0);
        // The dock's icon picks greet on items too; the tabs offer the rest.
        book.bookState.pickerKind = QuestBookScreen.PickerKind.ITEM;
        // A pick is filed by the rules like everything else: column 2 when there is a panel for it to
        // belong to, column 1 when there is not -- which is what makes a pick opened from the author's dock
        // a panel beside the dock rather than a child that could fold it away.
        book.applyColumns(PanelStack.afterOpen(book.columns(), PanelKind.PICKER));
        book.rebuildWidgets();
    }

    /**
     * Opens the type picker on a member, at the top of its list.
     *
     * <p>One way in, so the two that reach it cannot disagree about what opening means -- and the scroll
     * reset is part of opening: a list has no memory between openings, and the last one's scroll would
     * drop the author into the middle of a list they have not seen yet.
     */
    void openTypePicker(String member) {
        book.bookState.pickingEntryType = member;
        book.bookState.pickingConditionFor = null;
        book.bookState.overlayView.scrollTo(0);
        book.rebuildWidgets();
    }

    /**
     * The same list of types, opened <b>from the table editor</b>: a card over the editor, not a page of it.
     *
     * <h2>Why this is not {@link #openTypePicker}</h2>
     *
     * <p>Because the button beside it does the other thing. `+ Item` opens the picker's own overlay card
     * — see {@link QuestBookScreen#openTableItemPick} — so a list of rewards drawn *inside* the table's card put two
     * sibling buttons in one toolbar into two different shapes: one opened a card over the panel and the
     * other pushed a page into it, with the table's chrome above the list and the table's footer as its
     * way out. The act is the same, so the presentation is the same: a pick from a table opens a card, and
     * a pick from a quest card is a page of that card (which is what that card's item picker is too).
     *
     * <p>The card's subject is filled the way the item pick does it — the table's name, and the icon the
     * pick is for — and it goes through {@link QuestBookScreen#tablePickSubjectFor} rather than naming an icon itself, so
     * the `+ Item` beside it and this button cannot show two different headers for one act.
     */
    void openTableTypePick(String member, String title) {
        if (book.bookState.tableAddress == null) {
            return;
        }
        book.bookState.typePickReturn = true;
        // The subject is reset *before* it is filled, and the target named, exactly as the item pick does
        // it: the card's own widget pass re-reads the subject from the tree while this overlay is open, and
        // a `pickTarget` left as a chapter's from the last pick would put that chapter's title and icon on
        // the header of a list of the table's rewards.
        book.bookAssets.forgetPickSubject();
        book.bookState.pickTarget = QuestBookScreen.PickTarget.QUEST;
        book.bookState.pickTitle = title;
        book.bookState.pickName = book.bookRewards.tableTitle();
        book.bookState.pickIcon = book.bookAssets.tablePickSubjectFor("", -1);
        book.bookState.pickingEntryType = member;
        book.bookState.pickingConditionFor = null;
        // A fresh box and a fresh list per open, on the item picker's terms: the last page's query must
        // not filter this one's, and a list has no memory between openings.
        book.bookState.typeSearch = null;
        book.bookState.typeQuery = "";
        book.bookState.typeSelected = -1;
        book.bookState.typeBody.setScrollY(0);
        book.applyColumns(PanelStack.afterOpen(book.columns(), PanelKind.PICKER));
        book.rebuildWidgets();
    }

    /** Leaves the type page a table opened: back to the editor, with the pick forgotten. */
    void closeTypePick() {
        book.bookState.typePickReturn = false;
        book.bookState.pickingEntryType = null;
        book.bookState.pickingConditionFor = null;
        book.bookState.typeRows = List.of();
        book.bookState.typeFrame = null;
        book.bookState.typeSearch = null;
        book.bookState.typeQuery = "";
        book.bookState.typeSelected = -1;
        book.bookState.typeBody.setScrollY(0);
        if (book.bookState.tableAddress != null) {
            // Back to the editor through the rules rather than by writing the field: the editor replaces the
            // arrangement, so a picker that was sitting in the second column goes with it.
            book.applyColumns(PanelStack.asRoot(book.columns(), PanelKind.TABLE_EDITOR));
        }
        book.rebuildWidgets();
    }

    /**
     * The same picker, adding a condition to one entry's list instead of a task or a reward.
     *
     * <p>{@code pathPrefix} is the entry's own path ("tasks.3"): the picker lists condition types while
     * it is set, and the press rebuilds that entry's {@code conditions} array with the new one appended.
     */
    void openConditionTypePicker(String pathPrefix) {
        book.bookState.pickingEntryType = null;
        book.bookState.pickingConditionFor = pathPrefix;
        book.bookState.overlayView.scrollTo(0);
        // The entry the picker is adding to is where the author is: a condition template applied
        // from the shelf lands here next.
        if (pathPrefix != null && book.editTarget() != null) {
            int dot = pathPrefix.lastIndexOf('.');
            if (dot > 0) {
                try {
                    dev.ellipog.tenet.client.dev.ComponentTemplates.noteEntryContext(book.editTarget(),
                            pathPrefix.substring(0, dot), Integer.parseInt(pathPrefix.substring(dot + 1)));
                }
                catch (NumberFormatException notAnEntry) {
                    // A path that does not end in an index names no entry, so there is no context
                    // to remember — and the picker's own press will say so if it matters.
                }
            }
        }
        book.rebuildWidgets();
    }

    /**
     * One row of the condition picker: append that type's default to the entry's conditions list.
     *
     * <p>A rebuilt array through {@code SetField}, not an insert op: {@code EditorOp.Insert} addresses a
     * top-level list, and a nested one has no such op — the same move the dependency list and the chapter
     * order already make. One op, so one undo.
     */
    private void pressConditionTypeRow(String typeId) {
        if (typeId.startsWith(QuestBookScreen.TEMPLATE_ROW_PREFIX)) {
            pressTemplateConditionRow(typeId.substring(QuestBookScreen.TEMPLATE_ROW_PREFIX.length()));
            return;
        }
        String prefix = book.bookState.pickingConditionFor;
        if (prefix == null || book.editTarget() == null) {
            return;
        }
        com.google.gson.JsonObject fresh = net.minecraft.resources.Identifier.tryParse(typeId) == null
                ? null
                : dev.ellipog.tenet.quest.condition.ConditionTypes
                        .defaultTree(net.minecraft.resources.Identifier.tryParse(typeId)).orElse(null);
        if (fresh == null) {
            book.status("This build cannot add a " + typeId + " here", true);
            return;
        }
        if (appendCondition(prefix, fresh)) {
            book.status("Added a " + typeId + " condition", false);
        }
    }

    /**
     * One row of the condition picker that is a template: append a copy of the saved condition
     * instead of the type's default.
     */
    private void pressTemplateConditionRow(String templateId) {
        String prefix = book.bookState.pickingConditionFor;
        if (prefix == null || book.editTarget() == null) {
            return;
        }
        var template = dev.ellipog.tenet.client.dev.ComponentTemplates.byId(templateId).orElse(null);
        if (template == null) {
            return;
        }
        if (!BookAssets.knownTemplateType(template) || template.kind()
                != dev.ellipog.tenet.client.dev.ComponentTemplate.Kind.CONDITION) {
            book.status("tenet.status.template_unknown_type", true);
            return;
        }
        if (appendCondition(prefix, template.freshCopy())) {
            dev.ellipog.tenet.client.dev.ComponentTemplates.markUsed(templateId);
            book.status("Applied \"" + template.name() + "\"", false);
        }
    }

    /**
     * Appends one condition tree to an entry's conditions list.
     *
     * @return whether the op was sent: false when the open quest is gone, so callers report nothing
     */
    private boolean appendCondition(String prefix, com.google.gson.JsonObject fresh) {
        JsonObject quest = book.replicaQuest();
        if (quest == null) {
            return false;
        }
        com.google.gson.JsonArray conditions = new com.google.gson.JsonArray();
        JsonElement existing = QuestPanelLayout.get(quest, prefix + ".conditions");
        if (existing != null && existing.isJsonArray()) {
            for (JsonElement value : existing.getAsJsonArray()) {
                conditions.add(value.deepCopy());
            }
        }
        conditions.add(fresh);
        book.sendField(book.editTarget(), prefix + ".conditions", conditions);
        book.bookState.pickingConditionFor = null;
        return true;
    }

    /**
     * Removes one condition from its entry, by index: the list is rebuilt without it.
     *
     * <p>{@code path} is the condition's own path ("tasks.3.conditions.1"). Removing the last one sends
     * an empty array rather than nothing, so the field's presence is what the author chose rather than
     * something the write decided.
     */
    void removeCondition(String path) {
        if (book.editTarget() == null) {
            return;
        }
        int dot = path.lastIndexOf('.');
        int index;
        try {
            index = Integer.parseInt(path.substring(dot + 1));
        }
        catch (NumberFormatException notAnIndex) {
            return;
        }
        String listPath = path.substring(0, dot);
        JsonObject quest = book.replicaQuest();
        if (quest == null) {
            return;
        }
        JsonElement existing = QuestPanelLayout.get(quest, listPath);
        if (existing == null || !existing.isJsonArray()) {
            return;
        }
        com.google.gson.JsonArray held = existing.getAsJsonArray();
        if (index < 0 || index >= held.size()) {
            // The array changed under the last frame. Rebuilding without an element would send an array
            // identical to the one stored -- a wasted save and a false "removed".
            return;
        }
        com.google.gson.JsonArray rebuilt = new com.google.gson.JsonArray();
        for (int i = 0; i < held.size(); i++) {
            if (i != index) {
                rebuilt.add(held.get(i).deepCopy());
            }
        }
        // The list's shape changes here, and a pending value indexed inside it would name a different
        // condition afterwards -- the same failure `pressEntry` forgets for, one nesting level down.
        // `rebuilt` was read through the overlay, so it already carries every other condition's pending
        // values; forgetting the leaves loses nothing the array draft that follows does not hold.
        BookScreenState.fieldDraft.forgetList(QuestBookScreen.effectiveChapter(), book.editTarget(), listPath);
        book.sendField(book.editTarget(), listPath, rebuilt);
        book.status("tenet.status.removed_the_condition", false);
    }

    /**
     * One row of the type picker: add a fresh entry of that type, and close the picker.
     *
     * <p>The tree is the type's own default, encoded by the type's codec -- so what the picker adds is
     * exactly what the loader reads back, and an addon's type is addable the day it registers. A type
     * whose defaults cannot be encoded is refused out loud rather than inserted half-formed.
     */
    void pressTypeRow(String typeId) {
        if (typeId.startsWith(QuestBookScreen.TEMPLATE_ROW_PREFIX)) {
            pressTemplateRow(typeId.substring(QuestBookScreen.TEMPLATE_ROW_PREFIX.length()));
            return;
        }
        if (book.bookState.pickingConditionFor != null) {
            pressConditionTypeRow(typeId);
            return;
        }
        // The type picker opened from the table editor adds an *entry*, not a reward on a quest. Read
        // before the card's own path, which would otherwise insert the reward into the quest behind the
        // panel -- a reward on a quest the author is not looking at, from a press they made in a table.
        //
        // The test is the return flag and not the overlay, which is the half of this that the consistency
        // fix above had to move with the button: the page is now a card *over* the editor, so `overlay` is
        // PICKER while it is open and a condition that asked for TABLE_EDITOR would fall through to the
        // card's path -- the exact fault this comment describes, arriving from the other direction.
        if (book.bookState.tableAddress != null && book.bookState.typePickReturn
                && book.bookState.pickingEntryType != null) {
            // `defaultElement` is a reward; a table's list holds `{weight, reward}`. This press used to
            // pass the reward straight to the insert, so the file got a reward where an entry belongs --
            // refused by the validator as `unknown field "type"` and `unknown field "table"`, every time,
            // for every type. Which meant the one route to a non-item entry had never worked at all.
            JsonObject reward = QuestPanelLayout.defaultElement(book.bookState.pickingEntryType, typeId);
            if (reward == null) {
                book.status("This build cannot add a " + typeId + " here", true);
                return;
            }
            int at = book.bookRewards.tableModel().map(dev.ellipog.tenet.quest.loot.RewardTable::entryCount).orElse(0);
            BookRewards.tableDraft.forgetList(QuestBookScreen.effectiveChapter(),
                    book.bookRewards.tableOwnerKey(), "entries");
            book.bookRewards.sendTableOp(new dev.ellipog.tenet.editor.TableOp.Insert(book.bookState.tableAddress, at,
                    QuestPanelLayout.tableEntry(reward)));
            // **The page closes, and it closes back into the editor.** Clearing the member alone left the
            // overlay as PICKER with no list under it, so the picker's card went on showing whatever the
            // item picker had last held -- and `closeTypePick` is the same exit the footer's Back takes,
            // so adding a type and leaving the page are one route rather than two.
            closeTypePick();
            book.status("Added " + typeId, false);
            return;
        }
        String member = book.bookState.pickingEntryType;
        if (member == null || book.editTarget() == null) {
            return;
        }
        JsonObject entry = QuestPanelLayout.defaultElement(member, typeId);
        if (entry == null) {
            book.status("This build cannot add a " + typeId + " here", true);
            return;
        }
        JsonObject quest = book.replicaQuest();
        int at = quest == null ? 0 : QuestPanelLayout.strings(quest, member).size();
        if (quest != null && quest.has(member) && quest.get(member).isJsonArray()) {
            at = quest.getAsJsonArray(member).size();
        }
        book.send(new EditorOp.Insert(book.editTarget(), member, at, entry));
        book.bookState.pickingEntryType = null;
        dev.ellipog.tenet.client.dev.ComponentTemplates.noteEntryContext(book.editTarget(), member, at);
        book.status("Added " + typeId, false);
    }

    /**
     * One row of the type picker that is a template: add a copy of the saved setup instead of a
     * fresh default, and close the picker.
     *
     * <p>Every road {@link #pressTypeRow} walks, with the payload swapped: the table editor wraps
     * a reward into an entry, the quest card inserts at the end of the list, and an unknown type
     * or a template deleted between the draw and the press is refused out loud. A copy, so the
     * shelf and the quest never share an id.
     */
    private void pressTemplateRow(String templateId) {
        var template = dev.ellipog.tenet.client.dev.ComponentTemplates.byId(templateId).orElse(null);
        if (template == null) {
            return;
        }
        if (!BookAssets.knownTemplateType(template)) {
            book.status("tenet.status.template_unknown_type", true);
            return;
        }
        if (book.bookState.tableAddress != null && book.bookState.typePickReturn
                && book.bookState.pickingEntryType != null) {
            if (template.kind()
                    != dev.ellipog.tenet.client.dev.ComponentTemplate.Kind.REWARD) {
                return;
            }
            int at = book.bookRewards.tableModel().map(dev.ellipog.tenet.quest.loot.RewardTable::entryCount).orElse(0);
            BookRewards.tableDraft.forgetList(QuestBookScreen.effectiveChapter(),
                    book.bookRewards.tableOwnerKey(), "entries");
            book.bookRewards.sendTableOp(new dev.ellipog.tenet.editor.TableOp.Insert(book.bookState.tableAddress, at,
                    QuestPanelLayout.tableEntry(template.freshCopy())));
            closeTypePick();
            dev.ellipog.tenet.client.dev.ComponentTemplates.markUsed(templateId);
            book.status("Applied \"" + template.name() + "\"", false);
            return;
        }
        String member = book.bookState.pickingEntryType;
        if (member == null || book.editTarget() == null) {
            return;
        }
        var want = "rewards".equals(member)
                ? dev.ellipog.tenet.client.dev.ComponentTemplate.Kind.REWARD
                : dev.ellipog.tenet.client.dev.ComponentTemplate.Kind.TASK;
        if (template.kind() != want) {
            return;
        }
        JsonObject quest = book.replicaQuest();
        int at = quest == null ? 0 : QuestPanelLayout.strings(quest, member).size();
        if (quest != null && quest.has(member) && quest.get(member).isJsonArray()) {
            at = quest.getAsJsonArray(member).size();
        }
        book.send(new EditorOp.Insert(book.editTarget(), member, at, template.freshCopy()));
        book.bookState.pickingEntryType = null;
        dev.ellipog.tenet.client.dev.ComponentTemplates.noteEntryContext(book.editTarget(), member, at);
        dev.ellipog.tenet.client.dev.ComponentTemplates.markUsed(templateId);
        book.status("Applied \"" + template.name() + "\"", false);
    }

    /**
     * An entry row's two controls: Copy duplicates the entry beside itself, the cross removes it.
     *
     * <p>Both are one op each, so undo is one step each -- and the copy is the entry's own tree, which
     * is what keeps a task's unknown-to-the-panel fields when it is duplicated.
     */
    void pressEntry(String key, boolean copy) {
        JsonObject quest = book.replicaQuest();
        if (quest == null || book.editTarget() == null) {
            return;
        }
        String rest = key.startsWith("h:") ? key.substring(2) : key;
        int dot = rest.lastIndexOf('.');
        if (dot < 0) {
            return;
        }
        String member = rest.substring(0, dot);
        int index;
        try {
            index = Integer.parseInt(rest.substring(dot + 1));
        }
        catch (NumberFormatException notAnEntry) {
            return;
        }
        JsonElement found = QuestPanelLayout.get(quest, member + "." + index);
        if (found == null || !found.isJsonObject()) {
            return;
        }
        // The list's shape is about to change, so a pending value indexed inside it no longer names
        // the entry the author was editing -- see FieldDraft.forgetList for what that would commit.
        BookScreenState.fieldDraft.forgetList(QuestBookScreen.effectiveChapter(), book.editTarget(), member);
        if (copy) {
            book.send(new EditorOp.Insert(book.editTarget(), member, index + 1, found.getAsJsonObject().deepCopy()));
            // The copy is where the author is now: a condition template applied from the shelf lands
            // on the entry the last press left them at.
            dev.ellipog.tenet.client.dev.ComponentTemplates.noteEntryContext(book.editTarget(), member, index + 1);
            book.status("Copied " + member.substring(0, member.length() - 1) + " " + (index + 1), false);
        }
        else {
            book.send(new EditorOp.Remove(book.editTarget(), member, index));
            book.status("Removed " + member.substring(0, member.length() - 1) + " " + (index + 1), false);
        }
    }

    /**
     * The edit bar's Delete: the first press arms it, the second deletes.
     *
     * <p>A quest is a file, and the model's delete renames rather than destroys -- but "recoverable"
     * is not the same as "obvious", and one keystroke away from a mis-click is exactly where the
     * confirmation belongs. Any other press disarms, so the label on screen always matches what the
     * next press will do.
     */
    private void pressDeleteQuest() {
        if (book.editTarget() == null) {
            return;
        }
        if (!book.bookState.confirmingDelete) {
            book.bookState.confirmingDelete = true;
            book.rebuildWidgets();
            return;
        }
        book.send(new EditorOp.Delete(book.editTarget()));
        book.bookState.confirmingDelete = false;
        book.closeOverlay();
    }

    /** The edit bar's Duplicate: a copy of the quest, beside it, under a fresh id. */
    private void duplicateQuest() {
        if (book.editTarget() == null) {
            return;
        }
        book.send(new EditorOp.Duplicate(book.editTarget()));
        book.closeOverlay();
    }

    /** The edit bar's Copy: the quest's own tree onto the session clipboard, as Ctrl+C would. */
    private void copyQuest() {
        JsonObject quest = book.replicaQuest();
        if (quest == null) {
            return;
        }
        ClientEditorClipboard.copy(List.of(quest));
        book.status("Copied " + book.editTarget(), false);
    }

    /**
     * Lands the pick: the quest that was clicked becomes a prerequisite of the quest the pick was armed
     * for.
     *
     * <p>The clicked quest may be in any chapter or group -- a dependency is an id, and the loader resolves
     * ids across the whole tree -- so what is checked is the id, not where it was clicked. The refusals are
     * said here, in the status line, rather than landing as an operation the server then refuses: a pick
     * that silently did nothing was the report.
     */
    void addPickedDependency(String id) {
        dev.ellipog.tenet.client.dev.DependencyPick pick = book.bookState.pendingPick;
        book.bookState.pendingPick = null;
        if (pick == null) {
            return;
        }
        // The three rules live in the pick itself, where a test can hold them: append to the end of the
        // list as it was armed, refuse a self-dependency, refuse one that is already there.
        dev.ellipog.tenet.client.dev.DependencyPick.Result result = pick.with(id);
        if (result.outcome() == dev.ellipog.tenet.client.dev.DependencyPick.Outcome.ITSELF) {
            book.status("tenet.status.a_quest_cannot_depend_on_itself", true);
            returnToEditedQuest(pick);
            return;
        }
        if (result.outcome() == dev.ellipog.tenet.client.dev.DependencyPick.Outcome.ALREADY) {
            book.status(pick.quest() + " already depends on " + id, true);
            returnToEditedQuest(pick);
            return;
        }
        // Sent to the chapter that was being edited, not to the one on screen: the pick may have been
        // taken in another chapter or group entirely — and the draft goes under that chapter too, since
        // that is whose replica will answer for it.
        book.bookInput.sendField(pick.chapter(), pick.quest(), "dependsOn",
                QuestBookScreen.stringArray(result.dependsOn()));
        book.status(pick.quest() + " now depends on " + id, false);
        returnToEditedQuest(pick);
    }

    /**
     * Back to the quest the pick was armed from: its chapter, then its card.
     *
     * <p>Because a pick is a detour and not an exit. The author was editing a quest, left it to name a
     * prerequisite -- possibly in another chapter, which is why the pick carries its own chapter -- and
     * wants to be back where they were with the list they just changed in front of them, rather than on
     * the canvas reading a status line. Cancelling goes back too, unchanged, which is what makes Escape a
     * way out of a pick rather than out of the book.
     */
    void returnToEditedQuest(dev.ellipog.tenet.client.dev.DependencyPick pick) {
        if (pick.chapter() != null && !pick.chapter().equals(BookScreenState.selectedChapter)) {
            // The chapter the quest lives in, not the one on screen: the pick may have been taken
            // somewhere else entirely, and the card can only show a quest of the chapter it is drawn in.
            BookScreenState.selectedChapter = pick.chapter();
            BookScreenState.frameChapterActive = false;
        }
        BookScreenState.selectedQuest = pick.quest();
        BookScreenState.multiSelection.clear();
        book.bookRewards.openOverlay(pick.quest());
    }

    void addDependency(String typed) {
        JsonObject quest = book.replicaQuest();
        String target = book.editTarget();
        if (!book.mayEditNow() || target == null || quest == null) {
            return;
        }
        if (typed.isEmpty()) {
            book.status("tenet.status.type_the_id_of_the_quest_to_depend_on_then_enter", true);
            book.rebuildWidgets();
            return;
        }
        if (typed.equals(target)) {
            book.status("tenet.status.a_quest_cannot_depend_on_itself", true);
            book.rebuildWidgets();
            return;
        }
        List<String> dependencies = new ArrayList<>(QuestPanelLayout.strings(quest, "dependsOn"));
        if (dependencies.contains(typed)) {
            book.status(target + " already depends on " + typed, true);
            book.rebuildWidgets();
            return;
        }
        dependencies.add(typed);
        book.sendField(target, "dependsOn", QuestBookScreen.stringArray(dependencies));
    }

    /** Folds or unfolds one of the quest panel's sections. */
    private void foldQuestSection(String key) {
        // The problems heading is not a section to put away — a folded fault is a hidden one —
        // so its press prints the report to the chat instead. That press used to toggle a fold
        // state nothing reads, which is to say it did nothing at all.
        if (ChapterPanelLayout.PROBLEMS.equals(key) && book.bookState.toolsTab == ToolsLayout.Tab.CHAPTER) {
            printProblemsToChat();
            return;
        }
        if (!questFolded.remove(key)) {
            questFolded.add(key);
        }
        book.rebuildWidgets();
    }

    /** A typed value as the wire carries it: the shape the op's reader takes back apart. */
    static JsonElement jsonOf(Object value) {
        if (value instanceof Boolean flag) {
            return new JsonPrimitive(flag);
        }
        if (value instanceof Number number) {
            return new JsonPrimitive(number);
        }
        return new JsonPrimitive(String.valueOf(value));
    }

    /** A palette row, or a colour row. */
    private void pressToolsRow(String key) {
        // The copy row before the token and palette tests: it is an action, not a selection, and it
        // takes two presses. The canvas's numbers and words are widgets now -- see `buildRowControls`
        // -- so this is what is left of the rows that are pressed rather than edited.
        if (ToolsLayout.CANVAS_COPY.equals(key)) {
            pressCanvasCopy();
            return;
        }
        // There is no branch for the canvas space row: it is the second half of a PAIR, so it is a
        // `ChoiceField` widget and its press opens a menu of the two spaces (`openChoiceMenu`). The
        // branch that used to sit here toggled the space on a press of a row that is never pressed, and
        // a second write path for one value is what this panel's rows deliberately do not have.
        String palette = ToolsLayout.paletteId(key);
        if (palette != null) {
            choosePalette(palette);
            return;
        }
        String token = ToolsLayout.tokenId(key);
        if (token != null) {
            toolsSelected = token.equals(toolsSelected) ? null : token;
            book.rebuildWidgets();
        }
    }

    /** The section's heading: folds or unfolds it. */
    private void fold(String key) {
        // The rows are about to be rebuilt: a picker anchored to one of them closes with it.
        book.closeColourPopover();
        if (key.equals(ToolsLayout.COLOUR_SECTION)) {
            toolsColoursOpen = !toolsColoursOpen;
            book.rebuildWidgets();
        }
        else if (key.equals(ToolsLayout.PALETTE_SECTION)) {
            toolsPaletteOpen = !toolsPaletteOpen;
            book.rebuildWidgets();
        }
        else if (key.equals(ToolsLayout.APPEARANCE_SECTION)) {
            toolsAppearanceOpen = !toolsAppearanceOpen;
            book.rebuildWidgets();
        }
        else if (key.equals(ToolsLayout.BOOK_SECTION)) {
            toolsBookOpen = !toolsBookOpen;
            book.rebuildWidgets();
        }
        else if (key.equals(ToolsLayout.CANVAS_SECTION)) {
            toolsCanvasOpen = !toolsCanvasOpen;
            book.rebuildWidgets();
        }
    }

    /** The palettes the tools list offers: the catalogue, in its order, with display labels. */
    private static List<ToolsLayout.Palette> paletteOptions() {
        return Themes.everything().stream()
                .map(theme -> new ToolsLayout.Palette(theme.name(), theme.displayName()))
                .toList();
    }

    /**
     * A palette press: the open chapter's on the Chapter tab, the operator's own on the book tab.
     *
     * <p>The target follows from the tab — there is no switch to move one tab's edits onto the other's
     * file — so the chapter tab's press writes the chapter's {@code theme} field: the file every player
     * sees, which the draft overlays immediately so the panel's preview follows. The book tab's press
     * is the operator's own appearance.
     *
     * <p>And the press overrides what was there, on both targets — *"when i click a theme it must
     * override everything that was there manually or already"*. On the author's own side that is
     * {@code Look.setTheme}, which drops their colour edits and radius with the switch. On the chapter's
     * side it is the patch below: a palette press means "this palette is the chapter's look", and a
     * {@code themePatch} left behind would tint the new palette silently — the chapter's raised strip or
     * dependency line staying the old colour is the same complaint one level down. Colours and corners
     * only: a patch's {@code motion} and {@code easing} are chapter behaviour rather than palette, and
     * the panel has no control for them, so clearing those would be a change the author never asked for.
     * The write goes through the same chapter op as every other field, so Ctrl+Z takes it back.
     */
    private void choosePalette(String name) {
        String chapter = QuestBookScreen.effectiveChapter();
        Theme theme = Themes.any(name);
        String label = theme != null ? theme.displayName() : name;
        if (book.bookState.themeTargetsChapter && chapter != null) {
            book.sendChapterField("theme", new JsonPrimitive(name));
            JsonObject patch = chapterThemePatchOf(chapter);
            boolean hadOverrides = patch.has("colours") || patch.has("cornerRadius")
                    || patch.has("canvasBackground");
            patch.remove("colours");
            patch.remove("cornerRadius");
            patch.remove("canvasBackground");
            sendChapterThemePatch(patch);
            book.status(Component.translatable(hadOverrides
                    ? "tenet.status.palette_chapter_reset"
                    : "tenet.status.palette_chapter", label).getString(), false);
        }
        else {
            chooseTheme(name);
            book.status(Component.translatable("tenet.status.palette_chosen", label).getString(), false);
        }
    }

    /**
     * The palette in force for what the panel is editing.
     *
     * <p>The chapter's while it is the target — defaulting to {@code default} when the chapter names
     * none, which is what a reader sees — and the author's own otherwise. What the list marks, so the
     * tick answers "what am I looking at" rather than "what did I last click".
     */
    String currentPalette() {
        String chapter = QuestBookScreen.effectiveChapter();
        if (book.bookState.themeTargetsChapter && chapter != null) {
            String theme = ClientQuestCache.chapterTheme(chapter);
            return theme != null ? theme : Themes.DEFAULT.name();
        }
        return ClientAppearance.LOOK.currentName();
    }

    /**
     * Picks a colour by name: selected, colours unfolded, and the list scrolled to its row.
     *
     * <p>What pressing a part of the sample does — *"allow me to click the things in the preview thing at
     * the top to instantly kinda get me to it in the colours menu"*. The scroll is the point of it, and it
     * happens <b>after</b> the rebuild rather than before: the row it is looking for is a slot in the layout
     * that rebuild produced.
     *
     * <p>No toggling, unlike a row in the list: a part of the sample always means "this colour" and never
     * "off", because there is nothing about pressing a button in a picture that could mean "not that one".
     */
    private void selectColour(String token) {
        // The full name rather than an import: this file names two things in Armature and the shorter alias
        // has been tried. See the same spelling in `labelOfToken`.
        if (dev.ellipog.armature.client.ui.ThemeToken.byId(token) == null) {
            return;
        }
        toolsSelected = token;
        toolsColoursOpen = true;
        book.rebuildWidgets();
        var row = book.activeLayout() == null ? null : book.activeLayout().slot(ToolsLayout.tokenKey(token));
        if (row != null) {
            book.bookState.toolsView.scrollTo(row.y() - book.bookState.toolsFrame.list().height() / 3);
        }
        book.status(labelOfToken(token) + " - " + hexOf(token), false);
    }

    /** The shape row's arrows: one step of corner radius each. */
    private void stepRadius(int delta) {
        if (book.bookState.themeTargetsChapter) {
            writeThemeRadius(net.minecraft.util.Mth.clamp(editedRadius() + delta, Look.MIN_RADIUS,
                    Look.MAX_RADIUS));
            book.status("Chapter border radius " + editedRadius()
                    + (editedRadiusChosen() ? "  (theme's own: " + editedBaseRadius() + ")" : ""), false);
            book.rebuildWidgets();
            return;
        }
        ClientAppearance.LOOK.setRadius(net.minecraft.util.Mth.clamp(ClientAppearance.LOOK.radius() + delta,
                Look.MIN_RADIUS, Look.MAX_RADIUS));
        book.status("Border radius " + ClientAppearance.LOOK.radius()
                + (ClientAppearance.LOOK.radiusChosen() ? "  (theme's own: " + editedBaseRadius() + ")" : ""),
                false);
        book.rebuildWidgets();
    }

    /** The pattern row's arrows: the next or previous kind, wrapping. Flat is one of the kinds. */
    private void cycleCanvasPattern(int delta) {
        CanvasBackground current = book.editedBackground();
        CanvasBackground.Kind[] kinds = CanvasBackground.Kind.values();
        CanvasBackground.Kind next = kinds[Math.floorMod(current.kind().ordinal() + delta, kinds.length)];
        writeThemeBackground(new CanvasBackground(next, current.space(), current.spacing(),
                current.tuning(), current.image()));
        book.status("Canvas pattern: " + ToolsPanel.patternLabel(next), false);
        book.rebuildWidgets();
    }

    /** The strip's press: the same cycle the pattern row's up-arrow makes. */
    private void pressCanvasPreview() {
        cycleCanvasPattern(1);
    }

    /** The mark-size row's arrows: one pixel a step, inside the model's own range. */
    private void stepCanvasSize(int delta) {
        CanvasBackground current = book.editedBackground();
        CanvasBackground.Tuning tuning = current.tuning();
        int size = Mth.clamp(tuning.size() + delta,
                CanvasBackground.Tuning.MIN_SIZE, CanvasBackground.Tuning.MAX_SIZE);
        writeThemeBackground(new CanvasBackground(current.kind(), current.space(), current.spacing(),
                new CanvasBackground.Tuning(size, tuning.density(), tuning.backslash()),
                current.image()));
        book.status("Canvas mark size " + size, false);
        book.rebuildWidgets();
    }

    /** The speckle density row's arrows: one cell a step, inside the model's own range. */
    private void stepCanvasDensity(int delta) {
        CanvasBackground current = book.editedBackground();
        CanvasBackground.Tuning tuning = current.tuning();
        int density = Mth.clamp(tuning.density() + delta,
                CanvasBackground.Tuning.MIN_DENSITY, CanvasBackground.Tuning.MAX_DENSITY);
        writeThemeBackground(new CanvasBackground(current.kind(), current.space(), current.spacing(),
                new CanvasBackground.Tuning(tuning.size(), density, tuning.backslash()),
                current.image()));
        book.status("Canvas speckle density " + density, false);
        book.rebuildWidgets();
    }

    /** The hatch direction row's arrows: the two diagonals, toggled. */
    private void toggleCanvasDirection() {
        CanvasBackground current = book.editedBackground();
        CanvasBackground.Tuning tuning = current.tuning();
        boolean backslash = !tuning.backslash();
        writeThemeBackground(new CanvasBackground(current.kind(), current.space(), current.spacing(),
                new CanvasBackground.Tuning(tuning.size(), tuning.density(), backslash),
                current.image()));
        book.status("Canvas hatch: " + (backslash ? "\\" : "/"), false);
        book.rebuildWidgets();
    }

    /** The fit row's arrows: tiling and covering, the two ways an image meets its rectangle. */
    private void cycleCanvasFit() {
        CanvasBackground current = book.editedBackground();
        CanvasBackground.Image image = current.image();
        CanvasBackground.Fit next = image.fit() == CanvasBackground.Fit.TILE
                ? CanvasBackground.Fit.COVER : CanvasBackground.Fit.TILE;
        writeThemeBackground(new CanvasBackground(current.kind(), current.space(), current.spacing(),
                current.tuning(),
                new CanvasBackground.Image(image.texture(), next, image.tileSize())));
        book.status("Canvas image fit: " + ToolsPanel.fitLabel(next), false);
        book.rebuildWidgets();
    }

    /** The tile row's arrows: four pixels a step, inside the model's own range. */
    private void stepCanvasTile(int delta) {
        CanvasBackground current = book.editedBackground();
        CanvasBackground.Image image = current.image();
        int tile = Mth.clamp(image.tileSize() + delta * 4,
                CanvasBackground.Image.MIN_TILE, CanvasBackground.Image.MAX_TILE);
        writeThemeBackground(new CanvasBackground(current.kind(), current.space(), current.spacing(),
                current.tuning(), new CanvasBackground.Image(image.texture(), image.fit(), tile)));
        book.status("Canvas tile " + tile + " px", false);
        book.rebuildWidgets();
    }

    /**
     * The opacity row's arrows: five percent a step of the {@code canvasPattern} token's alpha.
     *
     * <p>The step is taken in percent rather than in 0..255 so the number the row shows and the number
     * the press moves are the same quantity; the token keeps its RGB, because the row is about how loud
     * the ink is rather than about its hue -- the Ink row above is that question.
     */
    private void stepCanvasOpacity(int delta) {
        int argb = editedTheme().colour("canvasPattern");
        int percent = Mth.clamp(Math.round(((argb >>> 24) & 0xFF) / 255F * 100F) + delta * 5, 0, 100);
        int alpha = Math.round(percent / 100F * 255F);
        writeThemeToken("canvasPattern", (argb & 0x00FFFFFF) | (alpha << 24));
        book.status("Canvas pattern ink " + percent + "%", false);
        book.rebuildWidgets();
    }

    /** The spacing row's arrows: four units a step, inside the model's own range. */
    private void stepCanvasSpacing(int delta) {
        CanvasBackground current = book.editedBackground();
        int spacing = Mth.clamp(current.spacing() + delta * 4,
                CanvasBackground.MIN_SPACING, CanvasBackground.MAX_SPACING);
        writeThemeBackground(new CanvasBackground(current.kind(), current.space(), spacing,
                current.tuning(), current.image()));
        book.status("Canvas spacing " + spacing, false);
        book.rebuildWidgets();
    }

    /**
     * Copy to all chapters: two presses, with the label saying so in between.
     *
     * <h2>One op per chapter, through the chapter's own session</h2>
     *
     * <p>The current background is written into every chapter's own {@code themePatch}. The patch is
     * read and written whole, so a chapter that already had one keeps every other key it carried --
     * a palette press clears a patch, but this is an addition to each chapter's look rather than a
     * replacement of it. Each write goes through the same chapter op a field commit uses, so Ctrl+Z
     * takes the copy back one chapter at a time, which is the honest granularity for an edit that
     * touched every file.
     *
     * <p>The arming press is what puts the count in the label, and it is read from the cache at that
     * moment: the number of chapters a pack has is not a thing that changes under a reader's hands.
     */
    private void pressCanvasCopy() {
        if (!book.bookState.canvasCopyArmed) {
            // Arm, then rebuild with the guard up: the row model is what carries the armed label, and
            // the guard is what keeps this rebuild from clearing the arm it is drawing. See
            // `canvasCopyArming`.
            book.bookState.canvasCopyArmed = true;
            book.bookState.canvasCopyArming = true;
            try {
                book.rebuildWidgets();
            }
            finally {
                book.bookState.canvasCopyArming = false;
            }
            return;
        }
        disarmCanvasCopy();
        if (!book.mayEditNow()) {
            book.status("Edit mode is off, so the copy was not written", true);
            return;
        }
        CanvasBackground background = book.editedBackground();
        int updated = 0;
        for (ClientQuestCache.ChapterEntry chapter : ClientQuestCache.chapters()) {
            String id = chapter.id();
            if (id == null || id.isEmpty()) {
                continue;
            }
            JsonObject patch = chapterThemePatchOf(id);
            patch.add("canvasBackground", background.toJson());
            sendChapterThemePatch(id, patch);
            updated++;
        }
        book.status(updated + (updated == 1 ? " chapter updated" : " chapters updated"), false);
    }

    /** Undoes an armed copy: the flag, and the label a player is looking at. */
    void disarmCanvasCopy() {
        if (!book.bookState.canvasCopyArmed) {
            return;
        }
        book.bookState.canvasCopyArmed = false;
        if (book.bookState.canvasCopyButton != null) {
            book.bookState.canvasCopyButton.setMessage(Component.translatable("tenet.dev.canvas.copy"));
        }
    }

    /** The copy row's label: the armed variant while armed, the plain key otherwise. */
    private String canvasCopyLabel() {
        return book.bookState.canvasCopyArmed
                ? Labels.of("tenet.dev.canvas.copy_armed", ClientQuestCache.chapterCount())
                : "tenet.dev.canvas.copy";
    }

    /**
     * The palette the Theme tab is editing: the open chapter's composed one, or the player's own.
     *
     * <p>One expression, read by the panel's drawing and by every handler, so a swatch and the number it
     * writes can never come from different palettes.
     */
    Theme editedTheme() {
        return book.bookState.themeTargetsChapter ? QuestBookScreen.viewportTheme() : ClientAppearance.LOOK.main();
    }

    /** The radius in force for the panel's target. */
    private int editedRadius() {
        return book.bookState.themeTargetsChapter ? editedTheme().cornerRadius() : ClientAppearance.LOOK.radius();
    }

    /** Whether the target itself pins the radius, rather than inheriting the theme's. */
    private boolean editedRadiusChosen() {
        return book.bookState.themeTargetsChapter
                ? chapterThemePatchOf(QuestBookScreen.effectiveChapter()).has("cornerRadius")
                : ClientAppearance.LOOK.radiusChosen();
    }

    /** The radius the theme underneath asks for: what a Revert goes back to. */
    private int editedBaseRadius() {
        if (!book.bookState.themeTargetsChapter) {
            Theme theme = Themes.any(ClientAppearance.LOOK.currentName());
            return theme == null ? 0 : theme.cornerRadius();
        }
        // The chapter's named theme without its patch: the radius the chapter's own override replaced.
        return ChapterTheme.compose(ClientQuestCache.chapterTheme(QuestBookScreen.effectiveChapter()), null,
                ClientAppearance.LOOK.main()).cornerRadius();
    }

    /** Whether the target itself pins a canvas background, rather than inheriting the theme's. */
    private boolean editedBackgroundChosen() {
        return book.bookState.themeTargetsChapter
                ? chapterThemePatchOf(QuestBookScreen.effectiveChapter()).has("canvasBackground")
                : ClientAppearance.LOOK.backgroundChosen();
    }

    /**
     * The open chapter's theme patch as the editor has it: the pending write first, then the file's own
     * copy, then what the tree last sent — the same three-layer read {@code dependencyLinesOf} makes, for
     * the same reason: every writer rebuilds the whole object, so a pending sibling edit has to be in the
     * base or the server's whole-object write discards it.
     */
    JsonObject chapterThemePatchOf(String chapter) {
        if (chapter == null) {
            return new JsonObject();
        }
        JsonElement drafted = BookScreenState.fieldDraft.value(chapter,
                dev.ellipog.tenet.client.dev.FieldDraft.CHAPTER_OWNER,
                "themePatch");
        if (drafted != null && drafted.isJsonObject()) {
            return drafted.getAsJsonObject().deepCopy();
        }
        ClientChapterReplica.Copy copy = ClientChapterReplica.of(chapter);
        if (copy != null && copy.revision() == ClientQuestCache.treeRevision()) {
            JsonElement stored = QuestPanelLayout.get(copy.chapterTree(), "themePatch");
            if (stored != null && stored.isJsonObject()) {
                return stored.getAsJsonObject().deepCopy();
            }
        }
        JsonObject sent = ClientQuestCache.chapterThemePatch(chapter);
        return sent == null ? new JsonObject() : sent.deepCopy();
    }

    /**
     * Writes one token into whichever palette the panel is editing, or removes it when {@code argb} is
     * null.
     *
     * <p>Two destinations behind one call, because the panel's handlers must not each remember which: the
     * player's theme lives in this client's appearance file, and a chapter's patch is a chapter field
     * written through the same op every other chapter edit uses — so Ctrl+Z takes a chapter colour back
     * like any other change.
     */
    void writeThemeToken(String token, Integer argb) {
        if (!book.bookState.themeTargetsChapter) {
            if (argb == null) {
                ClientAppearance.LOOK.clearCustom(token);
            }
            else {
                ClientAppearance.LOOK.setCustom(token, argb);
            }
            return;
        }
        JsonObject patch = chapterThemePatchOf(QuestBookScreen.effectiveChapter());
        JsonObject colours = patch.has("colours") && patch.get("colours").isJsonObject()
                ? patch.getAsJsonObject("colours") : new JsonObject();
        if (argb == null) {
            colours.remove(token);
        }
        else {
            colours.addProperty(token, String.format("#%08X", argb));
        }
        if (colours.isEmpty()) {
            patch.remove("colours");
        }
        else {
            patch.add("colours", colours);
        }
        sendChapterThemePatch(patch);
    }

    /** Writes (or clears) the corner radius on the panel's target. */
    private void writeThemeRadius(Integer radius) {
        if (!book.bookState.themeTargetsChapter) {
            if (radius == null) {
                ClientAppearance.LOOK.clearRadius();
            }
            else {
                ClientAppearance.LOOK.setRadius(radius);
            }
            return;
        }
        JsonObject patch = chapterThemePatchOf(QuestBookScreen.effectiveChapter());
        if (radius == null) {
            patch.remove("cornerRadius");
        }
        else {
            patch.addProperty("cornerRadius", radius);
        }
        sendChapterThemePatch(patch);
    }

    /** Writes (or clears) the canvas background on the panel's target. */
    void writeThemeBackground(CanvasBackground background) {
        if (!book.bookState.themeTargetsChapter) {
            ClientAppearance.LOOK.setBackground(background);
            return;
        }
        JsonObject patch = chapterThemePatchOf(QuestBookScreen.effectiveChapter());
        if (background == null) {
            patch.remove("canvasBackground");
        }
        else {
            patch.add("canvasBackground", background.toJson());
        }
        sendChapterThemePatch(patch);
    }

    /** Sends a chapter's whole patch object; an empty patch is removed rather than written. */
    private void sendChapterThemePatch(JsonObject patch) {
        sendChapterThemePatch(QuestBookScreen.effectiveChapter(), patch);
    }

    /**
     * The same, aimed at a named chapter: what the copy-to-all writes each file through.
     *
     * <p>{@code EditorOp.SetChapter} carries no chapter id -- the session in the payload is what names
     * the file (see the op's own note) -- so a per-chapter write is the same op sent through a
     * different session, and the draft has to be filed under that chapter for the same reason. The
     * shape mirrors {@code sendChapterField}, which is the single-chapter original of this pair.
     */
    private void sendChapterThemePatch(String chapter, JsonObject patch) {
        JsonElement value = patch.isEmpty() ? null : patch;
        BookScreenState.fieldDraft.set(chapter, dev.ellipog.tenet.client.dev.FieldDraft.CHAPTER_OWNER,
                "themePatch", value, ClientQuestCache.treeRevision(), Util.getMillis());
        // A request per send, like every other: the reply carries its id back and is matched to it, and a
        // request the queue refused means this op must not go -- an op sent without one would be answered
        // by a reply matched to the request before it. See `ClientEditReplies.noteSent`.
        ClientEditReplies.Request request = ClientEditReplies.noteSent("");
        if (request == null) {
            return;
        }
        TenetNetworking.sendEditorOp(chapter == null ? "" : chapter,
                new EditorOp.SetChapter("themePatch", value), request.id());
    }

    /** The selected colour's value, as the field should show it — from the palette being edited. */
    private String hexOf(String token) {
        return token == null ? "" : String.format("#%06X", editedTheme().colour(token) & 0xFFFFFF);
    }

    /**
     * A hex code typed into the band's field.
     *
     * <p>Accepts what a person types: `#RRGGBB`, `RRGGBB`, `#RGB`, or `#AARRGGBB` when the alpha matters.
     * Only RGB keeps the colour's own alpha, because setting a colour to translucent by mistake is not
     * recoverable by looking at it -- and three of the forty-three tokens are translucent on purpose. The
     * reading itself is {@code HexColour}'s, where the cases are asserted; what is here is the two things
     * this screen does with the answer.
     *
     * <p>A value it cannot read is reported and changes nothing: the field goes back to the colour it
     * belongs to, so a typo costs a message rather than a broken theme.
     */
    private void applyHex(String typed) {
        String text = typed == null ? "" : typed.trim();
        if (text.isEmpty() || toolsSelected == null) {
            return;
        }
        Integer argb = HexColour.parse(text, editedTheme().colour(toolsSelected));
        if (argb == null) {
            book.status("\"" + text + "\" is not a hex colour - try #4A90D9", true);
            // The field goes back to the colour it belongs to, silently. Rebuilding the widgets here is what
            // it used to do, and a rebuild blurs the field being submitted, which submits again: the message
            // above and this line are the whole answer.
            refreshHexField();
            return;
        }
        writeThemeToken(toolsSelected, argb);
        book.status(labelOfToken(toolsSelected) + " set to " + String.format("#%08X", argb)
                + (book.bookState.themeTargetsChapter ? "  (this chapter)" : ""), false);
        refreshHexField();
    }

    /**
     * Puts the field back to the selected colour's code, without the field calling back.
     *
     * <p>{@code setValue} is the model's silent setter — no handler, no focus change — which is the whole
     * reason it exists rather than a call to {@code submit()}: a field told what to say has nothing to
     * report. The panel's colours, swatches and number are drawn from the theme every frame, so a colour
     * change needs no rebuilding at all.
     */
    private void refreshHexField() {
        if (hexField != null) {
            hexField.setValue(hexOf(toolsSelected));
        }
    }

    /** One channel stepper: eight steps of one channel of the selected colour. */
    private void nudgeChannel(String channel, int step) {
        String token = toolsSelected;
        if (token == null) {
            return;
        }
        int argb = editedTheme().colour(token);
        int shift = switch (channel) {
            case "R" -> 16;
            case "G" -> 8;
            case "B" -> 0;
            default -> 24;
        };
        int value = net.minecraft.util.Mth.clamp(((argb >>> shift) & 0xFF) + step, 0, 255);
        writeThemeToken(token, (argb & ~(0xFF << shift)) | (value << shift));
        book.rebuildWidgets();
    }

    /**
     * Undoes the edits to what the panel is about: the selected colour, or -- with nothing selected --
     * the corner radius, which has no selection of its own now that its arrows live in its row.
     */
    private void revertSelected() {
        boolean chapter = book.bookState.themeTargetsChapter;
        if (toolsSelected == null) {
            writeThemeRadius(null);
            book.status(chapter ? "Chapter border radius back to the theme's own"
                    : "Border radius back to the theme's own", false);
            book.rebuildWidgets();
            return;
        }
        if (ToolsLayout.RADIUS.equals(toolsSelected)) {
            writeThemeRadius(null);
            book.status(chapter ? "Chapter border radius back to the theme's own"
                    : "Border radius back to the theme's own", false);
            book.rebuildWidgets();
            return;
        }
        writeThemeToken(toolsSelected, null);
        book.status("Reverted " + labelOfToken(toolsSelected) + (chapter ? " in this chapter" : ""), false);
        book.rebuildWidgets();
    }

    /**
     * Writes the edits out as a theme and switches to it.
     *
     * <p>No name is passed: {@code saveAsTheme} derives one from the theme being edited, which is why a
     * save never has to be refused for want of a text field -- and the button's tooltip names the file
     * before it is written.
     */
    private void saveTheme() {
        String saved = ClientAppearance.LOOK.saveAsTheme(null);
        book.status(saved == null ? "The theme could not be written - see the log" : "Saved as " + saved,
                saved == null);
        book.rebuildWidgets();
    }

    static String labelOfToken(String token) {
        var found = dev.ellipog.armature.client.ui.ThemeToken.byId(token);
        return found == null ? token : found.label();
    }

    /**
     * Whether a point is in the author's dock.
     *
     * <h2>The column test, not a rectangle of its own</h2>
     *
     * <p>It used to be {@code toolsFrame.panel().contains(...)} behind a guard on the overlay being shut, and
     * that was a second boundary for a question the column resolver already answers. Two answers were already
     * one too many -- the guard and the drawing had to be kept in step by a comment -- and now that the dock
     * <i>is</i> a column, the resolver is the only one that can also know what a column answers during its
     * arrival and which column a folded pair is showing.
     */
    boolean inTools(double mouseX, double mouseY) {
        return book.panelColumnAt(mouseX, mouseY) == PanelKind.TOOLS;
    }

    /**
     * Disband: a confirm card when members would lose the party, two presses when solo.
     *
     * <p>A party with members in it ends for everybody, including offline members who cannot be
     * asked — so it goes through the confirm card like transfer and successor-pick do. A solo
     * owner's party ends for nobody but them, so it keeps the 3-second double-press on the button.
     * The label is changed on the control rather than by rebuilding the panel, and that is not a
     * shortcut: a rebuild goes through {@code init}, which disarms — so arming and rebuilding cannot be
     * the same press. The window lives in {@link ArmedPress}, which is where its boundaries are
     * testable; this method only decides what to do about the answer.
     */
    private void pressDisband() {
        PartyRoster roster = book.partyRoster();
        if (roster.isReal() && roster.memberCount() > 1
                && book.bookState.partyPhase == QuestBookScreen.PartyPhase.NONE) {
            openPartyPhase(QuestBookScreen.PartyPhase.CONFIRM_DISBAND, null);
            return;
        }
        if (book.bookState.disbandPress.press(net.minecraft.util.Util.getMillis())) {
            runPartyCommand("/tenet party disband");
            return;
        }
        if (book.bookState.disbandButton != null) {
            book.bookState.disbandButton.setMessage(Component.translatable("tenet.screen.party.confirm_again"));
            book.bookState.disbandButton.tooltip(List.of(Component.translatable("tenet.screen.press_again_within_three_seconds"),
                    Component.translatable("tenet.screen.everybody_keeps_the_progress_they_earned")));
        }
    }

    /** Undoes an armed Disband. A rebuild does it, and so does the window lapsing. */
    void disarmDisband() {
        book.bookState.disbandPress.disarm();
        if (book.bookState.disbandButton != null) {
            book.bookState.disbandButton.setMessage(Component.translatable("tenet.screen.party.disband"));
        }
    }

    /**
     * A panel row's key, at the position it is drawn and placed at.
     *
     * <h2>Why the viewport and not an origin</h2>
     *
     * <p>Because the panel scrolls, so "where does this row go" is the card's body origin <i>minus the
     * scroll</i>. Both halves are the viewport's, and the widget placement asks the same object the same
     * question — so a row's label, its hover wash and the button beside it cannot come from two
     * expressions that agree until the panel is scrolled.
     */
    static Slot screenSlot(Viewport body, Slot slot) {
        return new Slot(slot.key(), body.screenX(slot.x()), body.screenY(slot.y()),
                slot.width(), slot.height());
    }

    /**
     * Places one of the panel's widgets at a content slot, with the kit's own cull rule.
     *
     * <p>The scroll view is not asked to do this because it matches widgets by row key and a row may
     * hold two controls -- see {@link QuestBookScreen#partyControls}. The rule it would have applied is kept here
     * instead: map through the viewport, hide a widget the viewport cannot show, and the same
     * half-open test it uses (a row whose bottom edge is exactly at the viewport's top is off screen).
     *
     * <p>A null slot hides the widget rather than leaving it where it was: an unplaced control is one
     * whose row is not in this face at all, and leaving it on screen would be the previous panel's
     * control drawn over this one.
     */
    void placePartyWidget(net.minecraft.client.gui.components.AbstractWidget widget,
                                  Slot slot, Viewport body, int slideDx) {
        if (widget == null) {
            return;
        }
        if (slot == null) {
            widget.visible = false;
            return;
        }
        Slot onScreen = screenSlot(body, slot);
        // The rail's own arrival, added here rather than left to the slide pass: this placement
        // runs every frame from the draw, *after* `slidePanelWidgets` wrote `base + offset`, so
        // without the shift it overwrites the travelled position with the settled one and the
        // control spawns at its destination while its sheet is still gliding in. The slide pass
        // still files these widgets and still holds them inert mid-travel; its position write is
        // simply always overwritten by this one later in the same frame, so the offset counts once.
        widget.setX(onScreen.x() + slideDx);
        widget.setY(onScreen.y());
        widget.setWidth(onScreen.width());
        widget.setHeight(onScreen.height());
        widget.visible = onScreen.y() < body.viewBottom() && onScreen.bottom() > body.originY();
    }

    /** Places a face's row controls, one per control, from the row's own strip. */
    void placePartyLineControls(PartyPanelLayout.Face face, Layout layout, Viewport body,
                                        int slideDx) {
        if (face == null || layout == null) {
            return;
        }
        for (PartyPanelLayout.Line line : face.lines()) {
            if (!line.hasControls()) {
                continue;
            }
            Slot row = layout.slot(line.key());
            List<Slot> slots = row == null ? List.of() : PartyPanelLayout.controlSlots(line, row);
            for (int i = 0; i < line.controls().size(); i++) {
                placePartyWidget(book.bookState.partyControls.get(line.controls().get(i).key()),
                        i < slots.size() ? slots.get(i) : null, body, slideDx);
            }
        }
    }

    /** Places a text field in this face's row for it, if the face has that row. */
    void placePartyField(net.minecraft.client.gui.components.AbstractWidget field,
                                 PartyPanelLayout.Face face, Layout layout, String key, Viewport body,
                                 int slideDx) {
        if (field == null || face == null || layout == null) {
            return;
        }
        PartyPanelLayout.Line line = face.line(key);
        Slot row = layout.slot(key);
        placePartyWidget(field, line == null || row == null ? null
                : PartyPanelLayout.textSlot(line, row), body, slideDx);
    }

    /**
     * A header button's press: show the panel it names, or put it away if it is the one showing.
     *
     * <h2>Why the three buttons are toggles</h2>
     *
     * <p>Because the button is the panel's own name in the header, and a control that is drawn <b>lit</b>
     * while its panel is up has to answer "press me again to put it away" -- otherwise the lit state is a
     * decoration on a button that only ever opens, and the reader has to find the X or Escape for the same
     * act. So the lit state ({@code selected}) and this handler are one decision: a button that cannot be
     * un-pressed must not read as a toggle.
     *
     * <p><b>Closing means the whole panel, its child rail included.</b> A lit Party button with a picker
     * beside it takes both away, through the same one-step-back the X and Escape take
     * ({@code closePanelColumn}) -- a toggle that left a rail of the panel's own furniture standing would
     * be the "half closed" state this screen avoids everywhere else. <b>And it never means the dock</b>:
     * `leftKind()` is the open panel, and the dock is no longer the fallback occupant of a panel column, so
     * a lit header button cannot take the author's tools away.
     */
    private void togglePanel(PanelKind kind) {
        if (book.leftKind() == kind) {
            book.closePanelColumn(book.bookState.overlay2 != PanelKind.NONE);
            return;
        }
        switch (kind) {
            case PARTY -> openPartyOverlay();
            case REWARDS -> book.bookRewards.openRewardsOverlay();
            case SETTINGS -> openSettingsOverlay();
            case PINNED -> openPinnedOverlay();
            default -> {
                // The four buttons this exists for. A fifth would be a compile error here rather than a
                // press that silently did nothing, which is the shape this switch is for.
            }
        }
    }

    /**
     * Opens the pinned list at the top of both columns: the player's watchlist, beside their settings.
     *
     * <p>A root like the other header panels, through the same rules: asking for the list is asking to
     * move on. The scroll restarts at the top, because a list opened mid-way down is a list whose head
     * the player has not seen.
     */
    private void openPinnedOverlay() {
        book.applyColumns(PanelStack.asRoot(book.columns(), PanelKind.PINNED));
        book.bookState.pinnedView.scrollTo(0);
        book.rebuildWidgets();
    }

    /**
     * Opens the party panel at the top of both columns, with no question left over.
     *
     * <p>The phases and the fields are reset here rather than in a rebuild: a panel opened afresh is a
     * new interaction, and a half-typed search from ten minutes ago would make the invite list look
     * like it was missing people. The scroll reset is documented at the {@code scrollTo} call below.
     */
    private void openPartyOverlay() {
        // A question replaces the arrangement rather than being filed beside it, and it goes through the
        // rules like every other opening: the fields are the screen's, the arrangement is `PanelStack`'s.
        book.applyColumns(PanelStack.asRoot(book.columns(), PanelKind.PARTY));
        book.bookState.overlayQuest = null;
        book.bookState.partyPhase = QuestBookScreen.PartyPhase.NONE;
        partyPhaseTarget = null;
        partyRenaming = false;
        book.bookState.partySearch = "";
        book.bookState.partySearchFocused = false;
        book.bookState.partyCreateFocused = false;
        // The name a player typed for a party they no longer have must not greet them as if it were
        // that party: clearing it lets the build re-derive the default from their own name, so the
        // field is a suggestion for a new party rather than a leftover. See the create heading.
        book.bookState.partyCreateName = "";
        // Opened at the top, and reset before the rebuild rather than after: the rebuild's own `apply`
        // clamps against the new content, and an offset left from a previous party would be clamped
        // into range rather than forgotten -- a panel that opened halfway down for no visible reason.
        book.bookState.partyLeftView.scrollTo(0);
        book.bookState.partyRightView.scrollTo(0);
        book.rebuildWidgets();
    }

    /**
     * Runs a party command as this player.
     *
     * <h2>Why the panel's buttons are commands rather than a payload of their own</h2>
     *
     * <p>Because the command <b>is</b> the operation, and it is the server's. {@code /tenet party kick}
     * already checks party rank, refuses with a reason, and tells every client whose roster moved. A
     * payload doing the same three things would be a second implementation of all of it, and the two
     * would drift the first time one gained a check -- most likely the check, since a client-side "may
     * I" test is the one thing here that cannot be trusted.
     *
     * <p>So a press sends exactly what a typed command sends, through the connection the server already
     * validates. A modified client asking for an unpermitted kick gets the refusal the command would
     * have given it, and nothing changes.
     *
     * <h2>Why the panel stays open, and why the roster is not updated here</h2>
     *
     * <p>The panel used to close on every press, and that was the wrong shape for what these actions
     * are: inviting somebody, accepting an invite, changing your mind about a kick -- all of them mean
     * pressing more than one button, and a panel that shut after each press made every step cost a
     * reopen. The report was plain: <i>"stop making it close every time i do something, just auto
     * update"</i>.
     *
     * <p>So it stays open, and the redraw is not done here either. The server answers a party change by
     * pushing the new roster to everyone it concerns, the cache moves its revision when that arrives,
     * and the screen rebuilds itself -- see {@link ArmatureLive}, which is where that noticing lives now
     * that it is every panel's problem rather than this one's.
     *
     * <p>Updating the panel from the press would be the tempting alternative and the wrong one: it is a
     * second source of truth for one fact, and the one that is wrong whenever the server refuses. A
     * refused kick would be drawn as a successful one for as long as it took the real roster to land.
     */
    private void runPartyCommand(String command) {
        if (book.minecraft() == null || book.minecraft().getConnection() == null) {
            return;
        }
        // **No leading slash.** `ClientPacketListener.sendCommand` takes a bare command -- which is
        // why `ChatScreen` strips the slash before calling it -- and passing one is not harmless:
        // the server's dispatcher reads the first token as the command name, so `/tenet party create
        // X` was parsed as a command called `/tenet` and answered "Unknown or incomplete command".
        //
        // That was every button on this panel, because they all go through here. `sendCommand` rather
        // than `sendChat` for the other half of the same reason: no chat message is produced or
        // logged, because a control press is not something the player said.
        book.minecraft().getConnection().sendCommand(
                command.startsWith("/") ? command.substring(1) : command);
        // No close. The panel redraws from the roster the server sends back -- see the note above.
    }

    /**
     * Creates the sidebar's rows as widgets, over the current outline.
     *
     * <h2>The four steps, in this order, and why the order is this method's business</h2>
     *
     * <ol>
     *   <li>The outline is fetched — {@link QuestBookScreen#sidebar()} rebuilds it if a tree has arrived since the last
     *       build, which is the only thing that discards the player's collapses.</li>
     *   <li>The scroll view is cleared, so a row that no longer exists cannot linger.</li>
     *   <li>The viewport is <b>bound before anything is placed</b>. {@link ScrollView#apply} positions
     *       against the viewport, so it has to describe the current window first. This is the one place
     *       that ordering is this class's responsibility rather than a drawing pass's, because
     *       {@code init} runs before any frame has drawn.</li>
     *   <li>{@code apply} places every row from the layout and tells the viewport how tall the content
     *       is. Those are one call on purpose: the scrollbar's range and the row positions cannot
     *       disagree if they come from one computation.</li>
     * </ol>
     *
     * <h2>Why the rows are widgets and not drawn lines</h2>
     *
     * <p>Because they are the thing a click lands on. A hand-drawn row cannot take focus, cannot be
     * narrated, and has no hover state unless this class reimplements one per row — and the price of
     * getting all three is a class that moves the widget when the content scrolls, which is exactly
     * {@link ScrollView}'s job.
     *
     * <p>Each button is created at <b>0,0 with no size</b>, and that is deliberate rather than lazy: its
     * rectangle is not this method's to decide. {@code apply} sets x, y, width and height from the row's
     * slot, so a button created at its final size would be a second description of where a row goes —
     * which is the class of mistake this whole round is about.
     *
     * <h2>A heading is flat, and a chapter is filled or not</h2>
     *
     * <p>A group row gets no fill and a chapter row gets {@link ArmatureButton#selected} when it is the
     * one being shown. The heading is a heading that happens to be clickable; giving it a selection fill
     * would make it read as the current chapter — the confusion the fill exists to prevent, one level up.
     */
    void buildSidebarWidgets() {
        SidebarLayout layout = BookSidebar.sidebar();
        // Retire what the last build registered on the screen. The full `init` clears every
        // widget first, but this builder also runs on the partial path
        // (`rebuildSidebarAndCluster`), where nothing precedes it: without this each rebuild
        // leaves the previous rows and toolbar buttons behind at their stale positions,
        // drawn and clickable ghosts of the last arrangement. Departed rows need none of it:
        // they left the screen when the collapse started and are tracked separately till the
        // wipe lands (see `sidebarDeparted`).
        for (AbstractWidget widget : book.bookState.sidebarRowWidgets) {
            book.removeBookWidget(widget);
            book.bookState.buttons.remove(widget);
            book.bookState.sidebarTravelBase.remove(widget);
        }
        book.bookState.sidebarView.clear();
        book.bookState.sidebarRowWidgets.clear();

        // Built after the clears, so the buttons stay in the list they were filed in: the wipe
        // travels what this list holds, and the retire above removes what it holds, so a toolbar
        // built before the clears would be registered on the screen and tracked nowhere —
        // frozen through every slide and never removed.
        book.bookSidebar.buildSidebarToolbar();
        // The gutter only when the list scrolls: decided here, from the same rows the stack below
        // places, so the bounds the binder files two lines down and the width the rows take cannot
        // disagree — and a short list takes the gutter its scrollbar would have drawn nothing in.
        // Measured against the binder's own height (the may-edit toolbar or not), because a reader's
        // taller list fits what an author's would scroll, and the flag has to answer for the rect
        // that is actually bound rather than for the one this width is read from.
        //
        // The inline session's rows are in the count: two lines more can be what starts the scroll.
        List<SidebarLayout.Row> rows = book.bookState.inlineEdit == null ? layout.rows()
                : book.bookSidebar.withInlineRows(layout, layout.rows());
        book.bookState.sidebarReserveScrollbar = BookCanvas.sidebarReservesScrollbar(rows.size(),
                book.geometry().sidebarViewport(book.mayEditNow()).height());
        book.sidebarViewport();
        SidebarState effective = book.geometry().sidebarState();
        if (effective.collapsed()) {
            // The sliver carries no rows: hovering it peeks the box, and the box it peeks is
            // built when the peek begins. Nothing to place, and the viewport is zero-area to say so.
            return;
        }
        int width = book.geometry().sidebarViewport(book.mayEditNow(), book.bookState.sidebarReserveScrollbar).width();

        // Resolved once per rebuild, not per frame: the icons come from the cache, which only moves when
        // a tree arrives -- and a tree arriving rebuilds this list anyway.
        Map<String, BookSidebar.SidebarIcon> icons = BookSidebar.sidebarIcons();

        // And the waiting counts with them, for the same reason: a drag rebuilds these rows on every
        // step, and rows born without their suffix draw one frame of full-width title before the
        // tick sets it — a flicker on every pixel of the drag. The tick still owns updates between
        // rebuilds (see syncSidebarSuffixes); this owns the first frame.
        Map<String, Integer> waiting = book.rewardCounts().byChapter();

        for (SidebarLayout.Row row : rows) {
            if (SidebarLayout.isInlineKey(row.key())) {
                if (!QuestBookScreen.INLINE_STATUS_KEY.equals(row.key())) {
                    book.bookSidebar.placeInlineField(row);
                }
                continue;
            }
            boolean heading = row.group();
            boolean isSelected = !heading && row.id().equals(QuestBookScreen.effectiveChapter());

            ArmatureButton button = book.control(0, 0, 0, 0, Component.literal(row.label()),
                    () -> book.bookSidebar.pressSidebarRow(row.key()));

            // The row's item, when there is one: a chapter's own icon, or a group's authored icon, or
            // the first chapter under a group that authored none. A missing item leaves the row without
            // a mark rather than with an empty box, and says so on hover -- the id is kept for exactly
            // that, the same "missing is not absent" reading the quest header draws in its corner. A
            // texture draws through the button's blit rather than its stack.
            BookSidebar.SidebarIcon icon = icons.get(row.key());
            if (icon != null && !icon.stack().isEmpty()) {
                button.icon(icon.stack());
            }
            else if (icon != null && icon.textureId() != null) {
                button.texture(icon.textureId());
            }
            else if (icon != null && icon.spriteId() != null) {
                button.sprite(icon.spriteId());
            }
            else if (icon != null && !icon.id().isEmpty()) {
                button.tooltip(List.of(Component.translatable("tenet.screen.missing_item_hint")));
            }

            // Left-aligned, every row. A column of centred labels has a ragged left edge, so nothing
            // lines up and a short title floats away from the row it names -- see ArmatureButton on
            // why the alignment is the caller's decision rather than a heuristic in the control.
            button.alignLeft(true);

            if (heading) {
                // A section, not flat. Flat draws nothing at all, which is what the headings were, and
                // a row whose whole width is clickable reading as plain text is the report that came
                // back: "no like thing to make the categories look like buttons".
                button.section(true);
            }
            else {
                // A chapter whose own gate is unmet is drawn in the "blocked" ink, which is the same ink
                // the canvas draws a locked quest's tag in: one appearance for one fact, whether the gate
                // was a quest's or a chapter's.
                button.selected(isSelected)
                        .textColour(isSelected ? ArmatureTheme.title()
                                : row.locked() ? ArmatureTheme.blocked() : ArmatureTheme.body());
                // The "(3)" rewards count, as the button's suffix from birth: the button truncates
                // around it while drawing, so a row rebuilt mid-drag never shows one frame of title
                // running under a count the tick has not set yet.
                Integer waitingCount = waiting.get(row.id());
                if (waitingCount != null && waitingCount > 0) {
                    int accent = QuestBookScreen.themeFor(row.id()).inProgress();
                    button.suffix("(" + waitingCount + ")").suffixColour(
                            isSelected ? Colour.shade(accent, 0.35F) : accent);
                }
            }

            book.bookState.sidebarView.put(row.key(), button);
            book.bookState.sidebarRowWidgets.add(button);
        }

        // Filed as seen: the tick compares against these, so a build that just set every suffix
        // must not be followed by a walk that sets them again. The tick still owns every change
        // after this — a claim lands as a changed map, a chapter change as a changed selection.
        book.bookState.sidebarWaiting = waiting;
        book.bookState.sidebarSuffixChapter = QuestBookScreen.effectiveChapter();

        book.bookState.sidebarView.apply(layout.stack(rows, width), width);
    }

    /**
     * The map buttons, in the canvas's own top-left corner — and the preview's way out, top-right,
     * while a preview is showing.
     *
     * <h2>Why this is a method rather than three calls in one branch of {@code init}</h2>
     *
     * <p>Because it used to be three calls in one branch, and the branch that mattered was another one.
     * The cluster was built by the book's branch alone, so opening a panel -- which rebuilds every widget
     * -- left the canvas without its three buttons. That is a control that is <i>absent</i> rather than
     * inert, and it is what "the main UI buttons top left" being missing was: the branches that opened a
     * panel built the sidebar and the header and stopped.
     *
     * <p>So the cluster is built with the rest of the book's chrome, in the one path `init` has now, and
     * nothing is made inert with it -- a rail covers no control, so the cluster answers beside a panel
     * exactly as it does with nothing open. That is the whole of what "one presentation" bought here.
     *
     * <p>The rectangles come from the same map the overlap test walks, which is the reason the three
     * calls were written this way to begin with.
     */
    void buildViewCluster() {
        // A panel over the cluster's corner hides it: the three buttons would otherwise float on the
        // panel's own content, controlling a map nobody can see.
        Map<String, BookGeometry.Rect> controls = book.viewClusterCovered() ? Map.of() : book.geometry().controls();
        // Retire what the last build registered: this builder runs on the partial path as well
        // as in `init`, and a cluster left registered draws a second set of map buttons at its
        // old seat beside the new one. The preview pill rides along: it is nulled below, so it
        // has to be retired before that line rather than with the list.
        for (AbstractWidget widget : book.bookState.viewClusterWidgets) {
            book.removeBookWidget(widget);
            book.bookState.buttons.remove(widget);
        }
        if (previewExitButton != null) {
            book.removeBookWidget(previewExitButton);
            book.bookState.buttons.remove(previewExitButton);
        }
        book.bookState.viewClusterWidgets.clear();

        ArmatureButton zoomIn = book.control(controls.get("zoomIn"), Component.literal("+"),
                () -> book.bookCanvas.zoomCentre(1.25F));
        if (zoomIn != null) {
            zoomIn.tooltip(List.of(Component.translatable("tenet.screen.zoom_in"),
                            Component.translatable("tenet.screen.or_scroll_up_over_the_canvas")))
                    .ink(ArmatureButton.Ink.BODY);
            book.bookState.viewClusterWidgets.add(zoomIn);
        }

        // The way back out of a pill-entered player's-view preview: a pill over the canvas's
        // top-right corner, opposite the cluster (a command-entered one draws none — see the
        // branch below). Nulled here rather than with the band's fields, because this is where
        // it is built — a field nulled in another builder is a control a reorder silently drops.
        previewExitButton = null;
        // The pill is the pill-entered preview's way out; a command-entered one draws none, so
        // the canvas reads exactly as a player's. See `previewFromCommand`.
        if (book.bookCanvas.previewing() && !book.bookCanvas.previewFromCommand) {
            BookGeometry.Rect canvas = book.geometry().canvas();
            int pillWidth = BookGeometry.PREVIEW_WIDTH;
            previewExitButton = book.control(
                    canvas.right() - BookGeometry.EDGE - pillWidth,
                    canvas.y() + BookGeometry.EDGE,
                    pillWidth, BookGeometry.ROW_HEIGHT,
                    Component.translatable("tenet.screen.preview"), this::pressPreviewPill);
            if (previewExitButton != null) {
                previewExitButton.ink(ArmatureButton.Ink.BODY)
                        .selected(true)
                        .tooltip(List.of(Component.translatable("tenet.screen.preview_on"),
                                Component.translatable("tenet.screen.preview_on_hint")));
            }
        }

        ArmatureButton zoomOut = book.control(controls.get("zoomOut"), Component.literal("\u2212"),
                () -> book.bookCanvas.zoomCentre(0.8F));
        if (zoomOut != null) {
            zoomOut.tooltip(List.of(Component.translatable("tenet.screen.zoom_out"),
                            Component.translatable("tenet.screen.or_scroll_down_over_the_canvas")))
                    .ink(ArmatureButton.Ink.BODY);
            book.bookState.viewClusterWidgets.add(zoomOut);
        }

        // A glyph rather than the word "Centre", because it is an 18-pixel square: "Centre" in that
        // box would be cut off by the button's own font measurement -- and it was that measurement that
        // fixed the chapter titles, so the fix here is to pass a label that fits rather than to widen
        // the control back out. The tooltip carries the word. The glyph is a house -- "back to where the
        // view started" -- which is one of the few symbols this font has; the fisheye it used to be was
        // not, and drew as the missing-glyph box. See `BookGeometry.TOOLS_PILL_WIDTH`.
        ArmatureButton centre = book.control(controls.get("centre"), Component.literal("\u2302"), () -> {
            BookScreenState.centred = false;
            book.bookCanvas.centreCanvas();
        });
        if (centre != null) {
            centre.tooltip(List.of(Component.translatable("tenet.screen.re_centre_the_view"),
                            Component.translatable("tenet.screen.drag_with_left_or_middle_to_pan")))
                    .ink(ArmatureButton.Ink.BODY);
            book.bookState.viewClusterWidgets.add(centre);
        }
    }

    /**
     * The two controls in the header: Close and the party button.
     *
     * <h2>Why this is a method rather than a block in one branch of `init`</h2>
     *
     * <p>Because all three branches need it, and only one of them had it. The header chrome was built
     * by the branch that builds the book, so opening a modal -- which clears every widget and builds
     * the modal's controls instead -- left both fields pointing at controls that were no longer in
     * `children`. That is why Close disappeared exactly when a dialog was open: the one control every
     * dialog has, gone at the moment it was wanted.
     *
     * <h2>Both are ordinary controls, and that is a fix</h2>
     *
     * <p>They are in the header, and the widget pass is clipped to the sidebar's column so that a
     * scrolled row cannot be drawn through the title bar. That clip used to span the whole panel, which
     * swallowed these two: they were drawn by hand in the raised layer instead -- and a control drawn by
     * hand is one the base class never tells about the pointer, so neither of them ever faded under it.
     * The clip is the sidebar's column now, and the pass draws them like everything else.
     */
    void buildHeaderChrome() {
        Map<String, BookGeometry.Rect> controls = book.geometry().controls();
        chromeControls.clear();

        // Close closes one step of the arrangement, and the book when there is nothing left to close: the
        // same ladder Escape walks, in the same order, so the key and the control are one gesture with two
        // ways to reach it.
        book.bookState.closeButton = book.control(controls.get("close"), Component.literal("\u00d7"), () -> {
            if (book.bookState.overlay != PanelKind.NONE) {
                // Outermost first: the X is the same step back Escape takes, so a player with a picker over
                // a quest loses the picker and keeps the quest.
                book.closePanelColumn(book.bookState.overlay2 != PanelKind.NONE);
            }
            else if (book.bookState.dockOpen) {
                // **The dock is on the ladder too.** It used to be that the X closed the whole book while
                // the dock was up, which is the one thing an author with their tools open does not mean by
                // it. The pill and Escape put it away; so does this.
                book.pressAuthorPill();
            }
            else {
                book.onClose();
            }
        });
        chrome(book.bookState.closeButton);
        if (book.bookState.closeButton != null) {
            book.bookState.closeButton.ink(ArmatureButton.Ink.BODY);
        }

        // Labelled here rather than by the drawing, and that is a fix rather than a preference. The
        // strip this replaces had its message written in `drawBook`, which runs before the widget pass
        // within a frame -- so the first frame after `init` drew an empty control, and any frame where
        // the widget pass came first drew nothing at all. The report was exactly that: no text on the
        // party button.
        //
        // What changes with the roster is not the label -- "Party" says the same thing whoever is in it
        // -- but the tooltip, and a stale tooltip costs a hover line where a stale label cost the whole
        // control.
        book.bookState.partyButton = book.control(controls.get("party"),
                Component.translatable("tenet.screen.party.button"), () -> togglePanel(PanelKind.PARTY));
        chrome(book.bookState.partyButton);
        if (book.bookState.partyButton != null) {
            book.bookState.partyButton.ink(ArmatureButton.Ink.BODY).selected(book.leftKind() == PanelKind.PARTY);
        }

        // The rewards button, between Close and Party -- the spot `BookGeometry.partyButton` was anchored
        // for. Built for every player, like Party: claiming is a player's business rather than an author's,
        // so it is not gated the way Edit and its gear are. What changes with state is the tooltip, not the
        // label -- the same rule the party button's own comment states, and for the same reason.
        book.bookState.rewardsButton = book.control(controls.get("rewards"),
                Component.translatable("tenet.screen.rewards.button"),
                () -> togglePanel(PanelKind.REWARDS));
        chrome(book.bookState.rewardsButton);
        if (book.bookState.rewardsButton != null) {
            book.bookState.rewardsButton.ink(ArmatureButton.Ink.BODY).selected(book.leftKind() == PanelKind.REWARDS);
            int waiting = book.bookRewards.claimableQuests().size();
            book.bookState.rewardsButton.tooltip(Component.translatable(waiting == 0
                    ? "tenet.screen.rewards.none" : "tenet.screen.rewards.waiting", waiting));
        }

        // The settings button, for every player -- the whole point of the card it opens. How large the text
        // draws is about the player's own body rather than the pack's look, so it is the one appearance row
        // that cannot live behind edit permission with the palette and the corner radius.
        settingsHeaderButton = book.control(controls.get("settings"),
                Component.translatable("tenet.screen.settings"),
                () -> togglePanel(PanelKind.SETTINGS));
        chrome(settingsHeaderButton);
        if (settingsHeaderButton != null) {
            settingsHeaderButton.ink(ArmatureButton.Ink.BODY).selected(book.leftKind() == PanelKind.SETTINGS)
                    .tooltip(Component.translatable("tenet.screen.settings_hint"));
        }

        // The pinned quests button, beside Settings for every player: the watchlist next to the card
        // that says how it reads. Built for every player like the three beside it, because pins are a
        // player's own business rather than an author's.
        pinnedHeaderButton = book.control(controls.get("pinned"),
                Component.translatable("tenet.screen.pinned.button"),
                () -> togglePanel(PanelKind.PINNED));
        chrome(pinnedHeaderButton);
        if (pinnedHeaderButton != null) {
            pinnedHeaderButton.ink(ArmatureButton.Ink.BODY).selected(book.leftKind() == PanelKind.PINNED)
                    .tooltip(Component.translatable("tenet.screen.pinned.tip"));
        }

        // The author's band, and its controls exist only for a player who may edit the questline -- the same
        // permission `/tenet reload` asks for, which is what makes "who may edit" one rule rather than two.
        // A player who is not an operator never has them built at all, so their screen carries no trace of
        // authoring: the same header as everyone, no band, and a canvas with only the view cluster on it.
        //
        // Five controls, in the author's reading order: the dock they work in, the pack's own files,
        // the mode, the depth, and the player's view. Author and Assets are surfaces rather than latches --
        // the dock's own button puts it away, and the Assets panel's way out closes it -- while Edit,
        // Advanced and Preview are the three latches over how the editor behaves.
        //
        // Nulled before the guard rather than inside it, which is the pills' own arrangement and matters:
        // `mayEdit()` is read live, so a player de-opped while the book is open would otherwise keep five
        // fields pointing at widgets that have been cleared, and `render` draws a field, not its
        // membership. A previewing operator's geometry has no band either, so the guard keeps them out
        // too — the way back out of a preview is the floating pill, not a sixth press in a strip that
        // is not drawn.
        authorButton = null;
        assetsButton = null;
        editButton = null;
        advancedButton = null;
        previewButton = null;
        if (mayEdit() && !book.bookCanvas.previewing()) {
            // The dock, which used to be what edit mode drew rather than a control of its own. See
            // `pressAuthorPill` for why the Edit button had to stop meaning two things, and for the one
            // transition that opens and closes this rail.
            authorButton = book.control(controls.get("author"),
                    Component.translatable("tenet.screen.author"), book::pressAuthorPill);
            chrome(authorButton);
            if (authorButton != null) {
                authorButton.ink(ArmatureButton.Ink.BODY)
                        .selected(book.bookState.dockOpen)
                        .tooltip(List.of(Component.translatable("tenet.screen.the_author_s_dock"),
                                Component.translatable("tenet.screen.the_book_and_chapter_tabs"),
                                Component.translatable("tenet.screen.press_again_to_put_it_away")));
            }
            // Beside it, and for the same reader: the pack's own files, which no quest has to be open to
            // reach. Not a latch -- it opens a panel, and the panel's own way out closes it.
            assetsButton = book.control(controls.get("assets"),
                    Component.translatable("tenet.screen.assets"), book.bookAssets::openAssets);
            chrome(assetsButton);
            if (assetsButton != null) {
                assetsButton.ink(ArmatureButton.Ink.BODY)
                        .tooltip(List.of(Component.translatable("tenet.screen.the_packs_own_files"),
                                Component.translatable("tenet.screen.tables_quests_and_types"),
                                Component.translatable("tenet.screen.ctrl_t_opens_this")));
            }
            // And the mode, which latches edit mode and nothing else -- the drawer it used to open and
            // close as a side effect has its own button now.
            editButton = book.control(controls.get("edit"),
                    Component.literal("\u270E ").append(Component.translatable("tenet.screen.edit")),
                    this::pressEditPill);
            chrome(editButton);
            if (editButton != null) {
                editButton.ink(ArmatureButton.Ink.BODY)
                        .selected(DevMode.on())
                        .tooltip(List.of(Component.translatable("tenet.screen.edit_this_questline"),
                                Component.translatable("tenet.screen.drag_nodes_create_duplicate_delete"),
                                Component.translatable("tenet.screen.ctrl_z_undoes_every_edit_is_saved")));
            }
            // And the depth, last, and only while the mode is on: with the editor closed there is nothing
            // for a shallower view to apply to, and a latch that changed nothing would be a control that
            // lies. Its own label is the state -- lit means every menu is showing everything.
            if (DevMode.on()) {
                advancedButton = book.control(controls.get("advanced"),
                        Component.translatable("tenet.screen.advanced"),
                        () -> setAdvanced(!DevMode.advanced()));
                chrome(advancedButton);
                if (advancedButton != null) {
                    advancedButton.ink(ArmatureButton.Ink.BODY)
                            .selected(DevMode.advanced())
                            .tooltip(DevMode.advanced()
                                    ? List.of(Component.translatable("tenet.screen.advanced_on"),
                                            Component.translatable("tenet.screen.advanced_on_hint"))
                                    : List.of(Component.translatable("tenet.screen.advanced_off"),
                                            Component.translatable("tenet.screen.advanced_off_hint")));
                }
                // And the player's view, last, and only while the mode is on and no preview is showing,
                // for the same reason as the depth: with the editor closed the book already shows the
                // player's content, so a latch that changed nothing would be a control that lies. This is
                // the way in; the way back out is the floating pill, because pressing this rebuilds the
                // book into a geometry with no band in it.
                previewButton = book.control(controls.get("preview"),
                        Component.translatable("tenet.screen.preview"), this::pressPreviewPill);
                chrome(previewButton);
                if (previewButton != null) {
                    previewButton.ink(ArmatureButton.Ink.BODY)
                            .selected(false)
                            .tooltip(List.of(Component.translatable("tenet.screen.preview_off"),
                                    Component.translatable("tenet.screen.preview_off_hint")));
                }
            }
        }
    }

    /**
     * The controls the chrome draws by hand: filled by {@link #buildHeaderChrome} as it builds them, drawn
     * in one pass in the chrome layer of {@code render}.
     *
     * <h2>One list rather than eight lines, and the two that were lost</h2>
     *
     * <p>Every control above the widget pass's clip has to be drawn by hand, because the pass cannot reach
     * it -- and that list was written out by hand for two rounds, which cost two controls: the rewards
     * button and then the settings button were each built, added as widgets and clickable from the day they
     * landed, and invisible, because nothing on the drawing side knew about them. The band added five more
     * controls to that set, and five more lines to remember is how the third one gets lost.
     *
     * <p>So the build site <i>is</i> the draw list: {@link #chrome} adds each control as it is built, and
     * the chrome layer walks this. A control built for the chrome is painted by construction, and a control
     * built for the body is not in here to be painted twice.
     */
    final List<ArmatureButton> chromeControls = new ArrayList<>();

    /** Remembers a control the chrome must draw, if it was built at all. See {@link #chromeControls}. */
    private void chrome(ArmatureButton button) {
        if (button != null) {
            chromeControls.add(button);
        }
    }

    /**
     * Edit mode on or off, with the latch following.
     *
     * <p>The inspector drawer used to follow too, because it <i>was</i> edit mode. It is the Author button's
     * now — see {@link QuestBookScreen#pressAuthorPill} — so this method no longer opens or closes a column on the way in,
     * and only puts the arrangement away on the way out.
     *
     * <p>Switching either way closes the Tools menu: a menu anchored to a control that is about to vanish
     * (turning edit off) would hang over the canvas with nothing to belong to, and leaving it open while
     * the drawer slides in puts a menu over the panel it is about to describe.
     */
    private void setEditing(boolean on) {
        DevMode.setOn(on);
        book.bookMenu.closeMenu();
        book.closeColourPopover();
        if (!on) {
            // The preview goes with the mode: it is only built while the mode is on, and a latch kept
            // past it would greet the mode's return already lit — a preview nobody asked to resume.
            book.bookCanvas.previewAsPlayer = false;
            book.bookCanvas.previewFromCommand = false;
            // **And turning edit mode off puts the arrangement away.** Not because a child could outlive its
            // parent -- `overlay`/`overlay2` make that impossible by themselves now that the dock is not the
            // fallback occupant -- but because the panels are the mode's surfaces: leaving the mode with a
            // card still over the canvas would leave the author in a panel whose every field had just gone
            // inert. The same transition `closeOverlay` uses, so both columns go together.
            //
            // The dock is deliberately not in this: it is not a column occupant of its own, it is the
            // fallback one, and it belongs to the Author button rather than to the mode.
            book.applyColumns(PanelStack.afterClose(book.columns(), false));
        }
        book.bookInput.authorReport(on ? "Edit mode on" : "Edit mode off");
        book.rebuildWidgets();
    }

    /**
     * The editor's depth: everything, or the basic set of every menu.
     *
     * <h2>What this deliberately does not do</h2>
     *
     * <p>It does not touch the arrangement. Every other latch over the screen's state closes something on
     * the way out — {@link #setEditing} puts the columns away, {@link QuestBookScreen#pressAuthorPill} is a rail's own
     * switch — and this one is the exception for a reason: a depth is not a surface. An author who presses
     * it while standing in a table's editor, a chapter's rows or a node's settings page should still be
     * standing there afterwards, one press shallower. Closing a panel because the rows inside it changed
     * would make a control over what is drawn into a control over what is open.
     *
     * <h2>The two caches it has to drop</h2>
     *
     * <p>Rebuilding the widgets covers every panel whose rows are built there — the dock's two tabs, the
     * settings page, the Assets list — because those rows are one field each, rebuilt from the same call
     * that draws them and hit-tests them.
     *
     * <p>The editor card is the one that would otherwise go stale, so it is dropped here:
     * {@link #editorCard} caches an entry's <b>line counts</b> under a key of ids, revisions, widths and
     * the text epoch, and the depth changes how many lines a form takes without changing any of those. A
     * card left in place would place the rows below it against the heights of the other depth.
     */
    private void setAdvanced(boolean on) {
        DevMode.setAdvanced(on);
        editorCard = null;
        editorCardKey = null;
        // A page that only exists at one depth: the Assets panel remembers the section the author was
        // reading, and a section this depth does not show would draw a list with nothing selected over it.
        book.bookAssets.assetsSection = dev.ellipog.tenet.client.dev.AssetsLayout.shownOrFirst(book.bookAssets.assetsSection);
        book.bookInput.authorReport(on ? "Advanced mode on" : "Advanced mode off");
        book.rebuildWidgets();
    }

    /**
     * Builds the controls of every rail that is on screen, and the fold that pages between the pair.
     *
     * <h2>One call, driven by the rules</h2>
     *
     * <p>{@link PanelStack#presented} names what is drawn, and it is the same list here, so a rail the
     * player cannot see cannot register a control either — which is what keeps a hidden parent's footer
     * from landing in the rectangle the shown child occupies. It is also how the three rails arrive
     * together: the dock, the panel and the child are built by one pass, so the dock's controls exist
     * whether or not a panel is open beside it.
     */
    void buildColumns() {
        // **The label-scrub list is cleared here, once per rebuild, and not by the builder that fills it.**
        // Two columns build in one pass -- the dock and the element panel -- and each adds its own numeric
        // rows, so a clear inside the shared builder was whichever panel built second evicting the other's
        // fields: a press on the dock's label then armed nothing.
        book.bookState.rowFields.clear();
        List<PanelKind> shown = PanelStack.presented(book.columns(), book.panelFolded());
        // Backwards, so the outermost rail is built first: that is the order these were always built in, and
        // the order the widget pass walks -- a press that somehow landed on two belongs to the panel the
        // player opened rather than to the dock it came from.
        for (int i = shown.size() - 1; i >= 0; i--) {
            buildColumn(shown.get(i));
        }
        // And the one press that pages between them. A no-op unless a child is showing.
        buildFoldControl();
        // Restored to the first panel, which is what the frame reads when no surface has claimed it: the
        // loop above set it to whichever rail it built last.
        book.bookState.surfaceKind = book.bookState.overlay;
    }

    /**
     * One presented rail's own controls, by kind.
     *
     * <p>The switch that used to be spread across the overlay branches of {@code init}: a column is built
     * from <b>what it holds</b>, never from which column it happens to be. {@link PanelKind#ASSETS} answers
     * with nothing of its own, because its rows are drawn and hit-tested from {@code AssetsLayout} the way
     * the table panels' are.
     *
     * <p>Built at once, on the frame the rail opens — not on the frame it settles. The rail arrives as a
     * slide rather than a wipe with its controls missing: the widgets are placed at their built positions
     * and the per-frame slide moves them with the surface, inert until they land. The bucket is what files
     * them under this kind; everything outside a column builds with it null and never travels.
     */
    private void buildColumn(PanelKind kind) {
        // Set first, because everything inside reads it: the four surface accessors, the table bodies, the
        // field's own box. A column built from another column's rectangle is a control drawn in one place
        // and hit in another, which is this file's oldest fault.
        book.bookState.surfaceKind = kind;
        PanelKind outer = book.bookState.slideBucket;
        book.bookState.slideBucket = kind;
        try {
            switch (kind) {
                case QUEST, PICKER, TEXTURE -> buildOverlayWidgets(kind);
                case TOOLS -> buildToolsWidgets();
                case PARTY -> buildPartyWidgets();
                case REWARDS -> book.bookState.rewardControls = book.bookRewards.buildRewardWidgets();
                case NAMING -> book.bookSidebar.buildNamingWidgets();
                case TABLE_BROWSER -> book.bookRewards.buildTableBrowserWidgets();
                case TABLE_EDITOR -> book.bookRewards.buildTableEditorWidgets();
                case SETTINGS -> buildSettingsWidgets();
                case PINNED -> buildPinnedWidgets();
                case ELEMENT -> buildElementWidgets();
                case FILTER -> buildFilterWidgets();
                case ASSETS, NONE, CHOICE -> {
                    // Nothing of its own: the assets list is drawn from its layout, and a choice is a card,
                    // which builds beside the columns rather than in one.
                }
            }
        }
        finally {
            book.bookState.slideBucket = outer;
        }
    }

    /**
     * Builds one column's own controls.
     *
     * <h2>Why this takes a kind rather than reading the field</h2>
     *
     * <p>Because with two columns the field names the first one and the second column's controls have to be
     * built in the same pass — a quest panel's footer and a picker's Back button are on the screen at once.
     * Everything inside reads {@link QuestBookScreen#surfaceKind}, which this sets first, so a control is placed against the
     * column it belongs to rather than against whichever column the screen happens to call the first.
     */
    private void buildOverlayWidgets(PanelKind kind) {
        book.bookState.surfaceKind = kind;
        // The texture picker's card, before the item picker's and for the same reason: it is an overlay
        // that is not about a quest, so the entry lookup below would close it on the frame it opened.
        if (kind == PanelKind.TEXTURE) {
            book.texturePicker.buildTextureWidgets();
            // The same single Back control the item picker's card carries: Escape and a press outside
            // also leave, and the card's own footer rectangle keeps the two cards' controls in line.
            ArmatureButton back = book.control(book.overlayControls(false).get("back"),
                    Component.translatable("tenet.screen.back"), book.texturePicker::closeTexturePicker);
            if (back != null) {
                back.ink(ArmatureButton.Ink.BODY)
                        .tooltip(Component.translatable("tenet.screen.escape_also_closes_this"));
            }
            return;
        }

        // The picker's own overlay first: it is the one overlay that is not about a quest, so it must
        // not be asked for an entry -- the test below is what would otherwise close it on the frame it
        // opened, because a chapter icon pick has no quest behind it.
        if (kind == PanelKind.PICKER) {
            // The card's own state is re-read from the tree here, not only when the row was pressed.
            // `tick` rebuilds for this overlay when a replica arrives -- that is the point of the rebuild
            // -- so a header, an icon or a Current row left from the tree as it stood when the row was
            // pressed would be exactly the staleness the rebuild exists to end, and pressing a stale
            // Current row would write the old id back over the new one.
            if (book.bookState.pickTarget == QuestBookScreen.PickTarget.CHAPTER) {
                JsonObject chapter = BookScreenState.fieldDraft.overlaid(QuestBookScreen.effectiveChapter(),
                        dev.ellipog.tenet.client.dev.FieldDraft.CHAPTER_OWNER,
                        ClientChapterReplica.chapterTree(QuestBookScreen.effectiveChapter()));
                readChapterIdentity(chapter);
                book.bookState.pickIcon = chapterIcon;
                book.bookState.pickTexture = iconString(chapter, "texture");
                book.bookState.pickSprite = iconString(chapter, "sprite");
                book.bookState.pickName = chapterHeader.title();
                book.bookState.pickingItemCurrent = chapterIconId;
            }
            else if (book.bookState.pickTarget == QuestBookScreen.PickTarget.GROUP) {
                ChapterPanelLayout.GroupInfo group = chapterGroupInfo(QuestBookScreen.effectiveChapter());
                if (group != null) {
                    book.bookState.pickIcon = groupIconStack(group);
                    book.bookState.pickTexture = "";
                    book.bookState.pickSprite = "";
                    for (ClientQuestCache.GroupEntry listed : ClientQuestCache.groups()) {
                        if (listed.id().equals(group.id())) {
                            book.bookState.pickTexture = listed.textureIcon();
                            book.bookState.pickSprite = listed.spriteIcon();
                            break;
                        }
                    }
                    book.bookState.pickName = group.title();
                    book.bookState.pickingItemCurrent = group.iconId();
                }
            }
            else if (book.bookState.pickTarget == QuestBookScreen.PickTarget.BOOK) {
                // No file to re-read: the tree's own values are the current ones, and the rebuild this
                // sits in is what keeps them from going stale.
                book.bookState.pickIcon = ClientQuestCache.bookIcon();
                book.bookState.pickTexture = ClientQuestCache.bookTextureIcon();
                book.bookState.pickSprite = ClientQuestCache.bookSpriteIcon();
                book.bookState.pickName = book.bookTitle().getString();
                book.bookState.pickingItemCurrent = ClientQuestCache.bookIconId();
            }
            buildPickerWidgets();
            // One control, because the card has no other: Escape and a press outside also leave, and a
            // picker whose only way out is a key nobody was told about reads as a trap. The reader's
            // Back is drawn on the rectangle the panel's other footers use, so the panels line up.
            ArmatureButton back = book.control(book.overlayControls(false).get("back"),
                    Component.translatable("tenet.screen.back"), book.itemPicker::closePickerOverlay);
            if (back != null) {
                back.ink(ArmatureButton.Ink.BODY)
                        .tooltip(Component.translatable("tenet.screen.escape_also_closes_this"));
            }
            return;
        }

        ClientQuestCache.Entry entry = book.entryFor(book.bookState.overlayQuest);
        if (entry == null) {
            // The quest this column is about is gone from the tree, so the whole arrangement goes with it:
            // a child that survived its parent's quest would be a picker picking for nothing.
            book.applyColumns(PanelStack.afterClose(book.columns(), false));
            book.rebuildWidgets();
            return;
        }

        // The back arrow, in the header's empty right-hand side and above the card like every modal
        // control. A prerequisite chain is four deep before anyone notices they cannot step back
        // without losing the thread, and the footer's Back leaves the whole card — so one press per
        // level, like a browser. Built for the reader and the editor alike: the editor's header marks
        // are drawn on top of this band, so the two must not share a corner.
        BookGeometry.Rect card = book.surfaceCard(PanelKind.QUEST);
        ArmatureButton backArrow = book.control(card.right() - BookGeometry.MODAL_INSET - 20, card.y() + 13,
                20, 20, Component.literal("\u2190"), this::backInCards);
        if (backArrow != null) {
            // Inert while there is nothing to go back to, rather than hidden: a control that appears
            // and disappears with the stack would move under the pointer that is using it.
            backArrow.active = !book.bookState.overlayHistory.isEmpty();
            backArrow.ink(ArmatureButton.Ink.BODY)
                    .tooltip(Component.translatable("tenet.screen.back_to_the_quest_you_came_from"));
        }

        // The pin, left of the back arrow and on the same band. It is here rather than in the footer because
        // the footer is about the quest's *progress* -- Submit, Claim -- while this is about the reader's own
        // screen, which is the same distinction the settings card draws; and it is here rather than in the
        // editor's header marks because **an operator reads every quest in the editor**: a reader-only
        // control would be one the person most likely to pin a quest could never press. The editor's marks
        // hug their own text at the other end of this band, so the two do not share a corner -- which is the
        // same reason the back arrow is placed before either of them.
        //
        // The star is the font's own, verified present in the measured glyph list: the two characters are
        // what a player reads as "pinned" and "not", and a drawn texture here would be new art for a control
        // whose whole state is one bit. `CheckGlyphs` scans literals, so this is the one place the pair is
        // written. It is deliberately not the viewer's star: that one means "favourite in EMI's sidebar",
        // and the tooltip below is what tells the two apart.
        boolean pinned = PinnedQuests.isPinned(entry.id());
        ArmatureButton pin = book.control(headerPinSlot(card), Component.literal(pinned ? "\u2605" : "\u2606"),
                () -> pressPin(entry.id()));
        if (pin != null) {
            pin.accent(pinned)
                    .tooltip(Component.translatable(pinned
                            ? "tenet.screen.unpin_from_hud"
                            : "tenet.screen.pin_to_hud"));
        }

        // An author opening a quest gets the editor, not the reader: the same card, with the fields
        // editable in place and the gameplay buttons gone. The reader's view is untouched for everyone
        // who may not edit.
        if (book.mayEditNow()) {
            buildQuestEditorWidgets(entry);
            return;
        }
        book.bookState.questDeleteButton = null;

        // The footer, from BookGeometry's control map -- the same source the dashboard uses, and the
        // same one BookGeometryTest asserts on. See the comment in init() for why that sharing is the
        // point rather than a nicety.
        //
        // The overlay is where the mismatch was real: this method used to pass 130 for Submit's width
        // while BookGeometry said 120, so the overlap test would have gone on passing while the control
        // on screen ran into Back.
        // Submit and Claim share the bottom-left control, and the geometry is asked for a control if
        // *either* of them wants one. They cannot both want it: Submit needs a task a player can hand
        // over, which means the quest is unfinished, and Claim needs the quest to be finished. So it is
        // one rectangle with two possible meanings rather than two controls fighting over one corner --
        // which is precisely the collision BookGeometry exists to prevent.
        int taskIndex = book.bookAssets.firstManualTask(entry);
        // Per player: a teammate having collected their copy must not hide this player's button.
        java.util.UUID self = book.minecraft().player == null ? null : book.minecraft().player.getUUID();
        boolean claimable = self != null && book.claimableWithMarks(self, entry);
        book.bookSidebar.readerFooterTaskIndex = taskIndex;
        book.bookSidebar.readerFooterClaimable = claimable;
        Map<String, BookGeometry.Rect> controls = book.overlayControls(taskIndex >= 0 || claimable);

        if (claimable) {
            // Collecting a finished quest's rewards. No permission, no confirmation: it is the player
            // asking for something the server already knows they earned, and the server re-checks that
            // before handing anything over -- so a client that shows this wrongly gets a refusal.
            ArmatureButton claim = book.control(controls.get("submit"),
                    Component.translatable("tenet.screen.quest_book.claim"),
                    () -> book.bookAssets.claim(entry.id()));
            if (claim != null) {
                claim.accent(true)
                        .tooltip(BookRewards.claimTooltip(entry.id()));
                // The first reward's own icon, when there is one to draw. A Claim button wearing the
                // thing it is about says what it is for without a word of label.
                if (!entry.rewards().isEmpty()) {
                    ClientQuestCache.RewardEntry first = entry.rewards().get(0);
                    claim.icon(first.hasItem() ? first.item() : first.icon());
                }
            }
        }
        else if (taskIndex >= 0) {
            final String questId = entry.id();
            final int index = taskIndex;
            ArmatureButton submit = book.control(controls.get("submit"),
                    Component.translatable("tenet.screen.quest_book.submit"),
                    () -> book.bookAssets.submit(questId, index));
            if (submit != null) {
                submit.accent(true)
                        .icon(entry.tasks().get(index).icon())
                        .tooltip(List.of(Component.translatable("tenet.screen.quest_book.submit_hint"),
                                Component.translatable("tenet.screen.the_server_checks_the_items_are_really_there")));
            }
        }

        ArmatureButton back = book.control(controls.get("back"), Component.translatable("tenet.screen.back"),
                book::closeOverlay);
        if (back != null) {
            back.ink(ArmatureButton.Ink.BODY)
                    .tooltip(Component.translatable("tenet.screen.escape_also_closes_this"));
        }
    }

    void buildItemSearch() {
        // Nothing to place when no pick is armed -- and nothing to place for the *card* when the pick is the
        // second column: the box belongs to the surface the list is drawn on, and that pass builds it. Two
        // boxes, one over the card's body and one over the rail, is what forgetting this looks like.
        if (book.bookState.pickingItemPath == null || (book.itemPicker.pickerAsColumn()
                && book.bookState.surfaceKind != PanelKind.PICKER)) {
            book.bookState.itemSearch = null;
            return;
        }
        BookGeometry.Rect bodyRect = BookGeometry.Rect.at(book.overlayBody().originX(),
                book.overlayBody().originY(), book.overlayBody().viewWidth(), book.overlayBody().viewHeight());
        ItemPickerLayout.Frame frame = ItemPickerLayout.Frame.of(bodyRect);
        String kept = book.bookState.itemSearch == null ? "" : book.bookState.itemSearch.value();
        book.bookState.itemSearch = new ArmatureTextField(frame.search().x(), frame.search().y(),
                frame.search().width(), frame.search().height(), kept);
        // Enter and Escape are the screen's while the picker is open -- see `keyPressed` -- so a blur
        // must commit nothing: a rebuild that blurred the box would otherwise set the field to the
        // text it happened to be holding.
        book.bookState.itemSearch.onSubmit(text -> { });
        book.addRenderableWidget(book.bookState.itemSearch);
        // **The placeholder, after the widget pass.** The field itself needs nothing: a rail is drawn before
        // the pass, so the pass paints the box on top of it. The text over that box does need drawing here,
        // because it is ink no widget owns -- and only while the box is empty, which is the whole of what a
        // placeholder is.
        book.bookState.panelOverlays.add(r -> {
            // Null-guarded like the table panels' boxes: the field and its ink are filed together, but
            // a close between the build and this draw must never be a crash — see `closeItemPicker` for
            // the window this guards.
            if (book.bookState.itemSearch == null || !book.bookState.itemSearch.value().isEmpty()) {
                return;
            }
            try (GuiRenderer.Scoped clip = r.clip(bodyRect.x(), bodyRect.y(), bodyRect.right(),
                    bodyRect.bottom())) {
                // The box's own question, per tab: on a picture pick the box filters files, takes a
                // sprite id, or filters mobs — and "search items" on those tabs would be the box
                // lying about what it does.
                //
                // Read off the box rather than the frame it was built from: the rail slides in with its
                // widgets, so ink that stayed at the built position would greet the box a rail-width away.
                String hint = "Search items \u2014 name or id";
                if (book.pickerTabsVisible()) {
                    hint = switch (book.bookState.pickerKind) {
                        case FILE -> Labels.of("tenet.dev.texture.hint");
                        case SPRITE -> Labels.of("tenet.dev.picker.sprite_hint");
                        case ENTITY -> Labels.of("tenet.dev.picker.entities_hint");
                        case ITEM -> hint;
                    };
                }
                r.text(hint, book.bookState.itemSearch.getX() + 4,
                        book.bookState.itemSearch.getY() + (book.bookState.itemSearch.getHeight() - 8) / 2,
                        ArmatureTheme.faint());
            }
        });
        book.setFocused(book.bookState.itemSearch);
    }

    /**
     * The type page's search box, built on the item picker's own terms.
     *
     * <p>Kept across rebuilds, focused on open, and redrawn after the card through {@code panelOverlays}
     * clipped to the page — the ordering that has cost this screen three separate faults and is written
     * down beside the item picker's own copy of it. What differs is only the band: the page may be a
     * guest of the table editor's card rather than of the quest card, and the box goes in the band the
     * list does.
     */
    void buildTypeSearch() {
        if (!book.typePageOpen()) {
            book.bookState.typeSearch = null;
            return;
        }
        final BookGeometry.Rect band = book.editorControls.typePageBand();
        ItemPickerLayout.Frame frame = ItemPickerLayout.Frame.of(band);
        String kept = book.bookState.typeSearch == null ? "" : book.bookState.typeSearch.value();
        book.bookState.typeSearch = new ArmatureTextField(frame.search().x(), frame.search().y(),
                frame.search().width(), frame.search().height(), kept);
        // A blur must commit nothing: Enter and Escape are the page's while it is open, and a rebuild
        // that blurred the box would otherwise set the field to whatever text it happened to hold.
        book.bookState.typeSearch.onSubmit(text -> { });
        book.addRenderableWidget(book.bookState.typeSearch);
        book.bookState.panelOverlays.add(r -> {
            if (book.bookState.typeSearch == null || !book.bookState.typeSearch.value().isEmpty()) {
                return;
            }
            try (GuiRenderer.Scoped clip = r.clip(band.x(), band.y(), band.right(), band.bottom())) {
                // Off the box, for the rail's slide: see the item picker's own copy of this note.
                r.text("Search types \u2014 name or id", book.bookState.typeSearch.getX() + 4,
                        book.bookState.typeSearch.getY() + (book.bookState.typeSearch.getHeight() - 8) / 2,
                        ArmatureTheme.faint());
            }
        });
        book.setFocused(book.bookState.typeSearch);
    }

    /**
     * The quest editor's widgets: the edit bar in the footer, and one control per inspector row.
     *
     * <p>Built exactly like the dock's lists -- register a widget under the row's key, let the
     * ScrollView place it from the same layout the drawing reads -- because that is what keeps a
     * scrolled control beside the row it belongs to. The rows themselves come from
     * {@code QuestPanelLayout}, so the modal and the dock cannot drift about what a quest has.
     */
    private void buildQuestEditorWidgets(ClientQuestCache.Entry entry) {
        // The edit bar, in the footer the reader uses for Submit and Claim -- hidden while editing,
        // because an author's own progress is noise on the page they are writing. Delete asks once:
        // the first press arms it and says so, the second deletes, and anything else disarms it.
        //
        // **Its rectangle comes from the geometry, and that is the fix rather than a tidy-up.** It used to
        // be the reader's Submit slot, read out of `overlayControls(false)` -- a map that deliberately has
        // no "submit" key, because asking for one moves Back up a row on a narrow card, above the band the
        // body reserves for it. So the key answered null, the whole bar was skipped, and four controls
        // were never built at all: Delete, Duplicate, Copy, and the Settings button that opens a node's
        // shape, size, placement and rules. "The editor is missing the button it used to have" was that
        // one line, and it had been missing since the footer was refactored rather than since any round
        // of this session. `BookGeometry.editorBar` derives it from the card and Back, and the sweep in
        // `BookGeometryTest` now holds it.
        Map<String, BookGeometry.Rect> controls = book.overlayControls(false);
        BookGeometry.Rect slot = book.geometry().editorBar(book.surfaceCard(PanelKind.QUEST));
        if (slot != null) {
            // The same gap the floor is measured with, so a column dragged to its minimum holds the four
            // actions at exactly the width they were measured at -- one number, or the floor would be
            // computed against a layout that is not the one drawn.
            int gap = QuestBookScreen.EDITOR_ACTION_GAP;
            int left = slot.x();
            int right = slot.right();
            int quarter = Math.max(0, (right - left - gap * 3) / 4);
            BookGeometry.Rect delete = BookGeometry.Rect.at(left, slot.y(), quarter, slot.height());
            BookGeometry.Rect duplicate = BookGeometry.Rect.at(delete.right() + gap, slot.y(),
                    quarter, slot.height());
            BookGeometry.Rect copy = BookGeometry.Rect.at(duplicate.right() + gap, slot.y(),
                    quarter, slot.height());
            BookGeometry.Rect settings = BookGeometry.Rect.at(copy.right() + gap, slot.y(),
                    Math.max(0, right - copy.right() - gap), slot.height());
            book.bookState.questDeleteButton = book.control(delete,
                    Component.literal(book.bookState.confirmingDelete ? "Really delete?" : "Delete"),
                    this::pressDeleteQuest);
            if (book.bookState.questDeleteButton != null) {
                // A live role rather than a captured colour: the armed state's red is the theme's
                // blocked ink, and this card is drawn inside the chapter's scope.
                book.bookState.questDeleteButton.ink(book.bookState.confirmingDelete ? ArmatureButton.Ink.BLOCKED
                        : ArmatureButton.Ink.BODY);
            }
            book.control(duplicate, Component.translatable("tenet.screen.duplicate"), this::duplicateQuest)
                    .ink(ArmatureButton.Ink.BODY);
            book.control(copy, Component.translatable("tenet.screen.copy"), this::copyQuest)
                    .ink(ArmatureButton.Ink.BODY);
            settingsButton = book.control(settings, Component.translatable("tenet.screen.settings"),
                    book.bookRewards::toggleSettings);
            if (settingsButton != null) {
                settingsButton.ink(ArmatureButton.Ink.BODY).selected(book.bookState.settingsOpen)
                        .tooltip(Component.translatable("tenet.screen.shape_size_placement_and_rules"));
            }
        }
        // One step back, as everywhere else in this book. While a page is over the card -- a list of types,
        // a list of items, the settings page -- this button leaves that page and the card stays open;
        // "Done" is for the press that really does end the card, which is its last step. The tooltip needs
        // no change: Escape leaves the same thing, so "Escape also closes this" is true of the page as
        // well as of the card.
        boolean pageOverCard = book.bookState.pickingEntryType != null || book.bookState.pickingConditionFor != null
                || (book.bookState.pickingItemPath != null && !book.itemPicker.pickerAsColumn())
                        || book.bookState.settingsOpen;
        ArmatureButton done = book.control(controls.get("back"),
                Component.translatable(pageOverCard ? "tenet.screen.back" : "tenet.screen.done"),
                pageOverCard ? book.bookRewards::leaveCardPage : book::closeOverlay);
        if (done != null) {
            done.ink(ArmatureButton.Ink.BODY).tooltip(Component.translatable("tenet.screen.escape_also_closes_this"));
        }

        buildPickerWidgets();

        // The type page hosts no widgets of its own: its rows are drawn by `drawTypePicker` from the
        // catalogue, the way the item picker's are, and its only control is the search box
        // `buildPickerWidgets` has just placed. It used to be one button per type placed into
        // `overlayView`, which meant the rows were widgets -- no keyboard, no scrollbar, and a hover that
        // could not say why a type the table refuses was refused.

        // The settings page's widgets, when it is open: one text field per row that takes typing, placed
        // by the page's own layout so a scrolled field sits beside its label. Everything else on the
        // page is drawn and hit-tested from `QuestSettingsLayout`, which is why the page has three
        // widgets rather than twenty.
        book.bookState.settingsRows = List.of();
        book.bookState.settingsLayout = null;
        if (book.bookState.settingsOpen) {
            JsonObject quest = book.replicaQuest();
            book.bookState.settingsRows = dev.ellipog.tenet.client.dev.QuestSettingsLayout.rows(quest,
                    book.bookRewards::dependencyTitle, book.bookRewards.selectedDependencyCandidates().size());
            dev.ellipog.tenet.client.dev.QuestSettingsLayout.Frame frame =
                    dev.ellipog.tenet.client.dev.QuestSettingsLayout.Frame.of(book.overlayBodyRect());
            book.bookState.settingsLayout = dev.ellipog.tenet.client.dev.QuestSettingsLayout.build(book.bookState.settingsRows,
                    frame.controls().width(), Measure.monospace(6, 9));
            // **Measured here, once per rebuild, and carried on the rows themselves.** Each row's control
            // takes the width its own label leaves it — a short name hands its spare room to a slider's track
            // or a field's box, a long one takes room back — and putting the answer on the row is what keeps
            // the drawing, the widgets and the press reading one number from one place. Nothing is measured on
            // the frame path, and what this replaces is the unmeasured row, so a page that never reaches this
            // line lays out exactly as it did before.
            book.bookState.settingsRows = measuredSettingsRows(book.bookState.settingsRows, frame.controls().width());
            book.bookState.settingsView.clear();
            book.bookState.settingsView.whole(true);
            // The column's own rectangle -- not the page's: a widget placed from a viewport that does
            // not match the clip sits beside its label.
            book.bookState.settingsView.viewport().bounds(frame.controls().x(), frame.controls().y(),
                    frame.controls().width(), frame.controls().height());
            for (dev.ellipog.tenet.client.dev.QuestSettingsLayout.Row row : book.bookState.settingsRows) {
                if (row.kind() != dev.ellipog.tenet.client.dev.QuestSettingsLayout.Row.Kind.FIELD) {
                    continue;
                }
                String path = row.key();
                ArmatureTextField field = new ArmatureTextField(0, 0, 0, 0, questValue(quest, path));
                field.onSubmit(text -> commitField(path, text, false));
                // The placer is a lambda rather than a method reference now, because a row's control takes
                // the room its *kind* needs rather than one width for the whole page -- a switch does not
                // need a slider's room, and taking it left the five "Hide ..." rows indistinguishable.
                book.bookState.settingsView.put(row.key(), field,
                        rowSlot -> dev.ellipog.tenet.client.dev.QuestSettingsLayout.strip(row, rowSlot));
                book.addRenderableWidget(field);
                // **No clipped redraw, and the two faults it was written for are recorded rather than
                // lost.** It exists because the widget pass does not clip: a field the scroll view has
                // culled still paints, and "input fields flow outside sometimes" was the report. On a card
                // the redraw solved it by painting a clipped copy *over* the unclipped one. A rail is
                // drawn before the pass, so the unclipped copy is the one on top and a second copy cannot
                // take it away -- the fix does not transfer, and a redraw here would be the same pixels
                // paid for twice with the fault still on screen. Clipping a rail's widgets properly means
                // clipping the widget pass to the rail's body, which is a change to the pass rather than
                // to this panel. Recorded in TESTING.md as an open gap rather than quietly dropped.
            }
            for (dev.ellipog.tenet.client.dev.QuestSettingsLayout.Row row : book.bookState.settingsRows) {
                if (row.kind() != dev.ellipog.tenet.client.dev.QuestSettingsLayout.Row.Kind.NUMBER) {
                    continue;
                }
                dev.ellipog.tenet.client.dev.ScrubField field = book.bookRewards.settingField(row.key(), quest);
                book.bookState.settingsView.put(row.key(), field);
                book.addRenderableWidget(field);
                // The same as the text fields above, and for the same reason: the pass draws it, a rail is
                // drawn before the pass, and a second clipped copy cannot take the first one away.
            }
            book.bookState.settingsView.apply(book.bookState.settingsLayout, frame.controls().width());
        }
    }

    /** A field's value as a box starts it, from the replica — empty when the field is absent. */
    private String questValue(JsonObject quest, String path) {
        JsonElement value = quest == null ? null : QuestPanelLayout.get(quest, path);
        if (value == null) {
            return "";
        }
        if (value.isJsonArray()) {
            return String.join(", ", QuestPanelLayout.strings(quest, path));
        }
        return value.isJsonPrimitive() ? value.getAsString() : value.toString();
    }

    /**
     * The settings page's own state: what the page is asking for, and what is under the pointer.
     *
     * <p>One record rather than six locals, because the drawing, the hit tests and the preview all need
     * the same answer to "what is the page showing" — and the alternative is three places that each
     * remember to ask the draft first.
     */
    dev.ellipog.tenet.client.dev.QuestSettingsPanel.View settingsViewOf(
            ClientQuestCache.Entry entry, JsonObject quest, double mouseX, double mouseY) {
        // The server's answer with the page's pending values applied over it, resolved here rather than
        // in the panel: the outline a rotation produces has to be *built*, and building it per frame
        // inside the drawing would rebuild a sampled table per frame.
        QuestShape shape = book.bookState.settingsDraft.shape(entry.id(), entry.shape());
        int size = book.bookState.settingsDraft.size(entry.id(), entry.size());
        double iconScale = book.bookState.settingsDraft.iconScale(entry.id(), entry.iconScale());
        int rotation = book.bookState.settingsDraft.rotation(entry.id(), entry.rotation());
        boolean showTitle = QuestBookScreen.flagOn(quest, "showTitle");
        int hoveredCell = -1;
        String hoveredKey = null;
        Slot grid = book.bookState.settingsLayout == null ? null : book.bookState.settingsLayout.slot("shape");
        if (grid != null) {
            Slot onScreen = InspectLayout.onScreen(book.bookState.settingsView.viewport(), grid);
            hoveredCell = dev.ellipog.tenet.client.dev.QuestSettingsLayout.cellAt(onScreen, mouseX,
                    mouseY, QuestShape.values().length);
            if (hoveredCell >= 0) {
                hoveredKey = "shape";
            }
        }
        if (hoveredKey == null && book.bookState.settingsLayout != null) {
            for (dev.ellipog.tenet.client.dev.QuestSettingsLayout.Row row : book.bookState.settingsRows) {
                Slot slot = book.bookState.settingsLayout.slot(row.key());
                if (slot == null) {
                    continue;
                }
                Slot onScreen = InspectLayout.onScreen(book.bookState.settingsView.viewport(), slot);
                if (onScreen.contains(mouseX, mouseY)) {
                    hoveredKey = row.key();
                    break;
                }
            }
        }
        // The chapter's two reveal defaults, read from its own copy rather than from the wire: they are
        // authoring fields, and this page is an authoring surface standing beside the tab that writes them.
        // The draft is applied over the copy for the same reason every other row here reads it -- a toggle
        // pressed in the Chapter tab has not reached the server yet, and the label should say what the
        // file is about to say. An empty copy (never asked for, or refused) reads as off, which is the
        // value both fields have in every file that does not mention them.
        JsonObject chapterCopy = BookScreenState.fieldDraft.overlaid(entry.chapterId(),
                dev.ellipog.tenet.client.dev.FieldDraft.CHAPTER_OWNER,
                ClientChapterReplica.chapterTree(entry.chapterId()));
        return new dev.ellipog.tenet.client.dev.QuestSettingsPanel.View(QuestBookScreen.titleOf(entry), entry.icon(),
                entry.textureIcon(), entry.spriteIcon(),
                shape, previewGeometry(shape, rotation), rotation, size, iconScale, showTitle,
                hoveredCell, hoveredKey,
                entry.chapterDefaultPrerequisiteMode().name().toLowerCase(java.util.Locale.ROOT),
                QuestBookScreen.flagOn(chapterCopy, "defaultHideUntilDependenciesComplete"),
                QuestBookScreen.flagOn(chapterCopy, "defaultHideUntilDependenciesVisible"));
    }

    /**
     * The outline the settings page previews, remembered until the shape or the angle changes.
     *
     * <p>A rotation is applied by sampling the shape again, which is O(size squared) -- cheap once, and
     * not something to do inside a drawing pass. The memo is one entry, because the page previews one
     * node, and a drag rebuilds it once per frame at worst, which is a few thousand point tests.
     */
    private dev.ellipog.armature.client.ui.shape.Shape previewGeometry(QuestShape shape, int rotation) {
        if (previewGeometry == null || previewShape != shape || previewRotation != rotation) {
            previewShape = shape;
            previewRotation = rotation;
            previewGeometry = rotation == 0
                    ? shape.geometry()
                    : dev.ellipog.armature.client.ui.shape.Shapes.rotated(shape.geometry(), rotation);
        }
        return previewGeometry;
    }

    /**
     * The editor's body: <b>the preview itself</b>, with the things that can be edited marked.
     *
     * <p>The fourth playtest's design and the row inspector it rejected: the card an author edits is
     * the card a player reads. A title is a title until the pointer is over it, prose is prose, task
     * rows are the row's own parts -- icon, count, name, consume -- and the editors appear in place
     * when a piece is pressed. The reader's layout is the editor's layout, with add rows in it, so the
     * two cannot disagree about where anything sits; {@code editTargets} is rebuilt here every frame
     * and read by the click, one derivation for the drawing and the press.
     */
    void drawQuestEditor(GuiRenderer r, ClientQuestCache.Entry entry, int mouseX, int mouseY,
                                 long now) {
        BookGeometry.Rect card = book.surfaceCard(PanelKind.QUEST);
        Viewport body = book.overlayBody();
        book.bookState.editTargets.clear();
        book.bookState.pendingLabels.clear();
        // The row positions are this frame's, rebuilt with the rows themselves: a release must aim at
        // the list the pointer saw, not at last frame's.
        book.bookState.dragRowSlots.clear();
        // The reader's rectangles are stale while the editor draws: a press on the raw description must open
        // the field, not follow a link that is no longer on screen.
        //
        // And the reader's card's layout goes with them. That is not tidiness: the layout is remembered
        // between frames and the parsed prose is remembered *with* it, so clearing the prose behind the
        // cache's back would leave a later hit handing back a layout whose description has nothing to
        // draw. One assignment here is what makes the cache's one invariant true — nothing touches the
        // prose without touching the key.
        book.bookState.readerProse.clear();
        book.bookState.linkRects.clear();
        book.bookSettings.readerCard = null;
        // The header is the reader's, already drawn above this call -- text, state tag, chapter line
        // and all. This adds only the marks and the targets: drawing the title again here is how the
        // subtitle line came out garbled, two texts on top of each other.
        drawEditHeaderMarks(r, entry, mouseX, mouseY);

        // The settings page takes the body when it is open, on the pickers' own terms: it is a page of
        // the card rather than a popover over it, because the thing it is changing -- the node -- needs
        // room to be drawn, and a popover over the prose was the form the third playtest rejected.
        if (book.bookState.settingsOpen) {
            book.editorControls.drawSettings(r, entry, mouseX, mouseY);
            return;
        }

        // The item picker takes the body when it is open, on the type picker's own terms: the list is
        // what you are reading, the card is the page you are setting a field on, and the page behind a
        // list is context rather than a second thing to press.
        if (book.bookState.pickingItemPath != null && !book.itemPicker.pickerAsColumn()) {
            book.itemPicker.drawItemPicker(r, body, mouseX, mouseY);
            return;
        }

        // The type page takes the body when it is open: a list of types is a list, and rows are what
        // lists are made of. Drawn by the item picker's own row renderer -- see `drawTypePicker` -- so
        // adding a task reads like adding an item, which is what this round was asked for.
        if (book.typePageOpen()) {
            book.editorControls.drawTypePicker(r, BookGeometry.Rect.at(body.originX(), body.originY(), body.viewWidth(),
                    body.viewHeight()), mouseX, mouseY);
            return;
        }

        JsonObject quest = book.replicaQuest();
        if (quest == null) {
            // **The placeholder says what the server said, when it said anything.** It was a hard-coded
            // literal that could only ever say "waiting", so a chapter the server had *refused* -- a
            // permission, or a chapter it does not have -- looked like a slow network forever. The chapter
            // tab and the table panel each fixed exactly this with this pair of keys; the quest body was the
            // last place still guessing, and it is also the last user-visible literal in this file.
            //
            // See `ClientChapterReplica.refusal`: a refusal is recorded rather than only toasted *because*
            // it is the answer to the question the placeholder is visibly failing to answer.
            String said = ClientChapterReplica.refusal(QuestBookScreen.effectiveChapter());
            r.text(said == null
                            ? Component.translatable("tenet.dev.chapter.missing_copy").getString()
                            : Component.translatable("tenet.dev.chapter.missing_copy_said", said).getString(),
                    body.originX() + 6, body.originY() + 6, ArmatureTheme.faint());
            return;
        }

        // The layout, and the four things the drawing below reads from the same build: the prose, the
        // per-entry line counts and the prerequisites. Cached together because they *are* one answer --
        // the line counts size the rows the layout places, and the prose is what the layout was wrapped
        // from, so a cached layout without its prose is a card that draws nothing where its description
        // should be. See `editorCard`.
        EditorCard editorFields = editorCard(r, entry, quest, body);
        Layout layout = editorFields.layout();
        List<String> description = editorFields.description();
        List<Integer> taskLines = editorFields.taskLines();
        List<Integer> rewardLines = editorFields.rewardLines();
        List<String> dependencies = editorFields.dependencies();
        book.bookState.overlayView.apply(layout, body.viewWidth());

        try (GuiRenderer.Scoped clip = r.clip(body)) {
            Measure measure = book.textMeasure(r);
            // One drawing of the prose, and while the description is being edited the editor's is the
            // one: the field is transparent so the text does not move, so a second copy underneath is
            // not hidden by it -- it is printed on top of it, a line apart wherever the two models
            // disagree. See `InlineEdit.replaces`.
            if (!InlineEdit.replaces("description", book.bookState.editingPath)) {
                drawDescription(r, description, layout, body, measure);
            }
            // The description's box, registered with **no mark drawn around it**: the prose is the mark,
            // and a border around prose is one more thing in the author's way (the report, after three
            // rounds of making that border behave: *"just remove the outline around the description box
            // completely, its easier"*). The box is still the press's answer to "where is the
            // description" and the field's answer to where it goes -- it is only never painted.
            BookGeometry.Rect prose = book.editorControls.descriptionBox(description.size(), layout, body);
            registerTarget(QuestBookScreen.EditAction.FIELD, "description", prose,
                    prose.x() + OverlayLayout.PROSE_PAD, prose.y() + OverlayLayout.PROSE_PAD,
                    BookEditorControls.join(description), null, -1);

            book.bookSettings.drawHeading(r, BookSettings.placed(layout, body, OverlayLayout.TASKS_HEADING), "Tasks");
            if (taskLines.isEmpty()) {
                // The row the layout reserves either way ("a row either way, so an empty list and a
                // one-item list take the same space") -- which the editor left *blank*, so an empty
                // section was a heading, a hole, and then the add row. The reader's own words go in it.
                BookEditorControls.drawEmptyState(r, BookSettings.placed(layout, body,
                        OverlayLayout.NO_TASKS), "Nothing required");
            }
            for (int i = 0; i < taskLines.size(); i++) {
                drawEntryRow(r, BookSettings.placed(layout, body, OverlayLayout.taskKey(i)),
                        BookEditorControls.memberEntry(quest, "tasks", i),
                        "tasks", i, mouseX, mouseY);
            }
            book.editorControls.drawAddRow(r, BookSettings.placed(layout, body, OverlayLayout.TASKS_ADD),
                    "+ Add task",
                    QuestBookScreen.EditAction.ADD_TASK, mouseX, mouseY);

            book.bookSettings.drawHeading(r, BookSettings.placed(layout, body, OverlayLayout.REWARDS_HEADING),
                    "Rewards");
            if (rewardLines.isEmpty()) {
                BookEditorControls.drawEmptyState(r, BookSettings.placed(layout, body,
                        OverlayLayout.NO_REWARDS), "Nothing");
            }
            for (int i = 0; i < rewardLines.size(); i++) {
                drawEntryRow(r, BookSettings.placed(layout, body, OverlayLayout.rewardKey(i)),
                        BookEditorControls.memberEntry(quest, "rewards", i), "rewards", i, mouseX, mouseY);
            }
            book.editorControls.drawAddRow(r, BookSettings.placed(layout, body, OverlayLayout.REWARDS_ADD),
                    "+ Add reward",
                    QuestBookScreen.EditAction.ADD_REWARD, mouseX, mouseY);

            // The insertion line last in the clip, over every row it sits between: a line drawn where
            // the rows are drawn is a line the next row paints over.
            if (book.bookState.dragRowLive) {
                book.bookRewards.drawRowDragIndicator(r, book.bookState.dragRowSlots.get(book.bookState.dragRowMember),
                        book.bookState.dragRowPointerY, BookRewards.onScreenBand(body));
            }

            book.bookSettings.drawHeading(r, BookSettings.placed(layout, body,
                    OverlayLayout.REQUIRES_HEADING), "Requires");
            for (int i = 0; i < dependencies.size(); i++) {
                book.editorControls.drawDependencyRow(r, BookSettings.placed(layout, body,
                        OverlayLayout.dependencyKey(i)),
                        dependencies.get(i), mouseX, mouseY);
            }
            book.editorControls.drawDependencyAddRow(r, BookSettings.placed(layout, body,
                    OverlayLayout.REQUIRES_ADD), mouseX, mouseY);
        }
        QuestBookScreen.drawBar(r, book.bookState.overlayView.bar(), mouseX, mouseY);

        // Where the open editor goes, every frame. The card scrolls under it -- the wheel is answered by
        // the body even while a field has the keyboard -- and the description's height has to track the
        // text being written as the card grows. Both are the same question -- where is the target now --
        // so both are answered here, from the frame's own target list, rather than a second time for the
        // area alone. See `inlineBox` for the geometry.
        book.editorControls.repositionInlineEditor();

        // The waiting labels are drawn after the clip, with the tooltips -- see `drawPendingLabels` for
        // why "last in the card" was not enough.

    }

    /**
     * The marks on the reader's own header -- icon, title and the chapter/subtitle line.
     *
     * <p>Marks only. The text, the state tag and the chapter line are drawn by the read path, which
     * runs before this; drawing them a second time here is what put two subtitles on top of each other.
     */
    private void drawEditHeaderMarks(GuiRenderer r, ClientQuestCache.Entry entry, int mouseX, int mouseY) {
        BookGeometry.Rect card = book.surfaceCard(PanelKind.QUEST);
        int iconX = card.x() + 14;
        int iconY = card.y() + (46 - QuestBookScreen.HEADER_ICON) / 2;

        target(r, QuestBookScreen.EditAction.ITEM, "icon.item",
                BookGeometry.Rect.at(iconX - 1, iconY - 1, QuestBookScreen.HEADER_ICON + 2,
                        QuestBookScreen.HEADER_ICON + 2),
                iconX + 1, iconY + (QuestBookScreen.HEADER_ICON - 8) / 2, "", null, -1, mouseX, mouseY);

        int textX = iconX + QuestBookScreen.HEADER_ICON + 6;
        target(r, QuestBookScreen.EditAction.FIELD, "title", BookGeometry.Rect.at(textX - 2, card.y() + 10,
                        Math.max(60, r.textWidth(QuestBookScreen.titleOf(entry)) + 6), 12),
                textX, card.y() + 12, QuestBookScreen.titleOf(entry), null, -1, mouseX, mouseY);

        String where = entry.chapterTitleText()
                + (subtitleOf(entry).isEmpty() ? "" : "  \u00b7  " + subtitleOf(entry));
        target(r, QuestBookScreen.EditAction.FIELD, "subtitle", BookGeometry.Rect.at(textX - 2, card.y() + 24,
                        Math.max(80, r.textWidth(where) + 6), 12),
                textX, card.y() + 26, subtitleOf(entry), null, -1, mouseX, mouseY);
    }

    /**
     * Registers a target and draws its affordance, so the mark and the press cannot disagree.
     *
     * <p>{@code textX} and {@code textY} are where the read path draws the value's text -- the box is
     * the affordance, which hugs the whole piece and is padded, while the text inside it is inset
     * (four pixels for a number, past the icon for an item, two for the title, four inside the
     * description's frame). The editor's field is placed from <b>those</b> rather than from the box,
     * which is what stops a click moving the value: the field draws its text at the text's own origin,
     * because that is where the text was -- and it stays there when the frame around it is padded.
     */
    private void target(GuiRenderer r, QuestBookScreen.EditAction action, String path, BookGeometry.Rect box, int textX,
                        int textY, String value, String member, int index, int mouseX, int mouseY) {
        if (registerTarget(action, path, box, textX, textY, value, member, index)) {
            QuestBookScreen.drawEditAffordance(r, box, box.contains(mouseX, mouseY));
        }
    }

    /**
     * How many lines each of a quest's entries needs, for the card layout's heights.
     *
     * <p>The width is the body's, less the layout's own row indent -- the same arithmetic a {@code Slot}
     * gets at build time, so a row's height and its slot cannot disagree about where the row ends.
     */
    private List<Integer> entryLineCounts(String member, JsonObject quest, int bodyWidth) {
        if (!quest.has(member) || !quest.get(member).isJsonArray()) {
            return List.of();
        }
        int slotWidth = bodyWidth - 2 * OverlayLayout.ROW_INDENT;
        List<Integer> lines = new ArrayList<>();
        for (int i = 0; i < quest.getAsJsonArray(member).size(); i++) {
            JsonElement element = quest.getAsJsonArray(member).get(i);
            lines.add(element.isJsonObject()
                    ? EntryFormLayout.lines(member, element.getAsJsonObject(), slotWidth,
                            book.bookState.entryFolded.contains(member + "." + i))
                    : 1);
        }
        return lines;
    }

    /**
     * One task or reward row, as its type's own form.
     *
     * <p>The badge names the type and shows its icon at its own size; the fields come from the type's
     * registration -- {@code QuestPanelLayout.editorFor}, which is the registry's form with the settings
     * every task or reward carries appended -- and each is drawn as a label in the label column with its
     * control beside it. An unknown type gets the raw value instead, editable whole, which is the one
     * thing that can honestly be offered for a shape this build cannot read.
     */
    private void drawEntryRow(GuiRenderer r, Slot slot, JsonObject entry, String member, int index,
                              int mouseX, int mouseY) {
        if (slot == null || entry == null) {
            return;
        }
        String type = entry.has("type") && entry.get("type").isJsonPrimitive()
                ? entry.get("type").getAsString() : "not stated";
        boolean known = QuestPanelLayout.knownType(type);
        boolean folded = book.bookState.entryFolded.contains(member + "." + index);

        // Each entry is a card: a surface and a border behind its own slot. It was a hairline rule
        // between entries, which made a stack of forms read as one long column of boxes -- and a reward
        // is a thing, so it is drawn as one. Inside the entry's own air, so no height changes and
        // nothing moves; the card is what the entry's rows are already laid out on.
        ArmatureTheme.panel(r, slot.x(), slot.y(), slot.width(), Math.max(0, slot.height() - 2),
                ArmatureTheme.recessed(), ArmatureTheme.panelEdge());

        // One derivation for the whole entry: the badge, the fields, the grip and the corner controls.
        // Drawing and the hit test below both read it, so a control cannot be drawn on one line and
        // pressed on another -- the rule every row in this card follows.
        EntryFormLayout.Form form = EntryFormLayout.form(member, entry,
                BookGeometry.Rect.at(slot.x(), slot.y(), slot.width(), slot.height()), folded);

        drawBadge(r, form, member, type, known, folded, entry, mouseX, mouseY);

        if (known && !folded) {
            for (EntryFormLayout.Cell cell : form.cells()) {
                drawField(r, cell, entry, member, index, member + "." + index,
                        EntryFormLayout.sharesLine(cell, form.cells()), mouseX, mouseY);
            }
            for (EntryFormLayout.ConditionRow condition : form.conditions()) {
                drawConditionRow(r, condition, entry, member, index, mouseX, mouseY);
            }
            drawConditionAddRow(r, form.addCondition(), member, index, mouseX, mouseY);
        }
        else if (!known && !folded) {
            drawRawRow(r, form, member, index, mouseX, mouseY);
        }

        // Copy, the template star and the cross, on the badge's line at the entry's corner. **Flat,
        // with the frame only on hover**, and that is a fix rather than a taste: drawn like everything
        // else they carried the same faint frame a value box carries, so every entry ended in what read
        // as two more text fields -- "Copy" and "×" sitting in boxes the size of the ones you type in.
        String[] labels = {"Copy", "\u2605", "\u00d7"};
        QuestBookScreen.EditAction[] actions = {QuestBookScreen.EditAction.COPY_ENTRY,
                QuestBookScreen.EditAction.SAVE_ENTRY, QuestBookScreen.EditAction.REMOVE_ENTRY};
        BookGeometry.Rect[] boxes = {form.copy(), form.save(), form.remove()};
        String[] words = {"", "Save as template", ""};
        for (int i = 0; i < 3; i++) {
            BookGeometry.Rect box = boxes[i];
            boolean hot = box.contains(mouseX, mouseY);
            if (hot) {
                QuestBookScreen.drawEditAffordance(r, box, true);
            }
            r.text(labels[i], box.x() + (box.width() - r.textWidth(labels[i])) / 2,
                    box.y() + (box.height() - 8) / 2, hot ? ArmatureTheme.title() : ArmatureTheme.body());
            registerInto(book.bookState.editTargets, actions[i], null, box, box.x(),
                    box.y() + (box.height() - 8) / 2, "", member, index);
            if (hot && !words[i].isEmpty()) {
                book.bookState.pendingLabels.add(new QuestBookScreen.PendingLabel(box, words[i]));
            }
        }

        // The fold: the triangle between Copy and the cross, pointing down at the fields while they are
        // shown and up while they are not. Flat like the two beside it, and its word on hover -- a
        // triangle is a guess until it says what it does.
        BookGeometry.Rect fold = form.fold();
        boolean foldHot = fold.contains(mouseX, mouseY);
        if (foldHot) {
            QuestBookScreen.drawEditAffordance(r, fold, true);
        }
        String mark = folded ? "\u25b2" : "\u25bc";
        r.text(mark, fold.x() + (fold.width() - r.textWidth(mark)) / 2,
                fold.y() + (fold.height() - 8) / 2, foldHot ? ArmatureTheme.title() : ArmatureTheme.body());
        if (foldHot) {
            String word = folded ? "Expand" : "Collapse";
            book.bookState.pendingLabels.add(new QuestBookScreen.PendingLabel(fold, word));
        }
        registerInto(book.bookState.editTargets, QuestBookScreen.EditAction.TOGGLE_ENTRY, null, fold, fold.x(),
                fold.y() + (fold.height() - 8) / 2, "", member, index);

        // The row's leading strip is the drag's grip -- the type's icon and the gutter beside it, which no
        // field covers, so registering last wins exactly the space nothing else wanted. The whole row is
        // what the gap is counted against; the grip is only where the press lands.
        //
        // **Marked only while the pointer is on it**, for the same reason as Copy above: the grip used to
        // draw the value-box frame, so every entry wore a tall empty box down its left edge -- a column of
        // what looked like unlabelled fields. A bar on hover says the same thing without pretending there
        // is something there.
        book.bookState.dragRowSlots.computeIfAbsent(member, key -> new ArrayList<>())
                .add(BookGeometry.Rect.at(slot.x(), slot.y(), slot.width(), slot.height()));
        BookGeometry.Rect grip = form.grip();
        if (grip.contains(mouseX, mouseY)) {
            r.fill(grip.x() + 1, grip.y() + 2, grip.x() + 3, grip.bottom() - 2, ArmatureTheme.hoverRing());
        }
        registerTarget(QuestBookScreen.EditAction.DRAG_ENTRY, null, grip, grip.x() + 4,
                grip.y() + (grip.height() - 8) / 2, "", member, index);
    }

    /**
     * One condition under an entry: its badge, its own fields, and the cross that removes it.
     *
     * <p>A condition is edited exactly as a task is, one level down: the same registered form, the same
     * controls, the same one-op-per-press rule — with the condition's type's form from
     * {@code QuestPanelLayout.conditionEditorFor}. A type this build does not know gets the raw value,
     * the same refusal an unknown entry gets: what cannot be drawn is not faked.
     */
    private void drawConditionRow(GuiRenderer r, EntryFormLayout.ConditionRow row, JsonObject entry,
                                  String member, int index, int mouseX, int mouseY) {
        JsonElement value = entry.getAsJsonArray("conditions").get(row.index());
        if (!value.isJsonObject()) {
            return;
        }
        JsonObject condition = value.getAsJsonObject();
        String pathPrefix = member + "." + index + ".conditions." + row.index();
        String type = condition.has("type") && condition.get("type").isJsonPrimitive()
                ? condition.get("type").getAsString() : "not stated";

        ItemStack icon = conditionIcon(type);
        if (!icon.isEmpty()) {
            r.icon(icon, row.icon().x(), row.icon().y(), EntryFormLayout.ICON_BOX);
        }
        r.text(Measure.truncate(QuestPanelLayout.conditionName(type), row.name().width() - 2,
                        book.textMeasure(r)),
                row.name().x() + 1, row.name().y() + (EntryFormLayout.LINE_HEIGHT - 8) / 2,
                ArmatureTheme.faint());

        // The star before the cross: this condition as a template, one press. Flat like the entry's
        // own three, and last-but-one for the same reason the entry's cross is last — a press that
        // travels past the harmless control to reach the destructive one.
        BookGeometry.Rect save = row.save();
        boolean saveHot = save.contains(mouseX, mouseY);
        if (saveHot) {
            QuestBookScreen.drawEditAffordance(r, save, true);
        }
        String star = "\u2605";
        r.text(star, save.x() + (save.width() - r.textWidth(star)) / 2,
                save.y() + (save.height() - 8) / 2,
                saveHot ? ArmatureTheme.title() : ArmatureTheme.body());
        registerInto(book.bookState.editTargets, QuestBookScreen.EditAction.SAVE_CONDITION, pathPrefix, save, save.x(),
                save.y() + (save.height() - 8) / 2, "", member, index);
        if (saveHot) {
            book.bookState.pendingLabels.add(new QuestBookScreen.PendingLabel(save, "Save as template"));
        }

        BookGeometry.Rect remove = row.remove();
        boolean removeHot = remove.contains(mouseX, mouseY);
        if (removeHot) {
            QuestBookScreen.drawEditAffordance(r, remove, true);
        }
        String cross = "\u00d7";
        r.text(cross, remove.x() + (remove.width() - r.textWidth(cross)) / 2,
                remove.y() + (remove.height() - 8) / 2,
                removeHot ? ArmatureTheme.title() : ArmatureTheme.body());
        registerInto(book.bookState.editTargets, QuestBookScreen.EditAction.CONDITION_REMOVE, pathPrefix,
                remove, remove.x(),
                remove.y() + (remove.height() - 8) / 2, "", member, index);

        if (QuestPanelLayout.conditionEditorFor(condition).isEmpty()) {
            BookGeometry.Rect raw = BookGeometry.Rect.at(row.name().x(), row.name().bottom(),
                    Math.max(20, row.name().width()), EntryFormLayout.LINE_HEIGHT);
            QuestBookScreen.drawEditAffordance(r, raw, raw.contains(mouseX, mouseY));
            if (!InlineEdit.replaces(pathPrefix, book.bookState.editingPath)) {
                r.text("edit JSON", raw.x() + 4, raw.y() + (EntryFormLayout.LINE_HEIGHT - 8) / 2,
                        ArmatureTheme.faint());
            }
            registerInto(book.bookState.editTargets, QuestBookScreen.EditAction.RAW, pathPrefix, raw, raw.x() + 4,
                    raw.y() + (EntryFormLayout.LINE_HEIGHT - 8) / 2, "", member, index);
            return;
        }
        for (EntryFormLayout.Cell cell : row.cells()) {
            // The condition owns these fields. `drawField` reads every value and flag state from the
            // object it is handed, and a task beside it has fields with the same names -- item, count,
            // match -- so handing it the entry is what made a condition's row display the task's
            // numbers, and its inline editor pre-fill from them.
            drawField(r, cell, condition, member, index, pathPrefix,
                    EntryFormLayout.sharesLine(cell, row.cells()), mouseX, mouseY);
        }
    }

    /** The row that opens the condition picker: a chip in the entry's own fields' column, and its press. */
    private void drawConditionAddRow(GuiRenderer r, BookGeometry.Rect box, String member, int index,
                                     int mouseX, int mouseY) {
        if (box.width() <= 0 || box.height() <= 0) {
            return;
        }
        BookGeometry.Rect chip = BookGeometry.Rect.at(box.x() + EntryFormLayout.CONDITION_INDENT, box.y(),
                Math.min(84, Math.max(20, box.width() - EntryFormLayout.CONDITION_INDENT)), box.height());
        boolean hot = chip.contains(mouseX, mouseY);
        QuestBookScreen.drawEditAffordance(r, chip, hot);
        String label = "+ Condition";
        r.text(Measure.truncate(label, chip.width() - 6, book.textMeasure(r)), chip.x() + 3,
                chip.y() + (chip.height() - 8) / 2, hot ? ArmatureTheme.title() : ArmatureTheme.body());
        registerTarget(QuestBookScreen.EditAction.CONDITION_ADD, member + "." + index, chip, chip.x() + 3,
                chip.y() + (chip.height() - 8) / 2, "", member, index);
    }

    /** A condition type's registered icon, or nothing for one this build does not know. */
    private static ItemStack conditionIcon(String typeId) {
        net.minecraft.resources.Identifier id =
                net.minecraft.resources.Identifier.tryParse(typeId);
        return id == null ? ItemStack.EMPTY
                : dev.ellipog.tenet.quest.condition.ConditionTypes.iconOf(id).toStack();
    }

    /**
     * The entry's badge: the type's icon at its own size, and the type's name beside it.
     *
     * <p>The icon used to be drawn sixteen pixels tall into a fourteen-pixel slot, one pixel high, with
     * nothing saying what it was -- a game item standing in for a concept. At its own size, beside the
     * type's name, it reads as what the type's author meant it to be: a badge.
     *
     * <p>Folded, the name is followed by a summary of what the entry says -- see {@link #summary} -- so a
     * folded row is still a row that tells you something rather than a row reduced to a word.
     */
    private void drawBadge(GuiRenderer r, EntryFormLayout.Form form, String member, String type,
                           boolean known, boolean folded, JsonObject entry, int mouseX, int mouseY) {
        ItemStack icon = BookEditorControls.typeIcon(member, type);
        if (!icon.isEmpty()) {
            r.icon(icon, form.icon().x(),
                    form.icon().y() + (form.icon().height() - EntryFormLayout.ICON_BOX) / 2,
                    EntryFormLayout.ICON_BOX);
        }
        // A type this build cannot read says so where its name would be, and keeps the raw editor below:
        // the alternative -- no name, no form, no explanation -- is the row that looked broken.
        String name = known ? QuestPanelLayout.typeName(member, type)
                : type + " \u2014 not known to this build";
        String line = name;
        if (folded && known) {
            String summary = summary(member, entry);
            if (!summary.isEmpty()) {
                line = name + "   \u00b7   " + summary;
            }
        }
        r.text(Measure.truncate(line, form.name().width(), book.textMeasure(r)), form.name().x(),
                form.name().y() + (EntryFormLayout.LINE_HEIGHT - 8) / 2,
                known ? ArmatureTheme.title() : ArmatureTheme.blocked());

        // The raw id, when the pointer is over the icon: the badge says "Item", and the spelling a file
        // and an error message use is one hover away rather than nowhere.
        if (form.icon().contains(mouseX, mouseY)) {
            book.bookState.pendingLabels.add(new QuestBookScreen.PendingLabel(form.icon(), type));
        }
    }

    /**
     * One line saying what an entry asks for, in the words its own fields use.
     *
     * <p>Built from the same rules the controls draw by -- an item's name, an id's friendly label, a
     * choice's option, a switch's own label while it is on, a number with its unit -- so a folded entry
     * reads as the reader's line for it rather than as a second, different summary. Triples are left out:
     * "Corner · Box size" for six numbers says nothing, and the fields are one press away.
     */
    private String summary(String member, JsonObject entry) {
        StringBuilder out = new StringBuilder();
        for (EditorField field : QuestPanelLayout.editorFor(member, entry)) {
            String part = summaryOf(field, entry);
            if (part.isEmpty()) {
                continue;
            }
            if (!out.isEmpty()) {
                out.append("   \u00b7   ");
            }
            out.append(part);
        }
        return out.toString();
    }

    /** One field's contribution to a folded entry's line: empty when the field says nothing. */
    private String summaryOf(EditorField field, JsonObject entry) {
        if (field.isTriple()) {
            return "";
        }
        if (field.kind() == EditorField.Kind.FLAG) {
            return QuestBookScreen.flagOn(entry, field.path()) ? field.label() : "";
        }
        // The picture override reads one level down, like the form's own box does: the badge names
        // the item rather than showing nothing for a set picture.
        String value = (field.kind() == EditorField.Kind.ITEM
                        || field.kind() == EditorField.Kind.ICON) && "icon".equals(field.path())
                ? QuestBookScreen.rawValue(entry, "icon.item")
                : QuestBookScreen.rawValue(entry, field.path());
        if (value.isEmpty()) {
            return "";
        }
        return switch (field.kind()) {
            case ITEM, ICON -> {
                ItemStack stack = QuestBookScreen.itemStack(value);
                yield stack.isEmpty() ? value : stack.getHoverName().getString();
            }
            case SEARCH, TAG -> friendlyId(value);
            case CHOICE -> EditorSpecs.label(value);
            case NUMBER -> field.unit().isEmpty() ? value : value + " " + field.unit();
            default -> value;
        };
    }

    /**
     * Folds an entry to its badge, or unfolds it.
     *
     * <p>Nothing is rebuilt: the card asks every entry for its line count as it lays out, so the list
     * reflows on the next frame -- and the fold is keyed by the entry's own position, the same index-keyed
     * shape the card's sections fold by.
     */
    void toggleFold(String member, int index) {
        String key = member + "." + index;
        if (!book.bookState.entryFolded.remove(key)) {
            book.bookState.entryFolded.add(key);
        }
    }

    /**
     * One field of an entry: its label in the label column, and its control in the column beside it.
     *
     * <p>The label is outside the box, which is the whole difference from the row this replaced: a box
     * that reads "Count" when it is empty and "8" when it is not is a box an author has to press to
     * identify, and a form whose labels all sit on one x is one an eye can run down.
     *
     * <p>The path the field commits to is the caller's. The one difference a condition makes: its fields live one level deeper, and every press it
     * registers must carry the full path — {@code tasks.3.conditions.1.min} — while the form's own
     * arithmetic is unchanged. The member and index still name the entry, which is what the presses that
     * need more than a path resolve their field through.
     */
    private void drawField(GuiRenderer r, EntryFormLayout.Cell cell, JsonObject entry, String member,
                           int index, String pathPrefix, boolean shared, int mouseX, int mouseY) {
        EditorField field = cell.field();
        String path = pathPrefix + "." + field.path();
        // The icon override is an object, not an id: its item lives one level down, and the box must
        // read that rather than the object itself, which would show "pick an item" over a set icon.
        String value = (field.kind() == EditorField.Kind.ITEM
                        || field.kind() == EditorField.Kind.ICON) && "icon".equals(field.path())
                ? QuestBookScreen.rawValue(entry, "icon.item")
                : QuestBookScreen.rawValue(entry, field.path());
        boolean on = QuestBookScreen.flagOn(entry, field.path());
        boolean replaced = InlineEdit.replaces(path, book.bookState.editingPath);
        Measure measure = book.textMeasure(r);

        // The label: lit when its switch is on, so a form of flags reads as a set of states rather than a
        // set of words.
        // A switch sharing its line hugs its own chip; see `EntryFormLayout.sharesLine`.
        String shownLabel = Measure.truncate(field.label(), cell.label().width() - 4, measure);
        int labelX = field.kind() == EditorField.Kind.FLAG && shared
                ? cell.label().right() - 2 - measure.width(shownLabel) : cell.label().x() + 2;
        r.text(shownLabel, labelX, cell.label().y() + (EntryFormLayout.LINE_HEIGHT - 8) / 2,
                field.kind() == EditorField.Kind.FLAG && on ? ArmatureTheme.title() : ArmatureTheme.faint());

        // And what the field is for, on the label's own hover: the labels are one or two words by
        // necessity, and a form of switches reads as a set of guesses without this.
        if (!field.hint().isEmpty() && cell.label().contains(mouseX, mouseY)) {
            book.bookState.pendingLabels.add(new QuestBookScreen.PendingLabel(cell.label(), field.hint()));
        }

        switch (field.kind()) {
            case FLAG -> {
                // A switch: a chip that lights. The press is the chip and its label together, without the
                // hover frame -- a control that is a chip does not need a box drawn around it as well.
                drawSwitch(r, cell.value(), on);
                BookGeometry.Rect whole = BookGeometry.Rect.at(cell.label().x(), cell.label().y(),
                        Math.max(0, cell.value().right() - cell.label().x()), EntryFormLayout.LINE_HEIGHT);
                registerTarget(QuestBookScreen.EditAction.FLAG, path, whole, whole.x() + 2,
                        whole.y() + 3, value, member,
                        index);
            }
            case NUMBER -> {
                drawChip(r, cell.minus(), "-", cell.minus().contains(mouseX, mouseY));
                drawChip(r, cell.plus(), "+", cell.plus().contains(mouseX, mouseY));
                drawNumber(r, cell.value(), value, field.unit(), "", replaced, mouseX, mouseY);
                // The boxes a number is nudged with, and the box it is typed in. Three presses rather
                // than one: a count is almost always a nudge, and the buttons say which direction.
                registerTarget(QuestBookScreen.EditAction.STEP_DOWN, path, cell.minus(), cell.minus().x() + 3,
                        cell.minus().y() + 3, value, member, index);
                registerTarget(QuestBookScreen.EditAction.STEP_UP, path, cell.plus(), cell.plus().x() + 3,
                        cell.plus().y() + 3, value, member, index);
                target(r, QuestBookScreen.EditAction.FIELD, path, cell.value(), cell.value().x() + 4,
                        cell.value().y() + 3, value, member, index, mouseX, mouseY);
            }
            case CHOICE -> {
                // Unset reads as the default, faint, rather than as an empty box: an empty choice looked
                // broken, where it is the most common answer there is.
                String shown = value.isEmpty() ? "Default" : EditorSpecs.label(value);
                drawValue(r, cell.value(), shown, "", value.isEmpty(), replaced, mouseX, mouseY);
                drawOptionsMark(r, cell.value());
                if (cell.value().contains(mouseX, mouseY)) {
                    // What the press will do, said before it is pressed: a cycle through values is a
                    // control nobody can aim without knowing the ring.
                    book.bookState.pendingLabels.add(new QuestBookScreen.PendingLabel(cell.value(),
                            optionsHint(field)));
                }
                registerTarget(QuestBookScreen.EditAction.CYCLE_CHOICE, path, cell.value(), cell.value().x() + 4,
                        cell.value().y() + 3, value, member, index);
            }
            case ITEM, ICON -> {
                int textX = drawItemValue(r, cell.value(), value, replaced, mouseX, mouseY);
                target(r, QuestBookScreen.EditAction.ITEM, path, cell.value(), textX, cell.value().y() + 3,
                        value, member,
                        index, mouseX, mouseY);
            }
            case TABLE -> book.bookRewards.drawTableField(r, cell, entry, member, index, path, mouseX,
                    mouseY);            case POSITION, SIZE -> {
                String[] axes = {"X", "Y", "Z"};
                for (int axis = 0; axis < 3; axis++) {
                    BookGeometry.Rect box = cell.axes().get(axis);
                    String element = QuestBookScreen.rawValue(entry, field.path() + "." + axis);
                    drawNumber(r, box, element, "", axes[axis], replaced, mouseX, mouseY);
                    target(r, QuestBookScreen.EditAction.FIELD, pathPrefix + "." + field.axis(axis), box,
                            box.x() + 4, box.y() + 3, element, member, index, mouseX, mouseY);
                }
                if (field.kind() == EditorField.Kind.POSITION) {
                    QuestBookScreen.drawEditAffordance(r, cell.action(), cell.action().contains(mouseX, mouseY));
                    String label = "My position";
                    r.text(Measure.truncate(label, cell.action().width() - 6, measure),
                            cell.action().x() + 3, cell.action().y() + 3, ArmatureTheme.body());
                    registerTarget(QuestBookScreen.EditAction.USE_POSITION, path, cell.action(),
                            cell.action().x() + 3,
                            cell.action().y() + 3, value, member, index);
                }
            }
            default -> {
                // Text, and the ids a search lists. A text box carries its hint when it is empty, which
                // is the difference between a box that explains itself and one that does not.
                String shown = value.isEmpty() ? field.hint() : value;
                drawValue(r, cell.value(), shown, "", value.isEmpty(), replaced, mouseX, mouseY);
                if (cell.value().contains(mouseX, mouseY) && !field.hint().isEmpty() && !value.isEmpty()) {
                    book.bookState.pendingLabels.add(new QuestBookScreen.PendingLabel(cell.value(), field.hint()));
                }
                target(r, QuestBookScreen.EditAction.FIELD, path, cell.value(), cell.value().x() + 4,
                        cell.value().y() + 3, value, member, index, mouseX, mouseY);
            }
            case SEARCH, TAG -> {
                // An id with a list behind it: the name a person reads while it is set, and the picker on
                // the press. The raw id becomes the hover label, because the form has room for one word
                // and the spelling a file and an error message use is the other one.
                String shown = value.isEmpty() ? "search\u2026" : friendlyId(value);
                drawValue(r, cell.value(), shown, "", value.isEmpty(), replaced, mouseX, mouseY);
                if (cell.value().contains(mouseX, mouseY) && !value.isEmpty()) {
                    book.bookState.pendingLabels.add(new QuestBookScreen.PendingLabel(cell.value(), value));
                }
                target(r, QuestBookScreen.EditAction.SEARCH, path, cell.value(), cell.value().x() + 4,
                        cell.value().y() + 3, value, member, index, mouseX, mouseY);
            }
        }
    }

    /** The unknown-type row: the raw value, editable whole, drawn under the badge. */
    private void drawRawRow(GuiRenderer r, EntryFormLayout.Form form, String member, int index,
                            int mouseX, int mouseY) {
        BookGeometry.Rect row = BookGeometry.Rect.at(form.name().x(), form.name().y() +
                EntryFormLayout.LINE_HEIGHT, Math.max(20, form.name().width()), EntryFormLayout.LINE_HEIGHT);
        BookGeometry.Rect raw = BookGeometry.Rect.at(row.x(), row.y(),
                Math.min(120, Math.max(20, row.width())), EntryFormLayout.LINE_HEIGHT);
        QuestBookScreen.drawEditAffordance(r, raw, raw.contains(mouseX, mouseY));
        // The label is what the field replaces; the field is transparent, so drawing both would print the
        // raw JSON over the words "edit JSON".
        String path = member + "." + index;
        if (!InlineEdit.replaces(path, book.bookState.editingPath)) {
            r.text("edit JSON", raw.x() + 4, raw.y() + (EntryFormLayout.LINE_HEIGHT - 8) / 2,
                    ArmatureTheme.faint());
        }
        registerInto(book.bookState.editTargets, QuestBookScreen.EditAction.RAW, path, raw, raw.x() + 4,
                raw.y() + (EntryFormLayout.LINE_HEIGHT - 8) / 2, "", member, index);
    }

    /**
     * A number: its axis letter where it has one, the value, and what it counts beside the value.
     *
     * <p>The unit rides with the number rather than at the far edge of the box -- `8 ×`, not `8        ×`.
     * Right-aligned it looked like a stray glyph in an empty box, and an empty number (which is every
     * number until it is set) made the box read as two thirds air. The axis letter stays on a filled box as
     * well as an empty one: "10" under a label that says "Corner" does not say which axis it is.
     */
    private void drawNumber(GuiRenderer r, BookGeometry.Rect box, String value, String unit,
                            String axisLetter, boolean replaced, int mouseX, int mouseY) {
        QuestBookScreen.drawEditAffordance(r, box, box.contains(mouseX, mouseY));
        int baseline = box.y() + (EntryFormLayout.LINE_HEIGHT - 8) / 2;
        int textX = box.x() + 4;
        if (!axisLetter.isEmpty()) {
            r.text(axisLetter, textX, baseline, ArmatureTheme.faint());
            textX += r.textWidth(axisLetter) + 2;
        }
        int tail = unit.isEmpty() ? 0 : r.textWidth(unit) + 4;
        int room = Math.max(0, box.right() - 4 - textX - tail);
        if (!value.isEmpty() && !replaced) {
            String shown = Measure.truncate(value, room, book.textMeasure(r));
            r.text(shown, textX, baseline, ArmatureTheme.body());
            if (!unit.isEmpty()) {
                r.text(unit, textX + r.textWidth(shown) + 4, baseline, ArmatureTheme.faint());
            }
        }
        else if (!replaced && axisLetter.isEmpty()) {
            // Unset is the default, said so, like an unset choice: an empty box between - and + read as
            // a number that failed to load.
            r.text(Measure.truncate("Default", room + tail, book.textMeasure(r)), textX, baseline,
                    ArmatureTheme.faint());
        }
    }

    /** What the press on a choice will do next, for the hover label. */
    private static String optionsHint(EditorField field) {
        StringBuilder out = new StringBuilder();
        for (String option : field.options()) {
            if (!out.isEmpty()) {
                out.append("  \u00b7  ");
            }
            out.append(EditorSpecs.label(option));
        }
        return out.toString();
    }

    /** One entry of a quest's array, or null when the path names nothing that is an object. */
    static JsonObject entryAt(JsonObject quest, String member, int index) {
        JsonElement found = QuestPanelLayout.get(quest, member + "." + index);
        return found != null && found.isJsonObject() ? found.getAsJsonObject() : null;
    }

    /** A registry id as a row reads it: the path, prettified, with a tag's leading `#` kept. */
    private static String friendlyId(String id) {
        return QuestPanelLayout.friendlyId(id);
    }

    /**
     * A task row's sentence, with a registry id inside it prettified where the type's table entry says
     * the argument names one.
     *
     * <p>The count-shaped types pass no argument: their key is written for the count ("%s XP"), which
     * {@code text()} supplies, and there is nothing in it to prettify. See {@link #prettyArg} for the
     * kinds that do.
     */
    static String rowText(String member, ClientQuestCache.TaskEntry task) {
        // A title-less checkmark draws its bare button: legacy packs show no sentence, so neither does
        // this, and the "?" for genuinely-missing labels stays where it belongs — everywhere else.
        if (task.type().equals("tenet:checkmark") && task.label().isEmpty()
                && task.labelArg().isEmpty()) {
            return "";
        }
        String arg = task.labelArg();
        return arg.isEmpty()
                ? task.text().getString()
                : task.text(prettyArg(member, task.type(), arg)).getString();
    }

    /**
     * The named table a reward rolls, or null.
     *
     * <p>A table reward's subject is its table id — the display carries it as the label's argument —
     * so the row can ask the summaries what that table wants. Null for every other reward, and for a
     * table this client was never told about (an older server, or a table that failed to load).
     */
    static ClientQuestCache.TableSummary tableOf(ClientQuestCache.RewardEntry reward) {
        String type = reward.type();
        if (!type.equals("tenet:random") && !type.equals("tenet:loot")
                && !type.equals("tenet:all_table") && !type.equals("tenet:choice")) {
            return null;
        }
        String table = reward.labelArg();
        if (table.isEmpty() || table.equals("inline")) {
            return null;
        }
        return ClientQuestCache.table(table);
    }

    /**
     * A row's argument, with the registry ids in it prettified when the type's table entry says its
     * argument is one -- "minecraft:plains" reads "Plains", "#minecraft:logs" reads "#Logs".
     *
     * <p>The walk, and the reason it is token by token over full ids only, are
     * {@link QuestPanelLayout#prettiedArgument}. What is here and not there is the one kind the client
     * can name better than any id can spell: an advancement. The client holds the advancement's own
     * title, so the walk is given a name to try first and falls back to the prettified id when the
     * client has not been told about that advancement.
     */
    static String prettyArg(String member, String typeId, String arg) {
        if (!typeId.endsWith(":advancement")) {
            return QuestPanelLayout.prettiedArgument(member, typeId, arg);
        }
        return QuestPanelLayout.prettiedArgument(member, typeId, arg, BookQuestEditor::advancementTitle);
    }

    /**
     * An id token's advancement title, or null when this client has not been told about it.
     *
     * <p>One implementation, read from the cache: the adopted quest titles ask the same
     * question one level up, and two spellings of this lookup is how they would come to
     * disagree about what an advancement is called.
     */
    private static String advancementTitle(String id) {
        return ClientQuestCache.advancementTitle(id);
    }

    /**
     * The field of an entry's own form that a control's committed path belongs to, or null.
     *
     * <p>How the presses that need more than a path -- a cycle needs the ring, a search needs the source
     * -- find their field: the form is the registry's, and the path names one of its fields.
     */
    static EditorField fieldAt(JsonObject entry, String member, int index, String path) {
        String prefix = member + "." + index + ".";
        if (!path.startsWith(prefix)) {
            return null;
        }
        String relative = path.substring(prefix.length());
        // A condition's field: the entry's list, the condition, then the field's own path. One level of
        // nesting, and only one -- a condition has no lists of its own. Resolved here rather than by
        // widening EditTarget, so every press that needs its field (a cycle's ring, a search's source)
        // works for a nested field with no change of its own.
        if (relative.startsWith("conditions.")) {
            String tail = relative.substring("conditions.".length());
            int dot = tail.indexOf('.');
            if (dot < 0) {
                return null;
            }
            int conditionIndex;
            try {
                conditionIndex = Integer.parseInt(tail.substring(0, dot));
            }
            catch (NumberFormatException notAnIndex) {
                return null;
            }
            // `entry` is the entry object, and `prefix` already names it: the condition's own path is
            // relative to it, exactly as a field's path is relative to an entry.
            JsonElement found = QuestPanelLayout.get(entry, "conditions." + conditionIndex);
            if (found == null || !found.isJsonObject()) {
                return null;
            }
            String fieldPath = tail.substring(dot + 1);
            for (EditorField field : QuestPanelLayout.conditionEditorFor(found.getAsJsonObject())) {
                if (field.path().equals(fieldPath)) {
                    return field;
                }
            }
            return null;
        }
        for (EditorField field : QuestPanelLayout.editorFor(member, entry)) {
            if (field.path().equals(relative)) {
                return field;
            }
        }
        return null;
    }

    /**
     * The header's own pin control: pin, or unpin, and rebuild so the star follows.
     *
     * <p>Rebuilt rather than redrawn from a field, which is the whole reason there is no field: the control's
     * two states are one predicate read from the store, so a star that disagreed with what is pinned would
     * need a second place to have been written.
     */
    private void pressPin(String questId) {
        if (PinnedQuests.isPinned(questId)) {
            PinnedQuests.unpin(questId);
        }
        else if (!PinnedQuests.pin(questId)) {
            book.toast(Component.translatable("tenet.screen.hud_pins_full", PinnedQuests.MAX_PINS).getString(),
                    true);
        }
        book.rebuildWidgets();
    }

    /**
     * Where the header's pin sits: the back arrow's own box, one control and one gap to its left.
     *
     * <p>Derived here rather than in {@code BookGeometry}, and that is a deliberate limit rather than an
     * oversight: the geometry class exists for rectangles the sweep can assert <i>between elements</i>, and
     * this one's only neighbour is the back arrow -- which is placed by hand two lines above it, in the same
     * method, so a second derivation would be a copy of the number rather than a rule. The two do not
     * overlap, and {@code BookGeometryTest} does not need to know that a header control is 20 pixels.
     */
    static BookGeometry.Rect headerPinSlot(BookGeometry.Rect card) {
        int size = HEADER_CONTROL;
        return BookGeometry.Rect.at(
                card.right() - BookGeometry.MODAL_INSET - size - HEADER_CONTROL_GAP - size,
                card.y() + 13, size, size);
    }

    /** The header's square controls: the back arrow's own size, and the pin's. */
    private static final int HEADER_CONTROL = 20;

    /** The clear space between two of them. */
    private static final int HEADER_CONTROL_GAP = 4;

    /**
     * Commits one text field of one quest link.
     *
     * <p>Why the value's kind is asked of the chapter's tree: a link's fields are typed by what the file
     * holds, and the panel's own reader already answers that question — the same rule the element commit
     * follows, and it needs no path into the chapter, because a link's fields are its own. Links are flat
     * — {@code quest}, {@code x}, {@code y}, {@code shape}, {@code size} — so there is no nested-object
     * branch: every field goes straight in.
     *
     * <p>An empty row means *absent*, which is the rule every other text row in this screen follows: the
     * codec supplies the default, and clearing the target is how a link is parked pointing nowhere until
     * the pick lands.
     */
    private void commitLinkField(String link, String field, String text) {
        if (!book.mayEditNow()) {
            return;
        }
        // From the drafted view rather than the replica, and that is what makes a second edit compose with
        // the first: the replica still holds what the server last sent, so typing a target and then a size
        // would build the second write on a tree without the first.
        QuestLink now = book.bookPins.linkNow(link);
        if (now == null) {
            return;
        }
        String typed = text == null ? "" : text.trim();
        if (typed.isEmpty()) {
            book.bookPins.draftLinkField(link, field, null);
            book.send(new EditorOp.SetLink(link, field, null));
            book.rebuildWidgets();
            return;
        }
        JsonElement value = linkNumber(field, typed);
        if (value == null) {
            book.status("\"" + typed + "\" is not a number", true);
            book.rebuildWidgets();
            return;
        }
        book.bookPins.draftLinkField(link, field, value);
        book.send(new EditorOp.SetLink(link, field, value));
        book.rebuildWidgets();
    }

    /**
     * A text row's words as the link's file holds them: a string for the target, a number for the
     * coordinates and the size, or null when the words are not that shape.
     *
     * <p>Null refuses rather than writes: a size of "wide" is a value the codec would refuse the file
     * over, and the row should not be able to write one. The shape never arrives here — it is a
     * chooser, and the menu writes the word it offered.
     */
    private static JsonElement linkNumber(String field, String typed) {
        if ("quest".equals(field)) {
            return new JsonPrimitive(typed);
        }
        try {
            return new JsonPrimitive((long) Long.parseLong(typed));
        }
        catch (NumberFormatException notANumber) {
            return null;
        }
    }

    /**
     * Removes one link from the chapter's file.
     *
     * <p>And lets go of it, because a selection that outlives its subject is a ring around nothing — the same
     * reason a deleted quest clears {@code selectedQuest}.
     *
     * <p>The removal is <b>drafted</b> before it is sent, so the link and its ring leave the canvas on the
     * press rather than a round trip later, and its pending field values are forgotten with it: a value for a
     * link that no longer exists would be applied to whatever the next link with that id turns out to be.
     */
    private void deleteLink(String id) {
        BookScreenState.linkDraft.remove(QuestBookScreen.effectiveChapter(), id, Util.getMillis());
        BookScreenState.fieldDraft.forgetOwner(QuestBookScreen.effectiveChapter(),
                QuestBookScreen.LINK_OWNER_PREFIX + id);
        book.send(new EditorOp.RemoveLink(id));
        if (id.equals(BookCanvas.selectedLink)) {
            BookCanvas.selectedLink = null;
        }
        if (id.equals(book.bookCanvas.linkConfirmingDelete)) {
            book.bookCanvas.linkConfirmingDelete = null;
        }
    }

    /**
     * One link press row's button, or null when the row is not a link's.
     *
     * <p>Left-aligned like every other picker button in the editor, and carrying no icon: none of these
     * rows is choosing a thing with a stack to wear. The delete row wears its armed state, because a
     * button that asks twice has to say which press it is on.
     */
    private ArmatureButton linkButton(ToolsLayout.Action row) {
        if (dev.ellipog.tenet.client.dev.LinkPanelLayout.ADD_LINK.equals(row.key())) {
            ArmatureButton add = book.control(0, 0, 0, 0,
                    Component.literal(Labels.of("tenet.dev.link.add")), this::createLink);
            add.ink(ArmatureButton.Ink.BODY).alignLeft(true);
            return add;
        }
        String[] link = ChapterPanelLayout.linkFieldOf(row.key());
        if (link == null) {
            return null;
        }
        ArmatureButton button;
        switch (link[1]) {
            case dev.ellipog.tenet.client.dev.LinkPanelLayout.PICK_TARGET -> button = book.control(0, 0, 0, 0,
                    Component.literal(Labels.of("tenet.dev.link.pick")), () -> armLinkPick(link[0]));
            case dev.ellipog.tenet.client.dev.LinkPanelLayout.JUMP_TARGET -> button = book.control(0, 0, 0, 0,
                    Component.literal(Labels.of("tenet.dev.link.jump")), () -> jumpToLink(link[0]));
            case dev.ellipog.tenet.client.dev.LinkPanelLayout.DELETE_LINK -> {
                boolean armed = link[0].equals(book.bookCanvas.linkConfirmingDelete);
                button = book.control(0, 0, 0, 0,
                        Component.literal(armed
                                ? Labels.of("tenet.dev.link.delete_confirm")
                                : Labels.of("tenet.dev.link.delete")),
                        () -> pressDeleteLink(link[0]));
            }
            default -> {
                return null;
            }
        }
        button.ink(ArmatureButton.Ink.BODY).alignLeft(true);
        return button;
    }

    /**
     * Opens a link's target from the Chapter tab: the file behind the marker.
     *
     * <p>Nothing happens for an id this client cannot see, which is the honest answer for a press that
     * arrived about a target the tree has since dropped. Through the navigation the canvas press uses,
     * so a tab press and a canvas press open the same card in the same chapter.
     */
    private void jumpToLink(String id) {
        QuestLink link = book.bookPins.linkNow(id);
        if (link == null) {
            return;
        }
        ClientQuestCache.Entry target = book.entryFor(link.quest().id());
        if (target == null) {
            book.status("No quest with id or alias \"" + link.quest().id() + "\" to open", true);
            return;
        }
        book.navigateToQuest(target.id());
    }

    /**
     * The links' Delete: the first press arms it and says so, the second deletes.
     *
     * <p>Like the element panel's own footer and for the same reason: every destructive control here asks
     * twice. Armed per link, so a Delete left asking its question about one marker must not fire on another.
     */
    private void pressDeleteLink(String id) {
        if (!book.mayEditNow() || book.bookPins.linkNow(id) == null) {
            return;
        }
        if (id.equals(book.bookCanvas.linkConfirmingDelete)) {
            book.bookCanvas.linkConfirmingDelete = null;
            deleteLink(id);
            book.rebuildWidgets();
            return;
        }
        book.bookCanvas.linkConfirmingDelete = id;
        book.status("Press Delete again to remove this link", false);
        book.rebuildWidgets();
    }

    /**
     * Adds a link at the canvas's middle, pointing nowhere yet.
     *
     * <p>Pointing nowhere is a state the validator reports, so this is an unfinished edit rather than a
     * broken one: the target row or the pick finishes it. The middle of the view is where the author is
     * looking, which is the only honest default a canvas position has — snapped to the grid, like a drag.
     */
    private void createLink() {
        if (!book.mayEditNow() || QuestBookScreen.effectiveChapter() == null) {
            return;
        }
        int x = (int) Math.round(BookGeometry.snap(book.viewport().contentX(
                (book.canvasLeft() + book.canvasRight()) / 2.0), BookGeometry.SNAP_GRID, true));
        int y = (int) Math.round(BookGeometry.snap(book.viewport().contentY(
                (book.canvasTop() + book.canvasBottom()) / 2.0), BookGeometry.SNAP_GRID, true));
        com.google.gson.JsonObject tree = new com.google.gson.JsonObject();
        tree.addProperty("quest", "");
        tree.addProperty("x", x);
        tree.addProperty("y", y);
        // The client's own guess, unique against the links on screen: the server prefers a free id,
        // so the guess is usually the answer and the optimistic link is the real one. When it is not,
        // the draft's backstop covers it -- the same arrangement placing an element already has.
        String id = "link";
        int n = 2;
        while (book.bookPins.linkNow(id) != null && n < 1000) {
            id = "link_" + n;
            n++;
        }
        tree.addProperty("id", id);
        QuestLink optimistic = QuestLink.fromJson(tree).orElse(null);
        BookScreenState.linkDraft.insert(QuestBookScreen.effectiveChapter(), optimistic, Integer.MAX_VALUE,
                Util.getMillis());
        book.send(new EditorOp.InsertLink(Integer.MAX_VALUE, tree));
        BookCanvas.selectedLink = id;
        book.bookCanvas.linkConfirmingDelete = null;
        book.rebuildWidgets();
    }

    /**
     * Arms the link-target pick: the next node click names the target, Escape cancels.
     *
     * <p>The link and its chapter travel with the pick, because the author may switch chapters to find
     * the quest they mean — and the write goes to the chapter that holds the link, not the one on
     * screen when the click lands. That is the dependency pick's own arrangement, about one value
     * rather than a list.
     */
    private void armLinkPick(String id) {
        if (!book.mayEditNow() || book.bookPins.linkNow(id) == null || QuestBookScreen.effectiveChapter() == null) {
            return;
        }
        book.bookState.pickingDependency = false;
        book.bookState.pendingPick = null;
        book.bookCanvas.pendingLinkPick = new dev.ellipog.tenet.client.dev.LinkPick(id,
                QuestBookScreen.effectiveChapter());
        book.status("Click the quest to point the link at \u2014 any chapter \u2014 Escape cancels", false);
        book.rebuildWidgets();
    }

    /**
     * The element panel's rows and controls.
     *
     * <p>The rows come from {@code ElementPanelLayout}, keyed {@code element.<id>.<field>} — the same keys the
     * Chapter tab's copy of the form uses, which is what lets the commit, toggle and picker routing below be
     * the screen's existing element paths rather than a second set. The values are read from the
     * <b>drafted</b> element, so a field an author has just typed shows here and on the canvas at once.
     */
    private void buildElementWidgets() {
        CanvasElement element = book.elementNow(book.bookCanvas.elementPanelId);
        if (element == null) {
            // The element went away under the panel -- deleted on another client, or by an undo. Putting the
            // panel away is the only honest answer; its rows would address nothing.
            book.bookCanvas.elementPanelId = null;
            return;
        }
        book.bookCanvas.elementRows = dev.ellipog.tenet.client.dev.ElementPanelLayout.rowsForPanel(
                CanvasElement.asJson(element), book.bookCanvas.elementFolded);
        if (book.bookCanvas.elementConfirmingDelete != null
                && !book.bookCanvas.elementConfirmingDelete.equals(book.bookCanvas.elementPanelId)) {
            // Armed for another element: a Delete left asking its question must not fire on this one.
            book.bookCanvas.elementConfirmingDelete = null;
        }
        book.bookCanvas.elementTargets.clear();
        BookGeometry.Rect body = book.overlayBodyRect();
        book.bookCanvas.elementLayout = ToolsLayout.stack(book.bookCanvas.elementRows, InspectLayout.Mode.STACKED)
                .build(body.width(), Measure.monospace(6, 9));
        // **Forgotten first**, because this view outlives the element it was showing: its children are keyed
        // `element.<id>.<field>`, so a press that opens a second element would leave the first one's widgets
        // registered under keys no layout holds -- hidden, and kept for the session. `ScrollView.put` hides
        // what it registers until the next `apply`, so clearing here cannot flash a stale control.
        book.bookCanvas.elementView.clear();
        book.bookCanvas.elementView.viewport().bounds(body.x(), body.y() + BookRewards.ELEMENT_PREVIEW_HEIGHT,
                body.width(),
                Math.max(0, body.height() - BookRewards.ELEMENT_PREVIEW_HEIGHT));
        book.bookCanvas.elementView.whole(true);
        // **The list's own row pitch, which is what `ScrollBar.pitch` is for**: a wheel notch lands on a row
        // boundary instead of thirty pixels into one, and the row it lands on is a whole stacked row -- the
        // label's band and the control's, plus the gap between them. The sidebar states its own the same way.
        book.bookCanvas.elementView.bar().pitch(InspectLayout.STACKED_ROW_HEIGHT + InspectLayout.STACKED_ROW_GAP);
        for (ToolsLayout.Action row : book.bookCanvas.elementRows) {
            switch (row.kind()) {
                case TEXT -> {
                    ArmatureTextField field = new ArmatureTextField(0, 0, 0, 0, row.value());
                    String key = row.key();
                    field.onSubmit(text -> commitField(key, text, true));
                    field.colours(ArmatureTheme.title(), ArmatureTheme.recessed(), ArmatureTheme.panelEdge());
                    book.bookCanvas.elementView.put(row.key(), field, InspectLayout::controlBand);
                    book.addRenderableWidget(field);
                }
                case SWITCH -> {
                    // **Armature's own switch**, which is what a state row is for -- see
                    // `ToolsLayout.switchSlot` for the size and the party panel's two settings for the same
                    // shape. A button carrying the word is what this arm had, and the word is where it went
                    // wrong: the state was read from the row's `value` field, which a toggle row leaves null
                    // (`Action.toggle` puts it in `buttonLabel`), so the button said Off however the file was
                    // set -- *"the switch buttons that use on and off text only ever show off"*. A switch has
                    // no word to misread: its position is the state.
                    ArmatureSwitch toggle = new ArmatureSwitch(0, 0,
                            ToolsLayout.ON.equals(row.buttonLabel()));
                    toggle.onToggle(() -> pressChapterToggle(row.key(), toggle.selected()));
                    book.bookCanvas.elementView.put(row.key(), toggle, slot -> ToolsLayout.switchSlot(slot,
                            InspectLayout.Mode.STACKED));
                    book.addRenderableWidget(toggle);
                }
                case BUTTON -> {
                    // The one button in the form: the picture's file, which opens the texture picker as this
                    // panel's child column. The key names the element, which is how the picker knows what it
                    // is choosing for -- see `texturePickElement`.
                    //
                    // **Left-aligned, like every other picker button in the editor** -- the chapter tab's two
                    // icon rows and the book's icon are all `.alignLeft(true)`, and a path that started in the
                    // middle of a panel-wide button read as a control that was not lined up with the rows
                    // above it. No icon: an item's picker wears the stack it is choosing, and a texture has
                    // no stack to wear.
                    ArmatureButton pick = book.control(0, 0, 0, 0,
                            Component.literal(row.value().isEmpty()
                                    ? Labels.of("tenet.dev.element.pick_file") : row.value()),
                            () -> book.texturePicker.openTexturePickerFor(book.bookCanvas.elementPanelId));
                    pick.ink(ArmatureButton.Ink.BODY).alignLeft(true);
                    book.bookCanvas.elementView.put(row.key(), pick, InspectLayout::controlBand);
                }
                case HEADING -> {
                    // A section's fold: the same flat invisible button the Chapter tab hangs on every
                    // heading, so a section folds wherever the form is shown. The title heading is gone
                    // from this list (`rowsForPanel`), and what is left is the sections'.
                    ArmatureButton foldButton = book.control(0, 0, 0, 0, Component.literal(""),
                            () -> foldElementSection(row.key()));
                    foldButton.flat(true);
                    book.bookCanvas.elementView.put(row.key(), foldButton);
                }
                default -> {
                    // A read-only row holds no widget: the panel draws it, like the chapter tab's.
                }
            }
        }
        // **And the shared builder for the rest.** A `FIELD` is a scrubbable number and a `CHOICE` is a
        // chooser, and both are built by the one piece of the screen that knows how -- the same call the
        // appearance rows make. Without it a numeric row drew its label and nothing else, which is exactly how
        // it looked: Width, Height, Rotation and Alpha with empty space beside them.
        //
        // **Into this panel's own list, and stacked.** Both halves of that are the fix for what the report
        // showed: the same call used to register every one of these in the *dock's* scroll view, whose layout
        // holds no element key, so each one was hidden the moment it was placed -- and the element form is the
        // stacked composition, so a control belongs in its row's band rather than across the whole of it.
        buildRowControls(book.bookCanvas.elementView, book.bookCanvas.elementRows, true);
        book.bookCanvas.elementView.apply(book.bookCanvas.elementLayout, body.width());
        buildElementFooter(element);
    }

    /**
     * The element panel's footer: what the canvas's right-click menu used to be the only way to reach.
     *
     * <p>Lower and Raise step the draw order one rung — the discoverable path to the {@code order} row,
     * which stays for exact numbers — Duplicate copies the element and opens the panel on the copy, and
     * Delete asks once like every destructive control here. Done closes, leaving the selection, which is
     * what the chrome's own close chip does. Four actions share the quest editor's bar rectangle
     * ({@code BookGeometry.editorBar}), because that is the shape this card's footer already is; Done
     * takes Back's slot beside them.
     */
    private void buildElementFooter(CanvasElement element) {
        ArmatureButton done = book.control(book.overlayControls(false).get("back"),
                Component.translatable("tenet.screen.done"), book::closeOverlay);
        if (done != null) {
            done.ink(ArmatureButton.Ink.BODY)
                    .tooltip(Component.translatable("tenet.screen.escape_also_closes_this"));
        }
        if (!book.mayEditNow()) {
            return;
        }
        BookGeometry.Rect slot = book.geometry().editorBar(book.surfaceCard(PanelKind.ELEMENT));
        if (slot == null) {
            return;
        }
        int gap = QuestBookScreen.EDITOR_ACTION_GAP;
        int left = slot.x();
        int right = slot.right();
        int quarter = Math.max(0, (right - left - gap * 3) / 4);
        BookGeometry.Rect lower = BookGeometry.Rect.at(left, slot.y(), quarter, slot.height());
        BookGeometry.Rect raise =
                BookGeometry.Rect.at(lower.right() + gap, slot.y(), quarter, slot.height());
        BookGeometry.Rect duplicate =
                BookGeometry.Rect.at(raise.right() + gap, slot.y(), quarter, slot.height());
        BookGeometry.Rect delete = BookGeometry.Rect.at(duplicate.right() + gap, slot.y(),
                Math.max(0, right - duplicate.right() - gap), slot.height());
        String id = element.id();
        book.control(lower, Component.translatable("tenet.dev.element.lower"),
                () -> stepElementOrder(id, -1)).ink(ArmatureButton.Ink.BODY)
                .tooltip(Component.translatable("tenet.dev.element.lower_tip"));
        book.control(raise, Component.translatable("tenet.dev.element.raise"),
                () -> stepElementOrder(id, 1)).ink(ArmatureButton.Ink.BODY)
                .tooltip(Component.translatable("tenet.dev.element.raise_tip"));
        book.control(duplicate, Component.translatable("tenet.screen.duplicate"),
                () -> duplicateElementOpen(id)).ink(ArmatureButton.Ink.BODY);
        boolean armed = id.equals(book.bookCanvas.elementConfirmingDelete);
        ArmatureButton remove = book.control(delete,
                Component.literal(armed ? "Really delete?" : "Delete"),
                () -> pressDeleteElement(id));
        if (remove != null) {
            remove.ink(armed ? ArmatureButton.Ink.BLOCKED : ArmatureButton.Ink.BODY);
        }
    }

    /** Folds or unfolds one of the element panel's sections. The Chapter tab folds through its own set. */
    private void foldElementSection(String key) {
        book.closeColourPopover();
        if (!book.bookCanvas.elementFolded.remove(key)) {
            book.bookCanvas.elementFolded.add(key);
        }
        book.rebuildWidgets();
    }

    /**
     * Steps one element's draw order one rung, staying inside the range the form's own row allows.
     *
     * <p>At the rail's ends there is nowhere to go, and a gesture that changes nothing must not cost a
     * save and a history step — the same rule a drag that jittered follows in
     * {@code commitElementEdit}. The draft goes first so the canvas follows the press, like every other
     * element write.
     */
    private void stepElementOrder(String id, int delta) {
        if (!book.mayEditNow()) {
            return;
        }
        CanvasElement now = book.elementNow(id);
        if (now == null) {
            return;
        }
        dev.ellipog.tenet.client.dev.ElementPanelLayout.Range range =
                dev.ellipog.tenet.client.dev.ElementPanelLayout.rangeOf(now.type(), "order");
        int min = range == null ? -64 : (int) Math.round(range.min());
        int max = range == null ? 64 : (int) Math.round(range.max());
        int current = (int) Math.round(dev.ellipog.tenet.client.dev.ElementPanelLayout.numberOf(
                CanvasElement.asJson(now), "order"));
        int next = Math.max(min, Math.min(max, current + delta));
        if (next == current) {
            return;
        }
        JsonElement written = new JsonPrimitive((long) next);
        draftElementField(id, "order", written);
        book.send(new EditorOp.SetElement(id, "order", written));
        book.rebuildWidgets();
    }

    /**
     * The footer Delete: the first press arms it and says so, the second deletes and closes.
     *
     * <p>Closing, because the panel is about the element it just removed: a form whose subject is gone
     * would draw its heading and no fields, and every press in it would go nowhere — which is why
     * {@code deleteElement} already lets go of the selection. The canvas menu's Delete arms the same
     * way, and this is the same question asked from the panel.
     */
    private void pressDeleteElement(String id) {
        if (!book.mayEditNow() || book.elementNow(id) == null) {
            return;
        }
        if (id.equals(book.bookCanvas.elementConfirmingDelete)) {
            book.bookCanvas.elementConfirmingDelete = null;
            book.bookPins.deleteElement(id);
            book.closeOverlay();
            return;
        }
        book.bookCanvas.elementConfirmingDelete = id;
        book.rebuildWidgets();
    }

    /**
     * A copy of one element, with the panel opened on the copy.
     *
     * <p>The canvas menu duplicates onto the canvas and leaves the author to find the copy; from the
     * panel the copy is the thing being edited, so the panel follows it. The insert itself is the same
     * optimistic write {@code duplicateElement} sends, under the fresh id the client chooses.
     */
    private void duplicateElementOpen(String id) {
        if (!book.mayEditNow()) {
            return;
        }
        CanvasElement element = book.elementNow(id);
        if (element == null) {
            return;
        }
        com.google.gson.JsonObject tree = CanvasElement.asJson(element);
        int index = book.bookPins.elementInsertIndex();
        CanvasElement made = book.bookPins.placeElement(tree);
        if (made == null) {
            return;
        }
        BookScreenState.elementDraft.insert(QuestBookScreen.effectiveChapter(), made, index, Util.getMillis());
        book.send(new EditorOp.InsertElement(index, tree));
        book.bookPins.openElementPanel(made.id());
    }

    /** One pin's row key. The id follows the first colon, because an id may hold colons of its own. */
    private static String pinnedRowKey(String id) {
        return "pinned:" + id;
    }

    /**
     * The pinned list's rows: what it is, and one row per pin.
     *
     * <p>Built from the pin file's own order, including the quests the HUD hides: the list manages pins
     * and the HUD watches them, and a finished quest that vanished from one must still be removable in
     * the other. The auto-hide switch lives in the HUD editor rather than here, beside the element it
     * governs. A pin the tree does not hold reads by its id, so a pack edit cannot strand a row with
     * nothing behind it.
     */
    private void buildPinnedWidgets() {
        // No heading row: the card's own header already says "Pinned quests", and the list said it twice.
        List<ToolsLayout.Action> rows = new ArrayList<>();
        List<String> ids = dev.ellipog.tenet.client.hud.PinnedQuests.pinned();
        if (ids.isEmpty()) {
            rows.add(ToolsLayout.Action.value(BookPins.PINNED_EMPTY, "",
                    Labels.of("tenet.hud.pinned_sample")));
        }
        book.bookState.pinnedIcons.clear();
        for (String id : ids) {
            ClientQuestCache.Entry entry = book.entryFor(id);
            rows.add(ToolsLayout.Action.value(pinnedRowKey(id),
                    entry == null ? id : entry.titleText(),
                    entry == null ? "" : entry.chapterTitleText()));
            book.bookState.pinnedIcons.put(id, entry == null ? ItemStack.EMPTY : entry.icon());
        }
        book.bookState.pinnedRows = List.copyOf(rows);
        book.bookState.pinnedTargets.clear();
        BookGeometry.Rect body = book.overlayBodyRect();
        book.bookState.pinnedLayout = ToolsLayout.stack(book.bookState.pinnedRows, InspectLayout.Mode.STACKED)
                .build(body.width(), Measure.monospace(6, 9));
        book.bookState.pinnedView.clear();
        book.bookState.pinnedView.viewport().bounds(body.x(), body.y(), body.width(), body.height());
        book.bookState.pinnedView.whole(true);
        book.bookState.pinnedView.bar().pitch(InspectLayout.STACKED_ROW_HEIGHT + InspectLayout.STACKED_ROW_GAP);
        book.bookState.pinnedView.apply(book.bookState.pinnedLayout, body.width());

        ArmatureButton done = book.control(book.overlayControls(false).get("back"),
                Component.translatable("tenet.screen.done"), book::closeOverlay);
        if (done != null) {
            done.ink(ArmatureButton.Ink.BODY)
                    .tooltip(Component.translatable("tenet.screen.escape_also_closes_this"));
        }
    }

    /** The header's arrow: back to the card this one was reached from, as it was left. */
    private void backInCards() {
        if (book.bookState.overlayHistory.isEmpty()) {
            return;
        }
        QuestBookScreen.CardVisit visit = book.bookState.overlayHistory.remove(book.bookState.overlayHistory.size() - 1);
        ClientQuestCache.Entry target = book.entryFor(visit.questId());
        if (target == null) {
            // The quest was deleted while its card sat on the stack: a blank card is worse than a
            // dropped visit, so the visit goes and the button rebuilds without it.
            book.rebuildWidgets();
            return;
        }
        if (!target.chapterId().equals(QuestBookScreen.effectiveChapter())) {
            book.bookSidebar.selectChapter(target.chapterId());
        }
        BookScreenState.selectedQuest = visit.questId();
        BookScreenState.multiSelection.clear();
        book.bookRewards.openOverlay(visit.questId());
        // `openOverlay` resets the body to the top and drops the folds; the visit remembers how this
        // card was left, and a step back that lost the reader's place would not be a step back.
        book.bookState.overlayView.scrollTo(visit.scrollY());
        book.bookState.entryFolded.clear();
        book.bookState.entryFolded.addAll(visit.folded());
    }

    /**
     * The fault at one problem-row position, in the order the tab lists them, or null.
     *
     * <p>The press hit-tests rectangles while this counts rows, and the two walk the same list
     * in the same order — which is what makes the index the press found name the line this
     * reads.
     */
    private String problemLineAt(int index) {
        String prefix = ChapterPanelLayout.VALUE_PREFIX + "problem:";
        int seen = 0;
        for (ToolsLayout.Action row : book.bookState.chapterRows) {
            if (!row.key().startsWith(prefix)) {
                continue;
            }
            if (seen == index) {
                return row.value();
            }
            seen++;
        }
        return null;
    }

    /** Opens the player's own text-size card. */
    private void openSettingsOverlay() {
        book.closeOverlay();
        book.applyColumns(PanelStack.asRoot(book.columns(), PanelKind.SETTINGS));
        book.bookState.appearanceView.scrollTo(0);
        book.rebuildWidgets();
    }

    /**
     * The card's control: the text-size slider.
     *
     * <p>The row is composed once, on open, by {@link SettingsLayout}. The control calls into
     * {@code ClientAppearance.LOOK}, which persists as it changes: there is no Save button because
     * there is no unsaved state, and the header's second line says whether the file is even writable
     * rather than pretending.
     *
     * <h2>The card is sized to its rows</h2>
     *
     * <p>It used the full-height modal and read as a mostly-empty panel; two rows want a small card.
     * The width is asked for first — {@code modalFramed}'s width does not depend on its height, which
     * is the invariant that makes this possible — then the rows are built at that width, and the card
     * is sized to what they came to. The party panel sizes its faces the same way.
     */
    private void buildSettingsWidgets() {
        appearanceEntries = SettingsLayout.entries(ClientAppearance.LOOK.textScale());
        book.bookState.appearanceRows = appearanceEntries.stream()
                .map(entry -> SettingsLayout.SIDEBAR_KEY.equals(entry.key())
                        || SettingsLayout.HUD_KEY.equals(entry.key())
                        ? InspectRow.toggle(entry.key(), entry.label().getString())
                        : InspectRow.action(entry.key(), entry.label().getString()))
                .toList();

        int cardWidth = book.framed(PanelKind.SETTINGS).width();
        int bodyWidth = Math.max(0, cardWidth - BookGeometry.MODAL_INSET * 2);
        book.bookState.appearanceLayout = InspectLayout.build(book.bookState.appearanceRows, bodyWidth,
                BookScreenState.TEXT_MEASURE);

        // The rail, and the body from it: the rows' own height used to size a centred card, and a rail is as
        // tall as the canvas — so the band is what the rail leaves above its footer, and the list scrolls
        // inside it. See `appearanceBody`, which is the same expression for the wheel and the press.
        book.bookState.appearanceCard = book.framed(PanelKind.SETTINGS);
        int bodyHeight = Math.max(0,
                book.bookState.appearanceCard.height() - QuestBookScreen.BODY_TOP - BookGeometry.MODAL_CHROME);

        book.bookState.appearanceView.clear();
        book.bookState.appearanceView.whole(true);
        Viewport body = book.bookState.appearanceView.viewport().bounds(
                book.bookState.appearanceCard.x() + BookGeometry.MODAL_INSET,
                        book.bookState.appearanceCard.y() + QuestBookScreen.BODY_TOP,
                bodyWidth, bodyHeight);

        // The one control: the slider, whose message carries the value so it can follow the thumb.
        ArmatureSlider slider = new ArmatureSlider(0, 0, bodyWidth, TextScale.MIN, TextScale.MAX,
                0.05D, ClientAppearance.LOOK.textScale());
        slider.label(textSizeLabel(slider.value()));
        slider.onChange(() -> {
            // Live, and without a rebuild: the text resizes while the thumb moves, and a rebuild
            // mid-drag would destroy the widget being dragged.
            ClientAppearance.LOOK.setTextScale(slider.value());
            slider.label(textSizeLabel(slider.value()));
        });
        book.addRenderableWidget(slider);
        book.bookState.appearanceView.put(SettingsLayout.TEXT_KEY, slider);

        // And the row that opens the HUD editor. An action row rather than a strip, because what it changes
        // is not one number: where things sit wants a place to drag them, and that place is a screen over
        // the world. Opened from here it comes back here, which is what `openFromBook` is for.
        //
        // Labelled like the switch below it, with a short Edit in the control column: a full-width
        // button was the one control on the card that ignored the column every other one sits in.
        ArmatureButton hudEdit = book.control(0, 0, 0, 0, Component.translatable("tenet.screen.edit"),
                HudEditScreen::openFromBook);
        if (hudEdit != null) {
            hudEdit.tooltip(Component.translatable("tenet.screen.hud_edit.open"));
            book.bookState.appearanceView.put(SettingsLayout.HUD_KEY, hudEdit, slot -> {
                Slot strip = InspectLayout.strip(slot);
                int width = Math.min(56, strip.width());
                int height = Math.min(18, strip.height());
                return new Slot(slot.key(), controlRight(slot, bodyWidth) - width,
                        strip.y() + Math.max(0, (strip.height() - height) / 2), width, height);
            });
        }

        // And the chapter list's placement as a switch in its row's own strip: the row names the
        // placement and the switch shows which way it stands, so the state reads at a glance. The
        // strip is the layout's own (`InspectLayout.strip`), so the switch sits where the drawn
        // label stops — one derivation for the ink and the control, which is what keeps a switch
        // attached to its row rather than floating beside it.
        ArmatureSwitch sidebarSwitch = new ArmatureSwitch(0, 0, DevMode.isSidebarFloating());
        sidebarSwitch.onToggle(() -> setSidebarFloating(sidebarSwitch.selected()));
        book.addRenderableWidget(sidebarSwitch);
        book.bookState.appearanceView.put(SettingsLayout.SIDEBAR_KEY, sidebarSwitch, slot -> {
            Slot strip = InspectLayout.strip(slot);
            int width = Math.min(ArmatureSwitch.WIDTH, strip.width());
            int height = Math.min(ArmatureSwitch.HEIGHT, strip.height());
            return new Slot(slot.key(), controlRight(slot, bodyWidth) - width,
                    strip.y() + Math.max(0, (strip.height() - height) / 2), width, height);
        });

        book.bookState.appearanceView.apply(book.bookState.appearanceLayout, body.viewWidth());

        ArmatureButton back = book.control(book.geometry().questFooter(book.bookState.appearanceCard,
                false).get("back"),
                Component.translatable("tenet.screen.done"), book::closeOverlay);
        if (back != null) {
            back.ink(ArmatureButton.Ink.BODY);
        }
    }

    /**
     * The right edge every settings control ends on: the slider's, which spans the body.
     *
     * <p>The strip's own edge stopped a hundred pixels short of it, so the switch and Edit hung in the
     * middle of the card under a slider that ran to its border -- two right edges on a three-row card.
     */
    private static int controlRight(Slot row, int bodyWidth) {
        return row.x() + bodyWidth;
    }

    /** The slider's message: the setting's name and its value, kept current as the thumb moves. */
    private static Component textSizeLabel(double scale) {
        return Component.translatable("tenet.screen.text_size", Math.round(scale * 100));
    }

    /**
     * A palette press: the author's choice, which persists and beats the pack's from now on.
     *
     * <p>The tools panel's, now — the theme list lives there and only there, because choosing a theme
     * can undo a pack's whole planned look and that is the pack author's decision rather than a
     * player's. See {@code ToolsLayout.PALETTE_SECTION}.
     */
    private void chooseTheme(String name) {
        if (ClientAppearance.LOOK.setTheme(name)) {
            book.rebuildWidgets();
        }
    }

    /**
     * The editor's card: the layout, and the four things the drawing reads from the same build.
     *
     * <h2>Why the four are cached with it</h2>
     *
     * <p>Because they are one answer rather than five. The per-entry line counts are what <i>size</i> the
     * rows the layout places, so a layout without them is a card whose rows are in the wrong place; the
     * prose is what the layout was wrapped from and what the body draws. Caching the layout alone would
     * mean recomputing all four anyway — which is the work — or drawing a card from last frame's shape.
     *
     * <h2>The key, and the one input that is not a revision</h2>
     *
     * <p>{@code draft} is {@link dev.ellipog.tenet.client.dev.FieldDraft#version}: a stepper press
     * writes a draft and does not move the tree or the replica, so without it a card would keep showing
     * the shape it had before the press. {@code text} is the font and the player's text scale, which
     * change how wide every string is without changing any of them.
     */
    private EditorCard editorCard(GuiRenderer r, ClientQuestCache.Entry entry, JsonObject quest,
                                  Viewport body) {
        ClientChapterReplica.Copy copy = ClientChapterReplica.of(entry.chapterId());
        EditorCardKey key = new EditorCardKey(entry.id(), ClientQuestCache.treeRevision(),
                copy == null ? 0L : copy.revision(), BookScreenState.fieldDraft.version(), body.viewWidth(),
                TextEpoch.now());
        if (editorCard != null && key.equals(editorCardKey)) {
            return editorCard;
        }

        List<String> description = book.editorControls.shownDescription(entry, quest);
        // Per-entry line counts, not a count: an entry whose controls wrap is taller, and the layout
        // has to place the rows below it against the height the drawing will use.
        List<Integer> taskLines = entryLineCounts("tasks", quest, body.viewWidth());
        List<Integer> rewardLines = entryLineCounts("rewards", quest, body.viewWidth());
        List<String> dependencies = QuestPanelLayout.strings(quest, "dependsOn");
        Layout layout = OverlayLayout.stack(editorProse(r, description, body.viewWidth()),
                        taskLines, rewardLines, dependencies.size(), true)
                .build(body.viewWidth(), book.textMeasure(r));

        EditorCard built = new EditorCard(layout, description, taskLines, rewardLines, dependencies);
        editorCard = built;
        editorCardKey = key;
        return built;
    }

    /** What the editor's card was built from. See {@link #editorCard}. */
    private record EditorCardKey(String questId, long tree, long replica, long draft, int width,
                                 long text) {
    }

    /** The editor's card, and the four things its drawing reads beside the layout. */
    private record EditorCard(Layout layout, List<String> description, List<Integer> taskLines,
                              List<Integer> rewardLines, List<String> dependencies) {
    }

    private EditorCard editorCard;
    private EditorCardKey editorCardKey;

    /**
     * The editor's prose: the raw paragraphs, wrapped by the plain measure, markdown markers and all.
     *
     * <p>Because the author is editing the markdown *itself*: a field that hid the markers would have to
     * map every caret position through characters it does not draw, and this is the trade that was chosen
     * deliberately. It is also why a marked-up description can wrap at different words here and after the
     * edit is committed.
     */
    private List<OverlayLayout.Prose> editorProse(GuiRenderer r, List<String> description, int column) {
        Measure measure = book.textMeasure(r);
        List<OverlayLayout.Prose> prose = new ArrayList<>(description.size());
        for (String element : description) {
            prose.add(OverlayLayout.Prose.of(element, column, measure));
        }
        return prose;
    }

    /**
     * The prose, at the slots the layout placed it in, for a caller that has the lines already.
     *
     * <p>The editor draws the description it is <i>editing</i> rather than the one on file, because the
     * layout was built from those lines -- a paragraph drawn from the file under a layout built from
     * the editor's text is two descriptions of one block, which is the fault this file keeps naming.
     */
    private void drawDescription(GuiRenderer r, List<String> description, Layout layout,
                                 Viewport body, Measure measure) {
        if (description.isEmpty()) {
            // Nothing to draw: the editor's empty row is a click target, not a canvas.
            return;
        }

        for (int i = 0; i < description.size(); i++) {
            Slot slot = BookSettings.placed(layout, body, OverlayLayout.proseKey(i));
            if (slot == null) {
                continue;
            }
            int lineY = slot.y();
            for (String line : TextWrap.wrap(description.get(i), body.viewWidth(), measure)) {
                r.text(line, slot.x(), lineY, ArmatureTheme.body());
                lineY += measure.lineHeight();
            }
        }
    }

    /** Closes the accepted-items list, dropping the expression it was read from. */
    private void closeFilterPanel() {
        book.bookState.filterExpression = "";
        book.bookState.filterLayout = null;
        book.applyColumns(PanelStack.afterClose(book.columns(), true));
        book.rebuildWidgets();
    }

    /** The list's own footer: one Back control, like every child rail's. */
    private void buildFilterWidgets() {
        ArmatureButton back = book.control(book.overlayControls(false).get("back"),
                Component.translatable("tenet.screen.back"), this::closeFilterPanel);
        if (back != null) {
            back.ink(ArmatureButton.Ink.BODY)
                    .tooltip(Component.translatable("tenet.screen.escape_also_closes_this"));
        }
    }

    // ------------------------------------------------------------------
    // The quest panel's edits
    // ------------------------------------------------------------------

    /**
     * A quest's title with its legacy tokens still in it, for the surfaces that wear colours.
     *
     * <p>The same rename-winning read as {@link QuestBookScreen#titleOf} — the same words, agreed by construction —
     * and only the ink differs. Measuring still takes the stripped reading, because colours are
     * widthless and the two must agree about how wide the words are.
     */
    static String titleOfRaw(ClientQuestCache.Entry entry) {
        return BookScreenState.fieldDraft.text(entry.chapterId(), entry.id(), "title", entry.titleTextRaw());
    }

    /**
     * Leaves whichever page is over the card: the table's type pick closes back into the editor, and the
     * card's own pages are left by {@link BookRewards#leaveCardPage}.
     *
     * <p>One method for the three ways out of a page -- Escape, the footer's Back and a press outside --
     * so the page cannot be left one way by the key and another by the button.
     */
    void leaveTypePage() {
        if (book.bookState.typePickReturn) {
            book.questEditor.closeTypePick();
        }
        else {
            book.bookRewards.leaveCardPage();
        }
    }


    /**
     * Adds one dependency, by the id typed into the Add row.
     *
     * <p>The two refusals the server would make anyway are made here first, because a refusal that
     * arrives after a round trip reads as a delay rather than as an answer: a duplicate is already in
     * the list, and a quest cannot depend on itself. Everything else -- a mistyped id, a cycle, a quest
     * in another chapter -- is the loader's validator, and its message is the one the reply carries.
     */
    /**
     * Opens a description's link in the platform's browser -- <b>http and https only</b>.
     *
     * <p>A description is content somebody else may have written -- a published questline -- so the scheme
     * is checked here rather than trusted: a {@code file:} target or a made-up scheme is refused with a word
     * in the status line, never opened and never silently ignored.
     */
    void openLink(String url) {
        java.net.URI uri;
        try {
            uri = java.net.URI.create(url);
        }
        catch (IllegalArgumentException malformed) {
            book.status("tenet.status.that_link_is_not_a_valid_address", true);
            return;
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(java.util.Locale.ROOT);
        if (!scheme.equals("http") && !scheme.equals("https")) {
            book.status("tenet.status.only_http_and_https_links_open", true);
            return;
        }
        // The opener moved with the platform code: `Util.OS` is an enum again, and links open
        // through Blaze3D.
        com.mojang.blaze3d.Blaze3D.openUri(uri);
    }

    /**
     * The texture row's commit: the id canonicalised and looked up before anything is written.
     *
     * <h2>Why the file is checked here, and why the check can only be a courtesy</h2>
     *
     * <p>An id that resolves to nothing draws nothing -- the painter returns on an unresolvable
     * texture -- so writing one would be an edit whose only visible effect is a canvas that quietly
     * stopped being tiled. The resource manager is the client's own copy of the pack, so the check is
     * exactly as current as the pack the author is looking at, and a path that passes it may still be
     * absent on another player's client. What it catches is the typo, which is the common case; what it
     * cannot catch is a pack that disagrees with itself.
     *
     * <p>The status names the <b>resolved</b> path rather than what was typed, because the two differ
     * whenever the input was lowercase-folded, namespace-defaulted or carried a leading
     * {@code textures/} -- and the author needs to see the id the file will actually carry.
     *
     * <p>The boolean answer exists for the picker: the field's own submit ignores it and shows the
     * status line instead, while a pressed row has to know whether it may close -- a press that failed
     * the file check keeps the list open with the refusal beside it, rather than closing over the
     * reason.
     *
     * @return true when the texture was written, false when the id did not resolve to a file
     */
    boolean commitCanvasTexture(String typed) {
        String raw = typed == null ? "" : typed.trim();
        String canonical = CanvasBackground.canonicalTexture(raw);
        if (canonical.isEmpty()) {
            book.status("\"" + raw + "\" is not a texture id - try mymod:textures/gui/bg.png", true);
            return false;
        }
        Identifier id = Identifier.tryParse(canonical);
        if (id == null || book.minecraft() == null || book.minecraft().getResourceManager() == null
                || book.minecraft().getResourceManager().getResource(id).isEmpty()) {
            book.status("No texture at " + canonical, true);
            return false;
        }
        if (book.bookCanvas.texturePickElement != null) {
            // An element's picture: the whole image object, because the two arms are exclusive and choosing a
            // file is what clears the sprite. Drafted first, like every other element edit, so the canvas shows
            // the new file at once rather than a round trip later.
            JsonObject source = new JsonObject();
            source.addProperty("texture", canonical);
            book.questEditor.draftElementField(book.bookCanvas.texturePickElement, "image", source);
            book.send(new EditorOp.SetElement(book.bookCanvas.texturePickElement, "image", source));
            book.status("Picture file " + canonical, false);
            book.rebuildWidgets();
            return true;
        }
        CanvasBackground current = book.editedBackground();
        book.questEditor.writeThemeBackground(new CanvasBackground(current.kind(), current.space(), current.spacing(),
                current.tuning(),
                new CanvasBackground.Image(canonical, current.image().fit(),
                        current.image().tileSize())));
        book.status("Canvas texture " + canonical, false);
        book.rebuildWidgets();
        return true;
    }

    /**
     * Whether this player may edit the questline.
     *
     * <p>Permission level two: the level `/tenet reload` declares, read here through the player rather
     * than through a command source. The two cannot drift because there is one number and this comment
     * names it -- and the day the rule changes, the command is where it should change.
     */
    boolean mayEdit() {
        if (book.bookState.frameActive) {
            return book.bookState.frameMayEdit;
        }
        return dev.ellipog.tenet.QuestAuthority.mayEditClient(book.minecraft());
    }

    /**
     * The item picker's search box: the one widget it has, because every row is drawn and hit-tested by
     * the same derivation the list class owns.
     *
     * <h2>One method, two ways in</h2>
     *
     * <p>The picker opens as a page of the quest card and, since the Chapter tab's icon became pickable,
     * as an overlay of its own -- see {@link PanelKind#PICKER}. Both need this box built identically, and
     * the alternative to extracting it was a second copy of the placeholder, the clip and the redraw
     * ordering below, each of which is a thing that only looks right when it is the same in both.
     *
     * <p>Rebuilt with the value it already held: a tick rebuild (a replica arriving mid-search) must not
     * clear what is being typed.
     */
    /**
     * The pickers' search boxes: the item picker's while it is over the card, and the type page's.
     *
     * <p>They are the same control in the same place of the same shape of page, so they are built by one
     * method — and rebuilt with the text they already held, because a tick rebuild (a replica arriving
     * mid-search) must not clear what is being typed.
     */
    void buildPickerWidgets() {
        book.questEditor.buildItemSearch();
        book.questEditor.buildTypeSearch();
    }

    /**
     * The same, without the mark: for a piece whose mark is drawn somewhere the press is not -- the
     * description's frame, which is drawn outside the content's clip. Returns whether anything was
     * registered, so a caller drawing the mark can skip a box with no size.
     */
    boolean registerTarget(QuestBookScreen.EditAction action, String path, BookGeometry.Rect box, int textX,
                                   int textY, String value, String member, int index) {
        return registerInto(book.bookState.editTargets, action, path, box, textX, textY, value, member, index);
    }

    /**
     * The same, into the list the caller names.
     *
     * <p>The list is a parameter because a panel has its own: the Assets panel draws its chrome and its
     * footer with the helpers below, presses from {@link BookAssets#assetsTargets}, and never reads
     * {@link QuestBookScreen#editTargets} while it is open. A helper that decides the destination for its caller made
     * New table, Done and the card's cross unreachable -- drawn as controls, registered in a list the
     * panel that presses them does not walk. See {@code drawAssets} for the other half.
     *
     * <p>The box and its text origin arrive shifted for the slide: a press must meet rows where they
     * are drawn rather than where their layout says, for the tenth of a second a rail is travelling.
     * See {@link QuestBookScreen#shiftHit}.
     */
    boolean registerInto(List<QuestBookScreen.EditTarget> into, QuestBookScreen.EditAction action, String path,
                                 BookGeometry.Rect box, int textX, int textY, String value,
                                 String member, int index) {
        if (box.width() <= 0 || box.height() <= 0) {
            return false;
        }
        BookGeometry.Rect hit = book.shiftHit(box);
        into.add(new QuestBookScreen.EditTarget(action, path, hit, textX + hit.x() - box.x(), textY, value, member,
                index));
        return true;
    }

    /**
     * A value box: a faint frame, its text, and a right-aligned tail for a unit or a mark.
     *
     * <p>{@code replaced} is the inline editor's contract: the field drawing over this box draws the value,
     * and two drawings of one value is the fault that contract exists to prevent. The frame stays -- an
     * empty box where a value was reads as the row having lost it.
     */
    void drawValue(GuiRenderer r, BookGeometry.Rect box, String text, String tail, boolean faint,
                           boolean replaced, int mouseX, int mouseY) {
        QuestBookScreen.drawEditAffordance(r, box, box.contains(mouseX, mouseY));
        int baseline = box.y() + (EntryFormLayout.LINE_HEIGHT - 8) / 2;
        int room = box.width() - 8 - (tail.isEmpty() ? 0 : r.textWidth(tail) + 4);
        if (!text.isEmpty() && !replaced) {
            r.text(Measure.truncate(text, Math.max(0, room), book.textMeasure(r)), box.x() + 4, baseline,
                    faint ? ArmatureTheme.faint() : ArmatureTheme.body());
        }
        if (!tail.isEmpty()) {
            r.text(tail, box.right() - 4 - r.textWidth(tail), baseline, ArmatureTheme.faint());
        }
    }

    /** The chip a stepper's `-` and `+` are drawn as. */
    void drawChip(GuiRenderer r, BookGeometry.Rect box, String glyph, boolean hot) {
        QuestBookScreen.drawEditAffordance(r, box, hot);
        r.text(glyph, box.x() + (box.width() - r.textWidth(glyph)) / 2,
                box.y() + (EntryFormLayout.LINE_HEIGHT - 8) / 2,
                hot ? ArmatureTheme.title() : ArmatureTheme.body());
    }

    /** The switch a flag is drawn as: filled when on, empty when off. */
    void drawSwitch(GuiRenderer r, BookGeometry.Rect cell, boolean on) {
        int size = EntryFormLayout.BUTTON;
        BookGeometry.Rect chip = BookGeometry.Rect.at(cell.x(), cell.y() + (EntryFormLayout.LINE_HEIGHT - size) / 2,
                size, size);
        int edge = on ? ArmatureTheme.title() : ArmatureTheme.faint();
        r.fill(chip.x(), chip.y(), chip.right(), chip.y() + 1, edge);
        r.fill(chip.x(), chip.bottom() - 1, chip.right(), chip.bottom(), edge);
        r.fill(chip.x(), chip.y() + 1, chip.x() + 1, chip.bottom() - 1, edge);
        r.fill(chip.right() - 1, chip.y() + 1, chip.right(), chip.bottom() - 1, edge);
        if (on) {
            // A filled middle rather than a tick: the dot matrix has no check mark -- see the glyph list
            // `BookGeometry.TOOLS_PILL_WIDTH` records, where `\u2713` is absent.
            r.fill(chip.x() + 3, chip.y() + 3, chip.right() - 3, chip.bottom() - 3, ArmatureTheme.title());
        }
    }

    /** The mark that says a box cycles when pressed. `\u25bc` is a glyph the default font carries. */
    void drawOptionsMark(GuiRenderer r, BookGeometry.Rect box) {
        String mark = "\u25bc";
        r.text(mark, box.right() - 4 - r.textWidth(mark),
                box.y() + (EntryFormLayout.LINE_HEIGHT - 8) / 2, ArmatureTheme.faint());
    }

    /**
     * An item field's box: the icon, the item's own name, and -- on hover -- the id a file spells.
     *
     * <p>The name rather than the id, which is what the fields beside it do: a search field shows "Plains"
     * and a tag shows "Ores", so an item field showing {@code minecraft:cobblestone} made one form speak
     * two vocabularies. The id is a hover away, which is where it belongs -- and it is what a missing item
     * still shows, because there is no name to show for one.
     */
    int drawItemValue(GuiRenderer r, BookGeometry.Rect box, String value, boolean replaced,
                              int mouseX, int mouseY) {
        int boxSize = Math.max(8, box.height() - 2);
        ItemStack stack = QuestBookScreen.itemStack(value);
        boolean missing = !value.isEmpty() && stack.isEmpty();
        if (missing) {
            QuestBookScreen.drawItemPlaceholder(r, box.x() + 1, box.y() + 1, boxSize);
        }
        else {
            r.icon(stack, box.x() + 1, box.y() + 1, boxSize);
        }
        int baseline = box.y() + (EntryFormLayout.LINE_HEIGHT - 8) / 2;
        int textX = box.x() + box.height() + 2;
        int room = Math.max(0, box.right() - 4 - textX - (missing ? r.textWidth("missing") + 4 : 0));
        if (replaced) {
            // The open field draws the words; the icon stays, because a blank where the item was would
            // read as the row having lost it.
            return textX;
        }
        if (missing) {
            r.text(Measure.truncate(value, room, book.textMeasure(r)), textX, baseline, ArmatureTheme.blocked());
            r.text("missing", box.right() - 4 - r.textWidth("missing"), baseline, ArmatureTheme.blocked());
            if (box.contains(mouseX, mouseY)) {
                book.bookState.pendingLabels.add(new QuestBookScreen.PendingLabel(box,
                        "missing - the mod is not installed; the id is kept"));
            }
            return textX;
        }
        String shown = stack.isEmpty() ? "pick an item" : stack.getHoverName().getString();
        r.text(Measure.truncate(shown, room, book.textMeasure(r)), textX, baseline,
                stack.isEmpty() ? ArmatureTheme.faint() : ArmatureTheme.body());
        // The id, on hover: the badge says "Item" and the box says "Cobblestone", and the spelling a file
        // and an error message use is the one thing neither of them says.
        if (!value.isEmpty() && box.contains(mouseX, mouseY)) {
            book.bookState.pendingLabels.add(new QuestBookScreen.PendingLabel(box, value));
        }
        return textX;
    }

    /** A reward row's sentence; the task row's rule, one member over. */
    static String rowText(String member, ClientQuestCache.RewardEntry reward) {
        // A table that asks for its title names the row: the legacy `use_title`. The reward's own
        // sentence is the generic roll ("Roll the X table"); the table's title is what the author
        // wants read instead. Only for a named table whose summary says so — an inline table has no
        // summary, and a table that says nothing keeps the generic sentence a version-16 client drew.
        ClientQuestCache.TableSummary table = BookQuestEditor.tableOf(reward);
        if (table != null && table.useTitle()) {
            return table.titleText();
        }
        String arg = reward.labelArg();
        return arg.isEmpty()
                ? reward.text().getString()
                : reward.text(BookQuestEditor.prettyArg(member, reward.type(), arg)).getString();
    }
}
