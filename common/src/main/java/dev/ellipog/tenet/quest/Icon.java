package dev.ellipog.tenet.quest;

import com.mojang.datafixers.util.Pair;
import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.DynamicOps;

import net.minecraft.resources.ResourceLocation;

import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * What a quest, a chapter, a group, the book, a task or a reward shows as its picture.
 *
 * <p>Four arms, and the JSON says which by naming one key:
 *
 * <pre>{@code
 * { "item": "minecraft:oak_log", "count": 1 }   // an item, with the legacy count and components
 * { "texture": "my_pack:textures/gui/emblem.png" } // a texture file, textures/ and .png included
 * { "sprite": "occultism:block/chalk_glyph/0" } // an atlas region, drawn from the atlas
 * { "entity": "minecraft:creeper" }             // an entity, drawn as its spawn egg
 * }</pre>
 *
 * <p>The item arm is exactly yesterday's {@code {"item", "count", "components"}} object, so every
 * file written before the other arms existed reads unchanged — and an {@code Icon} that was
 * decoded from one still encodes back to it. A texture is a file's path rather than an item's,
 * for the reason {@code ImageSource} gives: the two are different lookups that fail differently,
 * so which one is meant is written down rather than inferred from a suffix. A sprite is an
 * atlas region for the same reason: it resolves against the atlas, not the file tree, and a
 * region nothing holds draws the missing mark like an unheld file. An entity draws as
 * its spawn egg where one exists, because every surface that draws an icon already knows how to
 * draw a stack; an entity with no egg draws the missing-item mark with the entity's name, which
 * is the honest answer for a picture nobody can draw. The migration tool maps the legacy
 * {@code custom_icon} file strings onto texture, atlas-form strings onto sprite, and
 * {@code entity_face} onto entity.
 *
 * <p>Decoding asks which key is present and hands the whole object to that arm's own codec, so an
 * item icon is still validated exactly as it always was — including its components, whose error
 * sentences belong to {@code ItemRef} rather than to a second copy here. More than one arm key is
 * the item arm winning, because "item" is the key every old file carries and the new keys never
 * appear beside it except by an author's mistake; the validator warns naming the winner and the
 * ignored arms, because an error would skip the whole quest over a cosmetic field.
 */
public sealed interface Icon permits Icon.Item, Icon.Texture, Icon.Sprite, Icon.Entity {

    /** An item, with a count and data components — the only arm yesterday's files know. */
    record Item(ItemRef ref) implements Icon {

        public Item {
            Objects.requireNonNull(ref, "item arm needs its reference");
        }
    }

    /**
     * A texture file by its own path — {@code textures/} and {@code .png} included, exactly as an
     * image element writes it. Drawn stretched into the icon's box. A path nothing holds draws
     * nothing, like an image element's missing file.
     */
    record Texture(ResourceLocation texture) implements Icon {

        public Texture {
            Objects.requireNonNull(texture, "texture arm needs its path");
        }
    }

    /**
     * An atlas region by its id — drawn from the atlas, like a chapter element's sprite.
     * A region nothing holds draws the missing mark, like an unheld file.
     */
    record Sprite(ResourceLocation sprite) implements Icon {

        public Sprite {
            Objects.requireNonNull(sprite, "sprite arm needs its id");
        }
    }

    /**
     * An entity by id — drawn as its spawn egg, which is a stack every icon surface already draws.
     * An entity with no egg (or from a mod that names its eggs unconventionally) draws the
     * missing-item mark, which names the entity rather than pretending it drew.
     */
    record Entity(ResourceLocation entity) implements Icon {

        public Entity {
            Objects.requireNonNull(entity, "entity arm needs its id");
        }
    }

    /** What an object that declares no icon means: the paper every icon surface fell back to. */
    Icon DEFAULT_ICON = new Item(ItemRef.DEFAULT_ICON);

    /**
     * The field names an icon contributes, for the validator to allow at an {@code icon} object.
     * The union of the item arm's fields and the new keys, so a texture path beside an item id
     * is the validator's question rather than the codec's — see the class note on which arm wins.
     */
    Set<String> FIELDS = Set.of("item", "count", "components", "texture", "sprite", "entity");

    Codec<Icon> CODEC = new Codec<>() {
        @Override
        public <T> DataResult<T> encode(Icon icon, DynamicOps<T> ops, T prefix) {
            return switch (icon) {
                case Item item -> ItemRef.CODEC.encode(item.ref(), ops, prefix);
                case Texture texture -> ops.mapBuilder()
                        .add("texture", ResourceLocation.CODEC.encodeStart(ops, texture.texture()))
                        .build(prefix);
                case Sprite sprite -> ops.mapBuilder()
                        .add("sprite", ResourceLocation.CODEC.encodeStart(ops, sprite.sprite()))
                        .build(prefix);
                case Entity entity -> ops.mapBuilder()
                        .add("entity", ResourceLocation.CODEC.encodeStart(ops, entity.entity()))
                        .build(prefix);
            };
        }

        @Override
        public <T> DataResult<Pair<Icon, T>> decode(DynamicOps<T> ops, T input) {
            Optional<T> item = ops.get(input, "item").result();
            if (item.isPresent()) {
                // The whole object goes to the item arm, so its count, its components and their
                // error sentences are ItemRef's own — yesterday's files decode exactly as before.
                return ItemRef.CODEC.decode(ops, input).map(pair -> pair.mapFirst(Item::new));
            }
            Optional<T> texture = ops.get(input, "texture").result();
            if (texture.isPresent()) {
                return ResourceLocation.CODEC.decode(ops, texture.get())
                        .map(pair -> pair.mapFirst(id -> (Icon) new Texture(id))
                                .mapSecond(rest -> input));
            }
            Optional<T> sprite = ops.get(input, "sprite").result();
            if (sprite.isPresent()) {
                return ResourceLocation.CODEC.decode(ops, sprite.get())
                        .map(pair -> pair.mapFirst(id -> (Icon) new Sprite(id))
                                .mapSecond(rest -> input));
            }
            Optional<T> entity = ops.get(input, "entity").result();
            if (entity.isPresent()) {
                return ResourceLocation.CODEC.decode(ops, entity.get())
                        .map(pair -> pair.mapFirst(id -> (Icon) new Entity(id))
                                .mapSecond(rest -> input));
            }
            return DataResult.error(() -> "expected one of \"item\", \"texture\", \"sprite\" or \"entity\"");
        }
    };

    /** The item arm's reference, or empty for a texture, a sprite or an entity. */
    default Optional<ItemRef> asItem() {
        return this instanceof Item item ? Optional.of(item.ref()) : Optional.empty();
    }

    /** For messages: the item's own words, or the texture path, sprite id or entity id. */
    default String describe() {
        return switch (this) {
            case Item item -> item.ref().describe();
            case Texture texture -> texture.texture().toString();
            case Sprite sprite -> sprite.sprite().toString();
            case Entity entity -> entity.entity().toString();
        };
    }

    /** Which arm this is, for the wire: absent means the item arm, which is every old file. */
    default String wireKind() {
        return switch (this) {
            case Item ignored -> "";
            case Texture ignored -> "texture";
            case Sprite ignored -> "sprite";
            case Entity ignored -> "entity";
        };
    }

    /** The id the wire carries beside the kind: the item, the texture path, the sprite or the entity. */
    default String wireId() {
        return switch (this) {
            case Item item -> item.ref().item().toString();
            case Texture texture -> texture.texture().toString();
            case Sprite sprite -> sprite.sprite().toString();
            case Entity entity -> entity.entity().toString();
        };
    }
}
