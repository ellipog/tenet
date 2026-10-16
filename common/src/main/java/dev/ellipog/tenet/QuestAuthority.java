package dev.ellipog.tenet;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.Permissions;

import java.util.function.Predicate;

/**
 * Who may change quest files.
 *
 * <h2>One rule, because three things ask it</h2>
 *
 * <p>`/tenet reload` and the editor's payload handler both mean *"may this player change the questline"*,
 * and the design notes settled the answer as a single number: **permission level 2**. Written here once and
 * read by all of them, because the failure mode of two copies is not a typo — it is a command that says yes
 * where the wire says no, which is a permission rule nobody can reason about from either side.
 *
 * <p>The book's split control gates the same number, so the button is hidden from players who may not press
 * it. That gate is a courtesy and this one is the check: a client that asked anyway gets a refusal from the
 * handler, which is the only place a check can live when the client is not the authority.
 */
public final class QuestAuthority {

    /** The permission level a player needs to change quest files. */
    public static final int EDIT_LEVEL = 2;

    private QuestAuthority() {
    }

    /** Whether a player may change quest files. The server's question, asked of a player. */
    public static boolean mayEdit(ServerPlayer player) {
        // Level 2 is gamemaster now: the int-level check is gone, and the permission set carries
        // the named level instead.
        return player != null
                && player.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER);
    }

    /**
     * The client's projection of the same rule: the permissions the server synced for command
     * suggestions, which reflect the player's real standing. Never an enforcement -- the handler
     * still refuses anyone who asks anyway -- only the courtesy gate that hides the editor.
     *
     * <p>Client-only, and only ever called there: like every other client reference from this
     * shared class, it must never run on a dedicated server.
     */
    public static boolean mayEditClient(net.minecraft.client.Minecraft minecraft) {
        if (minecraft == null || minecraft.getConnection() == null) {
            return false;
        }
        return minecraft.getConnection().getSuggestionsProvider().permissions()
                .hasPermission(Permissions.COMMANDS_GAMEMASTER);
    }

    /** The same rule as a command predicate, so every command that changes files asks it the same way. */
    public static Predicate<CommandSourceStack> mayEdit() {
        return source -> source.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER);
    }

    /**
     * A pack-authored command level as a permission set: 1-4 elevate to the matching named rung,
     * anything higher clamps to owner, and 0 (or below) strips to nothing.
     *
     * <p>One mapping for every place a file names a level -- the command reward and the clickable
     * element -- because two copies would drift about what 2 means. The strip at 0 is the old
     * {@code withPermission(int)} behaviour, which replaced the source's level rather than
     * elevating it: a level-0 reward runs with nothing, not as the player.
     */
    public static CommandSourceStack atLevel(CommandSourceStack source, int level) {
        return switch (level) {
            case 1 -> source.withPermission(
                    net.minecraft.server.permissions.LevelBasedPermissionSet.MODERATOR);
            case 2 -> source.withPermission(
                    net.minecraft.server.permissions.LevelBasedPermissionSet.GAMEMASTER);
            case 3 -> source.withPermission(
                    net.minecraft.server.permissions.LevelBasedPermissionSet.ADMIN);
            case 4 -> source.withPermission(
                    net.minecraft.server.permissions.LevelBasedPermissionSet.OWNER);
            default -> level > 4
                    ? source.withPermission(
                            net.minecraft.server.permissions.LevelBasedPermissionSet.OWNER)
                    : source.withPermission(
                            net.minecraft.server.permissions.PermissionSet.NO_PERMISSIONS);
        };
    }
}
