package dev.ellipog.tenet.quest.condition;

import net.minecraft.resources.Identifier;

/**
 * Something that has to be true for a task to count, or for a reward to be paid.
 *
 * <p>An interface, not a sealed hierarchy, because which condition types exist is the addon API — the
 * same rule {@link dev.ellipog.tenet.quest.task.QuestTaskType} states. A condition in JSON carries a
 * type field, which selects the codec that reads the rest:
 *
 * <pre>{@code
 * { "type": "tenet:stage", "stage": "my_pack:inducted" }
 * }</pre>
 *
 * <h2>There is deliberately no codec field here, and no default methods</h2>
 *
 * <p>For the reason {@link dev.ellipog.tenet.quest.QuestTask} spells out at length: initialising a
 * class initialises the superinterfaces that declare default methods, so a static codec on an interface
 * that records implement can be captured half-initialised the first time a type's codec is read. The
 * codec lives in {@link ConditionTypes#dispatchCodec()}, built lazily, and this interface holds no
 * static state — not even a convenience method, because the convenience is what caused the cycle there.
 */
public interface QuestCondition {

    /** Which registered type this is. The value of the JSON {@code "type"} field. */
    Identifier type();
}
