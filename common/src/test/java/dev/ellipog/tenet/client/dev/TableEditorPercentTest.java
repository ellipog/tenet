package dev.ellipog.tenet.client.dev;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * The table editor's one-decimal percent: the words, and that they are remembered.
 *
 * <p>The formatting is the formatter's — rounding included — so what is pinned here is that the
 * memo answers the same string, and that a second ask for the same fraction is the same object
 * rather than a second formatter.
 */
@DisplayName("the table editor's percent")
class TableEditorPercentTest {

    @Test
    @DisplayName("one decimal, clamped to the unit interval")
    void formatsOneDecimalClamped() {
        assertEquals("35.2%", TableEditorLayout.percent(0.352));
        assertEquals("100.0%", TableEditorLayout.percent(1.0));
        assertEquals("0.0%", TableEditorLayout.percent(0.0));
        assertEquals("100.0%", TableEditorLayout.percent(2.0), "over one clamps");
        assertEquals("0.0%", TableEditorLayout.percent(-0.5), "under zero clamps");
    }

    @Test
    @DisplayName("a repeated fraction is the same object, not a second formatter")
    void remembersFractions() {
        String first = TableEditorLayout.percent(0.25);
        assertEquals("25.0%", first);
        assertSame(first, TableEditorLayout.percent(0.25));
        // Keyed on the exact bits, not on the printed words: two fractions that print the same
        // are two entries with one spelling, which is a missed hit and never a wrong one.
        assertEquals("25.0%", TableEditorLayout.percent(0.2500001));
    }
}
