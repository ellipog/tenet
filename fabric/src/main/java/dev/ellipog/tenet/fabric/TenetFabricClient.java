package dev.ellipog.tenet.fabric;

import com.mojang.blaze3d.platform.InputConstants;

import dev.ellipog.armature.api.client.ArmatureClient;
import dev.ellipog.armature.api.net.ArmatureNetwork;
import dev.ellipog.armature.client.ArmatureScreens;
import dev.ellipog.tenet.client.viewer.Integrations;
import dev.ellipog.tenet.Constants;
import dev.ellipog.tenet.Tenet;
import dev.ellipog.tenet.client.CanvasSettings;
import dev.ellipog.tenet.client.ClientChapterReplica;
import dev.ellipog.tenet.client.ClientTableOpen;
import dev.ellipog.tenet.client.ClientTableReplica;
import dev.ellipog.tenet.client.ClientTableRoll;
import dev.ellipog.tenet.client.ClientEditReplies;
import dev.ellipog.tenet.client.ClientLocale;
import dev.ellipog.tenet.client.ClientPartyCache;
import dev.ellipog.tenet.client.ClientQuestCache;
import dev.ellipog.tenet.client.viewer.QuestViewerContent;
import dev.ellipog.tenet.net.TenetNetworking;
import dev.ellipog.tenet.client.ClientTicker;
import dev.ellipog.tenet.client.ClientAppearance;
import dev.ellipog.tenet.client.ClientWorking;
import dev.ellipog.tenet.client.DevMode;
import dev.ellipog.tenet.client.InventoryQuestBookButton;
import dev.ellipog.tenet.client.ObservationWatcher;
import dev.ellipog.tenet.client.QuestBookScreen;
import dev.ellipog.tenet.client.QuestNotifier;
import dev.ellipog.tenet.client.hud.HudEditScreen;
import dev.ellipog.tenet.client.hud.HudOverlay;
import dev.ellipog.tenet.client.hud.HudSettings;
import dev.ellipog.tenet.client.hud.PinnedQuests;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenMouseEvents;
import net.fabricmc.fabric.api.client.screen.v1.Screens;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ChatScreen;

import dev.ellipog.armature.client.render.GuiGraphicsRenderer;
import dev.ellipog.armature.client.ui.kit.Measure;

/**
 * Fabric's client half.
 *
 * <p>Separate from {@link TenetFabric} so a dedicated server never loads this class, and so never
 * tries to load {@code KeyMapping}, {@code Screen} or {@code ClientPlayNetworking}.
 *
 * <h2>Five things, and each has a reason for being here</h2>
 *
 * <ul>
 *   <li><b>The screen</b>, registered before the key is declared — or the key would open nothing.</li>
 *   <li><b>The key mapping</b>, declared through Armature so NeoForge's different timing is not this
 *       class's problem.</li>
 *   <li><b>The HUD</b>, which is the one thing here that draws outside a screen: Fabric hands a frame's
 *       context to {@code HudRenderCallback}, and everything about what to draw with it is Tenet's.</li>
 *   <li><b>The client tick</b>, which advances the tick counter cooldowns count down from, and polls
 *       the key.</li>
 *   <li><b>The disconnect hook</b>, which clears the cache. Without it, leaving one server and joining
 *       another shows the first server's questline until the new sync arrives — which looks exactly
 *       like a sync failure and is not one.</li>
 * </ul>
 */
public final class TenetFabricClient implements ClientModInitializer {

    @Override
    public void onInitializeClient() {
        // Declare and install defensively, so this works whether Fabric ran the common initialiser or
        // this one first. Both are idempotent: declaring twice is a no-op, and install keeps the first
        // backend. Without this, a client that ran its client initialiser first would register zero
        // receivers -- because the declarations list was still empty -- and every sync would vanish
        // with no error anywhere.
        Tenet.init();
        ArmatureNetwork.install(new FabricNetworking());

        // The screen first: declaring the key before the screen exists is a key that opens nothing.
        ArmatureScreens.register(Tenet.QUEST_BOOK_SCREEN, QuestBookScreen::new);
        ArmatureScreens.register(Tenet.HUD_EDIT_SCREEN, HudEditScreen::new);

        ArmatureClient.registerKeyMapping(
                Tenet.QUEST_BOOK_SCREEN,
                InputConstants.KEY_B,
                "key.categories.tenet",
                // Refused with the pack's sentence when the pack disabled its book, like every
                // other open. See `QuestBookScreen.checkOpenAllowed`.
                () -> {
                    if (QuestBookScreen.checkOpenAllowed()) {
                        ArmatureClient.openScreen(Tenet.QUEST_BOOK_SCREEN);
                    }
                });

        // The HUD editor's own key. Declared through the same call as the book's, so both loaders get it
        // from one shape and the Controls list names it the same way.
        ArmatureClient.registerKeyMapping(
                Tenet.HUD_EDIT_SCREEN,
                InputConstants.KEY_H,
                "key.categories.tenet",
                () -> ArmatureClient.openScreen(Tenet.HUD_EDIT_SCREEN));

        // The quest book's other door: a control on every container screen, wherever EMI and JEI draw.
        // What to add and where is Tenet's and lives in `InventoryQuestBookButton`; that a widget may be
        // added to somebody else's screen at all is this loader's business, which is why the two lines
        // below are here.
        ScreenEvents.AFTER_INIT.register((client, screen, scaledWidth, scaledHeight) -> {
            InventoryQuestBookButton button = InventoryQuestBookButton.forScreen(screen);
            if (button != null) {
                Screens.getButtons(screen).add(button);
            }
        });

        // Pinned quests answer presses in chat: the cursor is already free there, and the pins draw
        // behind it. Chat only, and left button only -- every other screen owns its clicks, and chat
        // keeps the ones that land nowhere near a pin. A press on a box opens the book on that quest,
        // which closes chat; anything typed but unsent goes with it, which is the documented cost.
        // Per screen instance, through BEFORE_INIT: these subscriptions die with the screen, so each
        // chat gets its own and no other screen ever answers for one.
        ScreenEvents.BEFORE_INIT.register((client, screen, scaledWidth, scaledHeight) -> {
            if (!(screen instanceof ChatScreen)) {
                return;
            }
            ScreenMouseEvents.allowMouseClick(screen).register((chat, mouseX, mouseY, button) -> {
                if (button != 0) {
                    return true;
                }
                var game = Minecraft.getInstance();
                var player = game.player;
                Measure measure = Measure.of(game.font::width, game.font.lineHeight);
                String quest = HudOverlay.pinAt(measure, game.getWindow().getGuiScaledWidth(),
                        game.getWindow().getGuiScaledHeight(), net.minecraft.Util.getMillis(),
                        player == null ? null : player.getUUID(), mouseX, mouseY);
                if (quest == null) {
                    return true;
                }
                QuestBookScreen.openOn(quest);
                return false;
            });
            // The hover half of the hook: the ring a press lands inside of, drawn after chat so it reads
            // over it. Same boxes, same player, one frame later at most -- a ring that lagged the boxes
            // would invite presses onto whatever moved under it.
            ScreenEvents.afterRender(screen).register((chat, context, mouseX, mouseY, tickDelta) -> {
                var game = Minecraft.getInstance();
                var player = game.player;
                Measure measure = Measure.of(game.font::width, game.font.lineHeight);
                HudOverlay.drawPinHover(new GuiGraphicsRenderer(context), measure,
                        game.getWindow().getGuiScaledWidth(), game.getWindow().getGuiScaledHeight(),
                        net.minecraft.Util.getMillis(), player == null ? null : player.getUUID(),
                        mouseX, mouseY);
            });
        });

        // And the layout those lines read, before anything can draw it. Read here for the same reason as
        // the settings below: an inventory opened in the first second of a session must not draw its
        // button somewhere else because the file had not been read yet.
        HudSettings.loadFromConfig();
        // And which quests are pinned, beside it and for the same reason: a HUD drawn in the first second
        // must not show an empty panel because the file had not been read yet.
        PinnedQuests.loadFromConfig();

        // The HUD's own elements: the pinned quests and the notices. This is the seam the round that built
        // the editor deliberately left unattached -- "nothing is drawn on the HUD by this round" -- and it is
        // one line because everything it draws is Tenet's: the hook hands over a drawing context, the wrapper
        // turns it into the toolkit's renderer, and `HudOverlay` decides what is on and where. The game does
        // not call this while the GUI is hidden, so F1 needs no guard from here. The player travels with it
        // because the overlay names no client class: who is looking decides whose claimed quests hide.
        HudRenderCallback.EVENT.register((graphics, tickDelta) -> {
            var player = net.minecraft.client.Minecraft.getInstance().player;
            HudOverlay.render(new GuiGraphicsRenderer(graphics), graphics.guiWidth(), graphics.guiHeight(),
                    net.minecraft.Util.getMillis(), player == null ? null : player.getUUID());
        });

        // The developer screen and its F9 key are gone: the tools are a panel inside the book now,
        // reached from its header by a player who may edit. A key that opened a *different* screen was
        // the wrong shape for a tool whose whole job is watching the canvas it floats over.
        // The setting the screen above edits, read before anything can draw it: a key pressed in the
        // first second of a session must not find the mode off because the file had not been read yet.
        DevMode.loadFromConfig();
        ClientAppearance.loadFromConfig();
        // Where the author was — the last table, the drawer's tab, the Assets section. Read here for the
        // same reason the two above are: a panel opened in the first second of a session must not open on
        // a default because the file had not been read yet.
        ClientWorking.loadFromConfig();
        // The component template library beside it: shipped presets need no file, but saved ones do,
        // and the Templates page must list them from the first open for the same reason as above.
        dev.ellipog.tenet.client.dev.ComponentTemplates.loadFromConfig();
        // And the canvas's own thresholds: how much of it to draw, and from how far out. Read here for the
        // same reason as the rest — a canvas drawn in the first second must not be drawn at the mod's
        // defaults because the file had not been read. See CanvasSettings.
        CanvasSettings.loadFromConfig();

        // The recipe-viewer seam: the content goes in as soon as the client exists, and whichever
        // viewer is installed reads it when it registers. No viewer is named here -- the seam is this
        // mod's, and this only says what the content is.
        Integrations.install(new QuestViewerContent());

        // The client half of Fabric's two-part payload registration.
        FabricClientNetworking.registerClientReceivers();

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            ClientTicker.advance();
            // The crosshair's half of the engine: observation tasks are judged here because a server
            // never sees where a player looks. See ObservationWatcher.
            ObservationWatcher.tick(client);
            ArmatureClient.tick();
            // The viewer's half: the content's revision check, then the one chosen adapter's tick.
            Integrations.tick();
            // The notice half: the one detector of completions and claims, which speaks whether or not
            // the book is open. See QuestNotifier.
            QuestNotifier.tick();
            // The HUD's two halves of the same bookkeeping: what is pinned is checked against the tree
            // once per revision, and the notices on screen are aged out. Both read a value rather than a
            // screen, so neither needs a window to be open.
            PinnedQuests.tick();
            HudOverlay.tick();
            // A command-entered player's-view preview hides vanilla chat (the command and its
            // reply are history no player would have) and restores it on the way out. See
            // QuestBookScreen.tickPreviewChat for why this is reconciled rather than toggled.
            QuestBookScreen.tickPreviewChat();
            // The language half: a player who changed language in the options is told to the server, so
            // the book follows them without a reconnect. Guarded on being in a world, because there is
            // no connection to send on before one and the server answers a join on its own.
            if (client.player != null) {
                ClientLocale.pollLocale(client.getLanguageManager().getSelected());
            }
        });

        // A payload can arrive for a world being left, and a cache holding it would be read by the
        // next world's screen. Cleared on disconnect rather than on connect, because the last sync of
        // a session is likely to arrive after any connect-time hook has already run.
        //
        // `forgetTransfers` bisects that, and it has to be here rather than inside the cache: the
        // cache knows about quests, and the half-received chunks belong to the networking. A player
        // who disconnects mid-tree would otherwise leave chunks in memory that no later message can
        // complete -- the next connection's transfer ids are fresh ones -- so they would sit there
        // until the cap in SyncWire.Reassembler evicted them.
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            ClientQuestCache.clear();
            // And the language: another server's translations are not this one's to draw, and a stale
            // overlay would answer for keys the new server's pack spells differently -- or never says.
            ClientLocale.clear();
            ClientChapterReplica.clear();
            ClientTableReplica.clear();
            ClientTableRoll.clear();
            ClientTableOpen.clear();
            // The party roster too: it is written by the roster message and nothing else empties it, so
            // without this the next world's panel opens on the last server's party -- every row a real
            // player name, which is what makes it convincing. The join-time push corrects it a moment
            // later, and "a moment later" is a frame of somebody else's party on screen.
            ClientPartyCache.clear();
            // And the edit answers in flight for a world being left: the next screen must not report them.
            ClientEditReplies.clear();
            // And what the canvas held, including its rebuild count: that one is cumulative, so a count
            // carried into the next world would read as a canvas that had been churning when nothing had
            // happened yet. See `CanvasStats`.
            dev.ellipog.tenet.client.dev.CanvasStats.clear();
            // The half-counted observations go with the tree they were counted against.
            ObservationWatcher.reset();
            // And the completion diff, or the next server's progress would be read against this one's
            // states and a quest that was locked here and complete there would announce a completion
            // that never happened in front of this player.
            QuestNotifier.reset();
            // And the notices drawn on the HUD: a sentence about the world just left must not be the first
            // thing read in the next one. The pinned quests are deliberately *not* cleared here -- they are
            // the player's own list and outlive a world, which is why `PinnedQuests.tick` refuses to prune
            // against an empty tree rather than being asked to skip one.
            HudOverlay.clear();
            TenetNetworking.forgetTransfers();
        });

        Constants.LOG.info("Tenet: Fabric client ready");
    }
}
