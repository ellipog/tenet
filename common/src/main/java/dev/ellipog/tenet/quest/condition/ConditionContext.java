package dev.ellipog.tenet.quest.condition;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.UUID;

/**
 * Who a condition is being asked about, and on what server.
 *
 * <p>A condition is asked once per player — the member whose count is being read on a shared task, or
 * the player a reward is being paid to — so the player here is the subject, not "the team". A party
 * condition is the same predicate for every member; a stage, inventory or score condition is not, and
 * the engine's per-member asking is what makes that the natural reading rather than a special case.
 *
 * @param player the player the condition is asked about
 * @param server the server they are on
 * @param owner  the progress owner this evaluation belongs to — the UUID a party's shared progress is
 *               keyed by. No condition reads it today: {@code tenet:party_size} asks the
 *               <b>player</b>, resolving them to their team itself, because the subject of a
 *               condition is the player and never the team. It is here because the evaluation has to
 *               know whose progress it is resolving, and a party condition is the one that looked
 *               like it might be the exception.
 * @param presentCount the subject's team members online, when the caller already holds them — the
 *               tick's member walk, which is the whole team on screen. Null everywhere else, where
 *               the behaviour resolves what it needs itself. Only {@code tenet:party_size} reads
 *               it: every other condition is per player rather than per team, so a shared count
 *               would be the wrong answer for them, not a faster one.
 */
public record ConditionContext(ServerPlayer player, MinecraftServer server, UUID owner,
                               Integer presentCount) {

    /** Without a held count: the behaviour resolves what it needs itself. */
    public ConditionContext(ServerPlayer player, MinecraftServer server, UUID owner) {
        this(player, server, owner, null);
    }
}
