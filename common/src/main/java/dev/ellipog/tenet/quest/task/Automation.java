package dev.ellipog.tenet.quest.task;

import dev.ellipog.armature.api.ArmatureApi;

import net.minecraft.server.level.ServerPlayer;

/**
 * Whether a player is a fake one: a contraption's hands rather than a person's.
 *
 * <p>The question the break and kill tasks both ask before they count anything, and the event
 * handler asks before it does any work. Asked of the loader through Armature, because vanilla marks
 * nothing and each loader answers it its own way.
 */
public final class Automation {

    private Automation() {
    }

    /**
     * Whether {@code player} is a fake player.
     *
     * <p>The platform layer is not installed in headless unit tests, and there are no fake players
     * there, so an uninstalled platform reads as a person rather than throwing. The same shape as the
     * config-dir read: see {@code PinnedQuests}, which answers an unreadable platform the same way.
     */
    public static boolean isFakePlayer(ServerPlayer player) {
        try {
            return ArmatureApi.platform().isFakePlayer(player);
        }
        catch (RuntimeException e) {
            return false;
        }
    }

    /**
     * Whether an act by this player counts for a task with this flag: a person always does, and a
     * fake player only for a task that opts in. Pure, so a test can hold the whole decision table
     * without a loader.
     */
    public static boolean counts(boolean countFakePlayers, boolean fake) {
        return !fake || countFakePlayers;
    }
}
