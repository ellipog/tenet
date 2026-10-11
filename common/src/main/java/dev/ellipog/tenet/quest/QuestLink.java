package dev.ellipog.tenet.quest;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.JsonOps;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import dev.ellipog.armature.api.data.Codecs;

import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * A quest drawn on a chapter that is not one of its own: a marker pointing elsewhere.
 *
 * <pre>{@code
 * { "id": "gate_hint", "quest": "the_deep_descent", "x": 336, "y": -64 }
 * }</pre>
 *
 * <h2>What a link is, and the three things it is not</h2>
 *
 * <p>It is a <b>node that mirrors another quest</b>: it draws in the node layer with the target's
 * state and icon, and pressing it opens the target — including across chapters, where the book
 * switches to the chapter that holds it. It is not a quest (it holds no progress, is never
 * counted for its chapter's completion, and no dependency, gate or milestone may name it), it is
 * not a dependency (nothing unlocks through it, and the ghost check does not count it as a
 * dependant), and it is not a canvas element (elements draw <i>beneath</i> the nodes and hold no
 * state; a link draws <i>among</i> them and reads the target's). Those three negatives are the
 * property to keep in mind when a link wants a new field: a link with a gate is a quest, and a
 * link with a press action of its own is an element.
 *
 * <h2>Where the defaults come from</h2>
 *
 * <p>Legacy links carry only an id, a target and a position; shape and size are the <i>quest's</i>
 * own, read from the target at draw time. Here they are link-local with the quest's own defaults
 * standing in — {@code rounded} and 48 pixels, clamped to the same 16..512 a quest node allows —
 * so a link that says nothing draws exactly as its target does, and one that says something is a
 * deliberate difference. The bounds live on {@link QuestLayout}, where the geometry they describe
 * lives, so the codec, the validator and the client cannot disagree about them.
 *
 * <h2>Ids live with the quests, not with the decorations</h2>
 *
 * <p>A link's id must not equal any quest id or alias the index knows, in any letter case: the
 * canvas addresses nodes by id, and a link sharing its target's name — the shape every converted
 * pack's hex ids take — would be a node that can never be addressed. Element ids are a separate
 * namespace the link does not join: elements are addressed by edit operations, links by the canvas,
 * and neither reaches through the other. The migration tool lowercases the legacy ids and maps
 * {@code linked_quest} onto {@code quest}; coordinates arrive as integers, scaled and
 * corner-based by the tool exactly as quest positions are.
 */
public record QuestLink(String id, QuestRef quest, int x, int y, QuestShape shape, int size) {

    /** Every key a link may carry. For the validator's unknown-field check. */
    public static final Set<String> FIELDS = Set.of("id", "quest", "x", "y", "shape", "size");

    public static final Codec<QuestLink> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Codec.STRING.fieldOf("id").forGetter(QuestLink::id),
            // A reference, so it is validated rather than resolved here: whether the quest it names
            // exists is a question about the whole tree, which this record cannot see.
            QuestRef.CODEC.fieldOf("quest").forGetter(QuestLink::quest),
            Codec.INT.optionalFieldOf("x", 0).forGetter(QuestLink::x),
            Codec.INT.optionalFieldOf("y", 0).forGetter(QuestLink::y),
            QuestShape.CODEC.optionalFieldOf("shape", QuestShape.ROUNDED).forGetter(QuestLink::shape),
            Codecs.clampedInt(QuestLayout.MIN_SIZE, QuestLayout.MAX_SIZE)
                    .optionalFieldOf("size", QuestLayout.DEFAULT_SIZE).forGetter(QuestLink::size)
    ).apply(instance, QuestLink::new));

    /**
     * This link moved by a delta.
     *
     * <p>Returned rather than mutated, because the records are immutable and an editor that mutated
     * in place would be one more thing the undo history has to know about — the same argument
     * {@link CanvasElement#translated} makes.
     */
    public QuestLink translated(int dx, int dy) {
        return new QuestLink(id, quest, x + dx, y + dy, shape, size);
    }

    /** One link as the JSON object a chapter file and the synced tree both carry. */
    public static JsonObject asJson(QuestLink link) {
        DataResult<JsonElement> encoded = CODEC.encodeStart(JsonOps.INSTANCE, link);
        JsonElement written = encoded.result().orElseThrow(
                () -> new IllegalStateException("a link did not encode: " + link));
        if (!written.isJsonObject()) {
            throw new IllegalStateException("a link encoded as something other than an object: "
                    + written);
        }
        return written.getAsJsonObject();
    }

    /** One link read back from the object a file or the tree carries, or empty when it will not read. */
    public static Optional<QuestLink> fromJson(JsonElement json) {
        return CODEC.parse(JsonOps.INSTANCE, json).result();
    }
}
