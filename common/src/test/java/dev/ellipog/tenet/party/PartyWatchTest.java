package dev.ellipog.tenet.party;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Which party changes {@link PartyWatch} treats as moves nobody announced.
 *
 * <p>The decision is {@link PartyWatch#moves}, which takes three maps and no server, so every case is
 * one line here. What a move then does (keep what the leaver earned, refresh the books) needs a world,
 * and the playthrough asserts it against a real server with a parties mod that announces nothing.
 */
class PartyWatchTest {

    private static final UUID ALEX = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    private static final UUID SAM = UUID.fromString("00000000-0000-0000-0000-00000000000b");
    private static final UUID CREW = UUID.fromString("00000000-0000-0000-0000-0000000000c1");
    private static final UUID OTHERS = UUID.fromString("00000000-0000-0000-0000-0000000000c2");

    @Test
    @DisplayName("the first look at somebody with no record is a baseline, not a move")
    void aFirstLookIsABaseline() {
        // The first start after an update: everybody already in a party is new to the record, and
        // reading that as "everybody just joined" would send every player a full sync for nothing.
        assertEquals(List.of(), PartyWatch.moves(Map.of(), Set.of(), now(ALEX, CREW)));
        assertEquals(List.of(), PartyWatch.moves(Map.of(), Set.of(), now(ALEX, null)));
    }

    @Test
    @DisplayName("a player seen solo who is now in a party joined it")
    void joiningIsAMove() {
        assertEquals(List.of(new PartyWatch.Move(ALEX, null, CREW)),
                PartyWatch.moves(Map.of(), Set.of(ALEX), now(ALEX, CREW)));
    }

    @Test
    @DisplayName("a player recorded in a party who is now solo left it, seen this session or not")
    void leavingIsAMoveEvenForSomebodyNotSeen() {
        // Not seen this session covers both cases the record is saved for: a member kicked while
        // offline, and a member who left before a restart. Either way they keep what they earned.
        assertEquals(List.of(new PartyWatch.Move(SAM, CREW, null)),
                PartyWatch.moves(Map.of(SAM, CREW), Set.of(), now(SAM, null)));
        assertEquals(List.of(new PartyWatch.Move(SAM, CREW, null)),
                PartyWatch.moves(Map.of(SAM, CREW), Set.of(SAM), now(SAM, null)));
    }

    @Test
    @DisplayName("moving straight from one party to another is one move with both ends")
    void switchingPartiesIsOneMove() {
        assertEquals(List.of(new PartyWatch.Move(ALEX, CREW, OTHERS)),
                PartyWatch.moves(Map.of(ALEX, CREW), Set.of(ALEX), now(ALEX, OTHERS)));
    }

    @Test
    @DisplayName("nobody who is where they were last seen has moved")
    void standingStillIsNotAMove() {
        assertEquals(List.of(), PartyWatch.moves(Map.of(ALEX, CREW), Set.of(ALEX), now(ALEX, CREW)));
        assertEquals(List.of(), PartyWatch.moves(Map.of(), Set.of(ALEX), now(ALEX, null)),
                "seen solo and still solo");
    }

    @Test
    @DisplayName("each player is judged alone, so one mover does not drag the rest of a look with it")
    void playersAreJudgedOneByOne() {
        Map<UUID, UUID> now = new HashMap<>();
        now.put(ALEX, CREW);
        now.put(SAM, null);
        assertEquals(List.of(new PartyWatch.Move(SAM, CREW, null)),
                PartyWatch.moves(Map.of(ALEX, CREW, SAM, CREW), Set.of(ALEX, SAM), now));
    }

    /** One player's answer now, which may be solo, so a map that accepts a null value. */
    private static Map<UUID, UUID> now(UUID player, UUID party) {
        Map<UUID, UUID> out = new HashMap<>();
        out.put(player, party);
        return out;
    }
}
