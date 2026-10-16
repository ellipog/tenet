package dev.ellipog.tenet.quest;

import com.mojang.serialization.Codec;
import net.minecraft.resources.Identifier;

/**
 * Something a player has to do to complete a quest.
 *
 * <p>An interface, not a sealed hierarchy, because which task types exist is the addon API — see
 * {@link dev.ellipog.tenet.quest.task.QuestTaskType}. The set of types is open at runtime.
 *
 * <p>A task in JSON carries a type field, which selects the codec that reads the rest:
 *
 * <pre>{@code
 * { "type": "tenet:item", "item": "minecraft:oak_log", "count": 8 }
 * }</pre>
 *
 * <h2>There is deliberately no codec field here</h2>
 *
 * <p>There was, and it was a class-initialisation cycle that compiled cleanly and failed at runtime:
 *
 * <pre>
 * TaskTypes.&lt;clinit&gt;   reads ItemTask.MAP_CODEC
 *   ItemTask.&lt;clinit&gt;  initialises QuestTask first, because QuestTask declares a default method
 *     QuestTask.&lt;clinit&gt;  reads TaskTypes.DISPATCH_CODEC
 *       TaskTypes is already initialising on this thread, so the JVM hands back null
 *     QuestTask.CODEC = null, permanently, because it is static final
 * </pre>
 *
 * <p>The JLS rule is real and easy to miss: initialising a class initialises its superinterfaces that
 * declare default methods. So {@link #optional()} — a one-line convenience — was enough to pull
 * {@code QuestTask} into an initialisation that had already started.
 *
 * <p>The codec lives in {@link dev.ellipog.tenet.quest.task.TaskTypes#dispatchCodec()} instead, built
 * lazily. That way this interface holds no static state, so nothing can be half-initialised, and the
 * cycle cannot re-form if someone later adds a field here. If you do need a codec, call the accessor
 * rather than caching the result in a constant.
 */
public interface QuestTask {

    /** Which registered type this is. The value of the JSON {@code "type"} field. */
    Identifier type();

    /** Settings every task has. */
    TaskCommon common();

    default boolean optional() {
        return common().optional();
    }
}
