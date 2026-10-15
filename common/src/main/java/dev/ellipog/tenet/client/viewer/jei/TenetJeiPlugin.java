package dev.ellipog.tenet.client.viewer.jei;

import dev.ellipog.tenet.Constants;
import dev.ellipog.armature.client.render.GuiGraphicsRenderer;
import dev.ellipog.armature.client.render.GuiRenderer;
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

import com.mojang.blaze3d.platform.InputConstants;

import mezz.jei.api.IModPlugin;
import mezz.jei.api.JeiPlugin;
import mezz.jei.api.constants.VanillaTypes;
import mezz.jei.api.gui.builder.ITooltipBuilder;
import mezz.jei.api.gui.builder.IRecipeLayoutBuilder;
import mezz.jei.api.gui.drawable.IDrawable;
import mezz.jei.api.gui.ingredient.IRecipeSlotsView;
import mezz.jei.api.gui.inputs.IJeiInputHandler;
import mezz.jei.api.gui.inputs.IJeiUserInput;
import mezz.jei.api.gui.widgets.IRecipeExtrasBuilder;
import mezz.jei.api.helpers.IGuiHelper;
import mezz.jei.api.ingredients.ITypedIngredient;
import mezz.jei.api.recipe.IFocusGroup;
import mezz.jei.api.recipe.RecipeIngredientRole;
import mezz.jei.api.recipe.RecipeType;
import mezz.jei.api.recipe.advanced.ISimpleRecipeManagerPlugin;
import mezz.jei.api.registration.IAdvancedRegistration;
import mezz.jei.api.registration.IRecipeCategoryRegistration;
import mezz.jei.api.runtime.IJeiRuntime;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;

import java.util.ArrayList;
import java.util.List;

/**
 * JEI: the same item-to-quest lookup, as plain rows.
 *
 * <h2>Why this is a fallback tier and not a second first-class integration</h2>
 *
 * <p>EMI's API renders arbitrary pages; JEI's renders recipes. A quest is not a recipe, so pretending
 * it is one — slots, arrows, a workstation — would be three times the adapter for a parity nobody
 * asked for. What a player without EMI is owed is the lookup: an item leads to the quests that use
 * or award it, as rows, with a click into the book. That is what this file is, and the row drawing
 * is deliberately thin.
 *
 * <h2>Dynamic by lookup, not by mutation</h2>
 *
 * <p>The recipes are produced by {@link ISimpleRecipeManagerPlugin} — JEI asks it what exists for an
 * item at the moment the player looks — so a quest tree that arrives after JEI started, or changes
 * on the server's own reload command, needs no bookkeeping at all: the next lookup reads the new
 * snapshot. The alternative ({@code IRecipeManager.addRecipes} at runtime) would need re-adding on
 * every JEI restart and could not remove what a reload deleted.
 *
 * <h2>One registers</h2>
 *
 * <p>Both entry points return before touching anything unless {@link Viewers#mayInstall} says this
 * client's one viewer is JEI, so a player running EMI and JEI sees quests once.
 */
@JeiPlugin
public final class TenetJeiPlugin implements IModPlugin, RecipeLookups.Lookup {

    private static final int WIDTH = 134;

    /**
     * The runtime JEI hands over when it is up, and the readiness gate for the book's press: the
     * lookup is asked at click time, and before this is set there is no GUI to open.
     *
     * <p>Static because JEI constructs its own plugin instance, the same reason the EMI adapter's
     * revision fields are.
     */
    private static volatile IJeiRuntime runtime;

    @Override
    public void onRuntimeAvailable(IJeiRuntime runtime) {
        TenetJeiPlugin.runtime = runtime;
    }

    @Override
    public void onRuntimeUnavailable() {
        runtime = null;
    }

    /** Cached so every call site hands JEI the same {@link RecipeType} instance, restart to restart. */
    private static RecipeType<QuestPage> recipeType(QuestContent content) {
        return RecipeType.create(content.categoryId().getNamespace(), content.categoryId().getPath(),
                QuestPage.class);
    }

    @Override
    public ResourceLocation getPluginUid() {
        return ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "recipe_viewer");
    }

    @Override
    public void registerCategories(IRecipeCategoryRegistration registration) {
        QuestContent content = eligible();
        if (content == null) {
            return;
        }
        registration.addRecipeCategories(new QuestCategory(recipeType(content), content,
                registration.getJeiHelpers().getGuiHelper()));
    }

    /**
     * Dragging a stack out of JEI and onto a table's entry list.
     *
     * <p>One target — the list — because that is what the screen says it wants: {@code dropArea} is null
     * when no table is open, and a viewer with no target draws no drop, which is the honest answer rather
     * than a drop the screen would refuse. JEI's ghost protocol reports the target a drag finished over
     * and not where inside it, so the entry lands at the end of the list; EMI's handler, which does have
     * the point, inserts at the row under the pointer.
     */
    @Override
    public void registerGuiHandlers(mezz.jei.api.registration.IGuiHandlerRegistration registration) {
        if (!Viewers.mayInstall(Viewers.Viewer.JEI)) {
            return;
        }
        registration.addGhostIngredientHandler(dev.ellipog.tenet.client.QuestBookScreen.class,
                new mezz.jei.api.gui.handlers.IGhostIngredientHandler<>() {

                    @Override
                    public <I> java.util.List<Target<I>> getTargetsTyped(
                            dev.ellipog.tenet.client.QuestBookScreen screen,
                            mezz.jei.api.ingredients.ITypedIngredient<I> ingredient, boolean doStart) {
                        net.minecraft.client.renderer.Rect2i area = screen.dropArea();
                        if (area == null || ingredient.getType() != VanillaTypes.ITEM_STACK) {
                            return java.util.List.of();
                        }
                        return java.util.List.of(new Target<>() {
                            @Override
                            public net.minecraft.client.renderer.Rect2i getArea() {
                                return area;
                            }

                            @Override
                            public void accept(I value) {
                                if (value instanceof ItemStack stack) {
                                    screen.acceptDrop(stack);
                                }
                            }
                        });
                    }

                    @Override
                    public void onComplete() {
                        // Nothing to undo: the drop was an edit, and the server's answer is the record.
                    }
                });
    }

    @Override
    public void registerAdvanced(IAdvancedRegistration registration) {
        QuestContent content = eligible();
        if (content == null) {
            return;
        }
        registration.addTypedRecipeManagerPlugin(recipeType(content), new QuestLookup(content));
    }

    /**
     * The content, only when this client's one viewer is JEI and a consumer has installed quests.
     *
     * <p>No category and no lookup plugin without it — a JEI that shows an empty "Quests" category
     * on a client with no quests would be a bug report about nothing. The recipe lookup is installed
     * here for the same reason: {@code mayInstall} is the one gate that decides which viewer owns
     * this client, so it is the one place that installs.
     */
    private QuestContent eligible() {
        if (!Viewers.mayInstall(Viewers.Viewer.JEI)) {
            return null;
        }
        RecipeLookups.install(this);
        return Integrations.content().orElse(null);
    }

    /**
     * {@inheritDoc}
     *
     * <p>An OUTPUT focus is JEI's "recipes that produce this", which is the question a task or
     * reward row asks. A tag has no focus here: the pinned JEI API has no public tag path at all, so
     * {@link RecipeLookups.Lookup#supportsTags()} stays false and the book keeps both the hint and
     * the press off tag rows under this viewer.
     */
    @Override
    public void recipesFor(RecipeLookups.Target target) {
        IJeiRuntime current = runtime;
        if (current == null || target.isTag()) {
            return;
        }
        current.getRecipesGui().show(current.getJeiHelpers().getFocusFactory()
                .createFocus(RecipeIngredientRole.OUTPUT, VanillaTypes.ITEM_STACK, target.item()));
    }

    // ------------------------------------------------------------------
    // The category
    // ------------------------------------------------------------------

    /**
     * A quest as a JEI page: item slots on the real references, text and bars drawn live.
     *
     * <p>The slots are JEI's own, so clicking a task's item shows that item's recipes — the same
     * behaviour EMI's slots give — and the header strip is an input handler that opens the book.
     * Everything else is drawn in {@link #draw} from {@link QuestContent}'s live reads, because JEI
     * builds a category once and a page's numbers move while it is open.
     */
    private static final class QuestCategory implements mezz.jei.api.recipe.category.IRecipeCategory<QuestPage> {

        private final RecipeType<QuestPage> type;
        private final QuestContent content;
        private final IDrawable icon;
        private final QuestPageLayout layout = new QuestPageLayout(WIDTH);

        QuestCategory(RecipeType<QuestPage> type, QuestContent content, IGuiHelper guiHelper) {
            this.type = type;
            this.content = content;
            this.icon = guiHelper.createDrawableItemStack(content.categoryIcon());
        }

        @Override
        public RecipeType<QuestPage> getRecipeType() {
            return type;
        }

        @Override
        public Component getTitle() {
            return content.categoryTitle();
        }

        @Override
        public IDrawable getIcon() {
            return icon;
        }

        /**
         * The tallest page this category can currently be asked to draw.
         *
         * <p>JEI takes the page size from the category, not the recipe, so anything less than the
         * tallest page would clip the last rows of a long quest.
         *
         * <h2>Measured once per snapshot rather than once per call</h2>
         *
         * <p>It was recomputed on every call, on the argument that one scan of the pages is cheap — and
         * the scan is, but "every call" is JEI's business rather than ours: this is one of the hooks a
         * recipe layout consults, so a page being built can ask for it more than once. The revision is
         * the honest cache key, because the snapshot cannot change without moving it — see
         * {@code QuestViewerContent.tick} — so the memo cannot be a tree-change stale.
         */
        @Override
        public int getHeight() {
            long revision = content.revision();
            if (revision != measuredRevision) {
                int tallest = QuestPageLayout.HEADER_HEIGHT;
                for (QuestPage page : content.pages()) {
                    tallest = Math.max(tallest, layout.height(page));
                }
                measuredHeight = tallest;
                measuredRevision = revision;
            }
            return measuredHeight;
        }

        /** The height as it was measured, and the snapshot it was measured from. See {@link #getHeight}. */
        private int measuredHeight = QuestPageLayout.HEADER_HEIGHT;
        private long measuredRevision = Long.MIN_VALUE;

        @Override
        public int getWidth() {
            return WIDTH;
        }

        @Override
        public void setRecipe(IRecipeLayoutBuilder builder, QuestPage page, IFocusGroup focuses) {
            // Tasks are JEI inputs: pressing R or U on one, or clicking it, looks its item up,
            // which is what an input slot is for. Rewards are display-only: a reward is something
            // to collect, not an ingredient to look up, so they get no slots at all — a slot is
            // a recipe link, and these must have none. Their icons are drawn by hand in draw(),
            // from the same boxes the slots would have taken.
            for (int i = 0; i < page.tasks().size(); i++) {
                QuestPageLayout.Box icon = layout.icon(layout.taskRow(i));
                addIngredient(builder.addInputSlot(icon.x(), icon.y()), page.tasks().get(i));
            }
        }

        private static void addIngredient(mezz.jei.api.gui.builder.IRecipeSlotBuilder slot, QuestRow row) {
            if (row.hasTag()) {
                row.tag().ifPresent(tag -> slot.addIngredients(Ingredient.of(TagKey.create(Registries.ITEM, tag))));
            }
            else if (!row.icon().isEmpty()) {
                slot.addItemStack(row.icon());
            }
        }

        @Override
        public void createRecipeExtras(IRecipeExtrasBuilder builder, QuestPage page, IFocusGroup focuses) {
            QuestPageLayout.Box header = layout.header();
            builder.addInputHandler(new OpenBookHandler(content, page,
                    new ScreenRectangle(header.x(), header.y(), header.width(), header.height())));
        }

        @Override
        public void draw(QuestPage page, IRecipeSlotsView slots, GuiGraphics graphics,
                         double mouseX, double mouseY) {
            GuiRenderer renderer = new GuiGraphicsRenderer(graphics);

            QuestPageLayout.Box header = layout.header();
            if (header.contains((int) mouseX, (int) mouseY)) {
                // The whole strip is the click target, and the wash is what says so.
                renderer.fill(header.x(), header.y(), header.right(), header.bottom(), PagePalette.HOVER);
            }
            QuestPageLayout.Box title = layout.headerTitle();
            renderer.text(PageArt.fit(renderer, page.quest().title(), title.width()),
                    title.x(), title.y(), PagePalette.TEXT);
            QuestPageLayout.Box badge = layout.headerBadge();
            PageArt.pill(renderer,
                    PageArt.fit(renderer, content.stateText(page.quest().id()), badge.width()),
                    badge.x(), badge.y(), content.stateColour(page.quest().id()));
            drawQuestIcon(renderer, page);

            if (!page.tasks().isEmpty()) {
                QuestPageLayout.Box heading = layout.tasksHeading(page);
                renderer.text(content.tasksLabel().getString(), heading.x() + QuestPageLayout.SLOT_X,
                        heading.y() + 1, PagePalette.MUTED);
            }
            for (int i = 0; i < page.tasks().size(); i++) {
                drawRow(renderer, layout.taskRow(i),
                        content.liveTask(page.quest().id(), page.tasks().get(i).sourceIndex()), true);
            }
            if (!page.rewards().isEmpty()) {
                QuestPageLayout.Box heading = layout.rewardsHeading(page);
                renderer.text(content.rewardsLabel().getString(), heading.x() + QuestPageLayout.SLOT_X,
                        heading.y() + 1, PagePalette.MUTED);
            }
            for (int i = 0; i < page.rewards().size(); i++) {
                drawRow(renderer, layout.rewardRow(page, i),
                        content.liveReward(page.quest().id(), page.rewards().get(i).sourceIndex()),
                        false);
                drawRewardIcon(renderer, layout.rewardRow(page, i), page.rewards().get(i));
            }
        }

        /**
         * The quest's own icon, top-left in the header.
         *
         * <p>The layout has always reserved the box ({@link QuestPageLayout#headerIcon} — "the quest's
         * icon and title on one line"), but no adapter drew into it: the title started at the text
         * column and the icon's box sat empty. A page without its quest's face is anonymous in a
         * list of pages, so this draws what the layout always meant to hold.
         */
        private void drawQuestIcon(GuiRenderer renderer, QuestPage page) {
            QuestPageLayout.Box box = layout.headerIcon();
            QuestRef quest = page.quest();
            if (!quest.icon().isEmpty()) {
                renderer.icon(quest.icon(), box.x(), box.y(), QuestPageLayout.ITEM);
                return;
            }
            drawPictureIcon(renderer, box, quest.textureIcon(), quest.spriteIcon());
        }

        /** A picture icon from an author's file or atlas region, or nothing when there is none. */
        private void drawPictureIcon(GuiRenderer renderer, QuestPageLayout.Box box,
                                     String texture, String sprite) {
            if (!texture.isEmpty()) {
                net.minecraft.resources.ResourceLocation id =
                        net.minecraft.resources.ResourceLocation.tryParse(texture);
                if (id != null) {
                    renderer.texture(id, box.x(), box.y(), box.width(), box.height());
                    return;
                }
            }
            if (!sprite.isEmpty()) {
                net.minecraft.resources.ResourceLocation id =
                        net.minecraft.resources.ResourceLocation.tryParse(sprite);
                if (id != null) {
                    renderer.sprite(id, box.x(), box.y(), box.width(), box.height(), 0xFFFFFFFF);
                }
            }
        }

        /**
         * A reward row's icon, drawn by hand because the row has no slot anymore.
         *
         * <p>Tags have no single item, so there is nothing to draw for them — the label carries
         * the tag, exactly as it does where the slot version drew nothing either (see REI's
         * shared skip, and the empty-icon arm below).
         */
        private void drawRewardIcon(GuiRenderer renderer, QuestPageLayout.Box row, QuestRow quest) {
            if (quest.hasTag() || quest.icon().isEmpty()) {
                return;
            }
            QuestPageLayout.Box item = layout.item(row);
            renderer.icon(quest.icon(), item.x(), item.y(), QuestPageLayout.ITEM);
        }

        private void drawRow(GuiRenderer renderer, QuestPageLayout.Box row, QuestRow live,
                             boolean task) {
            QuestPageLayout.Box text = layout.text(row);
            int colour = live.locked() ? PagePalette.LOCKED
                    : live.done() ? (task ? PagePalette.COMPLETE : PagePalette.MUTED)
                    : PagePalette.TEXT;
            if (!task) {
                drawRewardStatus(renderer, row, text, live, colour);
                return;
            }
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
        private void drawRewardStatus(GuiRenderer renderer, QuestPageLayout.Box row,
                                      QuestPageLayout.Box text, QuestRow live, int colour) {
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

        @Override
        public void getTooltip(ITooltipBuilder tooltip, QuestPage page, IRecipeSlotsView slots,
                               double mouseX, double mouseY) {
            if (layout.header().contains((int) mouseX, (int) mouseY)) {
                tooltip.add(Component.translatable("tenet.viewer.open"));
            }
            // Reward icons have no slots anymore (they must not open recipes), so they have no
            // slot tooltip either: the full label, for rows the column cut short. Task icons keep
            // their slots' tooltips, which is the other half of why only rewards are handled here.
            for (int i = 0; i < page.rewards().size(); i++) {
                QuestPageLayout.Box icon = layout.item(layout.rewardRow(page, i));
                if (icon.x() <= mouseX && mouseX < icon.x() + icon.width()
                        && icon.y() <= mouseY && mouseY < icon.y() + icon.height()) {
                    String label = page.rewards().get(i).label();
                    if (!label.isEmpty()) {
                        tooltip.add(Component.literal(label));
                    }
                }
            }
        }

        @Override
        public ResourceLocation getRegistryName(QuestPage page) {
            // Synthetic, so JEI has a stable identity for the page; nothing behind it on disk, which
            // is why bookmarking is out of scope and said so in TESTING.md.
            return ResourceLocation.fromNamespaceAndPath(type.getUid().getNamespace(),
                    "/quest/" + page.quest().id());
        }
    }

    /** The one clickable region: the title strip opens the book on this quest. */
    private record OpenBookHandler(QuestContent content, QuestPage page, ScreenRectangle area)
            implements IJeiInputHandler {

        @Override
        public ScreenRectangle getArea() {
            return area;
        }

        @Override
        public boolean handleInput(double mouseX, double mouseY, IJeiUserInput input) {
            if (input.isSimulate()) {
                // Mouse-down: claim the press so JEI lets the release through to us.
                return true;
            }
            if (!input.getKey().equals(InputConstants.Type.MOUSE.getOrCreate(0))) {
                return false;
            }
            content.openQuest(page.quest().id());
            return true;
        }
    }

    // ------------------------------------------------------------------
    // The lookup
    // ------------------------------------------------------------------

    /**
     * "Which quests want this item, and which give it" — answered per lookup from the live index.
     *
     * <p>An item in a task's slot appears under JEI's uses, an item in a reward's slot under its
     * recipes, with no registration bookkeeping: this is asked at the moment the player presses the
     * key, which is exactly the property that makes a reload invisible.
     */
    private record QuestLookup(QuestContent content) implements ISimpleRecipeManagerPlugin<QuestPage> {

        private static ResourceLocation itemOf(ITypedIngredient<?> ingredient) {
            return ingredient.getItemStack()
                    .map(stack -> BuiltInRegistries.ITEM.getKey(stack.getItem()))
                    .orElse(null);
        }

        @Override
        public boolean isHandledInput(ITypedIngredient<?> ingredient) {
            ResourceLocation item = itemOf(ingredient);
            // Membership rather than "is the collected list empty": JEI asks this per ingredient as it
            // decides what to show, and the old form built a list, a set and a copy to answer it.
            return item != null && content.index().uses(item);
        }

        @Override
        public boolean isHandledOutput(ITypedIngredient<?> ingredient) {
            ResourceLocation item = itemOf(ingredient);
            return item != null && content.index().awards(item);
        }

        @Override
        public List<QuestPage> getRecipesForInput(ITypedIngredient<?> ingredient) {
            ResourceLocation item = itemOf(ingredient);
            return item == null ? List.of() : pagesFor(content.index().questsUsing(item));
        }

        @Override
        public List<QuestPage> getRecipesForOutput(ITypedIngredient<?> ingredient) {
            ResourceLocation item = itemOf(ingredient);
            return item == null ? List.of() : pagesFor(content.index().questsAwarding(item));
        }

        @Override
        public List<QuestPage> getAllRecipes() {
            return content.pages();
        }

        private List<QuestPage> pagesFor(List<QuestRef> refs) {
            // One lookup per ref rather than one walk of every page per ref: the answer is in a map the
            // content publishes, and this used to be the nesting that made a common item's lookup
            // quadratic. See QuestContent.page.
            List<QuestPage> out = new ArrayList<>(refs.size());
            for (QuestRef ref : refs) {
                QuestPage page = content.page(ref.id());
                if (page != null) {
                    out.add(page);
                }
            }
            return out;
        }
    }
}
