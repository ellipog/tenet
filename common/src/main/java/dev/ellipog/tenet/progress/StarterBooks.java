package dev.ellipog.tenet.progress;

import dev.ellipog.tenet.Constants;
import dev.ellipog.tenet.QuestBook;
import dev.ellipog.tenet.quest.TenetQuests;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;
import net.minecraft.world.level.storage.LevelResource;

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

    /** Every player who already got theirs, in the order they joined. Insertion-ordered, so the file is stable. */
    private final Set<UUID> given = new LinkedHashSet<>();

    /**
     * The store's serialised form. A plain tag codec over {@link #save} and {@link #load} rather
     * than a field-by-field codec: UUID strings need no migration machinery, and the
     * {@code registries} neither side takes mean there is nothing a fancier codec would use.
     */
    public static final com.mojang.serialization.Codec<StarterBooks> CODEC =
            CompoundTag.CODEC.xmap(StarterBooks::load, books -> books.save(new CompoundTag()));

    private static final SavedDataType<StarterBooks> TYPE = new SavedDataType<>(
            Identifier.fromNamespaceAndPath("tenet", "starter_books"),
            StarterBooks::new,
            CODEC,
            // Null, deliberately: DataFixTypes is vanilla's migration table, and this is our own
            // format with its own version key, so there is nothing for it to migrate. Same null the
            // 1.21.1 `SavedData.Factory` took, for the same reason.
            null);

    /** The 1.21.1 file name, flat under the data folder. Only used once, to move it (see below). */
    private static final String LEGACY_FILE = "tenet_starter_books.dat";

    /** The store for this server. */
    public static StarterBooks of(MinecraftServer server) {
        // Overworld, so the data is global rather than per-dimension.
        moveLegacyStoreIfPresent(server);
        return server.overworld().getDataStorage().computeIfAbsent(TYPE);
    }

    /**
     * Moves a 1.21.1 store file into the namespaced place 26.x reads from, once.
     *
     * <p>The old storage wrote {@code data/tenet_starter_books.dat}; the new one resolves the type
     * id to {@code data/tenet/starter_books.dat}. Without this, a server upgrading versions would
     * hand every returning player a second copy of the book. A move rather than a copy, so there is
     * never a moment with two live files; if it throws, the legacy file is untouched and the next
     * boot tries again.
     */
    private static void moveLegacyStoreIfPresent(MinecraftServer server) {
        java.nio.file.Path data = server.getWorldPath(LevelResource.ROOT).resolve("data");
        java.nio.file.Path legacy = data.resolve(LEGACY_FILE);
        java.nio.file.Path current = data.resolve("tenet").resolve("starter_books.dat");
        try {
            if (java.nio.file.Files.isRegularFile(legacy)
                    && !java.nio.file.Files.isRegularFile(current)) {
                java.nio.file.Files.createDirectories(current.getParent());
                java.nio.file.Files.move(legacy, current);
                Constants.LOG.info("moved stored starter books from {} to {} (1.21.1 to 26.x storage layout)",
                        legacy.getFileName(), current);
            }
        }
        catch (java.io.IOException e) {
            Constants.LOG.warn("could not move stored starter books from {} (leaving it for the next boot): {}",
                    legacy, e.toString());
        }
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
        insertOrDrop(player, dev.ellipog.tenet.quest.ItemStacks.stackOf(bookItem, 1));
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
            // Server-side drop with no client prediction: the server is giving, not the player.
            player.drop(drop, false, net.minecraft.util.Prediction.SERVER_ONLY);
            return drop;
        }
        ItemStack remainder = access.insert(player, stack);
        if (!remainder.isEmpty()) {
            player.drop(remainder, false, net.minecraft.util.Prediction.SERVER_ONLY);
        }
        return remainder;
    }

    static StarterBooks load(CompoundTag tag) {
        StarterBooks books = new StarterBooks();
        // Absent in a file from before this store existed, which is why nothing checks a version:
        // a missing section is "nobody got one", a valid state rather than something to migrate.
        ListTag list = tag.getListOrEmpty("given");
        // Summarised rather than one line per bad entry: a corrupted file names every one of its
        // rows at warn level otherwise, and the count plus the first few is what a reader acts on.
        int bad = 0;
        String firstBad = null;
        for (int i = 0; i < list.size(); i++) {
            try {
                books.given.add(UUID.fromString(list.getStringOr(i, "")));
            }
            catch (IllegalArgumentException e) {
                bad++;
                if (firstBad == null) {
                    firstBad = list.getStringOr(i, "");
                }
            }
        }
        if (bad > 0) {
            Constants.LOG.warn("skipped {} invalid player uuid{} in stored starter books"
                    + " (first: '{}')", bad, bad == 1 ? "" : "s", firstBad);
        }
        return books;
    }

    /**
     * Writes the store. Called by {@link #CODEC}, not overridden from anywhere: 26.x storage
     * serialises through the type's codec, and this is the encoder half.
     */
    public CompoundTag save(CompoundTag tag) {
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
