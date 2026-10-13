package dev.ellipog.tenet.quest.condition;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import dev.ellipog.tenet.Tenet;
import dev.ellipog.tenet.quest.task.ItemCounting;

import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;

import java.util.Set;
import java.util.function.Function;

/**
 * Have enough of any item in a tag.
 *
 * <pre>{@code { "type": "tenet:item_tag", "tag": "minecraft:logs", "count": 16 } }</pre>
 *
 * <p>A sibling of {@code tenet:item}, as the task is, and read through the same counting helper: a
 * tag is a different question from "this exact item", and the answer for "how many" must not be.
 */
public record ItemTagCondition(ResourceLocation tag, int count) implements QuestCondition {

    public static final ResourceLocation TYPE = ResourceLocation.fromNamespaceAndPath(Tenet.MOD_ID, "item_tag");

    public static final Set<String> FIELDS = Set.of("tag", "count");

    public static final MapCodec<ItemTagCondition> MAP_CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
            ResourceLocation.CODEC.fieldOf("tag").forGetter(ItemTagCondition::tag),
            Codec.intRange(1, 6400).optionalFieldOf("count", 1).forGetter(ItemTagCondition::count)
    ).apply(instance, ItemTagCondition::new));

    @Override
    public ResourceLocation type() {
        return TYPE;
    }

    public static final ConditionBehaviour<ItemTagCondition> BEHAVIOUR = (condition, context) -> {
        TagKey<Item> tag = dev.ellipog.tenet.quest.TagKeys.of(Registries.ITEM, condition.tag());
        return ItemCounting.countIn(context.player().getInventory(), tag, condition.count()) >= condition.count();
    };

    public static final Function<ItemTagCondition, ConditionDisplay> DISPLAY = condition -> {
        // The count rides the subject, unlike a task's row where it rides the progress chip: a condition
        // has no chip, and "Have #minecraft:logs" would lose the sixteen the moment it is translated.
        String subject = "#" + condition.tag()
                + (condition.count() > 1 ? " \u00d7" + condition.count() : "");
        return ConditionDisplay.ofTranslatableText("tenet.condition.item_tag", "Have " + subject, subject);
    };
}
