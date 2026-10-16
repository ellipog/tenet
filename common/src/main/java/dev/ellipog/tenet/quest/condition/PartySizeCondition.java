package dev.ellipog.tenet.quest.condition;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import dev.ellipog.armature.api.teams.Teams;
import dev.ellipog.tenet.Tenet;

import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;

import java.util.Set;
import java.util.function.Function;

/**
 * Be in a party of at least this many, right now.
 *
 * <pre>{@code { "type": "tenet:party_size", "min": 2 } }</pre>
 *
 * <p><b>Online members, not the roster.</b> The engine only ever counts the members who are present —
 * a shared task is evaluated against the players actually here — and a togetherness gate satisfied by
 * somebody who is offline would be a gate nobody could see through. A solo player is a party of one,
 * so {@code min: 2} is the "bring a friend" gate.
 *
 * <p>The count is of {@code teamOf}, which never returns nothing: a player with no team is a party of
 * themselves, which is the same answer the engine's own evaluation uses.
 */
public record PartySizeCondition(int min) implements QuestCondition {

    public static final Identifier TYPE = Identifier.fromNamespaceAndPath(Tenet.MOD_ID, "party_size");

    public static final Set<String> FIELDS = Set.of("min");

    public static final MapCodec<PartySizeCondition> MAP_CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
            Codec.intRange(1, 1000).fieldOf("min").forGetter(PartySizeCondition::min)
    ).apply(instance, PartySizeCondition::new));

    @Override
    public Identifier type() {
        return TYPE;
    }

    public static final ConditionBehaviour<PartySizeCondition> BEHAVIOUR = (condition, context) -> {
        // Held by the tick's member walk, which is the whole team on screen: one count for every
        // member rather than one team resolve plus one player-list scan each. The walk and this
        // run on one thread with nothing between them that could move a player, so the held count
        // is this count. Every other path passes none and resolves below, exactly as before.
        Integer known = context.presentCount();
        if (known != null) {
            return known >= condition.min();
        }
        var team = Teams.teamOf(context.server(), context.player().getUUID());
        int present = 0;
        for (ServerPlayer online : context.server().getPlayerList().getPlayers()) {
            if (team.isMember(online.getUUID())) {
                present++;
            }
        }
        return present >= condition.min();
    };

    public static final Function<PartySizeCondition, ConditionDisplay> DISPLAY = condition -> {
        String subject = String.valueOf(condition.min());
        return ConditionDisplay.ofTranslatableText("tenet.condition.party_size",
                "Be in a party of " + subject + " or more", subject);
    };
}
