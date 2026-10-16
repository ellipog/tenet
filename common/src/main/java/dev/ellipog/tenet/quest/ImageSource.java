package dev.ellipog.tenet.quest;

import com.mojang.datafixers.util.Either;
import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import net.minecraft.resources.Identifier;

import java.util.Set;

/**
 * Where a picture element's picture comes from: a file, or a region of a stitched sheet.
 *
 * <h2>Why two arms, and why this is not one field</h2>
 *
 * <p>Because they are two different lookups that fail differently, and both are in real use. A
 * {@link Texture} names a PNG by path, which the resource manager either has or does not — and an absent
 * file draws nothing at all. A {@link Sprite} names a region of a sheet the game assembled at load time,
 * and an id the sheet does not hold draws the game's missing-texture marker. Neither can stand in for the
 * other: a sprite has no path to give, and a file has no place in an atlas.
 *
 * <p>This is the shape the legacy format uses under one string, decided by whether the value ends in
 * {@code .png} — a rule that lives in its parser rather than in its files. Here the two are separate keys,
 * so which lookup an element asks for is written down rather than inferred, and the migration tool applies
 * the suffix rule once, in the open, where its report can name what it did.
 *
 * <h2>Exactly one key, and the validator is what says so</h2>
 *
 * <p>The codec reads the object left to right and takes {@code texture} if it is there, which means an
 * object carrying both silently loses its {@code sprite}. That is the loader's usual leniency — a codec
 * error would cost the author every quest in the chapter — and the check that a message can be attached
 * to belongs to {@link QuestValidator}, which reads the raw object and can name the line. An object
 * carrying neither is the one case the codec does refuse, because there is nothing to draw and no
 * sensible value to invent.
 */
public sealed interface ImageSource {

    /** Every key an image source may carry. For the validator's unknown-field check. */
    Set<String> FIELDS = Set.of("texture", "sprite");

    /** A PNG named by its own path, e.g. {@code atm:textures/questpics/logo.png}. */
    record Texture(Identifier file) implements ImageSource {

        static final MapCodec<Texture> MAP_CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
                Identifier.CODEC.fieldOf("texture").forGetter(Texture::file)
        ).apply(instance, Texture::new));

        static final Codec<Texture> CODEC = MAP_CODEC.codec();

        @Override
        public Identifier id() {
            return file;
        }
    }

    /** A region of the block atlas, e.g. {@code minecraft:block/sculk}. */
    record Sprite(Identifier sprite) implements ImageSource {

        static final MapCodec<Sprite> MAP_CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
                Identifier.CODEC.fieldOf("sprite").forGetter(Sprite::sprite)
        ).apply(instance, Sprite::new));

        static final Codec<Sprite> CODEC = MAP_CODEC.codec();

        @Override
        public Identifier id() {
            return sprite;
        }
    }

    /**
     * The union, written so the arm that encodes is the arm that was read.
     *
     * <p>{@code Codec.either} tries the file arm first, which is what makes an object carrying both read as
     * a file — see the class note for why that is the lenient direction to fail in, and which class reports
     * it properly.
     */
    Codec<ImageSource> CODEC = Codec.either(Texture.CODEC, Sprite.CODEC).xmap(
            either -> either.map(texture -> (ImageSource) texture, sprite -> (ImageSource) sprite),
            source -> source instanceof Sprite sprite
                    ? Either.right(sprite)
                    : Either.left((Texture) source));

    /** The id this source names, whichever arm it is. For a message, and for an editor's field. */
    Identifier id();
}
