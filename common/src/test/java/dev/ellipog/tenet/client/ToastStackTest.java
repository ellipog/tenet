package dev.ellipog.tenet.client;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The toast stack: how long a sentence stays, how many stay, and in what order they come back.
 *
 * <h2>Why these, and not a drawn frame</h2>
 *
 * <p>Everything that can be wrong here is arithmetic: a message that never leaves, one that leaves before it
 * is read, a stack that grows without bound, or an order that makes the newest push the ones being read off
 * the top. The drawing is the screen's and needs a renderer; this does not.
 */
@DisplayName("the toast stack")
class ToastStackTest {

    private static final long LIFETIME = ToastStack.LIFETIME_MILLIS;

    @Test
    @DisplayName("a sentence arrives quickly, is full strength, then fades, then is gone")
    void oneLifetime() {
        ToastStack stack = new ToastStack();
        stack.add("Moved to position 2", false, 0L);

        List<ToastStack.Toast> fresh = stack.visible(0L);
        assertEquals(1, fresh.size(), "a sentence being said is visible even on its first frame");
        assertEquals(0F, fresh.get(0).alpha(0L), "arriving faint rather than blinking in");
        assertEquals(1F, fresh.get(0).alpha(ToastStack.FADE_IN_MILLIS),
                "and landed a blink later");
        assertEquals(1F, fresh.get(0).alpha((long) (LIFETIME * ToastStack.FADE_FROM)),
                "and stays there while it is being read");

        float midway = fresh.get(0).alpha((long) (LIFETIME * 0.85));
        assertTrue(midway > 0F && midway < 1F, "then fades rather than vanishing: " + midway);
        assertEquals(0F, fresh.get(0).alpha(LIFETIME), "and is gone at the end of its life");
        assertTrue(stack.visible(LIFETIME).isEmpty());
        assertTrue(stack.visible(999_999L).isEmpty(), "and stays gone");
    }

    @Test
    @DisplayName("the arrival climbs and lands: nothing on the first frame, settled after the fade-in")
    void arrival() {
        ToastStack stack = new ToastStack();
        stack.add("Moved to position 2", false, 0L);
        ToastStack.Toast toast = stack.visible(0L).get(0);

        assertEquals(0F, toast.arrival(0L, true), "nowhere on the frame it is said");
        float midway = toast.arrival(ToastStack.FADE_IN_MILLIS / 2, true);
        assertTrue(midway > 0F && midway < 1F, "climbing through the fade-in: " + midway);
        assertEquals(1F, toast.arrival(ToastStack.FADE_IN_MILLIS, true), "and landed after it");
        assertEquals(1F, toast.arrival(0L, false),
                "while with motion off there is no arrival to wait for");
    }

    @Test
    @DisplayName("with motion off the sentence does not fade: it is there, then it is gone")
    void motionOffDoesNotFade() {
        ToastStack stack = new ToastStack();
        stack.add("Moved to position 2", false, 0L);
        ToastStack.Toast toast = stack.visible(0L).get(0);

        assertEquals(1F, toast.alpha((long) (LIFETIME * 0.85), false),
                "a fade is movement, and the switch exists for people who cannot comfortably use it");
        assertEquals(0F, toast.alpha(LIFETIME, false), "and it still ends on time");
    }

    @Test
    @DisplayName("the expired are dropped, so the stack cannot grow for the life of the book")
    void expiredAreDropped() {
        ToastStack stack = new ToastStack();
        stack.add("one", false, 0L);
        assertEquals(1, stack.held());

        stack.expire(LIFETIME);
        assertEquals(0, stack.held(), "a tick after its time, it is not held any more");
    }

    @Test
    @DisplayName("at most four are held, and the oldest is the one that goes")
    void theOldestGoes() {
        ToastStack stack = new ToastStack();
        for (int i = 1; i <= ToastStack.MAX + 2; i++) {
            stack.add("message " + i, false, i);
        }

        assertEquals(ToastStack.MAX, stack.held(), "a stack taller than the card is a stack nobody reads");
        List<String> texts = stack.visible(ToastStack.MAX + 2L).stream()
                .map(ToastStack.Toast::text).toList();
        assertEquals(List.of("message 3", "message 4", "message 5", "message 6"), texts,
                "oldest first, and the two oldest are the two that went");
    }

    @Test
    @DisplayName("an empty sentence is not said, and the error flag rides with it")
    void nothingEmptysSaid() {
        ToastStack stack = new ToastStack();
        stack.add("", false, 0L);
        stack.add("   ", false, 0L);
        stack.add(null, false, 0L);
        assertEquals(0, stack.held(), "a blank message is not a message");

        stack.add("That field cannot be cleared", true, 0L);
        assertTrue(stack.visible(0L).get(0).error(), "and a refusal keeps its colour");
        assertFalse(stack.visible(0L).get(0).text().isBlank());
    }
}
