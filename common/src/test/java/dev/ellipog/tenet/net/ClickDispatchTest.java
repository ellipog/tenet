package dev.ellipog.tenet.net;

import dev.ellipog.tenet.api.TenetEvents;
import dev.ellipog.tenet.quest.Fixtures;
import dev.ellipog.tenet.quest.QuestIndex;

import net.minecraft.resources.Identifier;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What a canvas press asks the server to do, resolved against the authoritative index.
 *
 * <h2>Why the resolution is the thing under test, rather than the wire</h2>
 *
 * <p>The wire carries identity and nothing else — that is {@link PayloadTest}'s property, and it is
 * asserted there. What decides whether anything runs is this resolution: the command or the event
 * id comes from the server's own index, the click still has to <i>be</i> that kind of action, and
 * the level is the pack's answer capped at 2. A forged payload names nothing, a stale one runs
 * nothing, and neither can choose what the server runs — and each of those is arithmetic on an
 * index, which is what makes it headless.
 *
 * <p>The two things this cannot do headless are running a command against a dispatcher and posting
 * an event to a player, and both need a server: the first runs in the playthrough, and the second
 * is a listener away — the bus itself is asserted here, with no player, because delivery is the
 * bus's only promise.
 */
@DisplayName("a canvas press, resolved")
class ClickDispatchTest {

    /** A chapter with one press of each server-side kind, one stale press, and one blank command. */
    private static QuestIndex index() {
        return Fixtures.indexOf(Fixtures.fileWithChapter(
                "\"elements\": ["
                        + " { \"type\": \"image\", \"id\": \"runner\","
                        + "   \"image\": { \"sprite\": \"minecraft:block/stone\" },"
                        + "   \"click\": { \"type\": \"run_command\", \"data\": \"say {p} pressed {element}\" } },"
                        + " { \"type\": \"image\", \"id\": \"signaller\","
                        + "   \"image\": { \"sprite\": \"minecraft:block/stone\" },"
                        + "   \"click\": { \"type\": \"custom_event\", \"data\": \"my_pack:sounded\" } },"
                        + " { \"type\": \"image\", \"id\": \"opener\","
                        + "   \"image\": { \"sprite\": \"minecraft:block/stone\" },"
                        + "   \"click\": { \"type\": \"open_quest\", \"data\": \"a\" } },"
                        + " { \"type\": \"image\", \"id\": \"blank\","
                        + "   \"image\": { \"sprite\": \"minecraft:block/stone\" },"
                        + "   \"click\": { \"type\": \"run_command\", \"data\": \"\" } } ],",
                Fixtures.q("a").build()));
    }

    @Test
    @DisplayName("a command press resolves to the file's own words, at the pack's capped level")
    void aCommandPressResolves() {
        TenetNetworking.ClickCommand resolved = TenetNetworking
                .resolveClickCommand(index(), "chapter", "runner", 0).orElseThrow();

        assertEquals("say {p} pressed {element}", resolved.command(),
                "the words travel, unsubstituted: substitution is the dispatch's job");
        assertEquals(0, resolved.level(), "the pack's answer, uncapped");
        assertEquals("chapter", resolved.chapterId());
        assertEquals("runner", resolved.elementId());
    }

    @Test
    @DisplayName("the level is the pack's answer, floored at zero and never above two")
    void theLevelIsCapped() {
        assertEquals(2, TenetNetworking.resolveClickCommand(index(), "chapter", "runner", 2)
                .orElseThrow().level(), "elevated is elevated");
        assertEquals(2, TenetNetworking.resolveClickCommand(index(), "chapter", "runner", 9)
                .orElseThrow().level(), "and nothing is above two, whatever the file claims");
        assertEquals(0, TenetNetworking.resolveClickCommand(index(), "chapter", "runner", -3)
                .orElseThrow().level(), "and nothing is below zero");
    }

    @Test
    @DisplayName("a forged, stale or blank press resolves to nothing at all")
    void anythingElseResolvesToNothing() {
        QuestIndex index = index();
        assertTrue(TenetNetworking.resolveClickCommand(index, "nowhere", "runner", 0).isEmpty(),
                "an unknown chapter names nothing");
        assertTrue(TenetNetworking.resolveClickCommand(index, "chapter", "ghost", 0).isEmpty(),
                "an unknown element names nothing");
        assertTrue(TenetNetworking.resolveClickCommand(index, "chapter", "opener", 0).isEmpty(),
                "an element the author has since changed to another action runs nothing");
        assertTrue(TenetNetworking.resolveClickCommand(index, "chapter", "blank", 0).isEmpty(),
                "and a blank command is not a command");
        assertTrue(TenetNetworking.resolveClickCommand(null, "chapter", "runner", 0).isEmpty(),
                "and neither side may be missing");
    }

    @Test
    @DisplayName("an event press resolves to its id, and anything else resolves to nothing")
    void anEventPressResolves() {
        TenetNetworking.ClickEventFire resolved = TenetNetworking
                .resolveClickEvent(index(), "chapter", "signaller").orElseThrow();

        assertEquals(Identifier.fromNamespaceAndPath("my_pack", "sounded"), resolved.event());
        assertEquals("chapter", resolved.chapterId());
        assertEquals("signaller", resolved.elementId());

        QuestIndex index = index();
        assertTrue(TenetNetworking.resolveClickEvent(index, "chapter", "ghost").isEmpty(),
                "an unknown element fires nothing");
        assertTrue(TenetNetworking.resolveClickEvent(index, "chapter", "runner").isEmpty(),
                "and a command is not an event, however it is addressed");
    }

    @Test
    @DisplayName("placeholders fill in, and an unknown brace-word stays as written")
    void placeholdersSubstitute() {
        assertEquals("say elli pressed sounder from first_steps",
                TenetNetworking.substituteClick("say {p} pressed {element} from {chapter}", "elli",
                        10, 64, -3, "first_steps", "sounder"));
        assertEquals("at 10 64 -3",
                TenetNetworking.substituteClick("at {x} {y} {z}", "elli", 10, 64, -3, "c", "e"));
        assertEquals("do {player} now",
                TenetNetworking.substituteClick("do {player} now", "elli", 0, 0, 0, "c", "e"),
                "an unknown brace-word is left for the operator reading the log, not blanked");
    }

    @Test
    @DisplayName("the bus delivers a click event to whoever is listening, with its place attached")
    void theEventBusDelivers() {
        List<String> seen = new ArrayList<>();
        TenetEvents.CLICK_EVENT.register((player, id, chapter, element) ->
                seen.add(id + "@" + chapter + ":" + element));

        TenetEvents.CLICK_EVENT.invoker().onClickEvent(null,
                Identifier.fromNamespaceAndPath("my_pack", "sounded"), "first_steps", "sounder");

        assertEquals(List.of("my_pack:sounded@first_steps:sounder"), seen,
                "the id, the chapter and the element travel: two pictures firing one id are still two");
    }
}
