package dev.ellipog.tenet.quest;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The naming rules, which are the loader's rules plus the filesystem's.
 *
 * <p>The underscore case is the one worth the test: it is a string {@code Checks.id} accepts and every
 * folder walk skips, so a card that allowed it would create content that loads as nothing and cannot be
 * selected, renamed or deleted from the very list that made it.
 */
@DisplayName("ChapterNaming")
class ChapterNamingTest {

    private static final List<String> TAKEN = List.of("first_steps", "shape_gallery");

    @Test
    @DisplayName("an id the loader would read back is accepted")
    void acceptsAReadableId() {
        assertNull(ChapterNaming.problemWith("new_chapter_2", TAKEN));
    }

    @Test
    @DisplayName("a leading underscore is refused, because the walk skips it")
    void refusesALeadingUnderscore() {
        assertNotNull(ChapterNaming.problemWith("_notes", TAKEN));
    }

    @Test
    @DisplayName("the loader's other limits are the same ones here")
    void refusesWhatTheLoaderRefuses() {
        assertNotNull(ChapterNaming.problemWith("", TAKEN), "empty");
        assertNotNull(ChapterNaming.problemWith("Upper", TAKEN), "capitals");
        assertNotNull(ChapterNaming.problemWith("with-dash", TAKEN), "a dash");
        assertNotNull(ChapterNaming.problemWith("a".repeat(65), TAKEN), "too long");
    }

    /**
     * Both tombstone spellings are refused, and the reason is the character rather than a suffix rule.
     *
     * <p>The assertion used to read "the deleted suffix", and it passed because of the dot — which is
     * the whole story: a name that cannot contain {@code .} cannot be spelled the way
     * {@code QuestFiles.isDeletedName} is spelled, so the character rule is the rule, and the case
     * worth keeping is that the <i>numbered</i> form is refused too.
     */
    @Test
    @DisplayName("an id cannot be spelled the way a tombstone is, either spelling")
    void refusesTheTombstoneSpellings() {
        assertNotNull(ChapterNaming.problemWith("gone.deleted", TAKEN), "the plain suffix");
        assertNotNull(ChapterNaming.problemWith("gone.deleted.2", TAKEN), "a numbered aside");
        assertNull(ChapterNaming.problemWith("gone_deleted", TAKEN),
                "an underscore spelling is a real id: only the suffix makes a tombstone");
    }

    @Test
    @DisplayName("an id already in use is refused, and says so")
    void refusesACollision() {
        String problem = ChapterNaming.problemWith("first_steps", TAKEN);
        assertNotNull(problem);
        assertEquals(true, problem.contains("already used"));
    }

    @Test
    @DisplayName("a suggestion is the first free name, which is what a duplicate opens on")
    void suggestsAFreeName() {
        assertEquals("one_copy", ChapterNaming.suggested("one", "_copy", List.of()));
        assertEquals("one_copy2", ChapterNaming.suggested("one", "_copy", List.of("one_copy")));
        assertEquals("one_copy3", ChapterNaming.suggested("one", "_copy",
                List.of("one_copy", "one_copy2")));
    }

    @Test
    @DisplayName("a suggestion from a base at the limit still fits the rule, and still counts")
    void suggestedAlwaysFitsTheIdRule() {
        // **Why the base is what gets shortened.** Appending `_copy` to a 64-character id gave a name the
        // validator refuses, so duplicating a chapter with a long id was refused with a sentence about a
        // chapter the author never named -- and a card that pre-fills one opens on a name it marks invalid.
        // Shortening the *finished* candidate instead is worse than useless: the base swallows the suffix,
        // every counter returns the same string, and the search for a free name never ends.
        String long_ = "a".repeat(ChapterNaming.MAX_LENGTH);
        String first = ChapterNaming.suggested(long_, "_copy", List.of());
        assertEquals(ChapterNaming.MAX_LENGTH, first.length(), first);
        assertNull(ChapterNaming.problemWith(first), first);

        String second = ChapterNaming.suggested(long_, "_copy", List.of(first));
        assertTrue(second.length() <= ChapterNaming.MAX_LENGTH, second);
        assertNotEquals(first, second, "a second candidate is a different name, not the first truncated");
        assertNull(ChapterNaming.problemWith(second), second);
    }

    @Test
    @DisplayName("a title slugs to the id it spells, and the id is always usable")
    void slugsATitleToAnId() {
        assertEquals("first_steps", ChapterNaming.slug("First Steps"));
        assertEquals("my_new_chapter_", ChapterNaming.slug("My New Chapter "),
                "edges the alphabet cannot spell are stripped, the rest is literal");
        assertEquals("", ChapterNaming.slug("... !!! ..."),
                "a title with nothing spellable slugs to nothing, which is the fallback's cue");
        assertEquals("", ChapterNaming.slug(""), "empty in, empty out");
        assertEquals("", ChapterNaming.slug(null), "null in, empty out");
        // Whatever comes out must satisfy the rule on its own: the inline create sends it
        // without asking again.
        for (String title : List.of("Fresh Start", " déjà vu! ", "  padded  ", "UPPER lower 123",
                "a".repeat(200))) {
            String slugged = ChapterNaming.slug(title);
            if (!slugged.isEmpty()) {
                assertNull(ChapterNaming.problemWith(slugged, TAKEN), title + " -> " + slugged);
            }
        }
    }
}
