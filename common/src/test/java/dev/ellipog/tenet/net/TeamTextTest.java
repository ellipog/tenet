package dev.ellipog.tenet.net;

import dev.ellipog.tenet.progress.ProgressService;
import dev.ellipog.tenet.progress.ProgressionEngine;
import dev.ellipog.tenet.progress.QuestProgress;
import dev.ellipog.tenet.progress.TeamProgress;
import dev.ellipog.tenet.quest.Fixtures;
import dev.ellipog.tenet.quest.MinecraftTestBootstrap;
import dev.ellipog.tenet.quest.QuestIndex;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static dev.ellipog.tenet.quest.Fixtures.q;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * The team's shared text is a shortcut, so the only thing worth asserting about it is that it is
 * one: every member's message is byte for byte what it would be without it, with and without
 * overlays, as a full sync and as a delta, and a text built for some other moment is not used.
 */
@DisplayName("A team's shared quest text")
class TeamTextTest {

    private static final long NOW = 10_000L;
    private static final UUID HOLDER = UUID.fromString("00000000-0000-0000-0000-00000000000a");

    private static QuestIndex index;

    @BeforeAll
    static void build() {
        MinecraftTestBootstrap.boot();
        index = Fixtures.indexOf(Fixtures.file(
                q("first").tasks(2).build(),
                q("second").dependsOn("first").tasks(2).build(),
                q("third").tasks(1).build(),
                q("fourth").dependsOn("third").tasks(3).build()));
    }

    private static TeamProgress progress(int firstCount) {
        return TeamProgress.empty()
                .put(Fixtures.quest(index, "first"), QuestProgress.NONE.recordTask(0, firstCount))
                .put(Fixtures.quest(index, "third"), QuestProgress.NONE.recordTask(0, 1).completedAt(NOW - 5));
    }

    /** Someone holding toward one task, so the per-task picture is part of the text. */
    private static final ProgressService.Contributors CONTRIBUTORS = (questId, taskIndex) ->
            questId.equals("first") && taskIndex == 1 ? Map.of(HOLDER, 3) : Map.of();

    /** A live count on one task, so the display value is part of the text. */
    private static final ProgressService.Live LIVE = (questId, taskIndex) ->
            questId.equals("fourth") && taskIndex == 2 ? 2 : 0;

    /**
     * A member whose stage gate shuts one quest and whose conditions shut a row of another. The gated
     * quest is one the team has open, so the gate changes what this member reads.
     */
    private static final Set<String> GATED = Set.of("first");
    private static final Map<String, ProgressService.LockView> LOCKED_ROW =
            Map.of("fourth", new ProgressService.LockView(Map.of(0, List.of(0)), Map.of()));

    private static void assertSame(QuestSync.Delta expected, QuestSync.Delta actual, String what) {
        assertEquals(new String(expected.json(), StandardCharsets.UTF_8),
                new String(actual.json(), StandardCharsets.UTF_8), what + ": the bytes");
        assertEquals(expected.snapshot(), actual.snapshot(), what + ": the snapshot");
    }

    @Test
    @DisplayName("every member's message is the one they would be sent without it")
    void identicalWithAndWithout() {
        TeamProgress now = progress(1);
        ProgressionEngine.Resolution resolution = ProgressionEngine.resolve(index, now, NOW);
        QuestSync.TeamText team = QuestSync.teamText(resolution, now, index, CONTRIBUTORS, LIVE);

        // What each member was last sent, from an earlier moment, so the deltas below carry something.
        TeamProgress earlier = progress(0);
        ProgressionEngine.Resolution then = ProgressionEngine.resolve(index, earlier, NOW);
        Map<String, String> sent = QuestSync.progressDelta(then, earlier, index, null, CONTRIBUTORS,
                Set.of(), Map.of(), LIVE).snapshot();

        for (boolean full : new boolean[] {true, false}) {
            Map<String, String> previous = full ? null : sent;
            String kind = full ? "a full sync" : "a delta";

            assertSame(QuestSync.progressDelta(resolution, now, index, previous, CONTRIBUTORS, Set.of(), Map.of(),
                            LIVE, null),
                    QuestSync.progressDelta(resolution, now, index, previous, CONTRIBUTORS, Set.of(), Map.of(),
                            LIVE, team),
                    kind + " to a member with no overlay");
            assertSame(QuestSync.progressDelta(resolution, now, index, previous, CONTRIBUTORS, GATED, LOCKED_ROW,
                            LIVE, null),
                    QuestSync.progressDelta(resolution, now, index, previous, CONTRIBUTORS, GATED, LOCKED_ROW,
                            LIVE, team),
                    kind + " to a member whose gate and conditions shut something");
        }
    }

    @Test
    @DisplayName("the overlays still reach the member they belong to")
    void overlaysAreNotShared() {
        TeamProgress now = progress(1);
        ProgressionEngine.Resolution resolution = ProgressionEngine.resolve(index, now, NOW);
        QuestSync.TeamText team = QuestSync.teamText(resolution, now, index, CONTRIBUTORS, LIVE);

        Map<String, String> open = QuestSync.progressDelta(resolution, now, index, null, CONTRIBUTORS, Set.of(),
                Map.of(), LIVE, team).snapshot();
        Map<String, String> gated = QuestSync.progressDelta(resolution, now, index, null, CONTRIBUTORS, GATED,
                LOCKED_ROW, LIVE, team).snapshot();

        assertNotEquals(open.get("first"), gated.get("first"), "a gated quest reads as locked to its member");
        assertNotEquals(open.get("fourth"), gated.get("fourth"), "and a shut row is marked for its member");
        assertEquals(open.get("third"), gated.get("third"), "and a quest neither touches reads the same to both");
    }

    @Test
    @DisplayName("a text built for another moment is not used")
    void staleTextIsIgnored() {
        TeamProgress earlier = progress(0);
        ProgressionEngine.Resolution then = ProgressionEngine.resolve(index, earlier, NOW);
        QuestSync.TeamText stale = QuestSync.teamText(then, earlier, index, CONTRIBUTORS, LIVE);

        TeamProgress now = progress(1);
        ProgressionEngine.Resolution resolution = ProgressionEngine.resolve(index, now, NOW);
        assertSame(QuestSync.progressDelta(resolution, now, index, null, CONTRIBUTORS, Set.of(), Map.of(), LIVE,
                        null),
                QuestSync.progressDelta(resolution, now, index, null, CONTRIBUTORS, Set.of(), Map.of(), LIVE,
                        stale),
                "a text from older progress");
    }
}
