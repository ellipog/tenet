package dev.ellipog.tenet.client;

import java.util.ArrayList;
import java.util.List;

/**
 * A stack of transient sentences, and how long one of them stays.
 *
 * <h2>Why chat is not enough</h2>
 *
 * <p>Every sentence the book's screen produces -- an op refused, a row moved, a claim that found nothing --
 * went to chat. So the one place those messages matter most, the quest book itself, was the one place they
 * could not be read while it was open. A toast is the same sentence drawn over the book, gone after a few
 * seconds, and chat keeps its copy for a player who is not looking at the book.
 *
 * <h2>Its two readers, and why it moved out of {@code dev}</h2>
 *
 * <p>The book draws one of these, and so does the HUD's notice element: a player who is not looking at the
 * book is told the same sentence where they chose to have it. Both want the same lifetime, the same cap and
 * the same fade, so there is one class rather than two -- and it lives in {@code client} rather than
 * {@code client.dev}, because a package named for the author's tools is not where a player's notice belongs.
 *
 * <p>Game-free bookkeeping and nothing else: the caller supplies the time and measures its own text. The
 * order is oldest first, so the drawing can anchor the newest at the bottom and let the ones already being
 * read move up rather than jump.
 */
public final class ToastStack {

    /** How long a message stays, in milliseconds. Long enough to read a sentence, short enough to go. */
    public static final long LIFETIME_MILLIS = 4500;

    /**
     * How long a message takes to arrive, in milliseconds: a quick fade rather than a blink.
     *
     * <p>Short enough to read as immediate — the sentence is there while the eye finds it — and long
     * enough that a stack of them does not strobe when several land on nearby frames. The mirror of
     * the fade-out below: one eases in, the other eases out, and neither is the pop the stack had.
     */
    public static final long FADE_IN_MILLIS = 120;

    /** How much of the life is spent at full strength before it starts to fade. */
    public static final float FADE_FROM = 0.7F;

    /** How many are kept at once. The oldest goes when one more arrives. */
    public static final int MAX = 4;

    /** One sentence, and when it was said. */
    public record Toast(String text, boolean error, long bornAt) {

        /**
         * How visible it is now: arriving over {@link #FADE_IN_MILLIS}, full until {@link #FADE_FROM}
         * of its life, then down to nothing.
         *
         * <p>The motion-aware form is the one the book draws with: with Motion off the sentence is
         * simply there and then gone, because a fade is movement and the switch exists for people who
         * cannot comfortably use it. The one-argument form keeps the old behaviour for callers with no
         * opinion -- tests, and the book's own future ones.
         */
        public float alpha(long now, boolean motion) {
            long age = Math.max(0L, now - bornAt);
            if (age >= LIFETIME_MILLIS) {
                return 0F;
            }
            if (!motion) {
                return 1F;
            }
            if (age <= FADE_IN_MILLIS) {
                return (float) age / FADE_IN_MILLIS;
            }
            float elapsed = (float) age / LIFETIME_MILLIS;
            return elapsed <= FADE_FROM ? 1F : 1F - (elapsed - FADE_FROM) / (1F - FADE_FROM);
        }

        /** {@link #alpha(long, boolean)} with motion on. */
        public float alpha(long now) {
            return alpha(now, true);
        }

        /**
         * How settled its arrival is now, 0 on the frame it is said and 1 once it has landed.
         *
         * <p>What the drawing rises by: a sentence that only faded would brighten in place, while one
         * that also climbs a few pixels reads as arriving. Motion off is landed at once, like the
         * alpha above.
         */
        public float arrival(long now, boolean motion) {
            if (!motion) {
                return 1F;
            }
            long age = Math.max(0L, now - bornAt);
            return Math.min(1F, (float) age / FADE_IN_MILLIS);
        }
    }

    private final List<Toast> toasts = new ArrayList<>();

    /**
     * Says something, now.
     *
     * <p>Bounded rather than unbounded: a stack that grew with every message would be a stack the drawing
     * has to walk for as long as the book is open, and a column of notices taller than the card is a column
     * nobody reads. The oldest goes -- it has had its time.
     */
    public void add(String text, boolean error, long now) {
        if (text == null || text.isBlank()) {
            return;
        }
        toasts.add(new Toast(text, error, now));
        while (toasts.size() > MAX) {
            toasts.remove(0);
        }
    }

    /** Drops what has faded out. Called every tick; cheap when there is nothing to drop. */
    public void expire(long now) {
        toasts.removeIf(toast -> now - toast.bornAt() >= LIFETIME_MILLIS);
    }

    /**
     * Forgets everything, faded or not.
     *
     * <p>For a disconnect, and it is not the same act as {@link #expire}: a notice is about something that
     * just happened in <i>this</i> world, so a sentence still on screen when the next one is joined would be
     * the previous server's news. Expiring would leave that to a timer nobody is watching.
     */
    public void clear() {
        toasts.clear();
    }

    /**
     * What is still visible, oldest first.
     *
     * <p>By age rather than by alpha: a sentence on its first frame has an alpha of nothing and is
     * still a sentence being said, and the drawing decides how much ink that is worth. Faded-out is
     * the lifetime's end, which is the same condition {@link #expire} drops by.
     */
    public List<Toast> visible(long now) {
        List<Toast> out = new ArrayList<>();
        for (Toast toast : toasts) {
            if (now - toast.bornAt() < LIFETIME_MILLIS) {
                out.add(toast);
            }
        }
        return List.copyOf(out);
    }

    /** How many are held, faded or not. For the tests and for nothing else. */
    public int held() {
        return toasts.size();
    }
}
