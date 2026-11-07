package dev.ellipog.tenet.progress;

import dev.ellipog.tenet.quest.Fixtures;
import dev.ellipog.tenet.quest.QuestIndex;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static dev.ellipog.tenet.quest.Fixtures.q;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The engine keeps what an index alone decides (dependents, caps, groups, dependency targets) for the
 * index it was built from. A reload is a new index, so a resolve against a new index must answer from
 * the new one, and going back must answer from the old one again. The packs below use the same ids
 * with different rules, so an answer carried over from the wrong index shows.
 */
@DisplayName("Resolving against more than one index")
class ResolveAcrossIndexesTest {

    private static final long NOW = 10_000L;

    private static QuestState stateOf(QuestIndex index, TeamProgress progress, String id) {
        return ProgressionEngine.resolve(index, progress, NOW).stateOf(Fixtures.quest(index, id));
    }

    private static TeamProgress completed(QuestIndex index, String... ids) {
        TeamProgress progress = TeamProgress.empty();
        for (String id : ids) {
            progress = progress.put(Fixtures.quest(index, id),
                    QuestProgress.NONE.completedAt(NOW).withRewardsClaimed(true));
        }
        return progress;
    }

    @Test
    @DisplayName("dependencies come from the index being resolved")
    void dependenciesFollowTheIndex() {
        QuestIndex chained = Fixtures.indexOf(Fixtures.file(q("a").build(), q("b").dependsOn("a").build()));
        QuestIndex loose = Fixtures.indexOf(Fixtures.file(q("a").build(), q("b").build()));
        for (int round = 0; round < 3; round++) {
            assertEquals(QuestState.LOCKED, stateOf(chained, TeamProgress.empty(), "b"),
                    "round " + round + ": b waits on a in the chained pack");
            assertEquals(QuestState.UNLOCKED, stateOf(loose, TeamProgress.empty(), "b"),
                    "round " + round + ": and is open in the pack where it waits on nothing");
        }
    }

    @Test
    @DisplayName("a dependents cap comes from the index being resolved")
    void capsFollowTheIndex() {
        QuestIndex capped = Fixtures.indexOf(Fixtures.file(
                q("root").noTasks().maxCompletableDependents(1).build(),
                q("left").dependsOn("root").build(),
                q("right").dependsOn("root").build()));
        QuestIndex open = Fixtures.indexOf(Fixtures.file(
                q("root").noTasks().build(),
                q("left").dependsOn("root").build(),
                q("right").dependsOn("root").build()));
        for (int round = 0; round < 3; round++) {
            assertEquals(QuestState.LOCKED, stateOf(capped, completed(capped, "root", "left"), "right"),
                    "round " + round + ": the cap of one is reached, so right is cut off");
            assertEquals(Set.of("right"), ProgressionEngine.excludedIds(capped, completed(capped, "root", "left")),
                    "round " + round + ": and is named as excluded");
            assertEquals(QuestState.UNLOCKED, stateOf(open, completed(open, "root", "left"), "right"),
                    "round " + round + ": the same ids with no cap leave right open");
            assertEquals(Set.of(), ProgressionEngine.excludedIds(open, completed(open, "root", "left")),
                    "round " + round + ": and exclude nothing");
        }
    }

    @Test
    @DisplayName("an exclusive group comes from the index being resolved")
    void groupsFollowTheIndex() {
        QuestIndex grouped = Fixtures.indexOf(Fixtures.file(
                q("sword").exclusiveGroup("path").build(),
                q("bow").exclusiveGroup("path").build()));
        QuestIndex free = Fixtures.indexOf(Fixtures.file(q("sword").build(), q("bow").build()));
        for (int round = 0; round < 3; round++) {
            assertEquals(QuestState.LOCKED, stateOf(grouped, completed(grouped, "sword"), "bow"),
                    "round " + round + ": the sword took the group");
            assertEquals(QuestState.UNLOCKED, stateOf(free, completed(free, "sword"), "bow"),
                    "round " + round + ": and without the group the bow is open");
        }
    }
}
