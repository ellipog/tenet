package dev.ellipog.tenet.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.ellipog.armature.client.Look;
import dev.ellipog.tenet.Constants;
import dev.ellipog.tenet.net.QuestSync;
import dev.ellipog.tenet.progress.QuestState;
import dev.ellipog.tenet.quest.CanvasElement;
import dev.ellipog.tenet.quest.DependencyStyle;
import dev.ellipog.tenet.quest.QuestLayout;
import dev.ellipog.tenet.quest.QuestShape;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * What the client knows about the questline, without a server.
 *
 * <h2>Why the client does not just read the quest files itself</h2>
 *
 * <p>It has them on disk. In a single-player world it could parse them directly and it would work. On
 * a multiplayer server it would not — the client has whatever files <i>it</i> has, which may be none,
 * may be an older version, and on a server running a datapack is guaranteed to be the wrong one. Even
 * in single player, a client reading its own copy would be judging itself against a different snapshot
 * from the server's.
 *
 * <p>So the server is the only authority, and this is a projection of what it said.
 *
 * <h2>Everything here runs on the client thread</h2>
 *
 * <p>Written from a payload handler, read from a screen, and both of those are the client thread —
 * that is where the loaders deliver payloads. So no synchronisation is needed and adding any would be
 * a lie about a contention that does not exist. The volatile fields are for the one moment that is
 * genuinely cross-thread: a disconnect clearing the whole thing.
 */
public final class ClientQuestCache {

    private ClientQuestCache() {
    }

    /**
     * One task, resolved ready to draw.
     *
     * <p>The item is resolved here, on arrival, rather than on every frame. A registry lookup per node
     * per frame for a screen showing eighty quests is work with no purpose: registries do not change
     * while a client is connected, so a resolution can be made once and kept.
     */
    public record TaskEntry(ItemStack icon, ItemStack item, int count, boolean optional, boolean manual,
                            /**
                             * Whether the press is what finishes this task, rather than the tick. It
                             * decides which of the two button rules applies: a handed-in-before-the-count
                             * task (a checkmark) is offered while its count is unmet, and a task that
                             * takes is offered once the press would be accepted -- see
                             * {@link #firstSubmitTask}. Absent means the tick registers it, which is the
                             * old reading and therefore the safe one for a server that predates the field.
                             */
                            boolean waits,
                            /**
                             * Whether the tick never measures this task at all — the legacy
                             * {@code task_screen_only} under Tenet's {@code manualOnly} name. Absent
                             * means the tick measures, which is every task a version-18 server ever
                             * sent. The button rule reads this rather than the live count, because the
                             * live count of a task the tick never measures is always zero.
                             */
                            boolean manualOnly,
                            String type, String label, String labelFallback, String labelArg, String itemId,
                            /** The observation fields, empty for every other type: what to watch, how. */
                            String observeType, String observeTarget, int observeTicks,
                            /**
                             * The item tag a {@code tenet:item_tag} task hands in, empty for every other
                             * type. The same distinction {@code itemId} draws for item tasks: a viewer
                             * cannot find a tag task from an item without knowing which tag it is, and
                             * its sentence is not a field.
                             */
                            String tagId,
                            /** The gates this task carries, for the locked row's hover. Empty for none. */
                            List<ConditionEntry> conditions,
                            /**
                             * Whether this task's completion is announced. The legacy
                             * {@code disable_toast} on the task. Absent on the wire means announced,
                             * which is what a version-16 server always says.
                             */
                            boolean disableToast,
                            /**
                             * The texture path when the author overrode the picture with a texture.
                             * Empty means the type's own picture — an author item or egg travels in
                             * {@code picture}, exactly as a type's would.
                             */
                            String textureIcon,
                            /**
                             * The atlas region when the author overrode the picture with a sprite.
                             * Empty means the type's own picture. Never beside a texture: the
                             * display carries one override, like the file it came from.
                             */
                            String spriteIcon,
                            /**
                             * The item picture, since version 21, resolved ready to draw: the
                             * author's override when the author set one, or the type's own resolved
                             * picture (a kill task's spawn egg) otherwise. Empty when neither —
                             * which is every task a version-20 tree sent. Drawn instead of
                             * {@code item}; never read for
                             * recipes, progression, or viewer indexes, which all read {@code item}
                             * and {@code tagId}. A picture whose item this build does not have
                             * resolves empty and the row falls back to what it needs, rather than
                             * crying missing over a decoration.
                             */
                             ItemStack picture,
                             /**
                              * A {@code tenet:filter} task's expression, as the file spells it.
                              * Empty for every other type — and for every task a tree sent before
                              * this field existed, which none of them are. The row cycles through
                              * what it names and previews the matches on click.
                              */
                             String filter) {

        /** Whether this row draws an item at all, as opposed to text. */
        public boolean hasItem() {
            return !item.isEmpty();
        }

        /** Whether the row carries a picture: an author override, or the type's own resolved one. */
        public boolean hasPicture() {
            return !picture.isEmpty();
        }

        /**
         * What the row draws: the author's picture when there is one, the requirement when there
         * is one, and the type's own icon otherwise. Recipe lookups must not read this — they read
         * {@code item} and {@code tagId}, which is the fix for a tag task wearing an oak log opening
         * oak-log recipes instead of the tag.
         */
        public ItemStack shown() {
            if (!picture.isEmpty()) {
                return picture;
            }
            return !item.isEmpty() ? item : icon;
        }

        /**
         * The kind of thing to watch for, or null when this is not an observation task.
         *
         * <p>The value the client ticker matches the crosshair against; see
         * {@code ObservationWatcher}.
         */
        public dev.ellipog.tenet.quest.task.ObservationTask.ObserveType observation() {
            return observeType.isEmpty()
                    ? null
                    : dev.ellipog.tenet.quest.task.ObservationTask.ObserveType.byWire(observeType);
        }

        /**
         * The text for this row.
         *
         * <p>A translatable label when one was sent with a fallback, so a pack can translate its own
         * checkmark titles; the item's own name when there is an item; the literal text otherwise.
         *
         * <h2>What the key is formatted with, and the bug that made it explicit</h2>
         *
         * <p>{@code labelArg} when the server sent one — the biome, the stage, the mob the sentence is
         * about — and the count otherwise, for the types whose sentence counts something ("%s XP").
         * Passing the count for every key is what put "1" on the card where a stage reward should have
         * read "Grant the stage my_pack:inducted": the key was written for a subject the count is not.
         * An older server sends no argument, and those keys are the count-shaped ones, so the fallback
         * to the count is what keeps that pairing working.
         */
        public Component text() {
            return text(labelArg.isEmpty() ? String.valueOf(count) : labelArg);
        }

        /**
         * The same, with the key's argument supplied by the caller.
         *
         * <p>How a row shows a prettified id: the sentence is the server's and stays the server's, and
         * the one word in it the client can say better -- a registry id with a name the client knows --
         * is replaced before the key is formatted. See {@code QuestBookScreen.prettyArg}.
         */
        public Component text(String arg) {
            if (hasItem()) {
                return item.getHoverName();
            }
            if (!labelFallback.isEmpty() && !label.isEmpty()) {
                // Through the locale resolver, so a pack's own translation of this key wins over the
                // English the server sent -- and so a row's sentence follows a language change without
                // the tree being re-sent. See ClientLocale.
                return Component.literal(ClientLocale.text(label, labelFallback, arg));
            }
            return Component.literal(label.isEmpty() ? "?" : label);
        }
    }

    /**
     * One reward, resolved ready to draw.
     *
     * <p>{@code team}, {@code auto} and {@code excludeFromClaimAll} are the base mechanics, resolved
     * server-side against the tree's own settings: {@code team} decides whose claim settles this
     * reward (the player's own, or the whole team's), which is what the claim view reads.
     */
    public record RewardEntry(ItemStack icon, ItemStack item, int count, String type, String label,
                              String labelFallback, String labelArg, String itemId, String auto, boolean team,
                              boolean excludeFromClaimAll,
                              /** The gates this reward carries, for the locked row's hover. Empty for none. */
                              List<ConditionEntry> conditions,
                              /**
                               * Whether collecting this reward is announced. Recorded since version 17
                               * for the reward-level notice; no notice reads it yet, so it travels as
                               * data for the notice that will. Absent means announced.
                               */
                              boolean disableToast,
                              /**
                               * The texture path when the author overrode the picture with a texture.
                               * Empty means the type's own picture, like the task's own.
                               */
                              String textureIcon,
                              /**
                               * The atlas region when the author overrode the picture with a sprite.
                               * Empty means the type's own picture. Never beside a texture: the
                               * display carries one override, like the file it came from.
                               */
                              String spriteIcon,
                              /**
                               * The author's item picture, since version 21, resolved ready to draw.
                               * Empty when the author set no item picture. Drawn instead of
                               * {@code item}; never read for recipes or viewer indexes, which read
                               * {@code item}. A missing picture falls back to what the reward pays,
                               * like the task's own.
                               */
                              ItemStack picture) {

        public boolean hasItem() {
            return !item.isEmpty();
        }

        /** Whether the author overrode the picture with an item. */
        public boolean hasPicture() {
            return !picture.isEmpty();
        }

        /**
         * What the row draws: the author's picture when there is one, the payout when there is one,
         * and the type's own icon otherwise. Recipe lookups must not read this.
         */
        public ItemStack shown() {
            if (!picture.isEmpty()) {
                return picture;
            }
            return !item.isEmpty() ? item : icon;
        }

        public Component text() {
            // The subject when there is one, the count otherwise -- see TaskEntry.text for the
            // "1" that reading the count into every key produced.
            return text(labelArg.isEmpty() ? String.valueOf(count) : labelArg);
        }

        /** The same, with the key's argument supplied by the caller: see {@link TaskEntry#text(String)}. */
        public Component text(String arg) {
            if (hasItem()) {
                return item.getHoverName();
            }
            if (!labelFallback.isEmpty() && !label.isEmpty()) {
                // Through the locale resolver, so a pack's own translation of this key wins over the
                // English the server sent -- and so a row's sentence follows a language change without
                // the tree being re-sent. See ClientLocale.
                return Component.literal(ClientLocale.text(label, labelFallback, arg));
            }
            return Component.literal(label.isEmpty() ? "?" : label);
        }
    }

    /**
     * What a quest draws for its picture when it names none: its first task's with a picture,
     * cycling when that task is a filter. The legacy {@code IconAnimation} rule, resolved at draw
     * time rather than at conversion, so a filter's rotation survives the trip.
     *
     * <p>The priority is deliberate: a cycling filter frame first, then an author or
     * type-resolved picture (a kill task's egg arrives here), then an item requirement, and
     * only then the task type's own glyph — a knowledge book says "things to do" where paper
     * says nothing. Glyphs speak last because a requirement names the quest better than its
     * kind does, but they speak now: a quest of bare checkmarks used to keep the paper
     * default, like a quest of nothing.
     *
     * <p>Empty when the quest names an icon in any arm — an explicit picture always wins — and
     * when no task has anything to lend. What calls this falls back to paper, which is the
     * floor: a quest is never drawn empty.
     */
    public static ItemStack adoptedQuestIcon(Entry entry) {
        if (!entry.icon().isEmpty() || !entry.iconId().isEmpty() || !entry.textureIcon().isEmpty()
                || !entry.spriteIcon().isEmpty()) {
            return ItemStack.EMPTY;
        }
        for (TaskEntry task : entry.tasks()) {
            ItemStack frame = filterFrame(task);
            if (!frame.isEmpty()) {
                return frame;
            }
            if (!task.picture().isEmpty()) {
                return task.picture();
            }
            if (!task.item().isEmpty()) {
                return task.item();
            }
        }
        for (TaskEntry task : entry.tasks()) {
            if (!task.icon().isEmpty()) {
                return task.icon();
            }
        }
        return ItemStack.EMPTY;
    }

    /**
     * One frame of a filter task's rotation, at the legacy one-picture-a-second cadence: what every
     * surface showing that task draws with.
     *
     * <p>Empty for every non-filter task and for a filter nothing answers to — those draw the
     * still rule ({@code picture}, then requirement, then glyph) instead. One helper for the
     * book's rows, the quest adoption above and the HUD pins, because three spellings of the
     * cadence is how two of them would freeze while the third cycled.
     */
    public static ItemStack filterFrame(TaskEntry task) {
        if (task.filter().isEmpty()) {
            return ItemStack.EMPTY;
        }
        FilterMatches.Matches matches = FilterMatches.of(task.filter());
        if (matches.shown().isEmpty()) {
            return ItemStack.EMPTY;
        }
        return matches.shown().get(
                (int) ((net.minecraft.Util.getMillis() / 1000) % matches.shown().size()));
    }

    /**
     * Whether this quest's adopted icon cycles: no icon of its own, and the first task
     * with anything to lend lends a filter naming more than one frame.
     *
     * <p>The same priority walk as {@link #adoptedQuestIcon}, asked as a question rather
     * than for the picture — and the two must stay the same walk, or the stamp that
     * restamps for animation restamps for icons that never move. A single-frame filter
     * is still (its one frame draws always), a picture or requirement ahead of any
     * filter wins still, and the glyph second loop never cycles.
     *
     * <p>What the canvas stamp reads to decide whether the second is part of its key:
     * a cycling icon advances once a second, and a stamp that could not see the clock
     * would freeze it on the frame the stamp was taken.
     */
    public static boolean adoptionCycles(Entry entry) {
        if (!entry.icon().isEmpty() || !entry.iconId().isEmpty() || !entry.textureIcon().isEmpty()
                || !entry.spriteIcon().isEmpty()) {
            return false;
        }
        for (TaskEntry task : entry.tasks()) {
            if (!filterFrame(task).isEmpty()) {
                return FilterMatches.of(task.filter()).shown().size() > 1;
            }
            if (!task.picture().isEmpty() || !task.item().isEmpty()) {
                return false;
            }
        }
        return false;
    }

    /**
     * One gate a task or a reward carries, resolved ready to draw.
     *
     * <p>What a locked row's hover names. The item is resolved here, like a task's, and the rest is the
     * sentence: a key and an English fallback, exactly as a row's own text travels, because the server
     * does not know this client's language. The unmet <i>indices</i> travel on the progress channel and
     * say which of these to name — see {@link #taskLockOf}.
     */
    public record ConditionEntry(ItemStack item, int count, String label, String labelFallback,
                                 String labelArg) {

        /** Whether this gate is drawn as an item, with the item's own name. */
        public boolean hasItem() {
            return !item.isEmpty();
        }

        /**
         * The line a hover shows.
         *
         * <p>An item gate reads as the item's own name and count — the number is part of the sentence
         * here because a condition has no progress chip to carry it, which is the one place this
         * differs from a task row. A text gate carries its number in {@code labelArg}, so the key
         * formats to the whole sentence.
         */
        public String line() {
            if (hasItem()) {
                String name = item.getHoverName().getString();
                return count > 1 ? name + " \u00d7" + count : name;
            }
            if (!labelFallback.isEmpty() && !label.isEmpty()) {
                return ClientLocale.text(label, labelFallback, labelArg);
            }
            return label.isEmpty() ? "?" : label;
        }
    }

    /**
     * One chapter group heading, as the server described it.
     *
     * <p>Arrives in a flat {@code groups[]} at the tree root rather than nested around its chapters,
     * for the same reason a quest carries its own {@code chapterId}: the client groups however it
     * likes, and a nested shape would fix its outline to the server's.
     *
     * <p>{@code collapsedByDefault} is what the tree says the <i>first</i> time this client sees it,
     * and it is the only thing the server has to say about whether a group is open. What the player
     * toggles afterwards is the player's — it lives in the outline, is never written back, and is never
     * written to {@code config/armature/appearance.json}, which is the player's and Armature's file.
     * The two never meet, which is why this is a boolean from the server and a set of keys on the
     * client rather than a field that travels in both directions.
     *
     * <p>{@code icon} is the group's authored icon, empty when the file declares none — in which case
     * the sidebar falls back to the first chapter under it. {@code iconId} is kept beside the stack for
     * the same reason the quest's is: a resolved-empty stack with an id is a missing item, and the two
     * facts are worth telling apart.
     */
    public record GroupEntry(String id, String title, boolean collapsedByDefault, ItemStack icon,
                             String iconId,
                             /**
                              * The texture path when the group wears a texture icon, and empty otherwise.
                              * Kept apart from {@code iconId} so the missing-item reading stays an item
                              * reading: a texture draws through the blit rather than the stack, and an
                              * empty id is simply no icon.
                              */
                             String textureIcon,
                             /**
                              * The atlas region when the group wears a sprite icon, and empty otherwise.
                              * Drawn from the atlas; never beside a texture, like every other icon.
                              */
                             String spriteIcon,
                             /**
                              * The heading's English words when its title is a translation key, and
                              * empty when it is a plain string. See {@link #titleText()}.
                              */
                             String titleFallback) {

        /** The heading as the player reads it. See {@link ClientQuestCache#text}. */
        public String titleText() {
            return text(title, titleFallback, "group." + id + ".title");
        }
    }

    /**
     * One chapter, as the server described it — whether or not it holds any quests.
     *
     * <p>Since version 3 the tree carries a {@code chapters[]} of its own, and the reason is a chapter
     * that has no quests: until this list existed, a chapter reached the client only as a property of
     * the quests inside it, so an empty one was invisible — it could not be selected, edited, moved or
     * even seen. The per-quest {@code chapterTitle}/{@code chapterIcon} fields still arrive and are
     * still what the canvas draws, so a version-2 server needs nothing from this record.
     *
     * <p>{@code groupId} is empty for a chapter that belongs to no group — the same sentinel a quest's
     * {@code chapterGroupId} uses, and the case the sidebar already draws as a root row.
     *
     * <p>The gate fields arrived with version 12. They are what a reader needs to <i>explain</i> a shut
     * chapter — which chapters it waits on, and whether it is asking for them started or finished — and
     * how far those have got is not among them: that is {@link #chapterStateOf}, which comes on the
     * progress channel with every other fact that moves. Whether this chapter is hidden before its gate
     * is met is {@code hideUntilDependenciesComplete}, authored rather than derived.
     *
     * <p>All four are resolved values, and absent keys mean the defaults: an older server sends none of
     * them, and the honest reading of a chapter it says nothing about is "no gate, shown" — which is
     * exactly what a version-11 client does with them.
     */
    public record ChapterEntry(String id, String groupId, String title, ItemStack icon, String iconId,
                               /**
                                * The texture path when the chapter wears a texture icon, empty otherwise.
                                * See {@link GroupEntry#textureIcon} for why this is its own field rather
                                * than a second meaning of the id.
                                */
                               String textureIcon,
                               /**
                                * The atlas region when the chapter wears a sprite icon, empty otherwise.
                                * Drawn from the atlas; never beside a texture, like every other icon.
                                */
                               String spriteIcon,
                               List<String> dependsOn, dev.ellipog.tenet.quest.PrerequisiteMode prerequisiteMode,
                               int minRequired, boolean hideUntilDependenciesComplete,
                               /** The title's English words when it is a key, empty when it is text. */
                               String titleFallback,
                               /**
                                * The quest this chapter centres on when selected, by id or alias. Empty
                                * when the chapter says nothing — the canvas centres on its bounding box,
                                * which is what a version-16 server always says.
                                */
                               String autofocus,
                                /**
                                 * Whether this chapter is withheld from every reader, whatever its
                                 * gate says. The legacy {@code always_invisible}: a reader never sees
                                 * the row, while an author still does. Sparse on the wire, so absent
                                 * means shown, which is what every older server says.
                                 */
                                boolean alwaysInvisible,
                                /**
                                 * Whether this chapter's canvas draws without the zoom tiers, or null
                                 * when the file says nothing and the book's own flag answers.
                                 *
                                 * <p>Null rather than false, because absence defers: a chapter that
                                 * re-enables the tiers under a tierless book arrives as an explicit
                                 * {@code false}, which a defaulted boolean could not tell apart from
                                 * "never heard of it". See {@link #lodDisabledFor} for the resolution.
                                 */
                                Boolean lodDisabled) {

        public ChapterEntry {
            dependsOn = List.copyOf(dependsOn);
        }

        /** Whether this chapter names a quest to centre on. False for every older server. */
        public boolean hasAutofocus() {
            return !autofocus.isEmpty();
        }

        /** Whether this chapter waits on anything. False for every chapter of an older server. */
        public boolean waits() {
            return !dependsOn.isEmpty();
        }

        /** The chapter as the player reads it. See {@link ClientQuestCache#text}. */
        public String titleText() {
            return text(title, titleFallback, "chapter." + id + ".title");
        }
    }

    /**
     * One reward table, as the editor's browser lists it.
     *
     * <p>A summary rather than the table: the entries are wanted by the one panel that is editing a
     * table, and that panel asks for the file. What every editor needs — a browser row, and the badge
     * on a reward that points at one — is a name, an icon and a count.
     *
     * @param title   what to call it: the author's own title, or the id opened out
     * @param iconId  the item id as written, kept beside the resolved stack so a missing item can say so
     */
    public record TableSummary(String id, String title, ItemStack icon, String iconId, int entries,
                               /**
                                * Whether a reward row that rolls this table draws the table's title.
                                * The legacy {@code use_title}. Absent on the wire means the generic
                                * roll sentence, which is what a version-16 server always says.
                                */
                               boolean useTitle,
                               /**
                                * Whether that row draws no item tooltip. The legacy {@code hide_tooltip}.
                                * Absent means the tooltip draws.
                                */
                               boolean hideTooltip) {

        /**
         * The table as the player reads it.
         *
         * <p>No fallback component, unlike the other three: a table's title is a plain string in its
         * file rather than a {@code QuestText}, so it has no authored-key form to keep apart. The
         * pack's conventional key is the whole of its translation road.
         */
        public String titleText() {
            return RichText.plain(ClientLocale.text("rewardTable." + id + ".title", title));
        }
    }

    /**
     * One quest, as the client needs it.
     *
     * <p>{@code shape} is held as the resolved enum rather than the string that arrived, for the same
     * reason the items are resolved on arrival: the screen asks for it per node per frame, and
     * scanning a string per frame to answer the same question is work with no purpose.
     *
     * <p>{@code chapterGroupId} is what puts a quest's chapter under the right heading, and it is the
     * one thing the heading list on its own cannot express — {@code groups[]} says what a heading is
     * called and nothing about what hangs under it. It is <b>empty</b> for a server that predates
     * groups, and that means "no group", which is the case the sidebar has to draw today's flat chapter
     * list for. An empty string is unambiguously not a group id: {@code Checks.id} refuses one, so no
     * real group can collide with the sentinel.
     *
     * <p>It was missing entirely until the shapes were wired up. The field existed in the quest file
     * format, was validated, and was printed by {@code /tenet} — but {@code QuestSync} never put it on
     * the wire, so the client could not have honoured it, and the screen drew and hit-tested a square
     * whatever the file said. A field that is parsed, validated and reported but never consumed reads
     * as supported, which is worse than one that is absent.
     */
    public record Entry(String chapterGroupId, String chapterId, String chapterTitle, String chapterTheme,
                        String id, String title, String subtitle, String chapterSubtitle,
                        List<String> description, ItemStack icon, int x, int y, int size, QuestShape shape,
                        double iconScale, int rotation, boolean showTitle,
                        /** The dependency rule, as the server resolved it: null means the chapter default. */
                        dev.ellipog.tenet.quest.PrerequisiteMode prerequisiteMode,
                        /** What the chapter says when a quest has no opinion. */
                        dev.ellipog.tenet.quest.PrerequisiteMode chapterDefaultPrerequisiteMode,
                        int minRequired, int maxCompletableDependents,
                        /** Empty when the quest names no group. */
                        String exclusiveGroup,
                        /** The reveal flags, as authored. See `QuestVisibility`. */
                        boolean hideUntilDependenciesComplete, boolean hideUntilDependenciesVisible,
                        boolean hideDependencyLines, boolean hideTextUntilComplete,
                        boolean hideDetailsUntilStartable, int invisibleUntilTasks,
                        boolean chapterLinear, int orderInChapter,
                        List<String> dependencies, List<TaskEntry> tasks, List<RewardEntry> rewards,
                        boolean invisible,
                        /**
                         * Whether this quest is marked a side quest. Sparse on the wire: absent
                         * means it is not. A marker, not a gate — it gates exactly like any
                         * other quest — read for the card.
                         */
                        boolean optional,
                        /**
                         * Whether this quest measures before its gate opens: its own flag or its
                         * chapter's default, resolved server-side. Sparse on the wire: absent
                         * means strict, which is what every older server says. Read where gated
                         * visuals are decided — see {@code QuestVisibility}.
                         */
                        boolean flexible,
                        /**
                         * The icon's id as the server sent it, kept beside the resolved stack: a stack
                         * that failed to resolve with an id that was sent is a <b>missing item</b>, and
                         * the screens say so; an empty id is simply no icon.
                         *
                         * <p>An item id for the item arm, an entity id for the entity arm (drawn as its
                         * egg, or missing when it has none), and empty for the texture arm — whose path
                         * travels in {@link #textureIcon} instead, so no missing-item branch fires for a
                         * picture that was never a stack.
                         */
                        String iconId,
                        /** The texture path when this quest wears a texture icon, empty otherwise. */
                        String textureIcon,
                        /** The atlas region when this quest wears a sprite icon, empty otherwise. */
                        String spriteIcon,
                        /**
                         * The chapter's icon, and the id it was resolved from: the sidebar's chapter row
                         * draws it, and the id keeps the same "missing item" reading the quest's own pair
                         * has. Sent on every quest of the chapter, so it is never absent for a chapter --
                         * an unauthored icon is the model's paper default rather than nothing.
                         */
                        ItemStack chapterIcon, String chapterIconId,
                        /** The texture path when the chapter wears a texture icon, empty otherwise. */
                        String chapterTextureIcon,
                        /** The atlas region when the chapter wears a sprite icon, empty otherwise. */
                        String chapterSpriteIcon,
                        /**
                         * The per-line styles this quest's own dependencies carry, keyed by dependency
                         * id. Empty means every line follows {@link #chapterDependencyStyle()}, which is
                         * what a quest file that never mentions them says.
                         */
                        java.util.Map<String, dev.ellipog.tenet.quest.DependencyStyle> dependencyLines,
                        /**
                         * The chapter's default line style, already resolved: the canvas draws with it
                         * directly and has no chapter record to read one from. Resolved server-side so
                         * an axis nobody set arrives as the built-in rather than as an absence every
                         * drawing call site would have to remember to fill.
                         */
                        dev.ellipog.tenet.quest.DependencyStyle chapterDependencyStyle,
                        /**
                         * The chapter's token-level theme overrides as the file wrote them, or null when
                         * it names none. Raw JSON, like the chapter's own field: the client parses and
                         * composes it, and the server only carried it here.
                         */
                        com.google.gson.JsonObject chapterThemePatch,
                        /**
                         * The quest's own auto-claim mode, or null when it says nothing — in which case
                         * {@link #chapterAutoClaim} applies. See {@link #effectiveAutoClaim()}.
                         */
                        dev.ellipog.tenet.quest.reward.RewardAutoClaim autoClaim,
                        /**
                         * The chapter's auto-claim mode, already resolved against the pack setting by the
                         * server: the client needs the effective value for a quest that says nothing, and
                         * has no chapter record to resolve one from.
                         */
                        dev.ellipog.tenet.quest.reward.RewardAutoClaim chapterAutoClaim,
                        /**
                         * The text fields' English halves, since version 13.
                         *
                         * <h2>Why they are here rather than beside the text they belong to</h2>
                         *
                         * <p>Because each is the second half of a field that already exists, and
                         * grouping them says so: {@code title}/{@code titleFallback} are one fact in
                         * two parts, and a reader looking for "where did the English go" finds all
                         * the answers in one place rather than scattered.
                         *
                         * <p>{@code chapterSubtitle} sits with the values above while its fallback
                         * sits here, the same split {@code chapterTitle} has: the value rides with
                         * the field it describes, and the English half rides with the other halves.
                         *
                         * <p>Each is <b>empty for a plain string</b> and holds the English words when
                         * the file wrote {@code {"translate": key, "fallback": words}}. That empty is
                         * load-bearing rather than incidental: it is the client's only way to tell a
                         * literal from a key, and getting it wrong is how a raw key ends up drawn on
                         * a node — which is exactly what the server used to send, because it put
                         * {@code QuestText.value()} on the wire whatever the file meant by it.
                         *
                         * <p>{@code descriptionFallbacks} pairs with {@code description} by index and
                         * may be shorter: a paragraph the server sent no fallback for reads as empty,
                         * which resolves to the canonical paragraph that travelled beside it.
                         */
                        String titleFallback, String subtitleFallback, String chapterTitleFallback,
                        String chapterSubtitleFallback,
                        List<String> descriptionFallbacks,
                        /**
                         * The quest's former ids, for the lookups the server answers by id or alias.
                         *
                         * <p>Sparse on the wire: absent means "no aliases", which is every quest that was
                         * never renamed. The client needs them for the same lookups the server does, so a
                         * press naming an alias opens the quest rather than reporting a broken control —
                         * which is what an {@code open_quest} click carrying a pre-rename spelling is.
                         */
                        List<String> aliases,
                        /**
                         * The minimum width of this quest's detail panel, already resolved against
                         * the chapter's default by the server. Zero means unset — the panel kind
                         * decides — which is what a version-16 server always says.
                         */
                        int minWidth,
                        /**
                         * Whether the dependency lines leaving this quest for its dependants are
                         * drawn. The outgoing half of {@code hideDependencyLines}. Absent on the
                         * wire means drawn, which is what a version-16 server always says.
                         */
                        boolean hideDependentLines,
                        /**
                         * Whether completing this quest is announced. ORed with the silent
                         * auto-claim modes where notices are decided. Absent means announced.
                         */
                        boolean disableToast,
                        /**
                         * Whether recipe viewers list this quest. Resolved server-side from the
                         * quest's own tristate against the file's default, since the client holds
                         * no file record; absent means shown, which is what a version-19 server
                         * always says. The viewer content skips these entries outright.
                         */
                        boolean hideFromViewers,
                        /**
                         * Whether this quest wears no lock mark of its own on the canvas. The
                         * quest's half of the file's {@code showLockIcons}: either silence wins,
                         * and the canvas reads both. Absent means the quest does not opt out;
                         * whether a mark draws is the file's answer.
                         */
                        boolean hideLockIcon,
                        /**
                         * Words this quest answers to in lookups by tag. Sparse on the wire:
                         * absent means none, which is what every older server says.
                         */
                        List<String> tags,
                        /**
                         * The guide book page this quest belongs to, or empty when it names none.
                         * A reference the quest card shows; Tenet has no guide integration, so
                         * nothing reads it further. Sparse on the wire, like the tags above.
                         */
                        String guidePage) {

        public Entry {
            descriptionFallbacks = List.copyOf(descriptionFallbacks);
            aliases = List.copyOf(aliases);
            tags = List.copyOf(tags);
        }

        /**
         * Whether {@code idOrAlias} refers to this quest.
         *
         * <p>Without regard to letter case, because lookups are: the server normalises the same way,
         * and a client that resolved case-sensitively would refuse a reference the server accepts.
         * A {@code "#tag"} resolves to the first quest carrying it, the client's half of the
         * server's own lookup. See {@code Quest#matches} for the server's own half of this.
         */
        public boolean matches(String idOrAlias) {
            if (idOrAlias == null) {
                return false;
            }
            if (idOrAlias.startsWith("#") && idOrAlias.length() > 1) {
                String tag = idOrAlias.substring(1);
                for (String held : tags) {
                    if (held.equalsIgnoreCase(tag)) {
                        return true;
                    }
                }
                return false;
            }
            if (id.equalsIgnoreCase(idOrAlias)) {
                return true;
            }
            for (String alias : aliases) {
                if (alias.equalsIgnoreCase(idOrAlias)) {
                    return true;
                }
            }
            return false;
        }

        /**
         * The quest's title, as the player reads it.
         *
         * <p>The one accessor the screens should draw with. {@link #title()} stays exactly what the
         * wire carried — a literal, or the author's translation key — because the editor seeds its
         * text fields from it and a translated string written back to a quest file would silently
         * replace the author's own words. See {@link ClientQuestCache#text}.
         */
        public String titleText() {
            return text(title, titleFallback, "quest." + id + ".title");
        }

        /**
         * The quest's title with its rich-text tokens still in it, for the surfaces that wear colours.
         *
         * <p>The same resolution as {@link #titleText} — the same words, agreed by construction — and
         * only the ink differs. Measuring still takes the stripped reading, because colours are
         * widthless and the two must agree about how wide the words are.
         */
        public String titleTextRaw() {
            return textRaw(title, titleFallback, "quest." + id + ".title");
        }

        /** The subtitle, as the player reads it. Authoring paths use {@link #subtitle()}. */
        public String subtitleText() {
            return text(subtitle, subtitleFallback, "quest." + id + ".subtitle");
        }

        /** The subtitle with its rich-text tokens still in it. See {@link #titleTextRaw}. */
        public String subtitleTextRaw() {
            return textRaw(subtitle, subtitleFallback, "quest." + id + ".subtitle");
        }

        /**
         * The chapter this quest sits in, as the player reads it.
         *
         * <p>The chapter's own title rides on every quest of it because the client has no chapter
         * record to hang it on, so it is resolved here the same way {@code chapters[]} resolves it —
         * and a card that said "in <i>Chapter One</i>" beside a sidebar row saying something else
         * would be two answers to one question.
         */
        public String chapterTitleText() {
            return text(chapterTitle, chapterTitleFallback, "chapter." + chapterId + ".title");
        }

        /** The chapter title with its rich-text tokens still in it. See {@link #titleTextRaw}. */
        public String chapterTitleTextRaw() {
            return textRaw(chapterTitle, chapterTitleFallback, "chapter." + chapterId + ".title");
        }

        /**
         * The chapter's subtitle, as the player reads it.
         *
         * <p>The one line under the chapter's name, which the sidebar row's hover draws: the row's
         * label is the title, so this is the hover's second line. Empty for a chapter that names no
         * subtitle — which is every chapter a version-19 server ever sent — and empty for a literal
         * until the pack's {@code chapter.<id>.subtitle} translates it. Authoring paths use
         * {@link #chapterSubtitle()}.
         */
        public String chapterSubtitleText() {
            return text(chapterSubtitle, chapterSubtitleFallback, "chapter." + chapterId + ".subtitle");
        }

        /** The chapter subtitle with its rich-text tokens still in it. See {@link #titleTextRaw}. */
        public String chapterSubtitleTextRaw() {
            return textRaw(chapterSubtitle, chapterSubtitleFallback, "chapter." + chapterId + ".subtitle");
        }

        /**
         * The description, as the player reads it, in paragraphs.
         *
         * <h2>The three roads, in the order they are tried</h2>
         *
         * <ol>
         *   <li><b>One key for the whole description</b>, {@code quest.<id>.description}. This is the
         *       road a translator uses, and it is first because it is the only one that cannot
         *       half-fall-back: a canonical description of three paragraphs that a translator wrote as
         *       one, or as four, is one string here and the paragraph count never has to agree.</li>
         *   <li><b>One key per paragraph</b>, {@code quest.<id>.description.<i>}, for the case where
         *       only one paragraph of many is wrong — indexed against the canonical array, so a key
         *       past the end of it simply never matches.</li>
         *   <li><b>The canonical paragraphs</b> the tree sent.</li>
         * </ol>
         *
         * <p>The split for road 1 is {@link Prose#split}, the same rule the editor's own field
         * commits by, so a description typed in the editor and one written in a lang file break into
         * paragraphs identically.
         */
        public List<String> descriptionText() {
            // Asked first, and before the empty check below, because a translation may be the only text
            // there is: a quest whose canonical description is empty and whose locale file writes one is
            // a description, not nothing. Returning early on the empty canonical list would drop the
            // translator's work silently, which is the "reads as supported and does nothing" failure
            // this codebase keeps finding.
            String whole = ClientLocale.find("quest." + id + ".description");
            if (whole != null) {
                return Prose.trimmed(Prose.split(whole));
            }
            if (description.isEmpty()) {
                return description;
            }
            List<String> out = new java.util.ArrayList<>(description.size());
            for (int i = 0; i < description.size(); i++) {
                String fallback = i < descriptionFallbacks.size() ? descriptionFallbacks.get(i) : "";
                // Resolved, not stripped: the prose renderer reads the tokens itself -- colours, page
                // breaks, pictures, links -- and a description stripped here would arrive with none of
                // them while still wearing its markdown, which reads as half a feature. Titles strip
                // because their surfaces draw one ink; this surface draws run by run.
                out.add(textRaw(description.get(i), fallback, "quest." + id + ".description." + i));
            }
            return List.copyOf(out);
        }

        /**
         * The auto-claim mode in force for this quest: its own, or the chapter's.
         *
         * <p>What the completion toast reads: a mode that grants silently ({@code no_toast},
         * {@code invisible}) suppresses the notice, so an author who turned auto-claim on for fifty
         * starter quests does not get fifty toasts either. A server too old to send either field
         * answers {@code DEFAULT}, which is not automatic and therefore notifies — exactly the
         * behaviour before this existed.
         */
        public dev.ellipog.tenet.quest.reward.RewardAutoClaim effectiveAutoClaim() {
            if (autoClaim != null) {
                return autoClaim;
            }
            return chapterAutoClaim == null
                    ? dev.ellipog.tenet.quest.reward.RewardAutoClaim.DEFAULT : chapterAutoClaim;
        }

        /**
         * The rule this quest's dependencies are judged by: its own, or the chapter's when it has none.
         *
         * <p>One method rather than a ternary at each call site, because two call sites is how the
         * canvas and the card would come to disagree about whether a prerequisite is satisfied.
         */
        public dev.ellipog.tenet.quest.PrerequisiteMode effectivePrerequisiteMode() {
            return prerequisiteMode == null ? chapterDefaultPrerequisiteMode : prerequisiteMode;
        }

        /**
         * The node's outline, with its rotation applied: one shape, resolved once.
         *
         * <p>Not a method that rotates on demand, and that is the point of the field. A rotation is
         * applied by <b>sampling</b> the shape again (see {@code Shapes.rotated}), so a caller that
         * resolved it per frame would rebuild a span table per node per frame. Here it is built once
         * per (shape, rotation) for the whole client, and the drawing, the hit test and the icon fit all
         * read the same table -- which is also what keeps them from disagreeing about where the node is.
         *
         * <p>The cache is static because a record cannot hold one, and keyed by the shape and the angle
         * because those are the whole of what the sampling depends on: a node's position and size are
         * passed to the containment test, not baked into the table.
         */
        public dev.ellipog.armature.client.ui.shape.Shape geometry() {
            return ClientQuestCache.geometry(shape, rotation);
        }
    }

    /**
     * The outline for a shape at an angle, cached for the whole client.
     *
     * <p>What {@link Entry#geometry()} reads, and public because a drafted shape or rotation — a
     * settings-page change the tree has not carried yet — asks for a table the tree has not built.
     * Keyed by the shape and the angle, which are the whole of what the sampling depends on.
     */
    public static dev.ellipog.armature.client.ui.shape.Shape geometry(
            QuestShape shape, int rotation) {
        if (rotation == QuestLayout.DEFAULT_ROTATION) {
            return shape.geometry();
        }
        long key = ((long) shape.ordinal() << 16) | (rotation & 0xFFFF);
        return ROTATED.computeIfAbsent(key, ignored ->
                dev.ellipog.armature.client.ui.shape.Shapes.rotated(shape.geometry(), rotation));
    }

    /** Sampled rotated outlines, keyed by (shape ordinal, rotation). See {@link Entry#geometry()}. */
    private static final java.util.concurrent.ConcurrentHashMap<Long, dev.ellipog.armature.client.ui.shape.Shape>
            ROTATED = new java.util.concurrent.ConcurrentHashMap<>();
    /**
     * One quest's progress, as the server last reported it.
     *
     * <p>{@code blocked} is the team's rewards being held: the server sends the key only when they are, so
     * its absence — including from a server too old to send it — reads as "not held", which is the
     * direction that cannot label a button wrongly. It <b>labels</b> the panel and does not decide the
     * claim: a block is team-wide while a reward may except itself with {@code ignoreRewardBlocking},
     * which never travels, so turning it into "this cannot be collected" would hide rewards the server
     * would happily pay.
     *
     * <p>There used to be a {@code claimable} component here, parsed from the wire and read by nothing:
     * every button and badge recomputes the per-player answer through {@link #canClaimFor}, which is the
     * question a claim actually asks, while the wire's value was team-scoped.
     */
    private record Progress(QuestState state, long cooldown, List<Integer> tasks, boolean blocked,
                            Map<Integer, Map<UUID, Integer>> contributors,
                            /** Indices settled for the whole team (team-mode and auto claims). */
                            Set<Integer> teamClaims,
                            /** Per player, the indices they have collected themselves. */
                            Map<UUID, Set<Integer>> claimedBy,
                            /** Per task row, the conditions this player does not meet. Absent = unlocked. */
                            Map<Integer, List<Integer>> taskLocks,
                            /** The same for reward rows. */
                            Map<Integer, List<Integer>> rewardLocks,
                            /** A pre-per-player save's "collected": nobody may claim again. */
                            boolean legacySettled,
                            /**
                             * The tasks whose press the server would accept right now. Absent for every
                             * task of a server that predates the field, and for every task that is not
                             * waiting for a press — both read as "no button", which is the direction that
                             * cannot offer a press the server would refuse.
                             */
                            Set<Integer> ready,
                            /**
                             * How many times a repeatable quest has been finished. Absent for a quest
                             * never repeated, which reads as zero — the direction that cannot invent a
                             * history.
                             */
                            int timesCompleted,
                            /**
                             * Whether an exclusive choice shut this quest out for good — a taken group,
                             * or a reached dependent cap. Absent for every quest of a server that
                             * predates the mark, which reads as not excluded: without it the
                             * {@code hideExcludedQuests} setting has nothing to hide. See
                             * {@link #excludedOf}.
                             */
                            boolean excluded) {

        /** Who is holding what toward one task, in the order the server named them. Empty for nobody. */
        Map<UUID, Integer> contributorsOf(int taskIndex) {
            return contributors.getOrDefault(taskIndex, Map.of());
        }

        /** Whether a claim is settled, by the rule the reward's own team flag asks for. */
        boolean claimed(UUID player, int index, boolean teamReward) {
            if (teamReward) {
                return teamClaims.contains(index);
            }
            return claimedBy.getOrDefault(player, Set.of()).contains(index);
        }

        /** The conditions this player is missing on one task, ascending. Empty means not locked. */
        List<Integer> taskLock(int index) {
            return taskLocks.getOrDefault(index, List.of());
        }

        /** The same for one reward. */
        List<Integer> rewardLock(int index) {
            return rewardLocks.getOrDefault(index, List.of());
        }

        /** Whether a press on this task would be accepted right now. See {@link #firstSubmitTask}. */
        boolean ready(int index) {
            return ready.contains(index);
        }
    }

    private static volatile List<Entry> entries = List.of();

    /**
     * The list the id index was built from, and the index itself.
     *
     * <h2>Why an index, and why it is derived rather than published</h2>
     *
     * <p>{@link #entry} used to scan the list, which is O(quests) per call — and its callers are per
     * <i>row</i>: the rewards inbox draws a row per reward, the choice overlay a row per entry, and a
     * viewer page a row per task. So a lookup made per row was a scan per row, which is the shape of
     * cost that does not show up in a screenshot and does in a large pack.
     *
     * <p>Derived from the list rather than stored beside it, and that is the part worth stating: two
     * volatile fields can be seen out of step, so a reader could hold a new list and an old index. Keyed
     * on the list's own <b>identity</b> there is nothing to be out of step with — the index a reader
     * gets is always the one built from the list it just read — and every assignment to {@code entries}
     * is a fresh instance, so a change is always a change of identity. A rebuild is idempotent, so even
     * two threads racing here can only produce the same map twice.
     *
     * <p>{@code putIfAbsent}, because the scan it replaces returns the <b>first</b> match and a plain
     * {@code put} would keep the last: with two quests sharing an id — which the loader reports as an
     * error and does not refuse outright — the two would disagree about which quest a click opens.
     */
    private static volatile List<Entry> indexedFor;
    private static volatile Map<String, Entry> byId = Map.of();

    private static Map<String, Entry> byId() {
        List<Entry> current = entries;
        if (current != indexedFor) {
            Map<String, Entry> built = new java.util.HashMap<>(Math.max(16, current.size() * 2));
            for (Entry entry : current) {
                built.putIfAbsent(entry.id(), entry);
            }
            // The map before the key that says it is current, and both volatile: a reader that sees
            // `indexedFor == current` then sees the map built for `current` rather than one built for
            // the list before it. That ordering is what makes the claim above true rather than likely.
            byId = Map.copyOf(built);
            indexedFor = current;
        }
        return byId;
    }

    /**
     * The group headings, in the order the server declared them.
     *
     * <p>Empty for a server that predates groups, and that is not a case needing its own handling: the
     * sidebar draws the flat chapter list whenever it holds no headings, which is exactly what an older
     * server wants. So absence needs no flag and no branch — see {@link
     * dev.ellipog.tenet.net.QuestSync#treeAsJson}, which sends the key even when it is empty so a
     * version-2 tree is self-describing, and note that nothing here depends on that.
     */
    private static volatile List<GroupEntry> groups = List.of();

    /**
     * The chapters themselves, in the order the server declared them.
     *
     * <p>Empty for a server older than version 3, and that reads the same way {@link #groups} does: the
     * sidebar derives its chapter rows from the quests when this list is empty, which is exactly what
     * every client did before the list existed. So absence is a fallback rather than a special case.
     */
    private static volatile List<ChapterEntry> chapters = List.of();

    /**
     * What each chapter draws on its canvas behind its quests, by chapter id.
     *
     * <h2>Why a map rather than a component on {@link ChapterEntry}</h2>
     *
     * <p>Because elements are chapter-level in a way a chapter's own fields are not: there are as many of
     * them as an author drew, and a record that carries a list of them carries it everywhere a chapter is
     * passed. This is the arrangement {@link #chapterStates} already has for the same reason, and the two
     * arrive on the same message, so they are always from one moment.
     *
     * <p>Empty for a chapter with no decoration, which is most of them, and empty for a server older than
     * version 14 — and both read the same way: a canvas with nothing drawn on it. Nothing here is a
     * fallback that a reader has to notice, which is the property an additive field is for.
     */
    private static volatile Map<String, List<CanvasElement>> chapterElements = Map.of();

    /**
     * What each chapter draws among its quests as markers, by chapter id.
     *
     * <p>Beside {@link #chapterElements} rather than inside it, because a link is not an element: it
     * is drawn in the node layer with its target's state rather than behind the nodes with a tint.
     * Empty for a chapter with no markers, which is nearly all of them, and empty for a server older
     * than version 15 — and both read the same way: a canvas with no shortcuts. Nothing here is a
     * fallback a reader has to notice, which is the property an additive field is for.
     */
    private static volatile Map<String, List<dev.ellipog.tenet.quest.QuestLink>> chapterLinks = Map.of();

    /** The reward tables the server declared, for the editor's browser. Empty on an older server. */
    private static volatile List<TableSummary> tables = List.of();

    /** The table files the load refused, with their reasons. Beside {@link #tables}, never inside it. */
    private static volatile List<RefusedTable> refusedTables = List.of();

    private static volatile Map<String, Progress> progress = Map.of();

    /**
     * How far each chapter has got, by chapter id, as the server resolved it.
     *
     * <p>Beside {@link #progress} rather than inside it because it is a different kind of fact about a
     * different kind of object — a quest's state is stored and read back, a chapter's is derived on the
     * server every time — and it arrives on the same message, so the two are always from one moment.
     *
     * <p>Empty means "every chapter is open", which is what an older server says by sending nothing, and
     * the default {@link #chapterStateOf} gives for any id it has not heard about.
     */
    private static volatile Map<String, QuestState> chapterStates = Map.of();

    private static volatile UUID teamId;
    private static volatile long syncedAt;
    private static volatile int questCount;
    private static volatile int chapterCount;
    private static volatile boolean treeReceived;

    /**
     * The pack's own name and icon for the book, from the tree root.
     *
     * <p>Empty for a pack that declares neither, and that reads as the client's translatable title and
     * no icon. The id is kept beside the resolved stack for the same reason a chapter's is: an id that
     * did not resolve is a <b>missing item</b> the header marks, while no id at all is simply no icon.
     */
    private static volatile String bookTitle = "";
    private static volatile String bookIcon = "";
    private static volatile ItemStack bookIconStack = ItemStack.EMPTY;
    /** The book's texture path when its icon is a texture, and empty otherwise. See {@link #bookIcon}. */
    private static volatile String bookTextureIcon = "";
    /** The book's atlas region when its icon is a sprite, and empty otherwise. See {@link #bookIcon}. */
    private static volatile String bookSpriteIcon = "";

    /**
     * The file's own answers the client draws with, from the tree root.
     *
     * <p>Every one travels sparse and unversioned — absent means the default, which is what a
     * server that predates the key always says — so each default here must match the file's:
     * marks hidden, nothing hidden, the world unpaused, the book opening, and no custom locked
     * word. {@code acceptTree}'s catch and {@code clear()} both
     * reset them with everything else, so a malformed tree or a disconnect cannot leave one
     * pack's answers on another's book.
     */
    private static volatile boolean showLockIcons = false;
    private static volatile boolean hideExcludedQuests = false;
    private static volatile boolean pauseGame = false;
    private static volatile boolean guiDisabled = false;
    private static volatile boolean bookLodDisabled = false;
    private static volatile String lockMessage = "";

    /**
     * Which tree this cache holds, as a number that only ever increases.
     *
     * <p>Bumped by every path that changes what the cache holds — a tree arriving, and a disconnect
     * clearing it — and by nothing else.
     *
     * <p>What it is for: a screen builds a collapsible outline, and has to be able to tell "the tree
     * is still the one I built my outline from" from "a new one has arrived". A player toggling a
     * group, resizing the window, or scrolling all leave this alone, so the outline keeps the toggles
     * the player chose. A reload — which is a tree arriving — moves it, so the outline is re-seeded
     * from the authored defaults, which is right rather than unfortunate: a reload means the files
     * changed, and the authored state is the honest one for a tree nobody has seen.
     *
     * <p>So the unit is "a tree arrived", not "the tree is different". Comparing contents would answer
     * the same question a second way with its own answer for an identical re-send, and the two
     * descriptions would disagree about whether the player keeps their toggles — with neither being
     * more correct than the other.
     */
    private static volatile long treeRevision;

    /**
     * Which progress this cache holds, as a counter that only ever moves.
     *
     * <h2>Why progress needs one when the tree has one</h2>
     *
     * <p>They are read for different reasons. The tree's revision says whether the <i>rows</i> a screen
     * built are still the rows to draw; this one says whether what those rows are <i>about</i> has
     * moved, which is a different question with a different answer at a different moment — a quest
     * becoming claimable puts a Claim button on the screen, and a panel that only watched the tree
     * would show it for the first time when the player reopened the book.
     *
     * <p>Moved on the message rather than on the contents, like both counters above it: "progress
     * arrived" is a fact about a message, and two messages may describe the same state.
     */
    private static volatile long progressRevision;

    /**
     * Which language the rendered rows were built in, as a counter that only ever moves.
     *
     * <h2>Why a language change needs a revision of its own</h2>
     *
     * <p>Because it moves neither of the other two. A locale arriving is not a tree: nothing is
     * re-parsed, no row's identity or position changes, and {@link #treeRevision()} deliberately does
     * not move — it is also the stamp editor drafts expire against and the revision a chapter replica
     * is matched to, so moving it would throw away an author's in-progress edit because a player
     * changed language.
     *
     * <p>But rows that hold <i>text</i> still have to be rebuilt. The sidebar's chapter and group
     * names, and the recipe viewers' page titles, are resolved when they are built and kept; without
     * this counter they would keep the previous language's words until the screen was closed and
     * reopened. Text is measured per frame, so nothing else needs telling.
     *
     * <p>Moved only on a real change of the overlay, like the two above it: a re-send of the same
     * locale — which a reload produces — must not make every screen holding rendered rows rebuild
     * them for nothing.
     */
    private static volatile long textRevision;

    /** Called by {@link ClientLocale} when the overlay it holds actually changed. */
    static void textChanged() {
        textRevision++;
    }

    /** Whether the tree has arrived — even an empty one. */
    public static boolean hasTree() {
        return treeReceived;
    }

    /**
     * Whether there is anything to show.
     *
     * <p>Deliberately distinct from {@link #hasTree()}: "waiting for the server" is a moment, "this
     * server has no quests" is a state, and telling someone the first when it is the second sends them
     * hunting a sync bug that does not exist.
     */
    public static boolean hasData() {
        // Chapters count as data since the tree can carry them on their own: a book whose only
        // chapter is empty is a book with something in it, and it is exactly the state that exists
        // between creating a chapter and writing its first quest.
        return treeReceived && (!entries.isEmpty() || !chapters.isEmpty());
    }

    public static List<Entry> entries() {
        return entries;
    }

    /**
     * The group headings, in the order the server declared them.
     *
     * <p>Declaration order, not sorted: the server's order is the author's — folder-name order for the
     * folder layout — and a client that sorted would silently reorder somebody's book.
     */
    public static List<GroupEntry> groups() {
        return groups;
    }

    /**
     * The chapters, in the order the server declared them — including chapters that hold no quests.
     *
     * <p>Declaration order for the same reason {@link #groups()} is: it is the author's order, and a
     * client that sorted would silently reorder somebody's book.
     */
    public static List<ChapterEntry> chapters() {
        return chapters;
    }

    /**
     * The chapters by id, derived from the list the way {@link #byId()} derives the
     * quests: keyed on the list's identity, so a reader can never hold a new list
     * with an old index. First wins, as a scan would.
     */
    private static volatile List<ChapterEntry> chaptersIndexedFor;
    private static volatile Map<String, ChapterEntry> chaptersById = Map.of();

    private static Map<String, ChapterEntry> chaptersById() {
        List<ChapterEntry> current = chapters;
        if (current != chaptersIndexedFor) {
            Map<String, ChapterEntry> built = new java.util.HashMap<>(Math.max(16, current.size() * 2));
            for (ChapterEntry entry : current) {
                built.putIfAbsent(entry.id(), entry);
            }
            chaptersById = Map.copyOf(built);
            chaptersIndexedFor = current;
        }
        return chaptersById;
    }

    /**
     * What one chapter draws behind its quests, in the order it should be drawn.
     *
     * <h2>Why the ordering is the cache's answer rather than the caller's</h2>
     *
     * <p>Because it is the same answer three callers need and one of them is a hit test: the canvas draws
     * this list forwards, picks an element from it backwards — the one drawn last is the one on top — and
     * the chapter tab lists it in the same order. {@link CanvasElement#inDrawOrder} is the one place that
     * order is defined, so this asks it rather than sorting again; a caller that sorted for itself would be
     * a second opinion about which element is on top, which is a fault with no visible cause.
     *
     * <p>Empty for a chapter with no decoration and for one this client has never heard of, which read the
     * same way: nothing is drawn. Never null.
     */
    public static List<CanvasElement> elements(String chapterId) {
        List<CanvasElement> held = chapterId == null ? null : chapterElements.get(chapterId);
        if (held == null || held.isEmpty()) {
            return List.of();
        }
        // The list the cache holds is in declaration order -- it is what the server sent, which is what the
        // file says -- and the sort is a fact about how a canvas draws rather than about what a server
        // knows, which is why it happens here rather than on the wire.
        return CanvasElement.inDrawOrder(held);
    }

    /**
     * One chapter's markers, in the order the file wrote them.
     *
     * <p>Declaration order, and <b>not</b> draw order: links share the node layer with quests, whose
     * picking the screen already settles, so there is no second order for this cache to define.
     * Empty for a chapter with no markers and for one this client has never heard of, which read the
     * same way: nothing is drawn. Never null.
     */
    public static List<dev.ellipog.tenet.quest.QuestLink> links(String chapterId) {
        List<dev.ellipog.tenet.quest.QuestLink> held = chapterId == null ? null : chapterLinks.get(chapterId);
        return held == null ? List.of() : held;
    }

    /**
     * Which tree this cache holds. See the field's own note for why a caller compares it.
     *
     * <p>Read by a screen to decide whether the outline it built is still the one to draw. Only
     * equality is ever asked of it, so nothing depends on the absolute value; the reason it never
     * decreases is that a caller might remember it across a clear, and the one thing that must not
     * happen is a remembered value matching a later, different tree.
     */
    public static long treeRevision() {
        return treeRevision;
    }

    /** Which progress this cache holds. See the field's note for why a screen watches it. */
    public static long progressRevision() {
        return progressRevision;
    }

    /**
     * Which language the rendered rows were built in. See the field's note for why it is its own.
     *
     * <p>A screen that caches rows holding text — the sidebar, and the recipe viewers' pages —
     * watches this beside the tree and the progress. Everything else draws text measured per frame
     * and needs nothing.
     */
    public static long textRevision() {
        return textRevision;
    }

    /**
     * One field's text, in the order the two key spaces are tried.
     *
     * <h2>Why one method rather than the rule at each accessor</h2>
     *
     * <p>Because there are two roads to a translation and they are tried in a fixed order, and four
     * records times several fields is a dozen places for the order to drift. The rule is:
     *
     * <ol>
     *   <li>the <b>author's own key</b>, when the file wrote one. It is the most specific thing
     *       anybody said about this exact text.</li>
     *   <li>the <b>pack's conventional key</b> — {@code quest.<id>.title} and its siblings. This is
     *       the road a converted pack uses, and the one an author can add without touching a quest
     *       file at all.</li>
     *   <li>the <b>wire's fallback</b>: the English words for a translatable text, or the literal
     *       itself. What a player reads when nothing translated the key, and the reason a missing
     *       translation never shows a raw key.</li>
     * </ol>
     *
     * @param value    what the wire carried: a literal, or the author's key
     * @param fallback the English words, empty when {@code value} is a literal
     */
    private static String text(String value, String fallback, String conventionalKey) {
        // Rich-text tokens read out, because every surface that draws one ink draws through here:
        // titles, subtitles, chapter titles and element words. A title carrying `&a` reads the words
        // without the ink; an escaped `\&` reads as `&`. Descriptions do not pass through here — they
        // keep their structure for the prose renderer, which wears the colours. Titles wear one ink
        // everywhere else, and one ink with the codes still in it is garbage.
        return RichText.plain(textRaw(value, fallback, conventionalKey));
    }

    /**
     * The same resolution with the tokens still in it, for the surfaces that wear colours.
     *
     * <p>Two readers rather than a flag, because the stripped and the coded readings answer different
     * questions: measuring and one-ink drawing take the stripped, and the colour pass takes the coded.
     * Both resolve the same locale chain, so a title never disagrees with itself about <i>which</i>
     * words it is — only about what ink they wear.
     */
    private static String textRaw(String value, String fallback, String conventionalKey) {
        if (fallback.isEmpty()) {
            // A plain string in the file is not a key, so only the conventional key can translate it.
            return ClientLocale.text(conventionalKey, value);
        }
        String byAuthor = ClientLocale.find(value);
        return byAuthor != null ? byAuthor : ClientLocale.text(conventionalKey, fallback);
    }

    /**
     * A canvas element's own words, resolved by the same four rungs every other text on the wire uses.
     *
     * <h2>Why the conventional key is built here</h2>
     *
     * <p>Because this is where the other three live — {@code quest.<id>.title}, {@code chapter.<id>.title} and
     * {@code rewardTable.<id>.title} — and a pack author should meet one spelling rule rather than two. The
     * field on the end is what keeps a label and a picture's title apart: an element has two pieces of text
     * and they are translated independently, so one key for both would translate a logo's caption whenever it
     * translated a label with the same id.
     *
     * <p>Public rather than private because the canvas is not this class: it draws the words and this resolves
     * them, and the alternative is a second copy of the chain in the screen.
     *
     * @param element the element the text belongs to, whose id names the conventional key
     * @param field   which of its texts this is: {@code "text"} or {@code "title"}
     * @param text    the author's own text, as the tree carried it
     */
    public static String elementWords(CanvasElement element, String field,
                                      dev.ellipog.tenet.quest.QuestText text) {
        return text(text.value(), text.fallback().orElse(""),
                "element." + element.id() + "." + field);
    }

    /**
     * The theme a chapter asks to be drawn in, or null when it has no opinion.
     *
     * <p>Null rather than the default theme's name, and that is not pedantry: this is what the screen
     * hands to the override, and an override of "modern" would beat a player who has chosen "tome" —
     * which is the exact bug the two-tier design exists to prevent. Absence has to survive the trip
     * so that "no opinion" and "the default" stay distinguishable.
     *
     * <p>Looks the chapter up in the entries rather than in a map of its own: the theme arrives on
     * every quest of the chapter, so a second structure would be a second thing to keep in step with
     * this one, and the entry list is the one the whole screen already walks.
     */
    public static String chapterTheme(String chapterId) {
        if (chapterId == null) {
            return null;
        }
        for (Entry entry : entries) {
            if (entry.chapterId().equals(chapterId)) {
                return entry.chapterTheme().isEmpty() ? null : entry.chapterTheme();
            }
        }
        return null;
    }

    /**
     * The chapter's theme patch, or null when it declares none — the counterpart of
     * {@link #chapterTheme}, and looked up the same way for the same reason.
     */
    public static com.google.gson.JsonObject chapterThemePatch(String chapterId) {
        if (chapterId == null) {
            return null;
        }
        for (Entry entry : entries) {
            if (entry.chapterId().equals(chapterId)) {
                return entry.chapterThemePatch();
            }
        }
        return null;
    }

    /**
     * The chapter's subtitle, as the player reads it, or empty when it names none.
     *
     * <p>Looked up in the entries like {@link #chapterTheme}, for the same reason: the subtitle
     * arrives on every quest of the chapter, so a second structure would be a second thing to keep
     * in step. What the sidebar row's hover draws. Empty for a chapter with no quests — which
     * carries no entry to read one from — and for every chapter of a server older than version 20,
     * which sent none at all.
     */
    public static String chapterSubtitleText(String chapterId) {
        if (chapterId == null) {
            return "";
        }
        for (Entry entry : entries) {
            if (entry.chapterId().equals(chapterId)) {
                return entry.chapterSubtitleText();
            }
        }
        return "";
    }

    public static int questCount() {
        return questCount;
    }

    public static int chapterCount() {
        return chapterCount;
    }

    /**
     * The pack's own name for the book, as the player reads it.
     *
     * <p>The tree's title overlaid with the pack's {@code book.title}: a pack that ships that key
     * renames the book for every language it translates, without touching {@code index.json}. Empty
     * reads as the client's own translatable title, which is what a pack that declares no title gets
     * — and what the header drew before the overlay existed, when nothing could translate it.
     */
    public static String bookTitle() {
        return ClientLocale.text("book.title", bookTitle);
    }

    /**
     * The tree's own book title, exactly as the server sent it.
     *
     * <p>The raw half of {@link #bookTitle()}: the editor seeds its Book field from this, because a
     * translated string written back to {@code index.json} would replace the author's own words with
     * somebody else's translation of them. See the {@code title()}/{@code titleText()} split on the
     * entries, which exists for the same reason.
     */
    public static String bookTitleRaw() {
        return bookTitle;
    }

    /**
     * The pack's icon id for the book, or empty when it declares none.
     *
     * <p>Kept beside the resolved stack so a missing item can be told from no icon at all: an id that
     * did not resolve is a mark the header draws, while an empty id is simply nothing to draw.
     */
    public static String bookIconId() {
        return bookIcon;
    }

    /** The pack's icon for the book, or {@link ItemStack#EMPTY}. */
    public static ItemStack bookIcon() {
        return bookIconStack;
    }

    /**
     * The book's texture path when its icon is a texture, and empty otherwise.
     *
     * <p>Drawn through the blit rather than the stack, like every other texture icon: the stack is
     * empty for a texture, and the id is empty with it so no missing-item branch fires.
     */
    public static String bookTextureIcon() {
        return bookTextureIcon;
    }

    /**
     * The book's atlas region when its icon is a sprite, and empty otherwise.
     * Drawn from the atlas, like every other sprite icon.
     */
    public static String bookSpriteIcon() {
        return bookSpriteIcon;
    }

    /**
     * Whether a locked quest wears its lock mark: the file's {@code showLockIcons}, read with the
     * quest's own {@code hideLockIcon} where a node is drawn. False for every server that predates
     * the key, which sent none at all — an old tree draws no marks on a new client.
     */
    public static boolean showLockIcons() {
        return showLockIcons;
    }

    /**
     * Whether a quest's own flag hides its lock mark, regardless of the file above.
     *
     * <p>False for a quest the tree never marked, which is every quest on a server that predates
     * the key. Read beside {@link #showLockIcons} — either silence wins — so a caller that only
     * asks one of them is a caller that draws a mark the pack asked to hide.
     */
    public static boolean hideLockIconOf(String questId) {
        Entry found = entry(questId);
        return found != null && found.hideLockIcon();
    }

    /**
     * Whether the reader's book hides quests an exclusive choice shut out.
     *
     * <p>False for every server that predates the key. Read with {@link #excludedOf}: the setting
     * says whether to hide, and the progress mark says which quests it applies to.
     */
    public static boolean hideExcludedQuests() {
        return hideExcludedQuests;
    }

    /**
     * Whether this quest was shut out for good by an exclusive choice — a taken group, or a
     * reached dependent cap — rather than merely not yet unlocked.
     *
     * <p>False for every quest on a server that predates the mark, which is the reading that hides
     * least: without it the setting above has nothing to hide.
     */
    public static boolean excludedOf(String questId) {
        Progress found = progress.get(questId);
        return found != null && found.excluded();
    }

    /**
     * Whether the book pauses the world in single player: the file's {@code pauseGame}, which
     * {@code QuestBookScreen.isPauseScreen} answers with.
     *
     * <p>False for every server that predates the key — the book has never paused the world, and
     * a pack that never heard of the field keeps that behaviour.
     */
    public static boolean pauseGame() {
        return pauseGame;
    }

    /**
     * Whether the book refuses to open: the file's {@code disableGui}.
     *
     * <p>False for every server that predates the key. Every open path answers with the same
     * sentence instead of a screen while this holds.
     */
    public static boolean guiDisabled() {
        return guiDisabled;
    }

    /**
     * Whether the book's chapters draw without the zoom tiers unless a chapter says
     * otherwise: the file's {@code disableCanvasLod}, and the fallback a chapter that
     * says nothing defers to.
     *
     * <p>False for every server that predates the key. Read with a chapter's own answer
     * in {@link #lodDisabledFor} — either silence defers, so a caller that only asks
     * one of them tiers a chapter the pack asked to draw whole.
     */
    public static boolean bookLodDisabled() {
        return bookLodDisabled;
    }

    /**
     * Whether one chapter's canvas draws without the zoom tiers: its own answer when
     * the tree carried one, else the book's.
     *
     * <p>Through the chapter index rather than a scan, because the callers are per
     * node: the canvas asks for every node it draws, every frame. A null chapter —
     * no chapter on screen — reads as the book's own answer, which is the only
     * reading that keeps "the book says tierless" true where no chapter overrides it.
     */
    public static boolean lodDisabledFor(String chapterId) {
        if (chapterId != null) {
            ChapterEntry found = chaptersById().get(chapterId);
            if (found != null && found.lodDisabled() != null) {
                return found.lodDisabled();
            }
        }
        return bookLodDisabled;
    }

    /**
     * What a locked quest is called when the pack has a better word than "Locked": the file's
     * {@code lockMessage}, the author's own sentence.
     *
     * <p>Empty means the client's own word, which is what every pack that predates the key gets —
     * and what the card draws without consulting this at all.
     */
    public static String lockMessage() {
        return lockMessage;
    }

    public static long syncedAt() {
        return syncedAt;
    }

    public static Optional<UUID> teamId() {
        return Optional.ofNullable(teamId);
    }

    /** The state of a quest. LOCKED for anything unknown — which is what a missing quest should look like. */
    public static QuestState stateOf(String questId) {
        Progress found = progress.get(questId);
        return found == null ? QuestState.LOCKED : found.state();
    }

    /**
     * How far one chapter has got, by its own id.
     *
     * <h2>UNLOCKED for a chapter this client has not been told about</h2>
     *
     * <p>Which is the reading that hides least, and the one that makes the field additive: a server
     * older than chapter gates sends no chapter states at all, and every chapter it describes is drawn
     * exactly as it is today. The same answer covers a chapter the tree named and the progress message
     * has not caught up with, where shutting the row would be a claim made on no evidence.
     *
     * <p>Computed on the server rather than here, and that is the design rather than an optimisation: a
     * chapter's completion is declared against quests, a quest's state is the team's, and the client's
     * own view of the canvas is filtered (an author sees hidden quests, a reader does not) — so a chapter
     * state derived here would disagree with the server's for the person most likely to notice.
     */
    public static QuestState chapterStateOf(String chapterId) {
        if (chapterId == null) {
            return QuestState.UNLOCKED;
        }
        return chapterStates.getOrDefault(chapterId, QuestState.UNLOCKED);
    }

    /** How far along a task is, as the server last reported. Zero for anything unknown. */
    /**
     * Who is holding what toward one task, in the order the server named them.
     *
     * <p>Empty for a quest this client has no progress for, for a task nobody is carrying anything
     * toward, and for every task of a server that predates the field — one answer for all three, and
     * the safe one: no faces drawn rather than a wrong name beside a task.
     */
    public static Map<UUID, Integer> contributorsOf(String questId, int taskIndex) {
        Progress found = progress.get(questId);
        return found == null ? Map.of() : found.contributorsOf(taskIndex);
    }

    public static int taskProgressOf(String questId, int taskIndex) {
        Progress found = progress.get(questId);
        if (found == null || taskIndex < 0 || taskIndex >= found.tasks().size()) {
            return 0;
        }
        return found.tasks().get(taskIndex);
    }

    /**
     * The conditions this player does not meet on one task, ascending; empty means the task is open.
     *
     * <p>This player's, not the team's: two members of a party can look at one quest and see different
     * tasks shut. Empty for a quest or task this client has no progress for, and for every task of a
     * server that predates conditions — one answer for all three, and the safe one: a row drawn open
     * that the server would refuse is corrected by the refusal and the sync that follows it.
     *
     * <p>The indices are positions in the task's own {@code conditions} list, which the tree carries;
     * the hover uses them to name exactly what is missing rather than listing the gates that hold too.
     */
    public static List<Integer> taskLockOf(String questId, int taskIndex) {
        Progress found = progress.get(questId);
        return found == null ? List.of() : found.taskLock(taskIndex);
    }

    /** The same for one reward. */
    public static List<Integer> rewardLockOf(String questId, int rewardIndex) {
        Progress found = progress.get(questId);
        return found == null ? List.of() : found.rewardLock(rewardIndex);
    }

    /**
     * Whether the server would accept a press on one task right now.
     *
     * <p>False for a quest or task this client has no progress for, and for every task of a server that
     * predates {@code taskReady} — one answer for all three, and the safe one: no button offered rather
     * than a press the server would refuse.
     */
    public static boolean taskReadyOf(String questId, int taskIndex) {
        Progress found = progress.get(questId);
        return found != null && found.ready(taskIndex);
    }

    /**
     * Whether one task is finished, by the rule the viewer page reads.
     *
     * <h2>Why this is a method rather than a comparison written at each call site</h2>
     *
     * <p>{@code progress >= count} is the definition of a finished task, and it was spelled out inline
     * in the viewer's live row. One predicate, one reader, so the rule cannot drift between the page
     * that draws a task's status and anything else that later needs the same answer.
     */
    public static boolean taskDone(String questId, int taskIndex) {
        Entry entry = entry(questId);
        return entry != null && taskDone(entry, questId, taskIndex);
    }

    /** The same, for a caller that already holds the entry. */
    private static boolean taskDone(Entry entry, String questId, int taskIndex) {
        if (taskIndex < 0 || taskIndex >= entry.tasks().size()) {
            return false;
        }
        return taskProgressOf(questId, taskIndex) >= entry.tasks().get(taskIndex).count();
    }

    /**
     * The first task whose row offers the Submit button, or -1 for a quest that offers none.
     *
     * <h2>Two rules, because the two kinds of manual task are handed in at opposite moments</h2>
     *
     * <p>A <b>checkmark</b> has no count to meet — its count is the press itself — so its button is
     * offered while that press has not happened, which is what {@code progress < count} says. A task
     * that <b>takes</b> what it asks for is the other way round: the press is refused until the player
     * is holding enough, so its button appears when the server says a press would be accepted. Offering
     * it earlier would be a button whose only effect is a refusal, and offering it later — which is what
     * a single {@code progress < count} rule did once such a task stopped being registered by the tick —
     * would hide it at exactly the moment it became usable.
     *
     * <p>A locked task has no button either way: the press would be refused, and the row already says
     * why. The question is the same one the reward rows ask of {@link #canClaimFor}, one kind over.
     */
    public static int firstSubmitTask(String questId) {
        for (Entry quest : entries) {
            if (!quest.id().equals(questId)) {
                continue;
            }
            for (int i = 0; i < quest.tasks().size(); i++) {
                if (submitOffered(questId, i)) {
                    return i;
                }
            }
            return -1;
        }
        return -1;
    }

    /**
     * Whether one task's row offers the Submit button right now — the predicate {@link #firstSubmitTask}
     * walks, on its own so the row's tag and the hover can ask the same question rather than answer it
     * again. See that method for why the two kinds of manual task are offered their button at opposite
     * moments, and why a locked task is offered neither.
     */
    public static boolean submitOffered(String questId, int taskIndex) {
        List<Entry> all = entries;
        for (Entry quest : all) {
            if (!quest.id().equals(questId)) {
                continue;
            }
            if (taskIndex < 0 || taskIndex >= quest.tasks().size()) {
                return false;
            }
            // No button on a quest the press would be refused for. Two cases, and both are the
            // server's own rule (`!state.isPlayable()` refuses the submit) read through what the
            // client can see. A locked quest refuses every press, so it offers none. A completed
            // repeatable still cooling refuses too: the round's tasks read 0 of 1, so the count rule
            // below would offer one. The button comes back when the cooldown reads zero -- a replayable
            // quest resolves playable on the server while its stored state stays completed, and the
            // cooldown is what tells the two apart here -- whether by this client's own countdown or
            // by the next sync.
            QuestState seen = stateOf(questId);
            if (seen == QuestState.LOCKED
                    || seen == QuestState.COMPLETED
                            && cooldownOf(questId, ClientTicker.ticks()) > 0) {
                return false;
            }
            TaskEntry task = quest.tasks().get(taskIndex);
            if (!task.manual() || !taskLockOf(questId, taskIndex).isEmpty()) {
                return false;
            }
            // A task the tick never measures is always offered its button: the live count of such
            // a task is zero by construction, so the held-enough rule below would hide the press
            // forever. A short press is refused by the server with the same "not enough" line any
            // other short press earns, which is the legacy "click to submit" contract.
            if (task.manualOnly()) {
                return true;
            }
            return task.waits()
                    ? taskReadyOf(questId, taskIndex)
                    : taskProgressOf(questId, taskIndex) < task.count();
        }
        return false;
    }

    /**
     * Whether <b>this player</b> is finished with rewards they have not collected.
     *
     * <p>Per player, because a claim is a player's own: in a party where a teammate collected their
     * diamond, this player's button must still be there. The server sends who collected what, and
     * the tree sends each reward's own {@code team} flag; this is the two of them against the local
     * player's UUID. The server recomputes the same answer when the claim arrives — asking is not
     * claiming, and a client that shows the button wrongly gets a refusal.
     */
    public static boolean canClaimFor(UUID player, String questId) {
        return outstandingRewards(player, questId) > 0;
    }

    /**
     * Whether the team's rewards are held, so the panel can say why a claim would be refused.
     *
     * <p>False for a quest this client has no progress for, and for every quest of a server that predates
     * the key — one answer for both, and the safe one: no explanation offered rather than one that is
     * wrong. See {@link #canClaimFor} for the question the button itself asks.
     */
    public static boolean rewardsBlockedOf(String questId) {
        Progress found = progress.get(questId);
        return found != null && found.blocked();
    }

    /**
     * Whether the team's rewards are held at all, for a control that spans every quest.
     *
     * <p>The flag is the team's, so any quest that carries it carries it for the whole record — this
     * exists so the rewards inbox's sweep button can ask once rather than guess from a row.
     */
    public static boolean rewardsHeld() {
        return progress.values().stream().anyMatch(Progress::blocked);
    }

    /**
     * How many of a quest's rewards this player could collect right now.
     *
     * <p>The one loop behind {@link #canClaimFor} and the reward badges: a quest is claimable exactly
     * when this is more than zero, so the node badge, the Claim button and the rewards panel cannot
     * disagree about how much is waiting. The count is the number of rewards, not of quests, because a
     * node's badge says "three things are here"; the sidebar's count is of quests, because that is the
     * question a chapter's row answers — see {@link #claimableByChapter}.
     */
    public static int outstandingRewards(UUID player, String questId) {
        Entry entry = entry(questId);
        return entry == null ? 0 : outstandingIn(player, entry);
    }

    /** The same count for an entry already in hand, so the aggregates are one walk and not O(n²). */
    private static int outstandingIn(UUID player, Entry entry) {
        Progress found = progress.get(entry.id());
        if (found == null || player == null) {
            return 0;
        }
        int outstanding = 0;
        for (int index = 0; index < entry.rewards().size(); index++) {
            if (claimable(player, entry, found, index)) {
                outstanding++;
            }
        }
        return outstanding;
    }

    /**
     * Whether <b>this player</b> could collect one reward of a quest right now.
     *
     * <p>The single-row form of {@link #canClaimFor}, and deliberately the same loop: the rewards
     * panel's per-row Claim hangs on this, so a row cannot offer a press the quest-level badge would
     * not count, and the panel's header cannot disagree with its own rows. The server recomputes the
     * same answer when the press arrives — asking is not claiming, and a client that shows the button
     * wrongly gets a refusal.
     */
    public static boolean canClaimReward(UUID player, String questId, int rewardIndex) {
        Entry entry = entry(questId);
        return entry != null && canClaimReward(player, entry, rewardIndex);
    }

    /**
     * The same, for a caller that already holds the entry.
     *
     * <p>Because the id lookup is a walk of the whole tree, and the rewards panel's filter asks this
     * per reward per frame: without this overload a five-hundred-quest pack would answer one frame's
     * question with half a million comparisons.
     */
    public static boolean canClaimReward(UUID player, Entry entry, int rewardIndex) {
        Progress found = progress.get(entry.id());
        if (found == null || player == null
                || rewardIndex < 0 || rewardIndex >= entry.rewards().size()) {
            return false;
        }
        return claimable(player, entry, found, rewardIndex);
    }

    /**
     * One reward's claimability against progress already in hand — the one predicate behind both forms.
     *
     * <p>A reward whose conditions this player does not meet is not claimable by them: counting it
     * would show a badge the server refuses. The lock is this player's, so a teammate who meets the
     * conditions still counts theirs.
     */
    private static boolean claimable(UUID player, Entry entry, Progress found, int index) {
        if (found.state() != QuestState.COMPLETED || found.legacySettled()) {
            return false;
        }
        return found.rewardLock(index).isEmpty()
                && !found.claimed(player, index, entry.rewards().get(index).team());
    }

    /**
     * The quests this player could collect from right now, by chapter id — the sidebar's counts.
     *
     * <p>A quest is counted once however many rewards it holds: a chapter's row answers "how many
     * quests have something for me". The claim menu's banner answers a different question — "how many
     * rewards are ready in this chapter" — and it counts them from the rows it builds rather than from
     * a map here, so the badge and the list under it cannot be counted over two different populations.
     * There was a map for it, and removing it removed that whole class of disagreement.
     */
    public static java.util.Map<String, Integer> claimableByChapter(UUID player) {
        java.util.Map<String, Integer> counts = new java.util.LinkedHashMap<>();
        for (Entry entry : entries) {
            if (outstandingIn(player, entry) > 0) {
                counts.merge(entry.chapterId(), 1, Integer::sum);
            }
        }
        return counts;
    }

    /** The rewards waiting per quest, by quest id — the canvas badges' own count. */
    public static java.util.Map<String, Integer> outstandingByQuest(UUID player) {
        java.util.Map<String, Integer> counts = new java.util.LinkedHashMap<>();
        for (Entry entry : entries) {
            int outstanding = outstandingIn(player, entry);
            if (outstanding > 0) {
                counts.put(entry.id(), outstanding);
            }
        }
        return counts;
    }

    /**
     * The tree entry for a quest id, or null.
     *
     * <p>Through the index: see {@link #byId()} for why it is derived from the list rather than kept
     * beside it, and for the first-match rule it has to preserve.
     *
     * <p>Matched <b>exactly</b>, and that is a contract rather than a detail: the server's own
     * identifier lookup is a plain map keyed the same way, so a client that folded case would resolve
     * an id the tree does not hold. Null in, null out — not a crash, and the same answer the scan gave
     * when it asked a string it did not hold whether it equalled each id in turn.
     */
    public static Entry entry(String questId) {
        return questId == null ? null : byId().get(questId);
    }

    /**
     * Whether <b>this player</b> has already collected one reward.
     *
     * <p>The same fact {@link #canClaimFor} folds into one answer for the whole quest, asked about a
     * single row: a viewer page draws a status per reward — ready, locked or claimed — and a claim is
     * a player's own, so a teammate's collection does not mark this player's row. The reward's own
     * {@code team} flag decides, exactly as the claim path does.
     */
    public static boolean rewardClaimedBy(UUID player, String questId, int rewardIndex) {
        Entry entry = entry(questId);
        return entry != null && rewardClaimedBy(player, entry, rewardIndex);
    }

    /**
     * The same, for a caller that already holds the entry.
     *
     * <p>Because the id lookup is a walk of the whole tree, and the claim menu asks this once per reward
     * of every visible quest, every frame: without this overload a five-hundred-quest pack would answer
     * one frame's question with half a million comparisons. The same reason {@link #canClaimReward} has
     * the same pair.
     */
    public static boolean rewardClaimedBy(UUID player, Entry entry, int rewardIndex) {
        Progress found = progress.get(entry.id());
        if (found == null || player == null
                || rewardIndex < 0 || rewardIndex >= entry.rewards().size()) {
            return false;
        }
        return found.claimed(player, rewardIndex, entry.rewards().get(rewardIndex).team());
    }

    /**
     * Ticks of cooldown left for a quest, adjusted for the time since the sync arrived.
     *
     * <p>Adjusted rather than sent live, because a cooldown is a countdown and counting it down from a
     * known point costs one subtraction per frame instead of a packet per second. The client's tick
     * counter and the server's are different counters, so what gets subtracted is the client ticks
     * elapsed since the sync — close enough to display, and corrected by the next sync anyway.
     */
    public static long cooldownOf(String questId, long clientTickNow) {
        Progress found = progress.get(questId);
        if (found == null || found.cooldown() <= 0) {
            return 0L;
        }
        long elapsed = Math.max(0L, clientTickNow - syncedAt);
        return Math.max(0L, found.cooldown() - elapsed);
    }

    /**
     * How many times a repeatable quest has been finished, or zero.
     *
     * <p>Zero for a quest the server never counted — including every quest from a server that
     * predates the field — which reads as "never repeated" rather than inventing a history.
     */
    public static int timesCompletedOf(String questId) {
        Progress found = progress.get(questId);
        return found == null ? 0 : found.timesCompleted();
    }

    // ------------------------------------------------------------------
    // Writing
    // ------------------------------------------------------------------

    /**
     * A tree that carries no pack theme.
     *
     * <p>An overload rather than a {@code null} at each call site, and the reason is what a bare
     * {@code null} third argument reads like: {@code acceptTree(2, 1, null, json)} says nothing about
     * what is absent, and there are seventeen callers that mean "no theme" — every one of them a test
     * asserting on the tree's contents rather than on the connection's appearance.
     *
     * <p><b>A production caller with a theme must use the four-argument form.</b> This exists for the
     * case where there is no theme to pass, not as the convenient path; a wire handler that reached
     * for it would silently stop applying a pack's look, which is the kind of omission that shows up
     * as "the pack's theme works on my machine".
     */
    public static void acceptTree(int quests, int chapters, byte[] json) {
        acceptTree(quests, chapters, null, json);
    }

    /** Called from the payload handler on the client thread. */
    public static void acceptTree(int quests, int chapters, String packTheme, byte[] json) {
        // Moved before anything is parsed, and that is deliberate: the revision says *which tree this
        // cache holds*, and every path out of this method changes that. A parsed tree replaces what was
        // there; a tree that could not be read empties it. A revision that only moved on success would
        // leave a screen drawing the rows of a tree the cache has just thrown away.
        treeRevision++;

        try {
            parseTree(new String(json, StandardCharsets.UTF_8));
            questCount = quests;
            chapterCount = chapters;
            treeReceived = true;

            // The pack's main theme, applied before anything is drawn from this tree. It only takes
            // effect for a player who has never chosen a theme of their own -- `ClientAppearance.LOOK.main`
            // decides that, and this class has no business knowing the rule. Null rather than "leave it
            // alone" when the tree carries none: a server that stops sending one must stop influencing
            // the client, or a player would carry one pack's look onto the next server with nothing on
            // screen to explain it.
            ClientAppearance.LOOK.setServerDefault(packTheme);
            Constants.LOG.info("tenet: received {} quest(s) in {} chapter(s)", quests, chapters);
        }
        catch (RuntimeException e) {
            // A malformed tree is a bug in the serialiser, not the player's problem. Cleared rather
            // than half-kept, so the screen shows "no quests" instead of a list with entries missing
            // in the middle and nothing saying why.
            Constants.LOG.error("tenet: the server sent a quest tree this client could not read", e);
            entries = List.of();
            groups = List.of();
            // Qualified, because this method's own `chapters` parameter is the count that came with the
            // payload and shadows the list.
            ClientQuestCache.chapters = List.of();
            chapterElements = Map.of();
            chapterLinks = Map.of();
            bookTitle = "";
            bookIcon = "";
            bookIconStack = ItemStack.EMPTY;
            bookTextureIcon = "";
            bookSpriteIcon = "";
            showLockIcons = false;
            hideExcludedQuests = false;
            pauseGame = false;
            guiDisabled = false;
            bookLodDisabled = false;
            lockMessage = "";
            treeReceived = false;
        }
    }

    /**
     * A full progress sync. Called from the payload handler on the client thread.
     *
     * <p>The short form, kept because a full sync is what most callers mean and what every test
     * written before deltas existed passes. It delegates rather than duplicating: the two paths must
     * not be able to disagree about what "full" does.
     *
     * @param clientTickNow the client's tick count, so cooldowns can be counted down from here
     */
    public static void acceptProgress(UUID incomingTeam, long gameTime, byte[] json, long clientTickNow) {
        acceptProgress(incomingTeam, gameTime, json, clientTickNow, true);
    }

    /**
     * A progress message — full or a delta.
     *
     * <h2>A delta with no full sync behind it is refused, not applied</h2>
     *
     * <p>This is the one refusal in this class, and it is here because applying such a delta produces
     * a cache holding a handful of quests and everything else {@code LOCKED} — which looks exactly
     * like a working sync of a very small pack. There is no symptom that points at the cause, so the
     * failure has to be prevented rather than diagnosed: the server sends a full sync on join and on
     * reload, so a client that has none has missed something, and the honest response is to keep what
     * it has and wait.
     *
     * <p>The check is the team id rather than whether anything is held. A delta for a team this client
     * has never heard of cannot be relative to anything; a delta for the team it already holds can, and
     * that includes the case where the server's questline is empty.
     *
     * @param full whether the server said this message is the whole of its progress
     */
    public static void acceptProgress(UUID incomingTeam, long gameTime, byte[] json, long clientTickNow,
                                      boolean full) {
        UUID previous = teamId;

        if (full) {
            // Replaced, not merged. A full sync is the server saying "this is all of it", and merging
            // would leave a quest the server has since removed sitting in the cache forever.
            progress = Map.of();
        }
        else if (previous == null || !previous.equals(incomingTeam)) {
            Constants.LOG.warn("tenet: refused a progress delta for team {} -- this client holds no "
                    + "full sync for it (it holds {}), so there is nothing for the delta to be relative "
                    + "to. The server sends a full sync on join and on reload.",
                    incomingTeam, previous);
            return;
        }

        if (previous != null && !previous.equals(incomingTeam)) {
            Constants.LOG.info("tenet: progress is now for team {} (was {})", incomingTeam, previous);
        }
        teamId = incomingTeam;

        try {
            parseProgress(new String(json, StandardCharsets.UTF_8), full);
            syncedAt = clientTickNow;
        }
        catch (RuntimeException e) {
            Constants.LOG.error("tenet: the server sent progress this client could not read", e);
            progress = Map.of();
            // And nothing is named, as the whole of it: the cache has just been emptied, so a reader
            // holding a baseline is looking at a tree that says nothing rather than at a delta that
            // named a few quests. See ProgressTouch.
            lastTouch = ProgressTouch.nothing;
            // The team is forgotten too, and that is the important half: leaving it set would make the
            // *next* delta look applicable, and it would be applied onto the empty map this catch just
            // left behind. Clearing it means the next message has to be a full sync to be accepted,
            // which is the correct resynchronisation.
            teamId = null;
            // And the revision moves, for the same reason `clear()` moves it: an emptied cache is not
            // the progress that was there a moment ago. Without this, a watcher that trusts "revision
            // == what the cache holds" -- the completion notifier does -- would keep the discarded
            // map's states and could announce against them.
            progressRevision++;
        }
    }

    /**
     * Forgets everything.
     *
     * <p>Called on disconnect. Without it, leaving one server and joining another shows the first
     * server's questline until the new sync arrives — which looks exactly like a sync failure and is
     * not one, so it sends you looking in the wrong place.
     */
    public static void clear() {
        entries = List.of();
        groups = List.of();
        chapters = List.of();
        // And the decoration, which is a description of a server's pack as surely as its chapters are:
        // leaving it would draw the last world's labels over the next one's canvas.
        chapterElements = Map.of();
        // And the markers with it, for the same reason: a shortcut to a quest on the last world's
        // canvas must not survive onto the next one's.
        chapterLinks = Map.of();
        tables = List.of();
        refusedTables = List.of();
        progress = Map.of();
        // And the chapter states, which describe a server's progress as surely as the quests do: leaving
        // them would keep a chapter shut on the next server for a gate that server has never heard of.
        chapterStates = Map.of();
        // And what the last message named goes with it. A reader comparing samples holds a baseline of
        // quests; after a clear there are none, and telling it "a delta named nothing" would leave it
        // holding the last server's states to compare the next server's tree against. See ProgressTouch.
        lastTouch = ProgressTouch.nothing;
        teamId = null;
        questCount = 0;
        chapterCount = 0;
        syncedAt = 0;
        bookTitle = "";
        bookIcon = "";
        bookIconStack = ItemStack.EMPTY;
        bookTextureIcon = "";
        bookSpriteIcon = "";
        showLockIcons = false;
        hideExcludedQuests = false;
        pauseGame = false;
        guiDisabled = false;
        bookLodDisabled = false;
        lockMessage = "";
        treeReceived = false;
        // The sampled outlines go with the trees that asked for them: they are keyed by shape and
        // angle, so they cannot go stale, but a world's worth of them is not this world's to keep.
        ROTATED.clear();
        // Moved rather than left alone, because clearing changes what the cache holds as surely as
        // receiving does: a screen that seeded an outline at the old revision would otherwise keep
        // drawing that tree's rows for a cache that has nothing in it.
        // And the filter enumerations, which name this registry's items for that tree's expressions.
        FilterMatches.forget();
        treeRevision++;
        // And the progress counter, for the same reason again: an empty cache is not the progress that
        // was there a moment ago, and a panel holding a Claim button for it is holding a button for a
        // server this client has left.
        progressRevision++;
        // And the pack's theme, for the reason in this method's javadoc: it describes a connection, so
        // leaving it set would show one server's look on the next one -- an appearance nobody chose,
        // with nothing on screen saying where it came from.
        ClientAppearance.LOOK.setServerDefault(null);
    }

    // ------------------------------------------------------------------
    // Parsing
    // ------------------------------------------------------------------

    private static void parseTree(String json) {
        JsonObject root = JsonParser.parseString(json).getAsJsonObject();

        // Read, and only warned about. Deliberately not a gate: refusing a version this client does not
        // know would break exactly the case the additive design exists to keep working — a client on an
        // older install drawing today's flat list from a server that has moved on. So a newer tree is
        // drawn with whatever this build understands, and this log line is the only symptom a player
        // will ever get, which is why it names both numbers.
        int version = root.has("version") ? root.get("version").getAsInt() : 1;
        if (version > QuestSync.TREE_VERSION) {
            Constants.LOG.warn("tenet: the server sent a version {} quest tree and this client"
                    + " understands up to version {}. Anything it added will not be drawn.", version,
                    QuestSync.TREE_VERSION);
        }

        // The group headings, when the server sent any. Absent means a server older than groups, so
        // this defaults rather than requires and that tree draws the flat list it always drew.
        //
        // No `version` test guards this, deliberately: the key's presence is the fact. Testing a number
        // to decide whether a key is there would be a second way to find out something already known,
        // and the two would disagree the first time a server sent one without the other.
        List<GroupEntry> parsedGroups = new ArrayList<>();
        if (root.has("groups")) {
            for (JsonElement element : root.getAsJsonArray("groups")) {
                JsonObject group = element.getAsJsonObject();
                ResolvedIcon icon = resolveIcon(group, "icon", "iconComponents", "iconKind");
                parsedGroups.add(new GroupEntry(
                        str(group, "id"),
                        str(group, "title"),
                        group.has("collapsedByDefault") && group.get("collapsedByDefault").getAsBoolean(),
                        icon.stack(),
                        icon.id(),
                        icon.texture(),
                        icon.sprite(),
                        str(group, "titleFallback")));
            }
        }

        // The chapters themselves, when the server sent them. Absent means a server older than version
        // 3, whose chapters are still derivable from the quests below -- so this defaults rather than
        // requires, and nothing tests the version number to find out. The key's presence is the fact.
        List<ChapterEntry> parsedChapters = new ArrayList<>();
        Map<String, List<CanvasElement>> parsedElements = new LinkedHashMap<>();
        Map<String, List<dev.ellipog.tenet.quest.QuestLink>> parsedLinks = new LinkedHashMap<>();
        if (root.has("chapters")) {
            for (JsonElement element : root.getAsJsonArray("chapters")) {
                JsonObject chapter = element.getAsJsonObject();
                List<String> dependsOn = new ArrayList<>();
                if (chapter.has("dependsOn")) {
                    for (JsonElement dependency : chapter.getAsJsonArray("dependsOn")) {
                        dependsOn.add(dependency.getAsString());
                    }
                }
                // The chapter's own gate rule, as the server resolved it. Absent means no gate, and the
                // mode defaults to the one that asks the most -- the same pair of readings ChapterRules
                // gives, so a server that says nothing about a chapter gets the same answer as a file
                // that says nothing about one.
                dev.ellipog.tenet.quest.PrerequisiteMode mode =
                        dev.ellipog.tenet.quest.PrerequisiteMode.ALL_COMPLETED;
                if (chapter.has("prerequisiteMode")) {
                    mode = dev.ellipog.tenet.quest.PrerequisiteMode.CODEC
                            .parse(com.mojang.serialization.JsonOps.INSTANCE,
                                    chapter.get("prerequisiteMode"))
                            .result().orElse(dev.ellipog.tenet.quest.PrerequisiteMode.ALL_COMPLETED);
                }
                ResolvedIcon chapterIcon =
                        resolveIcon(chapter, "icon", "iconComponents", "iconKind");
                parsedChapters.add(new ChapterEntry(
                        str(chapter, "id"),
                        str(chapter, "groupId"),
                        str(chapter, "title"),
                        chapterIcon.stack(),
                        chapterIcon.id(),
                        chapterIcon.texture(),
                        chapterIcon.sprite(),
                        List.copyOf(dependsOn),
                        mode,
                        chapter.has("minRequired") ? Math.max(0, chapter.get("minRequired").getAsInt()) : 0,
                        chapter.has("hideUntilDependenciesComplete")
                                && chapter.get("hideUntilDependenciesComplete").getAsBoolean(),
                        str(chapter, "titleFallback"),
                        str(chapter, "autofocus"),
                        chapter.has("alwaysInvisible")
                                && chapter.get("alwaysInvisible").getAsBoolean(),
                        chapter.has("disableCanvasLod")
                                ? chapter.get("disableCanvasLod").getAsBoolean() : null));

                // The canvas's decoration, when the server sent any -- version 14 and a chapter that has
                // some. Read by the element codec rather than field by field, which is the other half of
                // the writer's decision to send each element's own object: one reader, and a field added
                // to an arm arrives without anybody remembering to read it.
                //
                // An element this client cannot read is skipped rather than taking the chapter with it. It
                // should not happen -- the server validated the file -- and if it does, the honest outcome
                // is one decoration missing from a canvas rather than a chapter that will not draw.
                if (chapter.has("elements") && chapter.get("elements").isJsonArray()) {
                    List<CanvasElement> parsed = new ArrayList<>();
                    for (JsonElement each : chapter.getAsJsonArray("elements")) {
                        CanvasElement.fromJson(each).ifPresent(parsed::add);
                    }
                    if (!parsed.isEmpty()) {
                        parsedElements.put(str(chapter, "id"), List.copyOf(parsed));
                    }
                }

                // The chapter's markers, when the server sent any -- version 15 and a chapter that has
                // some. Read by the link codec rather than field by field, which is the other half of
                // the writer's decision to send each link's own object: one reader, and a field added
                // to a link arrives without anybody remembering to read it.
                //
                // A link this client cannot read is skipped rather than taking the chapter with it, for
                // the same reason as an element: one marker missing from a canvas rather than a chapter
                // that will not draw.
                if (chapter.has("links") && chapter.get("links").isJsonArray()) {
                    List<dev.ellipog.tenet.quest.QuestLink> parsed = new ArrayList<>();
                    for (JsonElement each : chapter.getAsJsonArray("links")) {
                        dev.ellipog.tenet.quest.QuestLink.fromJson(each).ifPresent(parsed::add);
                    }
                    if (!parsed.isEmpty()) {
                        parsedLinks.put(str(chapter, "id"), List.copyOf(parsed));
                    }
                }
            }
        }

        // The reward tables, when the server sent any. Absent means a server older than version 11:
        // the editor's browser then lists nothing, which is the truth about that server rather than an
        // error. The key's presence is the fact, as it is for groups and chapters above.
        List<TableSummary> parsedTables = new ArrayList<>();
        if (root.has("rewardTables")) {
            for (JsonElement element : root.getAsJsonArray("rewardTables")) {
                JsonObject table = element.getAsJsonObject();
                parsedTables.add(new TableSummary(
                        str(table, "id"),
                        str(table, "title"),
                        stack(str(table, "icon"), 1, table.get("iconComponents")),
                        str(table, "icon"),
                        table.has("entries") ? table.get("entries").getAsInt() : 0,
                        table.has("useTitle") && table.get("useTitle").getAsBoolean(),
                        table.has("hideTooltip") && table.get("hideTooltip").getAsBoolean()));
            }
        }

        // The tables that did not load, when the server sent any. A server older than this version sends
        // none, which reads as "every table loaded" — true of what it told us, and the panel says nothing
        // rather than claiming a file is fine.
        List<RefusedTable> parsedRefused = new ArrayList<>();
        if (root.has("refusedTables")) {
            for (JsonElement element : root.getAsJsonArray("refusedTables")) {
                JsonObject refused = element.getAsJsonObject();
                parsedRefused.add(new RefusedTable(str(refused, "id"), str(refused, "why")));
            }
        }

        JsonArray quests = root.getAsJsonArray("quests");

        // The pack's own book identity, when the tree carries one. The header's values, read here so
        // they travel with the tree; `acceptTree`'s catch and `clear()` both reset them with everything
        // else, so a malformed tree or a disconnect cannot leave one pack's name on another's book.
        bookTitle = str(root, "bookTitle");
        ResolvedIcon book = resolveIcon(root, "bookIcon", "bookIconComponents", "bookIconKind");
        bookIcon = book.id();
        bookIconStack = book.stack();
        bookTextureIcon = book.texture();
        bookSpriteIcon = book.sprite();
        // The file's own answers, when the tree carries them. Each travels sparse and unversioned,
        // so absence is the default — which is what a server that predates the key always says,
        // and what the accessors above promise. Read here, beside the book's identity, for the
        // same reason: they travel with the tree, and the catch and `clear()` reset them below.
        showLockIcons = root.has("showLockIcons")
                && root.get("showLockIcons").getAsBoolean();
        hideExcludedQuests = root.has("hideExcludedQuests")
                && root.get("hideExcludedQuests").getAsBoolean();
        pauseGame = root.has("pauseGame") && root.get("pauseGame").getAsBoolean();
        guiDisabled = root.has("disableGui") && root.get("disableGui").getAsBoolean();
        bookLodDisabled = root.has("disableCanvasLod")
                && root.get("disableCanvasLod").getAsBoolean();
        lockMessage = str(root, "lockMessage");

        List<Entry> parsed = new ArrayList<>(quests.size());
        for (JsonElement element : quests) {
            JsonObject quest = element.getAsJsonObject();

            List<String> description = new ArrayList<>();
            if (quest.has("description")) {
                for (JsonElement paragraph : quest.getAsJsonArray("description")) {
                    description.add(paragraph.getAsString());
                }
            }
            // The ends of the prose are not content -- see `Prose`. Trimmed where the tree is parsed, so a
            // reader's card and the editor's copy of the same file agree about where the prose stops.
            //
            // The English half of each paragraph is read first and cut to the *same* ends, because the
            // two lists are paired by index and a literal paragraph in the middle carries an empty
            // fallback -- so trimming the fallbacks by their own blankness would drop that entry and
            // shift every paragraph after it onto the wrong translation. See `Prose.Ends`.
            //
            // The fallbacks are sent only for a description that is translatable at all, so an absent
            // array is the common case and means every paragraph is a literal. It may also be shorter
            // than the prose, which `cut` clamps: a server that sent fewer fallbacks than paragraphs
            // leaves the tail reading as literal, which is what those paragraphs are.
            List<String> descriptionFallbacks = new ArrayList<>();
            if (quest.has("descriptionFallbacks")) {
                for (JsonElement paragraph : quest.getAsJsonArray("descriptionFallbacks")) {
                    descriptionFallbacks.add(paragraph.getAsString());
                }
            }
            Prose.Ends ends = Prose.ends(description);
            description = new ArrayList<>(ends.cut(description));
            descriptionFallbacks = new ArrayList<>(ends.cut(descriptionFallbacks));

            // The dependency rule, as the server resolved it. Absent means the quest has no opinion and
            // the chapter's default applies -- which is a different thing from the mode written out, so
            // this is null rather than a fallback. A name this build has never heard of is also null:
            // the read-out then treats it as the chapter default, which is the safe reading.
            dev.ellipog.tenet.quest.PrerequisiteMode prerequisiteMode = null;
            if (quest.has("prerequisiteMode")) {
                prerequisiteMode = dev.ellipog.tenet.quest.PrerequisiteMode.CODEC
                        .parse(com.mojang.serialization.JsonOps.INSTANCE, quest.get("prerequisiteMode"))
                        .result().orElse(null);
            }

            List<String> dependencies = new ArrayList<>();
            if (quest.has("dependsOn")) {
                for (JsonElement dependency : quest.getAsJsonArray("dependsOn")) {
                    dependencies.add(dependency.getAsString());
                }
            }

            List<TaskEntry> tasks = new ArrayList<>();
            if (quest.has("tasks")) {
                for (JsonElement task : quest.getAsJsonArray("tasks")) {
                    tasks.add(taskEntry(task.getAsJsonObject()));
                }
            }

            List<RewardEntry> rewards = new ArrayList<>();
            if (quest.has("rewards")) {
                for (JsonElement reward : quest.getAsJsonArray("rewards")) {
                    rewards.add(rewardEntry(reward.getAsJsonObject()));
                }
            }

            // The quest's picture and its chapter's, in whichever arm the server sent: an item
            // resolves to a stack, a texture to a path for the blit, an entity to its egg. Absent
            // kind means the item arm, which is every icon a version-17 tree ever sent.
            ResolvedIcon questIcon = resolveIcon(quest, "icon", "iconComponents", "iconKind");
            ResolvedIcon questChapterIcon =
                    resolveIcon(quest, "chapterIcon", "chapterIconComponents", "chapterIconKind");

            parsed.add(new Entry(
                    // Empty for a server that predates groups, which the sidebar reads as "no group"
                    // and answers by drawing the flat chapter list.
                    str(quest, "chapterGroupId"),
                    str(quest, "chapterId"),
                    str(quest, "chapterTitle"),
                    // A chapter asking for a theme of its own, or "" for one that has no opinion.
                    // Read into the entry rather than into a map of chapter to theme, because it
                    // arrives on every quest of the chapter and a second structure keyed by chapter
                    // would be a second thing to keep in step with the first.
                    str(quest, "chapterTheme"),
                    str(quest, "id"),
                    str(quest, "title"),
                    str(quest, "subtitle"),
                    // The chapter's subtitle, since version 20. Absent — every older server, and
                    // every chapter that names none — reads as "", which resolves to nothing.
                    str(quest, "chapterSubtitle"),
                    List.copyOf(description),
                    questIcon.stack(),
                    quest.has("x") ? quest.get("x").getAsInt() : 0,
                    quest.has("y") ? quest.get("y").getAsInt() : 0,
                    // Clamped for the same reason, and by the same argument, as `iconScale` below: the
                    // codec that bounded this ran on the server, over a file that server had. `size` was
                    // the one number of the three that arrived raw, so a cross-version or hand-forged
                    // 4000 reached the canvas -- which is exactly what the codec's own bound exists to
                    // prevent. The bounds are QuestLayout's, the record that owns the field.
                    quest.has("size")
                            ? Math.min(Math.max(quest.get("size").getAsInt(),
                                    QuestLayout.MIN_SIZE), QuestLayout.MAX_SIZE)
                            : QuestLayout.DEFAULT_SIZE,
                    // Resolved with a fallback rather than by valueOf, so a server running a newer
                    // version that names a shape this client has never heard of draws a square
                    // instead of throwing while a player waits for a screen.
                    QuestShape.byName(str(quest, "shape"), QuestShape.ROUNDED),
                    // Clamped here as well as in the codec, and this is not belt-and-braces: the codec
                    // ran on the *server*, over a file that server had. What arrives is a number from
                    // possibly a different version, and the screen must not draw outside its node
                    // because of one. Same reasoning as QuestShape.span clamping its own output.
                    quest.has("iconScale")
                            ? Math.min(Math.max(quest.get("iconScale").getAsDouble(),
                                    QuestShape.MIN_ICON_SCALE), QuestShape.MAX_ICON_SCALE)
                            : QuestLayout.DEFAULT_ICON_SCALE,
                    // Wrapped rather than clamped, so a server that sent 450 degrees gets the shape it
                    // meant rather than one pinned at the top of the range.
                    quest.has("rotation")
                            ? Math.floorMod(quest.get("rotation").getAsInt(), 360)
                            : QuestLayout.DEFAULT_ROTATION,
                    quest.has("showTitle") && quest.get("showTitle").getAsBoolean(),
                    prerequisiteMode,
                    quest.has("chapterDefaultPrerequisiteMode")
                            ? dev.ellipog.tenet.quest.PrerequisiteMode.CODEC
                                    .parse(com.mojang.serialization.JsonOps.INSTANCE,
                                            quest.get("chapterDefaultPrerequisiteMode"))
                                    .result()
                                    .orElse(dev.ellipog.tenet.quest.PrerequisiteMode.ALL_COMPLETED)
                            : dev.ellipog.tenet.quest.PrerequisiteMode.ALL_COMPLETED,
                    quest.has("minRequired") ? Math.max(0, quest.get("minRequired").getAsInt()) : 0,
                    quest.has("maxCompletableDependents")
                            ? Math.max(0, quest.get("maxCompletableDependents").getAsInt()) : 0,
                    str(quest, "exclusiveGroup"),
                    quest.has("hideUntilDependenciesComplete")
                            && quest.get("hideUntilDependenciesComplete").getAsBoolean(),
                    quest.has("hideUntilDependenciesVisible")
                            && quest.get("hideUntilDependenciesVisible").getAsBoolean(),
                    quest.has("hideDependencyLines") && quest.get("hideDependencyLines").getAsBoolean(),
                    quest.has("hideTextUntilComplete") && quest.get("hideTextUntilComplete").getAsBoolean(),
                    quest.has("hideDetailsUntilStartable")
                            && quest.get("hideDetailsUntilStartable").getAsBoolean(),
                    quest.has("invisibleUntilTasks")
                            ? Math.max(0, quest.get("invisibleUntilTasks").getAsInt()) : 0,
                    quest.has("chapterLinear") && quest.get("chapterLinear").getAsBoolean(),
                    // Defaulted to a large number rather than to zero, so a server too old to send it
                    // cannot claim every quest is the first one in its chapter. A chapter that is not
                    // linear never reads this, and that is the only case an old server can produce.
                    quest.has("order") ? quest.get("order").getAsInt() : Integer.MAX_VALUE,
                    List.copyOf(dependencies),
                    List.copyOf(tasks),
                    List.copyOf(rewards),
                    quest.has("invisible") && quest.get("invisible").getAsBoolean(),
                    quest.has("optional") && quest.get("optional").getAsBoolean(),
                    quest.has("flexible") && quest.get("flexible").getAsBoolean(),
                    questIcon.id(),
                    questIcon.texture(),
                    questIcon.sprite(),
                    questChapterIcon.stack(),
                    questChapterIcon.id(),
                    questChapterIcon.texture(),
                    questChapterIcon.sprite(),
                    dependencyLines(quest),
                    DependencyStyle.from(quest.get("chapterDependencyStyle")).resolved(),
                    // Kept as the file wrote it; a server that sends nothing (or something that is not
                    // an object) reads as "no patch", which is the state every chapter was in before
                    // this field existed.
                    quest.has("chapterThemePatch") && quest.get("chapterThemePatch").isJsonObject()
                            ? quest.getAsJsonObject("chapterThemePatch") : null,
                    autoClaim(quest, "autoClaim"),
                    autoClaim(quest, "chapterAutoClaim"),
                    // The text fields' English halves, absent for every literal -- see the record's
                    // own note on why an empty fallback is the fact that says "this is not a key".
                    str(quest, "titleFallback"),
                    str(quest, "subtitleFallback"),
                    str(quest, "chapterTitleFallback"),
                    str(quest, "chapterSubtitleFallback"),
                    List.copyOf(descriptionFallbacks),
                    // Former ids, when the server sent any -- version 16 and a quest that was renamed.
                    // Absent means none, which is what a version-15 tree always says: the key's presence
                    // is the fact, as it is for every other sparse field on this wire.
                    readAliases(quest),
                    // How this quest presents itself, since version 17. The width arrives already
                    // resolved against the chapter's default; zero means the panel kind decides,
                    // which is what a version-16 server always says.
                    quest.has("minWidth") ? Math.max(0, quest.get("minWidth").getAsInt()) : 0,
                    quest.has("hideDependentLines")
                            && quest.get("hideDependentLines").getAsBoolean(),
                    quest.has("disableToast") && quest.get("disableToast").getAsBoolean(),
                    quest.has("hideFromViewers")
                            && quest.get("hideFromViewers").getAsBoolean(),
                    quest.has("hideLockIcon")
                            && quest.get("hideLockIcon").getAsBoolean(),
                    // Words this quest answers to in lookups by tag. Sparse: absent means none,
                    // which is what every older server says. Read like the aliases above, whose
                    // key's presence is likewise the fact.
                    readTags(quest),
                    // The guide book page this quest belongs to, or empty when it names none.
                    // Sparse, like the tags: absent means none.
                    str(quest, "guidePage")));
        }
        entries = List.copyOf(parsed);
        groups = List.copyOf(parsedGroups);
        chapters = List.copyOf(parsedChapters);
        // Whole rather than merged, like every other list here: a chapter that lost its last element has
        // to lose it on this client too, and a tree is a description of the pack rather than a delta.
        chapterElements = Map.copyOf(parsedElements);
        // And the markers with them: a chapter that lost its last link loses it here too, for the same
        // reason and on the same message, so the two are always from one moment.
        chapterLinks = Map.copyOf(parsedLinks);
        tables = List.copyOf(parsedTables);
        refusedTables = List.copyOf(parsedRefused);
    }

    /** The reward tables the server declared, in id order. Never null; empty before a tree arrives. */
    public static List<TableSummary> tables() {
        return tables;
    }

    /**
     * The table files that are there and did not load, in id order, each with its reason.
     *
     * <p>The counterpart of {@link #tables()} rather than a flag on it: a refused file has no title, no
     * icon and no entry count, so it is not a summary and cannot be one. It is what the Assets panel lists
     * under the loaded tables, because a table an author cannot load is the one they most need told about
     * — and until this existed, the only place it was said was the server's log.
     */
    public static List<RefusedTable> refusedTables() {
        return refusedTables;
    }

    /** One refused table: its id, and why the load would not take it. */
    public record RefusedTable(String id, String why) {
    }

    /** One table's summary, or null when this build has not been told about it. */
    public static TableSummary table(String id) {
        if (id == null) {
            return null;
        }
        for (TableSummary summary : tables) {
            if (summary.id().equals(id)) {
                return summary;
            }
        }
        return null;
    }

    /**
     * An auto-claim mode by wire name, or null for absent or unknown.
     *
     * <p>Null rather than a default, because "the quest said nothing" and "the quest said default" are
     * different states: the first falls through to the chapter's mode, the second is the quest's own
     * answer. The same lenient read the shapes get — a name from a newer server is ignored, not fatal.
     */
    private static dev.ellipog.tenet.quest.reward.RewardAutoClaim autoClaim(JsonObject quest, String key) {
        String name = str(quest, key);
        if (name.isEmpty()) {
            return null;
        }
        for (dev.ellipog.tenet.quest.reward.RewardAutoClaim mode
                : dev.ellipog.tenet.quest.reward.RewardAutoClaim.values()) {
            if (mode.name().equalsIgnoreCase(name)) {
                return mode;
            }
        }
        return null;
    }

    /** A quest's per-line overrides, keyed by dependency id. Absent or malformed reads as none. */
    private static java.util.Map<String, DependencyStyle> dependencyLines(JsonObject quest) {
        JsonElement element = quest.get("dependencyLines");
        if (element == null || !element.isJsonObject()) {
            return java.util.Map.of();
        }
        java.util.Map<String, DependencyStyle> lines = new java.util.LinkedHashMap<>();
        for (java.util.Map.Entry<String, JsonElement> entry : element.getAsJsonObject().entrySet()) {
            lines.put(entry.getKey(), DependencyStyle.from(entry.getValue()));
        }
        // `Collections.unmodifiableMap`, NOT `Map.copyOf`, and the difference reaches the author's file.
        // `Map.copyOf` returns an immutable map in *hash* order, so this map's iteration order stopped
        // matching the JSON it was parsed from -- and the fallback branch of `QuestBookScreen`'s
        // `dependencyLinesOf` rebuilds the whole `dependencyLines` object by iterating it and sends that
        // object as one field write. A line-style edit could therefore permute the on-disk key order of
        // every other override in the quest, which is a Git diff nobody made. The permutation was a pure
        // function of the key set, so it was not even random -- it was just not the file's order, and
        // adding one override reshuffled the rest.
        //
        // The read-only guarantee is the same; only the order differs. See `Quest.dependencyLines` for
        // why the order is part of the format rather than an accident: a diff on a file an author
        // hand-wrote should show the line they changed.
        return java.util.Collections.unmodifiableMap(lines);
    }

    private static TaskEntry taskEntry(JsonObject json) {
        return new TaskEntry(
                stack(str(json, "icon"), 1),
                stack(str(json, "item"), json.has("count") ? json.get("count").getAsInt() : 1,
                        json.get("itemComponents")),
                json.has("count") ? json.get("count").getAsInt() : 1,
                json.has("optional") && json.get("optional").getAsBoolean(),
                json.has("manual") && json.get("manual").getAsBoolean(),
                json.has("waits") && json.get("waits").getAsBoolean(),
                json.has("manualOnly") && json.get("manualOnly").getAsBoolean(),
                str(json, "type"),
                str(json, "label"),
                str(json, "labelFallback"),
                str(json, "labelArg"),
                str(json, "item"),
                str(json, "observeType"),
                str(json, "observeTarget"),
                json.has("observeTicks") ? json.get("observeTicks").getAsInt() : 0,
                str(json, "tag"),
                conditionEntries(json),
                json.has("disableToast") && json.get("disableToast").getAsBoolean(),
                str(json, "textureIcon"),
                str(json, "spriteIcon"),
                stack(str(json, "picture"), 1, json.get("pictureComponents")),
                str(json, "filter"));
    }

    private static RewardEntry rewardEntry(JsonObject json) {
        return new RewardEntry(
                stack(str(json, "icon"), 1),
                stack(str(json, "item"), json.has("count") ? json.get("count").getAsInt() : 1,
                        json.get("itemComponents")),
                json.has("count") ? json.get("count").getAsInt() : 1,
                str(json, "type"),
                str(json, "label"),
                str(json, "labelFallback"),
                str(json, "labelArg"),
                str(json, "item"),
                str(json, "auto"),
                json.has("team") && json.get("team").getAsBoolean(),
                json.has("excludeFromClaimAll") && json.get("excludeFromClaimAll").getAsBoolean(),
                conditionEntries(json),
                json.has("disableToast") && json.get("disableToast").getAsBoolean(),
                str(json, "textureIcon"),
                str(json, "spriteIcon"),
                stack(str(json, "picture"), 1, json.get("pictureComponents")));
    }

    /**
     * The gates a task or a reward carries, resolved.
     *
     * <p>Empty for an entry with none — every entry of a pack without conditions — and for every entry
     * of a server that predates the field, which are the same answer: nothing to explain on hover.
     */
    private static List<ConditionEntry> conditionEntries(JsonObject json) {
        if (!json.has("conditions") || !json.get("conditions").isJsonArray()) {
            return List.of();
        }
        List<ConditionEntry> out = new ArrayList<>();
        for (JsonElement value : json.getAsJsonArray("conditions")) {
            if (!value.isJsonObject()) {
                // A placeholder rather than a skip: the server's unmet mask indexes this list, so
                // dropping an element would shift every later index and name the wrong gate. Only a
                // malformed or foreign server can produce one.
                out.add(new ConditionEntry(ItemStack.EMPTY, 1, "?", "", ""));
                continue;
            }
            JsonObject condition = value.getAsJsonObject();
            String itemId = condition.has("item") && condition.get("item").isJsonPrimitive()
                    && condition.get("item").getAsJsonPrimitive().isString()
                    ? condition.get("item").getAsString() : "";
            int count = condition.has("count") && condition.get("count").isJsonPrimitive()
                    && condition.get("count").getAsJsonPrimitive().isNumber()
                    ? condition.get("count").getAsInt() : 1;
            out.add(new ConditionEntry(
                    itemId.isEmpty()
                            ? ItemStack.EMPTY
                            : stack(itemId, count, condition.get("itemComponents")),
                    count,
                    str(condition, "label"),
                    str(condition, "labelFallback"),
                    str(condition, "labelArg")));
        }
        return List.copyOf(out);
    }

    /**
     * Reads progress into the cache.
     *
     * <h2>{@code removed} is why a delta cannot be inferred from absence</h2>
     *
     * <p>A delta carries only what changed, so a quest missing from it means "unchanged" — which is
     * also what a quest deleted from a server file would look like. The two are indistinguishable
     * from the message alone, so the server names deletions explicitly and the client applies them
     * first. Without that, a quest removed from a file would live on in this cache until the player
     * reconnected: a ghost node on the canvas that cannot be clicked and cannot be explained.
     *
     * @param full whether to start from nothing or from what is already held
     */
    private static void parseProgress(String json, boolean full) {
        JsonObject root = JsonParser.parseString(json).getAsJsonObject();
        Map<String, Progress> next = full ? new LinkedHashMap<>() : new LinkedHashMap<>(progress);

        // What this message named: the ids it carried, and the ids it took away. A removal counts,
        // because to a reader comparing two samples "gone" is a change like any other -- and absence
        // cannot say it, since absence is also what unchanged looks like.
        Set<String> touched = new java.util.LinkedHashSet<>();

        if (root.has("removed")) {
            for (JsonElement gone : root.getAsJsonArray("removed")) {
                String id = gone.getAsString();
                next.remove(id);
                touched.add(id);
            }
        }

        if (root.has("quests")) {
            for (Map.Entry<String, JsonElement> entry : root.getAsJsonObject("quests").entrySet()) {
                JsonObject one = entry.getValue().getAsJsonObject();
                touched.add(entry.getKey());

                List<Integer> tasks = new ArrayList<>();
                if (one.has("tasks")) {
                    for (JsonElement value : one.getAsJsonArray("tasks")) {
                        tasks.add(value.getAsInt());
                    }
                }

                // Who is holding what, by task position. Absent for a task nobody is carrying anything
                // toward, and for every task of a server that predates the field -- so its absence is
                // simply "no faces to draw", which is the direction that cannot show a wrong name.
                Map<Integer, Map<UUID, Integer>> contributors = new LinkedHashMap<>();
                if (one.has("who")) {
                    for (Map.Entry<String, JsonElement> task : one.getAsJsonObject("who").entrySet()) {
                        int index = taskIndex(task.getKey());
                        if (index < 0) {
                            continue;
                        }
                        Map<UUID, Integer> picture = new LinkedHashMap<>();
                        for (Map.Entry<String, JsonElement> held : task.getValue().getAsJsonObject().entrySet()) {
                            UUID who = memberId(held.getKey());
                            if (who != null) {
                                picture.put(who, held.getValue().getAsInt());
                            }
                        }
                        if (!picture.isEmpty()) {
                            contributors.put(index, Map.copyOf(picture));
                        }
                    }
                }

                // Who collected what. Sparse in both directions, like the contributor pictures: a
                // quest nobody has claimed anything on sends neither key, which reads as "nobody has"
                // -- the direction that cannot invent a claim.
                Set<Integer> teamClaims = new java.util.LinkedHashSet<>();
                if (one.has("teamClaims")) {
                    for (JsonElement value : one.getAsJsonArray("teamClaims")) {
                        teamClaims.add(value.getAsInt());
                    }
                }
                Map<UUID, Set<Integer>> claimedBy = new LinkedHashMap<>();
                if (one.has("claims")) {
                    for (Map.Entry<String, JsonElement> who : one.getAsJsonObject("claims").entrySet()) {
                        UUID player = memberId(who.getKey());
                        if (player == null || !who.getValue().isJsonArray()) {
                            continue;
                        }
                        Set<Integer> indices = new java.util.LinkedHashSet<>();
                        for (JsonElement value : who.getValue().getAsJsonArray()) {
                            indices.add(value.getAsInt());
                        }
                        claimedBy.put(player, Set.copyOf(indices));
                    }
                }

                // The tasks whose press would be accepted right now, by position. Sparse in the same
                // direction as `taskLocks`: absent means none of them, which reads as no button.
                Set<Integer> ready = new java.util.LinkedHashSet<>();
                if (one.has("taskReady")) {
                    for (JsonElement value : one.getAsJsonArray("taskReady")) {
                        ready.add(value.getAsInt());
                    }
                }

                next.put(entry.getKey(), new Progress(
                        readState(str(one, "state")),
                        one.has("cooldown") ? one.get("cooldown").getAsLong() : 0L,
                        List.copyOf(tasks),
                        one.has("rewardsBlocked") && one.get("rewardsBlocked").getAsBoolean(),
                        Map.copyOf(contributors),
                        Set.copyOf(teamClaims),
                        Map.copyOf(claimedBy),
                        lockMap(one, "taskLocks"),
                        lockMap(one, "rewardLocks"),
                        one.has("settled") && one.get("settled").getAsBoolean(),
                        Set.copyOf(ready),
                        one.has("timesCompleted") ? Math.max(0, one.get("timesCompleted").getAsInt()) : 0,
                        one.has("excluded") && one.get("excluded").getAsBoolean()));
            }
        }
        progress = Map.copyOf(next);

        // And how far each chapter has got. Replaced rather than merged, and rejected as a whole if it
        // is not an object: a half-read chapter map is a chapter drawn shut on no evidence, and the
        // empty map is the honest reading of a message that did not carry one -- every chapter open,
        // which is what a version-11 server means.
        Map<String, QuestState> states = new LinkedHashMap<>();
        if (root.has("chapters") && root.get("chapters").isJsonObject()) {
            for (Map.Entry<String, JsonElement> entry : root.getAsJsonObject("chapters").entrySet()) {
                states.put(entry.getKey(), readChapterState(entry.getValue().getAsString()));
            }
        }
        chapterStates = Map.copyOf(states);

        lastTouch = new ProgressTouch(full, touched);
        progressRevision++;
    }

    /**
     * What the last progress message named: whether it was the whole of the server's progress, and the
     * quest ids it carried or removed.
     *
     * <h2>Why this is published rather than recomputed</h2>
     *
     * <p>Because the message is the only thing that knows what changed, and it is thrown away one line
     * after it arrives: a delta says "these quests are now thus", and the code that reads it walks
     * exactly those keys and then drops them. A reader that wants to know what moved has no way to ask
     * afterwards — it would have to compare the whole cache against a remembered copy, which is the
     * expensive thing this exists to let that reader stop doing.
     *
     * <p>{@code full} is carried rather than inferred, and it is the half that matters most to a reader
     * comparing two samples: a <b>delta's</b> ids are the only quests that moved, while a <b>full</b>
     * message says nothing about what moved — it is the whole of the server's answer, and a reader
     * holding a baseline has to treat every quest in it as possibly new. A reader that treated a full
     * as a delta would keep every unchanged quest's stale baseline and miss the next change to it.
     */
    public record ProgressTouch(boolean full, Set<String> ids) {

        public ProgressTouch {
            ids = Set.copyOf(ids);
        }

        /** Nothing named, as the whole of it: what a cache that has just been emptied holds. */
        public static final ProgressTouch nothing = new ProgressTouch(true, Set.of());
    }

    private static volatile ProgressTouch lastTouch = ProgressTouch.nothing;

    /** What the last progress message named. See {@link ProgressTouch} for why it is kept. */
    public static ProgressTouch lastProgressTouch() {
        return lastTouch;
    }

    /** A task position from the wire, or -1 for one that is not a position. */
    private static int taskIndex(String raw) {
        try {
            return Integer.parseInt(raw);
        }
        catch (NumberFormatException e) {
            return -1;
        }
    }

    /**
     * The row locks from the wire: row index -> the condition indices that failed.
     *
     * <p>Absent is unlocked, the opposite default from {@code claimable} and deliberately so: a server
     * that predates conditions sends no key at all, and a client that defaulted to locked would draw
     * every row of every older server's pack as gated.
     */
    private static Map<Integer, List<Integer>> lockMap(JsonObject one, String key) {
        if (!one.has(key) || !one.get(key).isJsonObject()) {
            return Map.of();
        }
        Map<Integer, List<Integer>> out = new LinkedHashMap<>();
        for (Map.Entry<String, JsonElement> row : one.getAsJsonObject(key).entrySet()) {
            int index = taskIndex(row.getKey());
            if (index < 0 || !row.getValue().isJsonArray()) {
                continue;
            }
            List<Integer> unmet = new ArrayList<>();
            for (JsonElement value : row.getValue().getAsJsonArray()) {
                // Guarded, like every other read here: a non-number would throw, and this parser's
                // caller clears the whole progress map on a throw -- one bad byte from a foreign server
                // would cost the client every quest's state.
                if (value.isJsonPrimitive() && value.getAsJsonPrimitive().isNumber()) {
                    unmet.add(value.getAsInt());
                }
            }
            out.put(index, List.copyOf(unmet));
        }
        return Map.copyOf(out);
    }

    /** A member's id from the wire, or null for one this client cannot read. */
    private static UUID memberId(String raw) {
        try {
            return UUID.fromString(raw);
        }
        catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static QuestState readState(String raw) {
        try {
            return QuestState.valueOf(raw);
        }
        catch (IllegalArgumentException e) {
            // A state this client does not know means the client and server are different versions.
            // LOCKED is the safe reading and the honest one: the client cannot show progress it does
            // not understand, and showing it as complete would be a lie in the more dangerous
            // direction.
            return QuestState.LOCKED;
        }
    }

    /**
     * A chapter's state, where an unknown name reads the <b>other</b> way.
     *
     * <p>Not {@link #readState} with a different caller, because the two questions have opposite safe
     * answers. A quest is content: drawing one as open when the server has shut it shows a player a
     * quest they cannot do, so unknown reads LOCKED. A chapter is a <b>door</b>: reading one as shut
     * hides a whole chapter's worth of content — and, for a chapter the author asked to hide before its
     * gate is met, hides it permanently — while reading it as open costs one dimmed row that the
     * server's own refusal corrects. Same class of fact, opposite honest answer.
     */
    private static QuestState readChapterState(String raw) {
        try {
            return QuestState.valueOf(raw);
        }
        catch (IllegalArgumentException e) {
            return QuestState.UNLOCKED;
        }
    }

    /**
     * Resolves an item id to a stack, for drawing.
     *
     * <p>An empty stack for anything unknown, which renders as nothing rather than as a crash. A
     * client missing a mod the server has is a normal situation, not an error — and the alternative,
     * throwing inside a payload handler, disconnects the player over a missing icon.
     */
    private static ItemStack stack(String id, int count) {
        return stack(id, count, null);
    }

    /**
     * The stack, with the server's component patch applied when one travelled.
     *
     * <p>An id with no item behind it resolves to {@link ItemStack#EMPTY} -- a missing mod -- and the
     * callers keep the id they were given beside the stack, which is how a row can say the item is
     * missing instead of drawing nothing.
     */
    private static ItemStack stack(String id, int count, com.google.gson.JsonElement components) {
        if (id == null || id.isEmpty()) {
            return ItemStack.EMPTY;
        }
        ResourceLocation location = ResourceLocation.tryParse(id);
        if (location == null) {
            return ItemStack.EMPTY;
        }
        Item item = BuiltInRegistries.ITEM.get(location);
        if (item == Items.AIR) {
            return ItemStack.EMPTY;
        }
        ItemStack stack = new ItemStack(item, Math.max(1, count));
        if (components != null && components.isJsonObject()) {
            net.minecraft.core.component.DataComponentPatch.CODEC
                    .parse(com.mojang.serialization.JsonOps.INSTANCE, components)
                    .result().ifPresent(stack::applyComponents);
        }
        return stack;
    }

    /**
     * An item id and its optional component patch, resolved for drawing.
     *
     * <p>The one public door into the resolver above, for a caller that holds an id from a tree the
     * cache did not parse into an entry -- the chapter's icon, which the Chapter tab draws from the
     * replica's own JSON. Empty for anything unknown, the same as every other resolution here, so a
     * caller that needs to tell "absent" from "missing" keeps the id beside it.
     */
    public static ItemStack iconOf(String id, JsonElement components) {
        return stack(id, 1, components);
    }

    /**
     * One icon as the tree sent it: the id, the kind beside it, and the components the item arm
     * carries. Resolved into the stack to draw, the id to keep, and the texture path when the arm
     * is a texture.
     *
     * @param idField         the wire id: an item id, a texture path, or an entity id
     * @param componentsField the item arm's component patch, absent on the other arms
     * @param kindField       the arm: absent means the item arm, which is every icon a version-17
     *                        tree ever sent
     */
    private record ResolvedIcon(ItemStack stack, String id, String texture, String sprite) {
    }

    private static ResolvedIcon resolveIcon(JsonObject json, String idField, String componentsField,
                                            String kindField) {
        String id = str(json, idField);
        String kind = json.has(kindField) && !json.get(kindField).isJsonNull()
                ? json.get(kindField).getAsString() : "";
        if ("texture".equals(kind)) {
            // A picture, not a stack: the id stays out of the missing-item reading and the path
            // travels on its own, for the blit at the draw site.
            return new ResolvedIcon(ItemStack.EMPTY, "", id, "");
        }
        if ("sprite".equals(kind)) {
            // An atlas region, not a stack and not a file: the id stays out of the missing-item
            // reading and travels on its own, for the atlas draw at the draw site.
            return new ResolvedIcon(ItemStack.EMPTY, "", "", id);
        }
        if ("entity".equals(kind)) {
            return new ResolvedIcon(eggStack(id), id, "", "");
        }
        return new ResolvedIcon(stack(id, 1, json.get(componentsField)), id, "", "");
    }

    /**
     * An entity's spawn egg, or empty.
     *
     * <p>The {@code <path>_spawn_egg} convention every vanilla mob follows: an entity arm draws as
     * the stack every icon surface already knows how to draw, with no entity render anywhere. An
     * entity with no egg — a missing mod, or a mod that names its eggs unconventionally — resolves
     * to empty with the entity's id kept beside it, which is what draws the missing mark naming it.
     */
    private static ItemStack eggStack(String entityId) {
        if (entityId == null || entityId.isEmpty()) {
            return ItemStack.EMPTY;
        }
        ResourceLocation entity = ResourceLocation.tryParse(entityId);
        if (entity == null) {
            return ItemStack.EMPTY;
        }
        ResourceLocation egg = ResourceLocation.fromNamespaceAndPath(entity.getNamespace(),
                entity.getPath() + "_spawn_egg");
        if (!BuiltInRegistries.ITEM.containsKey(egg)) {
            return ItemStack.EMPTY;
        }
        return stack(egg.toString(), 1, null);
    }

    private static String str(JsonObject object, String key) {
        return object.has(key) && !object.get(key).isJsonNull() ? object.get(key).getAsString() : "";
    }

    /**
     * A quest's former ids, as the tree sent them, or empty.
     *
     * <p>Strings only: an entry that is not one is skipped rather than throwing, because a tree from
     * a newer server may carry shapes this client has never heard of — and the whole of this parse
     * is lenient for exactly that reason.
     */
    private static List<String> readAliases(JsonObject quest) {
        if (!quest.has("aliases") || !quest.get("aliases").isJsonArray()) {
            return List.of();
        }
        List<String> aliases = new ArrayList<>();
        for (JsonElement each : quest.getAsJsonArray("aliases")) {
            if (each.isJsonPrimitive() && each.getAsJsonPrimitive().isString()) {
                aliases.add(each.getAsString());
            }
        }
        return List.copyOf(aliases);
    }

    /**
     * Words a quest answers to in lookups by tag, as the tree sent them, or empty.
     *
     * <p>Read like the aliases above, and lenient for the same reason: strings only, and an
     * absent or misshapen list is no tags rather than a failed tree.
     */
    private static List<String> readTags(JsonObject quest) {
        if (!quest.has("tags") || !quest.get("tags").isJsonArray()) {
            return List.of();
        }
        List<String> tags = new ArrayList<>();
        for (JsonElement each : quest.getAsJsonArray("tags")) {
            if (each.isJsonPrimitive() && each.getAsJsonPrimitive().isString()) {
                tags.add(each.getAsString());
            }
        }
        return List.copyOf(tags);
    }
}
