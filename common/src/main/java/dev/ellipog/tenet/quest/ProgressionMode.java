package dev.ellipog.tenet.quest;

import dev.ellipog.armature.api.data.Codecs;
import com.mojang.serialization.Codec;

/**
 * How a chapter hands out its quests.
 *
 * <p>The legacy format calls these the same thing, and they exist because of a real authoring problem: a
 * chapter that is a chain of thirty quests should not need thirty {@code dependsOn} entries, and the
 * one place the chain breaks should be visible rather than buried.
 */
public enum ProgressionMode {

    /**
     * A quest unlocks as soon as its own dependencies are met. The default, and the right one for a
     * chapter laid out as a tree or a web.
     */
    FLEXIBLE,

    /**
     * A quest unlocks only when every quest <b>before it in the list</b> is complete, as well as its
     * own dependencies. So the order quests appear in the file becomes meaningful, and a chapter of
     * thirty can be a chain with no {@code dependsOn} anywhere.
     *
     * <p>The cost is that reordering quests in the file changes the progression — which is the point,
     * but is worth knowing before someone tidies a chapter alphabetically.
     */
    LINEAR;

    public static final Codec<ProgressionMode> CODEC = Codecs.enumByName(ProgressionMode.class);
}
