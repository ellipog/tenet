package dev.ellipog.tenet.quest;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.serialization.Codec;
import com.mojang.serialization.JsonOps;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import dev.ellipog.tenet.Constants;
import dev.ellipog.tenet.quest.reward.RewardAutoClaim;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * The quest tree's own settings, from the {@code settings} block of {@code index.json}.
 *
 * <h2>What lives here, and why it is not a chapter's business</h2>
 *
 * <p>These are the answers that belong to the whole book rather than to any chapter: how rewards
 * default to being handed over, whether they go to the team, whether all automatic claiming is
 * suppressed, how long after joining the first task check waits — and what the book calls itself and
 * the item it wears in its header. The legacy format keeps the same set on its quest file, and pack authors
 * expect to find them in one place.
 *
 * <p>The keys are camelCase like every other Tenet field, not the legacy snake_case: Tenet's files are
 * Tenet's, and a format that mixes two spellings is a format nobody can remember.
 *
 * <p>Loading is deliberately lenient: a missing file, a missing block, or a value this build does not
 * know leaves the defaults in place and logs. The tree still has to load — an author with a typo in a
 * settings block should get a working book and a line in the log, not a server that refuses to start.
 * The book's icon is an item id that is resolved on the client, so an id this build cannot resolve is
 * drawn as the header's own "missing item" mark rather than refused here.
 */
public record QuestSettings(RewardAutoClaim defaultAutoClaim, boolean defaultTeamReward,
                            boolean suppressAllAutoclaiming, int detectionDelay,
                            String bookTitle, Optional<Icon> bookIcon, String fallbackLocale,
                            int clickCommandLevel, boolean defaultConsumeItems,
                            boolean defaultDisableRecipeMod,
                            /**
                             * How the book behaves as a file: lock marks, pausing, the grid, the
                             * emergency shelf.
                             *
                             * <p>Grouped for the mundane reason that record gives — the codec's
                             * sixteen components — and the real one: these nine are about how the
                             * book <i>behaves</i> rather than how its rewards and text resolve, which
                             * is also why their readers go through the delegates below rather than
                             * this accessor. The JSON stays flat regardless: {@code extra} is a
                             * {@code MapCodec}, so its fields sit beside these on the
                             * {@code settings} block, as if they were fields of their own.
                             */
                            QuestSettingsExtra extra) {

    /**
     * What a tree that says nothing gets.
     *
     * <p>Automatic claiming is <b>off</b>, as in the legacy format: a finished quest announcing a payout and
     * handing it over in the same breath is a choice an author makes, not one the mod makes for them.
     * An empty title and no icon mean "draw the client's own title and no icon", which is what every
     * pack that predates these fields gets.
     *
     * <p>{@code en_us} as the fallback locale, because that is the language the tree's own strings
     * are written in unless an author says otherwise: a quest file's {@code title} is the text a
     * player reads when nothing translates it, so the canonical locale is whatever language those
     * strings are in. See {@link QuestLanguages}.
     */
    public static final QuestSettings DEFAULTS =
            new QuestSettings(RewardAutoClaim.DISABLED, false, false, 20, "", Optional.empty(),
                    "en_us", 0, false, false, QuestSettingsExtra.DEFAULT);

    /** The field names, for the validator and the schema. */
    public static final Set<String> FIELDS = java.util.stream.Stream
            .concat(java.util.stream.Stream.of("defaultAutoClaim", "defaultTeamReward",
                    "suppressAllAutoclaiming", "detectionDelay", "bookTitle", "bookIcon",
                    "fallbackLocale", "clickCommandLevel", "defaultConsumeItems",
                    "defaultDisableRecipeMod"),
                    QuestSettingsExtra.FIELDS.stream())
            .collect(java.util.stream.Collectors.toUnmodifiableSet());

    /**
     * The book's icon: an item id as a bare string (every {@code index.json} written before the
     * icon union), an icon object, or an empty string for none.
     *
     * <p>Declared before {@link #MAP_CODEC}, because that codec reads it during this class's own
     * initialisation and a reference the other way is a forward one the compiler refuses.
     */
    private static final Codec<Optional<Icon>> BOOK_ICON_CODEC = new Codec<>() {
        @Override
        public <T> com.mojang.serialization.DataResult<T> encode(Optional<Icon> icon,
                                                                  com.mojang.serialization.DynamicOps<T> ops,
                                                                  T prefix) {
            if (icon.isEmpty()) {
                return com.mojang.serialization.DataResult.success(ops.createString(""));
            }
            return Icon.CODEC.encode(icon.get(), ops, prefix);
        }

        @Override
        public <T> com.mojang.serialization.DataResult<com.mojang.datafixers.util.Pair<Optional<Icon>, T>>
        decode(com.mojang.serialization.DynamicOps<T> ops, T input) {
            if (ops.getStringValue(input).result().isPresent()) {
                String raw = ops.getStringValue(input).result().orElse("");
                if (raw.isEmpty()) {
                    return com.mojang.serialization.DataResult.success(
                            com.mojang.datafixers.util.Pair.of(Optional.empty(), input));
                }
                net.minecraft.resources.ResourceLocation id =
                        net.minecraft.resources.ResourceLocation.tryParse(raw);
                if (id == null) {
                    return com.mojang.serialization.DataResult.error(
                            () -> "\"" + raw + "\" is not a namespaced id");
                }
                return com.mojang.serialization.DataResult.success(
                        com.mojang.datafixers.util.Pair.of(
                                Optional.of((Icon) new Icon.Item(new ItemRef(id, 1))), input));
            }
            return Icon.CODEC.decode(ops, input)
                    .map(pair -> pair.mapFirst(icon -> Optional.of(icon)));
        }
    };

    public static final MapCodec<QuestSettings> MAP_CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
            RewardAutoClaim.CODEC.optionalFieldOf("defaultAutoClaim", RewardAutoClaim.DISABLED)
                    .forGetter(QuestSettings::defaultAutoClaim),
            Codec.BOOL.optionalFieldOf("defaultTeamReward", false).forGetter(QuestSettings::defaultTeamReward),
            Codec.BOOL.optionalFieldOf("suppressAllAutoclaiming", false)
                    .forGetter(QuestSettings::suppressAllAutoclaiming),
            Codec.intRange(0, 72000).optionalFieldOf("detectionDelay", 20).forGetter(QuestSettings::detectionDelay),
            Codec.STRING.optionalFieldOf("bookTitle", "").forGetter(QuestSettings::bookTitle),
            // An item id as a bare string (every file written before the union), an icon object, or
            // an empty string for none. Written back as the object the union encodes — a legacy string
            // normalises on the next settings write, which the loader reads the same way.
            BOOK_ICON_CODEC.optionalFieldOf("bookIcon", Optional.empty())
                    .forGetter(QuestSettings::bookIcon),
            // Read through the same normalisation the loader uses, so an author who writes
            // "en-US" gets the locale the files are keyed by rather than one that never matches.
            Codec.STRING.optionalFieldOf("fallbackLocale", "en_us").forGetter(QuestSettings::fallbackLocale),
            // What a canvas click's command runs as: the player's own level, or elevated. Never above
            // 2, and never the presser's own level however high that is -- a click that escalated its
            // presser would be a privilege boundary drawn in the wrong place. File-only, like the
            // detection delay: it is read by the server that runs the command, and no client draws it.
            Codec.intRange(0, 2).optionalFieldOf("clickCommandLevel", 0)
                    .forGetter(QuestSettings::clickCommandLevel),
            // Whether item tasks take what they ask for when neither the task nor its chapter says.
            // the legacy file-level `default_consume_items`: the bottom rung of the consume ladder
            // (task wins over chapter, chapter wins over this, this wins over false). False by
            // default, because silently taking a player's items is the more surprising behaviour.
            Codec.BOOL.optionalFieldOf("defaultConsumeItems", false)
                    .forGetter(QuestSettings::defaultConsumeItems),
            // Whether quests stay out of recipe viewers when they say nothing themselves.
            // the legacy file-level `default_quest_disable_jei`: the fallback a quest's own
            // tristate defers to (see `Quest#showInRecipeMod`). False by default, because a file
            // that hides every quest from every viewer is the surprising behaviour — ATM10 leaves
            // it off and hides a single quest by name instead.
            Codec.BOOL.optionalFieldOf("defaultDisableRecipeMod", false)
                    .forGetter(QuestSettings::defaultDisableRecipeMod),
            // How the book behaves as a file, grouped: a `MapCodec`, so its nine fields sit flat
            // on the `settings` block beside these, as if they were fields of their own. See
            // `QuestSettingsExtra` for why the grouping is invisible in JSON.
            QuestSettingsExtra.MAP_CODEC.forGetter(QuestSettings::extra)
    ).apply(instance, QuestSettings::new));

    /**
     * The canonical locale, normalised, or empty when the setting names something that is not one.
     *
     * <p>Empty rather than the raw string, because every reader wants the locale id the files are
     * keyed by: an unusable value means "no canonical locale", which is a state the resolution
     * already handles, and passing the raw value on would be a second spelling that silently never
     * matches a file.
     */
    public String canonicalLocale() {
        return QuestLanguages.normalise(fallbackLocale);
    }

    /**
     * How the book behaves, without learning {@link #extra()}'s name.
     *
     * <p>Kept so no caller learns the grouping's name: the grouping is a codec's answer to a
     * codec's limit, and the callers predate it. See {@code Chapter#elements()} for the same
     * arrangement and the reason it exists.
     */
    public boolean showLockIcons() {
        return extra.showLockIcons();
    }

    /**
     * Whether the canvas draws without its zoom tiers unless a chapter says otherwise: the
     * book-level half of {@code disableCanvasLod}, with a chapter's own flag winning per
     * chapter. See {@link QuestSettingsExtra} for the legacy reason this defaults off.
     */
    public boolean disableCanvasLod() {
        return extra.disableCanvasLod();
    }

    /** Whether quests shut out by an exclusive choice vanish from the reader's book. */
    public boolean hideExcludedQuests() {
        return extra.hideExcludedQuests();
    }

    /** Whether the book pauses the world in single player. */
    public boolean pauseGame() {
        return extra.pauseGame();
    }

    /** Whether the book refuses to open. */
    public boolean disableGui() {
        return extra.disableGui();
    }

    /** Whether dying drops the quest book at the player's feet. */
    public boolean dropBookOnDeath() {
        return extra.dropBookOnDeath();
    }

    /** Whether a player joining for the first time finds a quest book in their inventory. */
    public boolean giveBookOnFirstJoin() {
        return extra.giveBookOnFirstJoin();
    }

    /** The editor canvas's grid step, in content units per cell edge. */
    public double gridScale() {
        return extra.gridScale();
    }

    /** What a locked quest is called when the pack has a better word than "Locked". */
    public String lockMessage() {
        return extra.lockMessage();
    }

    /** How long a player waits between emergency grants, in seconds. */
    public int emergencyItemsCooldown() {
        return extra.emergencyItemsCooldown();
    }

    /** What {@code /tenet emergency} hands out. */
    public java.util.List<ItemRef> emergencyItems() {
        return extra.emergencyItems();
    }

    public static final Codec<QuestSettings> CODEC = MAP_CODEC.codec();

    /**
     * The settings an {@code index.json} at this root declares, or the defaults.
     *
     * <p>Never throws: this runs during a load whose whole promise is that a broken file produces a
     * useful message rather than a crash, and a settings block is not worth more than the questline.
     */
    public static QuestSettings load(Path questRoot) {
        Path indexPath = questRoot.resolve(QuestFiles.INDEX_MANIFEST);
        if (!Files.isRegularFile(indexPath)) {
            return DEFAULTS;
        }
        JsonElement root;
        try {
            root = JsonParser.parseString(Files.readString(indexPath));
        }
        catch (IOException | RuntimeException e) {
            Constants.LOG.warn("Tenet: could not read {} for its settings; using the defaults ({})",
                    indexPath, e.getMessage());
            return DEFAULTS;
        }
        if (!root.isJsonObject()) {
            return DEFAULTS;
        }
        JsonObject settings = root.getAsJsonObject().getAsJsonObject("settings");
        if (settings == null) {
            return DEFAULTS;
        }
        return CODEC.parse(JsonOps.INSTANCE, settings).resultOrPartial(problem ->
                Constants.LOG.warn("Tenet: index.json settings: {}", problem)).orElse(DEFAULTS);
    }
}
