package dev.ellipog.tenet.quest;

import java.util.Collection;

/**
 * What a chapter or group id may be, in one place.
 *
 * <h2>Why this is not {@code Checks.id}</h2>
 *
 * <p>Because the loader's rule and the book's rule are not the same rule. {@code Checks.id} asks "could
 * this be an id", and it permits a leading underscore — an id beginning {@code _} is a perfectly good
 * string that every folder the loader walks will <b>skip</b>. On disk that is an id nobody can ever read
 * back: the chapter would exist, load as nothing, and be invisible in the very list that just made it.
 * So the extra rules here are the ones the filesystem adds, and they are stated where the card can show
 * them as the author types rather than where a refusal arrives after the press.
 *
 * <h2>Two surfaces ask this, and they ask it here</h2>
 *
 * <p>The book asks as the author types, so a refusal arrives before the press; the server asks again when
 * the op lands, because the book's list of taken ids is advisory — it is built from the tree that client
 * has, and a second client or a hand-edited file can be ahead of it. One rule and one wording, so the
 * same mistake reads the same wherever it is caught. This class lived in {@code client/dev} until the
 * server's own copy of the rule was folded into it; it names nothing client-side, which is why it can.
 *
 * <p>The collision check is the one part that is not shared: {@link #problemWith(String, Collection)}
 * adds it for the card, whose list is the live ids that client can see — <b>not</b> their aliases, which
 * the synced tree does not carry — and the server asks {@link #problemWith(String)} and words its own
 * sentence, because its list is built from the manifests and therefore holds the aliases too. The card is
 * advisory either way: the server's answer is the one that decides.
 */
public final class ChapterNaming {

    /** How long an id may be, matching the loader's own limit. */
    public static final int MAX_LENGTH = 64;

    /**
     * Why this id cannot be used, or null when it can — the rule alone. See the class note for why the
     * collision check is not in here.
     *
     * @param id what the author typed, or the name the server was asked to create
     */
    public static String problemWith(String id) {
        String problem = nameProblem(id);
        if (problem != null) {
            return problem;
        }
        if (id.length() > MAX_LENGTH) {
            return "an id may be at most " + MAX_LENGTH + " characters";
        }
        return null;
    }

    /**
     * Why this is not a usable <b>name</b> at all, or null — the rule without the length limit.
     *
     * <h2>Split out for the callers that are naming a file rather than declaring an id</h2>
     *
     * <p>A name that is really a path is a write somewhere else: the editor joins an id to a folder to
     * make a file, and `../` in it reaches out of the quest tree. That rule has nothing to do with how
     * long an id may be, and the callers that mint a <i>fresh</i> name shorten an over-long base instead
     * of refusing it — so the two halves are asked separately. The length limit is
     * {@link #problemWith}'s.
     */
    public static String nameProblem(String id) {
        if (id == null || id.isBlank()) {
            return "an id is needed - it becomes the folder's name";
        }
        // **The character rule is also the deleted-suffix rule, and that is why there is no second
        // check here.** There used to be one — "an id may not end with .deleted" — and it was
        // unreachable: `.deleted` and `.deleted.2` both carry a dot, and this loop refuses the dot
        // first, so the branch could never be the answer. Dead code that reads as a live rule is worse
        // than no rule, so the fact is stated here instead: a name cannot be spelled the way a tombstone
        // is spelled, and the message the author gets names the character.
        for (int i = 0; i < id.length(); i++) {
            char c = id.charAt(i);
            if (!((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '_')) {
                return "only a-z, 0-9 and _ are allowed - '" + c + "' is not";
            }
        }
        if (id.startsWith("_")) {
            return "an id may not begin with _ - the loader skips every name beginning with it";
        }
        return null;
    }

    /**
     * The same, plus the collision check against the names of the same kind.
     *
     * <p>The list has to carry <b>ids and aliases</b> to be the whole answer: the loader claims the two in
     * one map per kind, so a new chapter whose id is another chapter's old name is reported as a duplicate
     * and the later entry is not loaded at all. The card's own list is the live ids it can see — it is
     * advisory, and a second client or a hand-edited file can be ahead of it — so the server asks the same
     * question again in {@code QuestStructure.idProblem}, against a set it builds from the manifests. The
     * server's answer is the one that decides.
     *
     * <p>A caller renaming something leaves that thing's own id out of {@code existing}: a list that
     * contained it reported the name the thing already had as taken. See the card's own list.
     *
     * @param id       what the author typed
     * @param existing every name the tree already uses <i>of the same kind</i>: ids and aliases
     */
    public static String problemWith(String id, Collection<String> existing) {
        String problem = problemWith(id);
        if (problem != null) {
            return problem;
        }
        if (existing != null && existing.contains(id)) {
            return "\"" + id + "\" is already used - ids and aliases have to be unique";
        }
        return null;
    }

    /**
     * A title as an id: lowercased, spaces to underscores, everything outside the id alphabet
     * stripped, with no leading underscore left and nothing past the length limit.
     *
     * <p>What the sidebar's inline create types into the id the new chapter or group will carry, so
     * typing only a title yields an id {@link #problemWith} accepts: the caller dedupes the result
     * with {@link #suggested} against the live ids, and falls back to {@code new_chapter} or
     * {@code new_group} numbering when the title slugs to nothing. An empty string out means the
     * title said nothing an id can spell, which is the fallback's cue rather than a second rule.
     *
     * @param title what the author typed, or null
     * @return the id the title spells, possibly empty
     */
    public static String slug(String title) {
        if (title == null) {
            return "";
        }
        StringBuilder kept = new StringBuilder(title.length());
        for (int i = 0; i < title.length(); i++) {
            char c = Character.toLowerCase(title.charAt(i));
            if ((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '_') {
                kept.append(c);
            }
            else if (c == ' ') {
                kept.append('_');
            }
        }
        int start = 0;
        while (start < kept.length() && kept.charAt(start) == '_') {
            start++;
        }
        int end = Math.min(kept.length(), start + MAX_LENGTH);
        return kept.substring(start, end);
    }

    /**
     * The first free {@code base + suffix}, {@code base + suffix 2}, ... not in {@code taken}.
     *
     * <p>What a duplicate is pre-filled with, so the card opens on a name that will work rather than on
     * one the author has to fix before the button does anything. The server asks the same question when
     * it copies a chapter or a group, which is the other reason this is here rather than in the card.
     */
    public static String suggested(String base, String suffix, Collection<String> taken) {
        String candidate = candidateFor(base, suffix, 0);
        int n = 2;
        while (taken != null && taken.contains(candidate)) {
            candidate = candidateFor(base, suffix, n++);
        }
        return candidate;
    }

    /**
     * One candidate, with the base shortened so the whole thing fits the id rule.
     *
     * <p><b>Why the base is what gets shortened.</b> The counter is appended after the stem, so the stem
     * has to leave room for the suffix <i>and</i> for the digits — and the digits are never truncated, or
     * two counters could shorten to one string and the search above would never end. Truncating the
     * finished candidate instead is exactly that bug: a base already at the limit swallows the suffix, so
     * every counter returns the base and the loop spins.
     *
     * @param counter the number appended, or 0 for the first candidate, which carries none
     */
    private static String candidateFor(String base, String suffix, int counter) {
        String digits = counter == 0 ? "" : String.valueOf(counter);
        int room = Math.max(1, MAX_LENGTH - suffix.length() - digits.length());
        String stem = base.length() <= room ? base : base.substring(0, room);
        return stem + suffix + digits;
    }

    private ChapterNaming() {
    }
}
