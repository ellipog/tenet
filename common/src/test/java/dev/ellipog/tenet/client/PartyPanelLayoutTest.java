package dev.ellipog.tenet.client;

import dev.ellipog.armature.api.teams.Team;
import dev.ellipog.armature.api.teams.TeamPolicy;
import dev.ellipog.armature.api.teams.TeamRole;
import dev.ellipog.armature.client.ui.kit.Layout;
import dev.ellipog.armature.client.ui.kit.Measure;
import dev.ellipog.armature.client.ui.kit.Slot;
import dev.ellipog.armature.client.ui.party.PartyRoster;
import dev.ellipog.tenet.net.PartySnapshot;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The party panel's faces: what each one says, and where every control goes.
 *
 * <h2>What can be asserted here, and why</h2>
 *
 * <p>Every field of {@link PartyPanelLayout} is a record, an id, a string or a number, so the
 * questions that matter — does a solo player get a Create control and an owner a rename pencil, is
 * every control inside the row that reserved room for it, do the two columns add up to the body — are
 * answerable without a window. A screen cannot be asked anything; a layout can, which is the whole
 * reason it is a separate class.
 */
@DisplayName("The party panel: faces, rows and control placement")
class PartyPanelLayoutTest {

    private static final Measure MEASURE = Measure.monospace(6, 9);
    private static final int WIDTH = 240;

    private static final UUID OWNER = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID MEMBER = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final UUID OTHER = UUID.fromString("00000000-0000-0000-0000-000000000003");
    private static final UUID VIEWER = MEMBER;

    private static final Map<UUID, String> NAMES = new HashMap<>();

    static {
        NAMES.put(OWNER, "Ada");
        NAMES.put(MEMBER, "Cy");
        NAMES.put(OTHER, "Dee");
    }

    private static String nameOf(UUID id) {
        return NAMES.getOrDefault(id, id.toString());
    }

    private static Team party() {
        return Team.created(UUID.randomUUID(), "the crew", OWNER, 0L)
                .withMember(MEMBER, TeamRole.MEMBER);
    }

    private static PartyRoster roster() {
        return PartyRoster.of(party(), VIEWER, PartyPanelLayoutTest::nameOf, id -> true);
    }

    private static PartyRoster rosterWithoutRename() {
        // A non-owner viewing: canRename false, so the pencil is absent -- the one thing the active
        // face's left column decides from the roster rather than from the snapshot.
        return PartyRoster.of(party(), MEMBER, PartyPanelLayoutTest::nameOf, id -> true);
    }

    private static PartySnapshot snapshot() {
        UUID id = UUID.randomUUID();
        return new PartySnapshot(id, "the crew", OWNER,
                List.of(new PartySnapshot.Member(OWNER, "Ada", TeamRole.OWNER),
                        new PartySnapshot.Member(MEMBER, "Cy", TeamRole.MEMBER)),
                List.of(), List.of("Ada", "Cy", "Dee"), "one_member", List.of(OWNER, MEMBER),
                List.of(), TeamPolicy.DEFAULT, 8, List.of());
    }

    private static PartySnapshot soloSnapshot() {
        UUID id = UUID.randomUUID();
        return new PartySnapshot(new UUID(0L, 0L), "", new UUID(0L, 0L), List.of(),
                List.of(new PartySnapshot.Invite(id, "another party", OWNER, "Ada", 1300L)),
                List.of("Ada", "Cy", "Dee"), "one_member", List.of(),
                List.of(), TeamPolicy.DEFAULT, 0,
                List.of(new PartySnapshot.PublicParty(id, "open crew", 3, 8)));
    }

    // ------------------------------------------------------------------
    // The faces
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("the notice, for singleplayer with LAN closed")
    class Notice {

        @Test
        @DisplayName("says what is unavailable and the one action that changes it")
        void noticeSaysWhy() {
            PartyPanelLayout.Face face = PartyPanelLayout.notice("Parties Unavailable",
                    "Parties are disabled in singleplayer.",
                    "Open the world to LAN to invite local players.", 288, MEASURE);

            assertEquals(5, face.lines().size(),
                    "a title, a spacer, one line of why, a spacer and one line of how at this width");
            assertTrue(face.lines().stream().anyMatch(line -> line.label().contains("LAN")),
                    "the last line names the action, which is the whole reason this is a card "
                            + "rather than a greyed-out button");
            assertEquals("party:notice:title", face.lines().get(0).key());
            assertTrue(face.lines().get(0).header(), "and the title is drawn as a heading");
        }

        @Test
        @DisplayName("at a narrow width the sentences wrap instead of being truncated")
        void noticeWraps() {
            // The report: at a small window the notice read "Parties are disabled in singlep" -- one
            // truncated row per sentence. Wrapped, each row is a whole piece of a sentence and every
            // piece fits the width it was wrapped at.
            int width = 160;
            PartyPanelLayout.Face face = PartyPanelLayout.notice("Parties Unavailable",
                    "Parties are disabled in singleplayer.",
                    "Open the world to LAN to invite local players.", width, MEASURE);

            List<PartyPanelLayout.Line> why = face.lines().stream()
                    .filter(line -> line.key().startsWith("party:notice:why:")).toList();
            assertTrue(why.size() >= 2,
                    "the sentence does not fit one 160-pixel row, so it must take more than one");
            for (PartyPanelLayout.Line line : face.lines()) {
                if (!line.label().isEmpty()) {
                    assertTrue(MEASURE.width(line.label()) <= width,
                            () -> "a wrapped line is wider than the width it was wrapped at: "
                                    + line.label());
                }
            }
        }
    }

    @Nested
    @DisplayName("the solo onboarding")
    class Solo {

        @Test
        @DisplayName("offers Create with the name as the row's label, and the command carrying it")
        void createRow() {
            PartyPanelLayout.Face left =
                    PartyPanelLayout.soloLeft(roster(), soloSnapshot(), "Cy", "Cy party");

            PartyPanelLayout.Line create = left.line("solo:create");
            assertNotNull(create, "the create row is what the face exists for");
            assertEquals("tenet.screen.party.create.heading", left.line("solo:create:heading").label(),
                    "the heading above the field says this is a new party, not one that exists -- the "
                            + "report was a disbanded party's name left in the box, read as the party "
                            + "still being there");
            assertTrue(left.line("solo:create:heading").header(), "drawn as a section heading");
            assertEquals("Cy party", create.label(),
                    "the field's text is the row's label, so the screen draws one thing in that row");
            assertEquals(1, create.controls().size());
            assertEquals("/tenet party create Cy party", create.controls().get(0).command());
            assertTrue(create.controls().get(0).accent(),
                    "the one primary control on the face");
        }

        @Test
        @DisplayName("one invitation row per invite, with Accept and Decline and who asked")
        void inviteRows() {
            PartySnapshot snapshot = soloSnapshot();
            UUID invitedTo = snapshot.invites().get(0).teamId();
            PartyPanelLayout.Face left = PartyPanelLayout.soloLeft(roster(), snapshot, "Cy", "Cy party");

            PartyPanelLayout.Line invite = left.lines().stream()
                    .filter(line -> line.key().startsWith("invite:"))
                    .findFirst().orElseThrow();
            assertEquals("another party", invite.label());
            assertTrue(invite.detail().contains("Ada"),
                    "the inviter's name is on the row: an invitation's sender is what makes it a "
                            + "social decision rather than a queue entry. It said: " + invite.detail());
            assertEquals(2, invite.controls().size(), "Accept and Decline, in that order");
            assertEquals("/tenet party accept " + invitedTo, invite.controls().get(0).command(),
                    "the accept names the party it is for -- a player can hold two invitations, and "
                            + "the button must mean the row it is on");
            assertEquals("/tenet party decline " + invitedTo, invite.controls().get(1).command());
        }

        @Test
        @DisplayName("says so when nothing is waiting, rather than drawing an empty gap")
        void noInvites() {
            PartySnapshot bare = new PartySnapshot(new UUID(0L, 0L), "", new UUID(0L, 0L), List.of(),
                    List.of(), List.of(), "one_member", List.of(), List.of(), TeamPolicy.DEFAULT, 0,
                    List.of());

            PartyPanelLayout.Face left = PartyPanelLayout.soloLeft(roster(), bare, "Cy", "Cy party");

            assertNotNull(left.line("solo:incoming:none"), "the left column says so");
            assertNotNull(PartyPanelLayout.soloRight(bare).line("solo:public:none"),
                    "and the right column, for its own list");
        }

        @Test
        @DisplayName("the public column lists open parties with their count and a Join control")
        void publicRows() {
            PartyPanelLayout.Face right = PartyPanelLayout.soloRight(soloSnapshot());

            PartyPanelLayout.Line row = right.lines().stream()
                    .filter(line -> line.key().startsWith("public:"))
                    .findFirst().orElseThrow();
            assertEquals("open crew", row.label());
            assertEquals("3/8", row.detail(), "the count the header promises");
            assertTrue(row.controls().get(0).command().startsWith("/tenet party join "));
        }
    }

    @Nested
    @DisplayName("the active party")
    class Active {

        @Test
        @DisplayName("the name row carries a visible Rename control for the owner only")
        void renameIsTheRostersAnswer() {
            // The rename control is a visible small button on the name row (owner only) — the old
            // invisible click-on-name overlay is gone because an affordance nobody can see is not one.
            PartyRoster owner = PartyRoster.of(party(), OWNER, PartyPanelLayoutTest::nameOf, id -> true);
            PartyRoster member = rosterWithoutRename();

            PartyPanelLayout.Face asOwner = PartyPanelLayout.activeLeft(owner, snapshot());
            PartyPanelLayout.Face asMember = PartyPanelLayout.activeLeft(member, snapshot());

            assertEquals(1, asOwner.line("left:name").controls().size(),
                    "one visible Rename control beside the name");
            assertTrue(asOwner.line("left:name").controls().get(0).key().endsWith(":rename"));
            assertEquals("the crew", asOwner.line("left:name").label());
            assertTrue(owner.canRename(), "the owner may rename, and the layout says so");
            assertEquals(0, asMember.line("left:name").controls().size(),
                    "a member gets no rename control");
            assertFalse(member.canRename(), "a member may not, and gets no button");
            assertEquals("the crew", asMember.line("left:name").label(),
                    "a member still reads the name");
        }

        @Test
        @DisplayName("the stats line counts the members online, and the cap when there is one")
        void statsLine() {
            // The roster the panel really draws comes from the wire (`toRoster`), which is the only
            // path that carries the member limit -- `of` takes a live team, and a team has no limit to
            // read. So the fixture goes through the same conversion the client does.
            PartyRoster roster = PartySnapshot.toRoster(snapshot(), OWNER);

            PartyPanelLayout.Face left = PartyPanelLayout.activeLeft(roster, snapshot());

            assertEquals("2/8", left.line("left:stats").label(),
                    "the count once, without the redundant Members - prefix");
            assertEquals("2 online", left.line("left:stats").detail(),
                    "both members are in the snapshot's present list -- the number the header "
                            + "highlights");
        }

        @Test
        @DisplayName("the right column has both switches, whichever the server can express")
        void switches() {
            PartyPanelLayout.Face right =
                    PartyPanelLayout.activeRight(roster(), snapshot(), "Ada", "");

            PartyPanelLayout.Control memberInvites =
                    right.line("right:member-invites").controls().get(0);
            PartyPanelLayout.Control open = right.line("right:open").controls().get(0);
            assertTrue(memberInvites.isToggle(), "a state, not an action -- a switch");
            assertTrue(open.isToggle());
            assertFalse(memberInvites.sendsCommand());
            assertNotNull(right.line("right:member-invites").detail(),
                    "the state word (On/Off) travels in the detail slot");
            assertNotNull(right.line("right:open").detail());
        }

        @Test
        @DisplayName("the inviter list excludes the viewer, members and the already-invited")
        void invitable() {
            PartySnapshot sent = new PartySnapshot(snapshot().teamId(), "the crew", OWNER,
                    snapshot().members(), List.of(), List.of("Ada", "Cy", "Dee", "Eve"), "one_member",
                    List.of(), List.of(new PartySnapshot.SentInvite("Eve", 0L)), TeamPolicy.DEFAULT, 8,
                    List.of());

            List<String> names = PartyPanelLayout.invitable(sent, roster(), "Ada", "");

            assertEquals(List.of("Dee"), names,
                    "Ada is the viewer, Cy is a member, Eve is already invited -- each of those rows "
                            + "could only be refused, and Eve's Cancel is in the outgoing list");
        }

        @Test
        @DisplayName("the search filters by substring, case-insensitively")
        void searchFilters() {
            PartySnapshot many = new PartySnapshot(snapshot().teamId(), "the crew", OWNER,
                    snapshot().members(), List.of(), List.of("Ada", "Cy", "Dee", "Dana"), "one_member",
                    List.of(), List.of(), TeamPolicy.DEFAULT, 8, List.of());

            PartyPanelLayout.Face right =
                    PartyPanelLayout.activeRight(roster(), many, "Ada", "DA");

            List<String> inviter = right.lines().stream()
                    .filter(line -> line.key().startsWith("invite:"))
                    .map(PartyPanelLayout.Line::label)
                    .toList();
            assertEquals(List.of("Dana"), inviter,
                    "matched case-insensitively, and only the substring: 'Dee' does not contain 'da'");
            assertNull(right.line("right:invite:none"), "a match means no 'nobody matches' line");
        }

        @Test
        @DisplayName("the empty inviter line says which emptiness it is")
        void inviterEmptyMessages() {
            PartySnapshot alone = new PartySnapshot(snapshot().teamId(), "the crew", OWNER,
                    snapshot().members(), List.of(), List.of("Ada"), "one_member", List.of(),
                    List.of(), TeamPolicy.DEFAULT, 8, List.of());

            assertEquals("tenet.screen.party.invite.none.empty",
                    PartyPanelLayout.activeRight(roster(), alone, "Ada", "").line("right:invite:none").label(),
                    "nobody else is on the server, so the line says that");

            PartySnapshot many = new PartySnapshot(snapshot().teamId(), "the crew", OWNER,
                    snapshot().members(), List.of(), List.of("Ada", "Cy", "Dee"), "one_member",
                    List.of(), List.of(), TeamPolicy.DEFAULT, 8, List.of());
            String filtered = PartyPanelLayout.activeRight(roster(), many, "Ada", "zz")
                    .line("right:invite:none").label();
            assertTrue(filtered.startsWith("tenet.screen.party.invite.none.filtered"),
                    "and a search that hid everybody says the filter is responsible, not the server: "
                            + filtered);
            assertTrue(filtered.contains("zz"),
                    "the query travels with the key so translators can show what was filtered: "
                            + filtered);
        }

        @Test
        @DisplayName("the outgoing list names the invited and offers a Cancel")
        void outgoing() {
            PartySnapshot sent = new PartySnapshot(snapshot().teamId(), "the crew", OWNER,
                    snapshot().members(), List.of(), List.of("Ada", "Cy"), "one_member", List.of(),
                    List.of(new PartySnapshot.SentInvite("Dee", 400L)), TeamPolicy.DEFAULT, 8, List.of());

            PartyPanelLayout.Face right = PartyPanelLayout.activeRight(roster(), sent, "Ada", "");

            PartyPanelLayout.Line row = right.line("sent:Dee");
            assertEquals(PartyPanelLayout.outgoingLabel("Dee"), row.label(),
                    "the translatable key plus the name, so translators can reorder Invited %s");
            assertNotNull(row.detail(), "and how long ago, which travels as an age");
            assertEquals("/tenet party uninvite Dee", row.controls().get(0).command());
            assertNull(right.line("solo:public"),
                    "and a member's face never draws the browse list");
        }
    }

    @Nested
    @DisplayName("the confirmations")
    class Confirmations {

        @Test
        @DisplayName("a question is a title, a body and the controls that answer it")
        void confirmFace() {
            PartyPanelLayout.Face face = PartyPanelLayout.confirm("Hand it over?",
                    "Dee becomes the owner.",
                    PartyPanelLayout.Control.primary("phase:confirm", "/tenet party transfer Dee"),
                    PartyPanelLayout.Control.small("phase:cancel", null));

            assertEquals("Hand it over?", face.line("confirm:title").label());
            List<PartyPanelLayout.Control> controls = face.line("confirm:actions").controls();
            assertEquals(2, controls.size());
            assertTrue(controls.get(1).command() == null,
                    "Cancel is answered by the screen: there is no command for 'never mind'");
        }
    }

    // ------------------------------------------------------------------
    // Laying out
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("placement")
    class Placement {

        @Test
        @DisplayName("every control sits inside the row that reserved room for it")
        void controlsInsideTheirRows() {
            for (PartyPanelLayout.Face face : List.of(
                    PartyPanelLayout.soloLeft(roster(), soloSnapshot(), "Cy", "Cy party"),
                    PartyPanelLayout.soloRight(soloSnapshot()),
                    PartyPanelLayout.activeRight(roster(), snapshot(), "Ada", ""))) {
                Layout layout = face.build(WIDTH, MEASURE);
                for (PartyPanelLayout.Line line : face.lines()) {
                    Slot row = layout.slot(line.key());
                    assertNotNull(row, () -> "no slot for " + line.key());
                    List<Slot> controls = PartyPanelLayout.controlSlots(line, row);
                    for (Slot control : controls) {
                        // The row's inset reserved the strip *outside* the narrowed slot, so a control
                        // lives in [row.right(), row.right() + controlRoom()] -- the rule the layout's
                        // own doc states, and the one the first implementation did the opposite of.
                        assertTrue(control.x() >= row.right(),
                                () -> control.key() + " starts before its reserved strip: "
                                        + control.x() + " < " + row.right());
                        assertTrue(control.right() <= row.right() + line.controlRoom(),
                                () -> control.key() + " runs past its reserved strip: " + control.right()
                                        + " > " + (row.right() + line.controlRoom()));
                        assertTrue(control.width() >= 0);
                    }
                }
            }
        }

        @Test
        @DisplayName("the text room is the row minus exactly the controls' strip")
        void textRoomIsTheComplement() {
            PartyPanelLayout.Face face =
                    PartyPanelLayout.activeRight(roster(), snapshot(), "Ada", "");
            Layout layout = face.build(WIDTH, MEASURE);
            PartyPanelLayout.Line line = face.line("right:member-invites");
            Slot row = layout.slot(line.key());

            Slot text = PartyPanelLayout.textSlot(line, row);

            assertEquals(row.x(), text.x());
            assertEquals(row.width() - line.controlRoom(), text.width(),
                    "a label and the control beside it come from one row's own arithmetic");
        }

        @Test
        @DisplayName("the two columns and their gap are exactly the body")
        void columnsAddUp() {
            for (int body : new int[] {PartyPanelLayout.COLUMN_GAP, 47, 100, 320, 496}) {
                int left = PartyPanelLayout.leftWidth(body);
                int right = PartyPanelLayout.rightWidth(body);
                assertEquals(body, left + PartyPanelLayout.COLUMN_GAP + right,
                        () -> "a body of " + body + " lost or gained a pixel: " + left + "+"
                                + PartyPanelLayout.COLUMN_GAP + "+" + right);
                assertTrue(left >= 0 && right >= 0);
            }
            // A body narrower than the gap itself: the columns are empty rather than negative, which
            // is the only honest answer before there is room for two of anything.
            assertEquals(0, PartyPanelLayout.leftWidth(2));
            assertEquals(0, PartyPanelLayout.rightWidth(2));
        }

        @Test
        @DisplayName("rows do not overlap, and the layout is as tall as its lowest row")
        void rowsStack() {
            PartyPanelLayout.Face face =
                    PartyPanelLayout.activeRight(roster(), snapshot(), "Ada", "");
            Layout layout = face.build(WIDTH, MEASURE);

            int previousBottom = Integer.MIN_VALUE;
            for (Slot slot : layout.slots()) {
                assertTrue(slot.y() >= previousBottom, () -> "row " + slot.key() + " overlaps");
                previousBottom = slot.bottom();
            }
            assertEquals(previousBottom, layout.height(),
                    "the height is the bottom edge of the last row, which is what a scroll range "
                            + "is measured against");
        }
    }

    // ------------------------------------------------------------------
    // The small pure answers
    // ------------------------------------------------------------------

    @Test
    @DisplayName("the member count reads 3/8 with a limit and 3 members without one")
    void membersLine() {
        assertEquals("3/8", PartyPanelLayout.members(3, 8));
        assertEquals("3 members", PartyPanelLayout.members(3, 0),
                "zero is 'the source cannot say', and a made-up cap would disagree with the server");
    }

    @Test
    @DisplayName("the online count reads 1 online and N online")
    void onlineLine() {
        assertEquals("1 online", PartyPanelLayout.online(1));
        assertEquals("0 online", PartyPanelLayout.online(0));
        assertEquals("3 online", PartyPanelLayout.online(3));
    }

    @Test
    @DisplayName("a button fits its label plus padding, floored at the constant")
    void buttonWidthFits() {
        // The solo screenshot's fault: Create Party truncated to Create P... at width 58.
        assertTrue(PartyPanelLayout.ROW_BUTTON >= MEASURE.width("Create Party") + PartyPanelLayout.BUTTON_PAD,
                "ROW_BUTTON fits Create Party at the test measure");
        assertTrue(PartyPanelLayout.SHORT_BUTTON >= MEASURE.width("Decline") + PartyPanelLayout.BUTTON_PAD,
                "SHORT_BUTTON fits Decline at the test measure");
        assertEquals(PartyPanelLayout.ROW_BUTTON,
                PartyPanelLayout.buttonWidth("Join", MEASURE, PartyPanelLayout.ROW_BUTTON),
                "a short label keeps the floor rather than jittering per row");
        assertTrue(PartyPanelLayout.buttonWidth("Create Party", MEASURE, PartyPanelLayout.ROW_BUTTON)
                        >= MEASURE.width("Create Party") + PartyPanelLayout.BUTTON_PAD,
                "a long label grows past the floor instead of truncating");
    }

    @Test
    @DisplayName("the solo face has a subtitle, hints and rules")
    void soloGuidance() {
        PartySnapshot bare = new PartySnapshot(new UUID(0L, 0L), "", new UUID(0L, 0L), List.of(),
                List.of(), List.of(), "one_member", List.of(), List.of(), TeamPolicy.DEFAULT, 0,
                List.of());
        PartyPanelLayout.Face left = PartyPanelLayout.soloLeft(roster(), bare, "Cy", "Cy party");
        assertNotNull(left.line("solo:subtitle"), "what to do when both lists are empty");
        assertNotNull(left.line("solo:create:hint"), "the name rule under the field");
        assertNotNull(left.line("solo:incoming:none:hint"), "what to do with no invites");
        assertTrue(left.lines().stream().anyMatch(PartyPanelLayout.Line::isRule),
                "a rule separates the sections");
        PartyPanelLayout.Face right = PartyPanelLayout.soloRight(bare);
        assertNotNull(right.line("solo:public:none:hint"), "what a public party is");
    }

    @Test
    @DisplayName("the create row wraps below the narrow width")
    void createWrapsWhenNarrow() {
        PartyPanelLayout.Face wide = PartyPanelLayout.soloLeft(roster(), soloSnapshot(), "Cy", "Cy party",
                PartyPanelLayout.NARROW_WRAP_WIDTH + 100);
        assertNotNull(wide.line("solo:create"), "field and button share one row when wide");
        assertNull(wide.line("solo:create:button"), "no second row when wide");

        PartyPanelLayout.Face narrow = PartyPanelLayout.soloLeft(roster(), soloSnapshot(), "Cy", "Cy party",
                PartyPanelLayout.NARROW_WRAP_WIDTH - 1);
        assertNotNull(narrow.line("solo:create"), "the field keeps its own row when narrow");
        assertNotNull(narrow.line("solo:create:button"), "the button drops below it when narrow");
    }

    @Test
    @DisplayName("an age is coarse: minutes, then hours, then days, and empty when unknown")
    void ages() {
        assertEquals("", PartyPanelLayout.relativeTime(0L), "the source did not say");
        assertEquals("just now", PartyPanelLayout.relativeTime(200L), "under a minute");
        assertEquals("1m ago", PartyPanelLayout.relativeTime(20L * 60L));
        assertEquals("2h ago", PartyPanelLayout.relativeTime(20L * 60L * 120L));
        assertEquals("3d ago", PartyPanelLayout.relativeTime(20L * 60L * 60L * 72L));
    }
}
