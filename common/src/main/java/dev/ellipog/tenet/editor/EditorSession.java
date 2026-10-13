package dev.ellipog.tenet.editor;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * What the canvas remembers until the server's answer arrives: positions asked for and not yet seen.
 *
 * <h2>One exception with an expiry, and this is it</h2>
 *
 * <p>The canvas draws the tree the server sent, always — that is the whole of "the server is the authority".
 * The one thing that cannot wait for a round trip is a node the author has just dragged: the drop has to land
 * under the pointer rather than snap back for a tick and then move. So a position is remembered here, keyed by
 * quest, and forgotten the moment a tree arrives whose revision has moved — after which the files' own answer
 * is the current one and nothing is pending.
 *
 * <h2>What is no longer here</h2>
 *
 * <p>This held opened {@code QuestEditor}s as well, because the client used to edit chapters itself when it
 * was the host. It does not any more: the client sends operations and the server owns the files, so a client
 * on a dedicated server — where the files are not — has nothing to open. What is left is the pending
 * positions, which are a fact about this client's screen and nobody else's.
 */
public final class EditorSession {

    /** Positions the files have but the server has not sent yet: id to {x, y}. */
    private final Map<String, double[]> moved = new LinkedHashMap<>();

    /** The tree revision the pending positions were recorded at, or -1 when there are none. */
    private long movedAtRevision = -1;

    /**
     * Bumped whenever the positions this session answers can have changed.
     *
     * <h2>Why a reader needs this and {@code movedAtRevision} is not it</h2>
     *
     * <p>Because {@code movedAtRevision} is the *tree's* revision, recorded beside the position — it does
     * not move while an author drags a node, which is exactly when these positions change fastest. A cache
     * keyed on the tree revision alone would therefore hand back a node's old position for the whole of a
     * drag. This is the honest signal for "ask again": it moves on every recorded position and on the
     * clear, and nothing else in the client has to know how the positions are stored.
     */
    private long epoch;

    /** How many times the pending positions have changed. See {@link #epoch}'s note. */
    public long epoch() {
        return epoch;
    }

    /**
     * Remembers where a node was moved to, until a tree arrives that says the same thing.
     *
     * <p>Reuses the slot rather than allocating per mousemove: a drag reports every motion event,
     * and a fresh array per event is pure churn for coordinates that land in the same two doubles.
     * Exact values, not packed — a packed long would quantise sub-pixel positions that the files
     * then keep, and there is nothing to gain by rounding what nobody asked to round.
     */
    public void moved(String id, double x, double y, long revision) {
        double[] at = moved.get(id);
        if (at == null) {
            at = new double[2];
            moved.put(id, at);
        }
        at[0] = x;
        at[1] = y;
        movedAtRevision = revision;
        epoch++;
    }

    public boolean hasMoved(String id) {
        return moved.containsKey(id);
    }

    public double movedX(String id) {
        double[] at = moved.get(id);
        return at == null ? 0 : at[0];
    }

    public double movedY(String id) {
        double[] at = moved.get(id);
        return at == null ? 0 : at[1];
    }

    /**
     * Forgets the pending positions once the server has sent a tree that is newer than they are.
     *
     * <h2>Why the clear is wholesale, and why that is not the (0,0) fault</h2>
     *
     * <p>It was tempting to make this selective — to drop only the positions the arriving tree disagrees
     * with and keep the rest — and it is wrong, which is worth recording because it looks like a strict
     * improvement. <b>What is stored here is "the server has not answered yet"</b>, and a revision that
     * moves means it <i>has</i> answered. A position kept past that point is a position the tree has already
     * refuted, drawn over the top of the server's own answer: the author sees their node where they left it
     * while the file says otherwise, which is the one thing this class exists to prevent. It is not merely
     * conservative — {@code nodeX} falls back to {@code movedX} whenever an id is present, with no further
     * check, so a kept entry is drawn.
     *
     * <p>So the clear stays. The (0,0) write it contributed to is fixed where it belongs: the release that
     * commits a multi-node drag now asks {@link #hasMoved} before reading a position, instead of trusting
     * that every id it remembers still has one. That guard is the whole fix, and it is the read path's own
     * rule — see {@code QuestBookScreen.nodeX}, which has always guarded this way.
     *
     * <p>Called with the cache's revision, every frame, because that is where the fact lives: the tree
     * arriving is what makes the files' own answer the current one, and nothing else in this class can see it.
     * A revision that has not moved leaves them alone, which is what keeps a node where the author put it
     * while they are still working.
     */
    public void onRevision(long revision) {
        if (movedAtRevision >= 0 && revision != movedAtRevision) {
            moved.clear();
            movedAtRevision = -1;
            // The clear is a change of answer like any other: without this, whatever the clear made the
            // positions *become* would answer from a cache that was stamped while they were still pending.
            epoch++;
        }
    }
}
