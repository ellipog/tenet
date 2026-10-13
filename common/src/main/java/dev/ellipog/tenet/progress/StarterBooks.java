package dev.ellipog.tenet.progress;

import dev.ellipog.tenet.Constants;
import dev.ellipog.tenet.QuestBook;
import dev.ellipog.tenet.quest.TenetQuests;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

/**
 * Which players already found a quest book in their inventory on first join, for one world.
 *
 * <h2>Why its own file rather than a flag on the progress</h2>
 *
 * <p>Progress is keyed by <b>team</b> and wiped by {@code /tenet reset}; a starter book is owed to
 * a <b>player</b> exactly once, however many parties they join and whatever an operator resets.
 * A flag inside {@link ProgressStore} would be cleared by the same reset that is supposed to leave
 * it alone — and a rejoin after a reset would then hand over a second book, which is precisely
 * what this exists to prevent.
 *
 * <h2>Why not the player's own persistent data</h2>
 *
 * <p>That would also be per player, but its lifetime across death and logout is the loader's
 * business rather than this mod's: a death that drops the in-memory flag with a logout before
 * the next save would grant the book twice. A world file this mod owns has no such window —
 * the mark and the grant happen on the same thread, and the store is marked dirty with it.
 */
public final class StarterBooks extends SavedData {

    private static final String DATA_NAME = "tenet_starter_books";

    /** Every player who already got theirs, in the order they joined. Insertion-ordered, so the file is stable. */
    private final Set<UUID> given = new LinkedHashSet<>();

    /** The store for this server. */
    public static StarterBooks of(MinecraftServer server) {
        // Overworld, so the data is global rather than per-dimension — the same shape
        // {@link ProgressStore} uses, and for the same reason. See its note on the three-argument
        // Factory: two arguments is the older shape and does not compile.
        return server.overworld().getDataStorage()
                .computeIfAbsent(new SavedData.Factory<>(StarterBooks::new, StarterBooks::load, null), DATA_NAME);
    }

    /** Whether this player already got their book. */
    public boolean has(UUID player) {
        return given.contains(player);
    }

    /**
     * Records the grant, and answers whether it was new.
     *
     * <p>Dirty only when something changed, so a join that grants nothing costs no save.
     */
    public boolean markGiven(UUID player) {
        if (!given.add(player)) {
            return false;
        }
        setDirty();
        return true;
    }

    /**
     * Hands one quest book to a player joining for the first time, when the pack asks for it.
     *
     * <p>The production path: reads the pack's own answer and the book item, then grants through
     * the injectable form below. A respawn never reaches here — respawning fires no join — and a
     * rejoin finds its mark, so "exactly once" holds without any further state.
     *
     * @return whether a book was handed over
     */
    public static boolean grantIfFirstJoin(MinecraftServer server, ServerPlayer player) {
        return grantIfFirstJoin(server, player,
                TenetQuests.settings().giveBookOnFirstJoin(), QuestBook.ITEM);
    }

    /**
     * The same, with the pack's answer and the book item given.
     *
     * <p>Injectable so a test can drive the once-only behaviour without loading a questline: the
     * decision is about the mark, not about which item it carries. The grant is vanilla inventory
     * insertion — the first free slot, never overwriting, the way {@code TableExport} hands a
     * payout over — and what does not fit drops at the player's feet rather than vanishing, the
     * way the emergency shelf does.
     */
    static boolean grantIfFirstJoin(MinecraftServer server, ServerPlayer player, boolean enabled,
                                    Item bookItem) {
        if (!enabled || bookItem == null) {
            return false;
        }
        StarterBooks books = StarterBooks.of(server);
        if (!books.markGiven(player.getUUID())) {
            return false;
        }
        insertOrDrop(player, new ItemStack(bookItem, 1));
        return true;
    }

    /**
     * Puts one stack into the inventory, or drops it at the player's feet.
     *
     * <p>Through the loader's own transfer rather than vanilla's {@code add} directly, and asked
     * in the seam's own order: {@code fitsAll} first, {@code insert} only when it fits. That order
     * is load-bearing rather than cautious — vanilla's {@code add} <b>destroys</b> what fits
     * nowhere while reporting success, so inserting blind into a full inventory loses the stack
     * silently and the remainder comes back empty with nothing to drop. The strict claim path
     * asks first for the same reason; this is the lenient half of that rule (what fits goes in,
     * the rest drops) where the strict half refuses.
     *
     * @return what went to the floor, or empty when it all fit
     */
    static ItemStack insertOrDrop(ServerPlayer player, ItemStack stack) {
        var access = dev.ellipog.tenet.inventory.InventoryAccesses.current();
        if (!access.fitsAll(player, java.util.List.of(stack))) {
            ItemStack drop = stack.copy();
            player.drop(drop, false);
            return drop;
        }
        ItemStack remainder = access.insert(player, stack);
        if (!remainder.isEmpty()) {
            player.drop(remainder, false);
        }
        return remainder;
    }

    static StarterBooks load(CompoundTag tag, HolderLookup.Provider registries) {
        StarterBooks books = new StarterBooks();
        // Absent in a file from before this store existed, which is why nothing checks a version:
        // a missing section is "nobody got one", a valid state rather than something to migrate.
        ListTag list = tag.getList("given", Tag.TAG_STRING);
        // Summarised rather than one line per bad entry: a corrupted file names every one of its
        // rows at warn level otherwise, and the count plus the first few is what a reader acts on.
        int bad = 0;
        String firstBad = null;
        for (int i = 0; i < list.size(); i++) {
            try {
                books.given.add(UUID.fromString(list.getString(i)));
            }
            catch (IllegalArgumentException e) {
                bad++;
                if (firstBad == null) {
                    firstBad = list.getString(i);
                }
            }
        }
        if (bad > 0) {
            Constants.LOG.warn("skipped {} invalid player uuid{} in stored starter books"
                    + " (first: '{}')", bad, bad == 1 ? "" : "s", firstBad);
        }
        return books;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        // As strings rather than putUUID: greppable when someone opens the file to debug, the way
        // the progress file's own ids are.
        ListTag list = new ListTag();
        for (UUID player : given) {
            list.add(net.minecraft.nbt.StringTag.valueOf(player.toString()));
        }
        tag.put("given", list);
        return tag;
    }
}
