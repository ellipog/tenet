package dev.ellipog.tenet.party;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.StringJoiner;

/**
 * How one party's members' counts are combined — the party's answer to <i>"whose progress counts"</i>.
 *
 * <h2>Why this is Tenet's, and not Armature's</h2>
 *
 * <p>The rule that decides where a class lives is Armature's own: <i>"when unsure which repo a class
 * belongs in, put it in Armature"</i>. This one is not unsure, and the reason is not convenience.
 *
 * <p>A <b>party</b> is general, and all of it is already in Armature: membership, roles, invites,
 * events, persistence, and the adapters that read other mods' parties. <b>Combining counts is not
 * general.</b> What a mode combines is "how many oak logs does this member have", and the only thing
 * in either repo with an opinion about oak logs is a quest. Armature's teams are deliberately
 * ignorant of what a team is <i>for</i> — that is the property that makes them reusable by a mod
 * whose teams have nothing to do with quests — and teaching them to combine integers would be the
 * first step in the direction the legacy project went, where shared progress had to be untangled from the code that
 * owned the progress and became a third mod to ship.
 *
 * <p>So the general primitive is where it belongs already, and what is added here is the one piece
 * that is specifically a quest engine's business. The test for "should this have gone in Armature"
 * is whether another mod could want it; a mod with no quests cannot want this.
 *
 * <h2>The three modes, and which one is the default</h2>
 *
 * <p>{@link #ONE_MEMBER} is the default, and it is what the engine did unconditionally before a party
 * could choose anything. It is the least that makes "two players share progress correctly" true: a
 * party's quest is satisfied when <i>somebody</i> in it has the items, which is what makes one player
 * gathering work for both. It became an explicit mode rather than staying implicit when the others
 * arrived, because "nobody chose" and "we chose the middle option" should not read the same.
 *
 * <p>{@link #POOLED} adds the members' counts together — a more generous rule, and a real one: eight
 * members carrying one log each would finish a gather-eight quest, and eight members contributing one
 * log each would pay for one. It is the mode where <b>paying</b> changes as well as counting, which
 * is what {@link #takesFromEveryone} exists to say.
 *
 * <p>{@link #OWNER_ONLY} counts nobody but the owner. It is the mode the legacy format offers first, and its
 * use is a party where one player is doing the progressing and the others are along for the ride.
 *
 * <h2>Two questions, not one, and the second is the one that bites</h2>
 *
 * <p>A mode answers <b>how much</b> and <b>who is holding it</b>. A consuming task's items are taken
 * from the player who was counted, so a mode that answered only the first would record its task
 * satisfied while the items stayed in somebody's pocket — see {@link Tally}.
 *
 * <p>All three keep one invariant: <b>a count of zero has no payer</b>. That is not a coincidence of
 * three similar implementations; it is what the engine uses to tell "nobody has any yet" from
 * "somebody has some", and it is asserted for every mode rather than assumed of them.
 *
 * <h2>Deliberately not a codec here</h2>
 *
 * <p>The file format for a mode is {@link PartyStore}'s business, and it spells a mode by name. A
 * codec on this enum would be a second spelling of the same names — one for a file, one for
 * {@code /tenet party mode} — and the two would drift the first time one was renamed. So
 * {@link #byId} and {@link #ids()} are the single spelling, and the store and the command both go
 * through them.
 */
public enum PartyMode {

    /**
     * Satisfied when the largest single member's count reaches the requirement.
     *
     * <p>The default, and the mode that makes sharing true without inventing a rule about who pays.
     * "The party has eight logs" means "one of us has eight logs", which is the least surprising
     * reading of a party sharing progress, and it is what the engine did before there was a choice.
     */
    ONE_MEMBER,

    /**
     * Satisfied when the members' counts <b>added together</b> reach the requirement.
     *
     * <p>A more generous rule, and a genuinely different one: four members carrying two logs each
     * finish a gather-eight quest that none of them could finish alone. Items are taken from as many
     * members as it takes, in a fixed order, so a consuming task under this mode pays with what the
     * party actually pooled rather than trying to find eight logs in one inventory and quietly
     * finding fewer.
     */
    POOLED,

    /**
     * Satisfied when <b>the owner's</b> count reaches the requirement, whatever anybody else has.
     *
     * <p>The mode for a party where one player is the one making progress — a helper, somebody being
     * carried, somebody watching. It is the legacy format's first party option, and it is the reason the
     * owner's index has to reach {@link #combine} at all.
     *
     * <p><b>An offline owner counts as zero</b>, and that follows from the rule rather than being an
     * edge case bolted onto it: this mode says the owner's inventory is the only one that counts, and
     * an inventory that is not loaded is not one that can be counted. The alternative — falling back
     * to another member — would be a different mode wearing this one's name.
     */
    OWNER_ONLY;

    /** What a party counts with until it says otherwise. See {@link #ONE_MEMBER}. */
    public static final PartyMode DEFAULT = ONE_MEMBER;

    /**
     * How much this party has, and who is holding it.
     *
     * <p>Two answers rather than one because the engine needs both, and a mode that could answer only
     * the count would break consuming tasks under {@link #POOLED}: the count can be eight across four
     * members while no single inventory holds eight, so "take the requirement from the payer" would
     * take what happened to be there and record the rest as satisfied. Returning the payer is what
     * lets the engine know it has to ask more than one inventory for the items.
     *
     * @param counted the amount this mode says the party has
     * @param payer   the index of the member the counted amount is held by, or −1 when nothing is
     *                counted. Under {@link #POOLED} the count is the whole party's while the payer is
     *                only its largest holder, which is exactly the case {@link PartyMode#takesFromEveryone}
     *                exists for
     */
    public record Tally(int counted, int payer) {

        /** Nothing counted, and nobody holding it. The answer for an empty party. */
        public static final Tally NONE = new Tally(0, -1);

        /** Whether a member can be pointed at. False exactly when nothing was counted — see the class note. */
        public boolean hasPayer() {
            return payer >= 0;
        }
    }

    /**
     * Combines every online member's count into this party's answer.
     *
     * <p>Takes a list rather than the players themselves, and that is what makes every mode's
     * arithmetic a pure function with no Minecraft in it — so the rules are asserted directly rather
     * than through a server. The list is in a fixed order (see {@code ProgressService}'s own note on
     * why the sort matters), so an index is a stable reference to a member.
     *
     * @param perMember  one count per online member, in a stable order
     * @param ownerIndex which of them the owner is, or −1 if the owner is offline. Only
     *                   {@link #OWNER_ONLY} reads it
     */
    public Tally combine(List<Integer> perMember, int ownerIndex) {
        if (perMember.isEmpty()) {
            return Tally.NONE;
        }
        return switch (this) {
            case ONE_MEMBER -> largest(perMember);
            case POOLED -> pooled(perMember);
            case OWNER_ONLY -> ownerOnly(perMember, ownerIndex);
        };
    }

    /**
     * Whether paying for a consuming task takes from more than one member.
     *
     * <p>Derived from the counting rule rather than being a second opinion about it: a mode that adds
     * the party's counts together is a mode where the items are in more than one inventory by
     * construction. The other two count one member's inventory, so one member pays — and the two
     * halves cannot disagree, because this is the same {@code this} the counting went through.
     */
    public boolean takesFromEveryone() {
        return this == POOLED;
    }

    /** The name a file and a command both spell: {@code one_member}, not {@code ONE_MEMBER}. */
    public String id() {
        return name().toLowerCase(Locale.ROOT);
    }

    /** One line for a player choosing. */
    public String description() {
        return switch (this) {
            case ONE_MEMBER -> "counts the member who has the most";
            case POOLED -> "adds all members' counts together, and shares the cost";
            case OWNER_ONLY -> "counts only the party owner";
        };
    }

    /** The mode {@code id} names, if it names one. */
    public static Optional<PartyMode> byId(String id) {
        if (id == null) {
            return Optional.empty();
        }
        String wanted = id.trim().toLowerCase(Locale.ROOT);
        for (PartyMode mode : values()) {
            if (mode.id().equals(wanted)) {
                return Optional.of(mode);
            }
        }
        return Optional.empty();
    }

    /** Every mode's id, for an error message that says what the options were. */
    public static String ids() {
        StringJoiner joined = new StringJoiner(", ");
        for (PartyMode mode : values()) {
            joined.add(mode.id());
        }
        return joined.toString();
    }

    // ------------------------------------------------------------------
    // The arithmetic
    // ------------------------------------------------------------------

    /**
     * The largest count, and the first member attaining it.
     *
     * <p>Strictly greater, so the earliest member wins a tie — which makes the payer depend on the
     * members' <i>order</i> rather than on the map's iteration order, and the order is sorted before
     * this is called. Two members holding the same number is the ordinary case, not a corner: they
     * both gathered eight logs.
     */
    private static Tally largest(List<Integer> perMember) {
        int best = 0;
        int bestIndex = -1;
        for (int i = 0; i < perMember.size(); i++) {
            int value = perMember.get(i);
            if (value > best) {
                best = value;
                bestIndex = i;
            }
        }
        return bestIndex < 0 ? Tally.NONE : new Tally(best, bestIndex);
    }

    /**
     * The total, and the largest holder as the one who pays.
     *
     * <p>The payer here is not "who the count came from" — it came from everybody. It is the member
     * the reward is paid to and the member the events name, because under this mode the party's task
     * is satisfied by what its members hold between them, so there has to be one player to hand the
     * result to. It is <b>not</b> where the take starts and not the order it proceeds in: see
     * {@link #takesFromEveryone}, and {@code ProgressService.consumeAcross}, which walks the
     * contributors in sorted member order so which pockets get lighter is reproducible.
     */
    private static Tally pooled(List<Integer> perMember) {
        int total = 0;
        int largest = 0;
        int largestIndex = -1;
        for (int i = 0; i < perMember.size(); i++) {
            int value = perMember.get(i);
            total += value;
            if (value > largest) {
                largest = value;
                largestIndex = i;
            }
        }
        return total == 0 ? Tally.NONE : new Tally(total, largestIndex);
    }

    /**
     * The owner's count, and nobody else's.
     *
     * <p>An index that is not a member gives {@link Tally#NONE}, which is the offline owner and the
     * empty party arriving at the same answer — correctly, since in both cases there is no inventory
     * here that this mode is allowed to count.
     */
    private static Tally ownerOnly(List<Integer> perMember, int ownerIndex) {
        if (ownerIndex < 0 || ownerIndex >= perMember.size()) {
            return Tally.NONE;
        }
        int value = perMember.get(ownerIndex);
        return value == 0 ? Tally.NONE : new Tally(value, ownerIndex);
    }
}
