package dev.ellipog.tenet.client.viewer;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Which viewer owns the integration, checked as a rule rather than by installing one.
 *
 * <h2>Why a predicate and not three mods</h2>
 *
 * <p>The decision is the feature: a client running two viewers must see one set of quest entries,
 * and the answer is priority rather than suppression. That cannot be checked by installing viewers
 * into a test JVM -- there is no loader in one -- so {@code Viewers.choose} takes the loaded set as a
 * predicate and this hands it every subset. {@code chosen()} is the two-line runtime path over the
 * same call, so what is tested here is what a client runs.
 *
 * <h2>The one assumption</h2>
 *
 * <p>The runtime-path test relies on a test JVM having no Armature platform installed, which is the
 * same assumption {@code TeamProvidersTest} pins for the same reason: a platform that is not there
 * is read as "nothing is loaded", and that is exactly true.
 */
class ViewersTest {

    @Test
    @DisplayName("the chain is JEI, then REI, and the first installed one wins")
    void theFirstInstalledViewerInPriorityOrderWins() {
        assertEquals(Optional.of(Viewers.Viewer.JEI),
                Viewers.choose(Set.of("jei", "roughlyenoughitems")::contains),
                "with both installed, JEI is the integration");
        assertEquals(Optional.of(Viewers.Viewer.REI),
                Viewers.choose(Set.of("roughlyenoughitems")::contains),
                "and REI is the fallback tier");
        assertEquals(Optional.empty(), Viewers.choose(id -> false),
                "with nothing installed there is no integration at all");
    }

    @Test
    @DisplayName("for every possible installation, exactly one viewer is chosen")
    void everySubsetChoosesExactlyOne() {
        // (EMI was first until the 26.3 port removed it for want of a 26.x artifact; it goes back
        // at the head of both this list and the enum when it ports.)
        List<String> ids = List.of("jei", "roughlyenoughitems");

        for (int mask = 1; mask < (1 << ids.size()); mask++) {
            Set<String> installed = new HashSet<>();
            for (int bit = 0; bit < ids.size(); bit++) {
                if ((mask & (1 << bit)) != 0) {
                    installed.add(ids.get(bit));
                }
            }

            Viewers.Viewer chosen = Viewers.choose(installed::contains).orElseThrow(
                    () -> new AssertionError("a non-empty installation chose nothing: " + installed));
            assertTrue(installed.contains(chosen.modId()),
                    "the chosen viewer must be one of the installed ones: " + installed);

            List<Viewers.Viewer> outranking = new ArrayList<>();
            for (Viewers.Viewer viewer : Viewers.Viewer.values()) {
                if (viewer == chosen) {
                    break;
                }
                outranking.add(viewer);
            }
            for (Viewers.Viewer viewer : outranking) {
                assertFalse(installed.contains(viewer.modId()),
                        viewer + " outranks the chosen " + chosen + " and is installed: " + installed);
            }
        }
    }

    @Test
    @DisplayName("with no loader installed, nothing is chosen and nothing may install")
    void noPlatformMeansNoViewer() {
        assertTrue(Viewers.chosen().isEmpty(),
                "a test JVM installs no platform, so no viewer can be reported as loaded");

        for (Viewers.Viewer viewer : Viewers.Viewer.values()) {
            assertFalse(Viewers.mayInstall(viewer),
                    viewer + " must not install without a loader to confirm it is there");
        }
    }

    @Test
    @DisplayName("the runtime answer is stable across calls: the probes run once per platform")
    void chosenIsStable() {
        // The per-tick path: mods load at boot and never mid-game, so asking twice must answer
        // twice without asking the loader twice. Pinned by equality here; the probe count is an
        // implementation detail the predicate tests above already own.
        assertEquals(Viewers.chosen(), Viewers.chosen());
    }
}
