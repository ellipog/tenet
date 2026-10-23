package dev.ellipog.tenet.client.dev;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import dev.ellipog.tenet.client.DevMode;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * One quest link's form: the rows the Chapter tab shows for the selected marker.
 */
class LinkPanelLayoutTest {

    /**
     * The depth is a static client preference, so it is set rather than assumed — and put back afterwards.
     *
     * <p>The cut has its own case below, which sets the other depth and puts it back; leaving this one set
     * would make <i>this</i> class the one that breaks somebody else's expectation.
     */
    private boolean wasAdvanced;

    @BeforeEach
    void fullDepth() {
        wasAdvanced = Advanced.on();
        DevMode.setAdvanced(true);
    }

    @AfterEach
    void restoreDepth() {
        DevMode.setAdvanced(wasAdvanced);
    }

    private static JsonObject link(String json) {
        return JsonParser.parseString(json).getAsJsonObject();
    }

    private static JsonObject marker() {
        return link("{ \"id\": \"gate_hint\", \"quest\": \"the_deep\", \"x\": 336, \"y\": -64,"
                + " \"shape\": \"hexagon\", \"size\": 64 }");
    }

    /** Every row key of a list, in order — a pair row contributing both halves. */
    private static List<String> keys(List<ToolsLayout.Action> rows) {
        List<String> out = new ArrayList<>();
        for (ToolsLayout.Action row : rows) {
            out.add(row.key());
            if (row.kind() == ToolsLayout.Action.Kind.PAIR && row.right() != null) {
                out.add(row.right().key());
            }
        }
        return out;
    }

    private static ToolsLayout.Action rowOf(List<ToolsLayout.Action> rows, String key) {
        for (ToolsLayout.Action row : rows) {
            if (key.equals(row.key())) {
                return row;
            }
        }
        throw new AssertionError("no row " + key + " in " + keys(rows));
    }

    @Test
    @DisplayName("a row per field of the link, keyed link.<id>.<field>, under a heading naming it")
    void theRowsAreKeyedByLinkAndField() {
        List<ToolsLayout.Action> rows = LinkPanelLayout.rows(marker());

        assertEquals(ChapterPanelLayout.LINK_PREFIX + "gate_hint", rows.get(0).key(),
                "the first row is the form saying what it is about");
        assertEquals(ToolsLayout.Action.Kind.HEADING, rows.get(0).kind());

        List<String> keys = keys(rows);
        assertTrue(keys.contains("link.gate_hint.quest"), keys.toString());
        assertTrue(keys.contains("link.gate_hint.pick"), "the canvas pick is a row: " + keys);
        assertTrue(keys.contains("link.gate_hint.jump"), "and so is the jump: " + keys);
        assertTrue(keys.contains("link.gate_hint.x") && keys.contains("link.gate_hint.y"),
                "one pair row for both coordinates: " + keys);
        assertTrue(keys.contains("link.gate_hint.shape"), keys.toString());
        assertTrue(keys.contains("link.gate_hint.size"), keys.toString());
        assertTrue(keys.contains("link.gate_hint.delete"), "and the way out: " + keys);

        // And every key splits back into the link and the field it names, which is what the screen's commit
        // routing relies on -- a key that did not would be a row that writes nowhere.
        for (String key : keys) {
            if (key.equals(ChapterPanelLayout.LINK_PREFIX + "gate_hint")
                    || key.endsWith(".#target") || key.endsWith(".#place") || key.endsWith(".#danger")) {
                continue;
            }
            String[] field = ChapterPanelLayout.linkFieldOf(key);
            assertEquals("gate_hint", field[0], key);
            assertFalse(field[1].isBlank(), key);
        }
    }

    @Test
    @DisplayName("the target row carries the quest it points at, and the actions under it say only their names")
    void theTargetTravels() {
        assertEquals("the_deep",
                rowOf(LinkPanelLayout.rows(marker()), "link.gate_hint.quest").value());
        // The target is the field's to show, one row up: the two buttons print their own names and
        // nothing else, so they carry no label band to print it a second time.
        assertTrue(rowOf(LinkPanelLayout.rows(marker()), "link.gate_hint.pick").labelOnButton(),
                "the pick is a button that says its own name");
        assertTrue(rowOf(LinkPanelLayout.rows(marker()), "link.gate_hint.jump").labelOnButton(),
                "and so is the jump");
    }

    @Test
    @DisplayName("the shape menu offers the model's own vocabulary, and the file's word is among it")
    void theShapeMenuMatchesTheModel() {
        assertEquals(
                dev.ellipog.tenet.quest.QuestShape.values().length,
                LinkPanelLayout.valuesOf("shape").size(),
                "a shape the model adds is offered without anybody remembering to add it twice");
        assertTrue(LinkPanelLayout.valuesOf("shape").contains("hexagon"));
        assertTrue(LinkPanelLayout.valuesOf("text").isEmpty(), "a word has no closed set");
        assertEquals("Hexagon", LinkPanelLayout.valueName("hexagon"));

        JsonObject bare = link("{ \"id\": \"l\", \"quest\": \"a\" }");
        assertEquals("rounded", LinkPanelLayout.effectiveValueOf(bare, "shape"),
                "an absent shape is the codec's default, which is what the canvas draws");
        assertEquals(48.0, LinkPanelLayout.numberOf(bare, "size"),
                "and an absent size is the target's own");
    }

    @Test
    @DisplayName("every number the form offers has a range, and every field has hover help")
    void numbersHaveRangesAndFieldsHaveHelp() {
        for (String field : List.of("x", "y", "size")) {
            assertTrue(LinkPanelLayout.rangeOf(field) != null, field + " has no range");
        }
        assertEquals(336.0, LinkPanelLayout.numberOf(marker(), "x"));
        assertEquals(-64.0, LinkPanelLayout.numberOf(marker(), "y"),
                "a negative corner is a number too");
        assertEquals(64.0, LinkPanelLayout.numberOf(marker(), "size"));
        assertNull(LinkPanelLayout.rangeOf("quest"), "a word is not a number");
        for (String field : List.of("quest", "pick", "jump", "x", "y", "shape", "size", "delete",
                "#target", "#place", "#danger")) {
            assertTrue(LinkPanelLayout.help(field) != null, field + " has no hover sentence");
        }
        assertNull(LinkPanelLayout.help("tint"), "a link has no tint to explain");
        assertNull(LinkPanelLayout.help("no_such_field"), "and an unknown field says nothing");
    }

    @Test
    @DisplayName("the depth cut hides a link's refinements, and never its target or position")
    void theDepthCutHidesRefinements() {
        boolean was = Advanced.on();
        try {
            DevMode.setAdvanced(false);
            List<String> shallow = keys(LinkPanelLayout.rows(marker()));
            assertTrue(shallow.contains("link.gate_hint.quest"), "what it points at stays");
            assertTrue(shallow.contains("link.gate_hint.x"), "and where it sits stays");
            assertTrue(shallow.contains("link.gate_hint.pick"), "and the way to point it stays");
            assertFalse(shallow.contains("link.gate_hint.shape"), "how it is drawn goes");
            assertFalse(shallow.contains("link.gate_hint.size"), "with its size");
        }
        finally {
            DevMode.setAdvanced(was);
        }
    }

    @Test
    @DisplayName("a flag row is not a thing this form has, because a link has no flags")
    void noFlagsNoSwitches() {
        for (ToolsLayout.Action row : LinkPanelLayout.rows(marker())) {
            assertFalse(row.kind() == ToolsLayout.Action.Kind.SWITCH,
                    "a switch with nothing to switch: " + row.key());
        }
    }
}
