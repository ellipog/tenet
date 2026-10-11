package dev.ellipog.tenet.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * The effective edit gate: edit mode, the operator permission, and the player's-view preview.
 *
 * <p>A static truth table rather than a screen, because the answer is pure inputs — no player, no
 * widgets, no client — and a screen cannot be instantiated by a test. The one row that matters is
 * the preview's: everything on with the latch lit still answers false, so previewing pauses editing
 * without touching the mode underneath it.
 */
@DisplayName("the effective edit gate")
class QuestBookScreenTest {

    @Test
    @DisplayName("edit mode and the permission edit, and the preview pauses them")
    void previewPausesEditing() {
        assertTrue(QuestBookScreen.editEffective(true, true, false),
                "edit mode on, operator, no preview: editing");
        assertFalse(QuestBookScreen.editEffective(true, true, true),
                "the same with the preview lit: paused, not editing");
    }

    @Test
    @DisplayName("every other row answers false however the preview is set")
    void everythingElseIsFalse() {
        for (boolean preview : new boolean[] {false, true}) {
            assertFalse(QuestBookScreen.editEffective(false, false, preview),
                    "no mode, no permission: a reader either way");
            assertFalse(QuestBookScreen.editEffective(false, true, preview),
                    "no mode: an operator with edit mode off is already seeing the player's book");
            assertFalse(QuestBookScreen.editEffective(true, false, preview),
                    "no permission: edit mode without the level the server checks is a reader");
        }
    }

    @AfterEach
    void forgetPreviewAsk() {
        // A parked static: a test that leaves one set changes the next book to open.
        QuestBookScreen.clearPreviewRequest();
    }

    @Test
    @DisplayName("a preview ask parks absolutely, and a toggle flips the parked ask")
    void previewAskParks() {        QuestBookScreen.requestPreview(false, true);
        assertEquals(true, QuestBookScreen.consumePreviewRequest().orElse(null),
                "an absolute ask parks as-is");
        assertTrue(QuestBookScreen.consumePreviewRequest().isEmpty(),
                "consumed once: the second call answers empty");

        QuestBookScreen.requestPreview(false, false);
        assertEquals(false, QuestBookScreen.consumePreviewRequest().orElse(null),
                "an ask for the editor parks too, so the command can leave the preview");

        // Toggles, with the book closed: no ask and an ask for the editor both flip to the
        // player's view, because a toggle outside the book is an operator asking to see it.
        QuestBookScreen.requestPreview(true, false);
        assertEquals(true, QuestBookScreen.consumePreviewRequest().orElse(null),
                "a toggle with nothing parked opens the player's view");
        QuestBookScreen.requestPreview(false, false);
        QuestBookScreen.requestPreview(true, false);
        assertEquals(true, QuestBookScreen.consumePreviewRequest().orElse(null),
                "and so does a toggle over an ask for the editor");

        QuestBookScreen.requestPreview(false, true);
        QuestBookScreen.requestPreview(true, false);
        assertEquals(false, QuestBookScreen.consumePreviewRequest().orElse(null),
                "while a toggle over a preview ask leaves it");
    }

    @Nested
    @DisplayName("the pin toggle")
    class PinToggle {

        @Test
        @DisplayName("pinning an open box flips the flag and moves nothing else")
        void pinningAnOpenBoxKeepsItOpen() {
            assertEquals(new QuestBookScreen.SidebarPinAfter(true, false, false),
                    QuestBookScreen.pinAfterToggle(false, false, false));
        }

        @Test
        @DisplayName("unpinning an open box flips the flag and moves nothing else")
        void unpinningAnOpenBoxKeepsItOpen() {
            assertEquals(new QuestBookScreen.SidebarPinAfter(false, false, false),
                    QuestBookScreen.pinAfterToggle(true, false, false));
        }

        @Test
        @DisplayName("pinning a hover preview promotes it to an open pinned box")
        void pinningAPeekPromotesIt() {
            // The report this locks: pinning the sliver's peek flipped the dot and changed
            // nothing, so leaving ended the preview either way and the pin read as dead.
            assertEquals(new QuestBookScreen.SidebarPinAfter(true, false, false),
                    QuestBookScreen.pinAfterToggle(false, true, true));
        }

        @Test
        @DisplayName("pinning a sliver expands it to an open pinned box")
        void pinningASliverExpandsIt() {
            // The keyboard road (Ctrl+B) reaches the pin with no preview up: "pin it open" is
            // the only reading that does anything, so a sliver becomes a box rather than a
            // pinned sliver the next hover would still fold away.
            assertEquals(new QuestBookScreen.SidebarPinAfter(true, false, false),
                    QuestBookScreen.pinAfterToggle(false, true, false));
        }

        @Test
        @DisplayName("unpinning anywhere else leaves the other two answers alone")
        void unpinningChangesOnlyTheFlag() {
            assertEquals(new QuestBookScreen.SidebarPinAfter(false, true, true),
                    QuestBookScreen.pinAfterToggle(true, true, true));
            assertEquals(new QuestBookScreen.SidebarPinAfter(false, true, false),
                    QuestBookScreen.pinAfterToggle(true, true, false));
        }
    }
}
