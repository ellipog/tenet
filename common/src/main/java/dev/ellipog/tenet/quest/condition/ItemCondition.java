package dev.ellipog.tenet.quest.condition;

import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import dev.ellipog.tenet.Tenet;
import dev.ellipog.tenet.quest.ItemRef;
import dev.ellipog.tenet.quest.task.ComponentMatch;
import dev.ellipog.tenet.quest.task.ItemCounting;

import net.minecraft.resources.Identifier;

import java.util.Set;
import java.util.function.Function;

/**
 * Have enough of an item.
 *
 * <pre>{@code { "type": "tenet:item", "item": "minecraft:iron_ingot", "count": 8 } }</pre>
 *
 * <p>The condition half of {@code tenet:item}, and the same matching: {@code match} defaults to
 * strict for the reason it does there — a file that says nothing means the whole stack — and the count
 * is read through the same helper the task uses, so a condition can never disagree with the task it
 * guards about how many the player has.
 */
public record ItemCondition(ItemRef item, ComponentMatch match) implements QuestCondition {

    public static final Identifier TYPE = Identifier.fromNamespaceAndPath(Tenet.MOD_ID, "item");

    public static final Set<String> FIELDS = Set.of("item", "count", "components", "match");

    public static final MapCodec<ItemCondition> MAP_CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
            ItemRef.MAP_CODEC.forGetter(ItemCondition::item),
            ComponentMatch.CODEC.optionalFieldOf("match", ComponentMatch.STRICT).forGetter(ItemCondition::match)
    ).apply(instance, ItemCondition::new));

    @Override
    public Identifier type() {
        return TYPE;
    }

    public static final ConditionBehaviour<ItemCondition> BEHAVIOUR = (condition, context) -> {
        // The shared template: counting reads it, so the per-member per-task rebuild goes away.
        int have = ItemCounting.countIn(context.player().getInventory(), condition.item().template(),
                condition.match(), condition.item().count());
        return have >= condition.item().count();
    };

    public static final Function<ItemCondition, ConditionDisplay> DISPLAY =
            condition -> ConditionDisplay.ofItem(condition.item());
}
