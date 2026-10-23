package dev.ellipog.tenet.client;

import dev.ellipog.armature.client.Look;
import dev.ellipog.armature.client.ArmatureTheme;
import dev.ellipog.armature.client.render.GuiRenderer;
import dev.ellipog.armature.client.render.TextEpoch;
import dev.ellipog.armature.client.ui.inspect.InspectLayout;
import dev.ellipog.armature.client.ui.inspect.InspectRow;
import dev.ellipog.armature.client.ui.kit.Colour;
import dev.ellipog.armature.client.ui.kit.Layout;
import dev.ellipog.armature.client.ui.kit.Measure;
import dev.ellipog.armature.client.ui.kit.Slot;
import dev.ellipog.armature.client.ui.kit.TextWrap;
import dev.ellipog.armature.client.ui.kit.Viewport;
import dev.ellipog.tenet.Tenet;
import dev.ellipog.tenet.client.viewer.RecipeLookups;
import dev.ellipog.tenet.quest.EditorField;
import dev.ellipog.tenet.client.dev.InlineEdit;
import dev.ellipog.tenet.client.dev.ItemPickerLayout;
import dev.ellipog.tenet.client.dev.Labels;
import dev.ellipog.tenet.client.dev.QuestPanel;
import dev.ellipog.tenet.client.dev.SearchCatalogue;
import dev.ellipog.tenet.progress.QuestState;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Util;
import net.minecraft.world.item.ItemStack;
import com.mojang.blaze3d.platform.InputConstants;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The quest book's settings card: the quest's and the chapter's settings, as an author edits them.
 *
 * <p>Moved out of {@link QuestBookScreen} as it stood, with the state only it reads; the screen
 * keeps the state it shares and reaches this through its {@code bookSettings} field.
 */
final class BookSettings {

    private final QuestBookScreen book;

    BookSettings(QuestBookScreen book) {
        this.book = book;
    }

    /** The key prefix of a filter list's rows; the match's index follows. */
    private static final String FILTER_PREFIX = "filter:";

    /** The filter list's rows and layout, for the expression that is open. */
    private List<InspectRow> filterListRows = List.of();
    private int filterLayoutWidth = -1;

    /**
     * What the filter rows were resolved and truncated against: the locale overlay and the
     * font epoch. A language or font change rebuilds the rows even at the same width, or the
     * list would keep the old language's words cut to the old font's widths.
     */
    private long filterTextRevision = -1;
    private long filterStamp = -1;

    /** The filter rows' drawn strings, truncated at build in row order. See {@link #filterListRows}. */
    private List<String> filterShownText = List.of();

    /** The list's row rectangles, from the last frame's own drawing — the press contract. */
    private final List<BookGeometry.Rect> filterListRects = new ArrayList<>();

    /** The matches the open list's rows name, in row order, from the same pass. */
    private final List<ItemStack> filterShown = new ArrayList<>();

    /**
     * The reader card's prerequisite rows, from the last frame's drawing: where a press navigates or
     * locates.
     *
     * <p>The same lifecycle as {@link QuestBookScreen#rowItems} — rebuilt by the drawing, read by the click — with one
     * row carrying two targets: the row itself is the jump to that quest's card, and the locate icon at
     * its right edge (or a middle-click or shift-click anywhere on it) is "show me where it is".
     */
    private final List<DependencyTarget> dependencyTargets = new ArrayList<>();

    private record DependencyTarget(Slot row, BookGeometry.Rect locate, String questId) {
    }

    /** Per-tab queries beside the item picker's, so switching tabs starts each list fresh. */
    private String pickerEntityQuery = "";

    /** The tab strip's rectangles from the last frame's own drawing — the press contract. */
    private final Map<QuestBookScreen.PickerKind, BookGeometry.Rect> pickerTabRects =
            new java.util.EnumMap<>(QuestBookScreen.PickerKind.class);

    // ------------------------------------------------------------------
    // The settings card
    // ------------------------------------------------------------------

    /** The settings card: the rows, the two lines of header, and the scrollbar when it is needed. */
    void drawSettingsOverlay(GuiRenderer r, int mouseX, int mouseY) {
        if (book.bookState.appearanceCard == null) {
            return;
        }
        int left = book.bookState.appearanceCard.x();
        int top = book.bookState.appearanceCard.y();
        int w = book.bookState.appearanceCard.width();
        int h = book.bookState.appearanceCard.height();

        BookFrame.drawCard(r, left, top, w, h, 46, true);

        int textX = left + 14;
        r.text("Settings", textX, top + 12, ArmatureTheme.title());
        // Whether a choice can be remembered at all, from the accessor Look grew for exactly this: a
        // client whose config directory never resolved saves nothing, and a card that said "saved" would
        // be lying about it.
        r.text(ClientAppearance.LOOK.file() == null
                        ? "This session only - nothing can be saved here"
                        : "Saved as you change them",
                textX, top + 26, ArmatureTheme.faint());

        if (book.bookState.appearanceLayout == null) {
            return;
        }
        Viewport body = book.bookState.appearanceView.viewport();
        book.bookState.appearanceView.apply(book.bookState.appearanceLayout, body.viewWidth());
        // The arrival's travel, re-applied after the placing above: this draw re-places every
        // frame, which would otherwise pin every widget while its own panel travels — a switch
        // that stays behind while its card slides in reads as not attached to it. Same bases
        // `slidePanelWidgets` reads, so the two cannot disagree about where a widget lives; the
        // offset is absolute rather than added, so running both is one travel rather than two.
        long settingsNow = Util.getMillis();
        if (book.panelRevealing(PanelKind.SETTINGS, settingsNow)) {
            int travel = PanelLayout.slideOffset(book.panelRail(PanelKind.SETTINGS),
                    book.panelRevealElapsed(PanelKind.SETTINGS, settingsNow), book.panelRevealMillis());
            if (travel != 0) {
                for (String key : List.of(SettingsLayout.TEXT_KEY, SettingsLayout.HUD_KEY,
                        SettingsLayout.SIDEBAR_KEY)) {
                    AbstractWidget widget = book.bookState.appearanceView.get(key);
                    if (widget == null) {
                        continue;
                    }
                    Integer base = book.bookState.slideBaseX.get(widget);
                    if (base == null) {
                        base = widget.getX();
                        book.bookState.slideBaseX.put(widget, base);
                    }
                    widget.setX(base + travel);
                }
            }
        }
        QuestPanel.drawRows(r, BookGeometry.Rect.at(body.originX(), body.originY(),
                body.viewWidth(), body.viewHeight()), body, book.bookState.appearanceLayout,
                book.bookState.appearanceRows, mouseX, mouseY);
        // The bar, which draws nothing at all when the rows fit -- so the `if` that used to guard this
        // call is gone: it was a second answer to a question the bar already answers, and the two would
        // have disagreed the day a card grew a scrollbar it did not need.
        QuestBookScreen.drawBar(r, book.bookState.appearanceView.bar(), mouseX, mouseY);
    }


    /**
     * The picker's own card, for a pick that is not a quest's.
     *
     * <p>The same card the quest editor draws its picker into -- the panel, the header strip, the rule
     * under it -- with a heading of its own instead of a quest's name, because there is no quest behind
     * it. The list and the search box are the picker's own code, unchanged: one picker, one list, two
     * ways in.
     */
    void drawPickerOverlay(GuiRenderer r, int mouseX, int mouseY) {
        int left = book.overlayLeft();
        int top = book.overlayTop();
        int w = book.overlayWidth();
        int h = book.overlayHeight();

        BookFrame.drawCard(r, left, top, w, h, 46, false);

        // The subject's icon and name, so the card says what it is about: the row the press came from is
        // behind it, and "Pick an item" alone would not say which chapter's or group's icon is being set.
        // A texture or a sprite draws in the same box by the node's own priority, so a chapter wearing
        // either greets with its picture rather than a blank.
        int iconBox = QuestBookScreen.HEADER_ICON;
        int iconX = left + 14;
        int iconY = top + (46 - iconBox) / 2;
        net.minecraft.resources.Identifier pickTextureId =
                book.bookState.pickTexture.isEmpty() ? null
                        : net.minecraft.resources.Identifier.tryParse(book.bookState.pickTexture);
        net.minecraft.resources.Identifier pickSpriteId =
                book.bookState.pickSprite.isEmpty() ? null
                        : net.minecraft.resources.Identifier.tryParse(book.bookState.pickSprite);
        if (!book.bookState.pickIcon.isEmpty()) {
            r.icon(book.bookState.pickIcon, iconX, iconY, iconBox);
        }
        else if (pickTextureId != null) {
            r.texture(pickTextureId, iconX, iconY, iconBox, iconBox);
        }
        else if (pickSpriteId != null) {
            r.sprite(pickSpriteId, iconX, iconY, iconBox, iconBox, 0xFFFFFFFF);
        }
        int textX = iconX + iconBox + 6;
        // What the pick is for, in the caller's words; the target's own sentence is the fallback for the
        // three icon picks that have nobody to say it. See `openTableItemPick` for what the old
        // arrangement got wrong: a pick with no target of its own fell through to "a chapter's icon".
        //
        // Both lines stop short of the fold control when this column carries one: the subject line is a
        // table's or a quest's name, and a name is a pack's string rather than a length this file knows.
        String heading = book.bookState.pickTitle.isEmpty()
                ? book.bookState.pickTarget == QuestBookScreen.PickTarget.BOOK ? "Choose the book's icon"
                : book.bookState.pickTarget == QuestBookScreen.PickTarget.GROUP ? "Choose the group's icon"
                : "Choose the chapter's icon"
                : book.bookState.pickTitle;
        int headerRoom = Math.max(0, book.headerTextRoom(book.surfaceCard(book.bookState.surfaceKind)) - textX);
        Measure headerMeasure = Measure.of(r::textWidth, r.lineHeight());
        r.text(Measure.truncate(heading, headerRoom, headerMeasure),
                textX, top + 12, ArmatureTheme.title());
        if (!book.bookState.pickName.isEmpty()) {
            r.text(Measure.truncate(book.bookState.pickName, headerRoom, headerMeasure),
                    textX, top + 26, ArmatureTheme.faint());
        }

        // The list under that header: the item catalogue, or the type page a table opened -- which is the
        // same card, the same header and the same footer with a different list, because that is what the
        // button beside `+ Item` now does.
        if (book.typePageOpen()) {
            // No tabs over the type list: the strip belongs to picture picks, and a stale
            // rectangle would answer presses for a list that is not on screen.
            pickerTabRects.clear();
            book.editorControls.drawTypePicker(r, BookGeometry.Rect.at(book.overlayBody().originX(),
                    book.overlayBody().originY(),
                    book.overlayBody().viewWidth(), book.overlayBody().viewHeight()), mouseX, mouseY);
        }
        else {
            if (book.pickerTabsVisible()) {
                drawPickerTabs(r, mouseX, mouseY);
            }
            else {
                pickerTabRects.clear();
            }
            book.itemPicker.drawItemPicker(r, book.overlayBody(), mouseX, mouseY);
        }
    }

    /**
     * The four arms as tabs under the picker's header: Item, File, Sprite, Entity.
     *
     * <p>One control for "the quest's picture" rather than one control per arm: an author thinks
     * in pictures, and the arm is the file's business. The strip reads the kind, and the press
     * below answers it; the rectangles are kept for the press, like every drawn control here.
     *
     * <p>Under the search box, above the list: the box filters (or, on Sprites, <i>is</i>) the
     * tab's content, so the strip sits between the question and its answer rather than above
     * both. The list below insets past it (see the draw dispatch), so no row hides under a tab.
     */
    private void drawPickerTabs(GuiRenderer r, int mouseX, int mouseY) {
        int left = book.overlayLeft();
        int width = book.overlayWidth();
        int y = book.overlayBody().originY() + ItemPickerLayout.SEARCH_HEIGHT + 2;
        pickerTabRects.clear();
        QuestBookScreen.PickerKind[] kinds = QuestBookScreen.PickerKind.values();
        int tabWidth = width / kinds.length;
        for (int i = 0; i < kinds.length; i++) {
            int x = left + i * tabWidth;
            int w = i == kinds.length - 1 ? left + width - x : tabWidth;
            BookGeometry.Rect rect = BookGeometry.Rect.at(x, y, w, QuestBookScreen.PICKER_TAB_STRIP);
            pickerTabRects.put(kinds[i], rect);
            boolean selected = book.bookState.pickerKind == kinds[i];
            if (selected) {
                r.fill(rect.x(), rect.y(), rect.right(), rect.bottom(), ArmatureTheme.recessed());
            }
            else if (rect.contains(mouseX, mouseY)) {
                QuestBookScreen.drawEditAffordance(r, rect, true);
            }
            String label = switch (kinds[i]) {
                case ITEM -> Labels.of("tenet.dev.picker.tab_item");
                case FILE -> Labels.of("tenet.dev.picker.tab_file");
                case SPRITE -> Labels.of("tenet.dev.picker.tab_sprite");
                case ENTITY -> Labels.of("tenet.dev.picker.tab_entity");
            };
            r.text(label, rect.x() + (rect.width() - r.textWidth(label)) / 2,
                    rect.y() + (rect.height() - 8) / 2,
                    selected ? ArmatureTheme.title() : ArmatureTheme.faint());
        }
        r.fill(left + 1, y + QuestBookScreen.PICKER_TAB_STRIP, left + width - 1,
                y + QuestBookScreen.PICKER_TAB_STRIP + 1,
                ArmatureTheme.panelEdge());
    }

    /**
     * A press on the tab strip: the kind changes and that tab's list starts fresh.
     *
     * <p>Fresh because an index into another tab's list names nothing here: the query box is
     * cleared and the draw loop rebuilds the list from the empty query, which is the fresh-list
     * rule the texture picker already follows. Pressing the open tab changes nothing.
     */
    boolean pressPickerTab(double mouseX, double mouseY) {
        for (Map.Entry<QuestBookScreen.PickerKind, BookGeometry.Rect> tab : pickerTabRects.entrySet()) {
            if (tab.getValue().contains(mouseX, mouseY)) {
                if (tab.getKey() != book.bookState.pickerKind) {
                    book.bookState.pickerKind = tab.getKey();
                    if (book.bookState.itemSearch != null) {
                        book.bookState.itemSearch.setValue("");
                    }
                    book.bookState.pickerSelected = -1;
                    book.bookState.pickerBody.setScrollY(0);
                    book.bookState.textureSelected = -1;
                    book.bookState.textureBody.setScrollY(0);
                    preparePickerTab();
                    book.rebuildWidgets();
                }
                return true;
            }
        }
        return false;
    }

    /**
     * The newly opened tab's list state: what rows it draws from.
     *
     * <p>Items re-list the catalogue and what is carried; entities list the entity registry
     * through the same search-picker shape registry fields use (first rows on an empty box,
     * ranked past that); files and sprites keep the texture picker's own state, read at draw
     * from the shared box. Sources are nulled for the picture tabs so no registry branch in
     * the shared commit paths can mistake them for a field search.
     */
    private void preparePickerTab() {
        if (book.bookState.pickerKind == QuestBookScreen.PickerKind.ENTITY) {
            book.bookState.pickerSource = EditorField.Source.ENTITY;
            book.bookState.pickerObserveType = "";
            book.bookState.pickerEntries = SearchCatalogue.list(EditorField.Source.ENTITY, "");
            book.bookState.pickerInventory = List.of();
            book.bookState.pickerMatches = List.of();
            book.bookState.pickerRows = List.of();
            book.bookState.pickerFrame = null;
            book.bookState.pickerQuery = "";
            pickerEntityQuery = "";
        }
        else if (book.bookState.pickerKind == QuestBookScreen.PickerKind.ITEM) {
            book.bookState.pickerSource = null;
            book.bookState.pickerObserveType = "";
            book.bookState.pickerEntries = BookItemPicker.catalogue();
            book.bookState.pickerInventory = BookItemPicker.carried();
            book.bookState.pickerMatches = List.of();
            book.bookState.pickerRows = List.of();
            book.bookState.pickerFrame = null;
            book.bookState.pickerQuery = "";
        }
        else {
            book.bookState.pickerSource = null;
            book.bookState.pickerObserveType = "";
            book.bookState.pickerFileQuery = "";
        }
    }

    void drawOverlay(GuiRenderer r, int mouseX, int mouseY, long now) {
        ClientQuestCache.Entry entry = book.entryFor(book.bookState.overlayQuest);
        if (entry == null) {
            return;
        }

        int left = book.overlayLeft();
        int top = book.overlayTop();
        int w = book.overlayWidth();
        int h = book.overlayHeight();

        BookFrame.drawCard(r, left, top, w, h, 46, true);

        // --- header ---

        QuestState state = shownState(entry);
        // The header icon is drawn to its own box, and the text starts after that box, so the two are
        // the same layout decision. The title used to start at a hardcoded left+38 with a 16px icon at
        // left+14, which is 8px of gap -- close enough to look intentional and not derived from
        // anything, so moving the icon would have moved the text by accident.
        int iconBox = QuestBookScreen.HEADER_ICON;
        int iconX = left + 14;
        int iconY = top + (46 - iconBox) / 2;
        // The icon sits in a slot: a recessed square two pixels proud of the icon on every side, the
        // same well the task and reward rows give their items, so the header's picture reads as the
        // quest's item rather than as a glyph floating beside the title.
        r.fill(iconX - 3, iconY - 3, iconX + iconBox + 3, iconY + iconBox + 3, ArmatureTheme.recessed());
        ItemStack headerIcon = entry.icon();
        if (headerIcon.isEmpty() && entry.iconId().isEmpty() && entry.textureIcon().isEmpty()
                && entry.spriteIcon().isEmpty()) {
            // No icon in any arm: the first task's picture, cycling for a filter — the same
            // adoption the node draws, so the header and the canvas agree.
            headerIcon = ClientQuestCache.adoptedQuestIcon(entry);
        }
        if (headerIcon.isEmpty() && !entry.iconId().isEmpty()) {
            // An icon id the build cannot resolve: the placeholder where the item would be, a small
            // mark beside it, and the sentence on hover -- the header has no room for a word, but the
            // fact must not be silent. The editor's icon row and the picker both say it in full.
            QuestBookScreen.drawItemPlaceholder(r, iconX, iconY, iconBox);
            r.text("!", iconX + iconBox - 2, iconY - 2, ArmatureTheme.blocked());
            book.bookState.pendingLabels.add(new QuestBookScreen.PendingLabel(BookGeometry.Rect.at(iconX, iconY,
                    iconBox, iconBox),
                    "missing item - the id is kept, so the mod can come back"));
        }
        else if (!entry.textureIcon().isEmpty()
                && net.minecraft.resources.Identifier.tryParse(entry.textureIcon()) != null) {
            // A texture icon draws through the blit rather than the stack: the cache keeps the stack
            // empty for a texture, so the item path below would draw nothing.
            r.texture(net.minecraft.resources.Identifier.parse(entry.textureIcon()),
                    iconX, iconY, iconBox, iconBox);
        }
        else if (!entry.spriteIcon().isEmpty()
                && net.minecraft.resources.Identifier.tryParse(entry.spriteIcon()) != null) {
            // An atlas region draws from the atlas: the stack is empty for a sprite for the same
            // reason it is for a texture.
            r.sprite(net.minecraft.resources.Identifier.parse(entry.spriteIcon()),
                    iconX, iconY, iconBox, iconBox, 0xFFFFFFFF);
        }
        else {
            r.icon(headerIcon, iconX, iconY, iconBox);
        }

        int textX = iconX + iconBox + 6;
        // The title and the subtitle are drawn unless their own field is open and drawing them -- see
        // `InlineEdit.replaces`. The state tag stays either way: it is not part of the title, and its x
        // is derived from the title's width, so it does not move when the title's drawing changes hands.
        // Colours are widthless, so the tag sits where the stripped measure says whether or not the
        // title wears them.
        // The three titles draw from remembered cuts rather than re-parsing per frame: same runs, same
        // advances, decided per stamp. See headerCutEpoch for what moves a stamp.
        long cutEpoch = headerCutEpoch(r);
        // The header's text stops short of the pin and the back arrow on its right: at the larger GUI
        // scales the card is narrow enough for a long subtitle to run underneath them.
        int textLimit = BookQuestEditor.headerPinSlot(BookGeometry.Rect.at(left, top, w, h)).x() - 8;
        String badge = cardStateLabel(state);
        int titleRoom = Math.max(0, textLimit - textX - r.textWidth(badge) - 16);
        int titleAdvance = Math.min(titleRoom, r.textWidth(QuestBookScreen.titleOf(entry)));
        if (!InlineEdit.replaces("title", book.bookState.editingPath)) {
            titleAdvance = RichText.draw(r,
                    RichText.cut(BookQuestEditor.titleOfRaw(entry), titleRoom, book.textMeasure(r), cutEpoch),
                    textX, top + 12, InlineEdit.ink("title"));
        }
        drawStateBadge(r, badge, textX + titleAdvance + 8, top + 12, stateColour(state));

        String whereSub = BookQuestEditor.subtitleOf(entry);
        // The read line carries the chapter name as well; the field edits the subtitle alone, so
        // drawing both would print the chapter title through the field's text. Each part wears its
        // own colours, drawn left to right off the running advance.
        //
        // `RichText.draw` answers an advance, not an x: the running x is the start plus every advance so
        // far. It used to be assigned straight from the draw, which put the subtitle at the window's
        // left edge -- hidden behind the chapter list at most scales, and printed through the header at
        // GUI scale 4, where the card sits far enough left for the two to meet.
        if (!InlineEdit.replaces("subtitle", book.bookState.editingPath)) {
            int atX = textX + RichText.draw(r,
                    RichText.cut(entry.chapterTitleTextRaw(), Math.max(0, textLimit - textX),
                            book.textMeasure(r), cutEpoch),
                    textX, top + 26, InlineEdit.ink("subtitle"));
            int subRoom = textLimit - atX - r.textWidth("  ·  ");
            if (!whereSub.isEmpty() && subRoom > r.textWidth("...") * 2) {
                r.text("  \u00b7  ", atX, top + 26, InlineEdit.ink("subtitle"));
                atX += r.textWidth("  \u00b7  ");
                atX += RichText.draw(r,
                        RichText.cut(BookQuestEditor.subtitleOfRaw(entry), subRoom, book.textMeasure(r),
                                cutEpoch),
                        atX, top + 26, InlineEdit.ink("subtitle"));
            }
            // The guide book page this quest belongs to, when it names one. A reference in
            // faint ink, and nothing more: Tenet has no guide integration, so there is no
            // press and no page to open -- the line says where the quest is documented.
            String guide = "  \u00b7  Guide: " + entry.guidePage();
            if (!entry.guidePage().isEmpty() && atX + r.textWidth(guide) <= textLimit) {
                r.text(guide, atX, top + 26, ArmatureTheme.faint());
            }
        }

        // In edit mode the body is the editor: the same card, the same header, and the fields below
        // instead of the prose. The reader's body is skipped whole -- drawing both would draw the
        // description through the fields.
        if (book.mayEditNow()) {
            book.questEditor.drawQuestEditor(r, entry, mouseX, mouseY, now);
            return;
        }

        // --- body: one layout, one clip, one scroll ---

        // Bounds first, then the layout at that width, then the scroll range from the layout's own
        // height. The order matters: apply() clamps the offset against the view's height, so clamping
        // against a stale bounds would clamp to the previous window's body.
        Viewport body = book.overlayBody();
        Measure measure = book.textMeasure(r);
        Layout layout = overlayLayout(r, entry);
        book.bookState.overlayView.apply(layout, body.viewWidth());

        // Which row the pointer is over, resolved once for the whole body before anything is drawn.
        // `update` is called exactly once per frame rather than once per row: a pointer is a single
        // point, so there is at most one hovered row and asking per row would be the same answer
        // computed three times while never being told that the answer is "none".
        book.bookState.rowHover.update(rowAt(layout, body, rowKeysOf(entry), mouseX, mouseY), now);

        // The clip, the scroll clamp and the scrollbar all read this one rectangle. They used to be
        // three expressions for it — bodyLeft/bodyRight/bodyTop/bodyBottom written out in the drawing
        // and again in the clamp — which is the same class of mistake as the two controls that were
        // once drawn on top of each other.
        //
        // The clickable rows are rebuilt from this pass, and cleared here rather than with the tooltip
        // lists at the end of the frame: a press arrives *between* frames, so the list it reads has to
        // be the last frame's drawing — the same lifecycle `editTargets` has, and the reason a list
        // cleared after drawing would always be empty by the time a click asks.
        book.bookState.rowItems.clear();
        book.bookState.filterRows.clear();
        book.bookState.checkmarkRows.clear();
        book.bookState.rewardClaimRows.clear();
        dependencyTargets.clear();
        try (GuiRenderer.Scoped clip = r.clip(body)) {
            drawProse(r, layout, body, mouseX, mouseY);
            drawTasks(r, entry, layout, body, mouseX, mouseY, now);
            drawRewards(r, entry, layout, body, mouseX, mouseY, now);
            drawDependencies(r, entry, layout, body, mouseX, mouseY, now);
        }

        // The bar, and it draws nothing when the content fits. Its geometry comes from the same
        // viewport as the clip and the clamp, so a thumb that stops short of the end is not a thing
        // that can happen here.
        // The theme's own scrollbar tokens, for the same reason the sidebar's uses them: the overlay's
        // bar was drawn from `recessed` and `controlEdge`, neither of which was chosen for the job.
        QuestBookScreen.drawBar(r, book.bookState.overlayView.bar(), mouseX, mouseY);
    }

    /**
     * The body's elements, in order, at the width the body actually has.
     *
     * <p>The one call that decides both where every row goes and how tall the content is. That is the
     * property this round was about: {@code measureOverlay} was a second pass over the same fields,
     * kept in step with the drawing by a comment.
     */
    private Layout overlayLayout(GuiRenderer r, ClientQuestCache.Entry entry) {
        Viewport body = book.overlayBody();
        // What the reader may see, from the same rules the canvas hides quests with. The editor never
        // comes through here -- it builds its own stack with everything in it -- because an author
        // looking at a card must see every field they can edit.
        QuestState state = shownState(entry);
        boolean text = dev.ellipog.tenet.client.dev.QuestVisibility
                .showsText(entry.hideTextUntilComplete(), state);
        boolean details = dev.ellipog.tenet.client.dev.QuestVisibility
                .showsDetails(entry.hideDetailsUntilStartable(), state);

        // Everything the layout below is built from, as one value. Rebuilt only when one of these moves,
        // which is not an optimisation to be sprinkled: the markdown is parsed and wrapped here, every
        // task and reward's label is measured for its line count, and the whole element stack is laid
        // out — per frame, for a card that usually changes only when the reader does something.
        //
        // `progress` is in the key because two of the four inputs above come from the quest's *state*:
        // a quest that becomes startable reveals its details, which is a taller card. `textEpoch` is the
        // font and the player's text scale, the same pair that invalidates a measured width.
        ReaderCardKey key = new ReaderCardKey(entry.id(), ClientQuestCache.treeRevision(),
                ClientQuestCache.progressRevision(), body.viewWidth(), TextEpoch.now());
        if (readerCard != null && key.equals(readerCardKey)) {
            return readerCard;
        }

        Layout built = OverlayLayout.stack(
                        readerProse(r, text ? entry.descriptionText() : List.of(), body.viewWidth()),
                        entry.tasks().size(), entry.rewards().size(),
                                QuestBookScreen.dependenciesOf(entry).size(), false,
                        new OverlayLayout.Reveal(text, details))
                .build(body.viewWidth(), book.textMeasure(r));
        readerCard = built;
        readerCardKey = key;
        return built;
    }

    /** What the reader's card was built from. See {@link #overlayLayout}. */
    private record ReaderCardKey(String questId, long tree, long progress, int width, long text) {
    }
    private ReaderCardKey readerCardKey;

    /**
     * The reader's description as markdown: every element parsed into blocks, every block wrapped through
     * the styles, and both kept for the frame -- the layout gets the lines and widths, the drawing gets the
     * runs, and {@code readerProse} carries what the second needs.
     *
     * <p>Wrapped here rather than by the layout because a bold word is wider than the same word plain: only
     * a caller holding a font can say where a marked-up line breaks. The editor's own path wraps the raw
     * text with the plain measure instead -- same shape, other producer, and the difference between them is
     * the one the author sees when a marked-up description re-flows as they click out of the field.
     *
     * <p>Legacy tokens are read first, per paragraph: the colour codes become the look the drawing wears,
     * page breaks become paragraph breaks, and links and pictures are consumed into runs the drawing
     * draws. What the markdown pass sees is the residual words, so the two grammars never parse each
     * other's markers -- and the overlay is indexed by the residual, which is the only indexing the
     * drawing can ask for.
     */
    private List<OverlayLayout.Prose> readerProse(GuiRenderer r, List<String> description, int column) {
        book.bookState.readerProse.clear();
        book.bookState.linkRects.clear();
        // Measured at the font's own size: a block's scale is the kit's to apply (RichText.scale), so the
        // wrapped widths and the drawn runs come from one rule rather than two.
        dev.ellipog.armature.client.ui.kit.RichText.StyledWidth styled = (text, bold, italic) -> r.styledWidth(text, bold, italic, 1F);
        List<OverlayLayout.Prose> prose = new ArrayList<>();
        for (String element : description) {
            for (RichText.Rendered page : RichText.pages(element, BookSettings::substituteText)) {
                emitPage(r, prose, page, column, styled);
            }
        }
        return List.copyOf(prose);
    }

    /**
     * One substitution, resolved against the locale overlay the description already resolved through.
     *
     * <p>Missing keys arrive as blank rather than as a raw key on screen: the description this runs in
     * has already resolved every key it could, so what is left is content to fix rather than words.
     */
    private static String substituteText(String key) {
        String resolved = ClientLocale.find(key);
        return resolved == null ? "" : resolved;
    }

    /**
     * One rendered page: its words in blocks, its pictures between them.
     *
     * <p>Pictures stand at residual offsets, so the words split around them: text before the first,
     * between each pair, and after the last. Empty splits around a picture are skipped — they are
     * incidental, where page boundaries are intentional spacing — but a page with no words at all
     * still emits one empty block, because consecutive breaks are a wider gap and an empty page
     * that reserved nothing would collapse them into one.
     */
    private void emitPage(GuiRenderer r, List<OverlayLayout.Prose> prose, RichText.Rendered page,
                          int column, dev.ellipog.armature.client.ui.kit.RichText.StyledWidth styled) {
        int cursor = 0;
        for (RichText.PlacedImage placed : page.images()) {
            if (placed.offset() > cursor) {
                emitWords(r, prose, page, cursor, placed.offset(), column, styled);
            }
            emitImage(prose, placed, column);
            cursor = placed.offset();
        }
        if (cursor < page.residual().length() || page.images().isEmpty()) {
            emitWords(r, prose, page, cursor, page.residual().length(), column, styled);
        }
    }

    /** Words as blocks, with the look rebased onto the chunk. */
    private void emitWords(GuiRenderer r, List<OverlayLayout.Prose> prose, RichText.Rendered page,
                           int from, int to, int column, dev.ellipog.armature.client.ui.kit.RichText.StyledWidth styled) {
        String chunk = page.residual().substring(from, to);
        List<RichText.Span> spans = new ArrayList<>();
        for (RichText.Span span : page.spans()) {
            if (span.start() < to && span.end() > from) {
                spans.add(new RichText.Span(Math.max(span.start(), from) - from,
                        Math.min(span.end(), to) - from, span.argb(), span.link(), span.quest(),
                        span.bold(), span.italic(), span.underline()));
            }
        }
        for (dev.ellipog.armature.client.ui.kit.RichText.Paragraph block : dev.ellipog.armature.client.ui.kit.RichText.parse(chunk)) {
            List<dev.ellipog.armature.client.ui.kit.RichText.Line> lines = dev.ellipog.armature.client.ui.kit.RichText.wrap(block, column, styled);
            List<String> texts = new ArrayList<>(lines.size());
            int widest = 0;
            for (dev.ellipog.armature.client.ui.kit.RichText.Line line : lines) {
                texts.add(block.text().substring(line.start(), line.end()));
                widest = Math.max(widest, dev.ellipog.armature.client.ui.kit.RichText.styledWidth(block, line.start(), line.end(), styled));
            }
            book.bookState.readerProse.add(new QuestBookScreen.ProseBlock(block, lines, List.copyOf(spans), null));
            // The block's own pitch, from the same rule the drawing advances by: a heading's lines are
            // taller, and the space reserved for them has to be the space they take.
            prose.add(new OverlayLayout.Prose(texts, widest,
                    Math.round(OverlayLayout.LINE_HEIGHT * dev.ellipog.armature.client.ui.kit.RichText.scale(block))));
        }
    }

    /** One picture as a block the full column wide and the picture tall. */
    private void emitImage(List<OverlayLayout.Prose> prose, RichText.PlacedImage placed, int column) {
        RichText.ImageBox box = RichText.fitImage(placed.image(), column);
        book.bookState.readerProse.add(new QuestBookScreen.ProseBlock(null, List.of(), List.of(), placed));
        prose.add(new OverlayLayout.Prose(List.of(""), column, box.height()));
    }

    /**
     * The prose, at the slot the layout placed it in.
     *
     * <p>Wrapped again here, with the same {@link Measure} and the same column width the layout wrapped
     * it with. That is not a second description of the wrap rule — it is the same pure function called
     * with the same arguments, which is the property {@code TextWrap.height} exists to guarantee.
     *
     * <p>The width is the <b>body's</b>, not the slot's: a left-aligned text slot is as wide as its
     * longest line, so wrapping to it would narrow the paragraph a little more on every pass.
     */
    private void drawProse(GuiRenderer r, Layout layout, Viewport body, int mouseX, int mouseY) {
        if (book.bookState.readerProse.isEmpty()) {
            // Nothing to draw: a quest with no description has no description block at all, and the
            // layout places no row for one.
            return;
        }
        book.bookState.linkRects.clear();
        for (int i = 0; i < book.bookState.readerProse.size(); i++) {
            Slot slot = placed(layout, body, OverlayLayout.proseKey(i));
            if (slot == null) {
                continue;
            }
            QuestBookScreen.ProseBlock block = book.bookState.readerProse.get(i);
            // A picture block draws its file, not words: the slot reserves the column the layout gave
            // it, and the picture sits in it by its alignment. Missing files draw nothing, the way a
            // chapter picture with no file behind it draws nothing — a reader with half a pack still
            // reads the words around the gap.
            if (block.image() != null) {
                drawInlineImage(r, slot, body, block.image());
                continue;
            }
            // A heading takes the ink the card's own section headings take, so a markdown heading reads as
            // a heading rather than as louder prose.
            int ink = block.paragraph().kind() == dev.ellipog.armature.client.ui.kit.RichText.Kind.HEADING
                    ? ArmatureTheme.heading() : ArmatureTheme.body();
            int advance = Math.round(OverlayLayout.LINE_HEIGHT * dev.ellipog.armature.client.ui.kit.RichText.scale(block.paragraph()));
            int lineY = slot.y();
            // The token look runs alongside the markdown runs, indexed by where the piece starts: the two
            // grammars tile the same words, and a piece's start is always inside exactly one span, because
            // the spans tile the residual without gaps. An author interleaving codes with markdown spans
            // gets the look of the span the piece starts in, which is the documented approximation.
            int offset = 0;
            for (dev.ellipog.armature.client.ui.kit.RichText.Line line : block.lines()) {
                int x = slot.x();
                offset = line.start();
                for (dev.ellipog.armature.client.ui.kit.RichText.Piece piece : dev.ellipog.armature.client.ui.kit.RichText.pieces(block.paragraph(), line)) {
                    RichText.Span look = RichText.spanAt(block.spans(), offset);
                    boolean bold = piece.bold() || (look != null && look.bold());
                    boolean italic = piece.italic() || (look != null && look.italic());
                    String link = piece.link() != null ? piece.link()
                            : look == null || look.link().isEmpty() ? null : look.link();
                    String quest = look == null || look.quest().isEmpty() ? null : look.quest();
                    boolean underline =
                            link != null || quest != null || (look != null && look.underline());
                    int width = r.styledWidth(piece.text(), bold, italic, piece.scale());
                    BookGeometry.Rect box = link == null && quest == null ? null
                            : BookGeometry.Rect.at(x, lineY, width, advance);
                    boolean hot = box != null && box.contains(mouseX, mouseY);
                    int colour;
                    if (hot) {
                        colour = ArmatureTheme.title();
                    }
                    else if (look != null && look.argb() != RichText.WHITE) {
                        colour = look.argb();
                    }
                    else {
                        colour = piece.code() ? ArmatureTheme.faint() : ink;
                    }
                    // One call per piece: a styled run carries no colour, so a line's colours are the
                    // caller's to move between -- and the same loop that draws a link is the one that
                    // records where it was drawn, so a click can never land on a rectangle that is not what
                    // the reader saw.
                    r.styledText(List.of(new GuiRenderer.StyledRun(piece.text(), bold, italic,
                            underline, piece.scale())), x, lineY, colour);
                    if (box != null) {
                        book.bookState.linkRects.add(new QuestBookScreen.LinkRect(book.shiftHit(box),
                                link == null ? "" : link,
                                quest == null ? "" : quest));
                    }
                    x += width;
                    offset += piece.text().length();
                }
                lineY += advance;
            }
        }
    }

    /**
     * One inline picture, fitted to the body and aligned within the slot.
     *
     * <p>The slot is the full column the layout reserved; the picture sits in it left, centred or
     * right, by the token's own alignment. Files stretch to fill, like chapter pictures: the token's
     * numbers are the destination, and a file's real size is never looked up. The {@code .png} suffix
     * rule is the same one the chapter-image source uses, applied here rather than converted, because
     * the token is read at render time and there is no conversion pass to own it.
     */
    private void drawInlineImage(GuiRenderer r, Slot slot, Viewport body, RichText.PlacedImage placed) {
        RichText.ImageBox box = RichText.fitImage(placed.image(), body.viewWidth());
        int x = switch (placed.image().align()) {
            case "left" -> slot.x();
            case "right" -> slot.x() + slot.width() - box.width();
            default -> slot.x() + Math.max(0, (slot.width() - box.width()) / 2);
        };
        net.minecraft.resources.Identifier id =
                net.minecraft.resources.Identifier.tryParse(placed.image().src());
        if (id == null) {
            return;
        }
        if (placed.image().src().toLowerCase(java.util.Locale.ROOT).endsWith(".png")) {
            r.scaled(id, x, slot.y(), box.width(), box.height(), 0F, 0F, box.width(), box.height(),
                    box.width(), box.height(), 0xFFFFFFFF);
        }
        else {
            r.sprite(id, x, slot.y(), box.width(), box.height(), 0xFFFFFFFF);
        }
    }

    private void drawTasks(GuiRenderer r, ClientQuestCache.Entry entry, Layout layout, Viewport body,
                           int mouseX, int mouseY, long now) {
        drawHeading(r, placed(layout, body, OverlayLayout.TASKS_HEADING), "Tasks");

        if (entry.tasks().isEmpty()) {
            BookEditorControls.drawEmptyState(r, placed(layout, body, OverlayLayout.NO_TASKS),
                    "Nothing required");
            return;
        }
        for (int i = 0; i < entry.tasks().size(); i++) {
            String key = OverlayLayout.taskKey(i);
            Slot slot = placed(layout, body, key);
            if (slot != null) {
                book.bookQuestRows.drawTaskRow(r, entry, i, slot, book.bookState.rowHover.amount(key, now), mouseX,
                        mouseY, book.bookQuestRows.rowStamp());
            }
        }
    }

    /**
     * Every key a row of the body could be drawn under, in reading order.
     *
     * <p>Built here rather than asked of {@link OverlayLayout}, because the layout's business is where
     * things go and the hover tracker's is which one the pointer is over — and a method on the layout
     * that listed its own keys would be a second place the key naming has to be kept in step with
     * {@code stack}'s. It is not: both call the same key generators.
     *
     * <p>Remembered while the counts hold still: the keys are pure functions of the three counts, so
     * one list per (quest, counts, draft) rather than one per frame. The draft version rides along
     * because the dependency count is draft-aware — an author adding a prerequisite must move the
     * hover, not just the form.
     */
    private List<String> rowKeysOf(ClientQuestCache.Entry entry) {
        int tasks = entry.tasks().size();
        int rewards = entry.rewards().size();
        int deps = QuestBookScreen.dependenciesOf(entry).size();
        RowKeysKey key = new RowKeysKey(entry.id(), tasks, rewards, deps, BookScreenState.fieldDraft.version());
        if (rowKeys != null && key.equals(rowKeysKey)) {
            return rowKeys;
        }
        List<String> keys = new ArrayList<>(tasks + rewards + deps);
        for (int i = 0; i < tasks; i++) {
            keys.add(OverlayLayout.taskKey(i));
        }
        for (int i = 0; i < rewards; i++) {
            keys.add(OverlayLayout.rewardKey(i));
        }
        // The prerequisites are rows of the body too: they carry a hover wash and a press, and both
        // read the row the pointer is over from this one list.
        for (int i = 0; i < deps; i++) {
            keys.add(OverlayLayout.dependencyKey(i));
        }
        rowKeys = List.copyOf(keys);
        rowKeysKey = key;
        return rowKeys;
    }

    /** What a remembered row-key list was built from. See {@link #rowKeysOf}. */
    private record RowKeysKey(String questId, int tasks, int rewards, int deps, long draft) {
    }

    /** The row keys in force, and what they were built from. See {@link #rowKeysOf}. */
    private List<String> rowKeys;
    private RowKeysKey rowKeysKey;

    /**
     * The key of the row under the pointer, or null.
     *
     * <p>Asks each key's <b>placed slot</b> rather than recomputing a rectangle, which is the same
     * principle the whole drawing pass is built on: the row a click would land on and the row that
     * lights up come from one description of where a row is. A second piece of arithmetic here would
     * agree with the layout almost everywhere, and the place it disagreed would be a row that
     * highlights but does not respond.
     *
     * <p>Uses {@link Slot#contains}, so a row scrolled out of the view is null from {@code placed} and
     * is therefore unhoverable — which is right, since it is not on screen to point at.
     */
    private static String rowAt(Layout layout, Viewport body, List<String> keys,
                                double mouseX, double mouseY) {
        for (String key : keys) {
            Slot slot = placed(layout, body, key);
            if (slot != null && rowBox(slot).contains(mouseX, mouseY)) {
                return key;
            }
        }
        return null;
    }

    /**
     * A press on a row that has something to look up: the chosen viewer opens, and the press is
     * consumed.
     *
     * <p>Consumed even when no viewer is installed: a press inside the reader's card did nothing
     * before this feature, and it still does nothing — the difference is only that the row answers
     * when there is a viewer to answer with.
     */
    boolean pressRowItem(double mouseX, double mouseY) {
        for (QuestBookScreen.RowItem item : book.bookState.rowItems) {
            if (item.box().contains(mouseX, mouseY)) {
                RecipeLookups.open(item.target());
                return true;
            }
        }
        return false;
    }

    /**
     * A press on a checkmark row: the task is handed in, exactly as its Submit button would.
     *
     * <p>legacy packs let the row itself be the button, and so does this: the press goes through the same
     * {@code submit} (pending marks and all), so a double press cannot hand in twice and the row
     * stops offering the moment the first press lands.
     */
    boolean pressCheckmarkRow(double mouseX, double mouseY) {
        for (QuestBookScreen.CheckmarkRow row : book.bookState.checkmarkRows) {
            if (row.box().contains(mouseX, mouseY)) {
                book.bookAssets.submit(row.questId(), row.taskIndex());
                return true;
            }
        }
        return false;
    }

    /**
     * A press on a claimable reward row: the reward is collected, exactly as the Rewards menu's
     * Claim button would.
     *
     * <p>The checkmark row's rule, one section down: the press goes through the same
     * {@code claimReward} (pending marks and all), so a double press cannot collect twice and
     * the row stops offering the moment the first press lands. Only claimable rows are
     * registered, so this cannot steal a press a recipe lookup answers.
     */
    boolean pressRewardClaimRow(double mouseX, double mouseY) {
        for (QuestBookScreen.RewardClaimRow row : book.bookState.rewardClaimRows) {
            if (row.box().contains(mouseX, mouseY)) {
                book.bookRewards.claimReward(row.questId(), row.rewardIndex());
                return true;
            }
        }
        return false;
    }

    /**
     * A press anywhere on a filter row: the accepted-items list opens beside the quest, and the
     * press is consumed. Only when something matches — an empty expression has no list to show,
     * and the tooltip already says so, so the press falls through to whatever else answers.
     */
    boolean pressFilterRow(double mouseX, double mouseY) {
        for (QuestBookScreen.FilterRow preview : book.bookState.filterRows) {
            if (preview.box().contains(mouseX, mouseY)) {
                var matches = dev.ellipog.tenet.client.FilterMatches.of(preview.expression());
                if (matches.isEmpty()) {
                    return false;
                }
                openFilterPanel(preview.expression());
                return true;
            }
        }
        return false;
    }

    /**
     * Opens the accepted-items list for one expression, in the second column beside the quest.
     *
     * <p>Asking for the same list twice re-reads it rather than stacking anything:
     * {@code afterOpen} answers "already open" with the arrangement unchanged, so the expression
     * swap and the scroll reset have to happen here rather than in the transition.
     */
    private void openFilterPanel(String expression) {
        book.bookState.filterExpression = expression;
        book.bookState.filterLayout = null;
        book.bookState.filterView.scrollTo(0);
        book.applyColumns(PanelStack.afterOpen(book.columns(), PanelKind.FILTER));
        book.rebuildWidgets();
    }

    /**
     * The accepted-items list: one row per match the open expression names.
     *
     * <p>Drawn like the choice card — the panel, the header strip, the rule under it — with the
     * match count for a header, because the question the list answers is "what does this accept".
     * Rows are item plus hover name, the book row's own idiom; a row press opens the match's
     * recipes where a viewer is installed.
     */
    void drawFilterPanel(GuiRenderer r, int mouseX, int mouseY) {
        int left = book.overlayLeft();
        int top = book.overlayTop();
        int w = book.overlayWidth();
        int h = book.overlayHeight();

        BookFrame.drawCard(r, left, top, w, h, 46, false);

        var matches = dev.ellipog.tenet.client.FilterMatches.of(book.bookState.filterExpression);
        String heading = matches.total() == 1 ? "1 accepted item" : matches.total() + " accepted items";
        if (matches.truncated()) {
            heading += " (first " + matches.shown().size() + " shown)";
        }
        Measure measure = book.textMeasure(r);
        r.text(Measure.truncate(heading, w - 28, measure), left + 14, top + 12, ArmatureTheme.title());

        Viewport body = book.overlayBody();
        // The rows rebuild when the room does, and when the words or the font do: hover names
        // follow the language and the overlay, and a width remembered across a font change lays
        // text out to a width it no longer has. The matches themselves are stable objects from
        // FilterMatches' own memo, so only these three move the build.
        long filterText = ClientQuestCache.textRevision();
        long filterEpoch = TextEpoch.now();
        if (book.bookState.filterLayout == null || filterLayoutWidth != body.viewWidth()
                || filterTextRevision != filterText || filterStamp != filterEpoch) {
            List<InspectRow> rows = new ArrayList<>();
            for (int i = 0; i < matches.shown().size(); i++) {
                rows.add(InspectRow.action(FILTER_PREFIX + i,
                        matches.shown().get(i).getHoverName().getString()));
            }
            filterListRows = List.copyOf(rows);
            book.bookState.filterLayout = InspectLayout.build(filterListRows, body.viewWidth(),
                    Measure.monospace(6, 9));
            filterLayoutWidth = body.viewWidth();
            filterTextRevision = filterText;
            filterStamp = filterEpoch;
            book.bookState.filterView.clear();
            book.bookState.filterView.whole(true);
            book.bookState.filterView.viewport().bounds(body.originX(), body.originY(), body.viewWidth(),
                    body.viewHeight());
            book.bookState.filterView.apply(book.bookState.filterLayout, body.viewWidth());
            // Truncated once per build rather than per row per frame: the labels above are already
            // resolved, and these are the same slots the draw reads — onScreen only translates, so
            // the widths agree by construction.
            List<String> shown = new ArrayList<>(filterListRows.size());
            for (InspectRow row : filterListRows) {
                var slot = book.bookState.filterLayout.slot(row.key());
                int room = slot == null ? 0 : Math.max(0, slot.width() - QuestBookScreen.ROW_ICON - 8);
                shown.add(Measure.truncate(row.label(), room, measure));
            }
            filterShownText = List.copyOf(shown);
        }
        book.bookState.filterView.apply(book.bookState.filterLayout, body.viewWidth());
        filterListRects.clear();
        filterShown.clear();
        try (GuiRenderer.Scoped clip = r.clip(body.originX(), body.originY(), body.originX() + body.viewWidth(),
                body.originY() + body.viewHeight())) {
            for (int i = 0; i < filterListRows.size(); i++) {
                var slot = book.bookState.filterLayout.slot(filterListRows.get(i).key());
                if (slot == null) {
                    continue;
                }
                var onScreen = InspectLayout.onScreen(book.bookState.filterView.viewport(), slot);
                BookGeometry.Rect rect = BookGeometry.Rect.at(onScreen.x(), onScreen.y(),
                        onScreen.width(), onScreen.height());
                filterListRects.add(rect);
                if (i >= matches.shown().size()) {
                    continue;
                }
                ItemStack stack = matches.shown().get(i);
                filterShown.add(stack);
                if (rect.contains(mouseX, mouseY)) {
                    r.fill(rect.x(), rect.y(), rect.right(), rect.bottom(), ArmatureTheme.rowHover());
                }
                r.icon(stack, rect.x() + 2, rect.y() + 2, QuestBookScreen.ROW_ICON);
                r.text(filterShownText.get(i),
                        rect.x() + QuestBookScreen.ROW_ICON + 6,
                        rect.y() + (rect.height() - r.lineHeight()) / 2, ArmatureTheme.body());
            }
        }
        QuestBookScreen.drawBar(r, book.bookState.filterView.bar(), mouseX, mouseY);
    }

    /** A press on one of the list's rows: the match's recipes, where a viewer is installed. */
    void pressFilterListRow(double mouseX, double mouseY) {
        for (int i = 0; i < filterListRects.size(); i++) {
            if (filterListRects.get(i).contains(mouseX, mouseY)) {
                if (i < filterShown.size()) {
                    RecipeLookups.open(RecipeLookups.Target.of(filterShown.get(i)));
                }
                return;
            }
        }
    }

    /** Whether one of the list's rows is under the pointer. */
    boolean onFilterListRow(double mouseX, double mouseY) {
        for (BookGeometry.Rect rect : filterListRects) {
            if (rect.contains(mouseX, mouseY)) {
                return true;
            }
        }
        return false;
    }

    /**
     * The row's own box inside its slot: the icon's height, at the slot's top.
     *
     * <h2>Because a slot is an advance, not a row</h2>
     *
     * <p>{@code OverlayLayout} places a task or a reward as {@code ROW_ADVANCE} tall — the row, plus the
     * space under it before the next one starts — so a hit test against the slot whole reaches six pixels
     * into the gap below the row and none of the gap above it. That is the report exactly: <i>"I hold
     * under a task and it glows"</i>. Every row draws its content from the slot's top edge, so the box a
     * pointer should find is the top {@code ROW_ICON} of it, and the wash and the test both come from
     * here rather than from two readings of one slot.
     */
    static Slot rowBox(Slot slot) {
        return new Slot(slot.key(), slot.x(), slot.y(), slot.width(),
                Math.min(QuestBookScreen.ROW_ICON, slot.height()));
    }

    private void drawRewards(GuiRenderer r, ClientQuestCache.Entry entry, Layout layout, Viewport body,
                             int mouseX, int mouseY, long now) {
        drawHeading(r, placed(layout, body, OverlayLayout.REWARDS_HEADING), "Rewards");

        if (entry.rewards().isEmpty()) {
            BookEditorControls.drawEmptyState(r, placed(layout, body,
                    OverlayLayout.NO_REWARDS), "Nothing");
            return;
        }
        for (int i = 0; i < entry.rewards().size(); i++) {
            String key = OverlayLayout.rewardKey(i);
            Slot slot = placed(layout, body, key);
            if (slot != null) {
                book.bookQuestRows.drawRewardRow(r, entry, i, slot, book.bookState.rowHover.amount(key, now), mouseX,
                        mouseY, book.bookQuestRows.rowStamp());
            }
        }
    }

    private void drawDependencies(GuiRenderer r, ClientQuestCache.Entry entry, Layout layout,
                                  Viewport body, int mouseX, int mouseY, long now) {
        if (QuestBookScreen.dependenciesOf(entry).isEmpty()) {
            // No REQUIRES section at all, rather than one saying nothing. The layout omits it for the
            // same reason, so there is no heading to draw and no room reserved for one.
            return;
        }
        // The heading carries the count when the rule is more interesting than "all of them": "2 of 3
        // met" is the whole of what a player needs to know about a quest that is not unlocking, and it
        // is a question the old heading could not answer at all.
        dev.ellipog.tenet.client.dev.DependencyProgress progress = dependencyProgressOf(entry);
        int satisfied = progress.satisfied(ClientQuestCache::stateOf);
        String heading = satisfied >= progress.required()
                ? "Requires"
                : "Requires · " + satisfied + " of " + progress.required() + " met";
        drawHeading(r, placed(layout, body, OverlayLayout.REQUIRES_HEADING), heading);

        for (int i = 0; i < QuestBookScreen.dependenciesOf(entry).size(); i++) {
            Slot slot = placed(layout, body, OverlayLayout.dependencyKey(i));
            if (slot == null) {
                continue;
            }
            String key = OverlayLayout.dependencyKey(i);
            String dependency = QuestBookScreen.dependenciesOf(entry).get(i);
            ClientQuestCache.Entry other = book.entryFor(dependency);
            // The *rule's* bar, not completion: under `all_started` and `one_started` a prerequisite
            // with any task progress has done its job, and a cross beside it said otherwise.
            boolean met = progress.satisfies(dependency, ClientQuestCache::stateOf);
            // The quest's reading, not the file's: titles can carry legacy colour codes, and this row
            // draws one ink -- the codes in it would be plaintext. The stripped reading is what the
            // sidebar, the toasts and every other one-ink surface draws.
            String label = other != null ? other.titleText() : dependency;

            // The row's own box, and the bar the whole row is a target under: the row **opens that quest and
            // takes the camera to it**, so the bar is the affordance that says so before the press.
            Slot row = rowBox(slot);
            // **The bar's rectangle is derived, never inline.** It used to be drawn to `body.visibleRight()`
            // -- a *content* coordinate (see `Viewport.visibleRight`) used as a screen one, which is why the
            // wash only ever covered the label; when the hit test was pointed at the same rectangle the width
            // came out zero and every row stopped answering. `dependencyBar` owns the derivation and its own
            // test asserts it is never empty, which is the property that broke.
            Slot bar = OverlayLayout.dependencyBar(row, book.overlayBodyRect().right());
            float hover = book.bookState.rowHover.amount(key, now);
            if (hover > 0F) {
                r.fill(bar.x(), bar.y(), bar.right(), bar.bottom(),
                        Colour.translucent(ArmatureTheme.rowHover(), hover));
            }
            BookGeometry.Rect locate = locateBox(slot);
            // A tick and a cross. The cross is U+00D7 rather than the heavier U+2716, which is one of the
            // few symbols this font does not carry -- see `BookGeometry.TOOLS_PILL_WIDTH`. Cut to the
            // room the locate icon leaves, so a long title cannot run under it, and centred on the row:
            // the label used to be drawn at the slot's top edge while the icon was centred, which read
            // as text sitting high in the wash.
            // The mark carries the state and the quest's own icon carries which quest, the way a task or
            // reward row wears its item: a column of bare titles read as a list of words, not of quests.
            int textY = slot.y() + (slot.height() - 8) / 2;
            r.text(met ? "\u2714" : "\u00d7", slot.x(), textY,
                    met ? ArmatureTheme.complete() : ArmatureTheme.blocked());
            int labelX = slot.x() + 12;
            ItemStack depIcon = other == null ? ItemStack.EMPTY : other.icon();
            if (!depIcon.isEmpty()) {
                int size = Math.min(12, slot.height());
                r.icon(depIcon, labelX, slot.y() + (slot.height() - size) / 2, size);
                labelX += size + 4;
            }
            r.text(Measure.truncate(label, Math.max(24, locate.x() - labelX - 8), book.textMeasure(r)),
                    labelX, textY, met ? ArmatureTheme.body() : ArmatureTheme.blocked());
            drawLocateIcon(r, locate.x() + locate.width() / 2, locate.y() + locate.height() / 2,
                    locate.contains(mouseX, mouseY) ? ArmatureTheme.body() : ArmatureTheme.faint());
            dependencyTargets.add(new DependencyTarget(bar, locate, dependency));
        }
    }

    /** The locate icon's box: at the row's right edge, the size of the row it sits in. */
    private static BookGeometry.Rect locateBox(Slot slot) {
        int size = Math.max(8, Math.min(12, slot.height()));
        return BookGeometry.Rect.at(slot.right() - size, slot.y() + (slot.height() - size) / 2, size,
                size);
    }

    /**
     * A press on a prerequisite row: **the row opens that quest and takes the canvas to it**, while the
     * locate icon — or a middle-click or shift-click anywhere on the row — moves the canvas alone.
     *
     * <h2>Why a plain press does both</h2>
     *
     * <p>Because the two halves answer one question between them. "Where is the quest I need?" is not
     * answered by a card that covers the canvas, and it is not answered by a camera move that leaves the
     * reader without the quest's details — it is answered by both, which is what the docked presentation
     * shows at once: the column holding the required quest while the canvas slides onto its node. Asking
     * *the row* to choose between the two was the panel's convenience rather than the reader's.
     *
     * <p>Keep the split for the gestures that mean the camera alone: the locate icon, middle-click and
     * shift-click go there without replacing the card being read, which is worth having now that the plain
     * press is the loud one.
     *
     * <p>From the last frame's own drawing, the same contract every other row in the card follows.
     */
    boolean pressDependencyRow(double mouseX, double mouseY, int button, boolean shift) {
        for (DependencyTarget target : dependencyTargets) {
            if (!target.row().contains(mouseX, mouseY)) {
                continue;
            }
            // Middle is 2 on SDL, where right was 2 on GLFW: the port swapped middle and right
            // throughout this method (and only here — left is the only button other files name).
            // Each site below is restored to its 1.21.1 meaning; see the canvas press for the one
            // that broke right-click outright.
            boolean locate = button == InputConstants.MOUSE_BUTTON_MIDDLE || shift || target.locate().contains(mouseX, mouseY);
            if (locate) {
                book.locateOnCanvas(target.questId());
            }
            else if (button == InputConstants.MOUSE_BUTTON_LEFT) {
                // Open it, then bring the canvas to it: the order matters, because the camera's target is
                // computed from the view as it stands and opening a column does not move the canvas.
                book.navigateToQuest(target.questId());
                book.bookAssets.revealNode(target.questId());
            }
            return true;
        }
        return false;
    }

    /** The renderer the measure in force is measuring with. See {@link QuestBookScreen#textMeasure}. */
    GuiRenderer measuring;

    /** The measure in force. See {@link QuestBookScreen#textMeasure}. */
    Measure measure;

    /**
     * What a header cut is remembered against: the font and the text scale, the locale text, and
     * the renderer the widths came from.
     *
     * <p>All three titles on the card's header draw from cuts remembered under this stamp (see
     * {@code RichText.cut}), so each half has to be in it: a font reload or a text-scale move
     * arrives through {@code TextEpoch}, a language change through the text revision, and a
     * redraw through another renderer must never be handed widths measured through this one.
     * Mixed rather than added — any half moving moves the whole — and compared for equality only.
     */
    private static long headerCutEpoch(GuiRenderer r) {
        return TextEpoch.now() * 1_000_003L
                + ClientQuestCache.textRevision() * 31L
                + System.identityHashCode(r);
    }

    /**
     * The wash behind a hovered row.
     *
     * <h2>Why it hugs the content rather than the row</h2>
     *
     * <p>{@code slot} is stretched to the width of the body, because that is what a row of a stack is
     * — and that makes a wash out to {@code slot.right()} a bar the <b>full width of the panel</b>.
     * On a wide window that is a highlight clear across the screen for a row that draws an icon and
     * two words, which reads as the row being selected rather than as a hint about where a click
     * lands. It looked like an overflow, and it was one: the wash was drawn to the row's box, and the
     * row's box is not what the row <i>draws</i>.
     *
     * <p>So it runs from the row's left edge to just past the furthest thing the row actually puts on
     * screen. {@code contentRight} is that edge, measured by the caller before it draws anything —
     * see {@code drawTaskRow} for why the measurement has to come first.
     *
     * <h2>Why it is not the full row height either</h2>
     *
     * <p>{@code slot} is {@link OverlayLayout#ROW_ADVANCE} tall — the icon's box <b>plus the gap under
     * it</b> — so washing the whole slot puts the highlight two pixels into the row below and leaves
     * it sitting low under the icon. This brackets the icon instead: symmetric about it, and unable to
     * reach a neighbour even during a crossfade where two washes are on screen at once.
     *
     * <p>A caller whose rows are exactly as tall as they look wants {@link #rowWashFull} instead.
     */
    static void rowWash(GuiRenderer r, Slot slot, int contentRight, float hover) {
        wash(r, slot, contentRight, hover, slot.y() - 2, slot.y() + QuestBookScreen.ROW_ICON + 2);
    }

    /**
     * The wash behind a hovered row whose slot <b>is</b> the row — the claim menu's, and only its.
     *
     * <h2>Why the height is not bracketed here</h2>
     *
     * <p>{@link #rowWash} brackets the icon because the rows it was written for are {@code ROW_ADVANCE}
     * tall: the icon's box plus the gap beneath it, so a wash to the slot's bottom would bleed into the
     * row below. A claim menu row has no such gap — its slot is exactly its height, and the air between
     * rows is a gap <i>between</i> slots — so the wash can be the row and cannot reach a neighbour.
     *
     * <p>And it has to be, which is the fault this fixes: the wash's height was a constant, so a banner
     * taller than the button inside it drew a grey band exactly as tall as that button. The row was
     * taller and the highlight was not, so the highlight is what the row looked like — a heading that
     * reads as no taller than its own action. **The band is the row, to a reader**, and a band with a
     * height of its own is a second answer to how tall a row is.
     */
    private static void rowWashFull(GuiRenderer r, Slot slot, int contentRight, float hover) {
        wash(r, slot, contentRight, hover, slot.y(), slot.bottom());
    }

    /**
     * The wash itself, from two vertical stops the caller chooses.
     *
     * <p>One method for the horizontal arithmetic, because that part is the same either way and was the
     * half that took the work: the row's left edge to just past what it draws. The vertical stops are
     * the only thing the two callers disagree about, so they are the only thing they pass.
     */
    private static void wash(GuiRenderer r, Slot slot, int contentRight, float hover, int top,
                             int bottom) {
        if (hover <= 0F) {
            return;
        }
        int right = Math.min(slot.right() + 3, contentRight + 6);
        r.fill(slot.x() - 3, top, right, bottom, Colour.translucent(ArmatureTheme.rowHover(), hover));
    }

    private static int stateColour(QuestState state) {
        return switch (state) {
            case COMPLETED -> ArmatureTheme.complete();
            case STARTED -> ArmatureTheme.inProgress();
            case UNLOCKED -> ArmatureTheme.available();
            case LOCKED -> ArmatureTheme.blocked();
        };
    }

    /**
     * The state word the quest card shows.
     *
     * <p>The file's {@code lockMessage} when a locked quest's pack has a better word than "Locked"
     * — the author's own sentence, drawn as-is rather than translated — and {@link BookRewards#stateLabel}
     * otherwise. Only the card reads this: the viewers' rows and the narrator keep the shared
     * word, so a pack's flourish cannot confuse a surface that compares states.
     */
    private static String cardStateLabel(QuestState state) {
        if (state == QuestState.LOCKED && !ClientQuestCache.lockMessage().isEmpty()) {
            return ClientQuestCache.lockMessage();
        }
        return QuestBookScreen.stateLabel(state);
    }

    /**
     * The state word as a badge: the word in its state's ink on a wash of the same ink.
     *
     * <p>A badge rather than loose coloured text, because loose text beside a title reads as part of the
     * title -- "Punch a Tree Available" -- where a tinted chip reads as a fact about it. The wash is the
     * state's own colour at low strength, so a theme that recolours a state recolours its badge too, and
     * the badge needs no token of its own.
     */
    static void drawStateBadge(GuiRenderer r, String word, int x, int textY, int ink) {
        int padX = 4;
        int width = r.textWidth(word);
        r.fill(x, textY - 3, x + width + padX * 2, textY + 10, Colour.alphaOf(ink, 0.16F));
        r.text(word, x + padX, textY, ink);
    }

    // ------------------------------------------------------------------
    // The settings card
    // ------------------------------------------------------------------

    /** The reader's card, as it was last built, and the key it was built from. */
    Layout readerCard;

    /**
     * The locate icon: a small diamond ring with a dot in it — "show me where this is".
     *
     * <p>Drawn from pixels rather than written as a character, for the same reason the tick and the
     * cross are chosen carefully: the font carries no crosshair, and a missing glyph is a box.
     */
    static void drawLocateIcon(GuiRenderer r, int cx, int cy, int colour) {
        for (int dx = -2; dx <= 2; dx++) {
            for (int dy = -2; dy <= 2; dy++) {
                int distance = Math.abs(dx) + Math.abs(dy);
                if (distance == 2 || distance == 0) {
                    r.fill(cx + dx, cy + dy, cx + dx + 1, cy + dy + 1, colour);
                }
            }
        }
    }

    /**
     * The rule a quest's prerequisites are judged by, from the replica's own fields.
     *
     * <p>One helper for the canvas and the card, because two expressions for "is this prerequisite
     * satisfied" is how the two would come to disagree -- and they did: both asked about completion,
     * which is the wrong question for half the modes.
     */
    static dev.ellipog.tenet.client.dev.DependencyProgress dependencyProgressOf(
            ClientQuestCache.Entry entry) {
        return new dev.ellipog.tenet.client.dev.DependencyProgress(entry.effectivePrerequisiteMode(),
                entry.minRequired(), QuestBookScreen.dependenciesOf(entry));
    }

    /**
     * The state one quest's chrome draws as: gated visuals for a flexible quest whose rule is
     * unmet, the engine's own state everywhere else.
     *
     * <p>One helper for the node, its label, its padlock and the card's state word, because four
     * spellings of "gated" is how three of them would come to disagree. Playability never reads
     * this — submit buttons, accumulation and completion keep judging engine states — and neither
     * does the narrator, whose "worth opening" is true of a measurable quest.
     */
    static QuestState shownState(ClientQuestCache.Entry entry) {
        return dev.ellipog.tenet.client.dev.QuestVisibility.displayState(
                ClientQuestCache.stateOf(entry.id()), entry.flexible(),
                dependencyProgressOf(entry).met(ClientQuestCache::stateOf));
    }

    /**
     * A section label at the slot the layout reserved for it.
     *
     * <p>Sentence case in the heading ink and nothing else. It used to be capitals with a rule under
     * them, which made every section shout and drew a line that divided nothing; the space the layout
     * reserves below the word ({@link OverlayLayout#SECTION_LEAD}) separates the label from its rows
     * on its own.
     */
    void drawHeading(GuiRenderer r, Slot slot, String label) {
        if (slot == null) {
            return;
        }
        r.text(label, slot.x(), slot.y(), ArmatureTheme.heading());
    }

    /**
     * A key's slot, moved onto the screen, or null if it was not placed or is scrolled out of view.
     *
     * <p>Null for two different reasons, and a caller treats them the same: an element this layout did
     * not place at all — a REQUIRES heading on a quest with no prerequisites — and one that is off the
     * screen right now. Both mean "do not draw it here".
     *
     * <p>This is the whole of the culling, and it is why the drawing can be written against keys: the
     * dispatch is on what an element <i>is</i>, so a row that moves when a metric changes takes its
     * drawing with it. Matching on coordinates is the version of this that leaves a working test naming
     * the wrong row.
     */
    static Slot placed(Layout layout, Viewport body, Object key) {
        Slot slot = layout.slot(key);
        if (slot == null) {
            return null;
        }
        // Wholly off screen: above the top of the view or below its bottom. A slot that is only partly
        // on screen is drawn and cut off by the clip, which is what the clip is for.
        if (slot.bottom() <= body.visibleTop() || slot.y() >= body.visibleBottom()) {
            return null;
        }
        // Moved by the viewport's own mapping, so the placement and the culling cannot disagree about
        // where a row is. The overlay's viewport is fixed-scale, so a slot's width is its screen width.
        return slot.moved(body.screenX(slot.x()) - slot.x(), body.screenY(slot.y()) - slot.y());
    }
}
