package dev.ellipog.tenet.client;

import dev.ellipog.armature.client.ArmatureTheme;
import dev.ellipog.armature.client.render.GuiRenderer;
import dev.ellipog.armature.client.ui.kit.Measure;
import dev.ellipog.armature.client.ui.kit.ScrollBar;
import dev.ellipog.armature.client.ui.kit.Viewport;
import dev.ellipog.tenet.editor.EditorOp;
import dev.ellipog.tenet.client.dev.CanvasReveal;
import dev.ellipog.tenet.client.dev.InlineEdit;
import dev.ellipog.tenet.client.dev.Labels;
import dev.ellipog.tenet.client.dev.QuestPanelLayout;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import net.minecraft.util.Util;
import net.minecraft.world.item.ItemStack;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * The quest book's Assets panel: the pack listed, a folded entry's own fields, and an item dropped in
 * from a recipe viewer.
 *
 * <p>Moved out of {@link QuestBookScreen} as it stood, with the state only it reads; the screen
 * keeps the state it shares and reaches this through its {@code bookAssets} field.
 */
final class BookAssets {

    private final QuestBookScreen book;

    BookAssets(QuestBookScreen book) {
        this.book = book;
    }

    // ------------------------------------------------------------------
    // The Assets panel: the pack, listed
    // ------------------------------------------------------------------

    /** The page's scroll, its clamp and its bar as one object. Reset when the section changes. */
    final Viewport assetsBody = Viewport.fixed();
    final ScrollBar assetsBar = new ScrollBar(assetsBody);

    /** The panel's hint line, rebuilt as it draws. */
    private String assetsHint;

    /**
     * One line of the panel's page: a group heading, or a row.
     *
     * <p>The same shape the table panels' rows settled on — a name at the left and one detail at the right
     * — and flat rather than nested, because {@link dev.ellipog.tenet.client.dev.AssetsLayout} measures a
     * page as a list of two kinds of line and nothing else. {@code id} is what a press acts on; a line with
     * no action is drawn and not pressable, which is how a refused file is listed without pretending it is
     * something that can be opened.
     */
    private record AssetLine(dev.ellipog.tenet.client.dev.AssetsLayout.Kind kind, String label,
                             String detail, String id, QuestBookScreen.EditAction action, boolean blocked,
                             boolean hasRemove,
                             net.minecraft.world.item.ItemStack icon) {

        static AssetLine heading(String label) {
            return new AssetLine(dev.ellipog.tenet.client.dev.AssetsLayout.Kind.HEADING, label, "", "",
                    null, false, false, net.minecraft.world.item.ItemStack.EMPTY);
        }

        /**
         * A table row: its title drawn, its file id as the detail, and the same id as what a press opens.
         *
         * <h2>Why this is a factory rather than a call with four arguments</h2>
         *
         * <p>Because the call got it wrong, and the log said so: {@code reward_tables/4 entries.json could not
         * be read} — the Tables page passed its two strings in the other order, so the <b>entry count</b> landed
         * in the identity slot and every press asked the server for a table named "4 entries". Two adjacent
         * string parameters, one drawn and one acted on, is a shape that invites that mistake and it made it.
         * Here the two questions are answered once, from the summary, and a caller never holds both.
         */
        static AssetLine table(ClientQuestCache.TableSummary table) {
            return new AssetLine(dev.ellipog.tenet.client.dev.AssetsLayout.Kind.ROW, table.title(),
                    table.id(), table.id(), QuestBookScreen.EditAction.ASSETS_ROW, false, false, table.icon());
        }

        /** A quest row: the path it lives at is drawn, and the quest's own id is what a press acts on. */
        static AssetLine quest(ClientQuestCache.Entry quest) {
            return new AssetLine(dev.ellipog.tenet.client.dev.AssetsLayout.Kind.ROW, quest.titleText(),
                    quest.chapterId() + "/" + quest.id(), quest.id(), QuestBookScreen.EditAction.ASSETS_ROW, false,
                    false, quest.icon());
        }

        /**
         * A row that is there to be read: a table file that did not load.
         *
         * <p>Not pressable, and that is the honest answer rather than a missing feature — nothing can edit
         * a file that did not decode, because every table op resolves a decoded draft. The row says what is
         * wrong and the way out is the file itself. And it carries no icon, because the file that would
         * have named one is the file that did not load.
         */
        static AssetLine refused(String label, String detail) {
            return new AssetLine(dev.ellipog.tenet.client.dev.AssetsLayout.Kind.ROW, label, detail, "",
                    null, true, false, net.minecraft.world.item.ItemStack.EMPTY);
        }

        /**
         * A template row: the name drawn, the blurb as the detail, and the template's id as what a
         * press applies.
         *
         * <p>A row press <i>is</i> the Apply — the page's one-press promise — so the action is the
         * row's own rather than a chip. The cross is the only chip, and only a saved template
         * carries one: a shipped row has nothing to delete. A template whose type this build does
         * not know is blocked with its reason as the detail, but keeps the cross — a template for
         * a removed addon must still be removable.
         */
        static AssetLine template(String name, String detail, String id, boolean known,
                                  boolean removable, net.minecraft.world.item.ItemStack icon) {
            return new AssetLine(dev.ellipog.tenet.client.dev.AssetsLayout.Kind.ROW, name, detail, id,
                    known ? QuestBookScreen.EditAction.ASSETS_ROW : null, !known, removable, icon);
        }

        boolean pressable() {
            return action != null && !blocked;
        }
    }

    /**
     * A remembered section's name as the enum, among the sections this depth shows.
     *
     * <p>An unknown name is the first shown section, never a blank page -- which is also the answer for the
     * name of a section this depth hides, and the two arrive by the same road: the name is a string from a
     * file, and what it resolves to has to be something the panel can draw.
     */
    static dev.ellipog.tenet.client.dev.AssetsLayout.Section sectionNamed(String name) {
        for (dev.ellipog.tenet.client.dev.AssetsLayout.Section section
                : dev.ellipog.tenet.client.dev.AssetsLayout.shown()) {
            if (section.name().toLowerCase(java.util.Locale.ROOT).equals(name)) {
                return section;
            }
        }
        return dev.ellipog.tenet.client.dev.AssetsLayout.shownOrFirst(null);
    }

    /** Shows a section and remembers it. The scroll goes with the content it was measured against. */
    private void showAssets(dev.ellipog.tenet.client.dev.AssetsLayout.Section section) {
        if (book.assetsSection != section) {
            book.assetsSection = section;
            assetsBody.setScrollY(0);
            ClientWorking.rememberSection(section.name().toLowerCase(java.util.Locale.ROOT));
        }
    }

    /** The page's lines, from whichever section is showing. */
    private List<AssetLine> assetsLines() {
        return switch (book.assetsSection) {
            case TABLES -> assetsTableLines();
            case QUESTS -> assetsQuestLines();
            case TEMPLATES -> assetsTemplateLines();
        };
    }

    /** The loaded tables, then the files that are there and did not load. */
    private List<AssetLine> assetsTableLines() {
        List<AssetLine> lines = new ArrayList<>();
        List<ClientQuestCache.TableSummary> loaded = ClientQuestCache.tables();
        if (!loaded.isEmpty()) {
            lines.add(AssetLine.heading("Tables"));
            for (ClientQuestCache.TableSummary table : loaded) {
                // The table's own icon, which the summary already carries: a table with none of its own
                // shows the item its first entry grants, resolved server-side. And the row is built by the
                // factory, so its id — what a press opens — is the table's and not something else.
                lines.add(AssetLine.table(table));
            }
        }
        List<ClientQuestCache.RefusedTable> refused = ClientQuestCache.refusedTables();
        if (!refused.isEmpty()) {
            // The list no other panel has, because a refused file is not a table and is in nothing else.
            lines.add(AssetLine.heading("Not loaded"));
            for (ClientQuestCache.RefusedTable table : refused) {
                lines.add(AssetLine.refused(table.id(), table.why()));
            }
        }
        if (lines.isEmpty()) {
            lines.add(AssetLine.heading(ClientQuestCache.syncedAt() == 0
                    ? "Waiting for the pack's files"
                    : "No reward tables yet - New table makes one"));
        }
        return lines;
    }

    /** Every quest file, under the chapter it belongs to. */
    private List<AssetLine> assetsQuestLines() {
        List<AssetLine> lines = new ArrayList<>();
        String chapter = null;
        for (ClientQuestCache.Entry quest : ClientQuestCache.entries()) {
            if (!quest.chapterTitleText().equals(chapter)) {
                chapter = quest.chapterTitleText();
                lines.add(AssetLine.heading(chapter));
            }
            lines.add(AssetLine.quest(quest));
        }
        if (lines.isEmpty()) {
            lines.add(AssetLine.heading(ClientQuestCache.syncedAt() == 0
                    ? "Waiting for the pack's files" : "No quests in this pack yet"));
        }
        return lines;
    }

    /**
     * The component template library, under its two headings: what shipped, then what this
     * client saved.
     *
     * <p>Two lists rather than one because the two halves answer different questions — "what
     * can I use" and "what did I keep" — and an author scanning for their own saves should not
     * have to read past the presets to find them. The order inside each half is the shelf's own
     * sort, so cycling it reorders both halves at once.
     */
    private List<AssetLine> assetsTemplateLines() {
        List<AssetLine> lines = new ArrayList<>();
        var sort = dev.ellipog.tenet.client.dev.ComponentTemplates.Sort
                .known(ClientWorking.templateSort());
        List<dev.ellipog.tenet.client.dev.ComponentTemplate.Template> shipped = new ArrayList<>();
        List<dev.ellipog.tenet.client.dev.ComponentTemplate.Template> yours = new ArrayList<>();
        for (dev.ellipog.tenet.client.dev.ComponentTemplate.Template template
                : dev.ellipog.tenet.client.dev.ComponentTemplates.sorted(sort)) {
            (template.builtIn() ? shipped : yours).add(template);
        }
        if (!shipped.isEmpty()) {
            lines.add(AssetLine.heading(
                    dev.ellipog.tenet.client.dev.Labels.of("tenet.dev.template.shipped")));
            for (dev.ellipog.tenet.client.dev.ComponentTemplate.Template template : shipped) {
                lines.add(templateLine(template));
            }
        }
        if (!yours.isEmpty()) {
            lines.add(AssetLine.heading(
                    dev.ellipog.tenet.client.dev.Labels.of("tenet.dev.template.yours")));
            for (dev.ellipog.tenet.client.dev.ComponentTemplate.Template template : yours) {
                lines.add(templateLine(template));
            }
        }
        if (lines.isEmpty()) {
            lines.add(AssetLine.heading(
                    dev.ellipog.tenet.client.dev.Labels.of("tenet.dev.template.empty")));
        }
        return lines;
    }

    /** One template as a row: its name, its sentence, and whether this build can apply it. */
    private AssetLine templateLine(dev.ellipog.tenet.client.dev.ComponentTemplate.Template template) {
        boolean known = QuestBookScreen.knownTemplateType(template);
        String detail = known
                ? dev.ellipog.tenet.client.dev.ComponentTemplate.blurb(template.kind(),
                        template.typeId(), template.payload())
                : "Needs " + template.typeId();
        return AssetLine.template(template.name(), detail, template.id(), known, !template.builtIn(),
                QuestBookScreen.templateIcon(template));
    }

    /** One line of the page: its icon where it has one, its label, and its detail at the right. */

    /** One line of the page: its icon where it has one, its label, and its detail at the right. */
    private void drawAssetLine(GuiRenderer r, List<AssetLine> lines, int index, int top,
                               BookGeometry.Rect band, int mouseX, int mouseY) {
        AssetLine line = lines.get(index);
        int height = dev.ellipog.tenet.client.dev.AssetsLayout.lineHeight(line.kind());
        BookGeometry.Rect box = BookGeometry.Rect.at(band.x(), top, band.width(), height);
        int baseline = top + (height - 8) / 2;
        if (line.kind() == dev.ellipog.tenet.client.dev.AssetsLayout.Kind.HEADING) {
            r.text(Measure.truncate(line.label(), box.width(), book.textMeasure(r)), box.x(), baseline,
                    ArmatureTheme.title());
            return;
        }
        boolean hot = line.pressable() && box.contains(mouseX, mouseY);
        if (hot) {
            r.fill(box.x() - 2, box.y(), box.right(), box.bottom(), ArmatureTheme.raised());
        }
        // The icon, where there is one: a refused file has none, and the label keeps its inset so the two
        // kinds of row line up rather than the iconless ones sitting to the left of everything else.
        boolean icon = !line.icon().isEmpty();
        if (icon) {
            r.icon(line.icon(), box.x() + 2, box.y() + (height - dev.ellipog.tenet.client.dev.AssetsLayout.ICON) / 2,
                    dev.ellipog.tenet.client.dev.AssetsLayout.ICON);
        }
        int inset = icon ? dev.ellipog.tenet.client.dev.AssetsLayout.ICON_TEXT_INSET : 2;
        // Whether this row carries the two controls, which is a Tables row and nothing else: a quest is
        // opened or it is not. `reserved` is what they and the air before them take, and it is the one number
        // both the label's room and the detail's place are computed from — the first version reserved the
        // chips but not the gap, and the detail came out touching the word "Copy".
        boolean chipped = line.pressable()
                && book.assetsSection == dev.ellipog.tenet.client.dev.AssetsLayout.Section.TABLES
                && line.action() == QuestBookScreen.EditAction.ASSETS_ROW;
        // A saved template's pencil and cross: rename, then delete, the destructive one last at
        // the edge -- the same harmless-first order the entry rows follow. Even a blocked row keeps
        // both: a template for a removed addon must still be renamable and removable.
        boolean cullable = book.assetsSection == dev.ellipog.tenet.client.dev.AssetsLayout.Section.TEMPLATES
                && line.kind() == dev.ellipog.tenet.client.dev.AssetsLayout.Kind.ROW
                && line.hasRemove();
        int reserved = chipped
                ? dev.ellipog.tenet.client.dev.AssetsLayout.CHIPS
                        + dev.ellipog.tenet.client.dev.AssetsLayout.DETAIL_GAP
                : cullable ? dev.ellipog.tenet.client.dev.AssetsLayout.CHIP * 2
                        + dev.ellipog.tenet.client.dev.AssetsLayout.CHIP_GAP
                        + dev.ellipog.tenet.client.dev.AssetsLayout.DETAIL_GAP
                        : 0;
        var ink = line.blocked() ? ArmatureTheme.blocked()
                : hot ? ArmatureTheme.title() : ArmatureTheme.body();
        String detail = line.detail().isEmpty() ? "" : Measure.truncate(line.detail(),
                Math.max(0, box.width() / 2), book.textMeasure(r));
        int room = box.width() - inset - reserved - 4 - (detail.isEmpty() ? 0 : r.textWidth(detail) + 6);
        // One drawing of a value, never two: while the rename box is open for this row the widget
        // draws the name, so the row must not draw it underneath. See `InlineEdit.replaces`.
        boolean renaming = cullable
                && dev.ellipog.tenet.client.dev.InlineEdit.replaces(QuestBookScreen.TEMPLATE_ROW_PREFIX + line.id(),
                        book.editingPath);
        if (!renaming) {
            r.text(Measure.truncate(line.label(), Math.max(0, room), book.textMeasure(r)), box.x() + inset,
                    baseline, ink);
        }
        if (!detail.isEmpty()) {
            // Left of the row's controls and their gap, which is the half that was missing: see `reserved`.
            r.text(detail, box.right() - reserved - 4 - r.textWidth(detail), baseline,
                    ArmatureTheme.faint());
        }
        if (line.pressable()) {
            registerAssetTarget(QuestBookScreen.EditAction.ASSETS_ROW, box, line);
            // The row's own two controls, at its right end: copy first, remove last at the edge -- the same
            // "a mis-aimed press has to travel past the harmless one" rule the entry rows follow. Only a
            // table row has them: a quest is opened or it is not, and a type is read.
            if (chipped) {
                String copy = Labels.of("tenet.dev.copy");
                int chip = dev.ellipog.tenet.client.dev.AssetsLayout.CHIP;
                int gap = dev.ellipog.tenet.client.dev.AssetsLayout.CHIP_GAP;
                BookGeometry.Rect copyBox = BookGeometry.Rect.at(box.right() - chip * 2 - gap,
                        box.y() + 1, chip - 1, height - 2);
                BookGeometry.Rect removeBox = BookGeometry.Rect.at(box.right() - chip, box.y() + 1,
                        chip - 1, height - 2);
                boolean copyHot = copyBox.contains(mouseX, mouseY);
                boolean removeHot = removeBox.contains(mouseX, mouseY);
                r.text(copy, copyBox.x() + (copyBox.width() - r.textWidth(copy)) / 2, baseline,
                        copyHot ? ArmatureTheme.title() : ArmatureTheme.faint());
                r.text("\u00d7", removeBox.x() + (removeBox.width() - r.textWidth("\u00d7")) / 2, baseline,
                        removeHot ? ArmatureTheme.title() : ArmatureTheme.faint());
                book.assetsTargets.add(new QuestBookScreen.EditTarget(QuestBookScreen.EditAction.ASSETS_COPY,
                        line.id(), copyBox, copyBox.x(),
                        copyBox.y(), line.label(), null, book.assetsTargets.size()));
                book.assetsTargets.add(new QuestBookScreen.EditTarget(QuestBookScreen.EditAction.ASSETS_REMOVE,
                        line.id(), removeBox,
                        removeBox.x(), removeBox.y(), line.label(), null, book.assetsTargets.size()));
            }
            if (hot) {
                assetsHint = book.assetsSection == dev.ellipog.tenet.client.dev.AssetsLayout.Section.TEMPLATES
                        ? applyHint(line)
                        : "Open " + line.label();
            }
        }
        else if (line.blocked() && box.contains(mouseX, mouseY)) {
            assetsHint = line.detail();
        }
        if (cullable) {
            int chip = dev.ellipog.tenet.client.dev.AssetsLayout.CHIP;
            int gap = dev.ellipog.tenet.client.dev.AssetsLayout.CHIP_GAP;
            BookGeometry.Rect renameBox = BookGeometry.Rect.at(box.right() - chip * 2 - gap,
                    box.y() + 1, chip - 1, height - 2);
            BookGeometry.Rect removeBox = BookGeometry.Rect.at(box.right() - chip, box.y() + 1,
                    chip - 1, height - 2);
            boolean renameHot = renameBox.contains(mouseX, mouseY);
            boolean removeHot = removeBox.contains(mouseX, mouseY);
            r.text("\u270E", renameBox.x() + (renameBox.width() - r.textWidth("\u270E")) / 2, baseline,
                    renameHot ? ArmatureTheme.title() : ArmatureTheme.faint());
            r.text("\u00d7", removeBox.x() + (removeBox.width() - r.textWidth("\u00d7")) / 2, baseline,
                    removeHot ? ArmatureTheme.title() : ArmatureTheme.faint());
            // The rename target carries the name's origin for the editor's box, while its own box
            // stays the chip: a press on the pencil renames, and a press on the name still applies.
            book.assetsTargets.add(new QuestBookScreen.EditTarget(QuestBookScreen.EditAction.ASSETS_RENAME,
                    QuestBookScreen.TEMPLATE_ROW_PREFIX + line.id(),
                    renameBox, box.x() + inset, baseline, line.label(), null, book.assetsTargets.size()));
            book.assetsTargets.add(new QuestBookScreen.EditTarget(QuestBookScreen.EditAction.ASSETS_TEMPLATE_DELETE,
                    line.id(), removeBox,
                    removeBox.x(), removeBox.y(), line.label(), null, book.assetsTargets.size()));
            if (renameHot) {
                assetsHint = "Rename \"" + line.label() + "\"";
            }
            else if (removeHot) {
                assetsHint = "Delete \"" + line.label() + "\" from this shelf";
            }
        }
    }

    /** The sort footer's label: what the shelf is ordered by now. */
    private String sortLabel() {
        return switch (dev.ellipog.tenet.client.dev.ComponentTemplates.Sort
                .known(ClientWorking.templateSort())) {
            case NAME -> "Sort: Name";
            case TYPE -> "Sort: Type";
            case RECENT -> "Sort: Recent";
        };
    }

    /**
     * What a hover over a template row promises: where the press lands.
     *
     * <p>The landing is stated because it depends on state the row cannot show — which quest is
     * open, and which entry was touched last. A promise the press then keeps: {@code applyHint}
     * and {@code applyTemplate} read the same two answers.
     */
    private String applyHint(AssetLine line) {
        String quest = book.editTarget();
        if (quest == null) {
            return "Apply \"" + line.label() + "\" — open a quest first";
        }
        var template = dev.ellipog.tenet.client.dev.ComponentTemplates.byId(line.id()).orElse(null);
        if (template != null
                && template.kind() == dev.ellipog.tenet.client.dev.ComponentTemplate.Kind.CONDITION
                && conditionLanding(quest) == null) {
            return "Apply \"" + line.label() + "\" — touch a task or reward first";
        }
        return "Apply \"" + line.label() + "\" to " + quest;
    }

    /** The panel's own pressable list, and its registration: one shape, so the two cannot drift. */
    private void registerAssetTarget(QuestBookScreen.EditAction action, BookGeometry.Rect box, AssetLine line) {
        BookGeometry.Rect hit = book.shiftHit(box);
        book.assetsTargets.add(new QuestBookScreen.EditTarget(action, line.id(), hit, hit.x() + 2,
                hit.y() + 3, line.label(),
                null, book.assetsTargets.size()));
    }

    /** Draws the panel: the chrome, the sections, the page, the bar and the footer. */
    void drawAssets(GuiRenderer r, int mouseX, int mouseY) {
        assetsHint = null;
        book.assetsTargets.clear();
        // The surface it is on: a docked panel draws this listing in its column, and `AssetsLayout`'s own
        // rows and hit tests are derived from this one rectangle.
        BookGeometry.Rect card = book.surfaceCard(PanelKind.ASSETS);
        book.assetsFrame = dev.ellipog.tenet.client.dev.AssetsLayout.Frame.of(card);
        book.bookRewards.drawModalCardChrome(r, "Assets", mouseX, mouseY,
                QuestBookScreen.EditAction.ASSETS_CLOSE, book.assetsTargets);

        List<AssetLine> lines = assetsLines();
        // The layout measures lines; a page carries them. One list seen two ways, so what is measured and
        // what is drawn cannot disagree about how tall the page is.
        var kinds = lines.stream().map(AssetLine::kind).toList();
        BookGeometry.Rect band = book.assetsFrame.rows();
        QuestBookScreen.bindList(assetsBody, assetsBar, band, book.assetsFrame.scrollbar(),
                dev.ellipog.tenet.client.dev.AssetsLayout.contentHeight(kinds),
                dev.ellipog.tenet.client.dev.AssetsLayout.ROW_HEIGHT);
        int assetsScroll = assetsBody.scrollY();

        // The sections this depth shows, which is the same list `AssetsLayout.sectionAt` hit-tests against:
        // a section drawn here and not there would make every press select the row above the one under the
        // pointer, and the depth is what decides it. See `AssetsLayout.shown`.
        var sections = dev.ellipog.tenet.client.dev.AssetsLayout.shown();
        for (int i = 0; i < sections.length; i++) {
            BookGeometry.Rect box = book.assetsFrame.tab(i);
            boolean chosen = sections[i] == book.assetsSection;
            // Drawn like the texture picker's own tabs: the open one wears the recessed wash, a
            // hovered one the affordance frame, and the labels sit centred in their third of the
            // strip rather than against its left edge. Two tab strips that read as one family.
            if (chosen) {
                r.fill(box.x(), box.y(), box.right(), box.bottom(), ArmatureTheme.recessed());
            }
            else if (box.contains(mouseX, mouseY)) {
                QuestBookScreen.drawEditAffordance(r, box, true);
            }
            String name = Measure.truncate(sectionName(sections[i]), Math.max(0, box.width() - 4),
                    book.textMeasure(r));
            r.text(name, box.x() + (box.width() - r.textWidth(name)) / 2,
                    box.y() + (box.height() - 8) / 2,
                    chosen ? ArmatureTheme.title() : ArmatureTheme.faint());
            // Registered, because a drawn tab no press can reach is a dead tab: the press gate only
            // walks this panel's target list, and a tab drawn without one is a press the gate drops
            // before `pressAssets` ever runs. The section itself is resolved from the layout there,
            // so this registration carries the name for the gate and nothing else.
            BookGeometry.Rect hit = book.shiftHit(box);
            book.assetsTargets.add(new QuestBookScreen.EditTarget(QuestBookScreen.EditAction.ASSETS_SECTION,
                    sections[i].name().toLowerCase(java.util.Locale.ROOT), hit, hit.x() + 2,
                    hit.y() + 3, sectionName(sections[i]), null, book.assetsTargets.size()));
        }
        // The rule under the strip, like the texture picker's: the tabs are controls above a list,
        // and the line is what says so.
        r.fill(book.assetsFrame.tabs().x() + 1, book.assetsFrame.tabs().bottom(),
                book.assetsFrame.tabs().right() - 1, book.assetsFrame.tabs().bottom() + 1,
                ArmatureTheme.panelEdge());

        try (GuiRenderer.Scoped clip = r.clip(band.x(), band.y(), band.right(), band.bottom())) {
            for (int i = 0; i < lines.size(); i++) {
                int top = band.y() + dev.ellipog.tenet.client.dev.AssetsLayout.lineTop(kinds, i)
                        - assetsScroll;
                int height = dev.ellipog.tenet.client.dev.AssetsLayout.lineHeight(lines.get(i).kind());
                if (top + height < band.y() || top > band.bottom()) {
                    continue;
                }
                drawAssetLine(r, lines, i, top, band, mouseX, mouseY);
            }
        }

        // The page's bar, in the strip the layout keeps inside the page's right edge: the grip is drawn
        // where the rows are not, which is the arrangement the two hand-rolled thumbs here and in the
        // pickers did not have -- they painted over the last three pixels of every line.
        QuestBookScreen.drawBar(r, assetsBar, mouseX, mouseY);

        // The footer: the page's own action at the left, the way out at the right. Done closes the panel --
        // one step, which is what every footer in this book now means. Both rectangles come from
        // `AssetsLayout.Footer`, and both are registered in *this panel's* list: this panel presses from
        // `assetsTargets`, and a control registered in `editTargets` is one no press here can reach --
        // which is exactly how New table, Done and the header's cross came to do nothing at all.
        dev.ellipog.tenet.client.dev.AssetsLayout.Footer footer =
                dev.ellipog.tenet.client.dev.AssetsLayout.Footer.of(book.assetsFrame);
        if (book.assetsSection == dev.ellipog.tenet.client.dev.AssetsLayout.Section.TABLES) {
            book.bookRewards.drawTableButton(r, footer.newTable(), "New table", mouseX, mouseY,
                    QuestBookScreen.EditAction.ASSETS_NEW,
                    book.assetsTargets);
        }
        else if (book.assetsSection == dev.ellipog.tenet.client.dev.AssetsLayout.Section.TEMPLATES) {
            // The shelf's order, as the footer's own action: one press cycles Recent, Name, Type,
            // and the page re-lists under both headings at once.
            book.bookRewards.drawTableButton(r, footer.newTable(), sortLabel(), mouseX, mouseY,
                    QuestBookScreen.EditAction.ASSETS_TEMPLATE_SORT, book.assetsTargets);
        }
        book.bookRewards.drawTableButton(r, footer.done(), Labels.of("tenet.screen.done"), mouseX, mouseY,
                QuestBookScreen.EditAction.ASSETS_CLOSE, book.assetsTargets);

        book.bookRewards.drawTableHint(r, assetsHint);

        // Where the open rename box goes, every frame: the shelf scrolls under it like the card's
        // fields scroll under theirs, so it is repositioned from this panel's own target list.
        book.editorControls.repositionInlineEditor();
    }

    /** The section's name in the author's words. */
    private static String sectionName(dev.ellipog.tenet.client.dev.AssetsLayout.Section section) {
        return switch (section) {
            case TABLES -> "Tables";
            case QUESTS -> "Quests";
            case TEMPLATES -> "Templates";
        };
    }

    /**
     * The panel's press: the tab strip first, then the page's rows, then the footer.
     *
     * <p>The tabs come first because their rectangles touch the page's band in no way at all — they are
     * a separate strip — and asking the layout's own function keeps the answer the same one the
     * drawing used. The tabs are also registered targets, which is what gets a press past the gate
     * that only walks target lists; the resolution here is what names the section.
     */
    void pressAssets(double mouseX, double mouseY) {
        if (book.assetsFrame == null) {
            return;
        }
        var section = dev.ellipog.tenet.client.dev.AssetsLayout.sectionAt(book.assetsFrame, (int) mouseX,
                (int) mouseY);
        if (section != null) {
            showAssets(section);
            return;
        }
        // Last drawn wins: a row's chips sit inside the row's own rectangle, so a forward scan meets
        // the row first and the chip behind it never fires -- which is exactly how a template's ×
        // (and a table's Copy and × beside it) came to do nothing at all. The drawing paints chips
        // over rows, and the press reads the stack in the same order.
        for (int i = book.assetsTargets.size() - 1; i >= 0; i--) {
            QuestBookScreen.EditTarget target = book.assetsTargets.get(i);
            if (target.box().contains(mouseX, mouseY)) {
                pressAssetTarget(target, mouseX, mouseY);
                return;
            }
        }
    }

    /** What a press on one of the panel's targets does. */
    private void pressAssetTarget(QuestBookScreen.EditTarget target, double mouseX, double mouseY) {
        switch (target.action()) {
            case ASSETS_CLOSE -> book.closeOverlay();
            // The browser's own three verbs, so the two lists cannot disagree about what making, copying
            // or removing a table does -- including that a table something still points at is refused by
            // the server, which is where that guard lives.
            case ASSETS_NEW -> book.bookRewards.createTable();
            case ASSETS_COPY -> book.bookRewards.duplicateTable(target.path());
            case ASSETS_REMOVE -> {
                // **It asks first, as the browser's own `×` does.** This was the one destructive control in
                // the book that acted on a single press: a `×` the width of a character, one chip from
                // Copy, on a row whose press also opens the table. The server's referrer guard refuses a
                // table something still points at, which is not the same thing as asking -- an unreferenced
                // table is the ordinary case, and one press was enough to rename it out of the way.
                if (!target.path().equals(book.assetsConfirmRemove)) {
                    book.assetsConfirmRemove = target.path();
                    book.status("Press again to remove \"" + target.path() + "\"", true);
                    return;
                }
                book.assetsConfirmRemove = null;
                book.bookRewards.deleteTable(target.path());
            }
            case ASSETS_TEMPLATE_SORT -> {
                // Recent, Name, Type and back: the order the shelf is already in decides the next one,
                // read from the file rather than a field, so the footer can never disagree with the page.
                // Each key is written whole, because the language sweep matches literals: a concatenated
                // key would render fine in game and read as an orphan in the test.
                var next = switch (dev.ellipog.tenet.client.dev.ComponentTemplates.Sort
                        .known(ClientWorking.templateSort())) {
                    case RECENT -> dev.ellipog.tenet.client.dev.ComponentTemplates.Sort.NAME;
                    case NAME -> dev.ellipog.tenet.client.dev.ComponentTemplates.Sort.TYPE;
                    case TYPE -> dev.ellipog.tenet.client.dev.ComponentTemplates.Sort.RECENT;
                };
                ClientWorking.rememberTemplateSort(next.key());
                assetsBody.setScrollY(0);
                book.status(switch (next) {
                    case NAME -> "tenet.status.template_sort_name";
                    case TYPE -> "tenet.status.template_sort_type";
                    case RECENT -> "tenet.status.template_sort_recent";
                }, false);
            }
            case ASSETS_TEMPLATE_DELETE -> {
                // The same second press the tables' cross asks for: a saved setup is the author's own
                // work, and one press on a thirteen-pixel cross must never be enough to lose it.
                if (!target.path().equals(book.assetsConfirmRemove)) {
                    book.assetsConfirmRemove = target.path();
                    book.status("Press again to delete \"" + target.value() + "\"", true);
                    return;
                }
                book.assetsConfirmRemove = null;
                if (dev.ellipog.tenet.client.dev.ComponentTemplates.delete(target.path())) {
                    book.status("Deleted \"" + target.value() + "\"", false);
                }
            }
            // A saved template's pencil: the name edits in place, through the same inline editor
            // the quest card's fields use. The id never changes, so nothing that names the template
            // moves while its words do.
            case ASSETS_RENAME -> book.editorControls.openInlineEditor(target, mouseX, mouseY);
            case ASSETS_ROW -> {
                switch (book.assetsSection) {
                    case TABLES -> openTableEditorOn(target.path());
                    case QUESTS -> {
                        // The canvas, not the card: `locateOnCanvas` closes whatever is open, switches
                        // chapter when the quest lives elsewhere, glides the camera until the node is
                        // centred and flashes its outline -- the same call the prerequisite row's locate
                        // icon makes. A list of every quest is for finding one, and finding it means being
                        // taken there; opening its card instantly answered a question nobody had asked.
                        book.locateOnCanvas(target.path());
                    }
                    case TEMPLATES -> applyTemplate(target.path());
                    default -> {
                    }
                }
            }
            // A tab press that reached the target scan: `pressAssets` resolves the section from the
            // layout first and usually returns before this runs, so this is the second road to the
            // same section rather than a second answer. It resolves by name among the sections this
            // depth shows, which is never a blank page.
            case ASSETS_SECTION -> showAssets(sectionNamed(target.path()));
            default -> {
            }
        }
    }

    /**
     * Applies a template to the open quest: a copy with fresh ids, never a link.
     *
     * <p>Tasks and rewards append to the quest's own list through {@code Insert} — the same op
     * duplicating an entry sends, so undo is one step and the server validates the copy like any
     * hand-made edit. Conditions append to the last-touched entry's list through a rebuilt
     * array — the same move the condition picker makes — after verifying that entry is still
     * the open quest's. A refusal costs nothing and says where the picker's own path is.
     */
    private void applyTemplate(String templateId) {
        var template = dev.ellipog.tenet.client.dev.ComponentTemplates.byId(templateId).orElse(null);
        if (template == null) {
            return;
        }
        if (!QuestBookScreen.knownTemplateType(template)) {
            book.status("tenet.status.template_unknown_type", true);
            return;
        }
        String quest = book.editTarget();
        if (quest == null) {
            book.status("tenet.status.template_open_a_quest", true);
            return;
        }
        JsonObject replica = book.replicaQuest();
        switch (template.kind()) {
            case TASK, REWARD -> {
                String member = template.kind()
                        == dev.ellipog.tenet.client.dev.ComponentTemplate.Kind.TASK ? "tasks" : "rewards";
                int at = 0;
                if (replica != null && replica.has(member) && replica.get(member).isJsonArray()) {
                    at = replica.getAsJsonArray(member).size();
                }
                book.send(new EditorOp.Insert(quest, member, at, template.freshCopy()));
                dev.ellipog.tenet.client.dev.ComponentTemplates.noteEntryContext(quest, member, at);
                dev.ellipog.tenet.client.dev.ComponentTemplates.markUsed(templateId);
                book.status("Applied \"" + template.name() + "\" to " + quest, false);
            }
            case CONDITION -> {
                var landing = conditionLanding(quest);
                if (landing == null) {
                    book.status("tenet.status.template_condition_needs_entry", true);
                    return;
                }
                com.google.gson.JsonArray conditions = new com.google.gson.JsonArray();
                JsonElement existing = QuestPanelLayout.get(landing.replica(), landing.path());
                if (existing != null && existing.isJsonArray()) {
                    for (JsonElement value : existing.getAsJsonArray()) {
                        conditions.add(value.deepCopy());
                    }
                }
                conditions.add(template.freshCopy());
                book.sendField(quest, landing.path(), conditions);
                dev.ellipog.tenet.client.dev.ComponentTemplates.markUsed(templateId);
                book.status("Applied \"" + template.name() + "\" to " + quest, false);
            }
        }
    }

    /**
     * Where a condition template lands: the remembered entry's conditions list, verified against
     * the open quest.
     *
     * <p>One derivation for the hover's promise and the press's act ({@code applyHint} asks this
     * too), because a landing the hint names and the press cannot find is the fault the hint
     * exists to prevent.
     *
     * @param quest the open quest's id
     * @return the replica and the conditions path, or null when there is no live entry
     */
    private ConditionLanding conditionLanding(String quest) {
        if (quest == null) {
            return null;
        }
        var context = dev.ellipog.tenet.client.dev.ComponentTemplates.entryContext()
                .filter(each -> each.questId().equals(quest)).orElse(null);
        JsonObject replica = book.replicaQuest();
        JsonElement found = context == null || replica == null ? null
                : QuestPanelLayout.get(replica, context.member() + "." + context.index());
        if (context == null || found == null || !found.isJsonObject()) {
            return null;
        }
        return new ConditionLanding(replica, context.member() + "." + context.index() + ".conditions");
    }

    /** A verified landing for a condition template: the replica it was checked against, and the path. */
    private record ConditionLanding(JsonObject replica, String path) {
    }

    /**
     * Opens a named table's editor with no quest behind it, which is what the Assets list is for.
     *
     * <p>The same state `ClientTableOpen` sets when `/tenet table edit` arrives, so the command and the
     * list are one road: clearing the stack and the roll, and remembering where the author came from.
     */
    private void openTableEditorOn(String table) {
        dev.ellipog.tenet.client.ClientWorking.rememberTable(table);
        book.tableAddress = dev.ellipog.tenet.editor.TableAddress.of(table);
        book.tableStack.clear();
        book.tableApplied = 0;
        book.tableUndone = 0;
        book.tableFolded.clear();
        book.tableShowRoll = false;
        book.tableImportOpen = false;
        book.tablePreview = dev.ellipog.tenet.quest.reward.TableReward.Mode.RANDOM;
        // Back to the panel, not to the book: the editor was opened from a list.
        book.tableReturn = PanelKind.ASSETS;
        book.applyColumns(PanelStack.asRoot(book.columns(), PanelKind.TABLE_EDITOR));
        book.rebuildWidgets();
    }

    void pressTableEditor(double mouseX, double mouseY) {
        if (book.tableEditorFrame == null || book.tableAddress == null) {
            return;
        }
        // (The Import popover was asked first here, because it is drawn on top and the scan below takes the
        // first target that contains the point. It is the gate's now -- see `pressDropdown` and
        // `pressImportPopover` -- which is the same rule one step earlier, and the one that also covers the
        // widget pass this method never sees. The block was here rather than in the gate because the arm
        // that calls this is only reached when a drawn target is under the pointer, which is exactly the
        // hole a press in the popover's own padding fell through.)
        for (QuestBookScreen.EditTarget target : book.editTargets) {
            if (!target.box().contains(mouseX, mouseY)) {
                continue;
            }
            book.pressTableEditorTarget(target, mouseX, mouseY);
            return;
        }
        // A press that hit nothing dismisses an open Import popover: the popover is closed by the press
        // that misses it, the same rule the sidebar's menu follows, and the press is spent doing it. Kept
        // although the gate now answers every press while the popover is up: this is the same sentence, and
        // a reader looking for "what closes it" should find it where the panel's other presses are.
        book.tableImportOpen = false;
    }

    // ------------------------------------------------------------------
    // A folded entry's own fields
    // ------------------------------------------------------------------

    /** One entry of a table's tree by position, or null. */
    static JsonObject entryAt(JsonObject root, int index) {
        if (root == null || !root.has("entries") || !root.get("entries").isJsonArray()) {
            return null;
        }
        var entries = root.getAsJsonArray("entries");
        if (index < 0 || index >= entries.size() || !entries.get(index).isJsonObject()) {
            return null;
        }
        return entries.get(index).getAsJsonObject();
    }

    /** One field of one entry as text, for seeding a box: the drafted value, or empty. */
    String tableFieldText(int index, String field) {
        JsonObject entry = book.draftedEntry(index);
        return entry == null ? "" : QuestBookScreen.rawValue(entry, "reward." + field);
    }

    /** One entry's whole reward as compact JSON, for the raw row. */
    String entryRewardText(int index) {
        JsonObject entry = book.draftedEntry(index);
        JsonObject reward = entry == null ? null : BookRewards.rawObject(entry, "reward");
        return reward == null ? "{}" : reward.toString();
    }

    /**
     * Opens the inline editor on a field of a table, with the kind its text commits as.
     *
     * <p>One way in, so the box a fold opens and the box the weight's own press opens are the same box
     * with the same commit — the only difference being what the text becomes, which is the kind.
     */
    void openTableFieldEditor(int index, String field,
                                      dev.ellipog.tenet.client.dev.TableRowFields.Kind kind,
                                      String shown, BookGeometry.Rect box, int textX, int textY,
                                      double mouseX, double mouseY) {
        if (book.tableAddress == null) {
            return;
        }
        String path = QuestBookScreen.tableEntryPath(index, field);
        // The commit goes to the TABLE: the inline editor's own commit path writes a quest field, and a
        // path like `entries.1.reward.amount` written into a quest file is a stray member in the wrong
        // file. `tableEdit` is what says which file this box belongs to.
        book.tableEdit = new QuestBookScreen.TableEdit(path, kind);
        book.editorControls.openInlineEditor(new QuestBookScreen.EditTarget(QuestBookScreen.EditAction.FIELD,
                path, box, textX, textY, shown, null, index),
                mouseX, mouseY);
    }

    /** A switch inside a fold: the field negated. */
    void toggleTableFlag(int index, String field) {
        if (book.tableAddress == null || field.isEmpty()) {
            return;
        }
        JsonObject entry = book.draftedEntry(index);
        boolean on = entry != null && QuestBookScreen.flagOn(entry, "reward." + field);
        book.bookRewards.sendTableValue(QuestBookScreen.tableEntryPath(index, field),
                new com.google.gson.JsonPrimitive(!on));
    }

    /**
     * The {@code auto} chip: the next word in its ring, wrapping from the end back to the start.
     *
     * <p>The ring is {@code TableRowFields.AUTO_VALUES} -- the format's five words, taken from the enum --
     * and it starts at {@code default}, which is the absent value, so a press on a reward that declares
     * nothing moves it to {@code disabled} rather than to a word nothing understands. A value the ring
     * does not hold is left alone; see {@link QuestBookScreen#nextOption}.
     */
    void cycleTableChoice(int index, String field) {
        if (book.tableAddress == null || field.isEmpty()) {
            return;
        }
        JsonObject entry = book.draftedEntry(index);
        String current = entry == null ? "" : QuestBookScreen.rawValue(entry, "reward." + field);
        var ring = dev.ellipog.tenet.client.dev.TableRowFields.AUTO_VALUES;
        String next = QuestBookScreen.nextOption(ring, current);
        if (next.equals(current)) {
            return;
        }
        book.bookRewards.sendTableValue(QuestBookScreen.tableEntryPath(index, field),
                new com.google.gson.JsonPrimitive(next));
    }

    /** A stepper inside a fold: one entry's numeric field, nudged. Shift moves ten. */
    void nudgeTableNumber(int index, String field, int direction) {
        if (book.tableAddress == null || field.isEmpty()) {
            return;
        }
        JsonObject entry = book.draftedEntry(index);
        String current = entry == null ? "" : QuestBookScreen.rawValue(entry, "reward." + field);
        int value;
        try {
            value = current.isEmpty() ? 0 : (int) Double.parseDouble(current);
        }
        catch (NumberFormatException notANumber) {
            // A field holding something that is not a number: a press moves it to where the stepper
            // would have started, which is more use than refusing and leaving it stuck.
            value = 0;
        }
        int step = BookEditorControls.shiftDown() ? 10 : 1;
        // Zero is the floor for every numeric field a fold has: a count of zero is a legal count, and a
        // permission level of zero is how the format spells "no permission". What it is not is a second
        // clamp of its own per field -- that would be a rule about the format living in a press handler.
        int next = Math.max(0, value + direction * step);
        book.bookRewards.sendTableValue(QuestBookScreen.tableEntryPath(index, field),
                new com.google.gson.JsonPrimitive(next));
    }

    /** How many lines one entry's fold draws: its type's own fields, packed. Zero when it is not open. */
    int tableFoldLines(int index) {
        var model = book.bookRewards.tableModel();
        if (model.isEmpty() || index < 0 || index >= model.get().entryCount()) {
            return 0;
        }
        return dev.ellipog.tenet.client.dev.TableRowFields
                .lineCount(model.get().entries().get(index).reward());
    }

    /**
     * Moves the fold state down over a removed entry, and drops the fold that was at the hole.
     *
     * <p>Folds are keyed by position, so removing entry 1 without this leaves entry 2's fold open — and
     * entry 2 is now entry 1, so its fields are drawn under the wrong entry's name and the next press on
     * that fold edits a row the author did not open.
     */
    void forgetFoldAfterRemove(int removed) {
        java.util.Set<Integer> moved = new java.util.LinkedHashSet<>();
        for (int at : book.tableFolded) {
            if (at < removed) {
                moved.add(at);
            }
            else if (at > removed) {
                moved.add(at - 1);
            }
            // The fold at the removed position is dropped: it belonged to the entry that is gone.
        }
        book.tableFolded.clear();
        book.tableFolded.addAll(moved);
    }

    /**
     * Opens the book's item picker, with the pick aimed at the table editor.
     *
     * <p>The picker is the book's own: the same list, the same search, the same carried stacks with
     * their data. Only the commit differs -- see {@link BookItemPicker#commitTablePick} -- and only the return: it
     * closes back into the editor rather than into the card behind it.
     *
     * @param title what this pick is for, in the author's words. The card's title came from
     *              {@code PickTarget}, whose three sentences are all about a chapter's, a group's or the
     *              book's icon — so a table pick, which sets no target of its own, claimed to be choosing
     *              a chapter's icon. What a pick is for is the caller's to say; the field it writes is
     *              the other half, and it already came from the caller.
     */
    void openTableItemPick(String path, int entryIndex, String title) {
        if (book.tableAddress == null) {
            return;
        }
        // The standalone picker overlay rather than the card's page of it: the card is not what is open
        // here, and a page of a panel that is not on screen is a page nobody can see.
        book.tablePickPath = path == null ? "" : path;
        book.tablePickReturn = true;
        book.forgetPickSubject();
        book.pickTarget = QuestBookScreen.PickTarget.QUEST;
        book.pickTitle = title;
        // The subject line names the table, and the icon shows what is being replaced — the table's own
        // icon, or the item the picked one will replace. The card's picker has said both since it was
        // written; this page said neither, so it was a list of the whole item registry with no statement
        // of which table or which field it was for.
        book.pickName = book.bookRewards.tableTitle();
        book.pickIcon = book.tablePickSubjectFor(book.tablePickPath, entryIndex);
        book.pickingItemPath = path == null || path.isEmpty() ? "entries.new" : path;
        book.pickingItemCurrent = "";
        // Only the picture override may be cleared: it is optional, so removing the whole object
        // restores the type's own picture. Every other item field here is required by its type.
        book.pickingItemClearPath = path != null && path.endsWith(".icon") ? path : null;
        book.pickerSource = null;
        book.pickerObserveType = "";
        book.pickerFrame = null;
        book.itemSearch = null;
        book.pickerSelected = -1;
        book.pickerBody.setScrollY(0);
        book.questEditor.openPickerOverlay();
    }

    // ------------------------------------------------------------------
    // Dropping an item in from a recipe viewer
    // ------------------------------------------------------------------

    /**
     * Takes the canvas to a quest without putting anything away: chapter, selection, camera, flash.
     *
     * <h2>Split out of `locateOnCanvas` so "go there" and "open it, and go there" share one move</h2>
     *
     * <p>The prerequisite row now does both on a plain press -- it opens the required quest's card *and*
     * brings the canvas to it -- while the locate icon and the shift/middle gesture still mean the camera
     * alone. Two callers, one implementation of where the camera goes, because a second copy of this is
     * how "the row took me to the right quest but the wrong place" would come about.
     *
     * <p><b>The camera glides, always.</b> A glide is advanced from the canvas drawing, and the canvas is
     * drawn the whole time now — every kind is a rail beside it rather than a card over it — so there is no
     * arrangement in which a glide would be started and then not play.
     *
     * <p>The flash is the half that answers "which of these forty nodes is it": a camera that arrives at
     * an unremarkable square has moved the question rather than answered it.
     */
    void revealNode(String questId) {
        ClientQuestCache.Entry target = book.entryFor(questId);
        if (target == null) {
            book.status("No quest " + questId + " to show", true);
            return;
        }
        if (!target.chapterId().equals(QuestBookScreen.effectiveChapter())) {
            book.bookSidebar.selectChapter(target.chapterId());
        }
        QuestBookScreen.selectedQuest = questId;
        QuestBookScreen.multiSelection.clear();
        // The view is the caller's now, not the chapter auto-centre's: `centreCanvas` re-centres a
        // chapter once, and a glide it kept overriding would never arrive.
        QuestBookScreen.pannedChapter = QuestBookScreen.effectiveChapter();
        QuestBookScreen.centred = true;
        startGlide(target);
        book.flashQuest = questId;
        book.flashStart = Util.getMillis();
    }

    /**
     * Starts the camera's glide to a node's centre, from where the view stands now.
     */
    private void startGlide(ClientQuestCache.Entry entry) {
        Viewport view = book.viewport();
        book.glideQuest = entry.id();
        book.glideStart = Util.getMillis();
        // The locate takes the whole camera: a zoom glide still stepping must not fight it, for the same
        // reason the hand cancels either of them.
        book.bookCanvas.zoomGlideStart = -1L;
        book.glideFromX = view.offsetX();
        book.glideFromY = view.offsetY();
        // Content coordinates for the node's middle, through the drawing's own helpers — so the target
        // is the node as it is drawn, draft size included, rather than a second opinion about where it
        // is. The zoom does not change: "show me where it is" is not "zoom in on it".
        book.glideToX = CanvasReveal.offsetX(view.viewWidth(),
                view.contentX(book.bookRewards.nodeCentreX(entry)), view.scale());
        book.glideToY = CanvasReveal.offsetY(view.viewHeight(),
                view.contentY(book.bookRewards.nodeCentreY(entry)), view.scale());
    }

    /**
     * Whether one task's row still offers its hand-in, with a press already in flight taken off.
     *
     * <p>The mark is the difference between "the server would accept this" and "this has already been
     * asked for": the button has to go when it is pressed, or the row would offer a second press for the
     * task the player is watching change, and the count would be the only thing that moved.
     */
    boolean submitOfferedWithMarks(String questId, int taskIndex) {
        return !book.pendingClaims.isMarked(PendingClaims.taskKey(questId, taskIndex))
                && ClientQuestCache.submitOffered(questId, taskIndex);
    }
}
