package dev.ellipog.tenet.quest.reward;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import dev.ellipog.tenet.Tenet;
import dev.ellipog.tenet.net.RewardToastPayload;
import dev.ellipog.tenet.quest.QuestReward;
import dev.ellipog.tenet.quest.QuestText;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

/**
 * Run a command as the player, with the legacy placeholders.
 *
 * <pre>{@code { "type": "tenet:command", "command": "say {p} finished {quest}", "permissionLevel": 2 } }</pre>
 *
 * <p>The escape hatch every pack reaches for, and the one reward whose reach is the whole server — so
 * it runs through the server's own dispatcher with the player as the source, at the permission level
 * the reward names. the legacy placeholders are kept whole, because a pack moved from it should not have
 * to learn a second vocabulary for the same sentence.
 *
 * <p>{@code feedbackMessage} is the legacy {@code feedback_message}: a message shown when the
 * command runs. Absent means nothing extra is shown — the claim flow already says what it took —
 * which is the common case (ATM10 sets it nowhere). A {@code disableToast} reward runs the command
 * silently: the command still runs, only the message is withheld.
 */
public record CommandReward(RewardCommon common, String command, int permissionLevel, boolean silent,
                            Optional<QuestText> feedbackMessage)
        implements QuestReward {

    public static final ResourceLocation TYPE = ResourceLocation.fromNamespaceAndPath(Tenet.MOD_ID, "command");

    public static final Set<String> FIELDS = Set.of("command", "permissionLevel", "silent", "feedbackMessage");

    public static final MapCodec<CommandReward> MAP_CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
            RewardCommon.MAP_CODEC.forGetter(CommandReward::common),
            Codec.STRING.fieldOf("command").forGetter(CommandReward::command),
            // the legacy default migrated to 2; two is also its default, and it is enough for the
            // commands packs actually write ("say", "give", "summon") without opening /op.
            Codec.intRange(0, 4).optionalFieldOf("permissionLevel", 2).forGetter(CommandReward::permissionLevel),
            Codec.BOOL.optionalFieldOf("silent", false).forGetter(CommandReward::silent),
            QuestText.CODEC.optionalFieldOf("feedbackMessage").forGetter(CommandReward::feedbackMessage)
    ).apply(instance, CommandReward::new));

    @Override
    public ResourceLocation type() {
        return TYPE;
    }

    public static final RewardBehaviour<CommandReward> BEHAVIOUR = (reward, context) -> {
        CommandSourceStack source = context.player().createCommandSourceStack();
        if (reward.permissionLevel() > 0) {
            source = source.withPermission(reward.permissionLevel());
        }
        if (reward.silent()) {
            source = source.withSuppressedOutput();
        }
        context.server().getCommands().performPrefixedCommand(source, substitute(reward.command(), context));
        // The author's own success line, when there is one and the reward is announced. The command
        // itself always runs — quieting only withholds the message, the same split a toast reward
        // makes between granting and showing.
        if (!reward.common().disableToast()) {
            reward.feedbackMessage().ifPresent(message -> {
                Component text = message.component();
                if (!text.getString().isEmpty()) {
                    context.player().displayClientMessage(text, false);
                    dev.ellipog.armature.api.net.ArmatureNetwork.sendToPlayer(context.player(),
                            new RewardToastPayload(message.value(), message.translatable(),
                                    message.fallback().orElse("")));
                }
            });
        }
    };

    /**
     * The command with every placeholder filled in.
     *
     * <p>An unknown brace-word is left as written rather than blanked: a command that says
     * {@code {player}} to an operator reading the log is a one-second fix, and a command that silently
     * loses the word is a mystery.
     */
    static String substitute(String command, RewardContext context) {
        var pos = context.player().blockPosition();
        return command
                .replace("{p}", context.player().getScoreboardName())
                .replace("{x}", String.valueOf(pos.getX()))
                .replace("{y}", String.valueOf(pos.getY()))
                .replace("{z}", String.valueOf(pos.getZ()))
                .replace("{chapter}", context.chapterId())
                .replace("{quest}", context.questId())
                .replace("{team}", String.valueOf(context.owner()))
                .replace("{team_id}", String.valueOf(context.owner()))
                .replace("{long_team_id}", String.valueOf(context.owner()))
                .replace("{member_count}", String.valueOf(context.members().size()))
                .replace("{online_member_count}", String.valueOf(context.members().size()));
    }

    public static final Function<CommandReward, RewardDisplay> DISPLAY = reward ->
            RewardDisplay.ofTranslatableText("tenet.reward.command", "Run " + reward.command(),
                    reward.command(), 1);
}
