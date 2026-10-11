package dev.ellipog.tenet.quest;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import dev.ellipog.armature.api.data.Codecs;

import java.util.Set;

/**
 * How a picture element's title is drawn on top of it.
 *
 * <h2>Two jobs for one piece of text</h2>
 *
 * <p>{@link CanvasElement.Image#title} is either a tooltip — what an author expects from a title — or words
 * painted into the picture, and this record is the switch between them plus the five things the legacy format lets an
 * author say about the second. So an image with no {@code label} has a hover title and nothing drawn on it,
 * which is the reading that needs no explanation.
 *
 * <h2>Why the alignment is a three-value vocabulary</h2>
 *
 * <p>Because that is what the legacy format has and what fits the job: {@code start}, {@code middle}, {@code end} against
 * the image's own box, with {@code inset} as the distance from the edge the alignment names. A nine-value
 * grid would be a superset nobody asked for, and a converter would have to decide what to do with the six
 * extra cells.
 */
public record ElementLabel(boolean onImage, boolean shadow, double inset, TextAlign hAlign,
                           TextAlign vAlign) {

    /** Every key a label may carry. For the validator's unknown-field check. */
    public static final Set<String> FIELDS = Set.of("onImage", "shadow", "inset", "hAlign", "vAlign");

    /**
     * The inset's bounds, in pixels, and they live here rather than in the codec.
     *
     * <p>The same argument {@link QuestLayout} makes for its own bounds: a range that the codec, the
     * validator and the editor each state separately is three chances for one of them to move. An inset
     * past the image's own size simply puts the words outside it, which is a number the author got wrong
     * rather than a file this build cannot read — so the codec clamps.
     */
    public static final double MIN_INSET = 0.0;
    public static final double MAX_INSET = 64.0;

    /** What every image has when it says nothing: a tooltip, one pixel in, centred. */
    public static final ElementLabel DEFAULT =
            new ElementLabel(false, false, 1.0, TextAlign.MIDDLE, TextAlign.MIDDLE);

    /** Where text sits against the edge its axis names. */
    public enum TextAlign {

        /** Against the left or the top edge. */
        START,

        /** Centred on the axis. */
        MIDDLE,

        /** Against the right or the bottom edge. */
        END;

        public static final Codec<TextAlign> CODEC = Codecs.enumByName(TextAlign.class);
    }

    public static final Codec<ElementLabel> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Codec.BOOL.optionalFieldOf("onImage", false).forGetter(ElementLabel::onImage),
            Codec.BOOL.optionalFieldOf("shadow", false).forGetter(ElementLabel::shadow),
            Codecs.clampedDouble(MIN_INSET, MAX_INSET).optionalFieldOf("inset", 1.0)
                    .forGetter(ElementLabel::inset),
            TextAlign.CODEC.optionalFieldOf("hAlign", TextAlign.MIDDLE).forGetter(ElementLabel::hAlign),
            TextAlign.CODEC.optionalFieldOf("vAlign", TextAlign.MIDDLE).forGetter(ElementLabel::vAlign)
    ).apply(instance, ElementLabel::new));
}
