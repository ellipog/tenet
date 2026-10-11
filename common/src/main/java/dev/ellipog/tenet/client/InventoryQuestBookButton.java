package dev.ellipog.tenet.client;

import dev.ellipog.armature.api.client.ArmatureClient;
import dev.ellipog.armature.client.ArmatureButton;
import dev.ellipog.tenet.QuestBook;
import dev.ellipog.tenet.Tenet;
import dev.ellipog.tenet.client.hud.ContainerPanel;
import dev.ellipog.tenet.client.hud.HudElement;
import dev.ellipog.tenet.client.hud.HudLayout;
import dev.ellipog.tenet.client.hud.HudSettings;
import dev.ellipog.tenet.client.hud.InventoryPanel;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

/**
 * The quest book's other door: a control over container screens, in the window's top-left corner.
 *
 * <h2>Where it is</h2>
 *
 * <p>Two pixels in from the window's corner, which is where it ships and where {@link HudSettings} keeps it
 * until a player moves it in the HUD editor. With FTB Library's sidebar around it ships one sidebar cell
 * to the right instead -- see {@code FtbCompat} -- because that column draws from the same corner and
 * would otherwise sit on top of it. That corner is also contested by the recipe viewers -- JEI lays its
 * bookmarks out across the strip left of a container panel, and EMI's default left sidebar is its favourites
 * page -- and the answer to those is the editor and the switch rather than a different corner: a player can
 * put the button anywhere or turn it off, and it is worth having somewhere easy to find.
 *
 * <p>A player who puts it by the inventory rather than in the corner can anchor it to the panel instead of
 * the window, from the button's own row in the HUD editor. A window pixel that is "under the inventory" at
 * one GUI scale stops being under it at the next, because the panel is centred and moves with the window;
 * an offset from the panel's own corner survives that, because the live corner is re-added every time the
 * button is placed -- an inventory open, a resize, the editor's next frame -- including when the recipe
 * book slides the panel across the window. Window is the default and stays it; inventory is the opt-in.
 *
 * <p>It is offered on every container screen -- the player's own inventory in survival <b>and</b> in
 * creative, chests, crafting, furnaces, modded interfaces and anything else built on
 * {@code AbstractContainerScreen}, which is the same set of screens EMI and JEI draw beside. An inventory
 * is where a player looks for what is theirs, and a chest they opened for ten seconds is where they look
 * next: a door that only opens from one room is a door half the pack never finds. An inventory
 * anchor is resolved against the inventory panel's live corner on the two inventory screens, and
 * against the open container's own live panel everywhere else -- read through the screen's fields,
 * so an uncentred panel still reads correctly. An offset parked above or below the inventory keeps
 * its gap outside a taller chest instead of being swallowed by it, and likewise left and right of
 * a wider panel; inside, it tracks the corner the way a slot does. When the panel cannot be read
 * the closed-book ghost the HUD editor drops against keeps the button's place.
 *
 * <h2>Why it acts on press</h2>
 *
 * <p>Because a container screen never delivers a release to its widgets: {@code AbstractContainerScreen}
 * overrides {@code mouseReleased} and {@code mouseDragged} without calling {@code super}, so the release that
 * {@link ArmatureButton} acts on never arrives, and a control wired the usual way would look alive and do
 * nothing. Acting on press is also what vanilla's own container-screen buttons do -- {@code onClick} is a
 * press callback -- so this is the ordinary behaviour rather than a workaround, and it needs no release, no
 * clock and no state that could get stuck.
 *
 * <p>Opening the book is the whole action. There is nothing to close first: replacing the screen
 * closes the menu it replaced -- an inventory close tells the server nothing, a container close tells
 * it through the screen's own removal -- and a stack on the cursor stays on the cursor. The book's key
 * does exactly this, and has since the book existed.
 *
 * <h2>Why it is placed once and not every frame</h2>
 *
 * <p>Because there is nothing for it to follow. The position is a window coordinate -- or an offset from
 * the panel's live corner, when the player anchored it to the inventory -- and the widget is rebuilt
 * from it whenever a screen initialises, which both loaders do on a resize; and {@link HudLayout} clamps it
 * into the window it is given. That is why this class has no per-frame drawing hook and no place on the seam
 * list: it reads a position, it opens a screen on a press, and it draws itself through the base class like
 * every other control.
 */
public final class InventoryQuestBookButton extends ArmatureButton {

    /** The table entry this control is the drawing of. */
    private static final HudElement ELEMENT = HudElement.INVENTORY_BUTTON;

    private InventoryQuestBookButton(int x, int y, Component label) {
        super(x, y, ELEMENT.width(), ELEMENT.height(), label, InventoryQuestBookButton::open);
        // The same icon at the same inset the editor's preview draws, both taken from the element's own
        // entry: the box is the table's, so the sprite that fills it has to be the table's too.
        icon(new ItemStack(QuestBook.ITEM)).iconInset(ELEMENT.iconInset());
        setTooltip(Tooltip.create(label));
    }

    /**
     * The control for this screen, or null when this screen should not have one.
     *
     * <p>Three answers, and each is a different reason: the player switched it off, this is not a container
     * screen, or the book item is not registered yet -- the last only reachable if a screen somehow opened
     * before mod construction finished, in which case no button is better than a stack trace in a screen.
     */
    public static InventoryQuestBookButton forScreen(Screen screen) {
        if (!HudSettings.on(ELEMENT)) {
            return null;
        }
        // **Every container, not just the two inventories.** `InventoryScreen` and
        // `CreativeModeInventoryScreen` are both `EffectRenderingInventoryScreen`s and nothing more, so
        // asking for `InventoryScreen` alone answered null in creative -- and the report was exactly that:
        // *"the icon top left in inventory for quest book is not there in creative mode"*. Two explicit
        // names rather than the shared parent, because the rule used to be the **player's own inventory**.
        // The rule now is wherever EMI and JEI draw: every `AbstractContainerScreen` -- chests, crafting,
        // furnaces, modded interfaces -- which both inventories already are, so they need no special case
        // to keep working.
        if (!(screen instanceof AbstractContainerScreen)) {
            return null;
        }
        if (QuestBook.ITEM == null) {
            return null;
        }
        boolean isInventory = (screen instanceof InventoryScreen)
                || (screen instanceof CreativeModeInventoryScreen);
        if (!isInventory) {
            if (HudSettings.origin(ELEMENT) == HudElement.Origin.WINDOW) {
                // A window pixel is a window pixel on every screen: the same numbers the editor
                // writes, clamped onto the window like every other position.
                var windowBox = HudLayout.boxAt(ELEMENT, screen.width, screen.height,
                        HudSettings.x(ELEMENT), HudSettings.y(ELEMENT),
                        ELEMENT.width(), ELEMENT.height());
                return new InventoryQuestBookButton(windowBox.x(), windowBox.y(),
                        Component.translatable("tenet.screen.quest_book.title"));
            }
            // Inventory-anchored: read the offset against the live container panel, so a button put
            // above or below the inventory is pushed up or down by a taller or wider panel instead of
            // being swallowed by it. An offset outside is a gap from the nearest edge (see
            // `InventoryPanel.againstPanel`); inside it is pixels from the corner, the way a slot is.
            // Null when the panel cannot be read, in which case the ghost -- the closed-book survival
            // panel at this window's size, the corner the editor drops against -- keeps the button's
            // place rather than its gap.
            var container = ContainerPanel.rect(screen);
            int windowX;
            int windowY;
            if (container != null) {
                int[] on = InventoryPanel.againstPanel(HudSettings.x(ELEMENT), HudSettings.y(ELEMENT),
                        container.width(), container.height());
                windowX = container.x() + on[0];
                windowY = container.y() + on[1];
            }
            else {
                var ghost = InventoryPanel.rect(screen.width, screen.height, true, false);
                windowX = HudLayout.windowX(HudSettings.x(ELEMENT), ghost.x());
                windowY = HudLayout.windowY(HudSettings.y(ELEMENT), ghost.y());
            }
            var windowBox = HudLayout.boxAt(ELEMENT, screen.width, screen.height,
                    windowX, windowY,
                    ELEMENT.width(), ELEMENT.height());
            return new InventoryQuestBookButton(windowBox.x(), windowBox.y(),
                    Component.translatable("tenet.screen.quest_book.title"));
        }
        // The panel's live corner, recomputed from the numbers the game centres from rather than read
        // from a field nothing here can see -- see `InventoryPanel`. Only the recipe book moves it, and
        // only on survival; creative is always centred. An inventory-anchored button is re-based on this
        // corner every time it is placed, which is what carries it across GUI scales and window sizes.
        boolean survival = screen instanceof InventoryScreen;
        boolean bookOpen = survival
                && ((InventoryScreen) screen).getRecipeBookComponent().isVisible();
        var panel = InventoryPanel.rect(screen.width, screen.height, survival, bookOpen);
        var box = HudSettings.origin(ELEMENT) == HudElement.Origin.INVENTORY
                ? HudLayout.boxAtInventory(panel.x(), panel.y(),
                        HudSettings.x(ELEMENT), HudSettings.y(ELEMENT),
                        ELEMENT.width(), ELEMENT.height(), screen.width, screen.height)
                : HudLayout.boxAt(ELEMENT, screen.width, screen.height,
                        HudSettings.x(ELEMENT), HudSettings.y(ELEMENT),
                        ELEMENT.width(), ELEMENT.height());
        return new InventoryQuestBookButton(box.x(), box.y(),
                Component.translatable("tenet.screen.quest_book.title"));
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        boolean handled = super.mouseClicked(mouseX, mouseY, button);
        if (handled) {
            // On press, and the screen is replaced a moment later, which is what takes the press state with
            // it -- see the class note.
            open(this);
        }
        return handled;
    }

    private static void open(ArmatureButton button) {
        // Refused with the pack's sentence when the pack disabled its book, like every other
        // client-side open. See `QuestBookScreen.checkOpenAllowed`.
        if (!QuestBookScreen.checkOpenAllowed()) {
            return;
        }
        ArmatureClient.openScreen(Tenet.QUEST_BOOK_SCREEN);
    }
}
