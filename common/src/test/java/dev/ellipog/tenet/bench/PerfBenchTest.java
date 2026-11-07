package dev.ellipog.tenet.bench;

import dev.ellipog.tenet.net.QuestSync;
import dev.ellipog.tenet.progress.ProgressService;
import dev.ellipog.tenet.progress.ProgressionEngine;
import dev.ellipog.tenet.progress.QuestProgress;
import dev.ellipog.tenet.progress.TeamProgress;
import dev.ellipog.tenet.quest.Fixtures;
import dev.ellipog.tenet.quest.MinecraftTestBootstrap;
import dev.ellipog.tenet.quest.Quest;
import dev.ellipog.tenet.quest.QuestIndex;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.function.LongSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Headless timings for the server's progress paths. Not a test: it asserts only that its fixture
 * loaded, and it runs only when {@code TENET_BENCH=1} is set, so a normal build skips it.
 *
 * <p>Run with {@code TENET_BENCH=1 gradlew :common:test --tests '*PerfBenchTest' --rerun}. Results go
 * to {@code TENET_BENCH_OUT}, or {@code build/perf-bench.txt} under the common project.
 *
 * <p>The pack has the stress pack's shape ({@code tools/stress_quests.py}): 960 quests in one
 * chapter, a spanning tree of dependencies plus cross-links (about 1,900 edges), on a fixed seed.
 * Each quest has one to three tasks. The progress is a team part way through: the first 35% of the
 * quests completed, the next 10% started. Only public API is called, so the same file measures the
 * code before and after a change.
 */
@EnabledIfEnvironmentVariable(named = "TENET_BENCH", matches = "1")
class PerfBenchTest {

    private static final int QUESTS = 960;
    private static final long NOW = 10_000L;

    private static volatile long sink;

    private static QuestIndex index;
    private static TeamProgress progress;

    private final List<String> lines = new ArrayList<>();

    @BeforeAll
    static void buildPack() {
        MinecraftTestBootstrap.boot();
        Random random = new Random(960L);
        String[] quests = new String[QUESTS];
        for (int i = 0; i < QUESTS; i++) {
            Fixtures.Builder quest = Fixtures.q("q" + i).at((i % 40) * 60, (i / 40) * 60).tasks(1 + i % 3);
            if (i > 0) {
                int parent = random.nextInt(i);
                if (i > 1 && random.nextDouble() < 0.98) {
                    int cross = random.nextInt(i - 1);
                    if (cross >= parent) {
                        cross++;
                    }
                    quest.dependsOn("q" + parent, "q" + cross);
                }
                else {
                    quest.dependsOn("q" + parent);
                }
            }
            quests[i] = quest.build();
        }
        index = Fixtures.indexOf(Fixtures.file(quests));
        assertEquals(QUESTS, index.questCount(), "every generated quest has to make it into the index");

        TeamProgress team = TeamProgress.empty();
        for (int i = 0; i < QUESTS; i++) {
            Quest quest = Fixtures.quest(index, "q" + i);
            if (i < QUESTS * 35 / 100) {
                team = team.put(quest, QuestProgress.NONE.recordTask(0, 1).completedAt(NOW - 100));
            }
            else if (i < QUESTS * 45 / 100) {
                team = team.put(quest, QuestProgress.NONE.recordTask(0, 1));
            }
        }
        progress = team;
    }

    @Test
    void progressPaths() throws IOException {
        ProgressionEngine.Resolution resolution = ProgressionEngine.resolve(index, progress, NOW);
        Map<String, String> sent = QuestSync.progressDelta(resolution, progress, index, null).snapshot();

        lines.add(String.format(java.util.Locale.ROOT, "%-44s %14s %14s %14s",
                "scenario (960 quests)", "median us/op", "min us/op", "max us/op"));

        bench("resolve the quest graph", () ->
                ProgressionEngine.resolve(index, progress, NOW).states().size());
        bench("excluded ids (cap and exclusive pass)", () ->
                ProgressionEngine.excludedIds(index, progress).size());
        bench("progress delta, one member, nothing moved", () ->
                QuestSync.progressDelta(resolution, progress, index, sent).json().length);
        bench("push to a party of 4: resolve + 4 deltas", () -> {
            ProgressionEngine.Resolution fresh = ProgressionEngine.resolve(index, progress, NOW);
            long bytes = 0;
            for (int member = 0; member < 4; member++) {
                bytes += QuestSync.progressDelta(fresh, progress, index, sent).json().length;
            }
            return bytes;
        });
        QuestIndex.QuestEntry entry = index.quest("q480").orElseThrow();
        bench("hash one quest entry (a map key per kill site)", entry::hashCode);

        // The shared-text path, added with it (TEN-66). Not in the baseline, which predates it.
        ProgressService.Contributors nobody = (questId, taskIndex) -> Map.of();
        ProgressService.Live none = (questId, taskIndex) -> 0;
        bench("push to a party of 4: resolve + shared text + 4 deltas", () -> {
            ProgressionEngine.Resolution fresh = ProgressionEngine.resolve(index, progress, NOW);
            QuestSync.TeamText team = QuestSync.teamText(fresh, progress, index, nobody, none);
            long bytes = 0;
            for (int member = 0; member < 4; member++) {
                bytes += QuestSync.progressDelta(fresh, progress, index, sent, nobody, Set.of(), Map.of(), none,
                        team).json().length;
            }
            return bytes;
        });
        bench("push to a party of 4, walk cached: text + 4 deltas", () -> {
            QuestSync.TeamText team = QuestSync.teamText(resolution, progress, index, nobody, none);
            long bytes = 0;
            for (int member = 0; member < 4; member++) {
                bytes += QuestSync.progressDelta(resolution, progress, index, sent, nobody, Set.of(), Map.of(),
                        none, team).json().length;
            }
            return bytes;
        });
        bench("  of which: the team text alone", () ->
                QuestSync.teamText(resolution, progress, index, nobody, none).hashCode());
        QuestSync.TeamText built = QuestSync.teamText(resolution, progress, index, nobody, none);
        bench("  of which: one member's delta from it", () ->
                QuestSync.progressDelta(resolution, progress, index, sent, nobody, Set.of(), Map.of(), none, built)
                        .json().length);
        int fullBytes = QuestSync.progressDelta(resolution, progress, index, null).json().length;
        int deltaBytes = QuestSync.progressDelta(resolution, progress, index, sent).json().length;
        int sharedBytes = QuestSync.progressDelta(resolution, progress, index, sent, nobody, Set.of(), Map.of(),
                none, built).json().length;
        lines.add("  (bytes: full " + fullBytes + ", delta " + deltaBytes + ", delta from team text " + sharedBytes
                + ")");
        // Why the snapshots and the index's lookup are hash maps now: these ids differ only in a
        // trailing number, and Map.copyOf's linear probing packs neighbouring hash codes into long runs.
        List<String> ids = index.quests().stream().map(each -> each.quest().id()).toList();
        Map<String, String> copied = Map.copyOf(sent);
        Map<String, String> hashed = new java.util.HashMap<>(sent);
        bench("look up every id in a snapshot (Map.copyOf)", () -> {
            long found = 0;
            for (String id : ids) {
                found += copied.get(id).length();
            }
            return found;
        });
        bench("look up every id in a snapshot (HashMap)", () -> {
            long found = 0;
            for (String id : ids) {
                found += hashed.get(id).length();
            }
            return found;
        });
        bench("push to one player, walk cached: 1 delta", () ->
                QuestSync.progressDelta(resolution, progress, index, sent, nobody, Set.of(), Map.of(), none, null)
                        .json().length);

        write();
    }

    // ------------------------------------------------------------------
    // Harness
    // ------------------------------------------------------------------

    private static final long WARMUP_NANOS = 1_500_000_000L;
    private static final long SAMPLE_NANOS = 150_000_000L;
    private static final int SAMPLES = 12;

    private void bench(String name, LongSupplier op) {
        long until = System.nanoTime() + WARMUP_NANOS;
        long calls = 0;
        long acc = 0;
        while (System.nanoTime() < until) {
            acc += op.getAsLong();
            calls++;
        }
        long perSample = Math.max(1, calls * SAMPLE_NANOS / WARMUP_NANOS);
        double[] microsPerOp = new double[SAMPLES];
        for (int s = 0; s < SAMPLES; s++) {
            long start = System.nanoTime();
            for (long i = 0; i < perSample; i++) {
                acc += op.getAsLong();
            }
            microsPerOp[s] = (System.nanoTime() - start) / 1000.0 / perSample;
        }
        sink += acc;
        Arrays.sort(microsPerOp);
        double median = (microsPerOp[SAMPLES / 2 - 1] + microsPerOp[SAMPLES / 2]) / 2.0;
        lines.add(String.format(java.util.Locale.ROOT, "%-44s %14.1f %14.1f %14.1f",
                name, median, microsPerOp[0], microsPerOp[SAMPLES - 1]));
    }

    private void write() throws IOException {
        String target = System.getenv("TENET_BENCH_OUT");
        Path out = target == null || target.isBlank() ? Path.of("build", "perf-bench.txt") : Path.of(target);
        Path parent = out.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Files.writeString(out, String.join(System.lineSeparator(), lines) + System.lineSeparator(),
                StandardCharsets.UTF_8);
    }
}
