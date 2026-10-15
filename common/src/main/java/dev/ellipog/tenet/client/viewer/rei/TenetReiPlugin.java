package dev.ellipog.tenet.client.viewer.rei;

import dev.ellipog.armature.client.render.GuiGraphicsRenderer;
import dev.ellipog.armature.client.render.GuiRenderer;
import dev.ellipog.tenet.Constants;
import dev.ellipog.tenet.client.viewer.Integrations;
import dev.ellipog.tenet.client.viewer.PageArt;
import dev.ellipog.tenet.client.viewer.PagePalette;
import dev.ellipog.tenet.client.viewer.QuestContent;
import dev.ellipog.tenet.client.viewer.QuestPage;
import dev.ellipog.tenet.client.viewer.QuestPageLayout;
import dev.ellipog.tenet.client.viewer.QuestRef;
import dev.ellipog.tenet.client.viewer.QuestRow;
import dev.ellipog.tenet.client.viewer.RecipeLookups;
import dev.ellipog.tenet.client.viewer.Viewers;

import me.shedaniel.math.Point;
import me.shedaniel.math.Rectangle;
import me.shedaniel.rei.api.client.gui.DisplayRenderer;
import me.shedaniel.rei.api.client.gui.Renderer;
import me.shedaniel.rei.api.client.gui.widgets.Widget;
import me.shedaniel.rei.api.client.gui.widgets.Widgets;
import me.shedaniel.rei.api.client.plugins.REIClientPlugin;
import me.shedaniel.rei.api.client.registry.category.CategoryRegistry;
import me.shedaniel.rei.api.client.registry.display.DisplayCategory;
import me.shedaniel.rei.api.client.registry.display.DisplayRegistry;
import me.shedaniel.rei.api.client.registry.display.DynamicDisplayGenerator;
import me.shedaniel.rei.api.client.view.ViewSearchBuilder;
import me.shedaniel.rei.api.common.category.CategoryIdentifier;
import me.shedaniel.rei.api.common.display.basic.BasicDisplay;
import me.shedaniel.rei.api.common.entry.EntryIngredient;
import me.shedaniel.rei.api.common.entry.EntryStack;
import me.shedaniel.rei.api.common.util.EntryIngredients;
import me.shedaniel.rei.api.common.util.EntryStacks;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * REI: the same item-to-quest lookup as plain rows, on the same shared snapshot.
 *
 * <h2>Dynamic by generation, not by registration</h2>
 *
 * <p>A {@link DynamicDisplayGenerator} is asked at view time — when a player presses the lookup keys
 * on an item, or opens the category — so the displays it returns come from the live index and
 * nothing has to be re-registered when the tree changes. Registering displays statically would also
 * mean the item lookup showed each quest twice, because REI indexes registered displays by their
 * entries and the generator answers the same question.
 *
 * <h2>Why a category at all, with nothing registered in it</h2>
 *
 * <p>Every display needs a category to render in, so the category exists and announces itself; its
 * contents arrive through {@link QuestDisplays#generate} when the player opens it. An empty category
 * list entry would be a worse answer than a live one.
 *
 * <h2>What is deliberately thin here</h2>
 *
 * <p>The row is a title you can click, a live label and a bar. No arrows, no recipe frame — this is
 * the fallback tier, and its job is that a player without EMI is not left without the feature.
 */
@me.shedaniel.rei.forge.REIPluginClient
public final class TenetReiPlugin implements REIClientPlugin, RecipeLookups.Lookup {

    private static final int WIDTH = 150;

    /**
     * The one identifier this plugin hands out, built lazily and kept: categories and displays must
     * share the object, and the content's id is only known once a consumer has installed it.
     */
    private CategoryIdentifier<QuestDisplay> identifier;

    private CategoryIdentifier<QuestDisplay> identifier(QuestContent content) {
        if (identifier == null) {
            identifier = CategoryIdentifier.of(content.categoryId());
        }
        return identifier;
    }

    @Override
    public void registerCategories(CategoryRegistry registry) {
        QuestContent content = eligible();
        if (content == null) {
            return;
        }
        registry.add(new QuestCategory(identifier(content), content, new QuestPageLayout(WIDTH)));
    }

    @Override
    public void registerDisplays(DisplayRegistry registry) {
        QuestContent content = eligible();
        if (content == null) {
            return;
        }
        registry.registerDisplayGenerator(identifier(content), new QuestDisplays(content, identifier(content)));
    }

    /**
     * The content, only when this client's one viewer is REI and a consumer has installed quests.
     *
     * <p>The recipe lookup is installed here rather than in the register methods: {@code mayInstall}
     * is the one gate that decides which viewer owns this client, so it is the one place that
     * installs.
     */
    private QuestContent eligible() {
        if (!Viewers.mayInstall(Viewers.Viewer.REI)) {
            return null;
        }
        RecipeLookups.install(this);
        return Integrations.content().orElse(null);
    }

    /**
     * {@inheritDoc}
     *
     * <p>The view builder takes one entry per call, so a tag is expanded to its members first —
     * {@code EntryIngredients.ofItemTag} is REI's own expansion — and an empty tag opens nothing
     * rather than an empty view. Guarded with a catch because REI's helper throws rather than
     * returning false before its client has initialised, the same honesty {@link Viewers} practises
     * when it asks a platform that is not there.
     */
    @Override
    public void recipesFor(RecipeLookups.Target target) {
        try {
            ViewSearchBuilder builder = ViewSearchBuilder.builder();
            if (target.isTag()) {
                List<EntryStack<?>> members = new ArrayList<>(EntryIngredients.ofItemTag(target.tag()));
                if (members.isEmpty()) {
                    return;
                }
                for (EntryStack<?> member : members) {
                    builder.addRecipesFor(member);
                }
            }
            else {
                builder.addRecipesFor(EntryStacks.of(target.item()));
            }
            builder.open();
        }
        catch (RuntimeException e) {
            Constants.LOG.debug("REI is not ready to open a view ({})", e.toString());
        }
    }

    /** A tag is expanded to its members; see {@link #recipesFor}. */
    @Override
    public boolean supportsTags() {
        return true;
    }

    // ------------------------------------------------------------------
    // The display
    // ------------------------------------------------------------------

    /**
     * One quest as a REI display: its item references, plus the page it draws.
     *
     * <p>{@code BasicDisplay} carries the inputs and outputs REI's own machinery reads; the page is
     * what {@link QuestCategory} renders, so the two facts travel together without either being
     * derived from the other.
     */
    private static final class QuestDisplay extends BasicDisplay {

        private final QuestPage page;
        private final CategoryIdentifier<QuestDisplay> identifier;

        QuestDisplay(QuestPage page, CategoryIdentifier<QuestDisplay> identifier, EntryStack<?> focus) {
            super(inputs(page, focus), outputs(page));
            this.page = page;
            this.identifier = identifier;
        }

        QuestPage page() {
            return page;
        }

        @Override
        public CategoryIdentifier<?> getCategoryIdentifier() {
            return identifier;
        }

        private static List<EntryIngredient> inputs(QuestPage page, EntryStack<?> focus) {
            List<EntryIngredient> in = new ArrayList<>();
            if (focus != null) {
                // The entry the player looked up leads the inputs, so the view reads as being about
                // what was searched for; the rest is the quest's own requirement list.
                in.add(EntryIngredient.of(focus));
            }
            for (QuestRow row : page.tasks()) {
                if (!row.icon().isEmpty()) {
                    in.add(EntryIngredient.of(EntryStacks.of(row.icon())));
                }
            }
            return in;
        }

        private static List<EntryIngredient> outputs(QuestPage page) {
            List<EntryIngredient> out = new ArrayList<>();
            for (QuestRow row : page.rewards()) {
                if (!row.icon().isEmpty()) {
                    out.add(EntryIngredient.of(EntryStacks.of(row.icon())));
                }
            }
            return out;
        }
    }

    // ------------------------------------------------------------------
    // The category
    // ------------------------------------------------------------------

    private static final class QuestCategory implements DisplayCategory<QuestDisplay> {

        private final CategoryIdentifier<QuestDisplay> identifier;
        private final QuestContent content;
        private final QuestPageLayout layout;

        QuestCategory(CategoryIdentifier<QuestDisplay> identifier, QuestContent content,
                      QuestPageLayout layout) {
            this.identifier = identifier;
            this.content = content;
            this.layout = layout;
        }

        @Override
        public CategoryIdentifier<? extends QuestDisplay> getCategoryIdentifier() {
            return identifier;
        }

        @Override
        public Component getTitle() {
            return content.categoryTitle();
        }

        @Override
        public Renderer getIcon() {
            return EntryStacks.of(content.categoryIcon());
        }

        @Override
        public int getDisplayWidth(QuestDisplay display) {
            return WIDTH;
        }

        /** The tallest page the category can be asked to draw; see the JEI adapter for the reason. */
        @Override
        public int getDisplayHeight() {
            int tallest = QuestPageLayout.HEADER_HEIGHT;
            for (QuestPage page : content.pages()) {
                tallest = Math.max(tallest, layout.height(page));
            }
            return tallest;
        }

        /**
         * No slot area above the page.
         *
         * <p>The default renderer lays the display's entries out as slots, which would draw every
         * task item twice — once there, once in its row — so the page owns its own layout and the
         * default is replaced with nothing.
         */
        @Override
        public DisplayRenderer getDisplayRenderer(QuestDisplay display) {
            return new BlankRenderer();
        }

        @Override
        public List<Widget> setupDisplay(QuestDisplay display, Rectangle bounds) {
            List<Widget> widgets = new ArrayList<>();
            QuestPage page = display.page();

            QuestPageLayout.Box title = shift(layout.headerTitle(), bounds);
            widgets.add(Widgets.createButton(
                            new Rectangle(title.x() - 2, title.y() - 1, title.width() + 2, 12),
                            Component.literal(page.quest().title()))
                    .onClick(button -> content.openQuest(page.quest().id()))
                    .tooltipLine(Component.translatable("tenet.viewer.open")));
            QuestPageLayout.Box badge = shift(layout.headerBadge(), bounds);
            widgets.add(Widgets.createLabel(new Point(badge.x(), badge.y()),
                            Component.literal(content.stateText(page.quest().id())))
                    .color(content.stateColour(page.quest().id())).leftAligned().noShadow());
            QuestPageLayout.Box headerIcon = shift(layout.headerIcon(), bounds);
            widgets.add(new IconWidget(page.quest(), headerIcon));

            if (!page.tasks().isEmpty()) {
                QuestPageLayout.Box heading = shift(layout.tasksHeading(page), bounds);
                widgets.add(Widgets.createLabel(
                                new Point(heading.x() + QuestPageLayout.SLOT_X, heading.y() + 1),
                                content.tasksLabel())
                        .color(PagePalette.MUTED).leftAligned().noShadow());
            }
            for (int i = 0; i < page.tasks().size(); i++) {
                QuestPageLayout.Box row = shift(layout.taskRow(i), bounds);
                widgets.add(new RowWidget(content, page.quest().id(), true,
                        page.tasks().get(i).sourceIndex(), layout, row));
                addSlot(widgets, page.tasks().get(i), layout, layout.taskRow(i), bounds, true);
            }
            if (!page.rewards().isEmpty()) {
                QuestPageLayout.Box heading = shift(layout.rewardsHeading(page), bounds);
                widgets.add(Widgets.createLabel(
                                new Point(heading.x() + QuestPageLayout.SLOT_X, heading.y() + 1),
                                content.rewardsLabel())
                        .color(PagePalette.MUTED).leftAligned().noShadow());
            }
            for (int i = 0; i < page.rewards().size(); i++) {
                QuestPageLayout.Box row = shift(layout.rewardRow(page, i), bounds);
                widgets.add(new RowWidget(content, page.quest().id(), false,
                        page.rewards().get(i).sourceIndex(), layout, row));
                addSlot(widgets, page.rewards().get(i), layout, layout.rewardRow(page, i), bounds, false);
            }
            return widgets;
        }

        /**
         * One slot widget: an input for tasks, display-only for rewards.
         *
         * <p>A reward is something to collect, not an ingredient to look up, so its slot must not
         * answer clicks with recipes — same rule as JEI, where reward rows have no slots at all.
         * Tooltips stay on either way: the icon still names its item.
         */
        private static void addSlot(List<Widget> widgets, QuestRow row, QuestPageLayout layout,
                                    QuestPageLayout.Box rowBox, Rectangle bounds, boolean task) {
            if (row.icon().isEmpty()) {
                // A tag row's members are not known here -- the index resolved them; drawing a
                // representative item would be picking one arbitrarily. The label carries the tag.
                return;
            }
            QuestPageLayout.Box icon = shift(layout.icon(rowBox), bounds);
            var slot = Widgets.createSlot(new Point(icon.x(), icon.y()))
                    .entry(EntryStacks.of(row.icon()));
            widgets.add(task ? slot.markInput() : slot.notInteractable());
        }

        private static QuestPageLayout.Box shift(QuestPageLayout.Box box, Rectangle bounds) {
            return new QuestPageLayout.Box(box.x() + bounds.x, box.y() + bounds.y,
                    box.width(), box.height());
        }
    }

    /**
     * The quest's own icon in the header: what {@link QuestPageLayout#headerIcon} has always
     * reserved, drawn for the same reason as JEI's — a page without its quest's face is anonymous
     * in a list of pages.
     */
    private static final class IconWidget extends Widget {

        private final QuestRef quest;
        private final QuestPageLayout.Box box;

        IconWidget(QuestRef quest, QuestPageLayout.Box box) {
            this.quest = quest;
            this.box = box;
        }

        /** No children: the icon is one drawn picture, and REI's event dispatch asks for them. */
        @Override
        public List<? extends GuiEventListener> children() {
            return List.of();
        }

        @Override
        public void render(GuiGraphics graphics, int mouseX, int mouseY, float delta) {
            GuiRenderer renderer = new GuiGraphicsRenderer(graphics);
            if (!quest.icon().isEmpty()) {
                renderer.icon(quest.icon(), box.x(), box.y(), QuestPageLayout.ITEM);
                return;
            }
            if (!quest.textureIcon().isEmpty()) {
                ResourceLocation id = ResourceLocation.tryParse(quest.textureIcon());
                if (id != null) {
                    renderer.texture(id, box.x(), box.y(), box.width(), box.height());
                    return;
                }
            }
            if (!quest.spriteIcon().isEmpty()) {
                ResourceLocation id = ResourceLocation.tryParse(quest.spriteIcon());
                if (id != null) {
                    renderer.sprite(id, box.x(), box.y(), box.width(), box.height(), 0xFFFFFFFF);
                }
            }
        }
    }

    /** Draws nothing: the page's rows are the layout. */
    private static final class BlankRenderer extends DisplayRenderer {

        @Override
        public int getHeight() {
            return 0;
        }

        @Override
        public void render(GuiGraphics graphics, Rectangle bounds, int mouseX, int mouseY, float delta) {
            // Nothing to draw above the widgets.
        }
    }

    /**
     * One row's label, count and bar — read live, exactly as the other two adapters do it.
     *
     * <p>A plain {@link Widget} rather than a {@code Label}: the text changes while the page is open,
     * and a label built once would show the progress from when the view was built.
     */
    private static final class RowWidget extends Widget {

        private final QuestContent content;
        private final String questId;
        private final boolean task;
        private final int index;
        private final QuestPageLayout layout;
        private final QuestPageLayout.Box row;

        RowWidget(QuestContent content, String questId, boolean task, int index,
                  QuestPageLayout layout, QuestPageLayout.Box row) {
            this.content = content;
            this.questId = questId;
            this.task = task;
            this.index = index;
            this.layout = layout;
            this.row = row;
        }

        /** No children: the row is one drawn line, and REI's event dispatch asks for them. */
        @Override
        public List<? extends GuiEventListener> children() {
            return List.of();
        }

        /**
         * {@code Renderable}'s entry point — the current one.
         *
         * <p>REI's older {@code render(GuiGraphics, Rectangle, ...)} is deprecated, and its base
         * implementation delegates here (read from the class rather than assumed). Implementing only
         * this one is therefore both current and sufficient; overriding the deprecated variant would
         * shadow that delegation with a duplicate.
         */
        @Override
        public void render(GuiGraphics graphics, int mouseX, int mouseY, float delta) {
            GuiRenderer renderer = new GuiGraphicsRenderer(graphics);
            QuestRow live = task ? content.liveTask(questId, index) : content.liveReward(questId, index);

            QuestPageLayout.Box text = layout.text(row);
            if (task) {
                drawTaskRow(renderer, live, text);
            }
            else {
                drawRewardRow(renderer, live, text);
            }
        }

        private void drawTaskRow(GuiRenderer renderer, QuestRow live, QuestPageLayout.Box text) {
            int colour = live.locked() ? PagePalette.LOCKED
                    : live.done() ? PagePalette.COMPLETE
                    : PagePalette.TEXT;
            String count = live.need() > 0 ? Math.min(live.have(), live.need()) + " / " + live.need() : "";
            int countWidth = count.isEmpty() ? 0 : renderer.textWidth(count) + 4;
            renderer.text(PageArt.fit(renderer, live.label(), text.width() - countWidth),
                    text.x(), text.y(), colour);
            if (!count.isEmpty()) {
                renderer.text(count, text.right() - renderer.textWidth(count), text.y(),
                        PagePalette.MUTED);
            }
            if (live.need() > 0) {
                QuestPageLayout.Box bar = layout.bar(row);
                renderer.fill(bar.x(), bar.y(), bar.right(), bar.bottom(), PagePalette.BAR_TRACK);
                int filled = (int) Math.round(bar.width()
                        * Math.min(1.0, live.have() / (double) live.need()));
                if (filled > 0) {
                    renderer.fill(bar.x(), bar.y(), bar.x() + filled, bar.bottom(),
                            live.done() ? PagePalette.COMPLETE : PagePalette.PROGRESS);
                }
            }
        }

        /** A reward's label and status pill, never a bar; see the EMI adapter for the reason. */
        private void drawRewardRow(GuiRenderer renderer, QuestRow live, QuestPageLayout.Box text) {
            int colour = live.locked() ? PagePalette.LOCKED
                    : live.done() ? PagePalette.MUTED
                    : PagePalette.TEXT;
            QuestContent.RewardStatus status = PageArt.rewardStatus(live);
            if (status == null) {
                renderer.text(PageArt.fit(renderer, live.label(), text.width()),
                        text.x(), text.y(), colour);
                return;
            }
            String word = content.rewardStatusLabel(status).getString();
            int width = PageArt.pillWidth(renderer, word);
            renderer.text(PageArt.fit(renderer, live.label(), text.width() - width - 4),
                    text.x(), text.y(), colour);
            PageArt.pill(renderer, word, text.right() - width, row.y() + 4,
                    PageArt.rewardStatusColour(status));
        }
    }

    // ------------------------------------------------------------------
    // The lookup
    // ------------------------------------------------------------------

    /**
     * "Which quests want this item, and which give it", answered from the live index at view time.
     *
     * <p>The category's own contents come from {@link #generate}: opening a category is a view too,
     * and a category with nothing registered in it would otherwise open empty. Both paths return the
     * same displays, so a quest never appears twice in one view — the generator is the only source.
     */
    private static final class QuestDisplays implements DynamicDisplayGenerator<QuestDisplay> {

        private final QuestContent content;
        private final CategoryIdentifier<QuestDisplay> identifier;

        QuestDisplays(QuestContent content, CategoryIdentifier<QuestDisplay> identifier) {
            this.content = content;
            this.identifier = identifier;
        }

        @Override
        public Optional<List<QuestDisplay>> getUsageFor(EntryStack<?> entry) {
            return found(entry, true);
        }

        @Override
        public Optional<List<QuestDisplay>> getRecipeFor(EntryStack<?> entry) {
            return found(entry, false);
        }

        @Override
        public Optional<List<QuestDisplay>> generate(ViewSearchBuilder builder) {
            Set<CategoryIdentifier<?>> requested = new HashSet<>(builder.getCategories());
            requested.addAll(builder.getFilteringCategories());
            if (!requested.contains(identifier)) {
                return Optional.empty();
            }
            List<QuestDisplay> all = new ArrayList<>();
            for (QuestPage page : content.pages()) {
                all.add(new QuestDisplay(page, identifier, null));
            }
            return all.isEmpty() ? Optional.empty() : Optional.of(all);
        }

        private Optional<List<QuestDisplay>> found(EntryStack<?> entry, boolean usage) {
            ResourceLocation item = itemOf(entry);
            if (item == null) {
                return Optional.empty();
            }
            List<QuestRef> refs = usage
                    ? content.index().questsUsing(item)
                    : content.index().questsAwarding(item);
            List<QuestDisplay> out = new ArrayList<>(refs.size());
            for (QuestRef ref : refs) {
                // One lookup per ref rather than a walk of every page per ref; see QuestContent.page.
                QuestPage page = content.page(ref.id());
                if (page != null) {
                    out.add(new QuestDisplay(page, identifier, entry));
                }
            }
            return out.isEmpty() ? Optional.empty() : Optional.of(out);
        }

        private static ResourceLocation itemOf(EntryStack<?> entry) {
            return entry.getValue() instanceof ItemStack stack
                    ? BuiltInRegistries.ITEM.getKey(stack.getItem())
                    : null;
        }
    }
}
