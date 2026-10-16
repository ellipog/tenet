package dev.ellipog.tenet.server;

import com.mojang.authlib.GameProfile;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.serialization.Lifecycle;

import dev.ellipog.tenet.quest.MinecraftTestBootstrap;

import io.netty.channel.embedded.EmbeddedChannel;

import net.minecraft.SystemReport;
import net.minecraft.util.Util;
import net.minecraft.commands.CommandSource;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.MappedRegistry;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.Connection;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.Services;
import net.minecraft.server.WorldLoader;
import net.minecraft.server.WorldStem;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.progress.LoggingLevelLoadListener;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.server.packs.repository.PackRepository;
import net.minecraft.server.packs.repository.ServerPacksSource;
import net.minecraft.server.players.PlayerList;
import net.minecraft.util.datafix.DataFixers;
import net.minecraft.util.debugchart.LocalSampleLogger;
import net.minecraft.util.debugchart.SampleLogger;
import net.minecraft.world.Difficulty;
import net.minecraft.world.flag.FeatureFlags;
import net.minecraft.world.level.DataPackConfig;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.dimension.LevelStem;
import net.minecraft.world.level.levelgen.WorldDimensions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import net.minecraft.world.level.storage.LevelStorageSource;
import net.minecraft.world.level.storage.PrimaryLevelData;
import net.minecraft.world.level.validation.ContentValidationException;

import java.io.IOException;
import java.net.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/**
 * A real Minecraft server, in the test JVM, with a real {@code ServerPlayer} on it.
 *
 * <h2>Why this exists, and why not GameTest</h2>
 *
 * <p>Stage 3's exit is "a whole questline completable through commands, with a player".
 * {@code /tenet progress}, {@code submit} and {@code complete} all begin with
 * {@code getPlayerOrException()}, and there is no player at a server console -- so the playthrough
 * cannot be scripted from a console, and the only way to run it is to have a player.
 *
 * <p>The obvious answer is vanilla's GameTest framework, and it is not usable here.
 * {@code GameTestServer.create} throws on an empty test collection, so it cannot be borrowed as a
 * plain server; and a {@code TestFunction} needs a structure template, which a release server does
 * not ship. The <i>framework</i> needs ceremony that is about its own job; what is wanted is only
 * its <b>recipe</b>, which is fifteen lines and is copied here rather than invented:
 * {@code GameTestHelper.makeMockServerPlayerInLevel} is the canonical way to get a working player
 * without a client, and the boot sequence below mirrors {@code GameTestServer.create}.
 *
 * <h2>Why a real server rather than a constructed player</h2>
 *
 * <p>Because a bare {@code new ServerPlayer(server, level, profile, info)} is not usable, and its
 * failure is at the last line rather than the first. {@code ProgressService.complete} ends in
 * {@code player.displayClientMessage(...)}, which goes through {@code ServerPlayer.connection} --
 * set only by {@code PlayerList.placeNewPlayer}. A constructed player has that field null, so every
 * completion would throw after doing its real work: the progress would be saved, the rewards
 * granted, and the command would still report an exception. Handing the player a connection is the
 * difference between a test of the engine and a test of where the engine stops.
 *
 * <p>So this is real in every part that matters: a real world loaded from the real vanilla datapack,
 * a real {@code SavedData} for progress, a real command dispatcher, and a real player who is on the
 * server's player list. Nothing here is a stub of the thing being tested.
 *
 * <h2>The one seam, stated plainly</h2>
 *
 * <p>The engine's own entry point is a <i>player tick</i> hook, which fires from each loader's event
 * and therefore does not fire in a test. A test that wants the automatic half of the engine --
 * "eight oak logs in the inventory completes Punch a Tree" -- has to call
 * {@link dev.ellipog.tenet.progress.ProgressService#tick} itself. That is one call, and it is the
 * only thing here that does not go through a loader-supplied path. It is called on the server
 * thread, so the threading is the same as in play.
 *
 * <h2>Why commands are dispatched off the player's own source</h2>
 *
 * <p>{@code /tenet} reads its player with {@code getPlayerOrException()}, which returns
 * {@code this.entity} when it is a {@code ServerPlayer} and throws otherwise. So the source is the
 * server's own -- which is what a real command would use for position, level and permission -- with
 * {@code withEntity(player)} attaching the player as the entity. That is not a test-only trick: it
 * is the same source shape a command block or a console-driven command gets when it acts on a
 * player, and it means the command body runs unchanged.
 */
public final class HeadlessServer implements AutoCloseable {

    /** How long the world load is allowed to take. Generous: it runs the vanilla datapack for real. */
    private static final Duration READY_TIMEOUT = Duration.ofSeconds(120);

    private final MinecraftServer server;
    private final LevelStorageSource.LevelStorageAccess access;
    private final Thread thread;
    private final TestNameTable names;
    /**
     * The mock players' channels.
     *
     * <p>Synchronised because it is written on the server thread (from {@link #spawnPlayer}) and read
     * on the test thread (from {@link #close}). A plain list here is a data race that would show up as
     * a rare, baffling failure in a test nobody suspects.
     */
    private final List<EmbeddedChannel> channels = Collections.synchronizedList(new ArrayList<>());

    private HeadlessServer(MinecraftServer server,
                           LevelStorageSource.LevelStorageAccess access,
                           Thread thread,
                           TestNameTable names) {
        this.server = server;
        this.access = access;
        this.thread = thread;
        this.names = names;
    }

    // ------------------------------------------------------------------
    // Starting
    // ------------------------------------------------------------------

    /**
     * Boots a server over {@code universe}, creating the world if it is not there, and returns once
     * it is ticking.
     *
     * <p>Blocks, so this is a {@code @BeforeAll} rather than something done per test. A world load
     * is seconds of work.
     */
    /**
     * @throws ContentValidationException if a symlink in the world directory points somewhere the
     *         validator forbids. Declared rather than caught, because the compiler named it and
     *         guessing at its package was cheaper to stop doing than to keep doing: it is in
     *         {@code world.level.validation}, not beside the storage classes where a first look went.
     */
    public static HeadlessServer start(Path universe) throws IOException, ContentValidationException {
        MinecraftTestBootstrap.boot();

        Files.createDirectories(universe);

        LevelStorageSource storage = LevelStorageSource.createDefault(universe);
        LevelStorageSource.LevelStorageAccess access = storage.validateAndCreateAccess("playthrough");

        PackRepository packs = ServerPacksSource.createPackRepository(access);
        packs.reload();

        WorldDataConfiguration dataConfig = new WorldDataConfiguration(
                new DataPackConfig(new ArrayList<>(packs.getAvailableIds()), List.of()),
                FeatureFlags.REGISTRY.allFlags());

        LevelSettings settings = new LevelSettings(
                "Tenet playthrough", GameType.CREATIVE,
                new LevelSettings.DifficultySettings(Difficulty.NORMAL, false, false), true,
                dataConfig);

        // A flat world with no structures: it generates in seconds, and with nothing random in it
        // a failure is reproducible by construction rather than by a fixed seed.
        WorldLoader.InitConfig init = new WorldLoader.InitConfig(
                new WorldLoader.PackConfig(packs, dataConfig, false, true),
                Commands.CommandSelection.DEDICATED,
                net.minecraft.server.permissions.LevelBasedPermissionSet.OWNER);

        WorldStem stem = loadWorldStem(init, settings);

        // Write level.dat before the server starts, which is what Main does and for a reason. The
        // data is already in hand -- it came out of the WorldStem above -- but the directory on disk
        // has none, and a world directory with no data file is one the server treats as uninitialised.
        // Saving it here means the world this test plays in is a normal world, not a special case.
        // (Single argument now: the registries travel inside the data rather than beside it.)
        access.saveDataTag(stem.worldDataAndGenSettings().data());

        // A working name table rather than nulls: `placeNewPlayer` looks every spawned player
        // up in it, and a null kills the spawn with an NPE deep inside vanilla. Map-backed and
        // in-memory: names never leave the test, so nothing is saved and offline resolution stays
        // off. Unknown names read empty, which is what the old null profile cache did too.
        TestNameTable names = new TestNameTable();
        Services services = new Services(null, null, null, names, null);
        Thread[] serverThread = new Thread[1];

        MinecraftServer server = MinecraftServer.spin(thread -> {
            serverThread[0] = thread;
            return new TestServer(thread, access, packs, stem, services);
        });

        HeadlessServer headless = new HeadlessServer(server, access, serverThread[0], names);
        headless.awaitReady();
        // The spawn chunk, held at entity-ticking level: nothing here prepares the spawn
        // area the way a real boot does, and on 26.x an entity added outside ticking sections
        // is refused silently -- drops, projectiles and anything else a test spawns would vanish
        // without an error. `setChunkForced` is not enough: it holds level 31, which loads but
        // does not tick, so the ticket here names player-grade level 22 explicitly (measured:
        // a drop at 31 is invisible, at 22 it is found). Forcing is a ticket, not a load, so
        // the wait below is what makes it deterministic.
        headless.onServerThread(() -> server.overworld().getChunkSource().addTicket(
                new net.minecraft.server.level.Ticket(
                        net.minecraft.server.level.TicketType.FORCED, 22),
                new net.minecraft.world.level.ChunkPos(0, 0)));
        headless.awaitChunk();
        return headless;
    }

    /**
     * The world itself: packs, registries, and a flat level to stand in.
     *
     * <p>Laid out the way {@code GameTestServer.create} lays it out, including the flat world preset,
     * because that is the arrangement vanilla itself has tested. The one change is the level name,
     * so nothing here can be confused with a game-test world at a glance.
     */
    private static WorldStem loadWorldStem(WorldLoader.InitConfig init,
                                           LevelSettings settings) {
        // Seed zero, no structures, no bonus chest. A flat world from a fixed seed means a failure
        // is reproducible, and a world that generates quickly means the boot is seconds not minutes.
        net.minecraft.world.level.levelgen.WorldOptions options =
                new net.minecraft.world.level.levelgen.WorldOptions(0L, false, false);
        return Util.<WorldStem>blockUntilDone(executor -> WorldLoader.load(
                init,
                context -> {
                    Registry<LevelStem> stubs = new MappedRegistry<>(Registries.LEVEL_STEM, Lifecycle.stable()).freeze();
                    WorldDimensions.Complete dimensions = context.datapackWorldRegistries()
                            .lookupOrThrow(Registries.WORLD_PRESET)
                            .getOrThrow(WorldPresets.FLAT)
                            .value()
                            .createWorldDimensions()
                            .bake(stubs);
                    net.minecraft.world.level.storage.PrimaryLevelData levelData =
                            new PrimaryLevelData(settings, dimensions.specialWorldProperty(),
                                    dimensions.lifecycle());
                    net.minecraft.world.level.levelgen.WorldGenSettings genSettings =
                            new net.minecraft.world.level.levelgen.WorldGenSettings(options,
                                    new WorldDimensions(dimensions.dimensions()));
                    return new WorldLoader.DataLoadOutput<>(
                            new net.minecraft.world.level.storage.LevelDataAndDimensions.WorldDataAndGenSettings(
                                    levelData, genSettings),
                            dimensions.dimensionsRegistryAccess());
                },
                WorldStem::new,
                Util.backgroundExecutor(),
                executor))
                .join();
    }

    /**
     * A world with nothing moving in it.
     *
     * <p>Mob spawning, weather and random ticks are off because a playthrough test asserts on
     * inventories and stored progress, and a wandering zombie or a growing sapling is noise that
     * could only ever make a failure harder to read.
     */
    private static GameRules quietRules() {
        GameRules rules = new GameRules(FeatureFlags.REGISTRY.allFlags());
        // No fire-tick rule exists anymore, and nothing on a structureless flat world burns, so
        // nothing replaces it. The three that remain are the ones that could move under a test.
        rules.set(GameRules.SPAWN_MOBS, false, null);
        rules.set(GameRules.SPAWN_MONSTERS, false, null);
        rules.set(GameRules.ADVANCE_WEATHER, false, null);
        rules.set(GameRules.RANDOM_TICK_SPEED, 0, null);
        return rules;
    }

    /**
     * Waits until the server is up and has actually ticked once.
     *
     * <p>The tick count as well as {@code isReady()}, because {@code isReady} is set while the level
     * is still being prepared and tasks submitted before the first tick would be queued against a
     * server that has not started polling them.
     */
    private void awaitReady() {
        long deadline = System.nanoTime() + READY_TIMEOUT.toNanos();
        while (System.nanoTime() < deadline) {
            if (server.isReady() && server.getTickCount() > 0) {
                return;
            }
            // A dead server thread will never become ready, and waiting the full two minutes to say
            // so would be the slowest possible way to report the most likely failure.
            if (thread != null && !thread.isAlive()) {
                throw new IllegalStateException("the test server's thread exited before the server was ready "
                        + "(initServer() probably returned false)");
            }
            sleep(50);
        }
        throw new IllegalStateException("the test server was not ready within " + READY_TIMEOUT
                + " (tick " + server.getTickCount() + ", ready " + server.isReady() + ")");
    }

    /**
     * Waits until the spawn chunk ticks entities.
     *
     * <p>See {@link #start}: the ticket is not the load, and a drop in the first tick would land
     * in an unloaded section. Thirty seconds, because chunk generation on a loaded CI box is
     * milliseconds and anything slower is already broken. Polled in two stages, because neither
     * signal alone is the condition: a present chunk may not tick yet, and only a queryable
     * entity proves the sections answer. The probe entity is discarded before any test runs.
     */
    private void awaitChunk() {
        net.minecraft.core.BlockPos spawn =
                server.overworld().getRespawnData().globalPos().pos();
        long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        while (System.nanoTime() < deadline) {
            if (callOnServerThread(() -> server.overworld().isLoaded(spawn))) {
                break;
            }
            sleep(50);
        }
        java.util.UUID probeId = callOnServerThread(() -> {
            net.minecraft.world.entity.item.ItemEntity probe =
                    new net.minecraft.world.entity.item.ItemEntity(
                            server.overworld(), spawn.getX() + 0.5, spawn.getY() + 1.0,
                            spawn.getZ() + 0.5,
                            dev.ellipog.tenet.quest.ItemStacks.stackOf(
                                    net.minecraft.world.item.Items.STONE, 1));
            server.overworld().addFreshEntity(probe);
            return probe.getUUID();
        });
        deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        while (System.nanoTime() < deadline) {
            Boolean found = callOnServerThread(
                    () -> server.overworld().getEntity(probeId) != null);
            if (found) {
                callOnServerThread(() -> {
                    var probe = server.overworld().getEntity(probeId);
                    if (probe != null) {
                        probe.discard();
                    }
                    return null;
                });
                return;
            }
            sleep(50);
        }
        throw new IllegalStateException("the spawn chunk never started ticking in 30 seconds");
    }

    /** The server itself, for anything this class does not wrap. */
    public MinecraftServer server() {
        return this.server;
    }

    /**
     * The name table the mock players are looked up in.
     *
     * <p>Vanilla's own is backed by files and the session service; this one is two maps, because a
     * test that spawned "tenet-remembered" twice with different UUIDs would be testing nothing.
     * Entries are recorded as players spawn (see {@link #spawnPlayer}), so a lookup by either key
     * finds whoever joined under it.
     */
    private static final class TestNameTable
            implements net.minecraft.server.players.UserNameToIdResolver {
        private final java.util.Map<String, net.minecraft.server.players.NameAndId> byName =
                new java.util.concurrent.ConcurrentHashMap<>();
        private final java.util.Map<UUID, net.minecraft.server.players.NameAndId> byId =
                new java.util.concurrent.ConcurrentHashMap<>();

        @Override
        public void add(net.minecraft.server.players.NameAndId entry) {
            byName.put(entry.name(), entry);
            byId.put(entry.id(), entry);
        }

        @Override
        public java.util.Optional<net.minecraft.server.players.NameAndId> get(String name) {
            return java.util.Optional.ofNullable(byName.get(name));
        }

        @Override
        public java.util.Optional<net.minecraft.server.players.NameAndId> get(UUID id) {
            return java.util.Optional.ofNullable(byId.get(id));
        }

        @Override
        public void resolveOfflineUsers(boolean lookup) {
        }

        @Override
        public void save() {
        }
    }

    /** The server's own dispatcher, which is the one a real player's command goes through. */
    public CommandDispatcher<CommandSourceStack> dispatcher() {
        return this.server.getCommands().getDispatcher();
    }

    /**
     * Puts a real player on the server and returns them.
     *
     * <p>Vanilla's own recipe, from {@code GameTestHelper.makeMockServerPlayerInLevel}: build the
     * cookie, build the player, give it a netty channel, then let {@code placeNewPlayer} do
     * everything else -- assign the connection, send the login packet, add it to the player list,
     * load its data. The {@link EmbeddedChannel} is what makes this work without a socket: it is a
     * complete channel implementation that writes into a buffer nobody reads.
     *
     * <p>The channel is kept and closed in {@link #close()}. Leaking it would leave the connection's
     * event loop alive after the server stops, which is a test JVM that does not exit.
     */
    public ServerPlayer spawnPlayer(String name) {
        ServerPlayer[] out = new ServerPlayer[1];
        onServerThread(() -> {
            CommonListenerCookie cookie =
                    CommonListenerCookie.createInitial(new GameProfile(UUID.randomUUID(), name), false);
            // Recorded before the place call reads it back: `placeNewPlayer` looks the profile
            // up by id in the name table.
            names.add(new net.minecraft.server.players.NameAndId(cookie.gameProfile().id(),
                    cookie.gameProfile().name()));
            ServerPlayer player = new ServerPlayer(
                    server, server.overworld(), cookie.gameProfile(), cookie.clientInformation());

            Connection connection = new Connection(PacketFlow.SERVERBOUND);
            channels.add(new EmbeddedChannel(connection));

            server.getPlayerList().placeNewPlayer(connection, player, cookie);
            // Stood at the world spawn, like a real login: a placed-but-never-positioned player
            // stands at 0,0,0, and on 26.x an entity added outside loaded chunks is refused
            // silently — which is how a drop test watched its book vanish. Spawn chunks are
            // loaded, so this is also where drops land.
            net.minecraft.core.BlockPos spawn =
                    server.overworld().getRespawnData().globalPos().pos();
            player.setPos(spawn.getX() + 0.5, spawn.getY(), spawn.getZ() + 0.5);
            out[0] = player;
        });
        return out[0];
    }

    /**
     * Runs one command exactly as a player with {@code permission} would, and reports what happened.
     *
     * <p>Goes through {@code CommandDispatcher.execute} rather than
     * {@code Commands.performPrefixedCommand} so the command's own return value comes back. That
     * matters: {@code /tenet} returns 0 for a refusal and 1 for a change, so the return value is
     * the assertion, and reading it is far sturdier than matching on the wording of a message --
     * which would pass if a message were reworded and fail if it were translated.
     *
     * <p>A {@link CommandSyntaxException} is caught and reported as a result of 0 with its message
     * kept, because that is what a real player sees: an unknown or unpermitted command is refused
     * before any of Tenet's code runs. Keeping the message on the outcome is what stops a typo in a
     * test from looking like a refusal by the mod.
     */
    public Outcome run(ServerPlayer player, int permission, String command) {
        Transcript transcript = new Transcript();
        int[] result = new int[1];
        String[] thrown = new String[1];

        onServerThread(() -> {
            // The pack-authored level runs through the same mapping production uses, so a test
            // running at level 2 exercises the same permission set a gamemaster's click would.
            CommandSourceStack source = dev.ellipog.tenet.QuestAuthority.atLevel(
                    server.createCommandSourceStack().withSource(transcript).withEntity(player),
                    permission);
            try {
                result[0] = dispatcher().execute(stripSlash(command), source);
            }
            catch (CommandSyntaxException e) {
                thrown[0] = e.getMessage();
                result[0] = 0;
            }
        });

        return new Outcome(command, result[0], transcript.lines(), thrown[0]);
    }

    /** Runs {@code work} on the server's own thread, and waits for it. */
    public void onServerThread(Runnable work) {
        server.executeBlocking(work);
    }

    /**
     * Runs {@code work} on the server's own thread and returns its result.
     *
     * <p>Needed for reading progress back, which does not look like it needs the server thread and
     * does. A {@code SavedData} is fetched through {@code DimensionDataStorage.computeIfAbsent}, which
     * reads from disk on first access, and Tenet's progress store is one. Reading it from a test
     * thread while the server is ticking would be two threads in the same storage with no lock --
     * rare, silent, and blamed on whatever test happened to be running.
     *
     * <p>{@code executeBlocking} hands back a {@code CompletableFuture} it then joins, so anything
     * thrown inside arrives here wrapped in a {@link java.util.concurrent.CompletionException}. Callers
     * that assert do the unwrapping; see the playthrough's own note.
     */
    public <T> T callOnServerThread(Supplier<T> work) {
        AtomicReference<T> out = new AtomicReference<>();
        server.executeBlocking(() -> out.set(work.get()));
        return out.get();
    }

    /**
     * Waits for {@code condition}, polling, up to {@code timeout}.
     *
     * <p>Needed because the engine is time-based: a task's next evaluation is due a fixed number of
     * ticks after its last one, so "did this complete" has to be asked repeatedly rather than once.
     * Polls rather than sleeping the full time, so a passing test is fast.
     */
    public boolean waitUntil(BooleanSupplier condition, Duration timeout) {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return true;
            }
            sleep(50);
        }
        return condition.getAsBoolean();
    }

    private static String stripSlash(String command) {
        return command.startsWith("/") ? command.substring(1) : command;
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        }
        catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while waiting for the test server", e);
        }
    }

    // ------------------------------------------------------------------
    // Stopping
    // ------------------------------------------------------------------

    /**
     * Stops the server, then the world, then the channels -- in that order.
     *
     * <p>The order is not cosmetic. {@code stopServer} saves the players and the levels, so closing
     * the storage first, or the channels first, would drop exactly the writes the test exists to
     * check. And the server has to be halted or the "Server thread" is not a daemon: a test JVM that
     * keeps a running server alive never exits, and the symptom is a Gradle build that hangs with no
     * failure to read.
     */
    @Override
    public void close() {
        server.halt(true);

        for (EmbeddedChannel channel : channels) {
            try {
                channel.close();
            }
            catch (RuntimeException e) {
                // Nothing here is worth failing a test over: the server is already stopped and the
                // world saved. A channel that will not close is a leak, not a wrong answer.
                System.out.println("  (a mock player's channel did not close: " + e + ")");
            }
        }
        channels.clear();

        try {
            access.close();
        }
        catch (IOException e) {
            System.out.println("  (the world directory did not close cleanly: " + e + ")");
        }

        // The pack repository is deliberately not closed here, and the reason is worth a line because
        // it looks like an omission. There is no close(): PackRepository is not Closeable in 1.21.1 --
        // checked against the artefact, not assumed, after writing this call and getting
        // "cannot find symbol" from a class the compiler had just happily imported. The pack
        // resources are owned by the CloseableResourceManager that WorldLoader created, and
        // ReloadableServerResources closes it as part of the shutdown that halt() above triggered.
        // So closing anything here would be a second close of somebody else's resource.
    }

    // ------------------------------------------------------------------
    // Results
    // ------------------------------------------------------------------

    /**
     * What one command did.
     *
     * @param command what was run, kept so a failure can print the line that caused it
     * @param result  the command's own return value: 0 means it refused and changed nothing
     * @param output  everything the command sent back, in order
     * @param thrown  the parser's message when the command never reached Tenet at all, or null
     */
    public record Outcome(String command, int result, List<String> output, String thrown) {

        /** Whether the command refused. */
        public boolean refused() {
            return result == 0;
        }

        /** Everything the command said, as one block. */
        public String text() {
            return String.join("\n", output);
        }

        /** One line for a transcript a person reads. */
        public String report() {
            String head = String.format("%-52s -> %d", command, result);
            if (thrown != null) {
                head += "  (never ran: " + thrown + ")";
            }
            if (output.isEmpty()) {
                return head;
            }
            return head + "\n" + output.stream().map(line -> "        " + line)
                    .reduce((a, b) -> a + "\n" + b).orElse("");
        }
    }

    /**
     * A {@link CommandSource} that keeps what it is told.
     *
     * <p>{@code acceptsSuccess} and {@code acceptsFailure} both true and {@code shouldInformAdmins}
     * false, which together mean: record everything the command says to this source, and do not
     * broadcast any of it to operators. {@code sendFailure} wraps its message in red and still comes
     * through {@code sendSystemMessage}, so a refusal is in here too -- but the return value is what
     * the tests assert on, and this is for the human reading a failure.
     */
    private static final class Transcript implements CommandSource {

        private final List<String> lines = new ArrayList<>();

        @Override
        public void sendSystemMessage(Component message) {
            lines.add(message.getString());
        }

        @Override
        public boolean acceptsSuccess() {
            return true;
        }

        @Override
        public boolean acceptsFailure() {
            return true;
        }

        @Override
        public boolean shouldInformAdmins() {
            return false;
        }

        private List<String> lines() {
            return List.copyOf(lines);
        }
    }

    /**
     * The smallest server that can run a command.
     *
     * <p>Subclasses {@link MinecraftServer} directly rather than {@code DedicatedServer}, which would
     * drag in a console thread reading {@code System.in}, an RCON thread, a possible GUI, a
     * {@code server.properties} written into the repository, and a network call for auth keys. None
     * of that is wanted in a test, and {@code GameTestServer} makes the same choice for the same
     * reason.
     *
     * <p>The boot is deliberately identical to {@code GameTestServer.initServer}: build a player
     * list, then load the level. Skipping the player list looks harmless and is not --
     * {@code ProgressService} reads it, and {@code placeNewPlayer} needs it.
     */
    private static final class TestServer extends MinecraftServer {

        /**
         * Where tick timings would go, if any were collected.
         *
         * <p>Four dimensions, matching {@code GameTestServer}. Nothing reads it, because
         * {@link #isTickTimeLoggingEnabled()} is false -- but the field cannot simply be dropped, as
         * {@code MinecraftServer} declares {@code getTickTimeLogger()} abstract, so a subclass has to
         * be able to answer. Returning null would compile and then be the sort of latent crash a
         * profiler switch turns on in somebody else's session.
         */
        private final LocalSampleLogger sampleLogger = new LocalSampleLogger(4);

        private TestServer(Thread thread,
                           LevelStorageSource.LevelStorageAccess access,
                           PackRepository packs,
                           WorldStem stem,
                           Services services) {
            // The argument order is the 26.x constructor's, read off GameTestServer's own call:
            // the world's gamerules travel as an Optional (ours are the quiet ones), the load
            // listener is a constructed instance, and the trailing flag is false there too.
            super(thread, access, packs, stem, java.util.Optional.of(quietRules()), Proxy.NO_PROXY,
                    DataFixers.getDataFixer(), services,
                    LoggingLevelLoadListener.forDedicatedServer(), false,
                    new net.minecraft.server.notifications.NotificationManager());
        }

        @Override
        public boolean initServer() {
            // The empty braces are load-bearing, not a slip. PlayerList is declared `abstract` and has
            // no abstract methods at all, so it cannot be instantiated directly but *can* be
            // subclassed trivially -- and vanilla does exactly this in GameTestServer.initServer. The
            // compiler's message here, "PlayerList is abstract; cannot be instantiated", suggests a
            // missing implementation and there is none to write; what it needs is a subclass.
            //
            // The notification service is a silent one: this server notifies nobody about anything.
            setPlayerList(new PlayerList(this, registries(), playerDataStorage,
                    new net.minecraft.server.notifications.NotificationService() {
                        @Override
                        public void playerJoined(ServerPlayer player) {
                        }

                        @Override
                        public void playerLeft(ServerPlayer player) {
                        }

                        @Override
                        public void serverStarted() {
                        }

                        @Override
                        public void serverShuttingDown() {
                        }

                        @Override
                        public void serverSaveStarted() {
                        }

                        @Override
                        public void serverSaveCompleted() {
                        }

                        @Override
                        public void serverActivityOccured() {
                        }

                        @Override
                        public void worldUpgradeStarted() {
                        }

                        @Override
                        public void worldUpgradeProgress(float progress) {
                        }

                        @Override
                        public void worldUpgradeFinished() {
                        }

                        @Override
                        public void worldUpgradeFailed(String reason) {
                        }

                        @Override
                        public void playerOped(net.minecraft.server.players.ServerOpListEntry entry) {
                        }

                        @Override
                        public void playerDeoped(net.minecraft.server.players.ServerOpListEntry entry) {
                        }

                        @Override
                        public void playerAddedToAllowlist(
                                net.minecraft.server.players.NameAndId player) {
                        }

                        @Override
                        public void playerRemovedFromAllowlist(
                                net.minecraft.server.players.NameAndId player) {
                        }

                        @Override
                        public void ipBanned(net.minecraft.server.players.IpBanListEntry entry) {
                        }

                        @Override
                        public void ipUnbanned(String address) {
                        }

                        @Override
                        public void playerBanned(net.minecraft.server.players.UserBanListEntry entry) {
                        }

                        @Override
                        public void playerUnbanned(net.minecraft.server.players.NameAndId player) {
                        }

                        @Override
                        public void statusHeartbeat() {
                        }

                        @Override
                        public <T> void onGameRuleChanged(
                                net.minecraft.world.level.gamerules.GameRule<T> rule, T value) {
                        }
                    }) { });
            loadLevel();
            return true;
        }

        /**
         * Required, and only because {@code MinecraftServer} declares it abstract -- {@code fillSystemReport}
         * works out the rest. Returning the report untouched is what {@code GameTestServer} does.
         *
         * <p>Found by the compiler rather than by reading: the class compiled against a list of
         * abstract members reasoned out by eye, and two were missed. The compiler's list is the
         * complete one.
         */
        @Override
        public SystemReport fillServerSystemReport(SystemReport report) {
            return report;
        }

        /** See {@link #sampleLogger} -- required because the parent declares it abstract. */
        @Override
        public SampleLogger getTickTimeLogger() {
            return this.sampleLogger;
        }

        /** No tick-time sampling. The one thing this server does not need is a profiler. */
        @Override
        public boolean isTickTimeLoggingEnabled() {
            return false;
        }

        /** No native transport in a test: plain NIO, like everything else here. */
        @Override
        public boolean useNativeTransport() {
            return false;
        }

        /** Zero, like GameTestServer: no chat flows in a test either way. */
        @Override
        public int getChatSpamThresholdSeconds() {
            return 0;
        }

        /** Zero, for the same reason: commands in tests are dispatched directly. */
        @Override
        public int getCommandSpamThresholdSeconds() {
            return 0;
        }

        /** Maxed, like GameTestServer: tests run commands at every level through the source. */
        @Override
        public net.minecraft.server.permissions.PermissionSet getFunctionCompilationPermissions() {
            return net.minecraft.server.permissions.LevelBasedPermissionSet.OWNER;
        }

        /** Maxed, like GameTestServer. */
        @Override
        public net.minecraft.server.permissions.LevelBasedPermissionSet operatorUserPermissions() {
            return net.minecraft.server.permissions.LevelBasedPermissionSet.ALL;
        }

        /** One, like GameTestServer: a test needs a player, not a crowd. */
        @Override
        public int getMaxPlayers() {
            return 1;
        }

        @Override
        public boolean shouldRconBroadcast() {
            return false;
        }

        @Override
        public boolean shouldInformAdmins() {
            return false;
        }

        @Override
        public boolean isDedicatedServer() {
            return false;
        }

        @Override
        public boolean isPublished() {
            return false;
        }

        @Override
        public int getRateLimitPacketsPerSecond() {
            return 0;
        }

        @Override
        public boolean isHardcore() {
            return false;
        }

        @Override
        public boolean isSingleplayerOwner(net.minecraft.server.players.NameAndId nameAndId) {
            return false;
        }
    }

}
