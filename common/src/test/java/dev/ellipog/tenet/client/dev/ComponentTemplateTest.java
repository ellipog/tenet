package dev.ellipog.tenet.client.dev;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * One component template: copies that cannot share ids, keys that read like quest ids, and
 * rows that say what they hold even for types this build has never heard of.
 */
@DisplayName("One component template")
class ComponentTemplateTest {

    private static JsonObject tree(String json) {
        return JsonParser.parseString(json).getAsJsonObject();
    }

    @Test
    @DisplayName("a copy out of the store shares nothing with its source")
    void copiesShareNoIdentity() {
        JsonObject source = tree("{\"type\": \"tenet:item\", \"id\": \"keep_me\","
                + " \"uuid\": \"also_me\", \"item\": \"minecraft:oak_log\", \"count\": 8}");
        ComponentTemplate.Template template = new ComponentTemplate.Template("gather", "Gather",
                ComponentTemplate.Kind.TASK, "tenet:item", source, ComponentTemplate.Source.USER, 0, 0, 0);

        JsonObject copy = template.freshCopy();
        assertFalse(copy.has("id"), "an applied copy must never share the source's id");
        assertFalse(copy.has("uuid"), "nor any uuid beside it");
        assertEquals("tenet:item", copy.get("type").getAsString());
        assertEquals(8, copy.get("count").getAsInt());

        copy.addProperty("count", 64);
        assertEquals(8, template.payload().get("count").getAsInt(),
                "editing the copy must not reach back into the template");
        assertEquals(8, source.get("count").getAsInt(),
                "and neither into the tree it was saved from");
    }

    @Test
    @DisplayName("a key reads like a quest id, and the ladder matches the elements'")
    void idsAreSlugsWithALadder() {
        assertEquals("gather_oak_logs", ComponentTemplate.slug("Gather Oak Logs!"));
        assertEquals("template", ComponentTemplate.slug("!!!"),
                "a name with nothing kept is still a key");
        assertEquals("gather", ComponentTemplate.uniqueId("gather", Set.of("other")));
        assertEquals("gather_2", ComponentTemplate.uniqueId("gather", Set.of("gather")));
        assertEquals("gather_3", ComponentTemplate.uniqueId("gather", Set.of("gather", "gather_2")));
    }

    @Test
    @DisplayName("rows say what they hold, in words")
    void blurbsReadAsSentences() {
        assertEquals("Gather 8 × Oak log", ComponentTemplate.blurb(ComponentTemplate.Kind.TASK,
                "tenet:item", tree("{\"item\": \"minecraft:oak_log\", \"count\": 8}")));
        assertEquals("Hand in 16 × Iron ingot", ComponentTemplate.blurb(ComponentTemplate.Kind.TASK,
                "tenet:item", tree("{\"item\": \"minecraft:iron_ingot\", \"count\": 16,"
                        + " \"consumeItems\": true}")));
        assertEquals("Defeat 10 × Zombie", ComponentTemplate.blurb(ComponentTemplate.Kind.TASK,
                "tenet:kill", tree("{\"entity\": \"minecraft:zombie\", \"value\": 10}")));
        assertEquals("Gives 1 × Iron sword", ComponentTemplate.blurb(ComponentTemplate.Kind.REWARD,
                "tenet:item", tree("{\"item\": \"minecraft:iron_sword\"}")));
        assertEquals("Gives 5 levels", ComponentTemplate.blurb(ComponentTemplate.Kind.REWARD,
                "tenet:xp", tree("{\"levels\": 5}")));
        assertEquals("Requires stage started", ComponentTemplate.blurb(ComponentTemplate.Kind.CONDITION,
                "tenet:stage", tree("{\"stage\": \"started\"}")));
    }

    @Test
    @DisplayName("an unknown type still gets a row, never an exception")
    void unknownTypesDegradeToTheirId() {
        JsonObject payload = tree("{\"type\": \"othermod:gizmo\", \"count\": \"not_a_number\"}");
        String blurb = ComponentTemplate.blurb(ComponentTemplate.Kind.TASK, "othermod:gizmo", payload);
        assertEquals("Gizmo", blurb);
        assertEquals("?", ComponentTemplate.prettify(null));
        assertEquals("?", ComponentTemplate.prettify("  "));
    }

    @Test
    @DisplayName("a blank name falls back to the blurb rather than an empty row")
    void blankNamesFallBack() {
        ComponentTemplate.Template template = new ComponentTemplate.Template("id", "  ",
                ComponentTemplate.Kind.TASK, "tenet:break",
                tree("{\"block\": \"minecraft:stone\", \"value\": 32}"),
                ComponentTemplate.Source.USER, 0, 0, 0);
        assertEquals("Break", template.name().substring(0, 5),
                "the prettified type id: " + template.name());
    }
}
