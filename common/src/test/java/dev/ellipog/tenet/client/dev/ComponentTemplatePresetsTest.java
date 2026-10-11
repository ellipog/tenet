package dev.ellipog.tenet.client.dev;

import com.google.gson.JsonObject;
import net.minecraft.resources.ResourceLocation;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The shipped presets: every one names a type this build knows, and carries only fields that
 * type declares — so a preset can never be the menu item that silently does nothing.
 */
@DisplayName("The shipped template presets")
class ComponentTemplatePresetsTest {

    @Test
    @DisplayName("ten presets across all three kinds")
    void tenAcrossThreeKinds() {
        List<ComponentTemplate.Template> presets = ComponentTemplatePresets.builtIns();
        assertFalse(presets.isEmpty(), "the shelf ships with something on it");
        for (ComponentTemplate.Kind kind : ComponentTemplate.Kind.values()) {
            assertTrue(presets.stream().anyMatch(preset -> preset.kind() == kind),
                    "no preset for " + kind + ", so that kind's Apply path ships untested");
        }
    }

    @Test
    @DisplayName("every preset names a registered type and only its fields")
    void everyPresetDecodesInPrinciple() {
        for (ComponentTemplate.Template preset : ComponentTemplatePresets.builtIns()) {
            ResourceLocation id = ResourceLocation.tryParse(preset.typeId());
            assertTrue(id != null && known(preset.kind(), id),
                    preset.id() + " names " + preset.typeId() + ", which this build does not know");
            Set<String> allowed = new HashSet<>(fields(preset.kind(), id));
            allowed.add("type");
            for (String field : preset.payload().keySet()) {
                assertTrue(allowed.contains(field), preset.id() + " carries \"" + field
                        + "\", which " + preset.typeId() + " never declared");
            }
            assertFalse(ComponentTemplate.blurb(preset.kind(), preset.typeId(), preset.payload()).isBlank(),
                    preset.id() + " has no sentence for its row");
        }
    }

    private static boolean known(ComponentTemplate.Kind kind, ResourceLocation id) {
        return switch (kind) {
            case TASK -> dev.ellipog.tenet.quest.task.TaskTypes.ids().contains(id);
            case REWARD -> dev.ellipog.tenet.quest.reward.RewardTypes.ids().contains(id);
            case CONDITION -> dev.ellipog.tenet.quest.condition.ConditionTypes.ids().contains(id);
        };
    }

    private static Set<String> fields(ComponentTemplate.Kind kind, ResourceLocation id) {
        return switch (kind) {
            case TASK -> dev.ellipog.tenet.quest.task.TaskTypes.fieldsOf(id);
            case REWARD -> dev.ellipog.tenet.quest.reward.RewardTypes.fieldsOf(id);
            case CONDITION -> dev.ellipog.tenet.quest.condition.ConditionTypes.fieldsOf(id);
        };
    }

    @Test
    @DisplayName("preset ids are unique and under the reserved prefix")
    void idsAreUniqueAndReserved() {
        List<String> ids = ComponentTemplatePresets.builtIns().stream()
                .map(ComponentTemplate.Template::id).toList();
        assertEquals(ids.size(), new HashSet<>(ids).size(), "two presets share an id");
        for (String id : ids) {
            assertTrue(id.startsWith(ComponentTemplate.BUILT_IN_PREFIX),
                    id + " is not under the reserved prefix");
        }
    }
}
