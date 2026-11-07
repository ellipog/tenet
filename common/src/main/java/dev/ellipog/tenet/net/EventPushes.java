package dev.ellipog.tenet.net;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

/**
 * The teams a kill or a break moved since the last tick, told together on the next one.
 *
 * <h2>Why an event does not push on its own</h2>
 *
 * <p>Each counted kill and each counted break used to push progress the moment it was recorded. A
 * push is a resolve of the quest graph and a delta over every quest for every online member, and
 * bursts are what these events come in: a sword sweep through a mob grinder, a vein miner's
 * sixty-four blocks, a quarry. Ten counted kills in one tick built ten complete pushes for the same
 * team, and the client threw nine of them away.
 *
 * <p>So the events add their owners here, and the player tick hands them to the push it already
 * makes for the engine's own changes. A burst costs one push per team, and a team the tick moved too
 * is told once. The cost is latency: a client hears about a kill at the next player tick rather than
 * at once, at most one tick (50 ms) later on either loader. An event during the tick, a mob dying of
 * a sweep or in a trap, is told at the end of that tick on Fabric; a melee kill or a hand-mined block
 * arrives as a packet between ticks and is told on the next one. Nothing else moves: progress is
 * recorded, {@code TASK_COMPLETED} and {@code QUEST_STARTED} fire, and a completed quest's rewards go
 * out at the moment of the event, as before.
 *
 * <p>The owners are read for their members when the tick sends, so a member who leaves the team or the
 * server in between is not told by this push; the full sync that follows either change tells them.
 *
 * <p>Server thread only, like {@code QuestSync}'s snapshots.
 */
public final class EventPushes {

    private static final Set<UUID> PENDING = new LinkedHashSet<>();

    private EventPushes() {
    }

    /** Owners an event moved, to be told on the next player tick. */
    public static void add(Set<UUID> owners) {
        PENDING.addAll(owners);
    }

    /**
     * {@code changed} plus every pending owner, and nothing pending afterwards.
     *
     * <p>Returns {@code changed} itself when nothing is pending, which is almost every tick.
     */
    public static Set<UUID> drainWith(Set<UUID> changed) {
        if (PENDING.isEmpty()) {
            return changed;
        }
        Set<UUID> all = new LinkedHashSet<>(changed);
        all.addAll(PENDING);
        PENDING.clear();
        return all;
    }

    /** Forgets everything pending. The server's stop calls this, because the set outlives a world. */
    public static void clear() {
        PENDING.clear();
    }
}
