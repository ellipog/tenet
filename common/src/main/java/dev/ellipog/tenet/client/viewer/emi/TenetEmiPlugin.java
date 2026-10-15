package dev.ellipog.tenet.client.viewer.emi;

import dev.ellipog.armature.client.render.GuiGraphicsRenderer;
import dev.ellipog.armature.client.render.GuiRenderer;
import dev.ellipog.tenet.client.viewer.Integrations;
import dev.ellipog.tenet.client.viewer.PageArt;
import dev.ellipog.tenet.client.viewer.PagePalette;
import dev.ellipog.tenet.client.viewer.QuestContent;
import dev.ellipog.tenet.client.viewer.QuestPage;
import dev.ellipog.tenet.client.viewer.QuestRow;
import dev.ellipog.tenet.client.viewer.QuestPageLayout;
import dev.ellipog.tenet.client.viewer.RecipeLookups;
import dev.ellipog.tenet.client.viewer.ViewerReloadWatch;
import dev.ellipog.tenet.client.viewer.Viewers;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.tooltip.ClientTooltipComponent;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;

/**
 * EMI: a quest is a page, not a row.
 *
 * <h2>Why EMI is the first-class integration</h2>
 *
 * <p>Its API renders arbitrary recipes, so a quest can be a real page — tasks as inputs, rewards as
 * outputs, the player's live progress drawn on it — and EMI's own ingredient index then answers
 * "which quests use this item" without any index of ours. The other two viewers render rows through
 * a shared fallback; this one is why that tier exists at all.
 *
 * <h2>The two threads, and the rule that keeps them apart</h2>
 *
 * <p>{@link #register} runs on EMI's reload worker, so it reads only the immutable snapshots the
 * content publishes and builds recipes from them. Everything live — progress, state, claimability —
 * is read by widgets while they draw, on the client thread. An adapter that called
 * {@code liveTask} from {@code register} would be reading the game from a worker; one that captured
 * progress into the recipe would show the numbers from when the page was registered.
 *
 * <h2>The reload watch, and the internal call it needs</h2>
 *
 * <p>EMI has no public API to add recipes after a reload, and quest data arrives after the client
 * has started — so a page set registered before the tree arrives would be empty until the player
 * relogs. The one lever is {@code EmiReloadManager.reload()}, which lives in EMI's runtime package
 * rather than its API. It is used here behind three guards (a world with a recipe manager, EMI
 * finished loading, one request per revision) and pinned to the EMI version in
 * {@code gradle.properties}; a future EMI that changes it breaks this one file, loudly, at compile
 * time. That is the trade for not shipping a quest page that only appears after a relog.
 *
 * <h2>Absence, and the class-loading rule</h2>
 *
 * <p>Nothing here is touched unless EMI is installed: the entrypoint is EMI's own discovery -- the
 * {@code emi} entrypoint on Fabric, {@link dev.emi.emi.api.EmiEntrypoint} on NeoForge -- and
 * {@link Integrations} reaches this class through {@link EmiViewer}, a holder that names no EMI
 * type. The failure this avoids is not a compile error — it is a {@code NoClassDefFoundError} at
 * construction on a client without EMI.
 */
@dev.emi.emi.api.EmiEntrypoint
public final class TenetEmiPlugin implements dev.emi.emi.api.EmiPlugin, Integrations.ViewerAdapter,
        RecipeLookups.Lookup {

    /** EMI's default recipe width; every page is this wide so the layout has one number. */
    private static final int WIDTH = 134;

    /**
     * The revision the last successful registration built its pages from. Static, because EMI constructs
     * its own plugin instance and the holder constructs another — the two must agree about what is
     * registered.
     *
     * <p>Written by {@link #register}, which EMI runs on <b>its own reload worker</b>, and read by
     * {@link #tick} on the client thread — which is why it is volatile and why it is the one piece of
     * this state the watch does not hold: the watch is asked a question, and this is the half of the
     * answer that arrives from somewhere else.
     *
     * <p>The revision a rebuild has been <i>asked</i> for is not kept here. It was, and it is the
     * watch's now — keeping a copy would be a second description of one fact, and the failure it invites
     * is the quiet kind: the two drifting apart and the debounce asking twice for one revision, or not
     * at all.
     */
    private static volatile long registeredRevision = -1L;

    /**
     * The debounce, and the reason it exists is worth reading before changing the number.
     *
     * <p>A rebuild here is <b>every recipe in the game</b>: EMI hands over a registry and expects every
     * category filled, not only this mod's pages. The content moves per <i>edit</i> — a dragged node, a
     * held count stepper, a field's commit — so one rebuild per revision is one rebuild per keystroke,
     * and the player paying for it is doing something else. Twelve client ticks is 600 ms: long enough
     * that a gesture settling between presses asks once, short enough that an author who edits and
     * immediately looks at the viewer does not feel they are waiting for it.
     *
     * <p>Static with the revisions above, for the same reason: two plugin instances, one viewer.
     */
    private static final ViewerReloadWatch WATCH = new ViewerReloadWatch(12);

    @Override
    public void register(dev.emi.emi.api.EmiRegistry registry) {
        if (!Viewers.mayInstall(Viewers.Viewer.EMI)) {
            return;
        }
        // This client's one viewer is EMI, so EMI also answers the book's row presses.
        RecipeLookups.install(this);
        QuestContent content = Integrations.content().orElse(null);
        if (content == null) {
            // No mod on this client has quests: no category, no empty page, nothing to click.
            return;
        }

        // Dragging a stack out of EMI and onto a table's entry list: the gesture that turns "this item"
        // into an entry without a search box. The screen decides whether it wants the drop -- see
        // `ItemDropTarget` -- so this is only the translation.
        registry.addDragDropHandler(dev.ellipog.tenet.client.QuestBookScreen.class,
                (screen, dragged, mouseX, mouseY) -> {
                    for (dev.emi.emi.api.stack.EmiStack emiStack : dragged.getEmiStacks()) {
                        ItemStack stack = emiStack.getItemStack();
                        if (!stack.isEmpty() && screen.acceptDrop(mouseX, mouseY, stack)) {
                            return true;
                        }
                    }
                    return false;
                });

        ItemStack categoryIcon = content.categoryIcon();
        dev.emi.emi.api.recipe.EmiRecipeCategory category = new dev.emi.emi.api.recipe.EmiRecipeCategory(
                content.categoryId(), dev.emi.emi.api.stack.EmiStack.of(categoryIcon));
        registry.addCategory(category);
        for (QuestPage page : content.pages()) {
            registry.addRecipe(new QuestEmiRecipe(category, page, categoryIcon));
        }
        registeredRevision = content.revision();
    }

    @Override
    public void tick() {
        QuestContent content = Integrations.content().orElse(null);
        if (content == null || !Viewers.mayInstall(Viewers.Viewer.EMI)) {
            return;
        }
        // The guards `EmiReloadManager.reload` needs: without a world and a recipe manager it aborts
        // and leaves its status mid-reload, which would take the viewer down with it. The recipe
        // manager lives on the client level in 1.21.1 -- `Minecraft` has no accessor of its own, read
        // from the class rather than guessed.
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null || minecraft.level.getRecipeManager() == null) {
            return;
        }
        if (!dev.emi.emi.runtime.EmiReloadManager.isLoaded()) {
            return;
        }
        // The decision itself is the watch's, and game-free, so the rule can be asserted at a settle of
        // two rather than waited out at twelve. What stays here is what needs this client: the guards
        // above, and the call into EMI. The watch records the request before returning true — a reload
        // that fails must not be retried every tick forever, and the next tree change is the honest
        // retry point.
        if (!WATCH.due(registeredRevision, content.revision())) {
            return;
        }
        dev.emi.emi.runtime.EmiReloadManager.reload();
    }

    // ------------------------------------------------------------------
    // The book's press
    // ------------------------------------------------------------------

    /**
     * {@inheritDoc}
     *
     * <p>The book's press, answered with EMI's own lookup: {@code displayRecipes} is the output side
     * ("how is this made"), and it takes a tag ingredient directly — EMI has a tag branch, so a tag
     * task opens the tag's own page rather than one arbitrary member of it.
     *
     * <p>Guarded like {@link #tick}: before EMI's first reload there is no recipe manager, and both
     * display calls dereference it.
     */
    @Override
    public void recipesFor(RecipeLookups.Target target) {
        if (!dev.emi.emi.runtime.EmiReloadManager.isLoaded()
                || dev.emi.emi.api.EmiApi.getRecipeManager() == null) {
            return;
        }
        if (target.isTag()) {
            dev.emi.emi.api.EmiApi.displayRecipes(
                    dev.emi.emi.api.stack.EmiIngredient.of(target.tag()));
        }
        else {
            dev.emi.emi.api.EmiApi.displayRecipes(dev.emi.emi.api.stack.EmiStack.of(target.item()));
        }
    }

    /** EMI's {@code displayRecipes} has a tag branch; see {@link #recipesFor}. */
    @Override
    public boolean supportsTags() {
        return true;
    }

    // ------------------------------------------------------------------
    // The page
    // ------------------------------------------------------------------

    /**
     * One quest as an EMI recipe.
     *
     * <p>Inputs and outputs are the real item references — a tag task is a tag ingredient, a text
     * task is nothing — because EMI indexes these to answer item lookups. Drawing the type icon of a
     * checkmark as an input would make a chest findable from a quest that never mentions one.
     */
    private static final class QuestEmiRecipe implements dev.emi.emi.api.recipe.EmiRecipe {

        private final dev.emi.emi.api.recipe.EmiRecipeCategory category;
        private final QuestPage page;
        private final QuestPageLayout layout = new QuestPageLayout(WIDTH);
        private final List<dev.emi.emi.api.stack.EmiIngredient> inputs;
        private final List<dev.emi.emi.api.stack.EmiStack> outputs;

        /**
         * The recipe's id, built once: the page and the category never change under it, and
         * {@link #getId} is asked per frame by the pin check — an assembly per frame for an
         * answer that cannot move.
         */
        private final ResourceLocation id;

        QuestEmiRecipe(dev.emi.emi.api.recipe.EmiRecipeCategory category, QuestPage page,
                       ItemStack fallbackIcon) {
            this.category = category;
            this.page = page;
            // Synthetic, with the leading slash EMI reserves for ids that are not datapack recipes:
            // the page is generated from the client cache and has no file behind it.
            this.id = ResourceLocation.fromNamespaceAndPath(category.getId().getNamespace(),
                    "/quest/" + page.quest().id());

            List<dev.emi.emi.api.stack.EmiIngredient> in = new ArrayList<>();
            for (QuestRow row : page.tasks()) {
                if (row.hasTag()) {
                    row.tag().ifPresent(tag -> in.add(dev.emi.emi.api.stack.EmiIngredient.of(
                            TagKey.create(Registries.ITEM, tag))));
                }
                else if (!row.icon().isEmpty()) {
                    in.add(dev.emi.emi.api.stack.EmiStack.of(row.icon()));
                }
            }
            // Never empty: EMI favourites a recipe by its first output, draws the sidebar entry from
            // it, and refuses an empty stack -- so an empty list is the difference between a quest
            // that can be pinned and one that cannot. PageArt.outputs supplies the quest's own icon
            // when no reward is an item.
            List<dev.emi.emi.api.stack.EmiStack> out = new ArrayList<>();
            for (ItemStack stack : PageArt.outputs(page, fallbackIcon)) {
                out.add(dev.emi.emi.api.stack.EmiStack.of(stack));
            }
            this.inputs = List.copyOf(in);
            this.outputs = List.copyOf(out);
        }

        @Override
        public dev.emi.emi.api.recipe.EmiRecipeCategory getCategory() {
            return category;
        }

        @Override
        public ResourceLocation getId() {
            return id;
        }

        @Override
        public List<dev.emi.emi.api.stack.EmiIngredient> getInputs() {
            return inputs;
        }

        @Override
        public List<dev.emi.emi.api.stack.EmiStack> getOutputs() {
            return outputs;
        }

        @Override
        public int getDisplayWidth() {
            return WIDTH;
        }

        @Override
        public int getDisplayHeight() {
            return layout.height(page);
        }

        /** Not a recipe tree: a quest has no bill of materials, and the tree button would be a lie. */
        @Override
        public boolean supportsRecipeTree() {
            return false;
        }

        /** And nothing is craftable: a reward is granted, so claiming craftability is a lie too. */
        @Override
        public boolean hideCraftable() {
            return true;
        }

        @Override
        public void addWidgets(dev.emi.emi.api.widget.WidgetHolder widgets) {
            QuestContent content = Integrations.content().orElse(null);
            if (content == null) {
                return;
            }
            widgets.add(new HeaderWidget(content, page, layout, this));
            if (!page.tasks().isEmpty()) {
                QuestPageLayout.Box heading = layout.tasksHeading(page);
                widgets.addText(content.tasksLabel(), heading.x() + QuestPageLayout.SLOT_X,
                        heading.y() + 1, PagePalette.MUTED, false);
            }
            for (int i = 0; i < page.tasks().size(); i++) {
                QuestPageLayout.Box box = layout.taskRow(i);
                widgets.add(new RowWidget(content, page.quest().id(), true,
                        page.tasks().get(i).sourceIndex(), layout, box));
                addSlot(widgets, page.tasks().get(i), layout, box);
            }
            if (!page.rewards().isEmpty()) {
                QuestPageLayout.Box heading = layout.rewardsHeading(page);
                widgets.addText(content.rewardsLabel(), heading.x() + QuestPageLayout.SLOT_X,
                        heading.y() + 1, PagePalette.MUTED, false);
            }
            for (int i = 0; i < page.rewards().size(); i++) {
                QuestPageLayout.Box box = layout.rewardRow(page, i);
                widgets.add(new RowWidget(content, page.quest().id(), false,
                        page.rewards().get(i).sourceIndex(), layout, box));
                // Display-only: a reward is something to collect, not an ingredient to look up,
                // so it gets a drawn icon rather than a slot widget — same rule as JEI (no slots)
                // and REI (unmarked slots). The quest line lives on the first icon's tooltip.
                widgets.add(new RewardIconWidget(page.rewards().get(i), layout, box,
                        i == 0 ? questLine(content, page) : null));
            }
        }

        /**
         * The line a quest's primary output carries: its title, its state, and the first task still
         * outstanding. ASCII only, deliberately — the font's coverage is measured, and a bullet or an
         * em dash outside it is a box rather than a character.
         */
        private static Component questLine(QuestContent content, QuestPage page) {
            StringBuilder line = new StringBuilder("[Quest] ")
                    .append(page.quest().title())
                    .append(" - ")
                    .append(content.stateText(page.quest().id()));
            for (QuestRow row : page.tasks()) {
                QuestRow live = content.liveTask(page.quest().id(), row.sourceIndex());
                if (!live.done() && live.need() > 0) {
                    line.append(" (").append(Math.min(live.have(), live.need()))
                            .append('/').append(live.need()).append(')');
                    break;
                }
            }
            return Component.literal(line.toString());
        }

        private static dev.emi.emi.api.widget.SlotWidget addSlot(
                dev.emi.emi.api.widget.WidgetHolder widgets, QuestRow row,
                QuestPageLayout layout, QuestPageLayout.Box box) {
            QuestPageLayout.Box icon = layout.icon(box);
            // EMI's own slot texture behind the widget, which is how EMI's crafting cards draw a
            // container slot; without it an item icon sits bare on the card.
            widgets.addTexture(dev.emi.emi.api.render.EmiTexture.SLOT, icon.x(), icon.y());
            if (row.hasTag()) {
                java.util.Optional<ResourceLocation> tag = row.tag();
                return tag.isPresent()
                        ? widgets.addSlot(dev.emi.emi.api.stack.EmiIngredient.of(
                                TagKey.create(Registries.ITEM, tag.get())), icon.x(), icon.y())
                        : null;
            }
            if (!row.icon().isEmpty()) {
                // EMI's own slot: clicking it shows that item's recipes, which is exactly "a task's
                // or reward's item opens EMI's recipe screen" — nothing custom, and nothing to keep
                // in step with EMI's behaviour.
                return widgets.addSlot(dev.emi.emi.api.stack.EmiStack.of(row.icon()),
                        icon.x(), icon.y());
            }
            return null;
        }
        /**
         * A reward's icon, drawn rather than slotted.
         *
         * <p>A reward is something to collect, not an ingredient to look up, so it gets no slot
         * widget: a slot is a recipe link in EMI, and these must have none — same rule as JEI
         * (no slots) and REI (unmarked slots). The icon draws through the game's own item
         * renderer at the slot box the layout reserves; a tag row has no single item and draws
         * nothing, its label carrying the tag. Clicks fall through dead: this widget answers
         * neither mouse nor keys, so neither opens recipes.
         */
        private static final class RewardIconWidget extends dev.emi.emi.api.widget.Widget {

            private final QuestRow row;
            private final QuestPageLayout.Box icon;
            private final Component questLine;

            RewardIconWidget(QuestRow row, QuestPageLayout layout,
                             QuestPageLayout.Box rowBox, Component questLine) {
                this.row = row;
                QuestPageLayout.Box slot = layout.icon(rowBox);
                this.icon = slot;
                this.questLine = questLine;
            }

            @Override
            public dev.emi.emi.api.widget.Bounds getBounds() {
                return new dev.emi.emi.api.widget.Bounds(icon.x(), icon.y(),
                        icon.width(), icon.height());
            }

            @Override
            public void render(GuiGraphics graphics, int mouseX, int mouseY, float delta) {
                if (!row.icon().isEmpty()) {
                    graphics.renderItem(row.icon(), icon.x() + 1, icon.y() + 1);
                }
            }

            @Override
            public List<ClientTooltipComponent> getTooltip(int mouseX, int mouseY) {
                List<ClientTooltipComponent> lines = new ArrayList<>();
                if (!row.icon().isEmpty()) {
                    lines.add(ClientTooltipComponent.create(
                            row.icon().getHoverName().getVisualOrderText()));
                }
                if (questLine != null) {
                    lines.add(ClientTooltipComponent.create(questLine.getVisualOrderText()));
                }
                return lines;
            }

            @Override
            public boolean mouseClicked(int mouseX, int mouseY, int button) {
                return false;
            }
        }
    }

    /** The clickable title strip: the one region that opens the book. */
    private static final class HeaderWidget extends dev.emi.emi.api.widget.Widget {

        private final QuestContent content;
        private final QuestPage page;
        private final QuestPageLayout layout;
        private final QuestEmiRecipe recipe;

        HeaderWidget(QuestContent content, QuestPage page, QuestPageLayout layout,
                     QuestEmiRecipe recipe) {
            this.content = content;
            this.page = page;
            this.layout = layout;
            this.recipe = recipe;
        }

        @Override
        public dev.emi.emi.api.widget.Bounds getBounds() {
            QuestPageLayout.Box box = layout.header();
            return new dev.emi.emi.api.widget.Bounds(box.x(), box.y(), box.width(), box.height());
        }

        @Override
        public void render(GuiGraphics graphics, int mouseX, int mouseY, float delta) {
            GuiRenderer renderer = new GuiGraphicsRenderer(graphics);
            QuestPageLayout.Box box = layout.header();
            QuestPageLayout.Box pin = layout.pin();
            if (box.contains(mouseX, mouseY)) {
                // The whole strip is the click target, and the wash is what says so -- except over
                // the pin, which has its own.
                if (!pin.contains(mouseX, mouseY)) {
                    renderer.fill(box.x(), box.y(), box.right(), box.bottom(), PagePalette.HOVER);
                }
            }
            QuestPageLayout.Box iconBox = layout.headerIcon();
            ItemStack icon = page.quest().icon();
            net.minecraft.resources.ResourceLocation headerTexture =
                    page.quest().textureIcon().isEmpty() ? null
                            : net.minecraft.resources.ResourceLocation.tryParse(page.quest().textureIcon());
            net.minecraft.resources.ResourceLocation headerSprite =
                    page.quest().spriteIcon().isEmpty() ? null
                            : net.minecraft.resources.ResourceLocation.tryParse(page.quest().spriteIcon());
            if (!icon.isEmpty()) {
                renderer.icon(icon, iconBox.x(), iconBox.y(), iconBox.width());
            }
            else if (headerTexture != null) {
                // A picture, not a stack: drawn through the blit in the header's own box, like the
                // book's card draws it.
                renderer.texture(headerTexture, iconBox.x(), iconBox.y(), iconBox.width(),
                        iconBox.width());
            }
            else if (headerSprite != null) {
                // An atlas region, drawn from the atlas in the same box.
                renderer.sprite(headerSprite, iconBox.x(), iconBox.y(), iconBox.width(),
                        iconBox.width(), 0xFFFFFFFF);
            }
            else if (!page.quest().iconId().isEmpty()) {
                // A pack can name an item this build does not have; the header keeps the id rather
                // than silently losing its picture. The book draws the same fallback.
                renderer.text(PageArt.fit(renderer, page.quest().iconId(), iconBox.width()),
                        iconBox.x(), iconBox.y(), PagePalette.MUTED);
            }
            QuestPageLayout.Box title = layout.headerTitle();
            renderer.text(PageArt.fit(renderer, page.quest().title(), title.width()),
                    title.x(), title.y(), PagePalette.TEXT);
            QuestPageLayout.Box badge = layout.headerBadge();
            PageArt.pill(renderer,
                    PageArt.fit(renderer, content.stateText(page.quest().id()), badge.width()),
                    badge.x(), badge.y(), content.stateColour(page.quest().id()));

            if (pin.contains(mouseX, mouseY)) {
                renderer.fill(pin.x() - 1, pin.y() - 1, pin.right() + 1, pin.bottom() + 1,
                        PagePalette.HOVER);
            }
            PageArt.star(renderer, pin.x(), pin.y(), isPinned());
        }

        @Override
        public List<ClientTooltipComponent> getTooltip(int mouseX, int mouseY) {
            Component line = layout.pin().contains(mouseX, mouseY)
                    ? Component.translatable(isPinned()
                            ? "tenet.viewer.unpin" : "tenet.viewer.pin")
                    : Component.translatable("tenet.viewer.open");
            return List.of(ClientTooltipComponent.create(line.getVisualOrderText()));
        }

        @Override
        public boolean mouseClicked(int mouseX, int mouseY, int button) {
            if (button != 0) {
                return false;
            }
            if (layout.pin().contains(mouseX, mouseY)) {
                togglePin();
                return true;
            }
            content.openQuest(page.quest().id());
            return true;
        }

        /**
         * Whether this quest is in EMI's sidebar.
         *
         * <p>EMI 1.1.24 has no public favourites API — {@code EmiFavorites} is internal, like the
         * reload entry point — so the state is read from its public list and matched by this recipe's
         * id, the same id EMI persists the favourite under. Pinned to the EMI version like every
         * other call into that package, and one file wide.
         */
        private boolean isPinned() {
            // The id is built once here rather than once per favourite: `getId` assembles a
            // ResourceLocation, so the loop as it was written allocated one per pin to compare against
            // — every frame, because this is drawn. One allocation per favourite remains, and it comes
            // from the *other* recipe's id, which is not ours to hoist.
            //
            // Still a walk, and deliberately not a cached answer keyed on the recipe: the list is a
            // player's own pins — tens at most, usually empty — so there is nothing here to cache, and
            // a cache would need invalidating against EMI's favourites, which is one more thing to keep
            // in step with a version this file already pins for other reasons.
            var id = recipe.getId();
            for (dev.emi.emi.runtime.EmiFavorite favourite : dev.emi.emi.runtime.EmiFavorites.favorites) {
                if (favourite.getRecipe() != null && id.equals(favourite.getRecipe().getId())) {
                    return true;
                }
            }
            return false;
        }

        /**
         * Pins or unpins the quest, then asks EMI to redraw its favourites panel.
         *
         * <p><b>The removal passes the {@code EmiFavorite} itself, and that is load-bearing.</b>
         * EMI's {@code removeFavorite} matches a recipe-bound favourite by identity against the
         * recipe it reads off the argument, and only an {@code EmiFavorite} carries one -- the inner
         * stack a caller might reasonably pass compares as recipe-less and matches nothing, silently.
         * That was this toggle's first version, and the star simply did nothing. It also does not
         * save, unlike {@code addFavorite}, so the removal is persisted here or a restart restores
         * the pin. EMI's own A-key path is add-only; the star is the removal.
         */
        private void togglePin() {
            List<dev.emi.emi.runtime.EmiFavorite> pinned = new ArrayList<>();
            for (dev.emi.emi.runtime.EmiFavorite favourite : dev.emi.emi.runtime.EmiFavorites.favorites) {
                if (favourite.getRecipe() != null
                        && recipe.getId().equals(favourite.getRecipe().getId())) {
                    pinned.add(favourite);
                }
            }
            if (!pinned.isEmpty()) {
                for (dev.emi.emi.runtime.EmiFavorite favourite : pinned) {
                    dev.emi.emi.runtime.EmiFavorites.removeFavorite(favourite);
                }
                dev.emi.emi.runtime.EmiPersistentData.save();
            }
            else if (!recipe.getOutputs().isEmpty()) {
                dev.emi.emi.runtime.EmiFavorites.addFavorite(recipe.getOutputs().get(0), recipe);
            }
            dev.emi.emi.screen.EmiScreenManager.repopulatePanels(
                    dev.emi.emi.config.SidebarType.FAVORITES);
        }
    }

    /**
     * One row's label, count and bar — live, from the content, every frame.
     *
     * <p>This is what makes progress move while the page is open: the recipe is built once per EMI
     * reload, but the numbers are read here on each draw. Row and slot are separate widgets because
     * EMI's slot owns its own clicks, and a row widget that swallowed them would break the item
     * lookup it exists for.
     */
    private static final class RowWidget extends dev.emi.emi.api.widget.Widget {

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

        @Override
        public dev.emi.emi.api.widget.Bounds getBounds() {
            return new dev.emi.emi.api.widget.Bounds(row.x(), row.y(), row.width(), row.height());
        }

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
            String count = live.need() > 0
                    ? Math.min(live.have(), live.need()) + " / " + live.need()
                    : "";
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

        /**
         * A reward's row: its label and a status pill, never a progress bar.
         *
         * <p>A reward drawn with progress reads as a task the player still owes — the same icon, the
         * same count on both sides — so the row says what it is: Ready, Locked or Claimed, and
         * nothing while the quest is unfinished.
         */
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
}
