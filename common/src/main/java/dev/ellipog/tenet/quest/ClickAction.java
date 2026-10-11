package dev.ellipog.tenet.quest;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import dev.ellipog.armature.api.data.Codecs;

import java.util.Set;

/**
 * What pressing an element does.
 *
 * <h2>The seven names are the legacy set</h2>
 *
 * <p>{@code click_action} in the legacy format is one string, {@code "<type>:<data>"}, whose type is one of seven.
 * All seven are readable here, and all seven can be run: {@link Type#NONE}, {@link Type#OPEN_QUEST},
 * {@link Type#OPEN_URI}, {@link Type#SHOW_RECIPE}, {@link Type#SHOW_DOCS}, {@link Type#RUN_COMMAND} and
 * {@link Type#CUSTOM_EVENT}. The last two run on the server — a command through its dispatcher, an event
 * through the script bus — which is why the press crosses the wire as identity rather than as words.
 *
 * <p>Reading all seven is what makes a bad name reportable and precise: the value survives the decode, so
 * an unknown type is refused naming the seven there are — which tells an author their file came
 * from somewhere that meant something, and not what to do about it.
 *
 * <p>The honest alternative — keep the field, draw nothing when it is pressed — is rejected on the same
 * grounds T6 of the migration plan rejects it: a click that does nothing reads as a bug in the mod rather
 * than as a gap in it, and the author never finds out.
 */
public record ClickAction(Type type, String data) {

    /** Every key a click may carry. For the validator's unknown-field check. */
    public static final Set<String> FIELDS = Set.of("type", "data");

    /** The default: an element that is not pressable. */
    public static final ClickAction NONE = new ClickAction(Type.NONE, "");

    /** What a press can do. The names are the ones the file spells, lowercased. */
    public enum Type {

        /** Not pressable. */
        NONE,

        /** Open another quest, by id or alias. Implemented. */
        OPEN_QUEST,

        /** Open a URL in the browser. Implemented, and http/https only. */
        OPEN_URI,

        /** Run a command as the player, through the server's dispatcher at the pack's level. */
        RUN_COMMAND,

        /** Fire a script event, by id, through the script bus. */
        CUSTOM_EVENT,

        /** Show a recipe in a viewer. Needs a viewer installed; without one the press says so. */
        SHOW_RECIPE,

        /** Open a guide page. Degraded to a message: there is no docs integration to open with. */
        SHOW_DOCS;

        public static final Codec<Type> CODEC = Codecs.enumByName(Type.class);

        /**
         * Whether this build can actually run it.
         *
         * <p>A method rather than a list at the reporting site, so "which actions work" is one answer: the
         * validator, the editor's picker and the click dispatch all read this. True for every name today —
         * the set is pinned by test, so a future action that cannot run fails there rather than arriving
         * silently.
         */
        public boolean supported() {
            return true;
        }
    }

    public static final Codec<ClickAction> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            // Defaulted rather than required, so `"click": {}` is an element that is not pressable rather
            // than a file that will not load — the same reading every other optional field in this format
            // gets.
            Type.CODEC.optionalFieldOf("type", Type.NONE).forGetter(ClickAction::type),
            Codec.STRING.optionalFieldOf("data", "").forGetter(ClickAction::data)
    ).apply(instance, ClickAction::new));

    /** Whether this is the default, which is what an editor writes to remove the field. */
    public boolean isNone() {
        return type == Type.NONE;
    }

    /** Whether this build can run it. See {@link Type#supported()}. */
    public boolean supported() {
        return type.supported();
    }
}
