package dev.ellipog.tenet.client.viewer;

import dev.ellipog.tenet.Constants;
import dev.ellipog.armature.api.ArmatureApi;

import java.util.Optional;
import java.util.function.Predicate;

/**
 * Which recipe viewer, if more than one is installed, gets to draw the quest pages.
 *
 * <h2>One registers, and it is the first one in this list</h2>
 *
 * <p>A player can run two viewers at once, and two sets of the same quest entries is worse than one
 * set in the lesser viewer. So the rule is priority, not suppression: EMI carries the full
 * integration, JEI and REI carry the shared fallback, and each adapter asks
 * {@link #mayInstall} before it registers anything. The loser registers nothing at all -- not a
 * category with its recipes filtered out, which is the same duplicate with extra steps.
 *
 * <p>The order is the enum's declaration order, which is why the enum is declared in the order it is
 * and why the comment on it says so. Adding a viewer means adding it in the right place, and that is
 * a decision a reviewer can see in one line.
 *
 * <h2>Why {@code choose} takes a predicate</h2>
 *
 * <p>The same reason a provider chooser takes its candidates: the decision is the feature,
 * and it cannot be tested by installing three mods. {@link #chosen()} is the two-line runtime path
 * over the same call, so the tested thing and the used thing are one function.
 */
public final class Viewers {

    /**
     * The viewers, in priority order. EMI is first-class: its API renders arbitrary pages, so a quest
     * can be a real page there. JEI and REI take the shared fallback -- the same item lookup as plain
     * rows -- which is a decision rather than a ranking, and is why there are only two tiers.
     */
    public enum Viewer {
        EMI("emi"),
        JEI("jei"),
        REI("roughlyenoughitems");

        private final String modId;

        Viewer(String modId) {
            this.modId = modId;
        }

        /** The mod id the loader is asked about. A constant on purpose: callers probe with it. */
        public String modId() {
            return modId;
        }
    }

    private Viewers() {
    }

    /** The first viewer in priority order the predicate accepts, if any. */
    public static Optional<Viewer> choose(Predicate<String> loaded) {
        for (Viewer viewer : Viewer.values()) {
            if (loaded.test(viewer.modId())) {
                return Optional.of(viewer);
            }
        }
        return Optional.empty();
    }

    /** The viewer that owns the integration on this client. Empty when none is installed. */
    public static Optional<Viewer> chosen() {
        // Asked once per client tick, and the answer cannot move under a session: mods load at
        // boot, never mid-game. So the probes run once per platform object rather than once per
        // tick — keyed by identity, because a new platform is the only thing that can change the
        // answer, and a test JVM swapping harnesses is exactly such a change. A null platform (no
        // loader yet, or a test JVM) is a key like any other and reads as nothing loaded.
        Object platform;
        try {
            platform = ArmatureApi.platform();
        }
        catch (RuntimeException e) {
            platform = null;
        }
        if (platform != chosenPlatform) {
            chosenPlatform = platform;
            chosenAnswer = choose(Viewers::loaded);
        }
        return chosenAnswer;
    }

    /** The platform the cached answer was decided against. Anything else recomputes. */
    private static Object chosenPlatform = new Object();

    /** The answer for {@link #chosenPlatform}. */
    private static Optional<Viewer> chosenAnswer = Optional.empty();

    /**
     * Whether this viewer is the one that registered. Every adapter's first line is this.
     *
     * <p>A compatibility demotion used to live here — EMI skipped while it and JEI were installed and
     * EMI's JEI bridge was broken — and it was removed after a live pass showed what it cost: the
     * bridge break only affects pages EMI borrows from JEI, our page is EMI-native and renders fine,
     * so the demotion took the quests out of the viewer that worked and parked them in JEI where the
     * player was not looking. The pair's breakage is recorded in TESTING.md and the plan; do not put
     * the demotion back without evidence that EMI cannot show *our* page.
     */
    public static boolean mayInstall(Viewer viewer) {
        return chosen().filter(chosen -> chosen == viewer).isPresent();
    }

    /**
     * The platform ask, with the absent-platform case read as "nothing is loaded".
     *
     * <p>{@code ArmatureApi.platform()} throws when nothing has installed it, and a test JVM is
     * exactly that. It is also the honest answer before a loader has reached the platform: nothing
     * can be loaded if the loader is not there yet.
     */
    private static boolean loaded(String modId) {
        try {
            return ArmatureApi.platform().isModLoaded(modId);
        }
        catch (RuntimeException e) {
            Constants.LOG.debug("cannot ask whether '{}' is loaded ({}); assuming not",
                    modId, e.toString());
            return false;
        }
    }
}
