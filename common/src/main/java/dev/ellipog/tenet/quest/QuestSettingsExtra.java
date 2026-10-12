package dev.ellipog.tenet.quest;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import java.util.List;
import java.util.Set;

/**
 * The quest tree's own display and file behaviours, grouped.
 *
 * <p>Grouped for the mundane reason {@link ChapterRules} gives — {@code RecordCodecBuilder} stops at
 * sixteen components and {@link QuestSettings} is at ten without these — and the real one: these
 * are the answers about how the book <i>behaves</i> as a file (lock marks, pausing, the grid, the
 * emergency shelf) rather than how its rewards and text are resolved. In JSON the fields are
 * <b>flat</b> on the {@code settings} block, because this is a {@link MapCodec}, exactly as a
 * quest's rules are flat on the quest. An author writes {@code "pauseGame": true}, not a nested
 * object.
 */
public record QuestSettingsExtra(boolean showLockIcons, boolean hideExcludedQuests, boolean pauseGame,
                                 boolean disableGui, boolean dropBookOnDeath, boolean giveBookOnFirstJoin,
                                 double gridScale,
                                 String lockMessage, int emergencyItemsCooldown,
                                 List<ItemRef> emergencyItems, boolean disableCanvasLod) {

    /**
     * The bounds of {@code gridScale}, taken from the legacy format's own editor (1/32 to 8).
     *
     * <p>Named once so the codec, the validator and the schema can agree: three spellings of one
     * range is how an out-of-range value gets accepted by one and refused by another.
     */
    public static final double GRID_SCALE_MIN = 1.0 / 32.0;
    public static final double GRID_SCALE_MAX = 8.0;

    /** A file that says nothing about how it behaves: hidden locks, no hiding, no pausing. */
    public static final QuestSettingsExtra DEFAULT = new QuestSettingsExtra(false, false, false, false,
            false, false, 0.5, "", 300, List.of(), false);

    /** The field names this contributes, for the validator to allow in the settings block. */
    public static final Set<String> FIELDS = Set.of("showLockIcons", "hideExcludedQuests", "pauseGame",
            "disableGui", "dropBookOnDeath", "giveBookOnFirstJoin", "gridScale", "lockMessage",
            "emergencyItemsCooldown", "emergencyItems", "disableCanvasLod");

    public static final MapCodec<QuestSettingsExtra> MAP_CODEC = RecordCodecBuilder.mapCodec(
            instance -> instance.group(
                    // Whether a locked quest wears its lock mark on the canvas. The legacy
                    // `show_lock_icons` draws when absent — so the migration tool writes an explicit
                    // `true` for a pack that never heard of the field, and Tenet's own absent is
                    // hidden: no mark unless a pack asks for one. A quest hides its own with
                    // `hideLockIcon`; either silence wins. Read by the client, so it travels
                    // on the tree (sparse: only `true` crosses, because that is the unusual answer).
                    Codec.BOOL.optionalFieldOf("showLockIcons", false)
                            .forGetter(QuestSettingsExtra::showLockIcons),
                    // Whether quests shut out by an exclusive choice vanish from the reader's book. The
                    // legacy `hide_excluded_quests`: a quest another questline's completion excluded is
                    // not shown. Tenet's exclusion is an exclusive group (or a dependent cap) resolving
                    // to LOCKED, and the server marks those quests on the wire — the client cannot tell
                    // "excluded" from "not yet" by the state alone. False draws them locked, as before.
                    Codec.BOOL.optionalFieldOf("hideExcludedQuests", false)
                            .forGetter(QuestSettingsExtra::hideExcludedQuests),
                    // Whether the book pauses the world in single player. the legacy `pause_game`:
                    // `isPauseScreen` answers this rather than a constant, so an author reading mid-fight
                    // keeps the default and a lore book may still the world. Read by the client, so it
                    // travels on the tree (sparse: only `true` crosses).
                    Codec.BOOL.optionalFieldOf("pauseGame", false)
                            .forGetter(QuestSettingsExtra::pauseGame),
                    // Whether the book refuses to open. the legacy `disable_gui`, whose own semantics
                    // are unclear; Tenet reads it as a pack-level "this book is not for players": every
                    // open path answers with the same sentence instead of a screen. False opens, as before.
                    Codec.BOOL.optionalFieldOf("disableGui", false)
                            .forGetter(QuestSettingsExtra::disableGui),
                    // Whether dying drops the quest book at the player's feet. The legacy
                    // `drop_book_on_death`: the book item is spawned where the player fell, so it can be
                    // picked back up. False leaves death alone, as before.
                    Codec.BOOL.optionalFieldOf("dropBookOnDeath", false)
                            .forGetter(QuestSettingsExtra::dropBookOnDeath),
                    // Whether a player joining for the first time finds a quest book in their
                    // inventory. No legacy equivalent: authors used to tell players to take it from
                    // the creative tab or a command. False changes nothing, as before.
                    Codec.BOOL.optionalFieldOf("giveBookOnFirstJoin", false)
                            .forGetter(QuestSettingsExtra::giveBookOnFirstJoin),
                    // The editor canvas's grid step, in content units per cell edge. The legacy
                    // `grid_scale`, whose editor snaps to `1 / gridScale`. File-only for now: the
                    // value is validated and stored, and the editor keeps its 8-unit step (see
                    // `BookGeometry.SNAP_GRID`) until a later pass wires it — the snap is one
                    // constant read at every drag site, not one knob, so wiring it is that pass's
                    // work. It never moves a quest either way: a step says where a dragged node
                    // lands, not where one is.
                    Codec.doubleRange(GRID_SCALE_MIN, GRID_SCALE_MAX).optionalFieldOf("gridScale", 0.5)
                            .forGetter(QuestSettingsExtra::gridScale),
                    // What a locked quest is called when the pack has a better word than "Locked". The
                    // legacy `lock_message`: the author's own sentence, drawn wherever the state word
                    // would be. Empty means the client's own word, which is what every pack that predates
                    // this field gets. Read by the client, so it travels on the tree when set.
                    Codec.STRING.optionalFieldOf("lockMessage", "")
                            .forGetter(QuestSettingsExtra::lockMessage),
                    // How long a player waits between emergency grants, in seconds. The legacy
                    // `emergency_items_cooldown` (the legacy format documents no unit; Tenet reads seconds, so the
                    // file's 300 is five minutes). Zero means every press is granted, which is what a
                    // pack with no cooldown in mind gets.
                    Codec.intRange(0, 86400).optionalFieldOf("emergencyItemsCooldown", 300)
                            .forGetter(QuestSettingsExtra::emergencyItemsCooldown),
                    // What `/tenet emergency` hands out. the legacy `emergency_items`: item references
                    // with counts and components, exactly as a task writes them. Empty means the command
                    // answers that there is nothing to grant, which is what every pack that predates this
                    // field gets.
                    ItemRef.CODEC.listOf().optionalFieldOf("emergencyItems", List.of())
                            .forGetter(QuestSettingsExtra::emergencyItems),
                    // Whether the canvas draws every chapter without its zoom tiers: icons, rings,
                    // titles and badges at any zoom. The legacy format has no tiers — it draws everything
                    // always — so the migration tool writes an explicit `true`, and Tenet's own
                    // absent is tiered: LOD unless a pack asks out of it. A chapter opts out for
                    // itself with its own flag; either silence defers. Read by the client, so it
                    // travels on the tree (sparse: only `true` crosses, because that is the
                    // unusual answer).
                    Codec.BOOL.optionalFieldOf("disableCanvasLod", false)
                            .forGetter(QuestSettingsExtra::disableCanvasLod)
            ).apply(instance, QuestSettingsExtra::new));

    public static final Codec<QuestSettingsExtra> CODEC = MAP_CODEC.codec();
}
