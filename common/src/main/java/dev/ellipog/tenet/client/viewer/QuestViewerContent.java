package dev.ellipog.tenet.client.viewer;

import dev.ellipog.tenet.client.viewer.ItemQuestIndex;
import dev.ellipog.tenet.client.viewer.PagePalette;
import dev.ellipog.tenet.client.viewer.QuestContent;
import dev.ellipog.tenet.client.viewer.QuestPage;
import dev.ellipog.tenet.client.viewer.QuestRef;
import dev.ellipog.tenet.client.viewer.QuestRow;
import dev.ellipog.tenet.Tenet;
import dev.ellipog.tenet.QuestBook;
import dev.ellipog.tenet.client.ClientQuestCache;
import dev.ellipog.tenet.client.QuestBookScreen;
import dev.ellipog.tenet.progress.QuestState;

import net.minecraft.client.Minecraft;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderSet;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;

/**
 * Tenet's half of the recipe-viewer seam: snapshots built from the synced client cache, and live
 * reads of the same cache while a page is open.
 *
 * <h2>Where the data comes from, and why nothing new is sent</h2>
 *
 * <p>The quest tree and the progress both already reach this client on join, so a viewer is one more
 * reader of {@link ClientQuestCache} rather than a second sync. The snapshot — every quest's page,
 * and the item-to-quest index — is rebuilt on the client thread in {@link #tick()} when the tree
 * revision moves, and nothing else here touches the network.
 *
 * <h2>The two halves, and the threading rule that separates them</h2>
 *
 * <p>{@link #index()} and {@link #pages()} are immutable snapshots, safe to read from any thread —
 * EMI registers its recipes on a worker, and that is the whole reason they exist. The
 * {@code live*} methods read the cache as it is now and are client-thread only; a viewer calls them
 * while it draws.
 *
 * <h2>What a viewer page shows, and what only the book shows</h2>
 *
 * <p>A page carries the rows that reference an item — item tasks, tag tasks, item rewards — because
 * those are what the viewer is for: a lookup answers "what wants this, and what gives it". A text
 * task (xp, checkmark, advancement, stat, …) or a non-item reward has no item to look up and is left
 * to the book, and the sections are labelled so a reward is never read as something to hand in. A
 * quest with no item rows at all has no page: nothing could ever lead a player to it, and a blank
 * page is worse than none.
 *
 * <p>Only an item id and a tag id are structured on the wire, so those are what the index holds; a
 * fluid, a dimension, a stat is a sentence with no item in it. A quest icon is not a lookup either —
 * a quest whose icon happens to be a diamond is not a quest about diamonds. Reward tables and choice
 * rewards are out for a different reason: what they will hand out is not known until the roll.
 */
public final class QuestViewerContent implements QuestContent {

    /** The viewer category's id. The words are Tenet's, so the namespace is too. */
    public static final ResourceLocation CATEGORY_ID =
            ResourceLocation.fromNamespaceAndPath(Tenet.MOD_ID, "quests");

    private volatile ItemQuestIndex index = ItemQuestIndex.empty();
    private volatile List<QuestPage> pages = List.of();
    private volatile java.util.Map<String, QuestPage> byId = java.util.Map.of();
    private volatile long builtRevision = -1L;

    /**
     * The language revision the current pages were built in.
     *
     * <h2>The second input, and why the pages have to know about it</h2>
     *
     * <p>A page carries a quest's title and its chapter's, and those are the words a player reads — so
     * a locale arriving changes what every page <i>says</i> while the questline itself does not move.
     * Rebuilding on the tree revision alone would leave the recipe viewers showing the previous
     * language's titles for the rest of the session.
     *
     * <p>Tracked separately from {@link #builtRevision} rather than folded into it, because a tag-only
     * rebuild must leave the adapters' signal exactly where it was: tags decide which items point at a
     * quest, not what a page says. See {@link #revision()}.
     */
    private volatile long builtText = -1L;

    /**
     * What {@link #revision()} answers: a counter that moves whenever the pages' <b>words</b> could have.
     *
     * <p>The adapters compare this to decide whether to re-register their entries, so it has to move for
     * everything that changes a page and for nothing that does not. It is a counter of its own rather
     * than the tree revision because the two are no longer the same question — and because the adapters
     * measure text: a translated page is a different height, which is why JEI recomputes its layout from
     * this signal rather than from the tree.
     */
    private volatile long token;

    /**
     * The tags the last rebuild expanded, and the holder set it read each one from.
     *
     * <h2>The input the tree revision does not carry</h2>
     *
     * <p>A snapshot is rebuilt when the tree revision moves, and that is right for everything the tree
     * carries — but this snapshot is also a function of the client's <b>item tags</b>, which are synced from a
     * datapack rather than from this mod's files. A {@code /reload} that changes tag membership, or a mod
     * added to an existing world, moves no quest revision at all: so a quest gated on {@code #c:ingots/iron}
     * went on being findable from a member the pack had removed, and stayed unfindable from one it had added,
     * until the player relogged. Nothing logged and nothing looked wrong.
     *
     * <p><b>The watched value is the holder set object itself</b>, compared by identity — the mechanism kept
     * as plain as it can be. Binding tags builds a fresh holder set per tag, so the object behind a tag id is
     * a different one after a reload while the id is the same; comparing the object is the whole question.
     *
     * <p><b>Three earlier versions encoded the identity instead, and each was wrong in a different way</b> —
     * worth recording, because the first two look obviously right:
     *
     * <ol>
     *   <li>an <b>identity hash</b> repeats once the object is collected, and these sets are exactly the
     *       short-lived kind that gets collected — so a replaced set hashed the same as the one it replaced;</li>
     *   <li>an <b>equals-keyed weak map</b> compares with {@code equals}, and a holder set is a record, so two
     *       sets holding the same members are equal and shared a serial;</li>
     *   <li>a <b>weak map with an identity-comparing key</b> cannot work at all: {@code WeakHashMap} looks a
     *       key up by calling {@code equals} on the key it is <i>given</i> against the key it <i>holds</i>, so
     *       a key whose referent is gone matches nothing — including its own entry.</li>
     * </ol>
     *
     * <p>Storing the object sidesteps all three. Strong references are affordable: one entry per tag the pack
     * actually names, which is a handful, and those sets are already held by the item registry for as long as
     * the client lives.
     *
     * <p><b>Why not a reload listener.</b> The obvious answer is to register one and bump a counter, and this
     * codebase has none anywhere: it would mean a loader-specific hook in two loader modules for a question
     * that can be asked locally. This asks it locally — one lookup per expanded tag per tick — and it is
     * testable without a running game, which a listener is not.
     */
    private volatile java.util.Map<TagKey<Item>, HolderSet<Item>> watchedTags = java.util.Map.of();

    /**
     * How a tag's holder set is looked up. Production reads the client's item registry.
     *
     * <p>A field rather than a direct call so a test can hand back a different object: a test JVM loads no
     * datapack, so {@code BuiltInRegistries.ITEM.getTag} answers empty there and the one thing this mechanism
     * exists for — a tag whose holder set has been replaced — cannot be reached. It is the same shape
     * {@code rebuild}'s tag resolver takes, and for the same reason.
     */
    private java.util.function.Function<TagKey<Item>, Optional<HolderSet<Item>>> tagHolders =
            tag -> BuiltInRegistries.ITEM.getTag(tag).map(set -> (HolderSet<Item>) set);

    /** Points the tag lookup somewhere else. For a test; see {@link #tagHolders}. */
    void setTagHolders(java.util.function.Function<TagKey<Item>, Optional<HolderSet<Item>>> holders) {
        this.tagHolders = holders;
    }

    /** The tags the last rebuild expanded, and the set it read each from. For a test. */
    java.util.Map<TagKey<Item>, HolderSet<Item>> watchedTags() {
        return watchedTags;
    }

    /**
     * Whether any watched tag now reads from a different holder set than the snapshot was built against.
     *
     * <p>Package-private rather than private so a test can ask the question directly. It was private, and
     * that is what made a failing test hard to read: the test could only see the <b>revision</b> it produced,
     * so "the stamp is wrong" and "this method was never consulted" looked identical from outside — which is
     * four attempts of guessing. Exposed, the test can say which.
     *
     * <p>Cheap: one lookup per tag the pack actually names, and an identity comparison each. A tag that has
     * since disappeared is a change too — it is no longer there at all — so a reload that removes a tag
     * rebuilds rather than leaving a snapshot serving members that are gone.
     */
    boolean tagsMoved() {
        for (java.util.Map.Entry<TagKey<Item>, HolderSet<Item>> watched : watchedTags.entrySet()) {
            Optional<HolderSet<Item>> now = tagHolders.apply(watched.getKey());
            if (now.isEmpty() || now.get() != watched.getValue()) {
                return true;
            }
        }
        return false;
    }

    @Override
    public void tick() {
        long revision = ClientQuestCache.treeRevision();
        long text = ClientQuestCache.textRevision();
        if (revision == builtRevision && text == builtText && !tagsMoved()) {
            return;
        }
        // Whether the pages' words could have changed, as opposed to only the item index behind them.
        // A tag-only rebuild leaves the adapters' signal alone: their entries are built from `pages()`,
        // which a tag does not touch.
        boolean words = revision != builtRevision || text != builtText;
        // The tags the walk expands, collected as it goes, so the watch list is exactly what the snapshot
        // was built from rather than a second walk deciding what it might have read.
        java.util.Map<TagKey<Item>, HolderSet<Item>> seen = new java.util.HashMap<>();
        rebuild(revision, tag -> {
            Optional<HolderSet<Item>> holders = tagHolders.apply(tag);
            holders.ifPresent(set -> seen.put(tag, set));
            return itemsOf(holders);
        });
        watchedTags = java.util.Map.copyOf(seen);
        builtText = text;
        if (words) {
            token++;
        }
    }

    /**
     * Which snapshot the adapters last registered: a counter that moves with the pages' words.
     *
     * <p>Read by each viewer to decide whether its entries are stale, and by JEI to decide whether to
     * re-measure a page. It moves when the tree arrives and when the language does — see
     * {@link #builtText} — and not when only the item tags do.
     */
    @Override
    public long revision() {
        return token;
    }

    @Override
    public ItemQuestIndex index() {
        return index;
    }

    @Override
    public List<QuestPage> pages() {
        return pages;
    }

    @Override
    public QuestPage page(String questId) {
        return questId == null ? null : byId.get(questId);
    }

    @Override
    public QuestRow liveTask(String questId, int index) {
        ClientQuestCache.Entry entry = ClientQuestCache.entry(questId);
        if (entry == null || index < 0 || index >= entry.tasks().size()) {
            return blankRow();
        }
        ClientQuestCache.TaskEntry task = entry.tasks().get(index);
        int have = ClientQuestCache.taskProgressOf(questId, index);
        boolean locked = !ClientQuestCache.taskLockOf(questId, index).isEmpty();
        // `taskDone` rather than `have >= task.count()` written here: the claim menu's progression
        // column asks the same question about the same task, and one rule with two spellings is how a
        // page and a column come to disagree about whether a task is finished.
        return new QuestRow(taskIcon(task), taskLabel(task), have, task.count(),
                ClientQuestCache.taskDone(questId, index), locked, task.tagId(), index);
    }

    @Override
    public QuestRow liveReward(String questId, int index) {
        ClientQuestCache.Entry entry = ClientQuestCache.entry(questId);
        if (entry == null || index < 0 || index >= entry.rewards().size()) {
            return blankRow();
        }
        ClientQuestCache.RewardEntry reward = entry.rewards().get(index);
        // Null in a headless JVM (and a client with no player yet), where no claim can be known:
        // the row then reads unlocked-and-unclaimed rather than crashing a viewer.
        Minecraft minecraft = Minecraft.getInstance();
        UUID player = minecraft == null || minecraft.player == null ? null : minecraft.player.getUUID();
        boolean claimed = player != null && ClientQuestCache.rewardClaimedBy(player, questId, index);
        boolean locked = !ClientQuestCache.rewardLockOf(questId, index).isEmpty();
        boolean claimable = !claimed && !locked
                && ClientQuestCache.stateOf(questId) == QuestState.COMPLETED;
        // No count on a reward row: a "0 / 1" under a reward reads as a task still owed, and the
        // status the adapters draw in its place says what the row actually is.
        return new QuestRow(rewardIcon(reward), rewardLabel(reward), 0, 0,
                claimed, locked, claimable, "", index);
    }

    @Override
    public String stateText(String questId) {
        return QuestBookScreen.stateLabel(ClientQuestCache.stateOf(questId));
    }

    @Override
    public int stateColour(String questId) {
        return switch (ClientQuestCache.stateOf(questId)) {
            case COMPLETED -> PagePalette.COMPLETE;
            case STARTED -> PagePalette.PROGRESS;
            case UNLOCKED -> PagePalette.AVAILABLE;
            case LOCKED -> PagePalette.LOCKED;
        };
    }

    @Override
    public Component rewardStatusLabel(RewardStatus status) {
        return switch (status) {
            case READY -> Component.translatableWithFallback("tenet.viewer.ready", "Ready");
            case LOCKED -> Component.translatableWithFallback("tenet.viewer.locked", "Locked");
            case CLAIMED -> Component.translatableWithFallback("tenet.viewer.claimed", "Claimed");
        };
    }

    @Override
    public void openQuest(String questId) {
        QuestBookScreen.openOn(questId);
    }

    @Override
    public ResourceLocation categoryId() {
        return CATEGORY_ID;
    }

    @Override
    public Component categoryTitle() {
        return Component.translatableWithFallback("tenet.viewer.category", "Quests");
    }

    @Override
    public Component tasksLabel() {
        return Component.translatableWithFallback("tenet.viewer.tasks", "Tasks");
    }

    @Override
    public Component rewardsLabel() {
        return Component.translatableWithFallback("tenet.viewer.rewards", "Rewards");
    }

    @Override
    public ItemStack categoryIcon() {
        return QuestBook.ITEM == null ? new ItemStack(Items.WRITABLE_BOOK) : new ItemStack(QuestBook.ITEM);
    }

    // ------------------------------------------------------------------
    // The snapshot
    // ------------------------------------------------------------------

    /**
     * Rebuilds both snapshots from the cache as it stands.
     *
     * <p>Package-private with the tag resolver as a parameter so a test can supply a fake one: a test
     * JVM loads no datapack, so {@code BuiltInRegistries.ITEM.getTag} answers empty there, and the
     * expansion — the part that turns {@code #minecraft:logs} into the items a viewer can find — would
     * otherwise be untestable. Production passes {@link #registryTagItems}.
     */
    void rebuild(long revision, Function<TagKey<Item>, List<ResourceLocation>> tagItems) {
        ItemQuestIndex.Builder index = ItemQuestIndex.builder();
        List<QuestPage> built = new ArrayList<>();
        // One expansion per tag per rebuild. A pack that gates several quests behind `#c:ingots/iron`
        // otherwise reads the registry once per task, and the answer cannot differ inside one walk of
        // one tree -- so the second read is the same question asked again. A resolver that answers null
        // is not remembered, which is what `computeIfAbsent` does with a null mapping: the miss is
        // retried rather than cached as an absence.
        java.util.Map<TagKey<Item>, List<ResourceLocation>> expansions = new java.util.HashMap<>();
        Function<TagKey<Item>, List<ResourceLocation>> once =
                tag -> expansions.computeIfAbsent(tag, tagItems::apply);
        // The id index, built here because this is the only walk of the pages that has to happen at all
        // — see QuestContent.page for why an adapter must not write its own.
        java.util.Map<String, QuestPage> byQuest = new java.util.HashMap<>();
        for (ClientQuestCache.Entry entry : ClientQuestCache.entries()) {
            // A quest the author kept out of recipe viewers never becomes a page, an index row, or
            // an id lookup: the legacy `disable_recipe_mod`, resolved server-side against the
            // file's default and carried as `hideFromViewers`. Skipped before the ref is built, so
            // nothing downstream can disagree about whether it is listed.
            if (entry.hideFromViewers()) {
                continue;
            }
            QuestRef ref = new QuestRef(entry.id(), entry.titleText(), entry.chapterTitleText(),
                    entry.icon(), entry.iconId(), entry.textureIcon(), entry.spriteIcon());
            List<QuestRow> tasks = taskRows(entry, ref, index, once);
            List<QuestRow> rewards = rewardRows(entry, ref, index);
            if (!tasks.isEmpty() || !rewards.isEmpty()) {
                // A page with nothing to show is one a player could never be led to by an item, and a
                // blank page is worse than no page.
                QuestPage page = new QuestPage(ref, tasks, rewards);
                built.add(page);
                byQuest.put(entry.id(), page);
            }
        }
        pages = List.copyOf(built);
        byId = java.util.Map.copyOf(byQuest);
        this.index = index.build();
        builtRevision = revision;
    }

    private static List<QuestRow> taskRows(ClientQuestCache.Entry entry, QuestRef ref,
                                           ItemQuestIndex.Builder index,
                                           Function<TagKey<Item>, List<ResourceLocation>> tagItems) {
        List<QuestRow> rows = new ArrayList<>(entry.tasks().size());
        for (int source = 0; source < entry.tasks().size(); source++) {
            ClientQuestCache.TaskEntry task = entry.tasks().get(source);
            if (!referencesAnItem(task.hasItem(), task.itemId(), task.tagId())) {
                continue;
            }
            rows.add(new QuestRow(taskIcon(task), taskLabel(task), 0, task.count(),
                    false, false, task.tagId(), source));
            addItem(index, task.itemId(), ref, true, false);
            addTag(index, task.tagId(), ref, tagItems);
        }
        return rows;
    }

    private static List<QuestRow> rewardRows(ClientQuestCache.Entry entry, QuestRef ref,
                                             ItemQuestIndex.Builder index) {
        List<QuestRow> rows = new ArrayList<>(entry.rewards().size());
        for (int source = 0; source < entry.rewards().size(); source++) {
            ClientQuestCache.RewardEntry reward = entry.rewards().get(source);
            if (!referencesAnItem(reward.hasItem(), reward.itemId(), "")) {
                continue;
            }
            rows.add(new QuestRow(rewardIcon(reward), rewardLabel(reward), 0, reward.count(),
                    false, false, "", source));
            addItem(index, reward.itemId(), ref, false, true);
        }
        return rows;
    }

    /**
     * Whether a row belongs on a viewer page: it names an item, or a tag, in any form.
     *
     * <p>{@code hasItem} is a resolved stack, {@code itemId} an id that may not resolve in this build
     * and {@code tagId} a tag — all three are item references, and a missing item keeps its id rather
     * than losing its row, the same rule the book follows.
     */
    private static boolean referencesAnItem(boolean hasItem, String itemId, String tagId) {
        return hasItem || !itemId.isEmpty() || !tagId.isEmpty();
    }

    private static void addItem(ItemQuestIndex.Builder index, String itemId, QuestRef ref,
                                boolean required, boolean awarded) {
        if (itemId == null || itemId.isEmpty()) {
            return;
        }
        ResourceLocation item = ResourceLocation.tryParse(itemId);
        if (item == null) {
            // A hand-edited file can carry an id that does not parse; the validator warns about it and
            // the quest stays loadable, so the index skips the entry rather than the page.
            return;
        }
        index.add(item, ref, required, awarded);
    }

    private static void addTag(ItemQuestIndex.Builder index, String tagId, QuestRef ref,
                               Function<TagKey<Item>, List<ResourceLocation>> tagItems) {
        if (tagId == null || tagId.isEmpty()) {
            return;
        }
        ResourceLocation tag = ResourceLocation.tryParse(tagId);
        if (tag == null) {
            return;
        }
        for (ResourceLocation item : tagItems.apply(TagKey.create(Registries.ITEM, tag))) {
            index.add(item, ref, true, false);
        }
    }

    /**
     * The tag's members, from the client's own item registry.
     *
     * <p>Client-side tags are synced on join, so this is reading data the client already has — the
     * same registry the picker and the item stack resolution read. A tag nobody declared answers
     * empty, which leaves the quest findable from its label but not from an item, which is the honest
     * reading of a file that names a tag no pack provides.
     */
    private static List<ResourceLocation> registryTagItems(TagKey<Item> tag) {
        return itemsOf(BuiltInRegistries.ITEM.getTag(tag));
    }

    /**
     * The members of a holder set, as ids.
     *
     * <p>Split from {@link #registryTagItems} so {@link #tick} can read a tag's holder set <b>once</b> and
     * take both things it needs from it — the members to expand and the object to watch. Reading it twice
     * would be two registry lookups per tag per rebuild for one answer, and it would leave the watch and the
     * members free to disagree if the two reads straddled a reload.
     */
    private static List<ResourceLocation> itemsOf(Optional<? extends HolderSet<Item>> holders) {
        if (holders.isEmpty()) {
            return List.of();
        }
        List<ResourceLocation> items = new ArrayList<>();
        for (Holder<Item> holder : holders.get()) {
            holder.unwrapKey().map(ResourceKey::location).ifPresent(items::add);
        }
        return items;
    }

    // ------------------------------------------------------------------
    // Small conversions
    // ------------------------------------------------------------------

    private static ItemStack taskIcon(ClientQuestCache.TaskEntry task) {
        return task.hasItem() ? task.item() : ItemStack.EMPTY;
    }

    private static ItemStack rewardIcon(ClientQuestCache.RewardEntry reward) {
        return reward.hasItem() ? reward.item() : ItemStack.EMPTY;
    }

    /**
     * A task row's label: the item's name when it resolved, its id when it did not, and the task's own
     * sentence otherwise.
     *
     * <p>The id case matters: an item this build does not have still names an item, and its row has no
     * icon, so the id is what tells the player which item the quest is about.
     */
    private static String taskLabel(ClientQuestCache.TaskEntry task) {
        if (task.hasItem()) {
            return task.text().getString();
        }
        if (!task.itemId().isEmpty()) {
            return task.itemId();
        }
        return task.text().getString();
    }

    /** A reward row's label, with the same missing-item rule as a task's. */
    private static String rewardLabel(ClientQuestCache.RewardEntry reward) {
        if (reward.hasItem()) {
            return reward.text().getString();
        }
        if (!reward.itemId().isEmpty()) {
            return reward.itemId();
        }
        return reward.text().getString();
    }

    private static QuestRow blankRow() {
        return new QuestRow(ItemStack.EMPTY, "", 0, 0, false, false, "");
    }
}
