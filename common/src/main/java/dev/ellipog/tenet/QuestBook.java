package dev.ellipog.tenet;

import dev.ellipog.armature.api.ArmatureApi;
import dev.ellipog.armature.api.event.ArmatureEvents;
import dev.ellipog.armature.api.registry.Registrar;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;

/**
 * Tenet's game objects.
 *
 * <p>Registration is the one place the two loaders genuinely differ in mechanism, and this is
 * what exercises Armature's {@link Registrar} on both: Fabric writes to the registry
 * immediately, NeoForge queues the entry on Tenet's own event bus and creates it later.
 *
 * <p><b>Must run during mod construction.</b> On NeoForge the registration window closes
 * before the game finishes loading, and a late call throws. That is a deliberate design in
 * Armature: a clear failure at construction beats an item that silently does not exist.
 */
public final class QuestBook {

    private QuestBook() {
    }

    public static final Identifier ITEM_ID = Identifier.fromNamespaceAndPath(Tenet.MOD_ID, "quest_book");

    /** Held so a later stage can hand it out, or put it in a quest reward. */
    public static Item ITEM;

    /** Whether {@link #register} has run. See its javadoc for why the side effect guards itself. */
    private static boolean registered;

    /**
     * Registers the quest book item, once.
     *
     * <h2>Why the guard is here as well as on {@code Tenet.init}</h2>
     *
     * <p>Both are needed and they guard different things. {@code init}'s flag stops a second entry
     * point from re-running <i>initialisation</i>; this one stops <i>this registration</i> from
     * happening twice, however many callers appear. A registration is a one-shot write to a global
     * registry, so the flag belongs at the write — a third entry point, a reload hook or a test that
     * calls this directly would otherwise reintroduce exactly the bug below.
     *
     * <h2>What a second call used to do, because it is silent by default</h2>
     *
     * <p>Nothing threw. {@code MappedRegistry.register} builds a duplicate-key
     * {@code IllegalStateException} and then feeds it to {@code Util.pauseInIde}, which
     * <i>returns</i> it and whose return value the caller discards — so it only ever reports anything
     * when {@code SharedConstants.IS_RUNNING_IN_IDE} is true. In a released game the registry simply
     * gains a second item under the same name with a different raw id, and the failure surfaces much
     * later and elsewhere: joining a server, where {@code fabric-registry-sync} cannot remap the
     * client's item-model map and disconnects with
     * <i>"Map contained two equal IDs … (tenet:quest_book/1335 -> tenet:quest_book/1334)"</i>.
     *
     * <p>Armature's {@code FabricRegistrar} now refuses a duplicate id for every mod, which is where
     * a general fix belongs — this flag is the specific one, and it means the mistake cannot be made
     * here at all.
     */
    public static void register() {
        if (registered) {
            return;
        }
        registered = true;

        Registrar registrar = ArmatureApi.registrar().forMod(Tenet.MOD_ID);

        registrar.register(BuiltInRegistries.ITEM, ITEM_ID,
                () -> {
                    // On 26.x an item reads its own id out of its Properties at construction
                    // (`Item.<init>` resolves the description id through it), so the key is set
                    // into the Properties before the item exists -- constructing first and
                    // registering after, the 1.21 shape, dies in the constructor with
                    // "Item id not set". The key is pure (no registry access), so building it
                    // here is safe on both loaders and in headless tests.
                    var key = net.minecraft.resources.ResourceKey.create(
                            net.minecraft.core.registries.Registries.ITEM, ITEM_ID);
                    Item item = new QuestBookItem(new Item.Properties().setId(key).stacksTo(1));
                    ITEM = item;
                    return item;
                });
    }
}
