package dev.ellipog.tenet.client;

import dev.ellipog.armature.client.ui.kit.Insets;
import dev.ellipog.armature.client.ui.kit.Layout;
import dev.ellipog.armature.client.ui.kit.Measure;
import dev.ellipog.armature.client.ui.kit.Slot;
import dev.ellipog.armature.client.ui.kit.Stack;
import dev.ellipog.armature.client.ui.kit.TextWrap;
import dev.ellipog.armature.client.ui.party.PartyRoster;
import dev.ellipog.tenet.net.PartySnapshot;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * The party panel: what each face of it says, and where every row and control goes.
 *
 * <h2>Faces rather than one shape</h2>
 *
 * <p>The panel has four things it can be, and they are genuinely different screens rather than one
 * screen with rows missing:
 *
 * <ul>
 *   <li>{@link #notice()} — parties are unavailable, with the one instruction that changes that.</li>
 *   <li>{@link #soloLeft} / {@link #soloRight} — no party: start one, answer the invitations that are
 *       waiting, join an open one.</li>
 *   <li>{@link #activeLeft} / {@link #activeRight} — a party: who is in it and what the owner may
 *       change, split into two columns.</li>
 *   <li>{@link #confirm} — a small card for a question that must be answered: hand the party over,
 *       pick a successor, or disband.</li>
 * </ul>
 *
 * <h2>One description, and the screen is sized from it</h2>
 *
 * <p>Every builder returns a {@link Face}: the stack that places the rows and the list of
 * {@link Line}s the screen registers controls from. The two halves cannot disagree about a row's
 * location because there is one object, and a control's rectangle is derived from the row's own
 * {@link Slot} — see {@link #controlSlots}. That is the property the old panel was rewritten for
 * when its card and its rows came from two sums, and it is kept rather than re-learned.
 *
 * <h2>Where each half of it lives</h2>
 *
 * <p>{@link PartyRoster} is Armature's, because a member, a rank and "may I remove them" are facts
 * about a <b>team</b>. This class is Tenet's, because what it composes around the roster is one
 * screen's: a heading whose words come from this mod's language file, rows whose buttons send
 * <i>this</i> mod's commands, and a two-column body that exists because this panel's spec says so.
 *
 * <h2>Game-free, so it can be asserted on</h2>
 *
 * <p>Every field is a record, an id, a string or a number, so {@code PartyPanelLayoutTest} can ask
 * the questions that matter — is every control inside its row, does a solo player get a Create
 * control and a member not, are the two columns inside the card — with no window and no client.
 */
public final class PartyPanelLayout {

    // ------------------------------------------------------------------
    // Geometry
    // ------------------------------------------------------------------

    /** The space between the two columns. Kept for the deprecated split arithmetic below. */
    public static final int COLUMN_GAP = 8;

    /** The left column's share of the body, in percent. Deprecated: the panel stacks, see below. */
    public static final int LEFT_SHARE = 55;

    /** The height of an ordinary row. */
    public static final int ROW_HEIGHT = 18;

    /** Between two rows in the same section. */
    public static final int ROW_GAP = 3;

    /** The space either side of a rule that separates sections. */
    public static final int SECTION_GAP = 7;

    /** The title row's height. A line of text. */
    public static final int TITLE_HEIGHT = 11;

    /** The space under the title, before the first section. */
    public static final int TITLE_TAIL = 7;

    /** The height of one line in an empty state. */
    public static final int EMPTY_ADVANCE = 11;

    /** Between an empty state's two lines. Smaller than {@link #TITLE_TAIL}: they are one thought. */
    public static final int EMPTY_GAP = 3;

    /** How wide an ordinary row button is — Create, Accept, Invite, Join, Choose. */
    public static final int ROW_BUTTON = 88;

    /** How wide a short one is — Decline, Cancel. */
    public static final int SHORT_BUTTON = 64;

    /**
     * Horizontal padding inside a row button, both sides combined.
     *
     * <p>Matches {@code ArmatureButton}'s own {@code LABEL_INSET} of 6 per side plus a few pixels of
     * border slack, so a measured label plus this pad is the smallest width that does not truncate.
     * {@code Create Party} is 12 chars (~72px at 6px advance) + 12 inset = 84, which is why the old
     * 58 truncated it to {@code Create P...} in the solo screenshot.
     */
    public static final int BUTTON_PAD = 16;

    /** Below this body width the create row wraps to a field row plus a button row. */
    public static final int NARROW_WRAP_WIDTH = 280;

    /** The height of a 1px section rule row. The gaps around it come from spacers. */
    public static final int RULE_HEIGHT = 1;

    /** How wide a settings toggle is. Matches {@code ArmatureSwitch}'s own width. */
    public static final int SWITCH_WIDTH = 22;

    /** Kept between a row's right edge and the controls in its reserved strip. */
    public static final int CONTROL_INSET = 2;

    /** Between two controls in one row. */
    public static final int CONTROL_GAP = 2;

    // ------------------------------------------------------------------
    // A member row's interior
    // ------------------------------------------------------------------

    /** The portrait box: an 8x8 skin crop scaled up. */
    public static final int HEAD_BOX = 12;

    /** How far a portrait sits from its row's left edge, and the gap between it and the marker. */
    public static final int HEAD_INSET = 3;
    public static final int HEAD_GAP = 3;

    /** The online marker before a member's name: a small square, and the gap after it. */
    public static final int STATUS_DOT = 3;
    public static final int STATUS_GAP = 4;

    /**
     * How far a member's name sits below its row's own centre.
     *
     * <p>Optical rather than arithmetic: the line box a label is drawn in carries descender space
     * under the baseline, so text centred by that box reads a pixel or two high beside a face
     * centred on its own rectangle.
     */
    public static final int NAME_DROP = 2;

    /** The role chip: height, padding either side of its word, and the gap before the action strip. */
    public static final int CHIP_HEIGHT = 11;
    public static final int CHIP_PAD = 4;
    public static final int CHIP_GAP = 4;

    /**
     * The side of the square that replaces the chip when a role has no chip to show.
     *
     * <p>A role outside the three this build knows is drawn as a marker rather than as a word,
     * because a chip is a badge and a badge for something unnamed says nothing.
     */
    public static final int CHIP_MARK = 4;

    // ------------------------------------------------------------------
    // Keys
    // ------------------------------------------------------------------

    /** The party panel's own title, on the solo face. */
    public static final String TITLE = "tenet.screen.party.title";

                            private PartyPanelLayout() {
    }

    // ------------------------------------------------------------------
    // The row model
    // ------------------------------------------------------------------

    /**
     * One control in a row's reserved strip.
     *
     * <h2>Why a command is nullable, and what the two null shapes mean</h2>
     *
     * <p>A control with a command is a button whose press sends exactly what a typed command sends —
     * the same design the rest of the panel follows, where the server re-checks everything. A null
     * command is a control the <b>screen</b> answers, and there are exactly two kinds:
     * {@link #toggle(String)} is placed as a switch and reports on/off, and a null-command control
     * whose key ends in {@code :rename} opens the inline rename field. The screen decides by key,
     * which is the same rule the panel already uses for its own rows: a key is what a click routes by.
     *
     * @param key     what places this control and what a click is routed by
     * @param command the command a press sends, or null for a control the screen answers
     * @param width   how wide it is
     * @param accent  whether it wears the accent fill — one per row at most, so a row has a primary
     */
    public record Control(String key, String command, int width, boolean accent) {

        public static Control button(String key, String command) {
            return new Control(key, command, ROW_BUTTON, false);
        }

        public static Control primary(String key, String command) {
            return new Control(key, command, ROW_BUTTON, true);
        }

        public static Control small(String key, String command) {
            return new Control(key, command, SHORT_BUTTON, false);
        }

        /** A settings switch: the screen places an {@code ArmatureSwitch} and answers its toggle. */
        public static Control toggle(String key) {
            return new Control(key, null, SWITCH_WIDTH, false);
        }

        /** Whether a press sends a command rather than being answered by the screen. */
        public boolean sendsCommand() {
            return command != null;
        }

        /** Whether this is placed as a switch. See {@link #toggle}. */
        public boolean isToggle() {
            return command == null && key.endsWith(":switch");
        }
    }

    /**
     * One row of a face: a label, an optional detail at the right, and the controls beside them.
     *
     * <h2>Why one record rather than a widget per case</h2>
     *
     * <p>Every face is "a list of rows, some with controls", and the differences are what the rows
     * <i>say</i> rather than how they are placed. So the placement is one loop over this list and a
     * face is a different list.
     *
     * @param key      what places this row
     * @param label    what the row says, drawn at its left. May be empty for a field row the screen
     *                 fills with a text field
     * @param detail   what the row says at its right — a timestamp, a member count, an "online"
     *                 marker — or null. Drawn faint, except where the screen accents it
     * @param controls the controls in the row's strip, left to right. Empty is allowed
     * @param header   whether this row is a section heading. Drawn in heading ink, and the one thing
     *                 about a row's appearance the layout states rather than the screen, because which
     *                 rows are headings is a property of the face
     * @param height   how tall this row is. {@link #ROW_HEIGHT} for everything that holds a control or
     *                 a label; shorter for the pre-wrapped prose rows the notice is made of, where one
     *                 row is one line of a sentence rather than a thing with a control beside it
     */
    public record Line(String key, String label, String detail, List<Control> controls, boolean header,
                       int height) {

        public Line {
            controls = List.copyOf(controls);
        }

        public static Line plain(String key, String label) {
            return new Line(key, label, null, List.of(), false, ROW_HEIGHT);
        }

        public static Line header(String key, String label) {
            return new Line(key, label, null, List.of(), true, ROW_HEIGHT);
        }

        /** One line of pre-wrapped prose. See {@link #notice} for the only face that builds these. */
        public static Line prose(String key, String label) {
            return new Line(key, label, null, List.of(), false, EMPTY_ADVANCE);
        }

        /** A blank row, for the space between two paragraphs. Carries no words, so nothing is drawn. */
        public static Line spacer(String key) {
            return new Line(key, "", null, List.of(), false, EMPTY_GAP);
        }

        /**
         * A 1px section rule. Drawn by the screen as a faint line; carries no words and no controls.
         *
         * <p>The key must start with {@code rule:} so the screen can tell a rule from a spacer without
         * a new field on this record.
         */
        public static Line rule(String key) {
            return new Line(key, "", null, List.of(), false, RULE_HEIGHT);
        }

        /** Whether this row is a section rule (see {@link #rule}). */
        public boolean isRule() {
            return key() != null && key().startsWith("rule:") && label().isEmpty() && !hasControls();
        }

        /** Whether this row is an empty-state note (key ends in {@code :none}). Drawn muted. */
        public boolean isEmptyState() {
            return key() != null && key().endsWith(":none");
        }

        public static Line detail(String key, String label, String detail) {
            return new Line(key, label, detail, List.of(), false, ROW_HEIGHT);
        }

        public static Line controls(String key, String label, Control... controls) {
            return new Line(key, label, null, List.of(controls), false, ROW_HEIGHT);
        }

        public static Line controls(String key, String label, String detail, Control... controls) {
            return new Line(key, label, detail, List.of(controls), false, ROW_HEIGHT);
        }

        public boolean hasControls() {
            return !controls.isEmpty();
        }

        /** What a row must keep clear for its controls, controls included. */
        public int controlRoom() {
            if (controls.isEmpty()) {
                return 0;
            }
            int room = CONTROL_INSET * 2;
            for (Control control : controls) {
                room += control.width() + CONTROL_GAP;
            }
            return room - CONTROL_GAP;
        }
    }

    /**
     * One column: the stack that places its rows, and the rows themselves.
     *
     * <p>The screen holds the {@link Line}s to register controls from and the stack to build the
     * column's layout from, and both came out of one call — so a row the layout places and a row the
     * screen registers a button for cannot be two different rows.
     */
    public record Face(Stack stack, List<Line> lines) {

        /** The column built in {@code width}, with the height its rows come to. */
        public Layout build(int width, Measure measure) {
            return stack.build(Math.max(0, width), measure);
        }

        /** Where a line's control strip is, given the layout. See {@link #controlSlots}. */
        public List<Slot> controlSlots(Layout layout, String key) {
            Slot row = layout.slot(key);
            Line line = line(key);
            return row == null || line == null ? List.of() : PartyPanelLayout.controlSlots(line, row);
        }

        /** Where a line's text may go, given the layout: its row minus the controls' strip. */
        public Slot textSlot(Layout layout, String key) {
            Slot row = layout.slot(key);
            Line line = line(key);
            return row == null || line == null ? null : PartyPanelLayout.textSlot(line, row);
        }

        /** The line this face placed under {@code key}, or null. */
        public Line line(String key) {
            for (Line line : lines) {
                if (line.key().equals(key)) {
                    return line;
                }
            }
            return null;
        }
    }

    // ------------------------------------------------------------------
    // Building
    // ------------------------------------------------------------------

    /**
     * A list of lines as a stack, with each row reserving room for its own controls.
     *
     * <p>The gap goes <b>before</b> each row after the first rather than after each row, so the layout
     * does not end with one. A trailing gap places no slot, so {@code Layout.height()} — the bottom
     * edge of the lowest slot — would not count it, and the column would be built shorter than its
     * content. That is the fault this class was rewritten for once already, arriving by a second
     * route, so it is worth the sentence.
     */
    public static Stack stack(List<Line> lines) {
        Objects.requireNonNull(lines, "lines");
        Stack stack = Stack.stack();
        for (int i = 0; i < lines.size(); i++) {
            if (i > 0) {
                stack.gap(ROW_GAP);
            }
            Line line = lines.get(i);
            stack.row(line.key(), line.height(), line.controlRoom() == 0
                    ? Insets.NONE
                    : new Insets(0, 0, line.controlRoom(), 0));
        }
        return stack;
    }

    /** The same, for a face. See {@link Face#build}. */
    public static Face face(List<Line> lines) {
        return new Face(stack(lines), List.copyOf(lines));
    }

    /**
     * Where a row's text may go: the row minus the room its controls reserved.
     *
     * <p>The complement of {@link #controlSlots}, and it exists because a row holding a text field is
     * the one case where the screen must place a widget <i>inside</i> the label area rather than beside
     * it — the create-name field and the invite search. Both come from the same row slot, so the
     * field and the button beside it cannot disagree about where the row is.
     */
    public static Slot textSlot(Line line, Slot row) {
        Objects.requireNonNull(line, "line");
        Objects.requireNonNull(row, "row");
        return new Slot(line.key(), row.x(), row.y(),
                Math.max(0, row.width() - line.controlRoom()), row.height());
    }

    /**
     * Where a row's controls go: right-aligned as a group, left to right in the order given.
     *
     * <p>Derived from the row's own {@link Slot} rather than from a second computation of where the
     * row is — the property the kit exists for. The strip starts at the row's right edge, because the
     * row's inset reserved that room; a {@code STRETCH} row is narrowed by its inset, so the controls
     * belong outside the slot and inside the gap it left for them.
     */
    public static List<Slot> controlSlots(Line line, Slot row) {
        Objects.requireNonNull(line, "line");
        Objects.requireNonNull(row, "row");
        if (line.controls().isEmpty()) {
            return List.of();
        }
        int total = 0;
        for (Control control : line.controls()) {
            total += control.width() + CONTROL_GAP;
        }
        total -= CONTROL_GAP;
        // **Plus** the inset: the row's right inset reserved this room *outside* the narrowed slot, so
        // the strip starts at the slot's right edge and runs into that reservation. The first version
        // subtracted, which put every switch, button and pencil inside the text area -- the arithmetic
        // wrote the sentence above and then did the opposite of it.
        int x = row.right() + CONTROL_INSET;
        List<Slot> out = new ArrayList<>(line.controls().size());
        for (Control control : line.controls()) {
            out.add(new Slot(control.key(), x, row.y(), control.width(), row.height()));
            x += control.width() + CONTROL_GAP;
        }
        return List.copyOf(out);
    }

    /**
     * The two column widths of a body, left then right.
     *
     * <p>One function rather than two, because the two must add up: a caller computing "the right
     * column is what is left" twice is the arithmetic that agrees until a gap or a share changes.
     */
    public static int leftWidth(int bodyWidth) {
        // The gap is taken out first, so the share is of the space the columns actually get: 55% of
        // the body would leave the two columns and their gap overflowing by a fraction of it.
        return Math.max(0, bodyWidth - COLUMN_GAP) * LEFT_SHARE / 100;
    }

    public static int rightWidth(int bodyWidth) {
        return Math.max(0, bodyWidth - COLUMN_GAP - leftWidth(bodyWidth));
    }

    // ------------------------------------------------------------------
    // The faces
    // ------------------------------------------------------------------

    /**
     * The notice: parties are unavailable in singleplayer with LAN closed.
     *
     * <p>Two lines, and the second is the one that changes the state — the same argument the old
     * empty state's second line made. A greyed-out button would leave a player with the question
     * "why" and no way to have it answered.
     *
     * <h2>The words arrive resolved, because they have to be wrapped</h2>
     *
     * <p>Every other face carries translation keys and the screen substitutes them at draw time. This
     * one cannot: the wrap depends on the sentences themselves, so the caller resolves them from the
     * language file and hands them over with the width the card will actually have.
     */
    public static Face notice(String title, String why, String how, int width, Measure measure) {
        Objects.requireNonNull(measure, "measure");
        List<Line> lines = new ArrayList<>();
        lines.add(Line.header("party:notice:title", title));
        lines.add(Line.spacer("party:notice:gap0"));
        // One row per wrapped line, so a sentence survives a narrow window instead of being cut off.
        // The heights come from the wrap itself: a long "why" takes two rows and the card is built
        // from the rows, which is why the caller wraps at the width the card will actually have.
        int[] index = {0};
        for (String line : TextWrap.wrap(why, Math.max(0, width), measure)) {
            lines.add(Line.prose("party:notice:why:" + index[0]++, line));
        }
        lines.add(Line.spacer("party:notice:gap1"));
        index[0] = 0;
        for (String line : TextWrap.wrap(how, Math.max(0, width), measure)) {
            lines.add(Line.prose("party:notice:fix:" + index[0]++, line));
        }
        return face(lines);
    }

    /**
     * The solo face's left column: start a party, and answer what is waiting.
     *
     * <h2>The create card: a heading, then the field</h2>
     *
     * <p>The heading says "Start a party" and the row below it is the editable name — the screen
     * draws the field in that row's text slot, prefilled from the player's own name. The heading is
     * what distinguishes a form from a list entry; see the note at the row itself for the report that
     * made that concrete.
     */
    public static Face soloLeft(PartyRoster roster, PartySnapshot snapshot, String self, String createName) {
        return soloLeft(roster, snapshot, self, createName, Integer.MAX_VALUE);
    }

    /**
     * The solo face's left column, with a body width for the create row's wrap decision.
     *
     * <p>Wide bodies keep the name field and its Create button on one row; narrow ones (below
     * {@link #NARROW_WRAP_WIDTH}) put the field on its own row and the button below it, so a wider
     * Create button never squeezes the field into nothing. The stacked single column is the spec —
     * the old 55/45 side-by-side split survives only as deprecated {@link #leftWidth} arithmetic.
     */
    public static Face soloLeft(PartyRoster roster, PartySnapshot snapshot, String self, String createName,
                                int bodyWidth) {
        Objects.requireNonNull(snapshot, "snapshot");
        List<Line> lines = new ArrayList<>();
        lines.add(Line.plain(TITLE, "Parties"));
        lines.add(Line.prose("solo:subtitle", "tenet.screen.party.solo.subtitle"));

        // A heading before the field, and it is not decoration: without it the row is a text box
        // holding a party-shaped name and a button, which reads as *the party you are in* rather than
        // as a form for a new one -- a player who has just disbanded saw their old party's name in the
        // box and understood the party to still exist. The heading is the spec's own "top card
        // (Create)" and it is what makes the row say what it does.
        lines.add(Line.header("solo:create:heading", "tenet.screen.party.create.heading"));
        String createCommand = "/tenet party create " + (createName == null ? "" : createName);
        if (bodyWidth < NARROW_WRAP_WIDTH) {
            lines.add(Line.plain("solo:create", createName == null ? "" : createName));
            lines.add(Line.controls("solo:create:button", "",
                    Control.primary("solo:create:go", createCommand)));
        }
        else {
            lines.add(Line.controls("solo:create", createName == null ? "" : createName,
                    Control.primary("solo:create:go", createCommand)));
        }
        lines.add(Line.prose("solo:create:hint", "tenet.screen.party.create.hint"));

        lines.add(Line.rule("rule:solo:0"));
        lines.add(Line.header("solo:incoming", "tenet.screen.party.section.incoming"));
        if (snapshot.invites().isEmpty()) {
            lines.add(Line.plain("solo:incoming:none", "tenet.screen.party.section.incoming.none"));
            lines.add(Line.prose("solo:incoming:none:hint", "tenet.screen.party.section.incoming.none.hint"));
        }
        else {
            for (PartySnapshot.Invite invite : snapshot.invites()) {
                // The inviter's name is in the detail rather than the label, because the label is the
                // party and the question "which party" comes before "who asked".
                String sender = invite.inviterName().isEmpty() ? "someone" : invite.inviterName();
                String when = relativeTime(invite.age());
                lines.add(Line.controls("invite:" + invite.teamId(), invite.teamName(),
                        when.isEmpty() ? sender : sender + " - " + when,
                        Control.primary("accept:" + invite.teamId(), "/tenet party accept " + invite.teamId()),
                        Control.small("decline:" + invite.teamId(), "/tenet party decline " + invite.teamId())));
            }
        }
        return face(lines);
    }

    /** The solo face's right column: the open parties anyone may join. */
    public static Face soloRight(PartySnapshot snapshot) {
        List<Line> lines = new ArrayList<>();
        lines.add(Line.rule("rule:solo:1"));
        lines.add(Line.header("solo:public", "tenet.screen.party.section.public"));
        if (snapshot.publicParties().isEmpty()) {
            lines.add(Line.plain("solo:public:none", "tenet.screen.party.section.public.none"));
            lines.add(Line.prose("solo:public:none:hint", "tenet.screen.party.section.public.none.hint"));
        }
        else {
            for (PartySnapshot.PublicParty party : snapshot.publicParties()) {
                String count = party.limit() > 0
                        ? party.members() + "/" + party.limit()
                        : String.valueOf(party.members());
                lines.add(Line.controls("public:" + party.teamId(), party.name(), count,
                        Control.button("join:" + party.teamId(), "/tenet party join " + party.teamId())));
            }
        }
        return face(lines);
    }

    /**
     * The active face's left column: the identity and the roster.
     *
     * <p>The name row carries a visible {@code Rename} control for the owner — the permission is
     * asked here via {@code PartyRoster.canRename} rather than at the screen, because "may I rename"
     * is the roster's answer and a panel that decided for itself would draw a button the server
     * refuses. The old invisible click-on-name overlay is gone: an affordance nobody can see is not
     * an affordance.
     */
    public static Face activeLeft(PartyRoster roster, PartySnapshot snapshot) {
        Objects.requireNonNull(roster, "roster");
        List<Line> lines = new ArrayList<>();

        String name = snapshot.teamName().isEmpty() ? "tenet.screen.party.unnamed" : snapshot.teamName();
        if (roster.canRename()) {
            lines.add(Line.controls("left:name", name,
                    Control.small("left:name:rename", null)));
        }
        else {
            lines.add(Line.plain("left:name", name));
        }

        int online = 0;
        for (PartyRoster.Member member : roster.members()) {
            if (member.online()) {
                online++;
            }
        }
        lines.add(Line.detail("left:stats", members(roster.memberCount(), roster.memberLimit()),
                online(online)));

        // The roster's own composition, nested in whole -- the member rows and their reserved action
        // strip -- under the identity rows. It is appended rather than listed as `Line`s because a
        // member row is `PartyRoster`'s: a portrait, a presence marker and a name, whose rules about
        // order and authority Armature owns and tests. This face composes around it, which is exactly
        // the split `PartyRoster`'s own note argues for.
        Stack stack = stack(lines).gap(ROW_GAP).append(roster.composition());
        return new Face(stack, lines);
    }

    /** The active face's right column: the settings, then the people who could be invited. */
    public static Face activeRight(PartyRoster roster, PartySnapshot snapshot, String self, String query) {
        Objects.requireNonNull(snapshot, "snapshot");
        Objects.requireNonNull(roster, "roster");
        List<Line> lines = new ArrayList<>();

        lines.add(Line.rule("rule:active:0"));
        lines.add(Line.header("right:settings", "tenet.screen.party.section.settings"));
        lines.add(Line.controls("right:member-invites", "tenet.screen.party.setting.member_invites",
                roster.membersCanInvite() ? "tenet.screen.on" : "tenet.screen.off",
                Control.toggle("right:member-invites:switch")));
        lines.add(Line.prose("right:member-invites:hint", "tenet.screen.party.setting.member_invites.hint"));
        lines.add(Line.controls("right:open", "tenet.screen.party.setting.open",
                roster.openJoin() ? "tenet.screen.on" : "tenet.screen.off",
                Control.toggle("right:open:switch")));
        lines.add(Line.prose("right:open:hint", "tenet.screen.party.setting.open.hint"));
        if (!roster.canSetPolicy()) {
            lines.add(Line.prose("right:settings:owner",
                    "tenet.screen.party.setting.owner_only"));
        }

        lines.add(Line.rule("rule:active:1"));
        lines.add(Line.header("right:invite", "tenet.screen.party.section.invite"));
        lines.add(Line.plain("right:search", ""));
        lines.add(Line.prose("right:search:hint", "tenet.screen.party.invite.search_hint"));

        List<String> invitable = invitable(snapshot, roster, self, query);
        if (invitable.isEmpty()) {
            // Two different facts, and the message must say which: nobody else is on the server, or
            // the search hid them. Asking the *unfiltered* list which it is -- the first version
            // compared the online list instead, and told a player alone on their server "Nobody
            // matches", as though a filter were responsible.
            boolean anyone = !invitable(snapshot, roster, self, "").isEmpty();
            boolean filtered = query != null && !query.trim().isEmpty();
            if (anyone && filtered) {
                lines.add(Line.plain("right:invite:none",
                        "tenet.screen.party.invite.none.filtered" + ARG_SEP + query.trim()));
            }
            else {
                lines.add(Line.plain("right:invite:none", anyone
                        ? "tenet.screen.party.invite.none.filtered"
                        : "tenet.screen.party.invite.none.empty"));
            }
            if (!anyone) {
                lines.add(Line.prose("right:invite:none:hint", "tenet.screen.party.invite.none.empty.hint"));
            }
        }
        else {
            for (String name : invitable) {
                lines.add(Line.controls("invite:" + name, name,
                        Control.button("invite-go:" + name, "/tenet party invite " + name)));
            }
        }

        lines.add(Line.rule("rule:active:2"));
        lines.add(Line.header("right:outgoing", "tenet.screen.party.section.outgoing"));
        if (snapshot.sent().isEmpty()) {
            lines.add(Line.plain("right:outgoing:none", "tenet.screen.party.outgoing.none"));
        }
        else {
            for (PartySnapshot.SentInvite invite : snapshot.sent()) {
                String when = relativeTime(invite.age());
                lines.add(Line.controls("sent:" + invite.name(), outgoingLabel(invite.name()), when,
                        Control.small("cancel:" + invite.name(), "/tenet party uninvite " + invite.name())));
            }
        }
        return face(lines);
    }

    /**
     * The invitation list: everybody online who is not the viewer and not already in the party,
     * filtered by the search box.
     *
     * <p>A pure function of the snapshot, the roster and the query, so a test can ask what a party of
     * three with four players online offers — and so the screen cannot accidentally offer the viewer
     * an invitation to their own party, which is what the old panel did until a preview showed it.
     * Players already invited are excluded: their row is in the outgoing list with a Cancel, and a
     * second Invite control could only be refused.
     */
    public static List<String> invitable(PartySnapshot snapshot, PartyRoster roster, String self,
                                         String query) {
        String me = self == null ? "" : self;
        String needle = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        List<String> out = new ArrayList<>();
        for (String name : snapshot.online()) {
            if (name.equalsIgnoreCase(me) || isMember(roster, name) || isSent(snapshot, name)) {
                continue;
            }
            if (!needle.isEmpty() && !name.toLowerCase(Locale.ROOT).contains(needle)) {
                continue;
            }
            out.add(name);
        }
        return List.copyOf(out);
    }

    /**
     * A small card for a question: a title, a line of body text, and the controls that answer it.
     *
     * <p>Used for the transfer confirmation, the successor picker's frame and the disband prompt.
     * One builder rather than three near-identical ones — the difference between them is the words
     * and which controls answer, which is exactly what the arguments are.
     */
    public static Face confirm(String title, String body, Control... controls) {
        List<Line> lines = new ArrayList<>();
        lines.add(Line.plain("confirm:title", title));
        if (body != null && !body.isEmpty()) {
            lines.add(Line.plain("confirm:body", body));
        }
        lines.add(Line.controls("confirm:actions", "", controls));
        return face(lines);
    }

    // ------------------------------------------------------------------
    // Small pure answers
    // ------------------------------------------------------------------

    /**
     * The member count, with the cap when one is known.
     *
     * <p>{@code 3/8} where the source states a limit and {@code 3 members} where it does not — zero is
     * the manager's "cannot say", and inventing a cap for a foreign source would be a header that
     * disagrees with the server's own refusal. See {@code PartyRoster.hasMemberLimit}.
     */
    public static String members(int count, int limit) {
        return limit > 0 ? count + "/" + limit : count + " members";
    }

    /**
     * The online count for the stats line's detail slot.
     *
     * <p>Split from the label so the row says the count once ({@code 1/8}) and the presence once
     * ({@code 1 online}) instead of the old {@code Members - 1/8} label that repeated the number.
     */
    public static String online(int count) {
        return count == 1 ? "1 online" : count + " online";
    }

    /** Translation key for an outgoing invite row; the name travels after a {@code \0} separator. */
    public static final String OUTGOING_ROW_KEY = "tenet.screen.party.outgoing.row";

    /** Separator between a translatable row key and its format arg (a player name). */
    public static final String ARG_SEP = "\0";

    /**
     * An outgoing invite row's label: the translatable key plus the invited name.
     *
     * <p>The screen splits on {@link #ARG_SEP} and resolves via {@code Component.translatable(key,
     * name)} so translators can reorder {@code Invited %s}. A raw {@code Invited + name}
     * concatenation froze English word order.
     */
    public static String outgoingLabel(String name) {
        return OUTGOING_ROW_KEY + ARG_SEP + (name == null ? "" : name);
    }

    /**
     * The smallest button width that fits {@code label} without truncation.
     *
     * <p>{@code min} is the floor (e.g. {@link #ROW_BUTTON} for ordinary buttons) so a short label
     * like {@code Join} does not get a jittery per-row width while a long one like
     * {@code Create Party} grows past the floor instead of truncating to {@code Create P...}.
     */
    public static int buttonWidth(String label, Measure measure, int min) {
        Objects.requireNonNull(label, "label");
        Objects.requireNonNull(measure, "measure");
        return Math.max(min, measure.width(label) + BUTTON_PAD);
    }

    /**
     * How long ago something happened, coarsely, or empty when the source did not say.
     *
     * <p>Coarse on purpose: an invitation's age is read to answer "is this stale", and seconds of
     * precision would be a number that changes between two frames for no one's benefit. The input is
     * an <b>age</b> in game ticks, counted on the server's clock when the snapshot was built — see
     * {@code PartySnapshot.Invite} on why a timestamp could not travel — and the answer is one of a
     * handful of words.
     */
    public static String relativeTime(long ageTicks) {
        if (ageTicks <= 0L) {
            return "";
        }
        long minutes = ageTicks / 20L / 60L;
        if (minutes < 1L) {
            return "just now";
        }
        if (minutes < 60L) {
            return minutes + "m ago";
        }
        long hours = minutes / 60L;
        if (hours < 24L) {
            return hours + "h ago";
        }
        return (hours / 24L) + "d ago";
    }

    private static boolean isMember(PartyRoster roster, String name) {
        for (PartyRoster.Member member : roster.members()) {
            if (member.name().equalsIgnoreCase(name)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isSent(PartySnapshot snapshot, String name) {
        for (PartySnapshot.SentInvite invite : snapshot.sent()) {
            if (invite.name().equalsIgnoreCase(name)) {
                return true;
            }
        }
        return false;
    }
}
