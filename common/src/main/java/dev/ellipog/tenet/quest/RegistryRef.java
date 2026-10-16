package dev.ellipog.tenet.quest;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;

import net.minecraft.resources.Identifier;

/**
 * A reference to a registry entry, or to a tag of them: {@code minecraft:plains} or
 * {@code #minecraft:is_forest}.
 *
 * <p>the legacy format writes the tag form into the same field with a leading {@code #} — a shape a
 * {@link Identifier} cannot hold, which is why this exists rather than a second field. The
 * codec round-trips the same spelling, so a file that says {@code "#minecraft:is_forest"} keeps
 * saying it.
 */
public record RegistryRef(Identifier id, boolean tag) {

    /**
     * The codec, and the one thing about it that matters is that it <b>never throws</b>.
     *
     * <h2>Why this is a {@code comapFlatMap} and not the {@code xmap} it was</h2>
     *
     * <p>It was {@code Codec.STRING.xmap(RegistryRef::parse, RegistryRef::wire)}, and {@link #parse}
     * throws for a string that is neither an id nor a tag. A {@code Decoder}'s {@code map} has no
     * try/catch — DFU does not catch what an {@code xmap} function throws — so the exception left the
     * codec as a raw throwable rather than a {@code DataResult} error.
     *
     * <p>Two callers then had no way to survive it, and both are reachable from an ordinary typo in a
     * quest file:
     *
     * <ul>
     *   <li>{@code QuestValidator.decodeEntry} asks a registered type's own codec whether its fields
     *       form a value. That call is inside the validator, so a malformed {@code biome} threw out of
     *       validation and out of {@code QuestLoader.load} — which has no try/catch either.</li>
     *   <li>{@code QuestLoader.decode} handles a {@code DataResult} error and has no try/catch, so the
     *       same typo escaped the loader the same way.</li>
     * </ul>
     *
     * <p>The only handler above either of them is {@code TenetQuests.loadOnServerStart}, which catches
     * at the top: the result was <b>the entire questline failing to load</b> — every chapter, in every
     * file — because one {@code "biome"} was misspelled. A codec that reports through its return type
     * costs the author that one file and one message with a line number, which is the whole reason this
     * project validates before it decodes.
     *
     * <p>{@link #parse} still throws, and deliberately: its other callers are this mod's own default
     * values, written as literals two lines away from it, where a throw is a bug in the code rather
     * than in somebody's file. The codec is the reader for a value that came from a file, so the codec
     * is the one that has to answer instead of throwing.
     */
    public static final Codec<RegistryRef> CODEC =
            Codec.STRING.comapFlatMap(RegistryRef::decode, RegistryRef::wire);

    /** The codec's own reader: a {@code DataResult}, so a malformed value is a message not a crash. */
    private static DataResult<RegistryRef> decode(String raw) {
        String text = raw == null ? "" : raw.trim();
        boolean tag = text.startsWith("#");
        Identifier id = Identifier.tryParse(tag ? text.substring(1) : text);
        if (id == null) {
            return DataResult.error(() -> "'" + raw + "' is not an id or a #tag");
        }
        return DataResult.success(new RegistryRef(id, tag));
    }

    /** Parses either spelling. A {@code #} prefix means a tag; no other syntax is accepted. */
    public static RegistryRef parse(String raw) {
        String text = raw == null ? "" : raw.trim();
        boolean tag = text.startsWith("#");
        Identifier id = Identifier.tryParse(tag ? text.substring(1) : text);
        if (id == null) {
            throw new IllegalArgumentException("'" + raw + "' is not an id or a #tag");
        }
        return new RegistryRef(id, tag);
    }

    /** The file's own spelling, so a parsed value writes back as it was read. */
    public String wire() {
        return tag ? "#" + id : id.toString();
    }

    @Override
    public String toString() {
        return wire();
    }
}
