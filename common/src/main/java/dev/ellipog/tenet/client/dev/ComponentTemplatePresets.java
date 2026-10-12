package dev.ellipog.tenet.client.dev;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.ArrayList;
import java.util.List;

/**
 * The templates Tenet ships: the common few-types case, ready to apply.
 *
 * <h2>Why literals in code rather than files in the jar</h2>
 *
 * <p>For the reason {@code ElementDefaults} gives: a default is a claim worth asserting, and
 * inline JSON in a menu handler cannot be tested — but literals in <i>this</i> class can,
 * because {@code ComponentTemplatePresetsTest} decodes each one through its kind's codec the
 * way an apply does. A preset that stops decoding is a test failure, not a menu item that
 * writes a file the validator then refuses.
 *
 * <h2>Why these ten</h2>
 *
 * <p>They are the setups the worked example in {@code tools/quests} reaches for first: a
 * gather that counts itself, a hand-in that takes, a kill count, a break count, a dimension
 * visit, a bare agreement, a paid item, paid levels, a stage gate and a party-size gate. An
 * author who has never written a quest file gets all three component kinds in the first
 * screenful, and every one of them applies to any quest unchanged.
 */
public final class ComponentTemplatePresets {

    private ComponentTemplatePresets() {
    }

    /** Every shipped template, in the order the Templates page lists them. */
    public static List<ComponentTemplate.Template> builtIns() {
        List<ComponentTemplate.Template> out = new ArrayList<>();
        out.add(task("gather_oak_logs", "Gather oak logs",
                "{\"type\": \"tenet:item\", \"item\": \"minecraft:oak_log\", \"count\": 8}"));
        out.add(task("hand_in_iron", "Hand in iron",
                "{\"type\": \"tenet:item\", \"item\": \"minecraft:iron_ingot\", \"count\": 16,"
                        + " \"consumeItems\": true, \"match\": \"fuzzy\"}"));
        out.add(task("defeat_zombies", "Defeat zombies",
                "{\"type\": \"tenet:kill\", \"entity\": \"minecraft:zombie\", \"value\": 10}"));
        out.add(task("break_stone", "Break stone",
                "{\"type\": \"tenet:break\", \"block\": \"minecraft:stone\", \"value\": 32}"));
        out.add(task("reach_the_nether", "Reach the Nether",
                "{\"type\": \"tenet:dimension\", \"dimension\": \"minecraft:the_nether\"}"));
        out.add(task("agree_checkmark", "Agree",
                "{\"type\": \"tenet:checkmark\", \"title\": \"I agree\"}"));
        out.add(reward("reward_iron_sword", "An iron sword",
                "{\"type\": \"tenet:item\", \"item\": \"minecraft:iron_sword\"}"));
        out.add(reward("reward_levels", "Five levels",
                "{\"type\": \"tenet:xp\", \"amount\": 5, \"levels\": true}"));
        out.add(condition("needs_started_stage", "Needs the started stage",
                "{\"type\": \"tenet:stage\", \"stage\": \"started\"}"));
        out.add(condition("party_of_two", "Two players online",
                "{\"type\": \"tenet:party_size\", \"min\": 2}"));
        return List.copyOf(out);
    }

    private static ComponentTemplate.Template task(String id, String name, String json) {
        return of(ComponentTemplate.Kind.TASK, id, name, json);
    }

    private static ComponentTemplate.Template reward(String id, String name, String json) {
        return of(ComponentTemplate.Kind.REWARD, id, name, json);
    }

    private static ComponentTemplate.Template condition(String id, String name, String json) {
        return of(ComponentTemplate.Kind.CONDITION, id, name, json);
    }

    private static ComponentTemplate.Template of(ComponentTemplate.Kind kind, String id, String name,
                                                 String json) {
        JsonObject payload = JsonParser.parseString(json).getAsJsonObject();
        String type = payload.has("type") && payload.get("type").isJsonPrimitive()
                ? payload.get("type").getAsString()
                : "tenet:unknown";
        return new ComponentTemplate.Template(ComponentTemplate.BUILT_IN_PREFIX + id, name, kind, type,
                payload, ComponentTemplate.Source.BUILT_IN, 0, 0, 0);
    }
}
