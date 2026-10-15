package dev.ellipog.tenet.client;

import net.minecraft.resources.Identifier;

import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * The stages this player has, as the server told us.
 *
 * <p>Kept on the client for the same reason the world's dimension list is: a script, or a screen, that wants
 * to ask "does this player have X" should not need a round trip to find out. The server is still the only
 * authority -- this is a projection of what it last said, and a refusal happens there.
 *
 * <p>Written from a payload handler and read from wherever, both on the client thread, with the volatile
 * field for the one moment that is genuinely cross-thread: a disconnect clearing it.
 */
public final class ClientStages {

    private ClientStages() {
    }

    private static volatile Set<Identifier> stages = Set.of();

    /** Takes the server's list from the wire. Anything unparsable is dropped rather than poisoning the set. */
    public static void accept(List<String> ids) {
        Set<Identifier> parsed = new TreeSet<>(java.util.Comparator.comparing(Identifier::toString));
        for (String id : ids) {
            Identifier stage = Identifier.tryParse(id);
            if (stage != null) {
                parsed.add(stage);
            }
        }
        stages = Set.copyOf(parsed);
    }

    /** Every stage this player has. */
    public static Set<Identifier> all() {
        return stages;
    }

    /** Whether this player has the stage, by id. */
    public static boolean has(Identifier stage) {
        return stages.contains(stage);
    }

    /** Forgets them: another server's stages are not this one's to answer for. */
    public static void clear() {
        stages = Set.of();
    }
}
