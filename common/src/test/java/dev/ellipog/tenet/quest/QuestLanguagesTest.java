package dev.ellipog.tenet.quest;

import dev.ellipog.armature.api.data.DataProblem;
import dev.ellipog.armature.api.data.Problems;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The pack's translations: what loads, what is refused, and which locale a player is served.
 *
 * <h2>Why the resolution chain is tested this hard</h2>
 *
 * <p>Because it is the part with no visible failure. A locale that resolves to the wrong file draws
 * text a player cannot read, and nothing anywhere says so — no error, no log line, no missing key.
 * The chain has five rungs and each exists for a case that really happens, so each is asserted on its
 * own rather than through one pack that happens to exercise them all.
 *
 * <p>No vanilla bootstrap: nothing here reads an item registry. A locale file is a flat object of key
 * to text, and the loader's whole job is to read it and pick one.
 */
@DisplayName("the pack's own translations")
class QuestLanguagesTest {

    /** The root the loader reads, which is where `QuestLoader` puts the tree. */
    private static Path questRoot(Path configDir) {
        return configDir.resolve(QuestLoader.DIRECTORY);
    }

    /** Writes one locale file, creating the folder. */
    private static void locale(Path configDir, String name, String json) throws IOException {
        Path folder = questRoot(configDir).resolve(QuestFiles.LANG_DIRECTORY);
        Files.createDirectories(folder);
        Files.writeString(folder.resolve(name), json, StandardCharsets.UTF_8);
    }

    private static String flat(String... pairs) {
        StringBuilder out = new StringBuilder("{");
        for (int i = 0; i < pairs.length; i += 2) {
            if (i > 0) {
                out.append(", ");
            }
            out.append('"').append(pairs[i]).append("\": \"").append(pairs[i + 1]).append('"');
        }
        return out.append('}').toString();
    }

    // ------------------------------------------------------------------
    // Loading
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("loading")
    class Loading {

        @Test
        @DisplayName("no lang folder is a normal state and reports nothing")
        void noFolderIsQuiet(@TempDir Path configDir) {
            // The state every pack without translations is in, which is most of them. A warning here
            // would be a line in the log of every install on every reload.
            Problems problems = new Problems();

            QuestLanguages loaded = QuestLanguages.load(questRoot(configDir), problems);

            assertTrue(loaded.isEmpty());
            assertTrue(loaded.locales().isEmpty());
            assertTrue(problems.isEmpty(), () -> "nothing to say about a folder that is not there:\n"
                    + problems.all());
        }

        @Test
        @DisplayName("one file per locale, keyed by the file's own name")
        void filesAreKeyedByName(@TempDir Path configDir) throws IOException {
            locale(configDir, "en_us.json", flat("quest.a.title", "Punch a Tree"));
            locale(configDir, "es_es.json", flat("quest.a.title", "Golpea un arbol"));

            QuestLanguages loaded = QuestLanguages.load(questRoot(configDir), new Problems());

            assertEquals(java.util.Set.of("en_us", "es_es"), loaded.locales());
            assertEquals("Golpea un arbol",
                    loaded.forLocale("es_es", "en_us").get("quest.a.title"));
        }

        @Test
        @DisplayName("a name that is not a locale id is refused, and named")
        void aBadFileNameIsRefused(@TempDir Path configDir) throws IOException {
            // There is no key to point at: what is wrong is the name itself, so the report says so at
            // the file's first line rather than at some path inside it.
            locale(configDir, "not a locale.json", flat("quest.a.title", "A"));
            Problems problems = new Problems();

            QuestLanguages loaded = QuestLanguages.load(questRoot(configDir), problems);

            assertTrue(loaded.isEmpty());
            assertEquals(java.util.Set.of("not a locale.json"), loaded.refused());
            assertTrue(problems.hasErrorsIn("not a locale.json"),
                    "a file nothing can ask for has to be reported:\n" + problems.all());
        }

        @Test
        @DisplayName("a value that is not a string refuses the whole file rather than half-applying it")
        void aBadValueRefusesTheFile(@TempDir Path configDir) throws IOException {
            // Half a locale is the failure that reads as working: a few sentences translated and the
            // rest silently in the canonical language, with nothing saying which. The problem is
            // reported at the key's own position, which a flat file can point at exactly.
            locale(configDir, "es_es.json", "{ \"quest.a.title\": \"Bien\", \"quest.a.subtitle\": 7 }");
            Problems problems = new Problems();

            QuestLanguages loaded = QuestLanguages.load(questRoot(configDir), problems);

            assertTrue(loaded.isEmpty(), "the file is refused whole");
            assertEquals(java.util.Set.of("es_es"), loaded.refused());
            assertTrue(problems.hasErrorsIn("es_es.json"));
            assertTrue(problems.all().stream().anyMatch(problem ->
                            problem.severity() == DataProblem.Severity.ERROR
                                    && problem.message().contains("string")),
                    "the report should say what was expected:\n" + problems.all());
        }

        @Test
        @DisplayName("a file that does not parse is refused and the others still load")
        void oneBadFileDoesNotStopTheRest(@TempDir Path configDir) throws IOException {
            // A lang file must never stop the book loading. An author with a typo in a translation
            // should get a working book, the file named, and the key pointed at.
            locale(configDir, "es_es.json", "{ this is not json");
            locale(configDir, "fr_fr.json", flat("quest.a.title", "Cogner un arbre"));
            Problems problems = new Problems();

            QuestLanguages loaded = QuestLanguages.load(questRoot(configDir), problems);

            assertEquals(java.util.Set.of("fr_fr"), loaded.locales());
            assertEquals(java.util.Set.of("es_es"), loaded.refused());
            assertTrue(problems.hasErrorsIn("es_es.json"));
        }

        @Test
        @DisplayName("the underscore rule applies here like everywhere else")
        void underscoredFilesAreSkipped(@TempDir Path configDir) throws IOException {
            // The manual's "any file or folder whose name begins with `_` is skipped" has to hold in
            // every walk, or a note beside the real files becomes a locale with a name nobody can ask
            // for. See `DeclaredPaths.isIgnoredName`, which the other walks already call.
            locale(configDir, "es_es.json", flat("quest.a.title", "Bien"));
            locale(configDir, "_draft.json", flat("quest.a.title", "Draft"));

            QuestLanguages loaded = QuestLanguages.load(questRoot(configDir), new Problems());

            assertEquals(java.util.Set.of("es_es"), loaded.locales());
        }
    }

    // ------------------------------------------------------------------
    // Which locale a player reads
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("resolution")
    class Resolution {

        /** A pack with the locales named, each holding one key so the choice is visible. */
        private QuestLanguages pack(Path configDir, String... locales) throws IOException {
            for (String id : locales) {
                locale(configDir, id + ".json", flat("who", id));
            }
            return QuestLanguages.load(questRoot(configDir), new Problems());
        }

        @Test
        @DisplayName("the exact locale wins when the pack has it")
        void exactWins(@TempDir Path configDir) throws IOException {
            QuestLanguages loaded = pack(configDir, "en_us", "es_es", "es_mx");

            assertEquals("es_mx", loaded.servedLocale("es_mx", "en_us"));
            assertEquals("es_mx", loaded.forLocale("es_mx", "en_us").get("who"));
        }

        @Test
        @DisplayName("a file named for the language serves every region of it")
        void theLanguageRootIsSecond(@TempDir Path configDir) throws IOException {
            // `es.json` is Spanish wherever it is read, so every `es_*` client wants it -- and it is
            // more specific than the pack's canonical locale, which is a different language entirely.
            QuestLanguages loaded = pack(configDir, "en_us", "es");

            assertEquals("es", loaded.servedLocale("es_mx", "en_us"));
        }

        @Test
        @DisplayName("the pack's own canonical locale is preferred over an unrelated sibling")
        void theCanonicalLocaleIsThePreferredSibling(@TempDir Path configDir) throws IOException {
            // An `es_mx` player reading a pack whose canonical locale is `es_es` gets the Spanish the
            // author declared canonical, not whichever sibling sorts first. This is the rung that keeps
            // Latin American players off English, which is the whole reason it exists.
            QuestLanguages loaded = pack(configDir, "es_ar", "es_es", "en_us");

            assertEquals("es_es", loaded.servedLocale("es_mx", "es_es"));
            assertEquals("es_es", loaded.servedLocale("es_cl", "es_es"));
        }

        @Test
        @DisplayName("any sibling beats English, in name order so the answer cannot vary")
        void anySiblingIsBetterThanNothing(@TempDir Path configDir) throws IOException {
            // Minecraft ships es_ar, es_cl, es_ec, es_mx, es_uy and es_ve, and a pack with one Spanish
            // file would otherwise serve none of them. Name order rather than any cleverer rule, so the
            // answer is the same on every server and in every run.
            QuestLanguages loaded = pack(configDir, "es_ve", "es_ar", "en_us");

            assertEquals("es_ar", loaded.servedLocale("es_mx", "en_us"),
                    "the first sibling in name order, and the same one every time");
        }

        @Test
        @DisplayName("a language the pack has nothing for falls back to the canonical locale")
        void nothingMatchingFallsBackToCanonical(@TempDir Path configDir) throws IOException {
            QuestLanguages loaded = pack(configDir, "en_us", "de_de");

            assertEquals("en_us", loaded.servedLocale("fr_ca", "en_us"));
            assertEquals("de_de", loaded.servedLocale("fr_ca", "de_de"),
                    "the canonical locale is what an unrelated language gets");
        }

        @Test
        @DisplayName("a pack with no translations serves nothing, and says so")
        void anEmptyPackServesNothing(@TempDir Path configDir) {
            QuestLanguages loaded = QuestLanguages.EMPTY;

            assertEquals("", loaded.servedLocale("es_mx", "en_us"));
            assertTrue(loaded.forLocale("es_mx", "en_us").isEmpty(),
                    "nothing to merge, so the tree's own strings stay on screen");
        }

        @Test
        @DisplayName("a value that is not a locale id resolves to nothing rather than to a file")
        void garbageIsNotALocale(@TempDir Path configDir) throws IOException {
            // The client is the untrusted end of the request, so the string it names is decided to be a
            // locale id or not, and is then only ever a map key. `../en_us` is not a locale, so it is
            // not looked up -- and there is no path it could have been resolved against anyway.
            QuestLanguages loaded = pack(configDir, "en_us", "es_es");

            assertEquals("", loaded.servedLocale("../en_us", ""));
            assertEquals("", loaded.servedLocale("en_us/../../etc/passwd", ""));
            assertEquals("", loaded.servedLocale("", ""));
            assertEquals("", loaded.servedLocale(null, ""));
        }

        @Test
        @DisplayName("normalisation is one rule, applied to file names and to client strings alike")
        void normalisationIsShared(@TempDir Path configDir) throws IOException {
            // Both directions go through the same method, so the two can never be spelled differently
            // and miss each other. A file named `en-US.json` is the locale `en_us`, and a client that
            // says `EN-us` is asking for the same one.
            locale(configDir, "en-US.json", flat("who", "en_us"));
            QuestLanguages loaded = QuestLanguages.load(questRoot(configDir), new Problems());

            assertEquals(java.util.Set.of("en_us"), loaded.locales());
            assertEquals("en_us", loaded.servedLocale("EN-us", ""));
        }
    }

    // ------------------------------------------------------------------
    // The canonical locale underneath
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("the canonical locale underneath")
    class Merging {

        @Test
        @DisplayName("the served locale wins every key it has, and inherits the rest")
        void theServedLayerWins(@TempDir Path configDir) throws IOException {
            // What the merge is for: a pack can keep the strings every language shares -- a name, a
            // number, a line it did not translate -- in its canonical file and only the differences
            // elsewhere. A merge that went the other way would leave the canonical text on top of the
            // translation, which is the bug this rule exists to prevent.
            locale(configDir, "en_us.json", flat("shared", "Shared", "quest.a.title", "Punch a Tree"));
            locale(configDir, "es_es.json", flat("quest.a.title", "Golpea un arbol"));

            QuestLanguages loaded = QuestLanguages.load(questRoot(configDir), new Problems());
            Map<String, String> entries = loaded.forLocale("es_es", "en_us");

            assertEquals("Golpea un arbol", entries.get("quest.a.title"), "the translation wins");
            assertEquals("Shared", entries.get("shared"), "and everything it does not name is inherited");
        }

        @Test
        @DisplayName("a locale with no file of its own still gets the canonical one")
        void theCanonicalLocaleIsAlwaysUnderneath(@TempDir Path configDir) throws IOException {
            locale(configDir, "en_us.json", flat("shared", "Shared"));

            QuestLanguages loaded = QuestLanguages.load(questRoot(configDir), new Problems());

            assertEquals(Map.of("shared", "Shared"), loaded.forLocale("fr_ca", "en_us"),
                    "an untranslated language reads the canonical file rather than nothing");
        }

        @Test
        @DisplayName("the canonical locale on its own is not copied, it is returned")
        void theBaseIsNotCopied(@TempDir Path configDir) throws IOException {
            // The common case for a pack that only ships its canonical language, and it is asked once
            // per player per reload. Copying a map per ask would be work with no purpose.
            locale(configDir, "en_us.json", flat("quest.a.title", "Punch a Tree"));

            QuestLanguages loaded = QuestLanguages.load(questRoot(configDir), new Problems());

            assertEquals(loaded.forLocale("en_us", "en_us"), loaded.forLocale("fr_fr", "en_us"));
            assertTrue(loaded.forLocale("en_us", "en_us").containsKey("quest.a.title"));
        }
    }

    // ------------------------------------------------------------------
    // The entry point a server uses
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("what a reload does with it")
    class Reloading {

        @Test
        @DisplayName("a lang file's problems reach the same report the tree's do")
        void problemsReachTheTreesReport(@TempDir Path configDir) throws IOException {
            // The reason the loader takes the caller's problem list rather than keeping its own: that
            // list is what the log, `/tenet reload` and the in-game report to every author all read.
            // A mistyped translation is exactly the thing an author should be told about without going
            // to look for it.
            locale(configDir, "es_es.json", "{ \"quest.a.title\": 7 }");
            Problems problems = new Problems();
            problems.add("index.json", new dev.ellipog.armature.api.data.JsonLocation(1, 1, "$"),
                    DataProblem.Severity.WARNING, "a tree problem that was already there");

            QuestLanguages.load(questRoot(configDir), problems);

            assertEquals(2, problems.all().size(), () -> "both, in one report:\n" + problems.all());
            assertTrue(problems.hasErrors());
            assertTrue(problems.hasErrorsIn("es_es.json"));
            assertFalse(problems.hasErrorsIn("index.json"),
                    "and the tree's own problem kept its severity");
        }
    }

    // ------------------------------------------------------------------
    // Keys that name nothing
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("stale keys")
    class StaleKeys {

        /**
         * A tree with a group, a chapter, a quest and a label, without touching the disk.
         *
         * <p>Headless: the codecs ask for no registry, and the index keeps its own problems, so
         * only the language warnings under test reach the caller's list. The fixed names are
         * {@code fileWithChapter}'s — group {@code group}, chapter {@code chapter} — with quest
         * {@code a} and label {@code sign} beside them; tables are passed by name, as the loader
         * keys them.
         */
        private static QuestIndex tree() {
            return Fixtures.indexOf(Fixtures.fileWithChapter(
                    "\"elements\": [{\"type\": \"text\", \"id\": \"sign\", \"x\": 0, \"y\": 0,"
                            + " \"text\": \"Hello\"}],",
                    Fixtures.q("a").at(0, 0).build()));
        }

        private static Problems stale(Path configDir, QuestIndex index, java.util.Set<String> tables)
                throws IOException {
            Problems problems = new Problems();
            QuestLanguages loaded = QuestLanguages.load(questRoot(configDir), problems);
            loaded.warnStale(index, tables, problems);
            return problems;
        }

        private static String warnings(Problems problems) {
            return problems.all().stream()
                    .filter(problem -> problem.severity() == DataProblem.Severity.WARNING)
                    .map(DataProblem::render)
                    .reduce("", (a, b) -> a + "\n" + b);
        }

        @Test
        @DisplayName("keys for loaded objects are quiet")
        void keysForLoadedObjectsAreQuiet(@TempDir Path configDir) throws IOException {
            locale(configDir, "en_us.json", flat("quest.a.title", "A", "quest.a.subtitle", "Sub",
                    "quest.a.description", "Words", "quest.a.description.0", "Words",
                    "chapter.chapter.title", "C", "chapter.chapter.subtitle", "Sub",
                    "group.group.title", "G", "rewardTable.loot.title", "Loot",
                    "book.title", "Book", "element.sign.text", "Sign"));

            Problems problems = stale(configDir, tree(), java.util.Set.of("loot"));

            assertTrue(problems.isEmpty(), () -> "nothing stale in a file that names live objects:\n"
                    + problems.all());
        }

        @Test
        @DisplayName("keys for deleted objects warn, and the book still loads")
        void keysForDeletedObjectsWarnButDoNotFail(@TempDir Path configDir) throws IOException {
            // A deleted quest's keys stay behind silently, and the tree's own string stays on
            // screen with nothing saying which file holds the dead key. One warning per file,
            // never an error: a converted pack with leftover keys still loads cleanly.
            locale(configDir, "en_us.json", flat("quest.gone.title", "Gone",
                    "chapter.gone.title", "Gone", "group.gone.title", "Gone",
                    "rewardTable.gone.title", "Gone", "element.gone.text", "Gone"));

            Problems problems = stale(configDir, tree(), java.util.Set.of("loot"));

            assertFalse(problems.hasErrors(), "stale keys must never fail a load:\n" + problems.all());
            assertEquals(1, problems.warningCount(), "one warning for the file:\n" + problems.all());
            String report = warnings(problems);
            assertTrue(report.contains("quest.gone.title") && report.contains("no such quest"),
                    "the quest key is named with its reason:\n" + report);
            assertTrue(report.contains("chapter.gone.title") && report.contains("no such chapter"),
                    "and so is the chapter's:\n" + report);
            assertTrue(report.contains("element.gone.text") && report.contains("no such element"),
                    "and the label's:\n" + report);
            assertTrue(problems.forFile("es_es.json").isEmpty() && !problems.forFile("en_us.json").isEmpty(),
                    "reported against the file that holds them:\n" + problems.all());
        }

        @Test
        @DisplayName("another case suggests the lowercase id")
        void wrongCaseSuggestsTheLowercaseId(@TempDir Path configDir) throws IOException {
            // The spelling a hand-merged legacy file most often gets wrong: legacy ids are uppercase
            // hex, Tenet ids are lowercase, and the reader builds its lookups from the lowercase
            // one — so an uppercase key is dead, and the fix is spelled out rather than implied.
            locale(configDir, "en_us.json",
                    flat("quest.A.title", "A", "rewardTable.LOOT.title", "Loot"));

            Problems problems = stale(configDir, tree(), java.util.Set.of("loot"));

            assertFalse(problems.hasErrors());
            String report = warnings(problems);
            assertTrue(report.contains("did you mean \"a\""), "the quest's fix is named:\n" + report);
            assertTrue(report.contains("did you mean \"loot\""), "and the table's:\n" + report);
        }

        @Test
        @DisplayName("a key through an alias points at the id")
        void aliasKeyedEntriesPointAtTheId(@TempDir Path configDir) throws IOException {
            // Readers build their lookups from the canonical id, so an alias-keyed entry is never
            // read — and saying "no such quest" about one would be wrong, because the quest is
            // there. The warning names the id the key should use instead.
            QuestIndex index = Fixtures.indexOf(Fixtures.file(
                    Fixtures.q("a").alias("aye").at(0, 0).build()));
            locale(configDir, "en_us.json", flat("quest.aye.title", "Aye"));

            Problems problems = stale(configDir, index, java.util.Set.of());

            assertFalse(problems.hasErrors());
            assertTrue(warnings(problems).contains("is an alias"),
                    "the alias is named as an alias, not as a missing quest:\n" + problems.all());
        }

        @Test
        @DisplayName("live objects with unread fields warn")
        void unreadFieldsWarn(@TempDir Path configDir) throws IOException {
            // The object is there but no reader looks the field up: a chapter description, a
            // group description, a key past what the format reads. Same severity, different
            // sentence — the fix is to remove the key, not to fix a name.
            locale(configDir, "en_us.json", flat("quest.a.flavour", "Hmm",
                    "chapter.chapter.description", "Words", "group.group.description", "Words",
                    "element.sign.subtitle", "Sub", "book.subtitle", "Sub"));

            Problems problems = stale(configDir, tree(), java.util.Set.of("loot"));

            assertFalse(problems.hasErrors());
            assertTrue(warnings(problems).contains("nothing in the book is read through"),
                    "unread fields are reported as unread:\n" + problems.all());
        }

        @Test
        @DisplayName("keys in no book namespace are left alone")
        void nonBookKeysAreLeftAlone(@TempDir Path configDir) throws IOException {
            // The overlay serves any key, and a script may own the ones no reader looks up —
            // judging those would be a warning about somebody else's data.
            locale(configDir, "en_us.json", flat("shared", "Shared", "my_mod.bonus", "Bonus"));

            Problems problems = stale(configDir, tree(), java.util.Set.of("loot"));

            assertTrue(problems.isEmpty(), () -> "script-owned keys are not this check's:\n"
                    + problems.all());
        }

        @Test
        @DisplayName("each file is reported under its own name")
        void eachFileIsReportedUnderItsOwnName(@TempDir Path configDir) throws IOException {
            locale(configDir, "en_us.json", flat("quest.gone.title", "Gone"));
            locale(configDir, "es_es.json", flat("quest.gone.title", "Ido"));

            Problems problems = stale(configDir, tree(), java.util.Set.of("loot"));

            assertEquals(1, problems.forFile("en_us.json").size(), "one warning per file");
            assertEquals(1, problems.forFile("es_es.json").size(), "one warning per file");
            assertEquals(2, problems.warningCount(), "two files, two warnings:\n" + problems.all());
        }
    }

    @Nested
    @DisplayName("normalisation")
    class Normalisation {

        @Test
        @DisplayName("lower case, dashes to underscores, or empty")
        void spellsLocales() {
            assertEquals("en_us", QuestLanguages.normalise("en_us"));
            assertEquals("en_us", QuestLanguages.normalise("en-US"));
            assertEquals("en_us", QuestLanguages.normalise("  EN_us  "));
            assertTrue(QuestLanguages.normalise(null).isEmpty());
            assertTrue(QuestLanguages.normalise("not a locale").isEmpty());
        }

        @Test
        @DisplayName("the same string object is spelled once, however often it is asked")
        void sameReferenceAsksOnce() {
            // The per-tick poll asks with the client's own language string, which is the same
            // object every frame: the trim, the lower-casing and the pattern match run once per
            // language rather than once per tick.
            String language = "en_us";
            assertSame(QuestLanguages.normalise(language), QuestLanguages.normalise(language));
        }

        @Test
        @DisplayName("an equal string in another object recomputes to the same spelling")
        void equalContentRecomputes() {
            // Reference equality is the memo, not content equality: a fresh object with equal
            // characters is a missed hit and never a wrong one.
            String first = new String("en_us");
            String second = new String("en_us");
            assertEquals(QuestLanguages.normalise(first), QuestLanguages.normalise(second));
        }
    }
}
