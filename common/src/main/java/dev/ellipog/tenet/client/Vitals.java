package dev.ellipog.tenet.client;

import dev.ellipog.tenet.QuestAuthority;

import net.minecraft.client.Minecraft;

/**
 * Whether this client draws the vitals overlay — the frame rate and the frame's counters.
 *
 * <h2>Why it is no longer part of edit mode</h2>
 *
 * <p>It used to be: the frame rate appeared because a developer had the tools on, which meant the one number
 * you want when the client feels slow was only available to somebody who was also able to edit the
 * questline — and it vanished the moment they left edit mode to play. The two are different questions. Edit
 * mode asks "may this player change the files"; this asks "does this player want to watch the frame".
 *
 * <p><b>The counters came with it, and they were the other half of the fault.</b> They are the ten lines
 * under the frame rate, and they were drawn whenever the tools were on — which meant a counting renderer,
 * and its per-drawing-call measurement, was built for every author whether or not they were measuring
 * anything. One switch draws the whole overlay now: edit mode draws none of it and counts none of it, and
 * `/tenet vitals` draws the frame rate and the counters together.
 *
 * <h2>And why it is still gated</h2>
 *
 * <p>Because it is an instrument, and an instrument that any player can switch on is a way to see the
 * server's numbers and the client's cost while claiming not to. The switch is an operator's:
 * {@code /tenet vitals} is refused for anybody below {@link QuestAuthority#EDIT_LEVEL}, and this class
 * checks the same level again on the client, every frame, before anything is drawn. The second check is not
 * redundant — it is what makes the overlay disappear if the player is de-opped while it is up, without the
 * server having to track who it told.
 *
 * <p>Session-only on purpose: nothing is written to disk, so a restart forgets. A preference that outlives
 * the session wants a place in {@code client.json} beside the other client settings, which is a change to
 * that file's schema rather than to this flag.
 */
public final class Vitals {

    private static volatile boolean on;

    private Vitals() {
    }

    /** Whether this player asked for the overlay. Says nothing about whether they may still have it. */
    public static boolean on() {
        return on;
    }

    /** What the server told this client. See {@link #showing} for the question the drawing asks. */
    public static void set(boolean value) {
        on = value;
    }

    /**
     * Whether the overlay should draw: asked for, **and** this player is still an operator.
     *
     * <p>Read once a frame by the screen's overlay, which is why it is cheap: two field reads and a
     * permission check that the client caches per player.
     */
    public static boolean showing() {
        if (!on) {
            return false;
        }
        return dev.ellipog.tenet.QuestAuthority.mayEditClient(Minecraft.getInstance());
    }
}
