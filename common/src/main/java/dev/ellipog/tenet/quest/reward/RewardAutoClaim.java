package dev.ellipog.tenet.quest.reward;

import com.mojang.serialization.Codec;

import dev.ellipog.armature.api.data.Codecs;

/**
 * Whether, and how loudly, a reward is handed over the moment its quest is completed.
 *
 * <p>Borrowed whole from the legacy format, including the names, because pack authors have these five words
 * in their fingers and a synonym would only be a translation table in somebody's head. The default
 * is not "on": a finished quest announcing a payout and handing it over in the same breath is a
 * choice an author makes, not one the mod makes for them.
 */
public enum RewardAutoClaim {

    /** Defer to the quest tree's own default. The state a reward that says nothing is in. */
    DEFAULT,

    /** Never automatic. The reward waits for a claim, which is what a claim button is for. */
    DISABLED,

    /** Granted on completion, with a notification. */
    ENABLED,

    /** Granted on completion, silently: no toast. */
    NO_TOAST,

    /** Granted on completion as silently as {@link #NO_TOAST}: the second name for that silence,
     * borrowed from the legacy format. */
    INVISIBLE;

    public static final Codec<RewardAutoClaim> CODEC = Codecs.enumByName(RewardAutoClaim.class);

    /**
     * The words a file writes, in the order these constants declare them.
     *
     * <p>What a cycling control offers and what a form lists. Both rings used to be hand-written lists of
     * <b>three</b> of these five, so the reward card's `auto` chip could not reach {@code no_toast} or
     * {@code invisible} — and a press on one of them, which a shipped example carries, rewrote it to
     * {@code default} because {@code indexOf} answered -1. Deriving them from the enum means a sixth mode
     * cannot leave a control behind again.
     */
    public static java.util.List<String> wireValues() {
        return java.util.Arrays.stream(values())
                .map(mode -> mode.name().toLowerCase(java.util.Locale.ROOT))
                .toList();
    }

    /**
     * The mode a file's spelling names, case-insensitively, or empty for a word this build does not
     * know.
     *
     * <p>Here rather than at a call site, because a caller that matched the names itself was a second
     * copy of this enum's vocabulary — three of the five, in the validator's automatic-mode warning.
     * The name a file writes is this enum's business, the same business {@link #CODEC} is in.
     */
    public static java.util.Optional<RewardAutoClaim> byName(String name) {
        if (name == null) {
            return java.util.Optional.empty();
        }
        for (RewardAutoClaim mode : values()) {
            if (mode.name().equalsIgnoreCase(name)) {
                return java.util.Optional.of(mode);
            }
        }
        return java.util.Optional.empty();
    }

    /** The mode in force, given the quest tree's own default. */
    public RewardAutoClaim resolved(RewardAutoClaim fileDefault) {
        return this == DEFAULT ? fileDefault : this;
    }

    /** Whether this mode hands the reward over without being asked. */
    public boolean automatic() {
        return this == ENABLED || this == NO_TOAST || this == INVISIBLE;
    }

    /** Whether this mode tells the player it happened. */
    public boolean notifies() {
        return this == ENABLED;
    }
}
