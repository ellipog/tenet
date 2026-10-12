package dev.ellipog.tenet.client;

import dev.ellipog.tenet.Constants;

/**
 * A tick counter for the client, without naming a client class.
 *
 * <h2>Why this is not {@code Minecraft.getInstance().gui.getGuiTicks()}</h2>
 *
 * <p>Because {@code Minecraft} is a client class and this package is loaded on both sides — a
 * dedicated server has no {@code Minecraft}, and naming it in a class the server loads is the exact
 * mistake the client/server split in this project exists to prevent. So the counter is owned here and
 * advanced by the loader's client entry point, which is a class only a client loads.
 *
 * <h2>What it is for</h2>
 *
 * <p>Counting a cooldown down on the client. The server sends "this quest has 600 ticks left"; the
 * client needs to subtract however many ticks have passed since, and for that it needs to know how
 * many ticks have passed. One number, incremented once per client tick.
 *
 * <p>It advances whether or not a world is loaded, which is fine — the only consumer is a screen, and
 * a screen with no quest data shows nothing regardless of what the counter says.
 */
public final class ClientTicker {

    private static volatile long ticks;

    private ClientTicker() {
    }

    /** Called once per client tick, by each loader's client entry point. */
    public static void advance() {
        ticks++;
    }

    /** How many client ticks have elapsed since the game started. */
    public static long ticks() {
        return ticks;
    }

    /** Resets to zero. For the moment a client disconnects, so a new world starts from a clean count. */
    public static void reset() {
        ticks = 0;
        Constants.LOG.debug("client tick counter reset");
    }
}
