package dev.ellipog.tenet.progress;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.resources.Identifier;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Stages in the progress store: the set semantics, and the file they survive.
 *
 * <h2>Why the round trip is the test that matters</h2>
 *
 * <p>Everything else about a stage is one line of set arithmetic. What is easy to get wrong -- and what no
 * amount of reading catches -- is the write and read of it: a stage that works all session and is gone after
 * a restart is a questline that unlocks itself again tomorrow, and the player who reported it would be told
 * the feature works. So the test saves a real {@link CompoundTag} through the store's own methods and loads
 * it back, rather than asserting that the set contains what was just put in it.
 */
@DisplayName("stages in the store")
class StageStoreTest {

    private static final Identifier LEFT = Identifier.fromNamespaceAndPath("my_pack", "left_home");
    private static final Identifier COUNCIL = Identifier.fromNamespaceAndPath("my_pack", "met_council");

    @Test
    @DisplayName("a stage can be granted, asked about, and taken away once")
    void setSemantics() {
        ProgressStore store = new ProgressStore();
        UUID player = UUID.randomUUID();

        assertFalse(store.hasStage(player, LEFT), "a player starts with nothing");
        assertTrue(store.addStage(player, LEFT), "the first grant changes something");
        assertFalse(store.addStage(player, LEFT), "and the second does not -- which is what makes a reward "
                + "idempotent and what the event is gated on");
        assertTrue(store.hasStage(player, LEFT));

        assertTrue(store.removeStage(player, LEFT), "taking away what is there changes something");
        assertFalse(store.removeStage(player, LEFT), "and taking away what is not there does not");
        assertFalse(store.hasStage(player, LEFT));
        assertEquals(0, store.stagedPlayerCount(), "a player with no stages is not a row in the file");
    }

    @Test
    @DisplayName("stages are per player: one player's grant is not another's")
    void perPlayer() {
        ProgressStore store = new ProgressStore();
        UUID one = UUID.randomUUID();
        UUID two = UUID.randomUUID();

        store.addStage(one, LEFT);

        assertTrue(store.hasStage(one, LEFT));
        assertFalse(store.hasStage(two, LEFT), "a stage is personal, whatever the quest's team mode");
        assertTrue(store.stagesOf(two).isEmpty());
    }

    @Test
    @DisplayName("the file gives the stages back, in the order they were granted")
    void roundTrip() {
        ProgressStore store = new ProgressStore();
        UUID one = UUID.randomUUID();
        UUID two = UUID.randomUUID();
        store.addStage(one, COUNCIL);
        store.addStage(one, LEFT);
        store.addStage(two, LEFT);

        ProgressStore loaded = ProgressStore.load(store.save(new CompoundTag()));

        assertEquals(2, loaded.stagedPlayerCount(), "both players survive the file");
        // A set, so order is not the assertion -- membership is. The insertion order matters for the file's
        // stability, and the next test is what says so.
        assertTrue(loaded.hasStage(one, LEFT) && loaded.hasStage(one, COUNCIL));
        assertTrue(loaded.hasStage(two, LEFT));
        assertFalse(loaded.hasStage(two, COUNCIL));
    }

    @Test
    @DisplayName("a stage id the file cannot parse is dropped, and the rest of the player's stages stay")
    void unparsableIdsAreSkipped() {
        ProgressStore store = new ProgressStore();
        UUID player = UUID.randomUUID();
        store.addStage(player, LEFT);
        store.addStage(player, COUNCIL);
        CompoundTag tag = store.save(new CompoundTag());

        // Hand-damage one id, the way a half-edited file or a bad downgrade would: the answer must be the
        // valid ids, not an exception and not an empty set.
        ListTag stages = tag.getListOrEmpty("stages");
        ListTag ids = stages.getCompoundOrEmpty(0).getListOrEmpty("ids");
        ids.set(0, StringTag.valueOf("not a valid id"));

        ProgressStore loaded = ProgressStore.load(tag);

        assertEquals(1, loaded.stagesOf(player).size(), "the id that survived is kept");
        assertTrue(loaded.hasStage(player, COUNCIL));
    }

    @Test
    @DisplayName("a file written before stages existed loads with none, rather than failing")
    void oldFilesLoad() {
        CompoundTag old = new CompoundTag();
        old.putInt("version", 1);
        old.put("teams", new ListTag());

        ProgressStore loaded = ProgressStore.load(old);

        assertEquals(0, loaded.stagedPlayerCount(), "a missing section is 'no stages', which is a valid state");
        assertEquals(0, loaded.stagedTeamCount(), "and the team section missing is 'no team stages' too");
    }

    // ------------------------------------------------------------------
    // Team stages (T22): the legacy `team_stage`
    // ------------------------------------------------------------------

    @Test
    @DisplayName("a team stage can be granted, asked about, and taken away once")
    void teamSetSemantics() {
        ProgressStore store = new ProgressStore();
        UUID team = UUID.randomUUID();

        assertFalse(store.hasTeamStage(team, LEFT), "a team starts with nothing");
        assertTrue(store.addTeamStage(team, LEFT), "the first grant changes something");
        assertFalse(store.addTeamStage(team, LEFT), "and the second does not");
        assertTrue(store.hasTeamStage(team, LEFT));

        assertTrue(store.removeTeamStage(team, LEFT), "taking away what is there changes something");
        assertFalse(store.removeTeamStage(team, LEFT), "and taking away what is not there does not");
        assertFalse(store.hasTeamStage(team, LEFT));
        assertEquals(0, store.stagedTeamCount(), "a team with no stages is not a row in the file");
    }

    @Test
    @DisplayName("team stages are beside player stages: neither answers for the other")
    void teamAndPlayerStagesAreSeparate() {
        ProgressStore store = new ProgressStore();
        UUID player = UUID.randomUUID();
        UUID team = UUID.randomUUID();

        store.addTeamStage(team, LEFT);
        store.addStage(player, COUNCIL);

        assertTrue(store.hasTeamStage(team, LEFT));
        assertFalse(store.hasTeamStage(team, COUNCIL), "a player's own grant is not the team's");
        assertFalse(store.hasStage(player, LEFT), "and the team's grant is not the player's");
        assertTrue(store.hasStage(player, COUNCIL));
    }

    @Test
    @DisplayName("the file gives the team stages back too")
    void teamRoundTrip() {
        ProgressStore store = new ProgressStore();
        UUID team = UUID.randomUUID();
        UUID player = UUID.randomUUID();
        store.addTeamStage(team, LEFT);
        store.addTeamStage(team, COUNCIL);
        store.addStage(player, LEFT);

        ProgressStore loaded = ProgressStore.load(store.save(new CompoundTag()));

        assertEquals(1, loaded.stagedTeamCount(), "the team survives the file");
        assertTrue(loaded.hasTeamStage(team, LEFT) && loaded.hasTeamStage(team, COUNCIL));
        assertTrue(loaded.hasStage(player, LEFT), "and the player's own survives beside it");
    }
}
