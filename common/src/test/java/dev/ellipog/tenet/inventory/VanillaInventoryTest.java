package dev.ellipog.tenet.inventory;

import dev.ellipog.tenet.quest.ItemStacks;
import dev.ellipog.tenet.server.HeadlessServer;

import net.minecraft.server.level.ServerPlayer;
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
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The vanilla adapter's half of the loader parity corner — the one Fabric plays by.
 *
 * <p>Fabric installs {@link VanillaInventory}, whose model merges into a part-filled offhand
 * exactly as vanilla's own insertion does. NeoForge installs its own implementation instead,
 * which clamps to the 36 main slots and refuses that same merge — see
 * {@code NeoForgeInventory}, which names this class as the behaviour it deliberately differs
 * from. So this test pins the merge side, that class's range pins the refusal side, and a
 * future edit that "unifies" the two has a failing test telling it the difference is load-bearing.
 *
 * <p>Each case asks both halves of the strict-claim contract — {@code fitsAll} and
 * {@code insert} — because a simulation that promises space the insert cannot keep is the
 * precise failure the strict path exists to prevent.
 */
@DisplayName("the vanilla inventory adapter")
class VanillaInventoryTest {

    /** Where the world goes. Inside the build directory, so not committed — and deleted first, every run. */
    private static final Path ROOT = Path.of("build", "vanilla-inventory");

    private static HeadlessServer server;

    @BeforeAll
    static void startEverything() throws Exception {
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
    @DisplayName("a full main inventory with a part-filled offhand still takes a merge")
    void fullMainWithPartFilledOffhandMerges() {
        ServerPlayer player = server.spawnPlayer("tenet-offhand-merge");
        server.callOnServerThread(() -> {
            fillMain(player);
            player.getInventory().setItem(40, ItemStacks.stackOf(Items.STONE, 60));
            player.getInventory().setChanged();
            return null;
        });

        assertTrue(server.callOnServerThread(() -> VanillaInventory.INSTANCE.fitsAll(player,
                List.of(ItemStacks.stackOf(Items.STONE, 4)))),
                "four stone merge into the part-filled offhand");
        ItemStack remainder = server.callOnServerThread(() ->
                VanillaInventory.INSTANCE.insert(player, ItemStacks.stackOf(Items.STONE, 4)));
        assertTrue(remainder.isEmpty(), "the merge leaves no remainder");
        assertEquals(64, server.callOnServerThread(
                () -> player.getInventory().getItem(40).getCount()),
                "and the offhand holds the merged stack");
    }

    @Test
    @DisplayName("an empty offhand is never an empty slot")
    void emptyOffhandNeverPlaces() {
        ServerPlayer player = server.spawnPlayer("tenet-offhand-empty");
        server.callOnServerThread(() -> {
            fillMain(player);
            player.getInventory().setChanged();
            return null;
        });

        assertFalse(server.callOnServerThread(() -> VanillaInventory.INSTANCE.fitsAll(player,
                List.of(ItemStacks.stackOf(Items.STONE, 1)))),
                "nothing merges and no slot is free");
        ItemStack remainder = server.callOnServerThread(() ->
                VanillaInventory.INSTANCE.insert(player, ItemStacks.stackOf(Items.STONE, 1)));
        assertEquals(1, remainder.getCount(), "what does not fit comes back whole");
    }

    @Test
    @DisplayName("a merge-only slot refuses a stack it cannot merge")
    void mergeOnlyRefusesForeignStacks() {
        ServerPlayer player = server.spawnPlayer("tenet-offhand-foreign");
        server.callOnServerThread(() -> {
            fillMain(player);
            player.getInventory().setItem(40, ItemStacks.stackOf(Items.STONE, 60));
            player.getInventory().setChanged();
            return null;
        });

        assertFalse(server.callOnServerThread(() -> VanillaInventory.INSTANCE.fitsAll(player,
                List.of(ItemStacks.stackOf(Items.DIRT, 1)))),
                "dirt merges nowhere and no slot is free");
    }

    @Test
    @DisplayName("armour slots are never capacity")
    void armourIsIgnored() {
        ServerPlayer player = server.spawnPlayer("tenet-armour-ignored");
        server.callOnServerThread(() -> {
            var inventory = player.getInventory();
            fillMain(player);
            for (int slot = 36; slot < 40; slot++) {
                inventory.setItem(slot, ItemStacks.stackOf(Items.STONE, 64));
            }
            inventory.setChanged();
            return null;
        });

        assertFalse(server.callOnServerThread(() -> VanillaInventory.INSTANCE.fitsAll(player,
                List.of(ItemStacks.stackOf(Items.STONE, 1)))),
                "full armour beside a full main is still a full inventory");
    }

    /** Stone in every main slot, armour and offhand left for the caller to arrange. */
    private static void fillMain(ServerPlayer player) {
        var inventory = player.getInventory();
        inventory.clearContent();
        for (int slot = 0; slot < 36; slot++) {
            inventory.setItem(slot, ItemStacks.stackOf(Items.STONE, 64));
        }
        // Survival, so vanilla's insert keeps its remainder: a headless player otherwise has
        // infinite materials, and creative add() voids what does not fit instead of handing it
        // back — which would read as an insert that eats items rather than one that refuses.
        player.gameMode.changeGameModeForPlayer(net.minecraft.world.level.GameType.SURVIVAL);
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
