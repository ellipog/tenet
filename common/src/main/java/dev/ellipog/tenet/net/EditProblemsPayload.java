package dev.ellipog.tenet.net;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * What the reload found wrong, pushed to the authors who are editing.
 *
 * <h2>The faults nothing else can see</h2>
 *
 * <p>The per-file validator runs inside {@code QuestEditor.save} and refuses a file the loader would reject —
 * but it only ever sees <b>one file at a time</b>. Three faults are therefore invisible to it and were written
 * silently, with only the server log knowing:
 *
 * <ul>
 *   <li>a {@code dependsOn} naming a quest that is not in the pack;</li>
 *   <li>a cycle between quests in different files;</li>
 *   <li>an id declared in two chapters.</li>
 * </ul>
 *
 * <p>Each is a fault of the <i>pack</i> rather than of a file, so it can only be found by the index that reads
 * them all — which is the reload's own work. The author who caused one saw their edit succeed and nothing else,
 * and the quest was then quietly absent from the tree or unreachable.
 *
 * <h2>Why this arrives after the reply rather than with it</h2>
 *
 * <p>Because it cannot arrive with it. An edit is applied and answered <b>synchronously</b>, and the reload is
 * coalesced to one per server tick — so at the moment the reply is written the pack has not been re-read and
 * the cross-file faults do not exist yet as an answer. Attaching them to the reply would mean either reloading
 * per op, which is the coalescing this codebase deliberately added, or reporting the <i>previous</i> reload's
 * faults, which is a message about a state the author has already changed.
 *
 * <p>So the reply stays what it is — "your edit was applied and written" — and this carries the separate fact,
 * as soon as it is knowable.
 *
 * @param lines how many problems there are, so a truncated list can say what it is not showing
 * @param text  the problems, one per line, as {@code DataProblem.render} wrote them, or "" for none
 */
public record EditProblemsPayload(int lines, String text) implements CustomPacketPayload {

    /** The payload's id. The constructor, not {@code createType} — see {@link QuestSyncPayload#TYPE}. */
    public static final CustomPacketPayload.Type<EditProblemsPayload> TYPE =
            new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath("tenet", "edit_problems"));

    /** As much as one message carries. Beyond it the count says what was left out. */
    public static final int MAX_CHARS = 8192;

    public static final StreamCodec<? super RegistryFriendlyByteBuf, EditProblemsPayload> CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.VAR_INT, EditProblemsPayload::lines,
                    // One string with newlines, like the reply's messages, and for the same reason: the
                    // feedback line shows one and the chat shows them one after another.
                    ByteBufCodecs.stringUtf8(MAX_CHARS), EditProblemsPayload::text,
                    EditProblemsPayload::new);

    /** The problems as a list, which is how both readers of them want them. */
    public java.util.List<String> problems() {
        return text == null || text.isEmpty()
                ? java.util.List.of()
                : java.util.List.of(text.split("\n"));
    }

    /**
     * The payload for one load's problems, or <b>null</b> when there is nothing to say.
     *
     * <h2>Why the rendering lives here and not at the send site</h2>
     *
     * <p>Because it is the payload's own shape: the count and the lines are two fields of this record, and
     * the rule that keeps them consistent — the count is how many there <i>are</i>, the text is as many as
     * fit — is a fact about this message rather than about the network layer that sends it. It was inline in
     * {@code TenetNetworking}, where two senders would each have needed a copy and where no test could
     * reach it; here the truncation and the count are asserted directly.
     *
     * <p>Null rather than an empty payload, because "nothing is wrong" is not a message. Both callers want
     * to send nothing at all in that case — one to every player, one to a player arriving — and returning a
     * payload of zero lines would put a report about nothing on the screen of everyone who joins a healthy
     * server.
     *
     * <h2>One line per problem, and why the path is not one of them</h2>
     *
     * <p>This wrote {@code renderWithPath}, which is two lines: the fault, and an indented {@code at $.tasks[2]}
     * naming the JSON field. Both readers of this payload put a line on a screen — the Chapter tab's list and
     * the toast stack — and the toast stack shows one line per entry, so the second line became a toast of its
     * own: a card reading "at $" with nothing else on it, twice for two problems. The path is validator
     * detail; the file and the line already locate the fault, and the server's own log keeps the full form.
     */
    public static EditProblemsPayload of(dev.ellipog.armature.api.data.Problems problems) {
        if (problems == null || problems.isEmpty()) {
            return null;
        }
        java.util.List<dev.ellipog.armature.api.data.DataProblem> all = problems.all();
        StringBuilder text = new StringBuilder();
        int shown = 0;
        for (dev.ellipog.armature.api.data.DataProblem problem : all) {
            String line = problem.render();
            // The count travels separately, so a list cut short here says how many it is not showing rather
            // than reading as the whole of it.
            if (text.length() + line.length() + 1 > MAX_CHARS) {
                break;
            }
            if (shown > 0) {
                text.append('\n');
            }
            text.append(line);
            shown++;
        }
        return new EditProblemsPayload(all.size(), text.toString());
    }

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
