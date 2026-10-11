package dev.ellipog.tenet;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import com.mojang.brigadier.CommandDispatcher;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The {@code /quests} and {@code /questbook} aliases.
 *
 * <p>Pure Brigadier shape — no player, no server, no loader — so this runs headless. Building
 * the nodes is all registration does; what each subcommand <i>does</i> is covered by the
 * playthrough test, which runs the real tree on a server.
 */
@DisplayName("the /quests and /questbook aliases")
class CommandAliasesTest {

    @Test
    @DisplayName("both aliases redirect to the /tenet root")
    void aliasesRedirectToRoot() {
        var dispatcher = new CommandDispatcher<CommandSourceStack>();
        TenetCommand.register(dispatcher);

        var root = dispatcher.getRoot().getChild(Tenet.MOD_ID);
        assertNotNull(root, "/tenet is registered");

        for (String alias : new String[] {"quests", "questbook"}) {
            var node = dispatcher.getRoot().getChild(alias);
            assertNotNull(node, "/" + alias + " is registered");
            assertSame(root, node.getRedirect(),
                    "/" + alias + " parses into the /tenet tree rather than a copy of it");
            assertNotNull(node.getCommand(),
                    "bare /" + alias + " runs something (it opens the book)");
        }
    }

    @Test
    @DisplayName("an alias another mod claimed is left alone")
    void claimedAliasIsLeftAlone() {
        var dispatcher = new CommandDispatcher<CommandSourceStack>();
        dispatcher.register(Commands.literal("quests"));
        TenetCommand.register(dispatcher);

        assertNull(dispatcher.getRoot().getChild("quests").getRedirect(),
                "another mod's /quests keeps its own meaning; Tenet only skips the alias");
        assertNotNull(dispatcher.getRoot().getChild("questbook"),
                "the unclaimed alias still registers");
    }
}
