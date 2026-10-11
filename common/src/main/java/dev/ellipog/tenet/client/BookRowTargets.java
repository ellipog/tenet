package dev.ellipog.tenet.client;

import dev.ellipog.tenet.client.viewer.RecipeLookups;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/**
 * Which task and reward rows can send the player to a recipe viewer, and with what.
 *
 * <h2>Why this is not decided in the screen</h2>
 *
 * <p>The rule is the feature: an item task and an item reward have something to look up, a tag task
 * has a tag, a break task has its block's item form, and every other row — xp, stage, command,
 * checkmark, a break by block tag, or an item the client cannot resolve — has nothing. A screen cannot be instantiated by a test, so a rule that lives only inside
 * one is a rule that is verified by playing; this is the same split {@code SidebarLayout},
 * {@code ItemPicker} and {@code BookGeometry} already make, and for the same reason.
 */
public final class BookRowTargets {

    private BookRowTargets() {
    }

    /**
     * The target for a task row, or null when pressing it should do nothing.
     *
     * <p>A tag task's target is always its tag — even when the author overrode the picture with an
     * item. The picture is what the row draws ({@code TaskEntry#shown}); the tag is what it needs,
     * and opening the picture's recipes instead of the tag's is the bug this order exists for. An
     * author item or egg travels in {@code picture} since version 21, so a new tree never has both,
     * but an old one does, and the tag must win there too.
     *
     * <p>A tag id that does not parse is treated as no target rather than as an error: the row still
     * draws (its id is the label), and a press on it must not throw over a malformed id the server
     * already accepted.
     */
    public static RecipeLookups.Target ofTask(ClientQuestCache.TaskEntry task) {
        if (!task.tagId().isEmpty()) {
            ResourceLocation id = ResourceLocation.tryParse(task.tagId());
            if (id != null) {
                return RecipeLookups.Target.ofTag(TagKey.create(Registries.ITEM, id));
            }
            return null;
        }
        if (!task.block().isEmpty()) {
            // A break task names a block, not an item: the row draws its sentence, and the
            // lookup opens recipes for the block's item form where one exists. A block with
            // no item form opens nothing, like a text row with nothing to look up.
            ResourceLocation id = ResourceLocation.tryParse(task.block());
            Item item = id == null ? null : BuiltInRegistries.ITEM.getOptional(id).orElse(null);
            return item == null ? null : RecipeLookups.Target.of(new ItemStack(item));
        }
        // A break task's block tag opens nothing: a block tag is not an item tag, so there
        // is no tag target that means it, and opening one member's recipes would claim the
        // quest is about that member. The forward direction still finds the quest from any
        // member's item — see the viewer index — like a kill row, which is looked up never.
        if (!task.blockTag().isEmpty()) {
            return null;
        }
        if (task.hasItem()) {
            return RecipeLookups.Target.of(task.item());
        }
        return null;
    }

    /** The target for a reward row: an item reward, or nothing. Rewards carry no tags. */
    public static RecipeLookups.Target ofReward(ClientQuestCache.RewardEntry reward) {
        return reward.hasItem() ? RecipeLookups.Target.of(reward.item()) : null;
    }
}
