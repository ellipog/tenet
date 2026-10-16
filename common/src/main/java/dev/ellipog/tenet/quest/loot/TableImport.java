package dev.ellipog.tenet.quest.loot;

import com.google.gson.JsonObject;

import dev.ellipog.tenet.Constants;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Reading a container, or a player's own inventory, as table entries.
 *
 * <h2>One item, one entry, however many slots it occupies</h2>
 *
 * <p>A chest holding three stacks of iron is one entry of iron, not three entries — the author is
 * building a table of things, and a table with the same item three times is a table whose weights
 * nobody can reason about. So the slots are summed by item <i>and</i> components: a plain sword and a
 * named one are two things, and merging them would throw away the name.
 *
 * <p>Counts are capped at the codec's own limit rather than at a stack's, because a table entry is not
 * a stack: {@code ItemRef} takes up to 6400, and granting it already splits into legal stacks. What the
 * cap is for is the report — an author who imported a shulker of iron should be told the number was
 * clipped rather than find out at grant time.
 */
public final class TableImport {

    /**
     * The most a single entry may carry: {@code ItemRef}'s own bound, and its own constant.
     *
     * <p>This used to be a second {@code 6400} whose javadoc said it was "ItemRef's own bound" —
     * a comment standing in for a reference. The two agreeing was a coincidence of nobody having
     * edited one of them yet.
     */
    public static final int MAX_COUNT = dev.ellipog.tenet.quest.ItemRef.MAX_COUNT;

    /** How far a player can reach a container, which is the vanilla block-reach distance. */
    public static final double REACH = 4.5;

    private TableImport() {
    }

    /**
     * The container a player is looking at, if any.
     *
     * <p>A double chest is <b>one</b> container: a {@code ChestBlockEntity} only knows its own half, so
     * filling the half a player aimed at and spilling the rest would be a bug that looks like a bug in
     * the table. {@code ChestBlock.getContainer} is what joins the two halves; every other inventory —
     * a barrel, a hopper, a modded multi-block — is the block entity itself.
     */
    public static Optional<Container> containerLookingAt(ServerPlayer player) {
        BlockHitResult hit = (BlockHitResult) player.pick(REACH, 1.0F, false);
        if (hit == null || hit.getType() != HitResult.Type.BLOCK) {
            return Optional.empty();
        }
        var level = player.level();
        var pos = hit.getBlockPos();
        BlockState state = level.getBlockState(pos);
        if (state.getBlock() instanceof ChestBlock chest) {
            Container joined = ChestBlock.getContainer(chest, state, level, pos, true);
            return Optional.ofNullable(joined);
        }
        if (level.getBlockEntity(pos) instanceof Container container) {
            return Optional.of(container);
        }
        return Optional.empty();
    }

    /** Whether a player is looking at a block that is not a container, which is a different refusal. */
    public static boolean lookingAtBlock(ServerPlayer player) {
        BlockHitResult hit = (BlockHitResult) player.pick(REACH, 1.0F, false);
        return hit != null && hit.getType() == HitResult.Type.BLOCK;
    }

    /**
     * One entry per distinct item, from a container's slots and then a player's.
     *
     * @return the entries, in the order met, and the names of any that were clipped
     */
    public static Imported fromContainer(Container container) {
        Map<String, JsonObject> byItem = new LinkedHashMap<>();
        Map<String, Integer> counts = new LinkedHashMap<>();
        List<String> clipped = new ArrayList<>();
        for (int slot = 0; slot < container.getContainerSize(); slot++) {
            add(byItem, counts, clipped, container.getItem(slot));
        }
        return assemble(byItem, counts, clipped);
    }

    /** The same, from a player's own inventory: what the editor's "import my inventory" reads. */
    public static Imported fromPlayer(ServerPlayer player) {
        Map<String, JsonObject> byItem = new LinkedHashMap<>();
        Map<String, Integer> counts = new LinkedHashMap<>();
        List<String> clipped = new ArrayList<>();
        for (int slot = 0; slot < player.getInventory().getContainerSize(); slot++) {
            add(byItem, counts, clipped, player.getInventory().getItem(slot));
        }
        return assemble(byItem, counts, clipped);
    }

    private static Imported assemble(Map<String, JsonObject> byItem, Map<String, Integer> counts,
                                     List<String> clipped) {
        List<JsonObject> entries = new ArrayList<>();
        for (Map.Entry<String, JsonObject> entry : byItem.entrySet()) {
            entry.getValue().getAsJsonObject("reward")
                    .addProperty("count", counts.get(entry.getKey()));
            entries.add(entry.getValue());
        }
        return new Imported(entries, List.copyOf(clipped));
    }

    /** What an import found. */
    public record Imported(List<JsonObject> entries, List<String> clipped) {

        public Imported {
            entries = List.copyOf(entries);
            clipped = List.copyOf(clipped);
        }

        public boolean isEmpty() {
            return entries.isEmpty();
        }
    }

    /** One stack, folded into the running totals. */
    private static void add(Map<String, JsonObject> byItem, Map<String, Integer> counts,
                            List<String> clipped, ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return;
        }
        Identifier id = BuiltInRegistries.ITEM.getKey(stack.getItem());
        String key = id + "|" + stack.getComponents();
        int total = counts.getOrDefault(key, 0) + stack.getCount();
        if (total > MAX_COUNT) {
            if (!clipped.contains(id.toString())) {
                clipped.add(id.toString());
            }
            total = MAX_COUNT;
        }
        counts.put(key, total);
        byItem.computeIfAbsent(key, ignored -> {
            JsonObject reward = new JsonObject();
            reward.addProperty("type", "tenet:item");
            reward.addProperty("item", id.toString());
            // The stack's own components, so a named sword imports as that sword rather than as a sword.
            if (!stack.getComponentsPatch().isEmpty()) {
                var encoded = net.minecraft.core.component.DataComponentPatch.CODEC
                        .encodeStart(com.mojang.serialization.JsonOps.INSTANCE, stack.getComponentsPatch())
                        .result()
                        .orElse(null);
                if (encoded != null) {
                    reward.add("components", encoded);
                }
            }
            JsonObject entry = new JsonObject();
            entry.addProperty("weight", 1);
            entry.add("reward", reward);
            return entry;
        });
    }

    /** How to say what an import did, for a chat line or an editor's status. */
    public static String describe(Imported imported, String into) {
        if (imported.isEmpty()) {
            return "there was nothing in there to import";
        }
        StringBuilder line = new StringBuilder("added ").append(imported.entries().size())
                .append(imported.entries().size() == 1 ? " entry" : " entries")
                .append(" to ").append(into);
        if (!imported.clipped().isEmpty()) {
            line.append("; ").append(imported.clipped().size()).append(" item(s) were clipped to ")
                    .append(MAX_COUNT).append(" (")
                    .append(String.join(", ", imported.clipped())).append(")");
        }
        Constants.LOG.debug("{}", line);
        return line.toString();
    }
}
