package dev.ellipog.tenet.quest.task;

import dev.ellipog.armature.api.data.TypeSpec;
import dev.ellipog.tenet.quest.ItemRef;
import dev.ellipog.tenet.quest.QuestTask;

/**
 * A kind of task, as a registered type.
 *
 * <p>Public, and public on purpose: this is how another mod adds a task type. Tenet registers eighteen
 * and would like there to be twenty more.
 *
 * <p>The behaviour is part of the type rather than a field on each task, because how a task is
 * evaluated follows from what kind of task it is. An addon registering a type registers how it
 * works; it does not have to be looked up separately, and it cannot go missing.
 */
public interface QuestTaskType<T extends QuestTask> extends TypeSpec<T> {

    /**
     * The item shown in the quest's detail panel to represent this task — a knowledge book for a
     * checkmark, a chest for an item task.
     *
     * <p>Kept on the type rather than on each task, so the icon is chosen once by whoever wrote the
     * type rather than repeated in every quest file.
     */
    ItemRef icon();

    /** How this type decides whether a task is satisfied. */
    TaskBehaviour<T> behaviour();

    /**
     * A fresh instance of this type, for the editor's Add picker.
     *
     * <p>What "add an item task" starts as: paper, one, nothing consumed. On the type rather than in
     * the editor, for the same reason the icon is — the person who wrote the type knows what a
     * reasonable empty one looks like, and an addon's type is addable the day it registers rather
     * than the day somebody edits a switch in the editor.
     */
    T defaults();

    /**
     * What this task asks for, as a client should draw it.
     *
     * <p>Required rather than defaulted on purpose. A default would mean a new task type silently
     * rendering as a blank row with a generic icon, and the person who wrote the type would be the
     * last to find out — they would have to open the quest book to notice. Making it abstract means
     * the compiler asks the question at the moment the type is written, which is when the answer is
     * in hand.
     */
    TaskDisplay display(T task);

    /**
     * The fields as the in-game editor draws them: their order, their labels, and each one's control.
     *
     * <p>Declared by the type, so a new task type is a new <b>form</b> rather than a new branch in the
     * screen -- and an addon's type is tailored by the same registration this mod's types use. A type
     * that declares nothing gets a form derived from {@link #fields()}: one control per name, guessed
     * from the name, so an addon is usable the day it registers; see {@code EditorSpecs}.
     */
    default java.util.List<dev.ellipog.tenet.quest.EditorField> editor() {
        return java.util.List.of();
    }
}
