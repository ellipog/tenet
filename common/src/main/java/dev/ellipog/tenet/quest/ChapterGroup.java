package dev.ellipog.tenet.quest;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import java.util.List;
import java.util.Optional;

/**
 * A shelf of chapters — the legacy "chapter group".
 *
 * <p>Exists so a large pack can say "Tutorial / Progression / Endgame" and put twenty chapters
 * underneath, rather than scrolling one flat list of twenty. The extra level of nesting costs one
 * record and makes the navigation panel readable at scale, which is why the legacy project grew the same
 * level after not having it.
 *
 * <h2>Its description is a list of paragraphs, and that was found the hard way</h2>
 *
 * <p>Not one string. {@link Chapter}'s description has always been a list, and {@link Quest}'s own
 * javadoc argues the point at length — a single string with {@code \n} in it is what the legacy format takes,
 * and it is why its quest descriptions become one undifferentiated wall of text. That argument does
 * not stop applying one level up.
 *
 * <p>It was a single {@code Optional<QuestText>} until a second shipped questline caught it by being
 * written the obvious way: an array of lines, exactly like a chapter's, failed to decode, and the
 * failure arrived as a codec message at <b>line 1 column 1</b> saying the format had rejected the
 * file. That is the exact failure this project's whole validator exists to prevent — a message that
 * names the wrong place, about a field the validator had never been taught to look at, so it stayed
 * silent and let the codec speak from the root of the file.
 *
 * <p>A bare string is still accepted, so nothing that works today stops working. The union costs one
 * {@code xmap}: a one-line group description written as a string is not a mistake worth failing a
 * file over.
 */
public record ChapterGroup(
        String id,
        QuestText title,
        List<QuestText> description,
        Optional<Icon> icon,
        List<String> aliases,
        boolean collapsedByDefault,
        /**
         * Words this group answers to in lookups by tag.
         *
         * <p>the legacy {@code tags}, present on every object. Each tag is
         * {@code ^[a-z0-9_]{1,64}$}, the same rule an id follows, and a lookup of
         * {@code "#tag"} resolves to the first quest, chapter or group carrying it.
         */
        List<String> tags,
        List<Chapter> chapters
) {

    /**
     * The field names this contributes, for the validator to allow.
     *
     * <p>Declared here rather than in the validator's own list, and that is a correction rather than a
     * preference. The validator said its field sets "mirror a record's declared fields" and then held
     * its own copies — so adding {@code collapsedByDefault} to this record was a two-file edit with
     * nothing to catch a missed half. The symptom of getting it wrong is a field the codec reads and
     * the validator reports as unknown, which is a file that works with checking disabled.
     *
     * <p>{@link GroupManifest} declares the same set, and the duplication is honest: a manifest and the
     * group it becomes describe the same object, so they allow the same fields. The two are kept in
     * step by a test rather than by a comment.
     */
    public static final java.util.Set<String> FIELDS = java.util.Set.of(
            "id", "title", "description", "icon", "aliases", "collapsedByDefault", "tags", "chapters");

    /** Finds a chapter by id or alias. */
    public Optional<Chapter> chapter(String idOrAlias) {
        return chapters.stream().filter(chapter -> chapter.matches(idOrAlias)).findFirst();
    }

    /**
     * Whether {@code idOrAlias} refers to this group.
     *
     * <p>Without regard to letter case, because lookups are. See {@link QuestIndex}.
     */
    public boolean matches(String idOrAlias) {
        return id.equalsIgnoreCase(idOrAlias)
                || aliases.stream().anyMatch(alias -> alias.equalsIgnoreCase(idOrAlias));
    }

    public static final Codec<ChapterGroup> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Codec.STRING.fieldOf("id").forGetter(ChapterGroup::id),
            QuestText.CODEC.fieldOf("title").forGetter(ChapterGroup::title),
            // Accepts either shape. The union is QuestText's now -- see `LIST_OR_ONE`, which says why it
            // moved there and why a one-element list comes back as a bare string.
            QuestText.LIST_OR_ONE.optionalFieldOf("description", List.of())
                    .forGetter(ChapterGroup::description),
            // Optional, unlike a chapter's own icon: a group with none falls back on the client to the
            // first chapter under it, so absent and "authored as paper" have to stay distinguishable.
            Icon.CODEC.optionalFieldOf("icon").forGetter(ChapterGroup::icon),
            Codec.STRING.listOf().optionalFieldOf("aliases", List.of()).forGetter(ChapterGroup::aliases),
            // Whether the book shows this group's chapters the first time it sees the tree.
            //
            // False by default, which is the honest default for a *version-1* file: the field is new,
            // so every existing flat file has it absent, and absent has to mean "as it has always
            // looked" -- open. A pack that wants a long progression collapsed says so per group.
            //
            // Note what this is not: it is not written to `config/armature/appearance.json`, and no
            // client ever persists it there. That file is the player's and Armature's; this is a
            // property of the questline, and it applies once, on the first sight of a tree. See
            // Outline.seedFromDefaults.
            Codec.BOOL.optionalFieldOf("collapsedByDefault", false).forGetter(ChapterGroup::collapsedByDefault),
            Codec.STRING.listOf().optionalFieldOf("tags", List.of()).forGetter(ChapterGroup::tags),
            Chapter.CODEC.listOf().optionalFieldOf("chapters", List.of()).forGetter(ChapterGroup::chapters)
    ).apply(instance, ChapterGroup::new));
}
