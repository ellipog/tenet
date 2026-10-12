package dev.ellipog.tenet.progress;

import dev.ellipog.tenet.server.HeadlessServer;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The first-join book: one grant per player, never on rejoin, nothing when the pack says nothing.
 *
 * <h2>Why its own server rather than a case in the playthrough</h2>
 *
 * <p>The playthrough is an ordered chain sharing one universe and one questline; this feature is
 * orthogonal to every quest in it, and its once-only mark lives in the world save — so a case
 * there would either disturb the chain or depend on running after all of it. A server of its own
 * on its own universe directory costs one more startup and buys isolation: the mark, the setting
 * and the inventory here are this test's alone.
 *
 * <h2>What drives the grant</h2>
 *
 * <p>The injectable overload, with a vanilla book standing in for the quest book: the decision is
 * about the mark rather than the item, and the headless registry has no mod items (see
 * {@code NothingIsRegistered} in the playthrough). The production path — the pack's own answer
 * and {@code QuestBook.ITEM} — is the two-argument form beside it, which this exercises through
 * the same code.
 */
@DisplayName("the first-join book")
class StarterBooksTest {

    /** Where the world goes. Inside the build directory, so not committed — and deleted first, every run. */
    private static final Path ROOT = Path.of("build", "starter-books");

    private static HeadlessServer server;

    @BeforeAll
    static void startEverything() throws Exception {
        // Deleted first, every run: the mark lives in the world save, so a leftover universe
        // grants nothing and the first case fails for a reason nobody would guess.
        deleteRecursively(ROOT);
        Files.createDirectories(ROOT);
        server = HeadlessServer.start(ROOT.resolve("universe"));
    }

    @AfterAll
    static void stopEverything() {
        if (server != null) {
            server.close();
        }
    }

    @Test
    @DisplayName("a first join with the option on grants exactly one book, and a second grants none")
    void firstJoinGrantsOnce() {
        ServerPlayer player = server.spawnPlayer("tenet-newcomer");
        assertTrue(server.callOnServerThread(() ->
                StarterBooks.grantIfFirstJoin(server.server(), player, true, Items.WRITABLE_BOOK)),
                "the first join should have been granted");
        assertEquals(1, countBooks(player), "exactly one book, in the first free slot");

        // The rejoin: the same call with the mark in place. A respawn is the same shape —
        // respawning fires no join at all — so the mark going missing is the only way a second
        // book could ever arrive, and this is the assertion that it does not.
        assertFalse(server.callOnServerThread(() ->
                StarterBooks.grantIfFirstJoin(server.server(), player, true, Items.WRITABLE_BOOK)),
                "a second join must grant nothing");
        assertEquals(1, countBooks(player), "and the inventory is untouched");
    }

    @Test
    @DisplayName("with the option off nothing changes")
    void disabledGrantsNothing() {
        ServerPlayer player = server.spawnPlayer("tenet-stranger");
        assertFalse(server.callOnServerThread(() ->
                StarterBooks.grantIfFirstJoin(server.server(), player, false, Items.WRITABLE_BOOK)),
                "a pack that says nothing joins exactly as before");
        assertEquals(0, countBooks(player), "empty hands stay empty");
    }

    @Test
    @DisplayName("a full inventory drops the book at the player's feet rather than losing it")
    void fullInventoryDropsAtFeet() {
        ServerPlayer player = server.spawnPlayer("tenet-packed");
        server.callOnServerThread(() -> {
            var inventory = player.getInventory();
            inventory.clearContent();
            for (int slot = 0; slot < 36; slot++) {
                inventory.setItem(slot, new ItemStack(Items.STONE, 64));
            }
            inventory.setChanged();
            return null;
        });
        // The premise first, stated plainly and asked of the same seam the grant uses: with
        // nowhere to merge and no free slot, the book does not fit. (Vanilla's own `add`
        // boolean is not this signal — it answers true on a full inventory while placing
        // nothing — which is why the grant reads the remainder instead.)
        assertFalse(server.callOnServerThread(() ->
                dev.ellipog.tenet.inventory.InventoryAccesses.current().fitsAll(player,
                        java.util.List.of(new ItemStack(Items.WRITABLE_BOOK, 1)))),
                "a full inventory has no room for the book");
        ItemStack remainder = server.callOnServerThread(() ->
                StarterBooks.insertOrDrop(player, new ItemStack(Items.WRITABLE_BOOK, 1)));
        assertEquals(1, remainder.getCount(), "what does not fit is handed to the floor, not lost");
        assertTrue(remainder.is(Items.WRITABLE_BOOK), "the whole book, and the right item");
        assertEquals(0, countBooks(player), "with nothing added to a full inventory");
        assertEquals(1, dropsNear(player), "and one book lying at their feet");
        assertTrue(server.callOnServerThread(() ->
                StarterBooks.grantIfFirstJoin(server.server(), player, true, Items.WRITABLE_BOOK)),
                "and the grant itself still counts a full inventory as owed");
    }

    @Test
    @DisplayName("the mark survives a save and load")
    void markSurvivesSaveAndLoad() {
        ServerPlayer player = server.spawnPlayer("tenet-remembered");
        assertTrue(server.callOnServerThread(() ->
                StarterBooks.grantIfFirstJoin(server.server(), player, true, Items.WRITABLE_BOOK)),
                "the first join should have been granted");
        HolderLookup.Provider registries = server.server().registryAccess();
        StarterBooks reloaded = server.callOnServerThread(() -> {
            StarterBooks books = StarterBooks.of(server.server());
            CompoundTag tag = books.save(new CompoundTag(), registries);
            return StarterBooks.load(tag, registries);
        });
        assertTrue(reloaded.has(player.getUUID()), "a rejoin after a restart still finds its mark");
    }

    /** How many of the test's books this player holds. */
    private static int countBooks(ServerPlayer player) {
        return server.callOnServerThread(() -> {
            int found = 0;
            for (ItemStack stack : player.getInventory().items) {
                if (stack.is(Items.WRITABLE_BOOK)) {
                    found += stack.getCount();
                }
            }
            return found;
        });
    }

    /** Dropped items near the player, for the full-inventory case. */
    private static int dropsNear(ServerPlayer player) {
        return server.callOnServerThread(() -> server.server().overworld()
                .getEntitiesOfClass(ItemEntity.class, player.getBoundingBox().inflate(8), each -> true)
                .size());
    }

    private static void deleteRecursively(Path root) throws IOException {
        if (!Files.exists(root)) {
            return;
        }
        try (var walk = Files.walk(root)) {
            walk.sorted(Comparator.reverseOrder())
                    .forEach(path -> {
                        try {
                            Files.delete(path);
                        }
                        catch (IOException e) {
                            throw new RuntimeException(e);
                        }
                    });
        }
    }
}
