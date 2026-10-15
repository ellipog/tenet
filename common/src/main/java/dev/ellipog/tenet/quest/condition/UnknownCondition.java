package dev.ellipog.tenet.quest.condition;

import net.minecraft.resources.Identifier;

/**
 * A condition whose type this build has no codec for, kept rather than thrown away.
 *
 * <p>The condition half of {@link dev.ellipog.tenet.quest.task.UnknownTask}: a gate naming a type
 * that is not registered here, which before this refused the whole document rather than the one node.
 * See {@code TypeDispatch}.
 *
 * <h2>It has no behaviour, and that is the safe direction rather than an omission</h2>
 *
 * <p>A condition is a gate, so the two ways of being unable to evaluate one are not equally safe: a
 * gate that cannot be read and is treated as <b>open</b> is a lock that silently opens, and a reward or
 * a task behind it pays out on a rule nobody checked. {@link Conditions} already answers this — it
 * evaluates an unregistered type as <b>not met</b>, with that reasoning in its javadoc — and this record
 * deliberately adds nothing, because {@code ConditionTypes.behaviourOf} finding no entry is what
 * produces that answer.
 *
 * <p>So there is no {@code BEHAVIOUR} constant here and there must not be one. Registering a permissive
 * one would be the one change that turns "kept and visible" into "kept and ignored", which is worse
 * than the file being refused.
 *
 * <p>This interface declares no default methods on purpose — see {@link QuestCondition} for the
 * class-initialisation cycle that caused — so this record implements exactly one accessor and holds no
 * static state.
 */
public record UnknownCondition(Identifier type) implements QuestCondition {
}
