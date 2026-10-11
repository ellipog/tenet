package dev.ellipog.tenet.quest.reward;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.ellipog.tenet.Tenet;
import dev.ellipog.tenet.quest.ItemRef;
import dev.ellipog.tenet.quest.QuestReward;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;

import java.util.List;
import java.util.Set;

/**
 * Give the player some items.
 *
 * <pre>{@code { "type": "tenet:item", "item": "minecraft:diamond", "count": 4 } }</pre>
 *
 * <p>Note that this shares a type id with {@link dev.ellipog.tenet.quest.task.ItemTask} —
 * {@code tenet:item} means "items" on both sides, and the two registries are separate. As long as a
 * task and a reward are never confused for one another in the same place, which they cannot be, they
 * read the same way to an author.
 */
public record ItemReward(RewardCommon common, ItemRef item, int randomBonus, boolean onlyOne)
        implements QuestReward {

    public static final ResourceLocation TYPE = ResourceLocation.fromNamespaceAndPath(Tenet.MOD_ID, "item");

    /** The type's own fields; {@link RewardCommon#FIELDS} is unioned in at registration. */
    public static final Set<String> FIELDS =
            Set.of("item", "count", "components", "randomBonus", "onlyOne");

    public static final MapCodec<ItemReward> MAP_CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
            RewardCommon.MAP_CODEC.forGetter(ItemReward::common),
            ItemRef.MAP_CODEC.forGetter(ItemReward::item),
            // The same bound as the count it adds to, and one constant for it: a bonus is "up to this
            // many more of the same thing", so a bound of its own would be a second answer to "how
            // many of an item may one entry hand over".
            Codec.intRange(0, ItemRef.MAX_COUNT).optionalFieldOf("randomBonus", 0)
                    .forGetter(ItemReward::randomBonus),
            Codec.BOOL.optionalFieldOf("onlyOne", false).forGetter(ItemReward::onlyOne)
    ).apply(instance, ItemReward::new));

    @Override
    public ResourceLocation type() {
        return TYPE;
    }

    /**
     * The exact stacks this reward hands over, rolled now.
     *
     * <p>One call, used by both halves of a strict claim: the pre-check asks whether these fit, and the
     * grant inserts these same stacks. Rolling once is what makes the two agree — a bonus rolled twice
     * would be checked at one size and handed over at another.
     *
     * <p>Empty for a reward that gives nothing: an item this build cannot resolve, or an {@code onlyOne}
     * the player already carries.
     */
    public static List<ItemStack> stacksToGive(ItemReward reward, net.minecraft.server.level.ServerPlayer player) {
        ItemStack template = reward.item().toStack();
        if (template.isEmpty()) {
            // The item does not exist. The validator reports this at load time, so reaching here
            // means a reload changed the registries underneath a loaded questline -- worth saying,
            // and not worth throwing over.
            dev.ellipog.tenet.Constants.LOG.warn("tenet: reward {} resolved to nothing and was skipped",
                    reward.item().describe());
            return List.of();
        }

        // `onlyOne`: the legacy "don't give me a second one". Checked by item type, not components -- the
        // question is whether the player already has this thing, not this exact stack.
        if (reward.onlyOne() && carries(player, template)) {
            return List.of();
        }

        int bonus = reward.randomBonus() > 0
                ? player.getRandom().nextInt(reward.randomBonus() + 1)
                : 0;
        int remaining = reward.item().count() + bonus;

        // Split into legal stacks before handing them over: an ItemRef may ask for thousands, and one
        // over-full stack is a value vanilla items do not have.
        List<ItemStack> stacks = new java.util.ArrayList<>();
        while (remaining > 0) {
            int size = Math.min(remaining, template.getMaxStackSize());
            stacks.add(template.copyWithCount(size));
            remaining -= size;
        }
        return List.copyOf(stacks);
    }

    /**
     * The lenient grant: insert what fits, drop the rest.
     *
     * <p>Used wherever a reward is handed over without a fit check — a single row's Claim, an
     * auto-claim at completion, a table's leaves. The insert goes through {@link InventoryAccesses} —
     * the loader's own transfer, and the same one a strict check simulates — and the remainder is
     * dropped at the player's feet rather than discarded. The drop is reported rather than announced:
     * one press can overflow on many stacks, and the claim operation says the one sentence. See
     * {@link RewardFeedback}.
     */
    public static final RewardBehaviour<ItemReward> BEHAVIOUR = (reward, context) -> {
        for (ItemStack give : stacksToGive(reward, context.player())) {
            ItemStack remainder = dev.ellipog.tenet.inventory.InventoryAccesses.current()
                    .insert(context.player(), give);
            if (!remainder.isEmpty()) {
                context.player().drop(remainder, false);
                context.feedback().dropped(remainder);
            }
        }
    };

    /** Whether the player already carries this item at all. */
    private static boolean carries(net.minecraft.server.level.ServerPlayer player, ItemStack template) {
        var inventory = player.getInventory();
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (!stack.isEmpty() && stack.getItem() == template.getItem()) {
                return true;
            }
        }
        return false;
    }

    /**
     * The item and its count, with no label — the client draws the item's own name.
     *
     * <p>A lambda rather than {@code RewardDisplay::ofItem}, because the method reference does not
     * fit: {@code ofItem} takes an {@link ItemRef} and what arrives is an {@code ItemReward}. The
     * compiler says "invalid method reference", which is accurate and does not say why — the two
     * types read as interchangeable at a glance because one wraps the other.
     */
    public static final java.util.function.Function<ItemReward, RewardDisplay> DISPLAY =
            reward -> RewardDisplay.ofItem(reward.item());
}
