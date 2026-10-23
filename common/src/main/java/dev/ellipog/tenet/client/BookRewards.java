package dev.ellipog.tenet.client;

import dev.ellipog.armature.api.net.ArmatureNetwork;
import dev.ellipog.armature.client.ArmatureButton;
import dev.ellipog.armature.client.ArmatureTextField;
import dev.ellipog.armature.client.ArmatureTheme;
import dev.ellipog.armature.client.render.GuiRenderer;
import dev.ellipog.armature.client.ui.inspect.InspectLayout;
import dev.ellipog.armature.client.ui.kit.Colour;
import dev.ellipog.armature.client.ui.kit.Hover;
import dev.ellipog.armature.client.ui.kit.Layout;
import dev.ellipog.armature.client.ui.kit.Measure;
import dev.ellipog.armature.client.ui.kit.Motion;
import dev.ellipog.armature.client.ui.kit.Slot;
import dev.ellipog.armature.client.ui.kit.Viewport;
import dev.ellipog.tenet.editor.EditorOp;
import dev.ellipog.tenet.net.EditorReplyPayload;
import dev.ellipog.tenet.net.TenetNetworking;
import dev.ellipog.tenet.client.ClientChapterReplica;
import dev.ellipog.tenet.client.ClientEditReplies;
import dev.ellipog.tenet.client.dev.EntryFormLayout;
import dev.ellipog.tenet.quest.EditorField;
import dev.ellipog.tenet.client.dev.InlineEdit;
import dev.ellipog.tenet.client.dev.Labels;
import dev.ellipog.tenet.client.dev.Alignment;
import dev.ellipog.tenet.client.dev.LineArt;
import dev.ellipog.tenet.client.dev.QuestSettingsLayout;
import dev.ellipog.tenet.client.dev.QuestSettingsPanel;
import dev.ellipog.tenet.client.dev.SettingsDraft;
import dev.ellipog.tenet.quest.DependencyStyle;
import dev.ellipog.tenet.quest.QuestShape;
import dev.ellipog.tenet.client.dev.QuestPanelLayout;
import dev.ellipog.tenet.client.dev.RowDrag;
import dev.ellipog.tenet.editor.QuestEditor;
import dev.ellipog.tenet.Constants;
import dev.ellipog.tenet.net.ClaimRewardEntryPayload;
import dev.ellipog.tenet.net.ClaimAllPayload;
import dev.ellipog.tenet.net.ClaimChapterPayload;
import dev.ellipog.tenet.progress.ClaimFilter;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import org.joml.Matrix3x2fStack;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Util;
import net.minecraft.world.item.ItemStack;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * The quest book's rewards: the cells' short captions, the reward tables (the card's control, the
 * browser and the editor), and the rewards panel's drawing.
 *
 * <p>Moved out of {@link QuestBookScreen} as it stood, with the state only it reads; the screen
 * keeps the state it shares and reaches this through its {@code bookRewards} field.
 */
final class BookRewards {

    private final QuestBookScreen book;

    BookRewards(QuestBookScreen book) {
        this.book = book;
    }

    private static final int REWARDS_BODY_TOP = 62;

    /**
     * The height of one state toggle, and the air between the band's rule and it.
     *
     * <p>Declared rather than written at the two sites that need them, because the band's height, the
     * toggle's position and where the body starts are <b>one</b> decision: the first version drew the
     * band at a hard-coded 45 while the body began at {@link #REWARDS_BODY_TOP}, so the toggle row
     * straddled the rule separating the two — a control half in the header and half in the list.
     */
    private static final int STATE_CHIP_HEIGHT = 16;
    private static final int STATE_CHIP_GAP = 6;

    /** The gap between a row's fold marker and whatever it draws next. */
    private static final int MARKER_GAP = 4;

    /** The gap between two reward cells in the details column. */
    private static final int REWARD_CELL_GAP = 8;

    /**
     * How much air the auto-pan leaves between a quest it has just brought into view and the column.
     *
     * <p>Not zero, because a node landing exactly on the column's inner edge reads as half-filled rather
     * than as clear — and the node's own label is drawn beside it, so the box that has to clear the column
     * is a little wider than the node.
     */
    private static final int PANEL_PAN_MARGIN = 14;

    /** The reward a table is being chosen for: the file that holds it and the reward's own path. */
    private dev.ellipog.tenet.editor.TableAddress.Owner tableOwner;
    private String tableOwningPath;

    /** The browser's state: its query, its selection, and its frame from the last frame. */
    private String tableQuery = "";

    /**
     * The Import button's rectangle, recorded by the toolbar and read by the popover.
     *
     * <p>The popover hangs off that button, and the button is drawn by the toolbar while the popover is
     * drawn by the panel after its rows — so the one fact that has to travel between them is where the
     * button was.
     */
    private BookGeometry.Rect tableImportAnchor;

    /** The toolbar's test-roll label, which is the only place the count above is spelled out. */
    private static String tableRollLabel() {
        return "Test roll x" + QuestBookScreen.TABLE_ROLL_COUNT;
    }

    /**
     * The sentence the footer band shows this frame, or null.
     *
     * <p>One slot rather than a list: the note is about whatever the pointer is over, and two pointers
     * do not exist. Cleared at the top of the panel's draw and set by whichever control is hot, so a
     * note cannot outlive the hover that made it -- which is what a queue of labels did.
     */
    private String tableHint;
    private java.util.List<dev.ellipog.tenet.client.dev.TableBrowserLayout.Row> tableRows =
            java.util.List.of();

    /** The card's search box and the editor's title field. Widgets, so they are screen fields. */
    private dev.ellipog.armature.client.ArmatureTextField tableSearch;
    private dev.ellipog.armature.client.ArmatureTextField tableTitleField;

    /** Where the title box was built, for the tooltip that says what it is for. */
    private BookGeometry.Rect tableTitleBox;

    /**
     * The table id a delete has been armed for, or null.
     *
     * <p>Keyed by id rather than a boolean, so the confirmation cannot be spent on a different row: the
     * list re-sorts and re-filters under the pointer, and "press again" has to mean the table the author
     * was told about.
     */
    private String tableConfirmDelete;

    /** The rewards panel's rows and layout, for the card that is open. */
    private List<RewardInboxLayout.Row> rewardRows = List.of();
    private Layout rewardLayout;

    /**
     * Each quest's admitted rewards, by quest id — what its details column draws.
     *
     * <p>Built once per rebuild rather than re-derived in the drawing, and the difference is not
     * tidiness: a reward cell's text is a resolved translation, and the drawing runs sixty times a
     * second while a rebuild runs when the progress moves. It is also what lets a folded quest's column
     * be filled from the <b>same</b> walk its fold would list, so the two cannot disagree about what the
     * quest is giving.
     */
    private final Map<String, List<RewardInboxLayout.Row>> questRewardCells = new java.util.HashMap<>();

    /**
     * How many rewards the view holds, folded or not — the count on the card's second line.
     *
     * <p>Counted in the same walk that builds the rows, because counting the built rows would miss every
     * reward inside a folded quest: a fold hides rows, it does not remove them from the view, and a
     * subtitle that disagreed with the chapter badges above it would be the card contradicting itself.
     */
    private int rewardListed;

    /**
     * The claim menu's own rail: as wide as the table it lists, because four columns do not fit in the
     * width a prose panel is drawn at.
     *
     * <p>Null until the panel is built, and rebuilt on every {@code buildRewardWidgets} for the reason
     * {@link QuestBookScreen#appearanceCard} is: a window resized while the menu is open has to re-derive the rail, and
     * the rail is what the body, the footer and the header band are all placed from. Its width comes from
     * {@link BookGeometry#MAX_WIDE_MODAL_WIDTH}, which is {@code PanelStack.WIDE_WIDTH} — the figure these
     * four columns were laid out against when they were a card.
     */
    private BookGeometry.Rect rewardCard;

    /**
     * The chapters whose quests the menu is showing open.
     *
     * <p>Collapsed by default, and remembered for as long as this screen lives. Nothing is hidden that a
     * press cannot get at -- a banner carries its own badge and its own Claim Chapter -- and a pack with
     * twenty chapters opens as twenty lines rather than as a wall of quests.
     */
    private final Set<String> expandedChapters = new HashSet<>();

    /**
     * Whether the player has folded or unfolded a chapter here yet.
     *
     * <p>Until they have, a lone chapter opens: "collapsed by default" is for a pack of twenty, and one
     * folded banner over an empty card read as a panel with nothing in it.
     */
    private boolean chapterFoldTouched;

    /**
     * The quests whose reward rows the menu is showing open.
     *
     * <p>The same fold one level down, and a separate set because the two are independent: opening a
     * chapter does not open every quest in it, which is what keeps a chapter of thirty quests readable.
     */
    private final Set<String> expandedQuests = new HashSet<>();

    /**
     * The card's active state: which rows it lists, and what the footer button may take.
     *
     * <p>Remembered for as long as the screen lives, like the folds. The footer follows it -- the sweep
     * reaches exactly what the view shows -- because a button that acted on rows the player cannot see
     * would be acting on hidden elements. {@code READY} is where it starts: the menu's first job is what
     * is owed, and a history of what has been collected is the third thing a player wants from it.
     *
     * <p>See {@link RewardInboxLayout.State} for the admission rule and the filter it maps to, both of
     * which are asserted there rather than here.
     */
    private RewardInboxLayout.State rewardState = RewardInboxLayout.State.READY;

    /** The value under the pointer during a drag: the release commits this, not the draft. */
    private double draggingValue;

    /** The line whose handles were last revealed, so a hand reaching for a control point cannot blink. */
    private String[] revealedEdge;

    /**
     * How far below its rest the Import popover still sits.
     *
     * <p>The menu's rise with the popover's own stamp: same pixels, same duration, same stillness with
     * motion off. Applied where the box is drawn — which is also where the press reads it — so the
     * two agree for the tenth of a second the arrival lasts.
     */
    private int importRise() {
        if (!book.bookState.tableImportOpen || book.bookState.tableImportOpenedAt < 0) {
            return 0;
        }
        float arrived = PanelLayout.revealed(net.minecraft.util.Util.getMillis() - book.bookState.tableImportOpenedAt,
                BookItemPicker.MENU_RISE_MILLIS);
        return Math.round(BookItemPicker.MENU_RISE_PX * (1F - arrived));
    }

    /**
     * A toggle's press: the opposite of what the tree says now, which is the flag's own truth and not
     * the label's -- the label was decided when the rows were built, the tree is what a commit answers.
     *
     * <p>A dependency row's press is a remove, and it is one op with the whole remaining list: there is
     * no "remove one" op, and computing the list here from the replica is the same move adding makes.
     */
    private void pressQuestToggle(String key) {
        JsonObject quest = book.replicaQuest();
        String target = book.editTarget();
        if (!book.mayEditNow() || target == null || quest == null) {
            return;
        }
        if (key.startsWith(QuestPanelLayout.DEPENDENCY_PREFIX)) {
            String dependency = key.substring(QuestPanelLayout.DEPENDENCY_PREFIX.length());
            List<String> remaining = QuestPanelLayout.strings(quest, "dependsOn").stream()
                    .filter(each -> !each.equals(dependency)).toList();
            book.sendField(target, "dependsOn", QuestBookScreen.stringArray(remaining));
            return;
        }
        book.sendField(target, key, new JsonPrimitive(!QuestBookScreen.flagOn(quest, key)));
    }

    /**
     * The same chip, drawn blocked: a control the reading on screen does not use.
     *
     * <p>Blocked rather than absent. A control that has vanished reads as a control that is missing, and
     * the caller registers no target for this one, so it takes no press and the hover says why — the same
     * treatment the toolbar's Undo gets when there is nothing to undo.
     */
    private void drawChipBlocked(GuiRenderer r, BookGeometry.Rect box, String glyph) {
        QuestBookScreen.drawEditAffordance(r, box, false);
        r.text(glyph, box.x() + (box.width() - r.textWidth(glyph)) / 2,
                box.y() + (EntryFormLayout.LINE_HEIGHT - 8) / 2, ArmatureTheme.blocked());
    }

    // ------------------------------------------------------------------
    // The cells' captions: short, because a preview is about forty pixels wide
    // ------------------------------------------------------------------

    static String formLabel(DependencyStyle.Form form) {
        return Labels.of(switch (form) {
            case ORTHOGONAL -> "tenet.dev.line.ortho";
            case CHAMFERED -> "tenet.dev.line.circuit";
            case STRAIGHT -> "tenet.dev.line.straight";
            case CURVED -> "tenet.dev.line.curved";
        });
    }

    static String headLabel(DependencyStyle.ArrowHead head) {
        return Labels.of(switch (head) {
            case CHEVRON -> "tenet.dev.line.chevron";
            case TRIANGLE -> "tenet.dev.line.tri";
            case DOT -> "tenet.dev.line.dot";
            case DIAMOND -> "tenet.dev.line.diamond";
            case NONE -> "tenet.dev.line.none";
        });
    }

    static String placeLabel(DependencyStyle.ArrowPlace place) {
        return Labels.of(switch (place) {
            case TARGET -> "tenet.dev.line.target";
            case BOTH -> "tenet.dev.line.both";
            case MID -> "tenet.dev.line.mid";
            case STREAM -> "tenet.dev.line.stream";
        });
    }

    static String densityLabel(DependencyStyle.ArrowDensity density) {
        return Labels.of(switch (density) {
            case LOW -> "tenet.dev.line.low";
            case MEDIUM -> "tenet.dev.line.med";
            case HIGH -> "tenet.dev.line.high";
        });
    }

    static String patternLabel(DependencyStyle.Dash pattern) {
        return Labels.of(switch (pattern) {
            case SOLID -> "tenet.dev.line.solid";
            case DASHED -> "tenet.dev.line.dash";
            case DOTTED -> "tenet.dev.line.dot_pattern";
            case DASH_DOT -> "tenet.dev.line.dash_dot";
            case DOUBLE -> "tenet.dev.line.double";
            case HAZARD -> "tenet.dev.line.hazard";
        });
    }

    static String weightLabel(DependencyStyle.Weight weight) {
        return Labels.of(switch (weight) {
            case THIN -> "tenet.dev.line.hair";
            case THICK -> "tenet.dev.line.std";
            case BOLD -> "tenet.dev.line.bold";
            case CONDUIT -> "tenet.dev.line.pipe";
        });
    }

    /**
     * Turns a single bow into two control points, without moving the line.
     *
     * <p>The pair written is the one that passes through the quadratic's own midpoint, so the shape the
     * author was looking at is the shape they get; from there the two diamonds pull it into an S.
     */
    void splitHandles(String from, String to, DependencyStyle style) {
        JsonObject lines = lineOverride(from, to);
        JsonObject entry = lines.getAsJsonObject(from);
        double bend = style.bendOr(BookCanvas.DEFAULT_BEND);
        entry.add("fromHandle", handleArray(LineArt.equivalentFromHandle(bend)));
        entry.add("toHandle", handleArray(LineArt.equivalentToHandle(bend)));
        book.sendField(to, "dependencyLines", lines);
        book.status("Line " + from + " -> " + to + ": split into two handles", false);
    }

    /** Puts the single bow back: the two control points are removed and `bend` has it again. */
    void joinHandles(String from, String to) {
        JsonObject lines = lineOverride(from, to);
        JsonObject entry = lines.getAsJsonObject(from);
        entry.remove("fromHandle");
        entry.remove("toHandle");
        // A join can leave the entry empty -- when the handles were the only thing this line
        // overrode -- and an empty entry is not an override: it is a key that pins nothing.
        if (entry.isEmpty()) {
            lines.remove(from);
        }
        book.sendField(to, "dependencyLines", lines.isEmpty() ? null : lines);
        book.status("Line " + from + " -> " + to + ": handles joined", false);
    }

    /**
     * The override for one line, read from what the server last sent.
     *
     * <p>From the synced map rather than from the editor's replica: the map is the server's own answer
     * about this quest, and rebuilding the object from it means the write cannot lose an axis the file
     * holds but this client's copy does not.
     */
    private JsonObject lineOverride(String from, String to) {
        ClientQuestCache.Entry dependent = book.entryFor(to);
        JsonObject lines = dependent == null ? new JsonObject() : dependencyLinesOf(dependent);
        JsonObject style = lines.has(from) && lines.get(from).isJsonObject()
                ? lines.getAsJsonObject(from).deepCopy() : new JsonObject();
        lines.add(from, style);
        return lines;
    }

    /**
     * A quest's dependency-line overrides as the editor has them: the pending write first, then the
     * file's own copy, then the synced tree's parsed styles.
     *
     * <p>Every writer rebuilds the whole map for one axis's change, so the base has to include a
     * pending sibling edit — reading the synced tree alone dropped an axis the author had just set,
     * and the server's whole-object write then discarded it. The replica is the file's own answer, so
     * it carries axes the synced tree never shows; the parsed tree is the last resort.
     *
     * <p>The replica is only the file's own answer for the tree on screen: a broadcast that moved the
     * revision past it is the newer truth — another author's edit, with no draft to cover it — and
     * rebuilding from the older copy would drop that edit when the whole object is written back. So
     * the copy is read while its revision is the tree's, and the synced tree otherwise. The fallback
     * re-serializes the parsed tree, so an axis this build does not know — written by a newer one — is
     * not carried while the copy is stale; the replica's raw JSON is the only place it survives, which
     * is why the copy is preferred the moment it is current.
     */
    static JsonObject dependencyLinesOf(ClientQuestCache.Entry entry) {
        JsonElement drafted = BookScreenState.fieldDraft.value(entry.chapterId(), entry.id(), "dependencyLines");
        if (drafted != null && drafted.isJsonObject()) {
            return drafted.getAsJsonObject().deepCopy();
        }
        ClientChapterReplica.Copy copy = ClientChapterReplica.of(entry.chapterId());
        if (copy != null && copy.revision() == ClientQuestCache.treeRevision()) {
            JsonObject quest = copy.quests().get(entry.id());
            JsonElement stored = quest == null ? null : QuestPanelLayout.get(quest, "dependencyLines");
            if (stored != null && stored.isJsonObject()) {
                return stored.getAsJsonObject().deepCopy();
            }
        }
        JsonObject lines = new JsonObject();
        entry.dependencyLines().forEach((dependency, style) -> lines.add(dependency, style.asJson()));
        return lines;
    }

    /** Writes one axis of one line's override, keeping every other axis and every other line. */
    void setLineAxis(String from, String to, String axis, String value) {
        JsonObject lines = lineOverride(from, to);
        JsonObject style = lines.getAsJsonObject(from);
        style.addProperty(axis, value);
        if (DependencyStyle.isArrowAxis(axis)) {
            // The legacy axis said all three of the current ones at once: an edit that speaks the new
            // vocabulary retires it, or clearing one new axis later would resurrect the old value.
            style.remove(DependencyStyle.LEGACY_ARROWS_FIELD);
        }
        book.sendField(to, "dependencyLines", lines);
        book.status("Line " + from + " \u2192 " + to + ": " + axis + " " + value, false);
    }

    /** Removes one axis of one line's override, dropping the override entirely when it empties. */
    void clearLineAxis(String from, String to, String axis) {
        JsonObject lines = lineOverride(from, to);
        JsonObject style = lines.getAsJsonObject(from);
        style.remove(axis);
        if (DependencyStyle.isArrowAxis(axis)) {
            // The reset means "the chapter's default again", and while the legacy spelling is still in
            // the object that is not what it would get.
            style.remove(DependencyStyle.LEGACY_ARROWS_FIELD);
        }
        if (style.isEmpty()) {
            lines.remove(from);
        }
        book.sendField(to, "dependencyLines", lines.isEmpty() ? null : lines);
        book.status("Line " + from + " \u2192 " + to + ": chapter default", false);
    }

    /** Removes one dependency, by writing the rest of the list — the card's own remove path. */
    void removeDependency(String dependentId, String dependencyId) {
        ClientQuestCache.Entry dependent = book.entryFor(dependentId);
        if (dependent == null) {
            return;
        }
        List<String> remaining = QuestBookScreen.dependenciesOf(dependent).stream()
                .filter(each -> !each.equals(dependencyId)).toList();
        book.sendField(dependentId, "dependsOn", QuestBookScreen.stringArray(remaining));
        book.status("Removed " + dependencyId + " \u2192 " + dependentId, false);
    }

    /** How close to the ink a right-click must land, in pixels: looser than an exact hit. */
    static final int LINE_HIT = LineArt.TOLERANCE + 2;

    /**
     * How close to a canvas line's ink a press must land, which is the same reach a dependency line gets.
     *
     * <p>The same number on purpose: both are lines on the same canvas, drawn by the same class, and an author
     * who has learned how close is close enough for one has learned it for the other. A line is also the only
     * element whose shape is not its box, so it is the only one that needs a reach at all.
     */
    static final int ELEMENT_LINE_HIT = LINE_HIT;

    /**
     * The element panel's preview strip: what the card says about its subject before the form starts.
     *
     * <p>Two lines tall — a swatch and the element's own meta — taken out of the scroll body's head, so
     * the first form row starts below it rather than under it. The scroll view's viewport is inset by
     * the same number where its widgets are built, which is what keeps a row's drawing and its control
     * on the same band.
     */
    static final int ELEMENT_PREVIEW_HEIGHT = 28;

    /**
     * How far a hovered line is lightened at full hover, as a fraction towards white.
     *
     * <p>Lightened rather than recoloured: {@code Colour.shade} keeps the line's own hue and its
     * alpha, so a completed line still reads as completed while it brightens, and the cue cannot be
     * mistaken for a selection.
     */
    static final float LINE_HOVER_BRIGHTEN = 0.5F;

    /**
     * How far a line joined to the hovered or glowing node is lightened at full glow.
     *
     * <p>Quieter than a directly hovered line, so the line under the pointer still reads loudest
     * and the two cues never tie. Same {@code shade} for the same reason: a completed line stays
     * recognisably completed underneath.
     */
    static final float ADJACENT_LINE_BRIGHTEN = 0.35F;

    /**
     * The dependency line under the pointer, as {dependency, dependent}, or null.
     *
     * <p>The candidates are {@link #edgeCandidates}, which is the drawing's own walk -- so a line that
     * is drawn is a line that answers, and one function rather than three copies of the loop. The
     * caller has already established that no node is under the pointer, because the node wins the
     * press and so it wins the cue: see {@code drawCanvasContents}.
     */
    List<String> edgeAt(double mouseX, double mouseY, List<ClientQuestCache.Entry> quests) {
        String[] pair = LineArt.nearest(edgeCandidates(quests), mouseX, mouseY, LINE_HIT);
        return pair == null ? null : List.of(pair[0], pair[1]);
    }

    /** The route one line takes, from the style that line is owed. The drawing's own derivation. */
    private List<LineArt.Point> linePath(ClientQuestCache.Entry dependent, String dependencyId,
                                         ClientQuestCache.Entry from, ClientQuestCache.Entry to) {
        DependencyStyle style = book.bookCanvas.styleFor(dependent, dependencyId);
        LineArt.Point[] ends = book.bookCanvas.lineEnds(from, to, style);
        return BookCanvas.stylePath(style, ends[0], ends[1]);
    }

    int nodeCentreX(ClientQuestCache.Entry entry) {
        return book.bookCanvas.nodeScreenX(entry) + book.bookCanvas.nodeSize(entry) / 2;
    }

    int nodeCentreY(ClientQuestCache.Entry entry) {
        return book.bookCanvas.nodeScreenY(entry) + book.bookCanvas.nodeSize(entry) / 2;
    }

    /**
     * The handle of the curved line under the pointer, as {dependency, dependent}, or null.
     *
     * <p>Only a curved line has one — a bow means nothing on a step or a straight edge — and the handle
     * sits on the curve's own midpoint, which is off the route the hit test measures, so the two tests are
     * separate questions rather than one.
     */
    /** The three kinds of handle a line can show. */
    static final String HANDLE_FROM = "from";
    static final String HANDLE_TO = "to";
    static final String HANDLE_BEND = "bend";
    static final String HANDLE_FROM_HANDLE = "fromHandle";
    static final String HANDLE_TO_HANDLE = "toHandle";

    /**
     * The handle under the pointer, as {dependency, dependent, kind}, or null.
     *
     * <p>The rim dots come before the bend dot in this order, and both before any node: the dots sit on a
     * node's own edge, where a node hit test is true, so a press that reached the node first could never
     * grab one.
     */
    String[] handleAt(double mouseX, double mouseY) {
        // Nothing to ask about the arrangement: the graph is live whether a rail is open or not, so a handle
        // is grabbable by the same rule as a node. Widened together with the drawing below, deliberately --
        // a handle grabbable but not drawn is worse than neither.
        if (!book.mayEditNow()) {
            return null;
        }
        String chapter = QuestBookScreen.effectiveChapter();
        if (chapter == null) {
            return null;
        }
        if (book.bookState.draggedNode != null || book.bookState.marqueeActive) {
            // A node or a marquee owns the pointer; a handle is not what this press is for.
            return null;
        }
        List<ClientQuestCache.Entry> quests = book.questsIn(chapter);
        String[] found = null;
        double best = HANDLE_GRAB;
        for (LineArt.Candidate<String[]> candidate : edgeCandidates(quests)) {
            String[] edge = candidate.id();
            ClientQuestCache.Entry dependent = book.entryFor(edge[1]);
            ClientQuestCache.Entry dependency = book.entryFor(edge[0]);
            if (dependent == null || dependency == null) {
                continue;
            }
            DependencyStyle style = book.bookCanvas.styleFor(dependent, edge[0]);
            if (!showsHandles(edge, handleArms(edge, candidate.path(), style), mouseX, mouseY)) {
                continue;
            }
            LineArt.Point fromDot = handleDot(candidate.path(), true);
            LineArt.Point toDot = handleDot(candidate.path(), false);
            double fromDistance = Math.hypot(fromDot.x() - mouseX, fromDot.y() - mouseY);
            if (fromDistance <= best) {
                best = fromDistance;
                found = new String[] { edge[0], edge[1], HANDLE_FROM };
            }
            double toDistance = Math.hypot(toDot.x() - mouseX, toDot.y() - mouseY);
            if (toDistance <= best) {
                best = toDistance;
                found = new String[] { edge[0], edge[1], HANDLE_TO };
            }
            if (BookCanvas.isSplit(style) || bows(style)) {
                if (BookCanvas.isSplit(style)) {
                    LineArt.Point fromControl = splitPointOf(edge, style, true);
                    LineArt.Point toControl = splitPointOf(edge, style, false);
                    double fromHandleDistance = Math.hypot(fromControl.x() - mouseX,
                            fromControl.y() - mouseY);
                    if (fromHandleDistance <= best) {
                        best = fromHandleDistance;
                        found = new String[] { edge[0], edge[1], HANDLE_FROM_HANDLE };
                    }
                    double toHandleDistance = Math.hypot(toControl.x() - mouseX,
                            toControl.y() - mouseY);
                    if (toHandleDistance <= best) {
                        best = toHandleDistance;
                        found = new String[] { edge[0], edge[1], HANDLE_TO_HANDLE };
                    }
                }
                else {
                    LineArt.Point middle = LineArt.pointAt(candidate.path(),
                            LineArt.length(candidate.path()) / 2);
                    double middleDistance = Math.hypot(middle.x() - mouseX, middle.y() - mouseY);
                    if (middleDistance <= best) {
                        best = middleDistance;
                        found = new String[] { edge[0], edge[1], HANDLE_BEND };
                    }
                }
            }
        }
        return found;
    }

    /** How close to a handle a press must be, in pixels. */
    private static final int HANDLE_GRAB = 8;

    /**
     * How far the hover reaches around a line and its handles, in pixels.
     *
     * <p>Wider than the grab on purpose: the reveal is what invites the hand, and a line that answers
     * only on its own 1px of ink is one whose control points cannot be reached at all.
     */
    private static final int HANDLE_REACH = HANDLE_GRAB + 4;

    /**
     * How much further the already-revealed line keeps its handles, in pixels.
     *
     * <p>What stops a diamond winking out as the hand crosses to it diagonally: nothing new reveals
     * outside {@link #HANDLE_REACH}, but the line that is already lit stays lit a little longer.
     */
    private static final int HANDLE_STICKY = 6;

    /**
     * The handle layer: a circle at each end of the line under the pointer, and a diamond at the middle
     * of a curved one.
     *
     * <p><b>Called after the nodes are drawn</b>, so a dot that overlaps a node — an anchor dragged round
     * to its far side — is on top of it. The first version ran before `drawNode`, and the dot you were
     * holding vanished under the node it was next to.
     *
     * <p><b>One shape per meaning</b>: ends are circles, the bend is a diamond, so the outline says which
     * one detaches and which one bends. Grab radius is the same for all three; the diamond is checked last
     * and wins ties, so a pointer between an end and the control bends rather than detaching.
     *
     * <p><b>And a drag owns the pointer:</b> while a handle is being dragged, or a node, or a marquee,
     * no other line may light up — dragging across a neighbour used to sprout its handles mid-gesture.
     */
    void drawEdgeHandles(GuiRenderer r, double mouseX, double mouseY, List<BookCanvas.FrameEdge> edges,
                                 int dx, int dy) {
        // The same condition `handleAt` tests, and that is the point: the two are one decision -- where a
        // line's handles are grabbable is where they are drawn -- so a handle that answers a press must be
        // the handle the player can see.
        if (!book.mayEditNow()) {
            return;
        }
        // A gesture owns the pointer: only the dragged line shows anything, and a node or marquee drag
        // shows nothing at all until the release.
        boolean gesture = book.bookState.bendDragFrom != null || book.bookState.draggedNode != null
                || book.bookState.marqueeActive;
        int cLeft = book.canvasLeft();
        int cTop = book.canvasTop();
        int cRight = book.canvasRight();
        int cBottom = book.canvasBottom();
        // The frame's own edges: the paths the handles are grabbed against are the ones that were drawn,
        // and they are already built -- this loop used to rebuild every route a second time per frame.
        for (BookCanvas.FrameEdge frameEdge : edges) {
            boolean dragging = frameEdge.matches(book.bookState.bendDragFrom, book.bookState.bendDragTo);
            if (gesture && !dragging) {
                continue;
            }
            if (!dragging && !frameEdge.reaches(dx, dy, cLeft, cTop, cRight, cBottom)) {
                continue;
            }
            String[] edge = frameEdge.key();
            DependencyStyle style = frameEdge.style();
            List<LineArt.Point> path = frameEdge.path(dx, dy);
            List<List<LineArt.Point>> arms = handleArms(edge, path, style);
            if (!dragging && !showsHandles(edge, arms, mouseX, mouseY)) {
                continue;
            }
            boolean fromDragged = dragging && HANDLE_FROM.equals(book.bookState.bendDragKind);
            boolean toDragged = dragging && HANDLE_TO.equals(book.bookState.bendDragKind);
            // Drawn where they are grabbed, from the same path the ink came from: circles at the ends,
            // the diamond at the middle of a curve.
            circleDot(r, handleDot(path, true), fromDragged);
            circleDot(r, handleDot(path, false), toDragged);
            if (BookCanvas.isSplit(style)) {
                // Two diamonds at the control points, each with a leash to the end of the curve it
                // steers -- the outline that says "bend here" rather than "another end". Both come from
                // `arms`, the same list the hover reaches along, so the two cannot drift apart again.
                if (arms.size() == 3) {
                    leash(r, arms.get(1));
                    leash(r, arms.get(2));
                    diamondDot(r, arms.get(1).get(arms.get(1).size() - 1),
                            dragging && HANDLE_FROM_HANDLE.equals(book.bookState.bendDragKind));
                    diamondDot(r, arms.get(2).get(arms.get(2).size() - 1),
                            dragging && HANDLE_TO_HANDLE.equals(book.bookState.bendDragKind));
                }
            }
            else if (bows(style)) {
                LineArt.Point middle = LineArt.pointAt(path,
                        LineArt.length(path) / 2);
                diamondDot(r, middle, dragging && HANDLE_BEND.equals(book.bookState.bendDragKind));
            }
        }
    }

    /** How far outside the rim an end's dot sits, so it is clear of the node it belongs to. */
    private static final int DOT_OUT = 7;

    /**
     * Where an end's dot is drawn and grabbed: a point **of the drawn line**, a little in from its end.
     *
     * <p>The first version pushed the rim point outward along the direction from the node's centre — the
     * chord's direction — which is not the direction a curve leaves its rim in, so on every curved line
     * the dots sat beside the ink. Taking the point from the path cannot disagree with the drawing, for
     * any form and any anchor, because it is a point of it.
     */
    private static LineArt.Point handleDot(List<LineArt.Point> path, boolean atStart) {
        double total = LineArt.length(path);
        if (total < 1) {
            return path.isEmpty() ? new LineArt.Point(0, 0) : path.get(0);
        }
        // A third of the path rather than half: on a short line both dots would otherwise clamp onto
        // the same pixel and become one grab target.
        double in = Math.min(DOT_OUT, total / 3.0);
        return atStart ? LineArt.pointAt(path, in) : LineArt.pointAt(path, total - in);
    }

    /**
     * How wide a handle dot is, at its middle.
     *
     * <p>Its own number, not `HANDLE_GRAB - 2`: how big a target looks and how close a press must land
     * are two decisions, and tying them together made the dots chunky the day the grab got generous.
     */
    private static final int DOT_RADIUS = 4;

    /**
     * A terminal anchor's dot: a **circle**.
     *
     * <p>The interactive inks rather than the faint panel edge, which is close to invisible on the light
     * themes where the line itself is dark — a handle that cannot be seen is a handle that cannot be used.
     */
    private static void circleDot(GuiRenderer r, LineArt.Point centre, boolean dragged) {
        int colour = dragged ? ArmatureTheme.selectedRing() : ArmatureTheme.hoverRing();
        for (int y = -DOT_RADIUS; y <= DOT_RADIUS; y++) {
            int half = (int) Math.floor(Math.sqrt((double) DOT_RADIUS * DOT_RADIUS - y * y));
            r.fill(centre.x() - half, centre.y() + y, centre.x() + half + 1, centre.y() + y + 1, colour);
        }
    }

    /** A 1px leash along one arm, from the end of the curve out to its own control point. */
    private static void leash(GuiRenderer r, List<LineArt.Point> arm) {
        for (LineArt.Point point : arm) {
            r.fill(point.x(), point.y(), point.x() + 1, point.y() + 1, ArmatureTheme.line());
        }
    }

    /** The bend control's dot: a **diamond**, so it cannot be mistaken for an end. */
    private static void diamondDot(GuiRenderer r, LineArt.Point centre, boolean dragged) {
        int colour = dragged ? ArmatureTheme.selectedRing() : ArmatureTheme.hoverRing();
        for (int y = -DOT_RADIUS; y <= DOT_RADIUS; y++) {
            int half = DOT_RADIUS - Math.abs(y);
            r.fill(centre.x() - half, centre.y() + y, centre.x() + half + 1, centre.y() + y + 1, colour);
        }
    }

    /**
     * The arms the hover reaches along for one line: its ink, then one leash per split control point.
     *
     * <p><b>One source of truth for the furniture.</b> The drawing takes its leashes and its diamonds
     * from this list too, so a handle that is drawn is a handle the hover can reach. The split control
     * points were the case that proved it: they sit off the ink by design, and a reveal that knew only
     * about the ink made them vanish exactly as the hand arrived.
     */
    private List<List<LineArt.Point>> handleArms(String[] edge, List<LineArt.Point> path,
                                                 DependencyStyle style) {
        List<List<LineArt.Point>> arms = new ArrayList<>();
        arms.add(path);
        if (BookCanvas.isSplit(style)) {
            LineArt.Point fromControl = splitPointOf(edge, style, true);
            LineArt.Point toControl = splitPointOf(edge, style, false);
            if (fromControl != null && toControl != null) {
                arms.add(LineArt.steps(handleDot(path, true), fromControl));
                arms.add(LineArt.steps(handleDot(path, false), toControl));
            }
        }
        return arms;
    }

    /**
     * Whether this line should show its handles: the pointer is within {@link #HANDLE_REACH} of the ink,
     * a leash or a control point — or within {@link #HANDLE_STICKY} more of the line last revealed.
     *
     * <p>The reach is the union of the arms, connected by construction — ink, then the leash out to each
     * control — so travelling to a control point never crosses a dead band. The extra stickiness is for
     * the approach that does not follow the leash: aiming diagonally at a diamond used to wink the whole
     * line out a few pixels short of the target.
     */
    private boolean showsHandles(String[] edge, List<List<LineArt.Point>> arms,
                                 double mouseX, double mouseY) {
        double reach = LineArt.distanceToAny(arms, mouseX, mouseY);
        if (reach <= HANDLE_REACH) {
            revealedEdge = edge;
            return true;
        }
        return revealedEdge != null && revealedEdge[0].equals(edge[0]) && revealedEdge[1].equals(edge[1])
                && reach <= HANDLE_REACH + HANDLE_STICKY;
    }

    /**
     * Every line the chapter draws, as candidates for a hover or a hit test.
     *
     * <p>The same rules the drawing uses — dependencies, plus a linear chapter's road — so a line that is
     * drawn is a line that answers, and one function rather than three copies of the loop.
     */
    private List<LineArt.Candidate<String[]>> edgeCandidates(List<ClientQuestCache.Entry> quests) {
        List<LineArt.Candidate<String[]>> candidates = new ArrayList<>();
        for (ClientQuestCache.Entry quest : quests) {
            for (String dependencyId : QuestBookScreen.dependenciesOf(quest)) {
                ClientQuestCache.Entry dependency = book.entryFor(dependencyId);
                if (dependency == null || !quests.contains(dependency)) {
                    continue;
                }
                candidates.add(new LineArt.Candidate<>(new String[] { dependencyId, quest.id() },
                        linePath(quest, dependencyId, dependency, quest)));
            }
        }
        if (!quests.isEmpty() && quests.get(0).chapterLinear()) {
            List<ClientQuestCache.Entry> ordered = quests.stream()
                    .sorted(java.util.Comparator.comparingInt(ClientQuestCache.Entry::orderInChapter))
                    .toList();
            for (int i = 1; i < ordered.size(); i++) {
                ClientQuestCache.Entry previous = ordered.get(i - 1);
                candidates.add(new LineArt.Candidate<>(new String[] { previous.id(), ordered.get(i).id() },
                        linePath(ordered.get(i), previous.id(), previous, ordered.get(i))));
            }
        }
        return candidates;
    }

    /** The chord's frame for one line: the two ends, and the unit direction and length between them. */
    double[] chordOf(ClientQuestCache.Entry from, ClientQuestCache.Entry to) {
        double ax = nodeCentreX(from);
        double ay = nodeCentreY(from);
        double bx = nodeCentreX(to);
        double by = nodeCentreY(to);
        double length = Math.hypot(bx - ax, by - ay);
        return new double[] { ax, ay, length < 1 ? 0 : (bx - ax) / length, length < 1 ? 0 : (by - ay) / length, length };
    }

    /** A split control point as a screen point, for its diamond and the leash to its end. */
    private LineArt.Point splitPoint(double[] chord, List<Double> handle) {
        return LineArt.handlePoint(new LineArt.Point((int) chord[0], (int) chord[1]), chord[2], chord[3],
                chord[4], handle);
    }

    /** One of the two split control points of a line, or null when it is not split. */
    LineArt.Point splitPointOf(String[] edge, DependencyStyle style, boolean fromEnd) {
        if (!BookCanvas.isSplit(style)) {
            return null;
        }
        ClientQuestCache.Entry dependency = book.entryFor(edge[0]);
        ClientQuestCache.Entry dependent = book.entryFor(edge[1]);
        if (dependency == null || dependent == null) {
            return null;
        }
        return splitPoint(chordOf(dependency, dependent),
                fromEnd ? style.fromHandle().get() : style.toHandle().get());
    }

    /** Writes one split control point: one op, so Ctrl+Z undoes a split drag like any other edit. */
    void setLineHandle(String from, String to, String kind, List<Double> handle) {
        JsonObject lines = lineOverride(from, to);
        lines.getAsJsonObject(from).add(HANDLE_FROM_HANDLE.equals(kind) ? "fromHandle" : "toHandle",
                handleArray(handle));
        book.sendField(to, "dependencyLines", lines);
        book.status("Line " + from + " -> " + to + ": control point moved", false);
    }

    /** A pair as the JSON a file holds. */
    private static JsonArray handleArray(List<Double> handle) {
        JsonArray array = new JsonArray();
        array.add(handle.get(0));
        array.add(handle.get(1));
        return array;
    }

    /** Writes one end's anchor angle: one op, so Ctrl+Z undoes an anchor like any other edit. */
    void setLineAnchor(String from, String to, String kind, double degrees) {
        JsonObject lines = lineOverride(from, to);
        lines.getAsJsonObject(from).addProperty(HANDLE_FROM.equals(kind) ? "fromAnchor" : "toAnchor",
                degrees);
        book.sendField(to, "dependencyLines", lines);
        book.status("Line " + from + " -> " + to + ": anchor " + Math.round(degrees) + "°", false);
    }

    /**
     * Alt+click: centres a node between the two **linked** quests nearest it.
     *
     * <p>Links in either direction — the quests it depends on and the quests that depend on it — because
     * a chain runs through a node from both sides, and the two nearest of those are what it should sit
     * between. Fewer than two is said out loud rather than moving the node somewhere invented.
     */
    void straightenNode(String id) {
        ClientQuestCache.Entry node = book.entryFor(id);
        if (node == null) {
            return;
        }
        LineArt.Point here = new LineArt.Point((int) book.bookCanvas.nodeX(node), (int) book.bookCanvas.nodeY(node));
        List<LineArt.Point> linked = new ArrayList<>();
        for (String dependency : QuestBookScreen.dependenciesOf(node)) {
            ClientQuestCache.Entry parent = book.entryFor(dependency);
            if (parent != null && !parent.id().equals(id)) {
                linked.add(new LineArt.Point((int) book.bookCanvas.nodeX(parent), (int) book.bookCanvas.nodeY(parent)));
            }
        }
        for (ClientQuestCache.Entry other : book.questsIn(QuestBookScreen.effectiveChapter())) {
            if (!other.id().equals(id) && QuestBookScreen.dependenciesOf(other).contains(id)) {
                linked.add(new LineArt.Point((int) book.bookCanvas.nodeX(other), (int) book.bookCanvas.nodeY(other)));
            }
        }
        java.util.Optional<LineArt.Point> middle = Alignment.midpointOfTwoNearest(here, linked);
        if (middle.isEmpty()) {
            book.status("Nothing to straighten: " + id + " has fewer than two linked quests", true);
            return;
        }
        // Exact rather than snapped: Alt is already the free-placement key, so it means the same thing
        // here -- put it where it was asked for.
        book.send(new EditorOp.Move(id, middle.get().x(), middle.get().y()));
    }

    /** Writes a curve's new bow: one op, so Ctrl+Z undoes a bend like any other edit. */
    void setLineBend(String from, String to, double bend) {
        JsonObject lines = lineOverride(from, to);
        lines.getAsJsonObject(from).addProperty("bend", bend);
        book.sendField(to, "dependencyLines", lines);
        book.status("Line " + from + " -> " + to + ": bend " + Math.round(bend * 100) + "%", false);
    }

    /**
     * The on-screen band of a viewport: the rectangle a list's rows are visible in, scrolled or not.
     *
     * <p>The band a drag indicator is tested against. It has to be this and not the list's unscrolled
     * frame: the rows and the pointer are on-screen coordinates, and a clip in the frame's own would be
     * offset by the scroll -- which is how the chapter list's line came to vanish once the list moved.
     */
    static BookGeometry.Rect onScreenBand(Viewport view) {
        return BookGeometry.Rect.at(view.originX(), view.originY(), view.viewWidth(), view.viewHeight());
    }

    void drawRowDragIndicator(GuiRenderer r, List<BookGeometry.Rect> rows, double pointerY,
                                      BookGeometry.Rect clip) {
        if (rows == null || rows.isEmpty()) {
            return;
        }
        int gap = RowDrag.gapAt(rows, pointerY);
        if (gap < 0) {
            return;
        }
        int y = RowDrag.indicatorY(rows, gap);
        if (y < clip.y() - 2 || y > clip.bottom() + 2) {
            return;
        }
        BookGeometry.Rect first = rows.get(0);
        r.fill(first.x() - 2, y - 1, first.right() + 2, y + 2, ArmatureTheme.hoverRing());
    }

    /**
     * A press on the settings page: a swatch, a slider, a switch, an arrow, or the icon's button.
     *
     * <p>Every control is drawn from a rectangle {@code QuestSettingsLayout} computed, so this reads the
     * same rectangles rather than a second set: a swatch is chosen through {@code cellAt}, an arrow
     * through {@code stepperStepAt}, a slider through its track. The step is the field's own — a
     * coordinate moves by the grid (8), a size by one pixel, the icon scale by a twentieth — and a
     * slider's drag commits once, on release, rather than an operation per frame.
     *
     * <h2>Why this answers with a boolean</h2>
     *
     * <p>Because the arm that calls it needs to know whether the page took the press. It used to return
     * nothing and the arm returned {@code true} regardless — so the page <b>swallowed every press on the
     * panel</b>, including the ones that landed on no row at all, and the canvas could not be panned from
     * over the panel while it was open. That is the report this signature exists for: "some work some dont,
     * for example if i go in edit mode and open a quest, i can pan while its open, but if i go into settings
     * on the quest and then try to pan, i cant".
     *
     * @return whether one of the page's rows took the press. False means the press belongs to whatever is
     *         under the page — the canvas — which is the same answer every other arm now gives.
     */
    boolean pressSettingsPage(double mouseX, double mouseY) {
        JsonObject quest = book.replicaQuest();
        if (book.bookState.settingsLayout == null || quest == null || book.editTarget() == null) {
            return false;
        }
        for (dev.ellipog.tenet.client.dev.QuestSettingsLayout.Row row : book.bookState.settingsRows) {
            Slot slot = book.bookState.settingsLayout.slot(row.key());
            if (slot == null) {
                continue;
            }
            Slot onScreen = InspectLayout.onScreen(book.bookState.settingsView.viewport(), slot);
            if (!onScreen.contains(mouseX, mouseY)) {
                continue;
            }
            Slot strip = InspectLayout.onScreen(book.bookState.settingsView.viewport(),
                    dev.ellipog.tenet.client.dev.QuestSettingsLayout.strip(row, slot));
            // **A row claimed the press only if its own control did.** A press inside a row's bounds but
            // off its control — the air beside a short switch track, the gap between two cells — is not the
            // page's, and the one assignment below is what makes that one answer rather than five: each case
            // sets it where it acts, and the row's own `continue` to the next row is what happens otherwise.
            boolean acted = false;
            switch (row.kind()) {
                case SHAPE_GRID -> {
                    int cell = dev.ellipog.tenet.client.dev.QuestSettingsLayout.cellAt(onScreen,
                            mouseX, mouseY, QuestShape.values().length);
                    if (cell >= 0) {
                        chooseShape(QuestShape.values()[cell]);
                        acted = true;
                    }
                }
                case SLIDER -> {
                    Integer step = dev.ellipog.tenet.client.dev.QuestSettingsLayout.arrowStepAt(
                            strip, mouseX, mouseY);
                    if (step != null) {
                        stepField(row.key(), step, quest);
                        acted = true;
                    }
                    else {
                        // The track: the knob jumps to the pointer and follows it until the release,
                        // which is one commit rather than one per frame.
                        book.bookState.draggingSlider = row.key();
                        dragSliderTo(mouseX);
                        acted = true;
                    }
                }
                case SWITCH -> {
                    BookGeometry.Rect track = dev.ellipog.tenet.client.dev.QuestSettingsLayout
                            .switchTrack(strip);
                    if (track.contains(mouseX, mouseY)) {
                        pressQuestToggle(row.key());
                        acted = true;
                    }
                }
                case ICON -> {
                    BookGeometry.Rect button = dev.ellipog.tenet.client.dev.QuestSettingsPanel
                            .iconButton(strip);
                    if (button.contains(mouseX, mouseY)) {
                        // The picker opens on the icon's own field, so the item is chosen with the same
                        // list the panel uses rather than a second one here -- and it hands back to this
                        // page when it closes, because that is where the author was.
                        book.itemPicker.openItemPicker(new QuestBookScreen.EditTarget(QuestBookScreen.EditAction.ITEM,
                                "icon.item", button,
                                button.x(), button.y(), "", null, -1), true);
                        acted = true;
                    }
                }
                case CHOICE -> {
                    Integer step = dev.ellipog.tenet.client.dev.QuestSettingsLayout.arrowStepAt(
                            strip, mouseX, mouseY);
                    if (step != null) {
                        JsonElement value = QuestPanelLayout.get(quest, row.key());
                        String current = value != null && value.isJsonPrimitive()
                                ? value.getAsString() : "";
                        boolean triState = dev.ellipog.tenet.client.dev.QuestSettingsLayout
                                .isTriStateKey(row.key());
                        String next = triState
                                ? dev.ellipog.tenet.client.dev.QuestSettingsLayout
                                        .cycleTriState(current, step)
                                : "autoClaim".equals(row.key())
                                        ? dev.ellipog.tenet.client.dev.QuestSettingsLayout
                                                .cycleAutoClaim(current, step)
                                        : dev.ellipog.tenet.client.dev.QuestSettingsLayout
                                                .cycleRequirement(current, step);
                        // The chapter's default is the *absence* of the field, not a string that spells
                        // it out: a quest that says "all_completed" keeps saying it when the chapter's
                        // default changes, which is the whole difference between the two states. A reveal
                        // flag's unset state is absent for the same reason, and its two real states are
                        // booleans rather than the strings the picker cycles through.
                        JsonElement written = next.isEmpty()
                                ? null
                                : triState
                                        ? dev.ellipog.tenet.client.dev.QuestSettingsLayout
                                                .triStateValue(next)
                                        : new JsonPrimitive(next);
                        book.sendField(book.editTarget(), row.key(), written);
                        acted = true;
                    }
                }
                case DEPENDENCY -> {
                    if (dev.ellipog.tenet.client.dev.QuestSettingsLayout.removeAt(strip, mouseX, mouseY)) {
                        String id = row.key().substring(
                                dev.ellipog.tenet.client.dev.QuestSettingsLayout.DEPENDENCY_PREFIX
                                        .length());
                        List<String> left = new ArrayList<>(
                                QuestPanelLayout.strings(quest, "dependsOn"));
                        left.remove(id);
                        book.sendField(book.editTarget(), "dependsOn", QuestBookScreen.stringArray(left));
                        book.status("No longer depends on " + id, false);
                        acted = true;
                    }
                }
                case ACTION -> {
                    if (!dev.ellipog.tenet.client.dev.QuestSettingsLayout.actionAt(strip, mouseX, mouseY)) {
                        break;
                    }
                    if (row.key().equals(QuestPanelLayout.DEPENDENCY_PICK)) {
                        book.armDependencyPick();
                        acted = true;
                    }
                    else if (row.key().equals(
                            dev.ellipog.tenet.client.dev.QuestSettingsLayout.DEPENDENCY_SELECTED)) {
                        addSelectedDependencies();
                        acted = true;
                    }
                }
                default -> {
                }
            }
            // The row the pointer is on is the only row that can claim this press, whether or not its
            // control did: with `acted` false the answer is "the page did not take it" and the arm falls
            // through to the pan, so the pointer does not have to leave the page to move the canvas.
            return acted;
        }
        return false;
    }

    /**
     * What a prerequisite is called: the cache's title, or the id when the cache has never heard of it.
     *
     * <p>The id is the honest fallback and not a placeholder -- it is what the file holds, and it is what
     * an author would have to type into "Add by id" to get the same dependency back.
     */
    String dependencyTitle(String id) {
        ClientQuestCache.Entry entry = book.entryFor(id);
        return entry == null ? id : QuestBookScreen.titleOf(entry);
    }

    /**
     * The canvas selection, as prerequisites this quest does not have yet.
     *
     * <p>Filtered rather than handed over whole, because "Add selected" means <i>add</i>: the quest
     * itself is never a prerequisite of itself, and re-adding one that is already in the list would be a
     * no-op the server would have to refuse. The count in the row's label is this list's size, so the
     * label says exactly what the press would do.
     */
    List<String> selectedDependencyCandidates() {
        String target = book.editTarget();
        JsonObject quest = book.replicaQuest();
        if (target == null || quest == null) {
            return List.of();
        }
        List<String> have = QuestPanelLayout.strings(quest, "dependsOn");
        List<String> add = new ArrayList<>();
        for (String id : QuestBookScreen.selection()) {
            if (!id.equals(target) && !have.contains(id) && !add.contains(id)) {
                add.add(id);
            }
        }
        return add;
    }

    /** The action row: every selected node becomes a prerequisite, in one write. */
    private void addSelectedDependencies() {
        String target = book.editTarget();
        JsonObject quest = book.replicaQuest();
        if (!book.mayEditNow() || target == null || quest == null) {
            return;
        }
        List<String> add = selectedDependencyCandidates();
        if (add.isEmpty()) {
            book.status("Select the quests to depend on first \u2014 Ctrl-click or shift-click on the canvas",
                    true);
            return;
        }
        List<String> dependencies = new ArrayList<>(QuestPanelLayout.strings(quest, "dependsOn"));
        dependencies.addAll(add);
        book.sendField(target, "dependsOn", QuestBookScreen.stringArray(dependencies));
        book.status(target + " now depends on " + add.size() + (add.size() == 1 ? " quest" : " quests"), false);
    }

    /** A swatch's press: the shape, remembered for the preview and sent to the server. */
    private void chooseShape(QuestShape shape) {
        String target = book.editTarget();
        if (target == null) {
            return;
        }
        book.bookState.settingsDraft.shape(target, shape, revision());
        book.sendField(target, "shape",
                new JsonPrimitive(shape.name().toLowerCase(java.util.Locale.ROOT)));
    }

    /** An arrow's press: the field stepped by its own amount, remembered and sent. */
    private void stepField(String key, int step, JsonObject quest) {
        // Read once, so the quest whose pending value is accumulated *from* and the quest the result is
        // sent *to* cannot be two answers. They were: the draft was one value for the whole screen, so the
        // accumulate read whatever the last quest had left and the send went to this one -- which wrote
        // quest A's geometry into quest B's file. See `SettingsDraft`.
        String target = book.editTarget();
        if (target == null) {
            return;
        }
        if (key.equals("rotation")) {
            // Fifteen degrees an arrow: fine enough to phase a gear off the grid, coarse enough to
            // reach an angle without a drag. The track is there for anything finer.
            int current = book.bookState.settingsDraft.rotation(target, intField(quest, key, 0));
            int next = Math.floorMod(current + step * 15, 360);
            book.bookState.settingsDraft.rotation(target, next, revision());
            book.sendField(target, key, new JsonPrimitive((long) next));
            return;
        }
        if (key.equals("iconScale")) {
            double current = book.bookState.settingsDraft.iconScale(target, doubleField(quest, key, 0.75));
            double next = Math.max(QuestShape.MIN_ICON_SCALE,
                    Math.min(QuestShape.MAX_ICON_SCALE, Math.round((current + step * 0.05) * 100) / 100.0));
            book.bookState.settingsDraft.iconScale(target, next, revision());
            book.sendField(target, key, new JsonPrimitive(next));
            return;
        }
        int current = (int) book.bookState.settingsDraft.size(target, key.equals("size") ? intField(quest, key, 48)
                : intField(quest, key, 0));
        int delta = key.equals("x") || key.equals("y") ? 8 : 1;
        int next = current + step * delta;
        if (key.equals("size")) {
            next = Math.max(dev.ellipog.tenet.client.dev.QuestSettingsLayout.MIN_SIZE,
                    Math.min(dev.ellipog.tenet.client.dev.QuestSettingsLayout.MAX_SIZE, next));
            book.bookState.settingsDraft.size(target, next, revision());
        }
        if (key.equals("minRequired")) {
            // Bounded by the list above it: "3 of 2" is not a rule, and a validator that refuses it is
            // a validator the arrow should not have been able to reach.
            next = Math.max(0, Math.min(QuestPanelLayout.strings(quest, "dependsOn").size(), next));
        }
        else if (key.equals("maxCompletableDependents") || key.equals("invisibleUntilTasks")) {
            next = Math.max(0, Math.min(dev.ellipog.tenet.quest.QuestRules.MAX_COUNT, next));
        }
        book.sendField(target, key, new JsonPrimitive((long) next));
    }

    /**
     * A settings page numeric row as a field: its value, its range, its unit, and what a drag does.
     *
     * <p>The page's steppers are scrubbable fields now, on the same contract as the drawer's: a drag
     * previews through the draft -- the canvas node moves before anything is written -- and the release
     * sends one op. The ranges are the ones {@code stepField} clamps to, so the two paths cannot disagree
     * about what a value may be.
     */
    dev.ellipog.tenet.client.dev.ScrubField settingField(String key, JsonObject quest) {
        if ("x".equals(key) || "y".equals(key)) {
            return new dev.ellipog.tenet.client.dev.ScrubField(intField(quest, key, 0),
                    -100000, 100000, 1, " px")
                    .onPreview(next -> previewQuestField(key, Math.round(next)))
                    .onCommit(next -> commitNumberField(key, Math.round(next)));
        }
        if ("minWidth".equals(key)) {
            // A panel width, in pixels. It used to fall through to the cooldown below and read "0 ticks".
            return new dev.ellipog.tenet.client.dev.ScrubField(intField(quest, key, 0), 0, 4096, 1, " px")
                    .onPreview(next -> previewQuestField(key, Math.round(next)))
                    .onCommit(next -> commitNumberField(key, Math.round(next)));
        }
        if ("minRequired".equals(key)) {
            int max = QuestPanelLayout.strings(quest, "dependsOn").size();
            return new dev.ellipog.tenet.client.dev.ScrubField(intField(quest, key, 0), 0,
                    Math.max(0, max), 1, "")
                    .onPreview(next -> previewQuestField(key, Math.round(next)))
                    .onCommit(next -> commitNumberField(key, Math.round(next)));
        }
        if ("maxCompletableDependents".equals(key) || "invisibleUntilTasks".equals(key)) {
            return new dev.ellipog.tenet.client.dev.ScrubField(intField(quest, key, 0), 0,
                    dev.ellipog.tenet.quest.QuestRules.MAX_COUNT, 1, "")
                    .onPreview(next -> previewQuestField(key, Math.round(next)))
                    .onCommit(next -> commitNumberField(key, Math.round(next)));
        }
        // The cooldown, in ticks: an hour is past any cooldown anyone writes, and the field is the only
        // bound -- the model has none.
        return new dev.ellipog.tenet.client.dev.ScrubField(intField(quest, key, 0), 0, 72000, 1,
                " ticks")
                .onPreview(next -> previewQuestField(key, Math.round(next)))
                .onCommit(next -> commitNumberField(key, Math.round(next)));
    }

    /** A numeric row's commit: one op, the same write the arrows' step made. */
    private void commitNumberField(String key, long value) {
        book.sendField(book.editTarget(), key, new JsonPrimitive(value));
    }

    /** A drag's value, stamped for the canvas and the page without sending it: one op follows on release. */
    private void previewQuestField(String path, long value) {
        String chapter = QuestBookScreen.effectiveChapter();
        String target = book.editTarget();
        if (chapter == null || target == null) {
            return;
        }
        BookScreenState.fieldDraft.set(chapter, target, path, new JsonPrimitive(value),
                ClientQuestCache.treeRevision(), net.minecraft.util.Util.getMillis());
    }

    /** A slider drag: the value under the pointer, remembered for the preview and not yet sent. */
    void dragSliderTo(double mouseX) {
        if (book.bookState.draggingSlider == null || book.bookState.settingsLayout == null) {
            return;
        }
        Slot slot = book.bookState.settingsLayout.slot(book.bookState.draggingSlider);
        if (slot == null) {
            return;
        }
        // Through the viewport, because the pointer is in screen coordinates and the slot is not. This
        // read the layout's own slot until a report came back that a slider "drags to max and stays
        // there": every press in the column was far right of the track it thought it was on, so the
        // value clamped to the maximum on the first pixel. The drawing had been mapped; this had not.
        dev.ellipog.armature.client.ui.kit.Viewport column = book.bookState.settingsView.viewport();
        if (book.bookState.draggingSlider.equals("size")) {
            draggingValue = dev.ellipog.tenet.client.dev.QuestSettingsLayout.valueAtScreen(slot,
                    column, mouseX, dev.ellipog.tenet.client.dev.QuestSettingsLayout.MIN_SIZE,
                    dev.ellipog.tenet.client.dev.QuestSettingsLayout.MAX_SIZE, true);
        }
        else if (book.bookState.draggingSlider.equals("rotation")) {
            draggingValue = dev.ellipog.tenet.client.dev.QuestSettingsLayout.valueAtScreen(slot,
                    column, mouseX, dev.ellipog.tenet.client.dev.QuestSettingsLayout.MIN_ROTATION,
                    dev.ellipog.tenet.client.dev.QuestSettingsLayout.MAX_ROTATION, false);
        }
        else {
            draggingValue = dev.ellipog.tenet.client.dev.QuestSettingsLayout.valueAtScreen(slot,
                    column, mouseX, 25, 100, false) / 100.0;
        }
        stampDragging(revision());
    }

    /**
     * Stamps the value under the pointer into the preview draft, at a revision.
     *
     * <p>Called on every pointer move and again from the frame path, because a drag outlives tree
     * revisions: the settings draft expires on a revision move, and without the re-stamp the preview
     * would snap back to the server's value mid-gesture — and the release, which commits the dragged
     * value, would otherwise look like it committed a value the author never saw.
     */
    void stampDragging(long revision) {
        if (book.bookState.draggingSlider == null) {
            return;
        }
        // The quest the drag belongs to, read here rather than remembered when the drag began: the page
        // cannot outlive a quest switch with a slider still held, and reading it keeps the stamp and the
        // eventual commit aimed at the same quest.
        String target = book.editTarget();
        if (target == null) {
            return;
        }
        if (book.bookState.draggingSlider.equals("size")) {
            book.bookState.settingsDraft.size(target, (int) Math.round(draggingValue), revision);
        }
        else if (book.bookState.draggingSlider.equals("rotation")) {
            book.bookState.settingsDraft.rotation(target, (int) Math.round(draggingValue), revision);
        }
        else {
            book.bookState.settingsDraft.iconScale(target, draggingValue, revision);
        }
    }

    /**
     * A slider's release: the one commit for the whole drag, from the value the pointer was on.
     *
     * <p>The dragged value rather than the draft, and that is the fix for a real fault: the settings
     * draft expires on any tree revision, and a revision landing between the last pointer move and
     * the release would have committed the value the drag <i>started</i> from — the knob snapped back
     * and the old number went to the file. See {@link #stampDragging}.
     */
    void releaseSlider() {
        if (book.bookState.draggingSlider == null || book.editTarget() == null) {
            book.bookState.draggingSlider = null;
            return;
        }
        String key = book.bookState.draggingSlider;
        double value = draggingValue;
        book.bookState.draggingSlider = null;
        if (key.equals("rotation") || key.equals("size")) {
            book.sendField(book.editTarget(), key, new JsonPrimitive((long) Math.round(value)));
        }
        else {
            book.sendField(book.editTarget(), key, new JsonPrimitive(value));
        }
    }

    /** The revision the client's tree is at, for the draft's expiry. */
    private long revision() {
        return ClientQuestCache.treeRevision();
    }

    /**
     * A settings-page number, read from the tree the page is drawn from — which already carries the
     * draft, so an arrow or a slider shows what it asked for before the replica catches up.
     */
    private int intField(JsonObject quest, String key, int fallback) {
        JsonElement value = quest == null ? null : QuestPanelLayout.get(quest, key);
        return value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isNumber()
                ? value.getAsInt() : fallback;
    }

    /** The same, for a fractional field: see {@link #intField}. */
    private double doubleField(JsonObject quest, String key, double fallback) {
        JsonElement value = quest == null ? null : QuestPanelLayout.get(quest, key);
        return value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isNumber()
                ? value.getAsDouble() : fallback;
    }

    /**
     * Leaves the innermost page over the card, and only that one.
     *
     * <p>The card's counterpart of the table panels' way-out rule: the button that leaves a page may not
     * also close the card behind it, or a press from a list of types lands the author two steps away from
     * where they were. Innermost first, the same order Escape already follows — the type and condition
     * picker, then the item picker, then the settings page — and the item picker's own close is reused
     * rather than reimplemented, because it knows it may have been opened *from* the settings page and
     * puts that page back.
     */
    void leaveCardPage() {
        if (book.bookState.pickingEntryType != null || book.bookState.pickingConditionFor != null) {
            book.bookState.pickingEntryType = null;
            book.bookState.pickingConditionFor = null;
        }
        else if (book.bookState.pickingItemPath != null) {
            book.itemPicker.closeItemPicker();
        }
        else if (book.bookState.settingsOpen) {
            book.bookState.settingsOpen = false;
            book.bookState.draggingSlider = null;
            book.bookState.settingsDraft.clear();
        }
        book.rebuildWidgets();
    }

    /**
     * Opens or closes the settings page.
     *
     * <p>Opening it closes the pickers, because the page takes the body and a picker behind it would be
     * a second thing claiming presses that are the page's. Closing it forgets the draft: a value the
     * page was asking for is the page's own state, and leaving it behind would make the canvas draw a
     * number the file does not have.
     */
    void toggleSettings() {
        book.bookState.settingsOpen = !book.bookState.settingsOpen;
        book.bookState.draggingSlider = null;
        if (book.bookState.settingsOpen) {
            book.bookState.pickingEntryType = null;
            book.bookState.pickingConditionFor = null;
            book.bookState.pickingItemPath = null;
        }
        else {
            book.bookState.settingsDraft.clear();
        }
        book.rebuildWidgets();
    }

    /**
     * Whether a press is outside the open panel, asked of the rail the press is about.
     *
     * <h2>Two presentations used to answer this differently, and there is one now</h2>
     *
     * <p>A card is a dialog under a scrim: the book behind it answers nothing, so a press outside is not the
     * first half of any other gesture, and closing on the press is what every dialog does. A rail floats over
     * a canvas that is <b>still live</b> — the press on that canvas is the first half of a pan, and closing
     * on it meant the graph could not be dragged at all without putting the panel away (the report: *"the
     * second my mouse presses down outside the sidebar it closes … i want to be able to pan around"*).
     *
     * <p>So a rail's answer is made on <b>release</b>, and only for a press that did not travel:
     * {@code mouseReleased} closes a panel when {@code !pressMoved && !panelPress} — a click closes, a drag
     * does not. That is the one rule now, for every kind, and this method's own question ("is the pointer
     * inside the rail") is what the press dispatch asks with {@link QuestBookScreen#panelColumnAt} instead.
     */
    void openOverlay(String questId) {
        // Through the rules, not by assignment: opening a quest is a node click, and a node click empties
        // the second column -- the child was opened from the quest that is no longer showing. See
        // `PanelStack.afterNodeClick`, where that is asserted. The dock is not the rules' to take: an author
        // clicking through a chapter keeps their tools.
        //
        // Whether this arrives or swaps is `applyColumns`' to decide: a rail already showing only changes
        // what it holds — re-sliding it would put a full arrival between the click and the quest on a
        // gesture the reader repeats while browsing — while a newly shown one is stamped there. So a swap
        // is instant, and only an arrival is animated, with no stamp at this call site.
        book.applyColumns(PanelStack.afterNodeClick(book.columns()));
        book.bookState.overlayQuest = questId;
        book.bookState.overlayView.scrollTo(0);
        // The row keys mean something else now -- "task:0" was the last quest's first task -- so a
        // hover carried over would light up a row nobody is pointing at for a fifth of a second. The
        // folds go with them: an entry folded here is not the entry at that index in the next quest.
        book.bookState.rowHover.clear();
        book.bookState.entryFolded.clear();
        // A swap keeps whatever the reveal was doing (a click inside the slide does not restart it) and leaves
        // a settled rail settled: `panelRevealStarts` drops an entry only when its slide finishes or its rail
        // leaves the arrangement, so this neither stalls a slide nor starts one. And the entry is per kind, so
        // the dock beside it is not re-slid by this at all.
        panClearOfPanels(questId);
        book.rebuildWidgets();
    }

    /**
     * Moves the view the least it can, so the quest just opened is not hidden under the column that opened
     * for it.
     *
     * <h2>Why the panel needs this and a card did not</h2>
     *
     * <p>Because a column floats over the graph rather than replacing it, so a node near the canvas's right
     * edge can be behind the column the moment it is clicked — the reader opened a quest and cannot see the
     * thing they clicked, which reads as the click having missed. The card never had the problem: it covers
     * the whole canvas, so there was nothing to keep in view.
     *
     * <p><b>Only when it would actually be covered</b>, and by exactly the overlap: a view that moved for a
     * node that was already clear would be the canvas jumping every time somebody clicked, which is worse
     * than the fault. The chapter's own centring is then marked as done, because this is the caller moving
     * the view and `centreCanvas` would otherwise take it back on the next frame — the same hand-off
     * {@code locateOnCanvas} makes.
     */
    private void panClearOfPanels(String questId) {
        ClientQuestCache.Entry entry = book.entryFor(questId);
        String chapter = QuestBookScreen.effectiveChapter();
        // Only once the view is the chapter's, and that is a guard rather than a formality: a chapter that
        // has not been centred yet would be centred on the next frame, and this hands the view to the caller
        // by marking that done -- so panning first would leave a chapter nobody ever centred. It cannot
        // happen through a press (the frames in between have already centred it), which is exactly why it
        // would be a fault nobody could reproduce.
        if (entry == null || chapter == null || !chapter.equals(BookScreenState.pannedChapter)
                || PanelStack.presented(book.columns(), book.panelFolded()).isEmpty()) {
            return;
        }
        int size = book.bookCanvas.nodeSize(entry);
        int right = book.bookCanvas.nodeScreenX(entry) + size;
        int dx = PanelLayout.panToClear(right, book.visibleCanvasRight(), PANEL_PAN_MARGIN);
        if (dx == 0) {
            return;
        }
        Viewport view = book.viewport();
        view.setOffset(view.offsetX() - dx, view.offsetY());
        BookScreenState.pannedChapter = chapter;
        BookScreenState.centred = true;
    }

    /**
     * Marks the moment one rail started arriving, in the one place a rail is opened.
     *
     * <p>At the action rather than on the next tick: {@code tick} runs at twenty hertz, so a start
     * timestamp read there would spend up to forty-nine milliseconds of the reveal
     * before the animation had begun. {@code startGlide} and the locate flash are the precedent, and the
     * reveal's own duration is read from the theme when it is drawn rather than stored here — so a player
     * who changes Motion mid-arrival changes the arrival.
     *
     * <p>Takes the kind, and that is the whole of what makes the dock stay put: a panel opening records
     * <i>its own</i> start, so the rail beside it keeps whatever arrival it had — which for a dock that is
     * already open is none at all.
     *
     * <p>A mode that is off, a theme with no motion and the accessibility switch all come to the same
     * thing through {@code Motion.scaledDuration}: a duration of zero, which {@code PanelLayout.revealed}
     * answers as "already arrived". So this is set unconditionally and the zero case needs no branch.
     */
    void startPanelReveal(PanelKind kind) {
        if (kind != null && kind != PanelKind.NONE) {
            book.bookState.panelRevealStarts.put(kind, net.minecraft.util.Util.getMillis());
        }
    }

    // ------------------------------------------------------------------
    // The reward tables: the card's control, the browser, and the editor
    // ------------------------------------------------------------------

    /**
     * The reward's own path from a table control's path.
     *
     * <p>The control's path is the reward's {@code table} field ({@code rewards.2.table}), and every
     * operation about the reward — pointing it at a table, clearing it — names the reward rather than the
     * field, because that is the object the server edits.
     */
    private static String owningPathOf(String path) {
        return path != null && path.endsWith(".table")
                ? path.substring(0, path.length() - ".table".length())
                : path;
    }

    /** Asks the server for one table edit. The client writes nothing: see {@code ServerTables}. */
    void sendTableOp(dev.ellipog.tenet.editor.TableOp op) {
        // The payload's chapter is unused by a table op -- a table is a file, not a chapter's -- so it
        // is sent empty rather than as a chapter that has nothing to do with the edit.
        //
        // The marker goes first and the op only if it was taken: a refused marker means the queue is full,
        // and an op sent without one is answered by a reply matched to the request before it -- which for a
        // table op is what applies the wrong kind to the table panel's undo budget.
        ClientEditReplies.Request request = ClientEditReplies.noteSent(op.getClass().getSimpleName());
        if (request == null) {
            return;
        }
        TenetNetworking.sendEditorOp("", op, request.id());
    }

    /**
     * The table panels' own header height.
     *
     * <p>Smaller than the quest card's forty-five, which carries an icon, a title and a state tag: these
     * cards hold one word, and a band sized for the other card's contents left a strip of empty surface
     * above the first row -- which is what the screenshot showed as a header with no room to breathe
     * under it.
     */
    private static final int TABLE_HEADER = dev.ellipog.tenet.client.dev.TableEditorLayout.CARD_HEADER;

    /** Air between the header's rule and the first thing in the body. */
    private static final int TABLE_BODY_GAP = dev.ellipog.tenet.client.dev.TableEditorLayout.CARD_GAP;

    /**
     * Where the two table panels draw their bodies: the surface they are on, through the tested layout.
     *
     * <p>{@code surfaceCard(surfaceKind)} rather than {@code geometry().modal()}: the tables are dockable
     * kinds now, and this one expression is where all eight of their callers — the two frames, the two clips,
     * the two bodies, a hint's x and a target's rectangle — learn which surface that is. A table drawn in a
     * column with its rows placed on a card behind it would be the worst version of the fault this file keeps
     * recording: a control drawn in one place and hit in another.
     */
    private BookGeometry.Rect tableBody() {
        return dev.ellipog.tenet.client.dev.TableEditorLayout.body(book.surfaceCard(book.bookState.surfaceKind));
    }

    /**
     * Whether the pointer is over the band a table panel actually scrolls.
     *
     * <p>The two panels scroll different bands — the browser's list under its search box, the editor's
     * rows under its headings, and the roll page's lines in that same band — and a frame that has not
     * been built yet has nothing to scroll, so the answer is no rather than a rectangle of zeroes.
     */
    boolean overTableList(double mouseX, double mouseY) {
        if (book.bookState.overlay == PanelKind.TABLE_BROWSER) {
            return book.bookState.tableBrowserFrame != null && book.bookState.tableBrowserFrame.list().contains(mouseX,
                    mouseY);
        }
        return book.bookState.tableEditorFrame != null && book.bookState.tableEditorFrame.rows().contains(mouseX,
                mouseY);
    }

    /**
     * The card's chrome: the panel, the title strip, the two rules, and the way out.
     *
     * <p>Named for a modal card rather than for a table, which it was: it takes a title and a close action
     * and knows nothing about tables, and a third caller that is not a table panel would have made the old
     * name a lie. The strip's height is {@code TableEditorLayout.CARD_HEADER}, which
     * {@code AssetsLayout.TITLE_HEIGHT} reads as well, so a panel's content starts below the strip by the
     * same number the chrome fills.
     *
     * <p>The close chip is in the strip because that is where every other card's is, and because a panel
     * whose only visible exit is a footer button at the far corner is a panel that hides its own way out.
     * It is the same action the footer's button carries -- one press, one meaning, two places to find it.
     */
    private void drawModalCardChrome(GuiRenderer r, String title, int mouseX, int mouseY,
                                     QuestBookScreen.EditAction closeAction) {
        drawModalCardChrome(r, title, mouseX, mouseY, closeAction, book.bookState.editTargets);
    }

    /**
     * The same, registering the close chip in the list the caller names.
     *
     * <p>Only the Assets panel passes its own: it is the one card here that is not the quest editor's,
     * so the chip's press belongs to its press method rather than to the editor's. See
     * {@link QuestBookScreen#registerInto}.
     */
    void drawModalCardChrome(GuiRenderer r, String title, int mouseX, int mouseY,
                                     QuestBookScreen.EditAction closeAction, List<QuestBookScreen.EditTarget> into) {
        drawModalCardChrome(r, title, "", mouseX, mouseY, closeAction, into);
    }

    /**
     * The same, with a faint second line under the title: the shape the settings, the rewards and the
     * quest card's own headers have, so every card in the book opens with the same band.
     */
    void drawModalCardChrome(GuiRenderer r, String title, String subtitle, int mouseX, int mouseY,
                                     QuestBookScreen.EditAction closeAction, List<QuestBookScreen.EditTarget> into) {
        int left = book.overlayLeft();
        int top = book.overlayTop();
        int w = book.overlayWidth();
        int h = book.overlayHeight();
        BookFrame.drawCard(r, left, top, w, h, TABLE_HEADER, true);
        // Truncated to the room the header has, which is `PanelLayout.headerRight`'s one expression: a
        // table's name is a pack's string and the fold control sits at the header's right end, so a title
        // that ran on would be drawn through the button that pages the column away.
        //
        // No close chip in the band. It was here and nowhere else: the settings, the rewards, the party
        // and the quest card all leave by their footer button, Escape and their tab, and a card that also
        // wore a cross read as a different kind of thing. The footer carries the same action.
        Measure measure = Measure.of(r::textWidth, r.lineHeight());
        int room = Math.max(0, book.headerTextRoom(book.surfaceCard(book.bookState.surfaceKind)) - (left + 14));
        boolean twoLines = subtitle != null && !subtitle.isEmpty();
        r.text(Measure.truncate(title, room, measure), left + 14,
                twoLines ? top + 12 : top + (TABLE_HEADER - 8) / 2, ArmatureTheme.title());
        if (twoLines) {
            r.text(Measure.truncate(subtitle, room, measure), left + 14, top + 26, ArmatureTheme.faint());
        }
    }

    /**
     * The two panels' footer, drawn where every other card in this book keeps its controls.
     *
     * <h2>Why these are drawn targets rather than buttons</h2>
     *
     * <p>They were widgets, and the editor's Done was then the one control in this panel that answered
     * on <b>release</b> while every other control answered on press. That difference is what made it
     * need a dozen clicks: an accepted edit is answered with a tree broadcast, a broadcast moves the
     * revision the screen watches, and the screen rebuilds its widget list -- which replaces the button
     * between its press and its release, so the release lands on an instance that was never held. A
     * drawn target is read from this frame's own drawing, so no rebuild can take it away, whatever
     * caused it (this panel's own edit, another player's, a progress sync).
     *
     * <p>The browser's footer is the same shape, which is the other half of the point: one convention
     * for both panels rather than a widget footer here and a drawn one there.
     *
     * @param browser whether this is the browser's footer: it gets the submit slot's New table, and its
     *                way out says Back rather than Done because it leaves the panel rather than a table.
     */
    private void drawTableFooter(GuiRenderer r, int mouseX, int mouseY, boolean browser) {
        Map<String, BookGeometry.Rect> controls = book.overlayControls(browser);
        BookGeometry.Rect back = controls.get("back");
        if (back == null) {
            return;
        }
        // What this button does, decided once and then both named and pressed from that one answer. The
        // rule is one step back: a page of types or the roll report is left before a table's own back
        // step, and only the editor's root closes the panel. The roll report used to have no case here at
        // all, so the footer said "Done" over it and closed the whole panel -- two steps from where the
        // author was, which is what the general report was about.
        var exit = dev.ellipog.tenet.client.dev.TableEditorLayout
                .exit(browser, tablePage(), !book.bookState.tableStack.isEmpty());
        String out = switch (exit) {
            case LEAVE_BROWSER, TABLE_UP -> Labels.of("tenet.screen.back");
            // "Back" as well, and for the same reason the nested case says it: the press leaves a page
            // rather than the panel. "Done" is kept for the one press that really does end the session in
            // this editor.
            case LEAVE_PICKER, LEAVE_ROLL -> "Back";
            case CLOSE -> Labels.of("tenet.screen.done");
        };
        QuestBookScreen.EditAction action = switch (exit) {
            case LEAVE_BROWSER -> QuestBookScreen.EditAction.TABLE_BACK;
            case LEAVE_PICKER, LEAVE_ROLL -> QuestBookScreen.EditAction.TABLE_PAGE_BACK;
            case TABLE_UP, CLOSE -> QuestBookScreen.EditAction.TABLE_DONE;
        };
        drawTableButton(r, back, out, mouseX, mouseY, action);
        if (!browser) {
            return;
        }
        BookGeometry.Rect slot = controls.get("submit");
        if (slot != null) {
            // Dropped when there is no room at all, rather than registered at zero width: the width was
            // `Math.max(0, ...)`, so on the narrowest card this drew nothing and still took a press aimed
            // at whatever was under it. Any width above a chip is used, and the label truncates into it.
            int width = back.x() - 8 - slot.x();
            if (width >= MIN_TABLE_FOOTER_BUTTON) {
                drawTableButton(r, BookGeometry.Rect.at(slot.x(), slot.y(), width, slot.height()),
                        "New table", mouseX, mouseY, QuestBookScreen.EditAction.TABLE_NEW);
            }
        }
    }

    /** The narrowest a footer button may be and still be a control rather than an invisible target. */
    private static final int MIN_TABLE_FOOTER_BUTTON = 20;

    /**
     * What is open above the table editor's entry list, in one place.
     *
     * <p>Read by the footer to decide what its button leaves, and by nothing else — but read from the
     * state rather than passed in as flags, because the flags were how the roll report came to be missed:
     * a page that nothing names is a page whose way out nobody wrote.
     */
    private dev.ellipog.tenet.client.dev.TableEditorLayout.TablePage tablePage() {
        if (book.bookState.pickingEntryType != null) {
            return dev.ellipog.tenet.client.dev.TableEditorLayout.TablePage.PICKER;
        }
        if (book.bookState.tableShowRoll) {
            return dev.ellipog.tenet.client.dev.TableEditorLayout.TablePage.ROLL;
        }
        return dev.ellipog.tenet.client.dev.TableEditorLayout.TablePage.LIST;
    }

    // ------------------------------------------------------------------
    // The card's control
    // ------------------------------------------------------------------

    /**
     * What the card's table control draws: the reference, the inline table, or nothing.
     *
     * @param inline whether this is a table written in the reward itself rather than a named reference.
     *               Load-bearing rather than informative: an inline table with no handle is one the
     *               editor cannot address and the control gives one to, while a <b>named</b> reference
     *               has no handle to give -- and asking the server to mint one for it is a refusal, a
     *               red toast and a wasted op, once per reward per session. Which is what the log showed.
     */
    private record TableBadge(String title, ItemStack icon, int entries, boolean hasTable, boolean inline,
                              String inlineUid) {
    }

    /**
     * The table a reward's field names, as the control draws it.
     *
     * <p>Read from the quest's own replica rather than from a cache: the reward is what the card is
     * editing, so what it says is the truth about it. A named reference resolves through the synced
     * summaries, which is where the title, the icon and the count of a table come from.
     */
    private TableBadge tableBadgeOf(JsonObject entry, String fieldPath) {
        JsonObject quest = book.replicaQuest();
        String path = fieldPath;
        String prefix = path == null ? "" : path.substring(0, path.lastIndexOf('.') + 1);
        String id = QuestBookScreen.rawValue(quest, prefix + "table");
        if (!id.isEmpty()) {
            ClientQuestCache.TableSummary summary = ClientQuestCache.table(id);
            if (summary != null) {
                return new TableBadge(summary.titleText(), summary.icon(), summary.entries(), true, false, "");
            }
            // A reference this build has not been told about: the id is still the truth, and the file
            // it names may exist -- a server older than the summaries, or a table that failed to load.
            return new TableBadge(id, ItemStack.EMPTY, 0, true, false, "");
        }
        JsonObject inline = rawObject(quest, prefix + "inline");
        if (inline != null) {
            String uid = inline.has("uid") && inline.get("uid").isJsonPrimitive()
                    ? inline.get("uid").getAsString() : "";
            int entries = inline.has("entries") && inline.get("entries").isJsonArray()
                    ? inline.getAsJsonArray("entries").size() : 0;
            String title = inline.has("title") && inline.get("title").isJsonPrimitive()
                    ? inline.get("title").getAsString() : "(inline table)";
            String icon = inline.has("icon") && inline.getAsJsonObject("icon").has("item")
                    ? inline.getAsJsonObject("icon").get("item").getAsString() : "";
            if (icon.isEmpty()) {
                // The first entry that has an item, exactly as the server's own fallback decides it --
                // a table of ore drops shows an ore rather than a generic chest.
                icon = firstItemId(inline);
            }
            return new TableBadge(title, tableIcon(icon), entries, true, true, uid);
        }
        return new TableBadge("", ItemStack.EMPTY, 0, false, false, "");
    }

    /** The first item id anywhere in a table's entries, or empty. */
    private static String firstItemId(JsonObject table) {
        if (!table.has("entries") || !table.get("entries").isJsonArray()) {
            return "";
        }
        for (JsonElement element : table.getAsJsonArray("entries")) {
            if (!element.isJsonObject()) {
                continue;
            }
            JsonObject reward = element.getAsJsonObject().getAsJsonObject("reward");
            if (reward != null && reward.has("item") && reward.get("item").isJsonPrimitive()) {
                return reward.get("item").getAsString();
            }
        }
        return "";
    }

    /** An object at a path in a tree, or null. */
    static JsonObject rawObject(JsonObject root, String path) {
        JsonElement found = root == null ? null : QuestPanelLayout.get(root, path);
        return found != null && found.isJsonObject() ? found.getAsJsonObject() : null;
    }

    /** An item stack from an id, or empty: a table's icon is an id in a file, not a live stack. */
    private static ItemStack tableIcon(String id) {
        net.minecraft.resources.Identifier at = net.minecraft.resources.Identifier.tryParse(id);
        if (at == null) {
            return ItemStack.EMPTY;
        }
        return net.minecraft.core.registries.BuiltInRegistries.ITEM.get(at)
                .map(holder -> dev.ellipog.tenet.quest.ItemStacks.stackOf(holder))
                .orElse(ItemStack.EMPTY);
    }

    /**
     * The card's table control: a card that names the table, a chip that opens it, and a cross.
     *
     * <p>What it replaces is the point: two text boxes, one for an id an author had to know and one for
     * a table's whole body as JSON. Neither is here any more — see {@code EditorField.Kind.TABLE}.
     */
    void drawTableField(GuiRenderer r, EntryFormLayout.Cell cell, JsonObject entry, String member,
                                int index, String path, int mouseX, int mouseY) {
        BookGeometry.Rect whole = cell.value();
        boolean hot = whole.contains(mouseX, mouseY);
        TableBadge badge = tableBadgeOf(entry, path);

        // An inline table is shown but not edited: the panels edit tables that live in files, and a
        // table written inside a reward is a shape the format reads and the UI no longer makes. The card
        // says so in its title, and a press opens the browser -- which is where a real table replaces it.
        BookGeometry.Rect card = BookGeometry.Rect.at(whole.x(), whole.y(),
                Math.max(0, whole.width() - 2 * (TABLE_CHIP + EntryFormLayout.GAP)), whole.height());
        book.questEditor.drawValue(r, card, "", "", false, false, mouseX, mouseY);
        int textX = card.x() + 4;
        if (badge.hasTable()) {
            if (!badge.icon().isEmpty()) {
                r.icon(badge.icon(), card.x() + 2, card.y() + (card.height() - 16) / 2, 16);
                textX = card.x() + 20;
            }
            String label = badge.title().isEmpty() ? "(unnamed table)" : badge.title();
            String counted = badge.entries() > 0 ? label + " (" + badge.entries() + ")" : label;
            // Truncated to stop inside the card, not four pixels past it: `card.right() - textX - 4` let
            // the last four pixels of a long name hang over the card's edge and onto whatever is behind.
            r.text(Measure.truncate(counted, Math.max(0, card.right() - 2 - textX), book.textMeasure(r)), textX,
                    card.y() + (card.height() - 8) / 2, ArmatureTheme.body());
        }
        else {
            r.text(Measure.truncate("Click to select or create a table",
                            Math.max(0, card.right() - 2 - textX), book.textMeasure(r)), textX,
                    card.y() + (card.height() - 8) / 2, hot ? ArmatureTheme.title() : ArmatureTheme.faint());
        }
        book.questEditor.registerTarget(QuestBookScreen.EditAction.TABLE_OPEN, path, card, textX,
                card.y() + (card.height() - 8) / 2, "",
                member, index);

        BookGeometry.Rect edit = BookGeometry.Rect.at(card.right() + EntryFormLayout.GAP, whole.y(),
                TABLE_CHIP, whole.height());
        QuestBookScreen.drawEditAffordance(r, edit, edit.contains(mouseX, mouseY));
        String editLabel = "Edit";
        r.text(editLabel, edit.x() + (edit.width() - r.textWidth(editLabel)) / 2,
                edit.y() + (edit.height() - 8) / 2,
                edit.contains(mouseX, mouseY) ? ArmatureTheme.title() : ArmatureTheme.body());
        book.questEditor.registerTarget(QuestBookScreen.EditAction.TABLE_EDIT, path, edit, edit.x(),
                edit.y() + (edit.height() - 8) / 2, "",
                member, index);

        if (badge.hasTable()) {
            BookGeometry.Rect clear = BookGeometry.Rect.at(edit.right() + EntryFormLayout.GAP, whole.y(),
                    TABLE_CHIP, whole.height());
            boolean clearHot = clear.contains(mouseX, mouseY);
            QuestBookScreen.drawEditAffordance(r, clear, clearHot);
            String cross = "\u00d7";
            r.text(cross, clear.x() + (clear.width() - r.textWidth(cross)) / 2,
                    clear.y() + (clear.height() - 8) / 2,
                    clearHot ? ArmatureTheme.title() : ArmatureTheme.body());
            book.questEditor.registerTarget(QuestBookScreen.EditAction.TABLE_CLEAR, path, clear, clear.x(),
                    clear.y() + (clear.height() - 8) / 2, "", member, index);
        }
    }

    /** The width of the two chips beside a table card. */
    private static final int TABLE_CHIP = 34;

    /** The sum of the weights the rows are drawn from, computed once per frame beside the odds. */
    private double tableWeightTotal;

    /** The odds the rows are drawn from, computed once per frame. See `drawTableEditor`. */
    private dev.ellipog.tenet.quest.loot.RewardTable.Odds tableOdds =
            new dev.ellipog.tenet.quest.loot.RewardTable.Odds(0, java.util.List.of());


    // ------------------------------------------------------------------
    // Opening and closing
    // ------------------------------------------------------------------

    /** The card's card-press: the browser, with this reward as what a choice applies to. */
    void openTableBrowser(QuestBookScreen.EditTarget target) {
        if (book.editTarget() == null || target.path() == null) {
            return;
        }
        book.bookState.tableReturn = book.bookState.overlay == PanelKind.QUEST
                || book.bookState.overlay == PanelKind.ASSETS ? book.bookState.overlay
                : PanelKind.NONE;
        tableOwner = new dev.ellipog.tenet.editor.TableAddress.Owner.InQuest(QuestBookScreen.effectiveChapter(),
                book.editTarget());
        tableOwningPath = owningPathOf(target.path());
        tableQuery = "";
        book.bookState.tableSelected = -1;
        book.bookState.tableBrowserBody.setScrollY(0);
        tableSearch = null;
        book.bookState.tableConfirmReplace = false;
        book.applyColumns(PanelStack.asRoot(book.columns(), PanelKind.TABLE_BROWSER));
        book.rebuildWidgets();
    }

    /**
     * The card's chip-press: the editor on the table this reward has.
     *
     * <p>A named reference opens that file. An inline table opens by its handle, and one that has none
     * opens the browser instead — the control is already asking for a handle, and until it arrives there
     * is nothing to address. No table at all opens the browser, which is where one comes from.
     */
    /**
     * The card's chip-press: the editor on the table this reward has.
     *
     * <p>An inline table opens the browser instead, with a sentence saying why: the panels edit files,
     * and a table written inside a reward is not one. Pressing a table there points the reward at a real
     * one, which is also the way out of the shape.
     */
    void openTableEditorFor(QuestBookScreen.EditTarget target) {
        TableBadge badge = tableBadgeOf(book.replicaQuest(), target.path());
        if (!badge.hasTable() || badge.inline()) {
            if (badge.inline()) {
                book.status("This reward holds a table written inline - choose a table to replace it", true);
            }
            openTableBrowser(target);
            return;
        }
        String id = QuestBookScreen.rawValue(book.replicaQuest(), owningPathOf(target.path()) + ".table");
        if (!id.isEmpty()) {
            openTableEditor(dev.ellipog.tenet.editor.TableAddress.of(id), previewOf(target));
            return;
        }
        openTableBrowser(target);
    }

    /** The preview a table should open with: the mode of the reward it was opened from. */
    private dev.ellipog.tenet.quest.reward.TableReward.Mode previewOf(QuestBookScreen.EditTarget target) {
        JsonObject entry = rawObject(book.replicaQuest(), owningPathOf(target.path()));
        String type = entry != null && entry.has("type") && entry.get("type").isJsonPrimitive()
                ? entry.get("type").getAsString() : "";
        if (type.endsWith(":loot")) {
            return dev.ellipog.tenet.quest.reward.TableReward.Mode.LOOT;
        }
        if (type.endsWith(":all_table")) {
            return dev.ellipog.tenet.quest.reward.TableReward.Mode.ALL_TABLE;
        }
        if (type.endsWith(":choice")) {
            return dev.ellipog.tenet.quest.reward.TableReward.Mode.CHOICE;
        }
        return dev.ellipog.tenet.quest.reward.TableReward.Mode.RANDOM;
    }

    /** The card's cross: the reward stops rolling a table. */
    void clearTable(QuestBookScreen.EditTarget target) {
        if (book.editTarget() == null || target.path() == null) {
            return;
        }
        TableBadge badge = tableBadgeOf(book.replicaQuest(), target.path());
        if (!badge.hasTable()) {
            return;
        }
        // Destroying a hand-built inline table is one press away from a mis-click, so it asks first --
        // the confirm-in-place idiom the quest delete uses. Clearing a *reference* does not: the table
        // itself stays, and pointing the reward at it again is one press.
        if (!badge.inlineUid().isEmpty() && !book.bookState.tableConfirmReplace) {
            book.bookState.tableConfirmReplace = true;
            book.status("Press again to remove this reward's inline table", true);
            return;
        }
        book.bookState.tableConfirmReplace = false;
        tableOwner = new dev.ellipog.tenet.editor.TableAddress.Owner.InQuest(QuestBookScreen.effectiveChapter(),
                book.editTarget());
        tableOwningPath = owningPathOf(target.path());
        draftSelection("");
        sendTableOp(new dev.ellipog.tenet.editor.TableOp.Select(tableOwner, tableOwningPath, ""));
        book.status("tenet.status.cleared", false);
    }

    private void openTableEditor(dev.ellipog.tenet.editor.TableAddress address,
                                 dev.ellipog.tenet.quest.reward.TableReward.Mode preview) {
        // Where to go back to, decided once, when the panel is opened. A panel opened *from* another
        // table panel keeps the overlay it already had, so walking into a nested table and pressing Done
        // twice still ends at the card.
        if (book.bookState.overlay != PanelKind.TABLE_EDITOR && book.bookState.overlay != PanelKind.TABLE_BROWSER) {
            book.bookState.tableReturn = book.bookState.overlay == PanelKind.QUEST
                    || book.bookState.overlay == PanelKind.ASSETS
                    ? book.bookState.overlay : PanelKind.NONE;
        }
        book.bookState.tableAddress = address;
        book.bookState.tableStack.clear();
        book.bookState.tableApplied = 0;
        book.bookState.tableUndone = 0;
        book.bookState.tableFolded.clear();
        book.bookState.tableShowRoll = false;
        book.bookState.tableImportOpen = false;
        book.bookState.tableConfirmReplace = false;
        book.bookState.tableEditorBody.setScrollY(0);
        book.bookState.rollBody.setScrollY(0);
        tableTitleField = null;
        book.bookState.tablePreview = preview;
        book.applyColumns(PanelStack.asRoot(book.columns(), PanelKind.TABLE_EDITOR));
        book.rebuildWidgets();
    }

    /**
     * Closes both panels and forgets what they were about, giving back the overlay they were opened over.
     *
     * <p>The guard on the current overlay is load-bearing: {@code closeOverlay} calls this while closing
     * the *card*, and a close that always restored {@code tableReturn} would resurrect the card it was
     * in the middle of closing.
     */
    void closeTablePanel() {
        boolean open = book.bookState.overlay == PanelKind.TABLE_BROWSER
                || book.bookState.overlay == PanelKind.TABLE_EDITOR;
        PanelKind back = book.bookState.tableReturn;
        book.bookState.tableReturn = PanelKind.NONE;
        // Read before the address is cleared: the drafts are keyed by it, and a panel that closed
        // leaving its pending values behind would have them answer for the next table opened.
        String draftedOwner = book.bookState.tableAddress == null ? null : tableOwnerKey();
        // The drafts and the roll go now rather than with the exit: they are model state, not drawn
        // state, and the surface sinking out reads the committed copy either way. The UI nullings below
        // are what the exit draws from, so only they wait out the slide.
        tableDraft.forgetOwner(QuestBookScreen.effectiveChapter(), draftedOwner);
        dev.ellipog.tenet.client.ClientTableRoll.clear();
        if (open) {
            // Through the rules first, so the exit is recorded before its content is owed: the teardown
            // below is keyed under what this call retires. `NONE` is the book: whatever the panel was
            // opened from takes the arrangement, so a second column a pick left behind cannot outlive
            // the panel it belonged to.
            book.applyColumns(PanelStack.asRoot(book.columns(), back));
        }
        book.deferForLastDeparted(() -> {
            book.bookState.tableAddress = null;
            book.bookState.tableStack.clear();
            tableOwner = null;
            tableOwningPath = null;
            tableSearch = null;
            tableTitleField = null;
            book.bookState.tableFolded.clear();
            book.bookState.tableShowRoll = false;
            book.bookState.tableImportOpen = false;
            book.bookState.tableConfirmReplace = false;
            // And the state that names something outside the panel: a delete armed for a table the next open
            // would not be showing, the popover's anchor and its drawn box, and the table field an open inline
            // editor was writing to -- a box that outlived its panel would commit to whatever table came next.
            tableConfirmDelete = null;
            tableImportAnchor = null;
            book.bookState.tableImportBox = null;
            book.bookState.tableEdit = null;
        }, PanelKind.TABLE_BROWSER, PanelKind.TABLE_EDITOR);
        // Every picker the panel could have left armed, from the one place that knows them all. Without
        // this the type picker page survived the panel: the card behind it drew the list of reward types
        // and the author's next press added a reward to the wrong thing.
        book.bookAssets.clearPickers();
    }

    /** Escape and `Done`: one table back, or the panel closed when there is nothing behind it. */
    void popTableEditor() {
        if (!book.bookState.tableStack.isEmpty()) {
            book.bookState.tableAddress = book.bookState.tableStack.pop();
            // Back to the table that was left, with its own budget: the steps taken inside the nested
            // table are not this one's to undo.
            book.bookState.tableApplied = 0;
            book.bookState.tableUndone = 0;
            book.bookState.tableFolded.clear();
            book.bookState.tableShowRoll = false;
            book.bookState.tableImportOpen = false;
            tableTitleField = null;
            book.rebuildWidgets();
            return;
        }
        closeTablePanel();
        book.rebuildWidgets();
    }

    /** Opens a table an entry points at, remembering the one being left. */
    void pushTableEditor(dev.ellipog.tenet.editor.TableAddress address) {
        if (book.bookState.tableAddress != null) {
            book.bookState.tableStack.push(book.bookState.tableAddress);
        }
        book.bookState.tableAddress = address;
        // The budget is the frame's: walking into a nested table starts a new one, so Ctrl+Z there
        // cannot reach back into the table that was left -- which is the whole point of the floor.
        book.bookState.tableApplied = 0;
        book.bookState.tableUndone = 0;
        book.bookState.tableFolded.clear();
        book.bookState.tableShowRoll = false;
        book.bookState.tableImportOpen = false;
        tableTitleField = null;
        book.rebuildWidgets();
    }

    // ------------------------------------------------------------------
    // Reading the table
    // ------------------------------------------------------------------

    /**
     * The table panels' own draft, and the key a table is filed under in it.
     *
     * <p>A <b>separate</b> {@code FieldDraft}, not a key convention inside the card's. The card's
     * reconciler judges every draft it holds against the chapter's copy — that is what it is for — and a
     * table's values are in a different file: with one shared draft, a table's pending weight was
     * checked against a quest copy that has never heard of {@code entries}, read as "the list moved
     * under it", and dropped. A separate instance is what makes "the copy this panel draws" the only
     * thing its drafts are judged against; see {@code FieldDraft.reconcileOwner}.
     */
    static final dev.ellipog.tenet.client.dev.FieldDraft tableDraft =
            new dev.ellipog.tenet.client.dev.FieldDraft();

    /** The key one table's drafts are filed under: the table's own name, unique within this draft. */
    String tableOwnerKey() {
        return tableDescribe();
    }

    /**
     * The table the editor is on, decoded, or empty when it cannot be read or has not arrived.
     *
     * <p><b>Through the draft</b>, which is what makes the panel optimistic: every read in the editor —
     * the rows, the counts, the odds beside each weight, the total, the rolls — goes through this one
     * model, so a press that drafted a value shows it immediately instead of waiting for the replica.
     * Without it a stepper computed its next value from the copy the server had not answered about yet,
     * and five fast presses sent the same {@code +1} five times: the number never moved, which is what
     * "I have to press it loads of times" was.
     */
    java.util.Optional<dev.ellipog.tenet.quest.loot.RewardTable> tableModel() {
        JsonObject root = tableDraftedRoot();
        if (root == null) {
            decodedFrom = null;
            decodedModel = java.util.Optional.empty();
            return decodedModel;
        }
        if (root == decodedFrom) {
            return decodedModel;
        }
        decodedFrom = root;
        decodedModel = dev.ellipog.tenet.quest.loot.RewardTable.CODEC
                .parse(com.mojang.serialization.JsonOps.INSTANCE, root)
                .result();
        return decodedModel;
    }

    /** The table's tree with this panel's pending values applied, or null when there is no copy yet. */
    JsonObject tableDraftedRoot() {
        JsonObject root = tableRootMemo();
        return root == null ? null
                : tableDraft.overlaid(QuestBookScreen.effectiveChapter(), tableOwnerKey(), root);
    }

    /**
     * The decoded table, remembered against the tree it was decoded from.
     *
     * <p>Decoding runs the whole reward dispatch codec — every entry, every reward — and the panel asked
     * for it several times a frame (once for the body, again for the title). Cached by the tree's own
     * identity, which changes only when a replica arrives or a draft moves, so the decode happens when
     * the table changes and not sixty times a second.
     */
    private JsonObject decodedFrom;
    private java.util.Optional<dev.ellipog.tenet.quest.loot.RewardTable> decodedModel =
            java.util.Optional.empty();

    /**
     * The table's tree, remembered against the copy it came from.
     *
     * <p>For an inline table the root is <b>found</b> — a walk of the quest's rewards, once per read,
     * and the panel reads it per row when a row is folded — so it is resolved once per copy instead.
     *
     * <p>Keyed by the replica's <b>generation</b> rather than by its revision, and that is the fix for a
     * panel that sat on "Waiting for this table's file..." forever: the revision a copy carries is the
     * tree's, which is zero in a fresh world, so {@code dice@0} before the file arrived and {@code dice@0}
     * after it did were the same key — the memo kept answering null and the copy was never read. A
     * generation moves on every change, so an arrival cannot be mistaken for the state before it.
     */
    private String tableRootKey = "";
    private JsonObject tableRootCache;

    private JsonObject tableRootMemo() {
        String key = tableDescribe() + "@"
                + dev.ellipog.tenet.client.ClientTableReplica.generation()
                + "/" + QuestBookScreen.effectiveChapter();
        if (key.equals(tableRootKey)) {
            return tableRootCache;
        }
        tableRootKey = key;
        tableRootCache = tableRoot();
        return tableRootCache;
    }

    /**
     * Asks for a value, optimistically: the draft first, then the op.
     *
     * <p>The order is the point. The draft is what the next frame draws and what the next press
     * computes from; the op is the ask. A refusal forgets the draft, so the panel falls back to the
     * file the server actually has.
     */
    void sendTableValue(String path, com.google.gson.JsonElement value) {
        if (book.bookState.tableAddress == null) {
            return;
        }
        tableDraft.set(QuestBookScreen.effectiveChapter(), tableOwnerKey(), path, value, tableCopyRevision(),
                net.minecraft.util.Util.getMillis());
        sendTableOp(new dev.ellipog.tenet.editor.TableOp.Set(book.bookState.tableAddress, path, value));
    }

    /** The revision of the copy the editor is drawing: the table's file, or the chapter's for an inline. */
    private long tableCopyRevision() {
        if (book.bookState.tableAddress == null) {
            return 0L;
        }
        return switch (book.bookState.tableAddress.owner()) {
            case dev.ellipog.tenet.editor.TableAddress.Owner.Named named -> {
                var copy = dev.ellipog.tenet.client.ClientTableReplica.of(named.id());
                yield copy == null ? 0L : copy.revision();
            }
            case dev.ellipog.tenet.editor.TableAddress.Owner.InQuest quest -> {
                var copy = ClientChapterReplica.of(quest.chapter());
                yield copy == null ? 0L : copy.revision();
            }
        };
    }

    /**
     * Drops the drafts the copy has caught up with, and the ones it has disagreed with for too long.
     *
     * <p>Per owner, against the copy this panel draws: the table's own file for a named table, the
     * chapter's for an inline one. {@code FieldDraft} carries the rule.
     */
    private void reconcileTableDrafts() {
        if (book.bookState.tableAddress == null) {
            return;
        }
        tableDraft.reconcileOwner(QuestBookScreen.effectiveChapter(), tableOwnerKey(), tableCopyRevision(),
                (owner, path) -> QuestPanelLayout.get(tableRoot(), path),
                net.minecraft.util.Util.getMillis());
    }

    /**
     * The table's own JSON: the file's replica for a named table, or the quest's copy of an inline one.
     *
     * <p>Null while the copy is still on its way, which the panels draw as a sentence rather than as an
     * empty table — "the copy has not arrived" and "this table has no entries" are different facts, and
     * a panel that showed the second for the first would have an author adding entries to a table that
     * already has them.
     */
    private JsonObject tableRoot() {
        return tableRootRaw();
    }

    /** The lookup itself: the memo above is what the panel calls. */
    private JsonObject tableRootRaw() {
        if (book.bookState.tableAddress == null) {
            return null;
        }
        return switch (book.bookState.tableAddress.owner()) {
            case dev.ellipog.tenet.editor.TableAddress.Owner.Named named -> {
                dev.ellipog.tenet.client.ClientTableReplica.Copy copy =
                        dev.ellipog.tenet.client.ClientTableReplica.of(named.id());
                yield copy == null ? null : copy.root();
            }
            // A quest's own table is a reward's field rather than a table the editor opens: the panels
            // edit files, and an inline table is shown on the card and replaced from the browser.
            case dev.ellipog.tenet.editor.TableAddress.Owner.InQuest quest -> null;
        };
    }

    /**
     * Asks for the copy the editor is showing, and for the chapter's when the table is inline.
     *
     * <p>Once per revision rather than once per frame: {@code claim} records the attempt, which is what
     * keeps a screen that draws sixty times a second from asking sixty times a second. The tree's
     * revision moves on every reload, so an edit anywhere — this client's or another's — re-fetches.
     */
    private void claimTableData() {
        if (book.bookState.tableAddress == null) {
            return;
        }
        long revision = ClientQuestCache.treeRevision();
        long now = net.minecraft.util.Util.getMillis();
        switch (book.bookState.tableAddress.owner()) {
            case dev.ellipog.tenet.editor.TableAddress.Owner.Named named -> {
                if (dev.ellipog.tenet.client.ClientTableReplica.claim(named.id(), revision, now)) {
                    // A sentinel, because a replica request can be refused and the refusal arrives as
                    // the same EditorReplyPayload an edit's answer does. Without one, every refusal
                    // consumed the next edit's sentinel: the reply that followed was matched to the
                    // wrong op, the undo budget counted the wrong kind, and a refusal discarded the
                    // optimistic values of an edit that was never refused.
                    //
                    // A refused marker means the queue is full, so the request is not sent -- and the
                    // claim is left standing, so the next frame asks again rather than the panel going
                    // permanently empty. See `ClientEditReplies.noteSent`.
                    if (ClientEditReplies.noteSent(QuestBookScreen.REPLICA_SENTINEL) != null) {
                        book.bookState.replicaAskedFor = named.id();
                        ArmatureNetwork.sendToServer(
                                new dev.ellipog.tenet.net.TableReplicaRequestPayload(named.id()));
                    }
                }
            }
            case dev.ellipog.tenet.editor.TableAddress.Owner.InQuest quest -> {
                if (ClientChapterReplica.claim(quest.chapter(), revision, now)
                        && ClientEditReplies.noteSent(QuestBookScreen.REPLICA_SENTINEL) != null) {
                    ArmatureNetwork.sendToServer(
                            new dev.ellipog.tenet.net.ReplicaRequestPayload(quest.chapter()));
                }
            }
        }
    }

    /** What to call the table on screen, for a header. */
    String tableTitle() {
        var model = tableModel();
        if (book.bookState.tableAddress == null) {
            return "";
        }
        return switch (book.bookState.tableAddress.owner()) {
            case dev.ellipog.tenet.editor.TableAddress.Owner.Named named ->
                    model.map(table -> table.displayTitle(named.id())).orElse(named.id());
            case dev.ellipog.tenet.editor.TableAddress.Owner.InQuest quest ->
                    model.map(table -> table.displayTitle(quest.quest())).orElse("(inline table)");
        };
    }

    /** What to call a table in a sentence: its id, or where an inline one lives. */
    private String tableDescribe() {
        return book.bookState.tableAddress == null ? "" : book.bookState.tableAddress.describe();
    }

    /**
     * Whether the file itself carries a title, as opposed to the id {@link #tableTitle()} falls back to.
     *
     * <p>The difference is what the title box's two questions are: "does this table need a name typed"
     * (no title in the file) and "would this write change anything" (a blank box over an absent title
     * does not). Both read the model rather than the box, because the box is seeded with the display
     * fallback and so is never empty for a named table.
     */
    private boolean tableTitled() {
        return tableModel().map(table -> table.title().isPresent()).orElse(false);
    }

    // ------------------------------------------------------------------
    // The browser
    // ------------------------------------------------------------------

    void buildTableBrowserWidgets() {
        book.bookState.tableBrowserFrame = dev.ellipog.tenet.client.dev.TableBrowserLayout.Frame.of(tableBody());
        BookGeometry.Rect search = book.bookState.tableBrowserFrame.search();
        String kept = tableSearch == null ? tableQuery : tableSearch.value();
        tableSearch = new ArmatureTextField(search.x(), search.y(), search.width(), search.height(), kept);
        tableSearch.onSubmit(text -> { });
        tableSearch.colours(ArmatureTheme.title(), ArmatureTheme.recessed(), ArmatureTheme.panelEdge());
        book.addRenderableWidget(tableSearch);
        book.setFocused(tableSearch);
        // The placeholder after the pass, clipped to the list's band. **Added here, where the widget is
        // built, rather than in the draw method**: a redraw added per frame is never cleared (the list lives
        // until the next rebuild), so the browser accumulated one per frame for as long as it stayed open and
        // drew every one of them -- the longer an author browsed, the slower every frame became. The guard is
        // for the other end of the same window: this closure outlives the field it draws for by however long
        // it takes the next rebuild to arrive, and closing the panel nulls the field.
        BookGeometry.Rect clipTo = tableBody();
        book.bookState.panelOverlays.add(r -> {
            if (tableSearch == null || !tableSearch.value().isEmpty()) {
                return;
            }
            try (GuiRenderer.Scoped clip = r.clip(clipTo.x(), clipTo.y(), clipTo.right(), clipTo.bottom())) {
                r.text("Search tables", tableSearch.getX() + 4, tableSearch.getY() + 5,
                        ArmatureTheme.faint());
            }
        });

        // The footer is drawn by `drawTableFooter`, not built here: New table in the submit slot and
        // Back where Back always is. The first version drew two buttons inside the toolbar band, where
        // the search box covered them.
    }

    /** Closes a table panel and rebuilds: what every one of its footer buttons does. */
    void closeTablePanelAndRebuild() {
        closeTablePanel();
        book.rebuildWidgets();
    }

    void drawTableBrowser(GuiRenderer r, int mouseX, int mouseY) {
        drawModalCardChrome(r, "Reward tables", mouseX, mouseY, QuestBookScreen.EditAction.TABLE_BACK);
        drawTableFooter(r, mouseX, mouseY, true);
        if (book.bookState.tableBrowserFrame == null) {
            return;
        }
        BookGeometry.Rect body = tableBody();

        // The query is the box's own text: the field does not rebuild the screen on every character --
        // the rows are read here, once a frame, which is the same reason the picker's list is composed
        // at draw time. A query that changed means the list did, so the selection and the scroll go back
        // to the top rather than pointing at a row that is no longer there.
        String typed = tableSearch == null ? tableQuery : tableSearch.value();
        if (!typed.equals(tableQuery)) {
            tableQuery = typed;
            book.bookState.tableSelected = -1;
            book.bookState.tableBrowserBody.setScrollY(0);
        }
        tableRows = dev.ellipog.tenet.client.dev.TableBrowserLayout.rows(summaryRows(), tableQuery);
        // The bounds, the range, the clamp and the bar in one call: the list's own height is what the
        // viewport needs, and the clamp it applies here is the one the drawing and the bar both read.
        // There used to be a `Math.max(0, Math.min(...))` against `maxScroll` at this line and the same
        // expression again in the wheel's branch, which is two places for one range to be wrong.
        QuestBookScreen.bindList(book.bookState.tableBrowserBody, book.bookState.tableBrowserBar,
                book.bookState.tableBrowserFrame.list(),
                book.bookState.tableBrowserFrame.scrollbar(),
                dev.ellipog.tenet.client.dev.TableBrowserLayout.contentHeight(tableRows),
                dev.ellipog.tenet.client.dev.TableBrowserLayout.ROW_HEIGHT);
        int tableScroll = book.bookState.tableBrowserBody.scrollY();

        try (GuiRenderer.Scoped clip = r.clip(book.bookState.tableBrowserFrame.list().x(),
                book.bookState.tableBrowserFrame.list().y(),
                book.bookState.tableBrowserFrame.list().right(), book.bookState.tableBrowserFrame.list().bottom())) {
            for (int i = 0; i < tableRows.size(); i++) {
                BookGeometry.Rect rect = dev.ellipog.tenet.client.dev.TableBrowserLayout
                        .rowRect(tableRows, book.bookState.tableBrowserFrame, tableScroll, i);
                if (rect.bottom() < book.bookState.tableBrowserFrame.list().y()
                        || rect.y() > book.bookState.tableBrowserFrame.list().bottom()) {
                    continue;
                }
                drawTableBrowserRow(r, i, rect, mouseX, mouseY);
            }
        }
        // Outside the clip, like every bar here: the strip is not part of the list's content, and a bar
        // clipped to the band would lose the ends of its own groove on the frame it was needed most.
        QuestBookScreen.drawBar(r, book.bookState.tableBrowserBar, mouseX, mouseY);
        if (tableRows.size() <= 1) {
            String empty = tableQuery.isBlank()
                    ? "No reward tables yet - New table makes one."
                    : "Nothing matches that.";
            r.text(Measure.truncate(empty, body.width(), book.textMeasure(r)), body.x() + 4,
                    book.bookState.tableBrowserFrame.list().y() + 6, ArmatureTheme.faint());
        }
    }

    /**
     * Moves the browser's keyboard row, and scrolls it into view.
     *
     * <p>The scroll is set from the row's own rectangle rather than nudged by a row, so a selection that
     * moved further than one — Enter on a row from before a search re-listed the table, or the first
     * arrow press landing on row one from nothing selected — arrives on screen instead of off it.
     */
    void stepTableBrowser(int by) {
        if (tableRows.isEmpty()) {
            return;
        }
        int at = book.bookState.tableSelected < 0
                ? (by > 0 ? 0 : tableRows.size() - 1)
                : book.bookState.tableSelected + by;
        book.bookState.tableSelected = Math.max(0, Math.min(tableRows.size() - 1, at));
        if (book.bookState.tableBrowserFrame == null) {
            return;
        }
        int pitch = dev.ellipog.tenet.client.dev.TableBrowserLayout.ROW_HEIGHT;
        // The range first, so the two writes below are clamped against this list's own end rather than
        // against whatever the last frame left behind -- this runs from a key press, which is not
        // ordered against the drawing pass.
        book.bookState.tableBrowserBody.setContentSize(book.bookState.tableBrowserFrame.list().width(),
                dev.ellipog.tenet.client.dev.TableBrowserLayout.contentHeight(tableRows));
        BookGeometry.Rect rect = dev.ellipog.tenet.client.dev.TableBrowserLayout
                .rowRect(tableRows, book.bookState.tableBrowserFrame,
                        book.bookState.tableBrowserBody.scrollY(), book.bookState.tableSelected);
        if (rect.y() < book.bookState.tableBrowserFrame.list().y()) {
            book.bookState.tableBrowserBody.setScrollY(book.bookState.tableSelected * pitch);
        }
        else if (rect.bottom() > book.bookState.tableBrowserFrame.list().bottom()) {
            book.bookState.tableBrowserBody.setScrollY((book.bookState.tableSelected
                    + 1) * pitch - book.bookState.tableBrowserFrame.list().height());
        }
    }

    private void drawTableBrowserRow(GuiRenderer r, int index, BookGeometry.Rect rect, int mouseX,
                                     int mouseY) {
        var layout = dev.ellipog.tenet.client.dev.TableBrowserLayout.class;
        var row = tableRows.get(index);
        boolean hot = rect.contains(mouseX, mouseY);
        boolean selected = index == book.bookState.tableSelected;
        if (hot || selected) {
            r.fill(rect.x(), rect.y(), rect.right(), rect.bottom(),
                    selected ? ArmatureTheme.hoverRing() : ArmatureTheme.raised());
        }
        if (row.kind() == dev.ellipog.tenet.client.dev.TableBrowserLayout.Kind.NONE) {
            r.text("None - this reward rolls no table", rect.x() + 4, rect.y() + (rect.height() - 8) / 2,
                    ArmatureTheme.body());
            book.questEditor.registerTarget(QuestBookScreen.EditAction.TABLE_ROW, "", rect, rect.x(),
                    rect.y(), "", null, index);
            return;
        }
        // The stack the tree sent, component patch and all -- not the bare id resolved again here, which
        // threw away the components `QuestSync` carries precisely so a table whose icon is a renamed or
        // enchanted item draws *that* item. `tableIcon` stays for a summary this client was not sent, or
        // one whose icon id does not resolve.
        ClientQuestCache.TableSummary summary = ClientQuestCache.table(row.id());
        ItemStack icon = summary != null ? summary.icon() : tableIcon(row.iconId());
        BookGeometry.Rect iconBox = dev.ellipog.tenet.client.dev.TableBrowserLayout.icon(rect);
        if (!icon.isEmpty()) {
            r.icon(icon, iconBox.x(), iconBox.y(), 16);
        }
        BookGeometry.Rect nameBox = dev.ellipog.tenet.client.dev.TableBrowserLayout.name(rect);
        BookGeometry.Rect detailBox = dev.ellipog.tenet.client.dev.TableBrowserLayout.detail(rect);
        r.text(Measure.truncate(row.title(), nameBox.width(), book.textMeasure(r)), nameBox.x(),
                rect.y() + (rect.height() - 8) / 2, ArmatureTheme.title());
        String detail = row.id() + "  (" + row.entries() + ")";
        r.text(Measure.truncate(detail, detailBox.width(), book.textMeasure(r)), detailBox.x(),
                rect.y() + (rect.height() - 8) / 2, ArmatureTheme.faint());
        if (row.current()) {
            // Which one this reward already rolls, said on the row rather than left to be remembered:
            // the browser's first job is "what am I rolling now?".
            BookGeometry.Rect tag = dev.ellipog.tenet.client.dev.TableBrowserLayout.currentTag(rect);
            r.text("current", tag.x(), rect.y() + (rect.height() - 8) / 2, ArmatureTheme.title());
        }
        // No hover sentence: the row already says the id and the count, the tag says whether it is the
        // one in force, and what a press does is what the panel behind it will show.

        var buttons = dev.ellipog.tenet.client.dev.TableBrowserLayout.buttons(rect);
        drawTableButton(r, buttons.edit(), "Edit", mouseX, mouseY, QuestBookScreen.EditAction.TABLE_ROW_EDIT, index);
        drawTableButton(r, buttons.copy(), "Copy", mouseX, mouseY, QuestBookScreen.EditAction.TABLE_ROW_COPY, index);
        drawTableButton(r, buttons.delete(), "\u00d7", mouseX, mouseY,
                QuestBookScreen.EditAction.TABLE_ROW_DELETE, index);
        book.questEditor.registerTarget(QuestBookScreen.EditAction.TABLE_ROW, "",
                dev.ellipog.tenet.client.dev.TableBrowserLayout.body(rect),
                nameBox.x(), rect.y(), "", null, index);
    }

    /** The tables the server told this client about, as browser rows. */
    private java.util.List<dev.ellipog.tenet.client.dev.TableBrowserLayout.Row> summaryRows() {
        String current = currentTableId();
        java.util.List<dev.ellipog.tenet.client.dev.TableBrowserLayout.Row> rows = new java.util.ArrayList<>();
        for (ClientQuestCache.TableSummary summary : ClientQuestCache.tables()) {
            rows.add(dev.ellipog.tenet.client.dev.TableBrowserLayout.Row.table(summary.id(),
                    summary.titleText(), summary.iconId(), summary.entries(),
                    summary.id().equals(current)));
        }
        return rows;
    }

    /**
     * The table the reward the browser is open for rolls right now, or empty.
     *
     * <p>Read through the draft, like the card's own badge: a selection the author has just made is a
     * pending value until the server's copy holds it, and a "current" tag that disagreed with the card
     * behind it would be the panel arguing with itself.
     */
    private String currentTableId() {
        if (tableOwner == null || tableOwningPath == null) {
            return "";
        }
        JsonObject owner = tableOwnerRoot();
        if (owner == null) {
            return "";
        }
        if (tableOwner instanceof dev.ellipog.tenet.editor.TableAddress.Owner.InQuest quest) {
            owner = BookScreenState.fieldDraft.overlaid(quest.chapter(), quest.quest(), owner);
        }
        return QuestBookScreen.rawValue(owner, tableOwningPath + ".table");
    }

    /**
     * Drafts a reward's table reference before the op that changes it.
     *
     * <p>What makes a selection instant: the card's badge is drawn from `replicaQuest()`, which is the
     * draft-overlaid tree, so writing the new reference here means the card behind the browser shows the
     * table the author just pressed instead of the one the replica still holds. The draft converges the
     * moment the chapter's copy agrees, and a refusal drops it — see `FieldDraft`.
     *
     * <p>Only for a reward in a quest: a table's own file has no card behind it, and an inline table's
     * handle is the server's to mint, so there is nothing here to draft for that case.
     */
    private void draftSelection(String tableId) {
        if (!(tableOwner instanceof dev.ellipog.tenet.editor.TableAddress.Owner.InQuest quest)
                || tableOwningPath == null) {
            return;
        }
        long revision = tableCopyRevision();
        long now = net.minecraft.util.Util.getMillis();
        BookScreenState.fieldDraft.set(quest.chapter(), quest.quest(), tableOwningPath + ".table",
                tableId == null || tableId.isBlank() ? null : new com.google.gson.JsonPrimitive(tableId),
                revision, now);
        BookScreenState.fieldDraft.set(quest.chapter(), quest.quest(), tableOwningPath + ".inline", null,
                revision, now);
    }

    private void drawTableButton(GuiRenderer r, BookGeometry.Rect box, String label, int mouseX,
                                 int mouseY, QuestBookScreen.EditAction action) {
        drawTableButton(r, box, label, mouseX, mouseY, action, -1);
    }

    /**
     * The same, registering into the list the caller names.
     *
     * <p>The Assets panel's footer is the only caller that passes its own list: the card's own footer
     * answers in {@link QuestBookScreen#editTargets}, which is the list its press method walks, and the Assets panel's
     * answers in {@link QuestBookScreen#assetsTargets}, which is the list *its* press method walks. A helper that
     * picked for its caller put three controls on screen that no press could reach -- see
     * {@link QuestBookScreen#registerInto}.
     */
    void drawTableButton(GuiRenderer r, BookGeometry.Rect box, String label, int mouseX,
                                 int mouseY, QuestBookScreen.EditAction action, List<QuestBookScreen.EditTarget> into) {
        drawTableButton(r, box, label, mouseX, mouseY, action, -1, into);
    }

    private void drawTableButton(GuiRenderer r, BookGeometry.Rect box, String label, int mouseX,
                                 int mouseY, QuestBookScreen.EditAction action, int index) {
        drawTableButton(r, box, label, mouseX, mouseY, action, index, book.bookState.editTargets);
    }

    private void drawTableButton(GuiRenderer r, BookGeometry.Rect box, String label, int mouseX,
                                 int mouseY, QuestBookScreen.EditAction action, int index,
                                         List<QuestBookScreen.EditTarget> into) {
        boolean hot = box.contains(mouseX, mouseY);
        QuestBookScreen.drawEditAffordance(r, box, hot);
        // Truncated first, and the offset measured from what is drawn: centring the *untruncated* label
        // is what threw a word off a narrow button -- the text on screen was shorter than the text
        // measured, so the centring was computed for a word that is not there. The browser's 14-pixel
        // cross and a "New table" on a narrow card are both that case.
        String shown = Measure.truncate(label, Math.max(0, box.width() - 4), book.textMeasure(r));
        r.text(shown, box.x() + (box.width() - r.textWidth(shown)) / 2, box.y() + (box.height() - 8) / 2,
                hot ? ArmatureTheme.title() : ArmatureTheme.body());
        book.questEditor.registerInto(into, action, "", box, box.x(), box.y(), "", null, index);
    }

    void pressTableBrowser(double mouseX, double mouseY) {
        if (book.bookState.tableBrowserFrame == null) {
            return;
        }
        // The keyboard's row: the arrows move it, Enter takes it. Read here rather than in `keyPressed`
        // because the row list is rebuilt every frame, and this is the frame the press belongs to.
        for (QuestBookScreen.EditTarget target : book.bookState.editTargets) {
            if (!target.box().contains(mouseX, mouseY)) {
                continue;
            }
            switch (target.action()) {
                case TABLE_ROW -> {
                    book.bookState.tableSelected = target.index();
                    pressTableBrowserRow(target.index());
                }
                case TABLE_ROW_EDIT -> {
                    var row = tableRows.get(target.index());
                    openTableEditor(dev.ellipog.tenet.editor.TableAddress.of(row.id()),
                            dev.ellipog.tenet.quest.reward.TableReward.Mode.RANDOM);
                }
                case TABLE_ROW_COPY -> duplicateTable(tableRows.get(target.index()).id());
                case TABLE_ROW_DELETE -> {
                    // One press, and the file is renamed out of the way. The server refuses while anything
                    // still points at the table, so this is not a data-loss hole -- but a `×` is the
                    // narrowest control on the row, one column from Copy, and the press that reloads the
                    // browser is the same press, so it asks first: the confirm-in-place idiom this panel
                    // already uses for an inline table and for removing an entry.
                    String id = tableRows.get(target.index()).id();
                    if (!id.equals(tableConfirmDelete)) {
                        tableConfirmDelete = id;
                        book.status("Press again to delete \"" + id + "\"", true);
                        return;
                    }
                    tableConfirmDelete = null;
                    deleteTable(id);
                }
                case TABLE_NEW -> createTable();
                case TABLE_BACK -> closeTablePanelAndRebuild();
                default -> {
                }
            }
            return;
        }
    }

    void pressTableBrowserRow(int index) {
        if (index < 0 || index >= tableRows.size()) {
            return;
        }        var row = tableRows.get(index);
        if (tableOwner == null || tableOwningPath == null) {
            return;
        }
        if (row.kind() == dev.ellipog.tenet.client.dev.TableBrowserLayout.Kind.NONE) {
            if (book.bookState.tableConfirmReplace && inlinePresent()) {
                // The author has now said it twice: the inline table goes.
                book.bookState.tableConfirmReplace = false;
                draftSelection("");
                sendTableOp(new dev.ellipog.tenet.editor.TableOp.Select(tableOwner, tableOwningPath, ""));
                closeTablePanel();
                book.rebuildWidgets();
                return;
            }
            if (inlinePresent()) {
                book.bookState.tableConfirmReplace = true;
                book.status("Press again to remove this reward's inline table", true);
                return;
            }
            draftSelection("");
            sendTableOp(new dev.ellipog.tenet.editor.TableOp.Select(tableOwner, tableOwningPath, ""));
            closeTablePanel();
            book.rebuildWidgets();
            return;
        }
        // Choosing a table for a reward that holds an inline one would throw the inline away, so it
        // asks first: one press must not destroy a table somebody built.
        if (inlinePresent() && !book.bookState.tableConfirmReplace) {
            book.bookState.tableConfirmReplace = true;
            book.bookState.tableSelected = index;
            book.status("This reward has an inline table - press again to replace it", true);
            return;
        }
        book.bookState.tableConfirmReplace = false;
        draftSelection(row.id());
        sendTableOp(new dev.ellipog.tenet.editor.TableOp.Select(tableOwner, tableOwningPath, row.id()));
        closeTablePanel();
        book.rebuildWidgets();
    }

    /** Whether the reward the browser is open for holds an inline table right now. */
    private boolean inlinePresent() {
        if (tableOwner == null || tableOwningPath == null) {
            return false;
        }
        JsonObject owner = tableOwnerRoot();
        return owner != null && rawObject(owner, tableOwningPath) != null
                && rawObject(owner, tableOwningPath).has("inline");
    }

    /** The tree that holds the reward a table is being chosen for. */
    private JsonObject tableOwnerRoot() {
        return switch (tableOwner) {
            case dev.ellipog.tenet.editor.TableAddress.Owner.Named named -> {
                var copy = dev.ellipog.tenet.client.ClientTableReplica.of(named.id());
                yield copy == null ? null : copy.root();
            }
            case dev.ellipog.tenet.editor.TableAddress.Owner.InQuest quest ->
                    ClientChapterReplica.quest(quest.chapter(), quest.quest());
            case null -> null;
        };
    }

    /** A table name the pack does not use yet, from a base. */
    private String freeTableId(String base) {
        java.util.Set<String> taken = new java.util.LinkedHashSet<>(ClientQuestCache.tables().stream()
                .map(ClientQuestCache.TableSummary::id).toList());
        if (!taken.contains(base)) {
            return base;
        }
        for (int n = 2; n < 1000; n++) {
            if (!taken.contains(base + "_" + n)) {
                return base + "_" + n;
            }
        }
        return base + "_" + System.currentTimeMillis();
    }

    void createTable() {
        String id = freeTableId("table");
        JsonObject root = new JsonObject();
        root.add("entries", new com.google.gson.JsonArray());
        // Nothing is claimed here: the server answers with the sentence, and the reply path reports it.
        // A line said before the answer is a line that can be a lie -- "Removed dice" over a table that
        // is still there is what the first version did.
        sendTableOp(new dev.ellipog.tenet.editor.TableOp.Create(id, root));
        openTableEditor(dev.ellipog.tenet.editor.TableAddress.of(id),
                dev.ellipog.tenet.quest.reward.TableReward.Mode.RANDOM);
    }

    // `startInlineTable` was here: it asked the server to write a table inside the reward. Inline
    // tables are gone -- the format still reads one (files in the wild have them), but nothing in the
    // UI makes one, and the browser's "New table" is the only way to a table now.

    void duplicateTable(String id) {
        sendTableOp(new dev.ellipog.tenet.editor.TableOp.Duplicate(id, freeTableId(id + "_copy")));
    }

    void deleteTable(String id) {
        sendTableOp(new dev.ellipog.tenet.editor.TableOp.Delete(id));
    }

    // ------------------------------------------------------------------
    // The editor
    // ------------------------------------------------------------------

    void buildTableEditorWidgets() {
        book.bookState.tableEditorFrame = dev.ellipog.tenet.client.dev.TableEditorLayout.Frame.of(tableBody());
        // The footer is drawn, not built -- see `drawTableFooter` for why the way out of this panel is
        // a target rather than a widget. No conversion in the submit slot: `Make inline` and
        // `Save as preset` are gone with inline tables themselves. A table is a file, and the reward
        // points at it.

        // The title box is built first, and before the page branch below -- which returns. It belongs to
        // the card rather than to whichever page is showing, and building it after that branch left the
        // type-picker page with no box at all: the field was not rebuilt, so its redraw was not re-added
        // either, and the header showed an icon and an id with a hole between them. (It also left the
        // focus pointer on the previous rebuild's field, which is a widget no longer in the list.)
        buildTableTitleField();

        // The type page hosts no widgets of its own beyond its search box: its rows are drawn by
        // `drawTypePicker` from the catalogue this editor's registries supply, in the band below -- the
        // card's own list band, which is the rectangle `drawTableEditor` draws them in. Laying the page
        // out at `overlayBody()` -- the *quest* card's body, a different rectangle by design -- put the
        // picker's heading on this card's title line and its first rows behind the toolbar; one band, one
        // derivation.
        //
        // `buildPickerWidgets` is called here for the search box, and its absence was a real fault: the
        // card's own pass built the box and this one did not, so the table editor's page had a list with
        // no box over it -- the page half-wired, which is the shape of every "the control is not there"
        // report this screen has had.
        book.questEditor.buildPickerWidgets();
    }

    /**
     * The card's title box: built once per rebuild, whatever page is showing.
     *
     * <p>Its own method because where it is <i>called</i> was the whole of a fault. It used to be the tail
     * of the editor's widget pass, after the type-picker branch — which returns — so the picker page had
     * no title box at all, and the focus pointer stayed on the previous rebuild's field.
     */
    private void buildTableTitleField() {
        BookGeometry.Rect header = book.bookState.tableEditorFrame.header();
        // The title field sits on the header's first line, right of the icon box.
        BookGeometry.Rect title = BookGeometry.Rect.at(header.x() + 22, header.y() + 1,
                Math.max(40, header.width() / 2), 12);
        // Kept, so the drawing can say what the box is for without deriving the rectangle a second time.
        tableTitleBox = title;
        String kept = tableTitleField == null ? tableTitle() : tableTitleField.value();
        tableTitleField = new ArmatureTextField(title.x(), title.y(), title.width(), title.height(), kept);
        tableTitleField.onSubmit(text -> {
            if (book.bookState.tableAddress == null) {
                return;
            }
            // Only a real change. A press anywhere away from the box blurs it, and a blur submits -- so
            // a press on Done arrives here first, and an unconditional send would be an edit the file
            // does not need. That edit is not merely wasted: an accepted edit is answered with a tree
            // broadcast the screen rebuilds its widgets on, which replaces the very button being
            // pressed and loses the click between its press and its release. The box says what the
            // table already is -- its title, or the id the display falls back to -- and neither is an
            // edit; a blank box over a table with no title is the same absent value again.
            String typed = text == null ? "" : text;
            if (typed.equals(tableTitle()) || (typed.isBlank() && !tableTitled())) {
                return;
            }
            sendTableOp(new dev.ellipog.tenet.editor.TableOp.Set(book.bookState.tableAddress, "title",
                    new com.google.gson.JsonPrimitive(typed)));
        });
        tableTitleField.colours(ArmatureTheme.title(), ArmatureTheme.recessed(), ArmatureTheme.panelEdge());
        book.addRenderableWidget(tableTitleField);
        // The placeholder after the pass, clipped to the panel's body -- the box itself is the pass's, for
        // the reason the browser's search note gives.
        BookGeometry.Rect clipTo = tableBody();
        book.bookState.panelOverlays.add(r -> {
            if (tableTitleField == null || !tableTitleField.value().isEmpty()) {
                return;
            }
            try (GuiRenderer.Scoped clip = r.clip(clipTo.x(), clipTo.y(), clipTo.right(), clipTo.bottom())) {
                r.text("Name this table", tableTitleField.getX() + 4, tableTitleField.getY() + 3,
                        ArmatureTheme.faint());
            }
        });
        // Focused only while the file has no title of its own: that is the one table whose box is
        // asking for something. A table that has a title does not need the caret, and a focused field
        // commits on blur -- which is what the first press on Done does -- so holding focus on every
        // rebuild armed a write on every click.
        if (!tableTitled()) {
            book.setFocused(tableTitleField);
        }
    }


    /**
     * What the table editor's body is showing: entries, the roll report, or a page of types.
     *
     * <p>One string the drawing maintains itself, so no opener has to stamp the swap: switching tables,
     * rolling, or picking a type for an entry all change what this names, and the drift below starts
     * from the change rather than from fifteen call sites remembering to.
     */
    private String tablePageKey() {
        String address = book.bookState.tableAddress == null ? "none" : book.bookState.tableAddress.toString();
        if (book.bookState.tableShowRoll) {
            return address + "|roll";
        }
        if (book.bookState.pickingEntryType != null) {
            return address + "|type:" + book.bookState.pickingEntryType;
        }
        if (book.bookState.pickingConditionFor != null) {
            return address + "|cond";
        }
        return address + "|entries";
    }

    /** Which page the body showed last frame, and when it changed. See {@link #tablePageKey}. */
    private String lastTablePageKey = "";
    private long tablePageOpenedAt = -1L;

    /** How long a body page takes to settle into place: a nudge, in the menus' band. */
    private static final int TABLE_PAGE_MILLIS = 90;

    /** How far beside its rest a fresh body page starts. Small enough to read as a settle. */
    private static final int TABLE_PAGE_DRIFT_PX = 6;

    /**
     * How far right of its rest the table's body still stands.
     *
     * <p>Zero while the rail itself is arriving — the rail's own slide carries the body then, and a
     * second motion inside it would read as the content lagging its panel. Zero with motion off, like
     * every other arrival.
     */
    private int tablePageDrift(long now) {
        if (book.panelRevealing(PanelKind.TABLE_EDITOR, now)) {
            return 0;
        }
        float arrived =
                PanelLayout.revealed(now - tablePageOpenedAt, TABLE_PAGE_MILLIS);
        return Math.round(TABLE_PAGE_DRIFT_PX * (1F - arrived));
    }

    void drawTableEditor(GuiRenderer r, Matrix3x2fStack pose, int mouseX, int mouseY) {
        claimTableData();
        reconcileTableDrafts();
        tableHint = null;
        // The chrome names the page, which is what the item picker's card does and what this panel did
        // not: it said "Edit table" over the entry list, over the roll report and over the page of types
        // alike, so the one place a card says what it is said the same thing for three different pages.
        // The item picker is the reference here -- its strip says "Add an item to the table".
        String pageTitle = book.bookState.pickingEntryType != null
                ? ("tasks".equals(book.bookState.pickingEntryType) ? "Add a task" : "Add a reward")
                : book.bookState.tableShowRoll ? "Test roll" : "Edit table";
        drawModalCardChrome(r, pageTitle, mouseX, mouseY, QuestBookScreen.EditAction.TABLE_DONE);
        // Drawn before the frame check: the footer is the way out, and a frame that has not been built
        // yet is exactly when a panel most needs one.
        drawTableFooter(r, mouseX, mouseY, false);
        if (book.bookState.tableEditorFrame == null) {
            return;
        }
        BookGeometry.Rect header = book.bookState.tableEditorFrame.header();
        var model = tableModel();
        JsonObject root = tableRoot();
        JsonObject drafted = tableDraftedRoot();

        // The table's own name: the file's, or the quest it is written inside. Declared here rather than
        // beside its first use because the band above the header needs it too — see the referrers line.
        String id = switch (book.bookState.tableAddress.owner()) {
            case dev.ellipog.tenet.editor.TableAddress.Owner.Named named -> named.id();
            case dev.ellipog.tenet.editor.TableAddress.Owner.InQuest quest ->
                    "(inline in " + quest.quest() + ")";
        };

        // --- the breadcrumb, and who else uses this table: one band, two ends ---
        //
        // The referrers line lives in this band rather than on the header's second line, and that is a
        // correction rather than a preference: the second line's numbers text is truncated to leave room
        // for the mode chip and nothing else, so a line added there would collide with the entry count on
        // exactly the tables big enough for the collision to matter. This band is the line above the
        // header, it is empty for a table at the root of the walk, and the breadcrumb is short enough that
        // the two ends never meet.
        String uses = "";
        if (book.bookState.tableAddress.owner() instanceof dev.ellipog.tenet.editor.TableAddress.Owner.Named) {
            List<String> referrers = dev.ellipog.tenet.client.ClientTableReplica.usedBy(id);
            uses = referrers.isEmpty() ? "nothing uses this table yet"
                    : "used by " + String.join(", ", referrers);
        }
        int usesRoom = uses.isEmpty() ? 0
                : Math.min(header.width() / 2, book.textMeasure(r).width(uses) + 4);
        if (!book.bookState.tableStack.isEmpty()) {
            StringBuilder crumb = new StringBuilder();
            for (dev.ellipog.tenet.editor.TableAddress above : book.bookState.tableStack) {
                crumb.append(tableNameOf(above)).append(" > ");
            }
            crumb.append(tableTitle());
            r.text(Measure.truncate(crumb.toString(), Math.max(0, header.width() - usesRoom),
                            book.textMeasure(r)), header.x(),
                    book.bookState.tableEditorFrame.crumb().y(), ArmatureTheme.faint());
        }
        if (!uses.isEmpty()) {
            String shown = Measure.truncate(uses, usesRoom, book.textMeasure(r));
            r.text(shown, header.right() - r.textWidth(shown), book.bookState.tableEditorFrame.crumb().y(),
                    ArmatureTheme.faint());
            if (book.bookState.tableEditorFrame.crumb().contains(mouseX, mouseY)) {
                // The whole sentence, however long: the band holds what fits and the hover holds the rest.
                book.bookState.pendingLabels.add(new QuestBookScreen.PendingLabel(book.bookState.tableEditorFrame.crumb(), uses));
            }
        }

        // --- the header: the icon, the title field, the id, the numbers and the preview ---
        if (model.isPresent()) {
            ItemStack icon = model.get().displayIcon().toStack();
            if (!icon.isEmpty()) {
                r.icon(icon, header.x() + 2, header.y() + 1, 16);
                BookGeometry.Rect iconBox = BookGeometry.Rect.at(header.x(), header.y(), 20, 14);
                book.questEditor.registerTarget(QuestBookScreen.EditAction.TABLE_ICON, "", iconBox, iconBox.x(),
                        iconBox.y(), "", null, -1);
                if (iconBox.contains(mouseX, mouseY)) {
                    // The one control in this header with nothing said about it anywhere: a small stack
                    // sprite that is also a button is not a thing a person guesses.
                    book.bookState.pendingLabels.add(new QuestBookScreen.PendingLabel(iconBox,
                            "the icon this table draws in the browser and on a reward's badge"));
                }
            }
        }
        // The id right-aligned on the title's line, rather than floating at half the card's width:
        // it is the file's name, which is reference rather than a heading.
        //
        // And only when it says something the box does not. The box is seeded with the display title,
        // which falls back to the id, so a table with no title of its own drew "dice" twice on one line --
        // the box saying what it is and the right end repeating it. The id keeps its job either way: it is
        // the name the file has.
        String shownTitle = tableTitleField == null ? tableTitle() : tableTitleField.value();
        if (!id.equals(shownTitle)) {
            r.text(Measure.truncate(id, Math.max(0, header.width() / 3), book.textMeasure(r)),
                    header.right() - r.textWidth(Measure.truncate(id, Math.max(0, header.width() / 3),
                            book.textMeasure(r))), header.y() + 3, ArmatureTheme.faint());
        }
        if (tableTitleBox != null && tableTitleBox.contains(mouseX, mouseY)) {
            // The box says what the table already is, so a blank one reads as a table with no name rather
            // than as a field asking for one. Said here: the name is the browser row's heading, and the id
            // at the right is the file's name.
            book.bookState.pendingLabels.add(new QuestBookScreen.PendingLabel(tableTitleBox,
                    "what this table is called - the id at the right is its file name"));
        }

        // How a reward row draws this table, as two toggles on the title's line: whether the row wears
        // the table's title (legacy `use_title`) and whether it draws an item tooltip (legacy
        // `hide_tooltip` suppresses it). Beside the title box because that is what the first toggle draws, and drawn
        // from the drafted model so a press reads back on the next frame without waiting for the server.
        if (model.isPresent() && tableTitleBox != null) {
            // A word and a switch each, the quest editor's flags: they were chips reading "Title:off",
            // which overflowed their 44 pixels and were the only toggles in the editor drawn as words.
            int chipY = header.y() + 1;
            int box = dev.ellipog.tenet.client.dev.EntryFormLayout.BUTTON;
            BookGeometry.Rect titleChip = BookGeometry.Rect.at(tableTitleBox.right() + 8, chipY,
                    r.textWidth("Title") + 4 + box, 12);
            BookGeometry.Rect tipChip = BookGeometry.Rect.at(titleChip.right() + 10, chipY,
                    r.textWidth("Tooltip") + 4 + box, 12);
            boolean useTitle = model.get().useTitle();
            boolean hideTooltip = model.get().hideTooltip();
            drawTableFlag(r, titleChip, "Title", useTitle, titleChip.contains(mouseX, mouseY));
            drawTableFlag(r, tipChip, "Tooltip", !hideTooltip, tipChip.contains(mouseX, mouseY));
            book.questEditor.registerTarget(QuestBookScreen.EditAction.TABLE_USE_TITLE, "", titleChip,
                    titleChip.x(), titleChip.y(),
                    "", null, -1);
            book.questEditor.registerTarget(QuestBookScreen.EditAction.TABLE_HIDE_TOOLTIP, "", tipChip,
                    tipChip.x(), tipChip.y(),
                    "", null, -1);
            if (titleChip.contains(mouseX, mouseY)) {
                book.bookState.pendingLabels.add(new QuestBookScreen.PendingLabel(titleChip,
                        "a reward row draws this table's title rather than the generic roll sentence"));
            }
            if (tipChip.contains(mouseX, mouseY)) {
                book.bookState.pendingLabels.add(new QuestBookScreen.PendingLabel(tipChip,
                        "a reward row draws no item tooltip for this table"));
            }
        }

        if (model.isEmpty()) {
            String why = root == null
                    ? "Waiting for this table's file..."
                    : "This table could not be read here - the file has an error, and the card's raw "
                            + "editor is where to fix it.";
            r.text(Measure.truncate(why, header.width(), book.textMeasure(r)), header.x(), header.y() + 20,
                    root == null ? ArmatureTheme.faint() : ArmatureTheme.blocked());
            drawTableEditorToolbar(r, mouseX, mouseY, false);
            if (root == null && dev.ellipog.tenet.client.ClientTableReplica.refusal(id) != null) {
                r.text(Measure.truncate(dev.ellipog.tenet.client.ClientTableReplica.refusal(id),
                        header.width(), book.textMeasure(r)), header.x(), header.y() + 30,
                        ArmatureTheme.blocked());
            }
            drawTableHint(r, tableHint);
            return;
        }

        dev.ellipog.tenet.quest.loot.RewardTable table = model.get();
        // Once for the frame, not once per row: `odds` totals every weight and builds a Chance per
        // entry, and the row method asked for it again for every row it drew.
        tableOdds = table.odds(dev.ellipog.tenet.client.dev.TableEditorLayout
                .includesEmpty(book.bookState.tablePreview));
        // The same once-per-frame rule for the weight column's hover: the row's own hint says what its
        // weight is out of, and walking the entries per row would be O(rows x entries) a frame.
        tableWeightTotal = totalWeight(drafted);
        // The second header line, left to right: how many times it rolls (a stepper, with its value --
        // the first version drew the two chips and never the number between them), what it holds, and
        // what the mode does to the chances.
        //
        // The stepper is drawn blocked in the two readings that throw no dice. `all()` and `choice()`
        // never look at `lootSize`, so a live control here changed a number the file honours in two of
        // its four readings and ignores in the other two -- with nothing on screen saying which.
        var rolls = dev.ellipog.tenet.client.dev.TableEditorLayout.stepper(header.x(), header.y() + 22);
        boolean diced = dev.ellipog.tenet.client.dev.TableEditorLayout.rolls(book.bookState.tablePreview);
        // And each chip is blocked at the end of its range, where its press is clamped: a control that
        // looks live and does nothing is what the Undo button already refuses to be.
        boolean rollsDown = diced
                && table.lootSize() > dev.ellipog.tenet.quest.loot.RewardTable.LOOT_SIZE_MIN;
        boolean rollsUp = diced
                && table.lootSize() < dev.ellipog.tenet.quest.loot.RewardTable.LOOT_SIZE_MAX;
        if (rollsDown) {
            book.questEditor.drawChip(r, rolls.minus(), "-", rolls.minus().contains(mouseX, mouseY));
        }
        else {
            drawChipBlocked(r, rolls.minus(), "-");
        }
        if (rollsUp) {
            book.questEditor.drawChip(r, rolls.plus(), "+", rolls.plus().contains(mouseX, mouseY));
        }
        else {
            drawChipBlocked(r, rolls.plus(), "+");
        }
        String count = String.valueOf(table.lootSize());
        r.text(count, rolls.value().x() + (rolls.value().width() - r.textWidth(count)) / 2,
                rolls.value().y() + 2, diced ? ArmatureTheme.body() : ArmatureTheme.blocked());
        if (rollsDown) {
            book.questEditor.registerTarget(QuestBookScreen.EditAction.TABLE_ROLLS_DOWN, "", rolls.minus(),
                    rolls.minus().x(),
                    rolls.minus().y(), "", null, -1);
        }
        if (rollsUp) {
            book.questEditor.registerTarget(QuestBookScreen.EditAction.TABLE_ROLLS_UP, "", rolls.plus(),
                    rolls.plus().x(),
                    rolls.plus().y(),
                    "", null, -1);
        }
        if (!diced && (rolls.minus().contains(mouseX, mouseY) || rolls.plus().contains(mouseX, mouseY)
                || rolls.value().contains(mouseX, mouseY))) {
            tableHint = dev.ellipog.tenet.client.dev.TableEditorLayout.unusedByMode(book.bookState.tablePreview);
        }
        // The drafted root, not the replica's: the rows below draw pending weights, and a total that
        // counted only what the file holds would contradict the column it sits above. And in a reading
        // with no dice, the weights are not a quantity at all -- saying "total weight 12" over a table
        // that grants everything is the number an author would tune against for nothing.
        String holds = table.entryCount() + (table.entryCount() == 1 ? " entry" : " entries");
        String numbers = diced
                ? "roll" + (table.lootSize() == 1 ? "" : "s") + "   " + holds
                        + "   total weight " + BookAssets.trimNumber(totalWeight(drafted))
                : holds + "   (no dice in this reading)";
        int numbersX = rolls.plus().right() + 6;
        r.text(Measure.truncate(numbers, Math.max(0, header.right() - 110 - numbersX), book.textMeasure(r)),
                numbersX, header.y() + 24, diced ? ArmatureTheme.body() : ArmatureTheme.faint());

        BookGeometry.Rect mode = BookGeometry.Rect.at(header.right() - 96, header.y() + 22, 94, 14);
        book.questEditor.drawValue(r, mode,
                dev.ellipog.tenet.client.dev.TableEditorLayout.modeLabel(book.bookState.tablePreview), "",
                false, false, mouseX, mouseY);
        if (mode.contains(mouseX, mouseY)) {
            // The sentence used to float under this chip, which is the toolbar band -- the screenshot
            // that started this round showed it covering "Import chest", "Test roll" and "Undo". It
            // goes to the panel's hint line instead, where it covers nothing.
            tableHint = dev.ellipog.tenet.client.dev.TableEditorLayout.modeHint(book.bookState.tablePreview)
                    + "  (press to cycle - this is how the numbers are read, not a property of the file)";
        }
        book.questEditor.registerTarget(QuestBookScreen.EditAction.TABLE_PREVIEW, "", mode, mode.x() + 2, mode.y() + 3,
                "", null, -1);


        drawTableEditorToolbar(r, mouseX, mouseY, true);

        // The page arriving, when it just changed: entries, the roll report, or a page of types slide
        // a few pixels left into place under a toolbar that never moves. Tracked here rather than
        // stamped by the openers, because every swap — rolling, picking, nesting — funnels through this
        // draw. Presses read the unshifted rows for the tenth of a second this lasts; the pointer just
        // pressed the toolbar that caused the swap, so no aimed press can land inside the skew.
        long pageNow = net.minecraft.util.Util.getMillis();
        String pageKey = tablePageKey();
        if (!pageKey.equals(lastTablePageKey)) {
            lastTablePageKey = pageKey;
            tablePageOpenedAt = pageNow;
        }
        int drift = tablePageDrift(pageNow);
        boolean drifting = drift != 0;
        if (drifting) {
            pose.pushMatrix();
            pose.translate(drift, 0F);
        }
        try {
            drawTableEditorBody(r, table, mouseX, mouseY);
        }
        finally {
            if (drifting) {
                pose.popMatrix();
            }
        }
    }

    /**
     * The table editor's body: the roll report, the entries, or a page of types.
     *
     * <p>Split out of {@link #drawTableEditor} so the page drift has one region to wrap: chrome above
     * (the title, the toolbar) never moves, and everything below arrives. Called inside the drift's
     * pose scope when one is in flight, and directly otherwise.
     */
    private void drawTableEditorBody(GuiRenderer r,
                                     dev.ellipog.tenet.quest.loot.RewardTable table, int mouseX,
                                     int mouseY) {
        if (book.bookState.tableShowRoll) {
            drawTableRollPane(r, mouseX, mouseY);
            drawTableHint(r, tableHint);
            return;
        }

        // --- the entries ---
        BookGeometry.Rect list = book.bookState.tableEditorFrame.list();
        int entries = table.entryCount();
        // The folds are reconciled against the list before anything is measured against them: an undo, a
        // remote edit or a removal moves the list under a set of position keys, and a fold left pointing
        // past the end inflates the scroll extent with rows that are drawn nowhere.
        reconcileFolds();
        var folds = book.bookAssets.tableFolds();
        // The entries' band, not the list's: the headings do not scroll, so the range, the clamp and the
        // bar all describe the rows under them. Bound from the same `rows()` rectangle the drawing and
        // the hit test read, which is what keeps the grip's travel and the list's end one answer.
        QuestBookScreen.bindList(book.bookState.tableEditorBody, book.bookState.tableEditorBar,
                book.bookState.tableEditorFrame.rows(),
                book.bookState.tableEditorFrame.scrollbar(),
                dev.ellipog.tenet.client.dev.TableEditorLayout.contentHeight(entries, folds),
                dev.ellipog.tenet.client.dev.TableEditorLayout.ROW_HEIGHT);
        int tableScroll = book.bookState.tableEditorBody.scrollY();
        drawTableHeadings(r, list);
        // Clipped to the rows' band rather than the list's: the headings are the list's first strip,
        // and a row scrolled up must disappear under them rather than over.
        BookGeometry.Rect rowsBand = book.bookState.tableEditorFrame.rows();
        try (GuiRenderer.Scoped clip = r.clip(rowsBand.x(), rowsBand.y(), rowsBand.right(),
                rowsBand.bottom())) {
            for (int i = 0; i < entries; i++) {
                BookGeometry.Rect rect = dev.ellipog.tenet.client.dev.TableEditorLayout
                        .rowRect(entries, book.bookState.tableEditorFrame, tableScroll, i, folds);
                if (rect.bottom() < rowsBand.y() || rect.y() > rowsBand.bottom()) {
                    continue;
                }
                drawTableEntryRow(r, table, i, rect, mouseX, mouseY);
            }
        }
        if (entries == 0) {
            r.text("No entries yet - Add item, or drag one in from the recipe viewer.", list.x() + 4,
                    book.bookState.tableEditorFrame.rowsTop() + 4, ArmatureTheme.faint());
        }
        // The entries' bar, on the band the entries are in rather than on the whole list: the headings
        // above it do not scroll, and a groove that claimed them would put the grip's ends in the wrong
        // place by the height of a line.
        QuestBookScreen.drawBar(r, book.bookState.tableEditorBar, mouseX, mouseY);
        // The Import popover, last of everything: it is the innermost thing on screen while it is open,
        // so it is drawn on top of the list. Its *presses* are asked first rather than last -- see
        // `pressTableEditor` -- because the press scan takes the first target that contains the point,
        // and "drawn on top" and "registered first" cannot both be true of one list.
        if (book.bookState.tableImportOpen && tableImportAnchor != null) {
            // The real pointer, because the popover is one of the overlays that owns it: the rows under it
            // were drawn with a pointer over nothing, and these two are the ones still aimed at. See
            // `overlayOwnsPointer`.
            drawTableImportPopover(r, tableImportAnchor, book.bookState.renderMouseX, book.bookState.renderMouseY);
        }
        else {
            book.bookState.tableImportBox = null;
        }
        drawTableHint(r, tableHint);
    }

    private void drawTableEditorToolbar(GuiRenderer r, int mouseX, int mouseY, boolean live) {
        BookGeometry.Rect bar = book.bookState.tableEditorFrame.toolbar();
        int x = bar.x();
        // While the roll is showing, the adders are stood down and the roll button is the way back: the
        // page is about what the table did, and a button that said "Test roll x10" over it would both
        // re-roll (fine) and hide the only visible way out of the page (not fine).
        if (book.bookState.tableShowRoll) {
            tableToolbarButton(r, x, bar, "Back to table", mouseX, mouseY, QuestBookScreen.EditAction.TABLE_ROLL, true);
            // Set here, drawn by the caller at the end of its own frame: this method is one part of the
            // panel's drawing, and a band drawn from inside it lands before the rows have had their say
            // -- which is what made every sentence here a frame out of date.
            tableHint = "The last test roll - press Back to table for the entries";
            return;
        }
        // Adders first and filled, because they are what an author came to this panel to do; then the
        // one Import button, whose two sources hang under it; then the utilities, right-aligned, because
        // a diagnostic and a history control are not the same kind of act as adding to the table. Six
        // identical outlines in a row is what the screenshot showed as a dumping ground.
        x = tableToolbarButton(r, x, bar, "+ Item", mouseX, mouseY, QuestBookScreen.EditAction.TABLE_ADD_ITEM,
                live, true);
        x = tableToolbarButton(r, x, bar, "+ Reward", mouseX, mouseY, QuestBookScreen.EditAction.TABLE_ADD_REWARD, live,
                true);
        // The Import button's own rectangle, remembered for the popover -- which is drawn by the caller
        // *after* the rows, so that the order the press scan sees (the popover's rows, then the list's)
        // is the order they are stacked on screen. Drawn from here it was under nothing and over nothing,
        // and the two rows won the scan by registration order alone.
        tableImportAnchor = BookGeometry.Rect.at(x, bar.y(), r.textWidth("Import...") + 10, bar.height());
        tableToolbarButton(r, x, bar, "Import...", mouseX, mouseY, QuestBookScreen.EditAction.TABLE_IMPORT_MENU, live,
                false);
        // Undo is a control that is either live or not: a blocked one registers no target, so a press
        // does nothing and the hover says why -- rather than looking live, sending an op and coming
        // back with a refusal, which is a button that appears to do nothing.
        // The utilities keep the right end of the bar, so the run of controls has a direction: things
        // that change the table on the left, things that read it on the right.
        int rollWidth = r.textWidth(tableRollLabel()) + 10;
        int undoWidth = r.textWidth("Undo") + 10;
        int undoX = bar.right() - undoWidth;
        int rollX = undoX - 4 - rollWidth;
        tableToolbarButton(r, rollX, bar, tableRollLabel(), mouseX, mouseY, QuestBookScreen.EditAction.TABLE_ROLL, live,
                false);
        BookGeometry.Rect undo = BookGeometry.Rect.at(undoX, bar.y(), undoWidth, bar.height());
        boolean canUndo = live && tableCanUndo();
        boolean undoHot = undo.contains(mouseX, mouseY);
        QuestBookScreen.drawEditAffordance(r, undo, undoHot && canUndo);
        r.text("Undo", undo.x() + 5, undo.y() + (bar.height() - 8) / 2,
                !canUndo ? ArmatureTheme.faint() : undoHot ? ArmatureTheme.title() : ArmatureTheme.body());
        if (undoHot) {
            tableHint = canUndo ? "Undo the last edit to this table" : "Nothing to undo yet";
        }
        if (canUndo) {
            book.questEditor.registerTarget(QuestBookScreen.EditAction.TABLE_UNDO, "", undo, undo.x() + 5,
                    undo.y(), "", null, -1);
        }
    }

    private int tableToolbarButton(GuiRenderer r, int x, BookGeometry.Rect bar, String label, int mouseX,
                                   int mouseY, QuestBookScreen.EditAction action, boolean live) {
        return tableToolbarButton(r, x, bar, label, mouseX, mouseY, action, live, false);
    }

    /**
     * The same, with the bar's two weights.
     *
     * <p>{@code primary} fills the box and brightens its word: the adders are what the panel is for, and
     * an import, a test roll or an undo is not the same kind of act. One convention, so a control's
     * weight says what pressing it does rather than how long its word is.
     */
    private int tableToolbarButton(GuiRenderer r, int x, BookGeometry.Rect bar, String label, int mouseX,
                                   int mouseY, QuestBookScreen.EditAction action, boolean live, boolean primary) {
        int width = r.textWidth(label) + 10;
        BookGeometry.Rect box = BookGeometry.Rect.at(x, bar.y(), width, bar.height());
        boolean hot = box.contains(mouseX, mouseY);
        if (primary) {
            ArmatureTheme.panel(r, box.x(), box.y(), box.width(), box.height(),
                    ArmatureTheme.raised(), hot ? ArmatureTheme.title() : ArmatureTheme.panelEdge());
        }
        else {
            QuestBookScreen.drawEditAffordance(r, box, hot);
        }
        r.text(label, box.x() + 5, box.y() + (box.height() - 8) / 2,
                !live ? ArmatureTheme.faint()
                        : primary || hot ? ArmatureTheme.title() : ArmatureTheme.body());
        book.questEditor.registerTarget(action, "", box, box.x() + 5, box.y(), "", null, -1);
        return x + width + 4;
    }

    /**
     * The Import button's two rows: from what, exactly.
     *
     * <p>One button rather than two, because the two imports are one act with two sources -- and the
     * toolbar was six identical outlines in a row. The rows are drawn targets like everything else in
     * this panel, so a rebuild cannot take them away between a press and its handling, and the press
     * that misses them is what dismisses them (see {@code pressTableEditor}).
     */
    private void drawTableImportPopover(GuiRenderer r, BookGeometry.Rect button, int mouseX,
                                        int mouseY) {
        BookGeometry.Rect body = tableBody();
        int width = Math.max(60, Math.min(150, body.width()));
        int height = BookItemPicker.MENU_ROW_HEIGHT * 2 + 4;
        int x = Math.max(body.x(), Math.min(button.x(), body.right() - width));
        // Below the button when it fits, above it when it does not, and clamped into the card either way.
        // The left edge was the only edge clamped before, so on a short window the second row ("From
        // target chest") was drawn past the body's bottom -- over the entry list, and still taking the
        // presses aimed at a row underneath it.
        int below = button.bottom() + 1;
        int y = below + height <= body.bottom()
                ? below
                : Math.max(body.y(), button.y() - height - 1);
        // The same climb the menus do, from its own stamp: the box the press reads is drawn risen, so
        // the two agree for the tenth of a second the arrival lasts.
        y += importRise();
        ArmatureTheme.panel(r, x - 2, y - 2, width + 4, height + 4, ArmatureTheme.raised(),
                ArmatureTheme.panelEdge());
        // The rectangle it just drew, for the press: one derivation, so a press inside the popover is a
        // press on what is on screen rather than on the arithmetic a second time. Shifted for the rail's
        // slide like every other registered box — see `shiftHit`.
        book.bookState.tableImportBox = book.shiftHit(BookGeometry.Rect.at(x, y, width, height));
        drawTableImportRow(r, x, y, width, "From inventory", QuestBookScreen.EditAction.TABLE_IMPORT,
                mouseX, mouseY);
        drawTableImportRow(r, x, y + BookItemPicker.MENU_ROW_HEIGHT, width, "From target chest",
                QuestBookScreen.EditAction.TABLE_IMPORT_CHEST, mouseX, mouseY);
    }

    private void drawTableImportRow(GuiRenderer r, int x, int y, int width, String label,
                                    QuestBookScreen.EditAction action, int mouseX, int mouseY) {
        BookGeometry.Rect row = BookGeometry.Rect.at(x, y, width, BookItemPicker.MENU_ROW_HEIGHT);
        boolean hot = row.contains(mouseX, mouseY);
        if (hot) {
            r.fill(row.x(), row.y(), row.right(), row.bottom(),
                    Colour.alphaOf(ArmatureTheme.title(), 0.18F));
        }
        r.text(BookItemPicker.fitLabel(r, label), row.x() + 4, row.y() + (row.height() - 8) / 2,
                hot ? ArmatureTheme.title() : ArmatureTheme.body());
        book.questEditor.registerTarget(action, "", row, row.x() + 4, row.y(), "", null, -1);
    }

    /**
     * The toolbar's Undo, drawn blocked when there is nothing to undo.
     *
     * <p>Read from the frame's budget rather than from the file's history: the budget is what Ctrl+Z
     * acts on, so a button that looked live while the key said "nothing to undo" would be the button
     * lying rather than the key.
     */
    private boolean tableCanUndo() {
        return book.bookState.tableApplied > 0;
    }

    /** One entry: its badge, its weight with the chance that weight means, and its own fields folded. */
    /**
     * What the columns are: one strip above the first row.
     *
     * <p>There was none, and the row's own numbers were a quiz -- a stepper with no word over it is a
     * count or a weight depending on which panel you came from. The headings are laid out through the
     * row's own arithmetic, so a heading is over the column it names and moving a column moves its
     * heading: one derivation, the rule the rest of these panels follow.
     */
    /** One of the table header's flags: its word, then its switch at the flag's right end. */
    private void drawTableFlag(GuiRenderer r, BookGeometry.Rect flag, String word, boolean on, boolean hot) {
        int box = dev.ellipog.tenet.client.dev.EntryFormLayout.BUTTON;
        r.text(word, flag.x(), flag.y() + (flag.height() - 8) / 2,
                hot ? ArmatureTheme.title() : ArmatureTheme.body());
        // `drawSwitch` centres its square on an entry line, which is taller than this flag: the cell is
        // lifted by the difference so the square and the word share a middle.
        int lift = (dev.ellipog.tenet.client.dev.EntryFormLayout.LINE_HEIGHT - flag.height()) / 2;
        book.questEditor.drawSwitch(r, BookGeometry.Rect.at(flag.right() - box, flag.y() - lift, box,
                flag.height()), on);
    }

    private void drawTableHeadings(GuiRenderer r, BookGeometry.Rect list) {
        if (list.height() < dev.ellipog.tenet.client.dev.TableEditorLayout.HEADING_HEIGHT) {
            return;
        }
        BookGeometry.Rect probe = BookGeometry.Rect.at(list.x(), list.y(), list.width(),
                dev.ellipog.tenet.client.dev.TableEditorLayout.ROW_HEIGHT);
        var row = dev.ellipog.tenet.client.dev.TableEditorLayout.row(probe);
        BookGeometry.Rect odds = dev.ellipog.tenet.client.dev.TableEditorLayout.odds(probe);
        int y = list.y() + 2;
        r.text("Item / reward", row.icon().x(), y, ArmatureTheme.faint());
        r.text("Chance", odds.right() - r.textWidth("Chance"), y, ArmatureTheme.faint());
        String weight = "Weight";
        r.text(weight, row.minus().x() + (row.plus().right() - row.minus().x()
                - r.textWidth(weight)) / 2, y, ArmatureTheme.faint());
    }

    /**
     * The panels' hint line: one sentence about whatever the pointer is over, in the footer band.
     *
     * <h2>Why the footer band rather than a floating label</h2>
     *
     * <p>Hover notes used to be drawn under the control that had them, and a sentence under the mode
     * chip lands on the toolbar -- which is what the screenshot showed as a tooltip covering "Import
     * chest", "Test roll" and "Undo". The footer band is empty in both panels (the buttons sit at its
     * right end), so a note there covers nothing and there is one place to look for the answer to
     * "what is this?" -- rather than a label that moves with the pointer and hides what it explains.
     */
    void drawTableHint(GuiRenderer r, String text) {
        if (text == null || text.isEmpty()) {
            return;
        }
        BookGeometry.Rect card = book.surfaceCard(book.bookState.surfaceKind);
        // The free strip at the top of the footer band, above the row the controls sit in -- so the
        // sentence cannot land under a button whatever the window is, and no control's x has to be
        // guessed at. This used to be drawn at `body.bottom() + 8`, which is past the body and on the
        // rule above this band, with a hardcoded 200 pixels reserved for controls it never asked about:
        // a long sentence ran under Done and Back on a narrow window, and the reserve was a number
        // rather than a measurement.
        int y = card.bottom() - BookGeometry.MODAL_FOOTER_HEIGHT + 2;
        int room = Math.max(0, card.width() - 2 * BookGeometry.MODAL_INSET);
        try (GuiRenderer.Scoped clip = r.clip(card.x(), card.y(), card.right(), card.bottom())) {
            r.text(Measure.truncate(text, room, book.textMeasure(r)), tableBody().x(), y,
                    ArmatureTheme.faint());
        }
    }

    private void drawTableEntryRow(GuiRenderer r, dev.ellipog.tenet.quest.loot.RewardTable table, int index,
                                   BookGeometry.Rect rect, int mouseX, int mouseY) {
        var row = dev.ellipog.tenet.client.dev.TableEditorLayout.row(rect);
        var entry = table.entries().get(index);
        boolean folded = book.bookState.tableFolded.contains(index);

        ItemStack icon = entry.reward() instanceof dev.ellipog.tenet.quest.reward.ItemReward item
                ? item.item().toStack()
                : dev.ellipog.tenet.quest.reward.RewardTypes.iconOf(entry.reward().type()).toStack();
        if (!icon.isEmpty()) {
            r.icon(icon, row.icon().x(), row.icon().y(), 16);
        }
        // The name, through the one helper that knows a display's label is a translation key: drawing
        // `label()` straight onto the row is what printed "tenet:reward.xp.points".
        var display = dev.ellipog.tenet.quest.reward.RewardTypes.displayOf(entry.reward());
        String name = dev.ellipog.tenet.client.RewardText.of(display);
        BookGeometry.Rect nameBox = row.name();
        // Centred on the row's own line, not on its whole rectangle: a folded row is taller because of
        // the band under it, and its badge must stay on the line it belongs to.
        int line = nameBox.height();
        r.text(Measure.truncate(name, Math.max(0, nameBox.width() - 100), book.textMeasure(r)),
                nameBox.x() + 1, nameBox.y() + (line - 8) / 2, ArmatureTheme.body());
        int count = dev.ellipog.tenet.client.RewardText.countOf(display);
        if (count > 1) {
            String many = "x" + count;
            int nameWidth = Math.min(r.textWidth(name), Math.max(0, nameBox.width() - 100));
            r.text(many, nameBox.x() + 3 + nameWidth, nameBox.y() + (line - 8) / 2,
                    ArmatureTheme.faint());
        }

        // The chance, from the table's own arithmetic -- computed once for the frame, in the caller.
        var odds = tableOdds;
        String chance = dev.ellipog.tenet.client.dev.TableEditorLayout
                .chance(odds.each().get(index), book.bookState.tablePreview, table.lootSize());
        String again = dev.ellipog.tenet.client.dev.TableEditorLayout
                .atLeastOnce(odds.each().get(index), book.bookState.tablePreview, table.lootSize());
        BookGeometry.Rect oddsBox = dev.ellipog.tenet.client.dev.TableEditorLayout.odds(rect);
        r.text(chance, oddsBox.right() - r.textWidth(chance), oddsBox.y() + (line - 8) / 2,
                odds.each().get(index).always() ? ArmatureTheme.title() : ArmatureTheme.faint());
        // The "at least once" number is the same entry's second chance, so the hover that asks for it
        // is over the chance itself -- and the sentence goes to the footer band rather than floating
        // under the row, where it used to sit on the next entry's name (or, for the mode chip, on the
        // toolbar). It was queued for every row before, hot or not, which is what stacked them.
        if (!again.isEmpty() && oddsBox.contains(mouseX, mouseY)) {
            tableHint = again + " - over " + table.lootSize() + " rolls";
        }

        // The weight, and its stepper. Blocked in the readings that throw no dice, for the header's own
        // reason: a weight is not a quantity in a mode that grants everything.
        boolean weighed = dev.ellipog.tenet.client.dev.TableEditorLayout.rolls(book.bookState.tablePreview);
        // And the `-` is blocked at zero, where it cannot act: the press was clamped and the control
        // swallowed it, which is the one thing this panel's Undo already refuses to do.
        boolean canDown = weighed && entry.weight() > 0;
        if (weighed) {
            if (canDown) {
                book.questEditor.drawChip(r, row.minus(), "-", row.minus().contains(mouseX, mouseY));
            }
            else {
                drawChipBlocked(r, row.minus(), "-");
            }
            book.questEditor.drawChip(r, row.plus(), "+", row.plus().contains(mouseX, mouseY));
        }
        else {
            drawChipBlocked(r, row.minus(), "-");
            drawChipBlocked(r, row.plus(), "+");
        }
        String weight = BookAssets.trimNumber(entry.weight());
        r.text(weight, row.value().x() + (row.value().width() - r.textWidth(weight)) / 2,
                row.value().y() + (line - 8) / 2,
                weighed ? ArmatureTheme.body() : ArmatureTheme.blocked());
        if (row.minus().contains(mouseX, mouseY) || row.plus().contains(mouseX, mouseY)
                || row.value().contains(mouseX, mouseY)) {
            tableHint = !weighed
                    ? dev.ellipog.tenet.client.dev.TableEditorLayout.unusedByMode(book.bookState.tablePreview)
                    : !canDown
                            ? "Already at zero - zero means always granted"
                            : "Weight " + weight + " of " + BookAssets.trimNumber(tableWeightTotal)
                                    + " - zero means always granted";
        }
        if (canDown) {
            book.questEditor.registerTarget(QuestBookScreen.EditAction.TABLE_WEIGHT_DOWN, "", row.minus(),
                    row.minus().x(),
                    row.minus().y(), "", null, index);
        }
        if (weighed) {
            book.questEditor.registerTarget(QuestBookScreen.EditAction.TABLE_WEIGHT_UP, "", row.plus(), row.plus().x(),
                    row.plus().y(), "",
                    null, index);
            book.questEditor.registerTarget(QuestBookScreen.EditAction.TABLE_WEIGHT, "", row.value(),
                    row.value().x() + 2,
                    row.value().y(), "", null, index);
        }

        String mark = folded ? "\u25b2" : "\u25bc";
        boolean foldHot = row.fold().contains(mouseX, mouseY);
        r.text(mark, row.fold().x() + (row.fold().width() - r.textWidth(mark)) / 2,
                row.fold().y() + (line - 8) / 2,
                foldHot ? ArmatureTheme.title() : ArmatureTheme.body());
        if (foldHot) {
            tableHint = folded ? "Fold this entry back to its line" : "Open this entry's own fields";
        }
        book.questEditor.registerTarget(QuestBookScreen.EditAction.TABLE_FOLD, "", row.fold(), row.fold().x(),
                row.fold().y(), "", null,
                index);

        String cross = "\u00d7";
        boolean removeHot = row.remove().contains(mouseX, mouseY);
        r.text(cross, row.remove().x() + (row.remove().width() - r.textWidth(cross)) / 2,
                row.remove().y() + (line - 8) / 2,
                removeHot ? ArmatureTheme.title() : ArmatureTheme.body());
        if (removeHot) {
            tableHint = "Remove this entry";
        }
        book.questEditor.registerTarget(QuestBookScreen.EditAction.TABLE_REMOVE, "", row.remove(), row.remove().x(),
                row.remove().y(), "",
                null, index);

        // Folded open: the entry's own fields, chosen by the entry's reward type.
        //
        // This was two cases -- an item's id and count, and a nested table's "Edit" chip -- and every
        // other type folded onto eighteen pixels of nothing, so an experience, command, advancement,
        // stage or custom entry had no editable field anywhere in this panel. The controls now come from
        // `TableRowFields`, which answers for every registered type and falls back to the raw JSON for
        // anything it does not know; the band's height comes from the same list, one line per row of it,
        // so what is drawn and what the scroll arithmetic reserved cannot disagree.
        if (folded) {
            var model = tableModel();
            if (model.isPresent() && index < model.get().entryCount()) {
                var lines = dev.ellipog.tenet.client.dev.TableRowFields
                        .lines(model.get().entries().get(index).reward());
                for (int on = 0; on < lines.size(); on++) {
                    BookGeometry.Rect band = dev.ellipog.tenet.client.dev.TableEditorLayout
                            .foldBand(rect, on);
                    if (band.height() < dev.ellipog.tenet.client.dev.TableEditorLayout.FOLD_LINE_HEIGHT) {
                        // A band too short to hold a control is not a line, and the height arithmetic and
                        // this list disagreeing is the one thing that would put a control over the row
                        // below. Nothing here can fix that; a missing line is the honest symptom.
                        break;
                    }
                    var controls = lines.get(on).controls();
                    var cells = dev.ellipog.tenet.client.dev.TableEditorLayout
                            .foldCells(band, controls.size());
                    for (int c = 0; c < cells.size() && c < controls.size(); c++) {
                        drawFoldControl(r, cells.get(c), controls.get(c), index, mouseX, mouseY);
                    }
                }
            }
        }
    }

    /**
     * One control inside a folded entry: a switch, a chip, a stepper, a box, or the nested table's way in.
     *
     * <p>Every control registers its own target, so the press and the drawing are the same list — the
     * house rule, and the reason a folded row's controls cannot drift from where they are hit. The value
     * each one is drawn from comes through the draft, so a press that sent an op shows its result on the
     * next frame rather than when the server answers.
     *
     * <h2>{@code replaced}: the contract this method forgot</h2>
     *
     * <p>{@code InlineEdit.replaces} exists because a value the open field is showing must not be drawn
     * by the surface underneath it as well — the fault it was written for was prose "printed twice, a
     * line apart", and its own note says the callers are "the drawing sites of everything a field can
     * replace… A site that forgets is a value drawn twice". Every one of these sites forgot: the flag was
     * hardcoded {@code false}, so opening a command entry's text drew the old text in the box and the new
     * text in the editor, and the old one stayed until the submit rewrote the model. The card's fields
     * pass it; these do now, from the same one expression.
     */
    private void drawFoldControl(GuiRenderer r, BookGeometry.Rect box,
                                 dev.ellipog.tenet.client.dev.TableRowFields.Control control, int index,
                                 int mouseX, int mouseY) {
        boolean hot = box.contains(mouseX, mouseY);
        JsonObject entry = book.bookAssets.draftedEntry(index);
        // The picture override is an object, not an id: its item lives one level down, and the box
        // must read that rather than the object itself, which would show an empty "Picture" control
        // over a set icon — the card's own entry form reads it the same way.
        String value = entry == null ? ""
                : "icon".equals(control.field()) ? QuestBookScreen.rawValue(entry, "reward.icon.item")
                        : QuestBookScreen.rawValue(entry, "reward." + control.field());
        int baseline = box.y() + (box.height() - 8) / 2;
        // The field this control commits to is the field the editor would be standing in for.
        boolean replaced = dev.ellipog.tenet.client.dev.InlineEdit
                .replaces(BookAssets.tableEntryPath(index, control.field()), book.bookState.editingPath);

        switch (control.kind()) {
            case FLAG -> {
                boolean on = entry != null && QuestBookScreen.flagOn(entry, "reward." + control.field());
                // The chip and its label together, without a frame: a control that is a chip does not
                // need a box drawn around it as well. That is the card's own rule for a switch.
                book.questEditor.drawSwitch(r, box, on);
                String label = Measure.truncate(control.label(), Math.max(0, box.width() - 16),
                        book.textMeasure(r));
                r.text(label, box.x() + 15, baseline, on ? ArmatureTheme.title() : ArmatureTheme.faint());
                book.questEditor.registerTarget(QuestBookScreen.EditAction.TABLE_FOLD_FLAG, control.field(),
                        box, box.x(),
                        box.y(), value,
                        null, index);
            }
            case CHOICE -> {
                // An absent value is the field's own first word rather than nothing: `auto` unset means
                // "default", and an empty box with an options mark reads as a control that is broken. The
                // card's form draws the same empty box, so this is the one place that decides it.
                book.questEditor.drawValue(r, box, dev.ellipog.tenet.client.dev.TableRowFields.choiceText(value), "",
                        value.isEmpty(), replaced, mouseX, mouseY);
                book.questEditor.drawOptionsMark(r, box);
                book.questEditor.registerTarget(QuestBookScreen.EditAction.TABLE_FOLD_CHOICE, control.field(),
                        box, box.x() + 4,
                        box.y() + 3, value, null, index);
            }
            case INT, DECIMAL -> {
                // The label's rectangle comes from the layout, like every other control's: drawn at
                // `plus.right() + 4` it began at the cell's right edge, so on a two-control line the
                // left stepper's label ran across the right one's `-` chip.
                var number = dev.ellipog.tenet.client.dev.TableEditorLayout
                        .foldNumber(box, control.label().length());
                book.questEditor.drawChip(r, number.minus(), "-", number.minus().contains(mouseX, mouseY));
                book.questEditor.drawChip(r, number.plus(), "+", number.plus().contains(mouseX, mouseY));
                r.text(Measure.truncate(control.label(), number.label().width(), book.textMeasure(r)),
                        number.label().x(), baseline, ArmatureTheme.faint());
                if (!replaced) {
                    // The stepper's own number, drawn here rather than through `drawValue`: it sits
                    // between the chips, so it has its own rectangle -- and its own turn at asking.
                    r.text(Measure.truncate(value.isEmpty() ? "0" : value,
                                    Math.max(0, number.value().width() - 4), book.textMeasure(r)),
                            number.value().x() + 2, baseline, ArmatureTheme.body());
                }
                book.questEditor.registerTarget(QuestBookScreen.EditAction.TABLE_FOLD_STEP_DOWN,
                        control.field(), number.minus(),
                        number.minus().x(), number.minus().y(), value, null, index);
                book.questEditor.registerTarget(QuestBookScreen.EditAction.TABLE_FOLD_STEP_UP,
                        control.field(), number.plus(),
                        number.plus().x(), number.plus().y(), value, null, index);
                book.questEditor.registerTarget(QuestBookScreen.EditAction.TABLE_FOLD_TEXT, control.field(),
                        number.value(),
                        number.value().x() + 2, number.value().y(), value, null, index);
            }
            case ITEM -> {
                int textX = book.questEditor.drawItemValue(r, box, value, replaced, mouseX, mouseY);
                book.questEditor.registerTarget(QuestBookScreen.EditAction.TABLE_ENTRY_ITEM, control.field(),
                        box, textX,
                        box.y() + 3, value, null, index);
            }
            case TABLE_OPEN -> {
                book.questEditor.drawValue(r, box, "Edit this table", "", false, false, mouseX, mouseY);
                book.questEditor.registerTarget(QuestBookScreen.EditAction.TABLE_OPEN_CHILD, control.field(),
                        box, box.x() + 4,
                        box.y() + 3, value, null, index);
            }
            case RAW -> {
                book.questEditor.drawValue(r, box, "edit JSON", "", true, false, mouseX, mouseY);
                book.questEditor.registerTarget(QuestBookScreen.EditAction.TABLE_FOLD_RAW, control.field(),
                        box, box.x() + 4,
                        box.y() + 3, value, null, index);
            }
            default -> {
                // TEXT: the box shows the value, or its label when it is empty -- the same contract the
                // card's text fields have, which is what tells an author what a blank box wants. And the
                // editor's own field stands in for the value while it is open: see `replaced` above.
                String shown = value.isEmpty() ? control.label() : value;
                book.questEditor.drawValue(r, box, shown, "", value.isEmpty() && !replaced, replaced, mouseX, mouseY);
                book.questEditor.registerTarget(QuestBookScreen.EditAction.TABLE_FOLD_TEXT, control.field(),
                        box, box.x() + 4,
                        box.y() + 3, value, null, index);
            }
        }
        if (hot && !control.hint().isEmpty()) {
            // A field's own sentence, as a tooltip on the field: the footer band is for what the *page* is
            // (the mode's meaning, the roll's summary), and a form of twenty controls all explaining
            // themselves one at a time in one shared line is a line that changes faster than it can be
            // read. The card's fields have used this surface all along.
            book.bookState.pendingLabels.add(new QuestBookScreen.PendingLabel(box, control.hint()));
        }
    }

    /**
     * What a test roll shows: the summary, one line per entry with its rate, and a scroll.
     *
     * <h2>Why this is a page of the panel rather than a replacement for it</h2>
     *
     * <p>The first version printed the roll over the entry list as plain text and left the author there:
     * the toolbar stayed, Done still meant "leave the table", and Escape was the only way back -- a way
     * out nobody can see. The page has its own heading, its own scroll, and the toolbar's roll button
     * becomes the way back (see {@code drawTableEditorToolbar}), so the state is a place with a door
     * rather than a trap. The summary is arithmetic, not a text dump: every line says how many times the
     * entry came up and what share of the rolls that is.
     */
    private void drawTableRollPane(GuiRenderer r, int mouseX, int mouseY) {
        BookGeometry.Rect list = book.bookState.tableEditorFrame.list();
        r.fill(list.x(), list.y(), list.right(), list.bottom(), ArmatureTheme.recessed());
        var held = dev.ellipog.tenet.client.ClientTableRoll.of(tableDescribe());
        if (held == null) {
            r.text("Rolling...", list.x() + 4, list.y() + 2, ArmatureTheme.faint());
            return;
        }
        // The heading strip, where the entry page keeps its column names: what was rolled, and how.
        r.text("Test roll", list.x() + 2, list.y() + 2, ArmatureTheme.title());
        String summary = held.rolls() + (held.rolls() == 1 ? " roll as " : " rolls as ")
                + held.mode().wire()
                + (held.truncated() ? "  (nesting cut off)" : "");
        r.text(Measure.truncate(summary, Math.max(0, list.width() - 66), book.textMeasure(r)),
                list.x() + 58, list.y() + 2, ArmatureTheme.faint());

        BookGeometry.Rect band = book.bookState.tableEditorFrame.rows();
        // The page's own range and clamp, from the same line count its drawing walks: `maxRollScroll` was
        // the clamp here and the drawing's `y` was computed from the same pitch, which is the pair this
        // replaces with one object. A report is a list like any other and it gets a bar like any other --
        // it is lines of text about a roll, and "there is more below" is exactly what it has to say.
        QuestBookScreen.bindList(book.bookState.rollBody, book.bookState.rollBar, band,
                book.bookState.tableEditorFrame.scrollbar(),
                rollLines(held) * QuestBookScreen.ROLL_PITCH,
                QuestBookScreen.ROLL_PITCH);
        int rollScroll = book.bookState.rollBody.scrollY();
        try (GuiRenderer.Scoped clip = r.clip(band.x(), band.y(), band.right(), band.bottom())) {
            int y = band.y() - rollScroll;
            for (var row : held.rows()) {
                if (y + QuestBookScreen.ROLL_PITCH > band.y() && y < band.bottom()) {
                    drawRollRow(r, held, row.display(), row.depth(), row.hits(), row.emptyHits(), y, band);
                }
                y += QuestBookScreen.ROLL_PITCH;
            }
            if (held.emptyHits() > 0) {
                drawRollRow(r, held, null, 0, held.emptyHits(), 0, y, band);
                y += QuestBookScreen.ROLL_PITCH;
            }
            // And what no roll could pay out, last and in the blocked ink: a report that simply showed
            // fewer rows than the file has reads as those entries not being in the table, and sends the
            // author looking in the wrong place.
            for (String note : held.notRolled()) {
                if (y + QuestBookScreen.ROLL_PITCH > band.y() && y < band.bottom()) {
                    r.text(Measure.truncate(note, Math.max(0, band.width() - 8), book.textMeasure(r)),
                            band.x() + 2, y, ArmatureTheme.blocked());
                }
                y += QuestBookScreen.ROLL_PITCH;
            }
        }
        // The report's own bar, beside the lines rather than over them: the strip is the one the editor's
        // entries use, so the two views of this panel put their grip in the same column.
        QuestBookScreen.drawBar(r, book.bookState.rollBar, mouseX, mouseY);
    }

    /** How many lines the roll report has: every entry, the empty band's, and the notes'. */
    private static int rollLines(dev.ellipog.tenet.client.ClientTableRoll.Held held) {
        return held.rows().size() + (held.emptyHits() > 0 ? 1 : 0) + held.notRolled().size();
    }

    /**
     * One line of the roll: the entry, how often it came up, and what share of the rolls that is.
     *
     * <p>A {@code null} display is the empty band -- the rolls that paid nothing -- which is why it is
     * drawn by this method rather than by a second one: it is a line like the others, with a rate.
     *
     * <p>The rate comes from the report's own mode, so a report rolled as {@code all_table} says 100%
     * and one rolled as {@code choice} says Pick, whatever the chip behind the pane has been pressed to
     * since. The count is not drawn for a mode with no dice: "0x" beside "Pick" is a number about
     * nothing.
     */
    private void drawRollRow(GuiRenderer r, dev.ellipog.tenet.client.ClientTableRoll.Held held,
                             dev.ellipog.tenet.net.TableRollPayload.Display display, int depth, int hits,
                             int emptyHits, int y, BookGeometry.Rect band) {
        int x = band.x() + 2 + depth * 10;
        String name = display == null ? "nothing"
                : dev.ellipog.tenet.client.RewardText.of(display.label(), display.fallback(),
                        display.arg(), display.count(), display.item());
        String rate = dev.ellipog.tenet.client.dev.TableEditorLayout
                .reportRate(held.mode(), hits, held.rolls());
        r.text(rate, band.right() - 4 - r.textWidth(rate), y,
                display == null ? ArmatureTheme.faint() : ArmatureTheme.body());
        boolean diced = held.mode() == dev.ellipog.tenet.quest.reward.TableReward.Mode.RANDOM
                || held.mode() == dev.ellipog.tenet.quest.reward.TableReward.Mode.LOOT;
        int countedWidth = 0;
        if (diced) {
            String counted = hits + "x";
            countedWidth = r.textWidth(counted);
            r.text(counted, band.right() - 44 - countedWidth, y, ArmatureTheme.faint());
        }
        String paid = emptyHits > 0 ? name + " (paid nothing " + emptyHits + "x)" : name;
        r.text(Measure.truncate(paid, Math.max(0, band.right() - 96 - countedWidth - x), book.textMeasure(r)),
                x, y, display == null ? ArmatureTheme.faint() : ArmatureTheme.body());
    }

    /**
     * Drops the folds that name a row that is no longer there.
     *
     * <p>An undo, another player's edit or a reload all move the list under a set of indices, and a fold
     * left pointing past the end is a fold whose lines are drawn nowhere while its presence still
     * inflates the scroll extent — a list with a gap at the bottom and no row to explain it.
     */
    private void reconcileFolds() {
        int entries = tableModel().map(dev.ellipog.tenet.quest.loot.RewardTable::entryCount).orElse(0);
        book.bookState.tableFolded.removeIf(at -> at < 0 || at >= entries);
    }

    /** The weight the table's entries add up to, for the header. */
    private static double totalWeight(JsonObject root) {
        double total = 0;
        if (root == null || !root.has("entries") || !root.get("entries").isJsonArray()) {
            return 0;
        }
        for (JsonElement element : root.getAsJsonArray("entries")) {
            if (element.isJsonObject()) {
                JsonElement weight = element.getAsJsonObject().get("weight");
                if (weight != null && weight.isJsonPrimitive() && weight.getAsJsonPrimitive().isNumber()) {
                    total += weight.getAsDouble();
                }
            }
        }
        return total;
    }

    /** What to call a table in the breadcrumb, without decoding it. */
    private String tableNameOf(dev.ellipog.tenet.editor.TableAddress address) {
        return switch (address.owner()) {
            case dev.ellipog.tenet.editor.TableAddress.Owner.Named named -> {
                ClientQuestCache.TableSummary summary = ClientQuestCache.table(named.id());
                yield summary == null ? named.id() : summary.titleText();
            }
            case dev.ellipog.tenet.editor.TableAddress.Owner.InQuest quest -> "inline table";
        };
    }

    /**
     * Whether this line's shape is a bow the bend handle steers: a curve, and only a curve.
     *
     * <p>The handle sits at the curve's midpoint, where {@link LineArt#bendAt} reads the bow — the exact
     * inverse of {@link LineArt#curve}, which is what makes the drag land where the pointer is.
     */
    private static boolean bows(DependencyStyle style) {
        return style.formOr(DependencyStyle.Form.CHAMFERED) == DependencyStyle.Form.CURVED;
    }

    // ------------------------------------------------------------------
    // The rewards panel
    // ------------------------------------------------------------------

    /**
     * Opens the rewards panel: what the server owes this player, and the two presses that collect it.
     *
     * <p>Whatever card was open is closed first, the same way the choice card opens -- and unlike the
     * choice card there is nothing to remember while it is up: the list is read from the progress the
     * client already holds, so closing leaves no trace either.
     */
    void openRewardsOverlay() {
        book.closeOverlay();
        book.applyColumns(PanelStack.asRoot(book.columns(), PanelKind.REWARDS));
        book.bookState.rewardsRevision = ClientQuestCache.progressRevision();
        // The marks are **not** cleared here, and that is the fix rather than an omission: a press whose
        // answer is still in flight has to survive the card being closed over it, or reopening would show
        // a row the player had already claimed as ready again. A mark nobody ever answers now expires on
        // its own and says so -- see PendingClaims.
        book.bookState.rewardMarksRevision = book.bookState.pendingClaims.revision();
        book.bookState.rewardView.scrollTo(0);
        book.rebuildWidgets();
    }

    /**
     * The card's controls: a chapter accordion, its quests, their rewards, Claim all, and Back.
     *
     * <h2>One button per row, in the action column the layout reserved</h2>
     *
     * <p>The shape the tools panel's switches use: the row itself is drawn by {@link #drawRewardsOverlay},
     * and its button is placed in the action column outside the row's narrowed slot. A banner's or a
     * header's <b>body</b> is the accordion's handle and is hit-tested by {@link #toggleRewardRow} — so
     * the press a widget did not take is the press the row handles, and a click on Claim Chapter can
     * never also fold the row it belongs to.
     *
     * <h2>Status is the client's answer, not the client's decision</h2>
     *
     * <p>A row's button is live exactly when the cache says this player could take it, and the server
     * recomputes the same answer when the press arrives. A row that is already collected or gated is
     * drawn with a disabled button rather than no button: it is still part of what the quest gives, and
     * hiding it would make the accordion change shape as the player collects.
     *
     * <h2>Three levels, and the state decides what is in them</h2>
     *
     * <p>Chapters in the book's declared order, the quests of an expanded one, and the rewards of an
     * expanded quest. A container is listed when something inside it passed
     * {@link RewardInboxLayout.State#accepts} — so the Claimed view shows the chapters a player actually
     * collected from, rather than a banner for every chapter in the book with nothing under it.
     *
     * <h2>Why the rail is asked for before the rows are built</h2>
     *
     * <p>The rows have to be measured at the width the rail will actually be, and a rail's width is its
     * kind's alone — it does not depend on how tall the content came to, which is what the centred card's
     * framed form used to have to document as an invariant. So the rail is asked for first, the rows are
     * built at its width, and then they are applied to it.
     */
    List<ArmatureButton> buildRewardWidgets() {
        List<ArmatureButton> built = new ArrayList<>();
        rewardRows = List.of();
        rewardLayout = null;
        rewardCard = null;
        questRewardCells.clear();
        rewardListed = 0;
        UUID self = selfId();

        List<RewardInboxLayout.Row> rows = new ArrayList<>();
        List<Map.Entry<RewardInboxLayout.Row, List<RewardInboxLayout.Row>>> chapters = new ArrayList<>();
        int ordinal = 0;
        for (Map.Entry<String, String> chapter : BookContent.chapters().entrySet()) {
            ordinal++;
            String chapterId = chapter.getKey();
            List<RewardInboxLayout.Row> inside = new ArrayList<>();
            int ready = 0;
            if (self != null) {
                List<ClientQuestCache.Entry> quests = book.questsIn(chapterId);
                // A chapter can hold two quests with one title -- "Make a Table" is the one every pack
                // has twice -- so a repeated title is numbered by its position in the chapter. Computed
                // over this chapter's own list, so the number is stable for as long as the chapter is.
                Set<String> repeated = repeatedTitles(quests);
                for (int at = 0; at < quests.size(); at++) {
                    ClientQuestCache.Entry entry = quests.get(at);
                    if (entry.rewards().isEmpty()) {
                        continue;
                    }
                    // One walk per quest, read twice: the details column and the fold. See
                    // `questRewardCells`.
                    List<RewardInboxLayout.Row> leaves = rewardCells(self, chapterId, entry);
                    if (leaves.isEmpty()) {
                        continue;
                    }
                    questRewardCells.put(entry.id(), leaves);
                    // What is *owed*, not what is listed: a row whose press is in flight stays in the list
                    // -- it has to, or the list would change shape under the pointer -- and it is not
                    // something the player can still take. See PendingClaims.outstanding.
                    int owed = owedRows(leaves);
                    rewardListed += owed;
                    ready += owed;
                    inside.addAll(questRows(chapterId, entry, leaves, questLabel(entry, at, repeated)));
                }
            }
            if (inside.isEmpty()) {
                continue;
            }
            // The badge's number is the rows this walk just admitted, not a second count of the
            // chapter's rewards. That is deliberate: a count taken from somewhere else could disagree
            // with the list under it, and the first version of this did -- it counted every reward the
            // chapter defined, including quests this player cannot reach, beside a completion figure
            // counted over visible quests only.
            chapters.add(Map.entry(RewardInboxLayout.Row.chapter(chapterId,
                    chapterLabel(ordinal, chapterId, chapter.getValue()), ready,
                    containerStatus(RewardInboxLayout.chapterKey(chapterId), inside)), inside));
        }
        if (!chapterFoldTouched && chapters.size() == 1) {
            expandedChapters.add(chapters.get(0).getKey().chapterId());
        }
        for (Map.Entry<RewardInboxLayout.Row, List<RewardInboxLayout.Row>> chapter : chapters) {
            rows.add(chapter.getKey());
            if (expandedChapters.contains(chapter.getKey().chapterId())) {
                rows.addAll(chapter.getValue());
            }
        }
        rewardRows = List.copyOf(rows);

        int cardWidth = book.framed(PanelKind.REWARDS).width();
        rewardLayout = RewardInboxLayout.build(rewardRows, rewardBodyWidth(cardWidth),
                Measure.monospace(6, 9));
        // The rail, and no content height: it is as tall as the canvas, and the rows scroll inside the band
        // `rewardBody` gives them. The height this used to compute sized a centred card.
        rewardCard = book.framed(PanelKind.REWARDS);
        Viewport body = rewardBody();

        book.bookState.rewardView.clear();
        book.bookState.rewardView.whole(true);
        book.bookState.rewardView.viewport().bounds(body.originX(), body.originY(), body.viewWidth(),
                body.viewHeight());
        for (RewardInboxLayout.Row row : rewardRows) {
            ArmatureButton button = book.control(0, 0, 0, 0, buttonLabel(row), rowPress(row));
            // A real button face rather than flat text: every row here is a click target, and a bare
            // label reads as status text until the pointer happens to find it.
            if (!row.pressable()) {
                // Nothing left to take: a disabled control draws in the blocked colour and ignores the
                // press, which is the honest shape for a row whose claim the server would refuse.
                button.active = false;
            }
            button.tooltip(buttonTooltip(row));
            book.bookState.rewardView.put(row.key(), button, RewardInboxLayout::strip);
            built.add(button);
        }
        book.bookState.rewardView.apply(rewardLayout, body.viewWidth());

        built.addAll(buildRewardStateChips());

        // The footer: the sweep where the reader's Submit sits, Back where every card's does, and the
        // sweep's label and reach follow the active state. `hasSubmit` is true only while the view holds
        // something, because a sweep reaches exactly what the view shows: an empty choices view offering
        // a primary "Choose all" beside a line saying nothing needs choosing is the card disagreeing
        // with itself.
        Map<String, BookGeometry.Rect> controls =
                book.geometry().questFooter(rewardCard, rewardListed > 0);
        ArmatureButton all = book.control(controls.get("submit"), footerClaimLabel(),
                () -> claimAllRewards(rewardState.filter()));
        if (all != null) {
            List<Component> tip = new ArrayList<>();
            tip.add(Component.translatable(rewardState == RewardInboxLayout.State.READY
                    ? "tenet.screen.collects_everything_the_server_is_holding"
                    : "tenet.screen.collects_only_what_the_filter_shows"));
            // One flag for the whole record, so the sweep asks once rather than per row.
            if (ClientQuestCache.rewardsHeld()) {
                tip.add(Component.translatable("tenet.screen.rewards.held"));
            }
            all.accent(true).tooltip(List.copyOf(tip));
            built.add(all);
        }
        ArmatureButton back = book.control(controls.get("back"),
                Component.translatable("tenet.screen.done"), book::closeOverlay);
        if (back != null) {
            back.ink(ArmatureButton.Ink.BODY);
            built.add(back);
        }
        return List.copyOf(built);
    }

    /**
     * How many of these rows the player can still take.
     *
     * <p>One rule, read by the badge, the subtitle and the footer's own "is there anything to sweep":
     * {@code PENDING} is listed and is not owed, which is the whole reason the two questions differ.
     */
    private static int owedRows(List<RewardInboxLayout.Row> leaves) {
        int owed = 0;
        for (RewardInboxLayout.Row leaf : leaves) {
            if (leaf.status() == RewardInboxLayout.Status.READY) {
                owed++;
            }
        }
        return owed;
    }

    /**
     * Takes the rewards card's controls down and builds them again, without rebuilding the screen.
     *
     * <h2>Why this exists rather than a {@code rebuildWidgets()}</h2>
     *
     * <p>A press used to cost a full {@code init()} — every rail, every field, every table row — to change
     * one button's state, and the progress sync that answers it costs a second one. This is the first of
     * those two: the card's own controls and nothing else, which is what makes a press look like it landed
     * rather than like the screen blinked.
     *
     * <p>The reveal guard defers a rebuild while the rail is still sliding in: redoing travelling
     * widgets for every mark that moves mid-flight would be churn per frame, so the build waits for
     * the tick that settles it. See the inline note for what is consumed meanwhile.
     */
    private void refreshRewardCard() {
        if (book.panelRevealing(PanelKind.REWARDS, net.minecraft.util.Util.getMillis())) {
            // The build is deferred to the tick that settles the slide, so the marks are consumed here:
            // the deferred build will place controls that already know about them. Without this the tick
            // would see the same change on every frame of the slide and rebuild for each one.
            book.bookState.panelBuildPending = true;
            book.bookState.rewardMarksRevision = book.bookState.pendingClaims.revision();
            return;
        }
        // Filed under REWARDS if the bucket is missing it — see slideList for the crash this
        // answers: a full-screen rebuild clears the slide filing without touching this card's
        // controls (they rebuild lazily), so the first claim press after a rebuild — F3+T with
        // the book open is the reliable way — found no REWARDS entry and died removing from
        // List.of(). Filing here changes nothing when the entry exists, and when it does not
        // the removals below still run their cleanup.
        java.util.List<AbstractWidget> slid = QuestBookScreen.slideList(book.bookState.slideWidgets, PanelKind.REWARDS);
        for (ArmatureButton control : book.bookState.rewardControls) {
            book.removeBookWidget(control);
            book.bookState.buttons.remove(control);
            slid.remove(control);
            book.bookState.slideBaseX.remove(control);
        }
        // Filed under the rail like the first build was, so a refresh keeps travelling with a slide that
        // is still in flight rather than landing its replacements early.
        PanelKind outer = book.bookState.slideBucket;
        book.bookState.slideBucket = PanelKind.REWARDS;
        try {
            book.bookState.rewardControls = buildRewardWidgets();
        }
        finally {
            book.bookState.slideBucket = outer;
        }
        book.bookState.rewardMarksRevision = book.bookState.pendingClaims.revision();
        book.refreshOverlayMute();
    }

    /**
     * One quest's rows, from the rewards already admitted for it.
     *
     * <p>Empty when nothing inside the quest belongs in the current view, which is what makes a chapter
     * of untouched quests contribute no rows rather than a run of headers with nothing under them.
     *
     * <p>{@code label} is the quest's title, already numbered if its chapter uses that title twice —
     * composed by the caller because the numbering is a property of the chapter's list rather than of
     * one quest. See {@link #questLabel}.
     */
    private List<RewardInboxLayout.Row> questRows(String chapterId, ClientQuestCache.Entry entry,
                                                  List<RewardInboxLayout.Row> leaves, String label) {
        if (entry.rewards().size() == 1) {
            // One reward in the definition: one row, the reward in the details column, one Claim. Never
            // decided from what is left outstanding -- a row that collapsed to a single line the moment
            // a child was claimed would move every row below it under the player's pointer.
            return List.of(singleRow(chapterId, entry, label, leaves.get(0).status()));
        }

        List<RewardInboxLayout.Row> out = new ArrayList<>();
        out.add(RewardInboxLayout.Row.quest(chapterId, entry.id(), label, leaves.size(),
                containerStatus(RewardInboxLayout.questKey(entry.id()), leaves)));
        if (expandedQuests.contains(entry.id())) {
            out.addAll(leaves);
        }
        return out;
    }

    /**
     * The titles a chapter uses more than once, so a repeated one can be numbered.
     *
     * <p>A set rather than a count, because the caller only ever asks "is this title ambiguous" — and
     * one pass over the chapter's own list is what makes the numbering stable for that chapter.
     */
    /**
     * The titles that appear more than once in a chapter's list, in the words a player reads.
     *
     * <p>Package-private rather than private so the rule can be asserted without a screen: it is a pure
     * function of the entries, and the thing that can be got wrong is <i>which</i> accessor it reads —
     * which is exactly the kind of claim that rots silently. See {@code RepeatedQuestTitlesTest}.
     */
    static Set<String> repeatedTitles(List<ClientQuestCache.Entry> quests) {
        Set<String> seen = new HashSet<>();
        Set<String> repeated = new HashSet<>();
        for (ClientQuestCache.Entry entry : quests) {
            // The words the player reads, not the wire's key: two quests whose titles are the same
            // string in the player's language are the duplicate the number exists to disambiguate, and
            // a set built from the raw values would number rows that read identically and miss the ones
            // that do not.
            if (!seen.add(entry.titleText())) {
                repeated.add(entry.titleText());
            }
        }
        return repeated;
    }

    /**
     * A quest's row label, numbered only when its chapter uses that title twice.
     *
     * <p>Two quests called "Make a Table" in one chapter are indistinguishable on screen, which is the
     * duplicate-name problem the chapter banner only solves <i>between</i> chapters. The number is the
     * quest's position in its chapter, and it is drawn <b>only</b> for a title that actually repeats —
     * numbering every row would put a number on the ninety per cent of rows that do not need one.
     */
    /**
     * A quest's row label, numbered only when its chapter uses that title twice.
     *
     * <p>Package-private beside {@link #repeatedTitles}, and for the same reason: the two have to agree
     * about <i>which</i> string they are comparing, and a number drawn for a title the set does not
     * hold — or withheld from one it does — is invisible in English and wrong under any locale. See
     * {@code RepeatedQuestTitlesTest}.
     */
    static String questLabel(ClientQuestCache.Entry entry, int at, Set<String> repeated) {
        String title = QuestBookScreen.titleOf(entry);
        return repeated.contains(entry.titleText()) ? (at + 1) + " \u00b7 " + title : title;
    }

    /**
     * A quest's rewards as rows, for the rewards the active view admits.
     *
     * <p>One walk, read twice: it is what the fold lists and what the details column draws, which is why
     * the column shows a folded quest's rewards rather than going blank until it is opened.
     */
    private List<RewardInboxLayout.Row> rewardCells(UUID self, String chapterId,
                                                    ClientQuestCache.Entry entry) {
        List<RewardInboxLayout.Row> out = new ArrayList<>();
        for (int index = 0; index < entry.rewards().size(); index++) {
            RewardInboxLayout.Status status = statusOf(self, entry, index);
            if (rewardState.accepts(status, isChoiceType(entry.rewards().get(index).type()))) {
                out.add(rewardInboxRow(chapterId, entry, index, status));
            }
        }
        return out;
    }

    /** This client's player, or null before there is one — a headless client, or a screen built early. */
    UUID selfId() {
        return book.minecraft().player == null ? null : book.minecraft().player.getUUID();
    }

    /**
     * A container row's status, from the leaves it holds.
     *
     * <p>Ready when anything inside it is: the press takes the whole container, so its button is live
     * exactly when there is something for it to take. A press already in flight reads collected — the
     * optimistic half every row has — and a container whose leaves are all collected reads collected
     * too, so the Claimed view's banners are not drawn as though they were still owed.
     *
     * <p>The fallback is collected rather than locked, and that is exact rather than a guess: a locked
     * leaf is admitted by no state at all, so a container with rows in it holds ready, pending or
     * collected ones and nothing else.
     */
    private RewardInboxLayout.Status containerStatus(String key, List<RewardInboxLayout.Row> leaves) {
        if (book.bookState.pendingClaims.isMarked(key)) {
            return RewardInboxLayout.Status.PENDING;
        }
        for (RewardInboxLayout.Row leaf : leaves) {
            if (leaf.status() == RewardInboxLayout.Status.READY) {
                return RewardInboxLayout.Status.READY;
            }
        }
        return RewardInboxLayout.Status.CLAIMED;
    }

    /** What a row's action does. */
    private Runnable rowPress(RewardInboxLayout.Row row) {
        return switch (row.kind()) {
            case CHAPTER -> () -> claimChapterFromInbox(row.chapterId());
            case QUEST -> () -> claimQuestFromInbox(row.questId());
            case SINGLE, REWARD -> () -> claimReward(row.questId(), row.rewardIndex());
        };
    }

    /**
     * A chapter's banner title: the chapter's own title, and nothing else.
     *
     * <p>It used to be the group, the ordinal and the title — "First Light › 1 · The Engine" — and the
     * row read "First Light › 1 · Th..." on any card that mattered: eighteen characters of navigation
     * metadata before the one word the reader came for, truncated mid-word. The ordinal told a reader
     * which section they were in when a banner scrolled to the top, and the group told them which group
     * it hung under — both context, both now in the row's hover, where context belongs. The row owns
     * the title; the hover owns the rest.
     */
    private String chapterLabel(int ordinal, String chapterId, String title) {
        return title;
    }

    /** The title of the group a chapter hangs under, or empty for a chapter that belongs to none. */
    private static String groupTitleOf(String chapterId) {
        for (ClientQuestCache.ChapterEntry chapter : ClientQuestCache.chapters()) {
            if (!chapter.id().equals(chapterId)) {
                continue;
            }
            if (chapter.groupId().isEmpty()) {
                return "";
            }
            for (ClientQuestCache.GroupEntry group : ClientQuestCache.groups()) {
                if (group.id().equals(chapter.groupId())) {
                    return group.titleText();
                }
            }
            return "";
        }
        return "";
    }

    /** The body's width for a card of this width: what the card holds inside its insets. */
    private static int rewardBodyWidth(int cardWidth) {
        return Math.max(0, cardWidth - BookGeometry.MODAL_INSET * 2);
    }

    /**
     * The claim menu's body, from the card last built.
     *
     * <p>Its own rather than {@link QuestBookScreen#overlayBody()}'s, because this card is its own rectangle: the menu
     * is wider than the modal and its header band is taller, since it carries the state toggles. The
     * shape of the arithmetic is the overlay's — an inset at the sides, a stop above and a stop below —
     * so the two bodies are read the same way even though they are different rectangles.
     */
    private Viewport rewardBody() {
        BookGeometry.Rect card = rewardCard;
        return book.bookState.rewardView.viewport().bounds(
                card.x() + BookGeometry.MODAL_INSET,
                card.y() + REWARDS_BODY_TOP,
                rewardBodyWidth(card.width()),
                Math.max(0, card.height() - REWARDS_BODY_TOP - BookGeometry.MODAL_CHROME));
    }

    /** Whether the card is too narrow to hold its title and its toggles side by side. */
    private boolean isRewardChipsNarrow() {
        if (rewardCard == null) {
            return false;
        }
        int chipWidth = stateChipWidth();
        int gap = 4;
        int states = RewardInboxLayout.State.values().length;
        int right = rewardCard.right() - BookGeometry.MODAL_INSET;
        int titleRoom = 90;
        return right - (states * chipWidth + (states - 1) * gap)
                < rewardCard.x() + BookGeometry.MODAL_INSET + titleRoom;
    }

    /** The filter chips, right-aligned in the card's header band. Answers the chips it made. */
    private List<ArmatureButton> buildRewardStateChips() {
        List<ArmatureButton> built = new ArrayList<>();
        RewardInboxLayout.State[] states = RewardInboxLayout.State.values();
        int chipHeight = STATE_CHIP_HEIGHT;
        int chipWidth = stateChipWidth();
        int gap = 4;
        // Under the two lines of title and above the rule, from the same number the rule is drawn at.
        int y = rewardCard.y() + REWARDS_BODY_TOP - STATE_CHIP_HEIGHT - STATE_CHIP_GAP;
        int right = rewardCard.right() - BookGeometry.MODAL_INSET;
        // Chips win over the title when the two collide: dropping the toggles strands the reader in
        // one view with no way out, while dropping the title loses decoration. The overlay skips the
        // title text when narrow (see drawRewardsOverlay) so the two never overwrite each other.
        for (int i = 0; i < states.length; i++) {
            RewardInboxLayout.State state = states[i];
            int x = right - (states.length - i) * chipWidth - (states.length - 1 - i) * gap;
            ArmatureButton chip = book.control(x, y, chipWidth, chipHeight, stateLabel(state),
                    () -> setRewardState(state));
            chip.selected(state == rewardState);
            built.add(chip);
        }
        return built;
    }

    /**
     * How wide one state toggle is: the longest of the labels, plus its padding.
     *
     * <p>Measured rather than picked, which is the rule the rest of this file's chips arrived at the
     * hard way: a fixed width that happens to fit "Choices" truncates "Choices pending", and a truncated
     * toggle reads as a control whose label nobody finished. The stand-in measure is the one every
     * layout in this screen is built with, so the chip and the rows agree about how wide a character is.
     */
    private static int stateChipWidth() {
        Measure measure = Measure.monospace(6, 9);
        int widest = 0;
        for (RewardInboxLayout.State state : RewardInboxLayout.State.values()) {
            widest = Math.max(widest, measure.width(stateLabel(state).getString()));
        }
        return widest + 12;
    }

    /** A press on a toggle: the list narrows, and the footer follows it. */
    private void setRewardState(RewardInboxLayout.State state) {
        if (rewardState == state) {
            return;
        }
        rewardState = state;
        book.bookState.rewardView.scrollTo(0);
        refreshRewardCard();
    }

    /** What one toggle says. */
    private static Component stateLabel(RewardInboxLayout.State state) {
        return Component.translatable(switch (state) {
            case READY -> "tenet.screen.rewards.state_ready";
            case CHOICES -> "tenet.screen.rewards.state_choices";
        });
    }

    /** The footer button's label, which says exactly what the press will reach. */
    private Component footerClaimLabel() {
        return Component.translatable(rewardState == RewardInboxLayout.State.CHOICES
                ? "tenet.screen.rewards.choose_all" : "tenet.screen.rewards.claim_all");
    }

    /** Whether the inbox is on its Choices tab, where every bulk press is a pick rather than a claim. */
    private boolean choosing() {
        return rewardState == RewardInboxLayout.State.CHOICES;
    }

    /** The one type that asks a question instead of handing something over. */
    private static boolean isChoiceType(String type) {
        return "tenet:choice".equals(type);
    }

    /**
     * The quests whose rewards this player can collect right now, in the tree's order.
     *
     * <p>Asked of {@code ClientQuestCache.canClaimFor} rather than derived from a state: that is the same
     * question the reader's Claim button asks, so the panel and the button cannot disagree about what is
     * waiting -- and it is per player, so a teammate having collected their copy does not hide this one's.
     *
     * <p>Not the menu's row list any more — the menu groups by chapter and admits rows by the active
     * state, which is {@link #buildRewardWidgets}. This is the header's question: whether anything at all
     * is waiting, so the button that opens the menu can say so before it is pressed.
     */
    List<ClientQuestCache.Entry> claimableQuests() {
        UUID self = book.minecraft().player == null ? null : book.minecraft().player.getUUID();
        if (self == null) {
            return List.of();
        }
        List<ClientQuestCache.Entry> out = new ArrayList<>();
        for (ClientQuestCache.Entry entry : ClientQuestCache.entries()) {
            // Through the marks, so the header's "N waiting" comes down with the rows the player has
            // just pressed rather than one round trip later.
            if (book.claimableWithMarks(self, entry)) {
                out.add(entry);
            }
        }
        return List.copyOf(out);
    }

    /**
     * The sweep: one press, and the server walks the book with the same test the single claim uses.
     *
     * <p>The view's filter travels with it, so a view of choices claims choices — the button's label is
     * a promise, and the server is the side that keeps it. Never called with a null filter: the Claimed
     * view draws no sweep, so there is nothing to press.
     *
     * <p>Every reward the filter reaches is marked before the packet goes, which is what makes the sweep
     * look like one press rather than a list that empties a round trip later. The walk is the same one
     * {@link #rewardCells} makes, so what is marked is exactly what the server is being asked for — and a
     * press whose key is already marked is <b>not sent again</b>, which is the guard a double press used
     * to lack.
     */
    private void claimAllRewards(ClaimFilter filter) {
        long now = net.minecraft.util.Util.getMillis();
        UUID self = selfId();
        // A choices sweep marks nothing, and that is the rule `markPending` states one row down: a choice
        // is not collected by a press, it is collected by an answer, so a row that read collected while its
        // card was open would be the card promising a payout that has not happened. The sweep still goes.
        if (self != null && filter != ClaimFilter.CHOICES) {
            // `reached` rather than "did anything get marked": a reward that asks a question is reachable
            // and is never marked (a choice is collected by an answer, not a press), so a sweep whose only
            // outstanding rewards are choices still has something to ask the server for.
            boolean reached = false;
            for (ClientQuestCache.Entry entry : ClientQuestCache.entries()) {
                for (int index = 0; index < entry.rewards().size(); index++) {
                    if (ClientQuestCache.canClaimReward(self, entry, index)) {
                        reached = true;
                        markPending(entry.id(), index, now);
                    }
                }
            }
            if (!reached) {
                // Nothing outstanding anywhere: the sweep would be a packet asking for nothing.
                return;
            }
        }
        refreshRewardCard();
        ArmatureNetwork.sendToServer(new ClaimAllPayload(filter));
    }

    /**
     * The banner's press: the whole chapter's rewards, marked collected until the server answers.
     *
     * <p>Every reward row of every quest in the chapter goes pending with the banner, for the reason the
     * quest's own press marks its children: a banner reading collected while the rows under it still
     * offered their own buttons would be the card disagreeing with itself about one press.
     *
     * <p>The chapter's quests are walked from the tree rather than from the built rows, because the rows
     * hold only what the active state admits — and the press takes what the server will pay, which is a
     * question the view does not get to answer.
     *
     * <p>The view's filter travels with it, for the reason the footer's does: a banner drawn in a view
     * of choices must not reach past what the player can see. The record normalises the null the Claimed
     * view would send, and that view draws no pressable banner to send it from.
     */
    private void claimChapterFromInbox(String chapterId) {
        long now = net.minecraft.util.Util.getMillis();
        if (!book.bookState.pendingClaims.mark(PendingClaims.chapterKey(chapterId), now)) {
            // The banner is already in flight: a second press would be a second packet for one decision.
            return;
        }
        for (ClientQuestCache.Entry entry : book.questsIn(chapterId)) {
            book.bookState.pendingClaims.mark(PendingClaims.questKey(entry.id()), now);
            for (int index = 0; index < entry.rewards().size(); index++) {
                markPending(entry.id(), index, now);
            }
        }
        refreshRewardCard();
        ArmatureNetwork.sendToServer(new ClaimChapterPayload(chapterId, rewardState.filter()));
        Constants.LOG.debug("asked the server for every reward in chapter {}", chapterId);
    }

    /**
     * The one row of a quest whose definition holds exactly one reward.
     *
     * <p>Its reward is the row's own, and the count is drawn only when the row is a number of things:
     * for an item that is "x16", and for the count-shaped types it is already inside the sentence
     * ("5 XP").
     */
    private RewardInboxLayout.Row singleRow(String chapterId, ClientQuestCache.Entry entry,
                                            String label, RewardInboxLayout.Status status) {
        int index = 0;
        ClientQuestCache.RewardEntry reward = entry.rewards().get(index);
        int count = reward.hasItem() ? reward.count() : 0;
        return RewardInboxLayout.Row.single(chapterId, entry.id(), label, index, count, status);
    }

    /** One reward's menu row, with the status this player's copy of it is in. */
    private RewardInboxLayout.Row rewardInboxRow(String chapterId, ClientQuestCache.Entry entry,
                                                 int index, RewardInboxLayout.Status status) {
        ClientQuestCache.RewardEntry reward = entry.rewards().get(index);
        // A missing item is named for what it is, not by the id it was written as. The id is the
        // author's to act on and the row is the player's to read; the picker is where the id belongs.
        boolean missingItem = !reward.hasItem() && !reward.itemId().isEmpty();
        String label = missingItem
                ? Component.translatable("tenet.screen.missing_item").getString()
                : BookQuestEditor.rowText("rewards", reward);
        int count = reward.hasItem() ? reward.count() : 0;
        return RewardInboxLayout.Row.reward(chapterId, entry.id(), index, label, count, status);
    }

    /**
     * Where one reward stands for this player, including a press still in flight.
     *
     * <p>Asked with the entry already in hand, because the claim menu asks it once per reward of every
     * visible quest on every frame — see {@code ClientQuestCache.rewardClaimedBy}'s entry overload for
     * what the id lookup would cost at that rate.
     */
    private RewardInboxLayout.Status statusOf(UUID self, ClientQuestCache.Entry entry, int index) {
        if (book.bookState.pendingClaims.isMarked(PendingClaims.rewardKey(entry.id(), index))
                || book.bookState.pendingClaims.isMarked(PendingClaims.questKey(entry.id()))
                || book.bookState.pendingClaims.isMarked(PendingClaims.chapterKey(entry.chapterId()))) {
            // A whole-quest or whole-chapter press marks every reward inside it, so its children read
            // collected with it: a banner that read collected above rows still offering their own buttons
            // would be the card disagreeing with itself about one press.
            return RewardInboxLayout.Status.PENDING;
        }
        if (self != null && ClientQuestCache.rewardClaimedBy(self, entry, index)) {
            return RewardInboxLayout.Status.CLAIMED;
        }
        if (!ClientQuestCache.canClaimReward(self, entry, index)) {
            // The same predicate the badge counts with: a row is gated exactly when the cache says
            // this player could not take it, so the banner's count and its rows cannot disagree.
            return RewardInboxLayout.Status.LOCKED;
        }
        return RewardInboxLayout.Status.READY;
    }

    /**
     * What a row's action button says: its action while it has one, its state once it does not.
     *
     * <p>The action is named by the row's kind rather than by whether it folds, which is what the two
     * kinds of accordion need: a banner and a quest header both fold, and a button that said "Claim
     * Quest" on a chapter would be the card naming the wrong rung of its own ladder. Containers carry
     * their count — "Claim Chapter (3)" — so a bulk press states its scope and a single press does
     * not pretend to be one.
     */
    private Component buttonLabel(RewardInboxLayout.Row row) {
        if (!row.pressable()) {
            return Component.translatable(row.status() == RewardInboxLayout.Status.LOCKED
                    ? "tenet.viewer.locked" : "tenet.viewer.claimed");
        }
        return switch (row.kind()) {
            // On the Choices tab a bulk press opens pickers rather than handing anything over, and the
            // footer already says "Choose all": the banner's button says the same verb.
            case CHAPTER -> Component.translatable(choosing()
                    ? "tenet.screen.rewards.choose_chapter" : "tenet.screen.rewards.claim_chapter", row.count());
            case QUEST -> Component.translatable(choosing()
                    ? "tenet.screen.rewards.choose_quest" : "tenet.screen.rewards.claim_quest", row.count());
            case SINGLE, REWARD -> Component.translatable(
                    isChoiceReward(row.questId(), row.rewardIndex())
                            ? "tenet.screen.rewards.choose" : "tenet.screen.rewards.claim_row");
        };
    }

    /** Whether the reward at this position is a choice, so its button says a picker opens. */
    private static boolean isChoiceReward(String questId, int index) {
        ClientQuestCache.Entry entry = ClientQuestCache.entry(questId);
        return entry != null && index >= 0 && index < entry.rewards().size()
                && isChoiceType(entry.rewards().get(index).type());
    }

    /**
     * Asks the server for one reward, and marks its row collected until the answer comes.
     *
     * <p>The optimistic half lives here and only here, and it is safe because an answer always comes:
     * every claim handler sends a progress sync whether or not it paid, so a refusal reaches the client
     * and the mark goes by agreement. The worst a refusal produces is a row that reads collected for one
     * round trip and then goes back — and the alternative, a row that shows nothing until the packet
     * returns, reads as a press that did not register.
     *
     * <p>A row already in flight is not asked for twice, and the guard is the mark itself rather than
     * {@link #markPending}'s answer: a <b>choice</b> is never marked — the press opens a card instead of
     * collecting — so a guard that read "the mark was not new" would swallow the press that asks for the
     * choices. See {@link #markPending} for why a choice is not marked.
     */
    void claimReward(String questId, int rewardIndex) {
        if (book.bookState.pendingClaims.isMarked(PendingClaims.rewardKey(questId, rewardIndex))) {
            return;
        }
        markPending(questId, rewardIndex, net.minecraft.util.Util.getMillis());
        refreshRewardCard();
        ArmatureNetwork.sendToServer(new ClaimRewardEntryPayload(questId, rewardIndex));
        Constants.LOG.debug("asked the server for reward {} of {}", rewardIndex, questId);
    }

    /**
     * Marks one reward's row collected until the server answers, and answers whether it was new.
     *
     * <p>Except a choice: the press does not collect it — it opens the card that asks the player which
     * entry they want — so a choice row that read collected while its card was open would be the card
     * promising a payout that has not happened. The pick marks it, through the sync that follows the
     * answer.
     *
     * <h2>Why a team reward is marked too, which the plan said not to do</h2>
     *
     * <p>The plan excluded them, on the ground that a teammate's claim moves the very row this player
     * guessed. Checked against the code, that concern is backwards: a {@code team} reward is one payout
     * for the whole party, so a teammate claiming it <b>confirms</b> the guess rather than contradicting
     * it — {@code ClientQuestCache.rewardClaimedBy} reads the team's claims for a team reward, and that
     * is what this mark is resolved against. Excluding them would have cost the instant half of the press
     * for one of the commonest reward kinds in a party pack and bought nothing: the case that is
     * genuinely unconfirmable by anybody else is the {@code team: false} one.
     */
    private boolean markPending(String questId, int rewardIndex, long nowMillis) {
        ClientQuestCache.Entry entry = ClientQuestCache.entry(questId);
        if (entry == null || rewardIndex < 0 || rewardIndex >= entry.rewards().size()) {
            return false;
        }
        if (isChoiceType(entry.rewards().get(rewardIndex).type())) {
            return false;
        }
        return book.bookState.pendingClaims.mark(PendingClaims.rewardKey(questId, rewardIndex), nowMillis);
    }

    /**
     * The header's press: the whole quest's rewards, marked collected until the server answers.
     *
     * <p>Every row of the quest goes pending with the header, because that is what the press asked for:
     * a header reading collected while its rows still offered their own buttons would be the card
     * disagreeing with itself about one press.
     */
    private void claimQuestFromInbox(String questId) {
        if (!markQuest(questId, net.minecraft.util.Util.getMillis())) {
            return;
        }
        refreshRewardCard();
        book.bookAssets.claim(questId);
    }

    /**
     * Marks a whole quest: its own key, and every reward inside it that a press would take.
     *
     * <p>Answers whether the quest's own key was new, so the caller can send once for one decision.
     * Shared with the reader card's Claim button, which is the same press asked from the other side of
     * the book — the two used to be one optimistic path and one not, which is how a row could read
     * collected in the menu while the card behind it still offered to claim it.
     */
    boolean markQuest(String questId, long nowMillis) {
        ClientQuestCache.Entry entry = ClientQuestCache.entry(questId);
        if (!book.bookState.pendingClaims.mark(PendingClaims.questKey(questId), nowMillis)) {
            return false;
        }
        if (entry != null) {
            for (int index = 0; index < entry.rewards().size(); index++) {
                markPending(questId, index, nowMillis);
            }
        }
        return true;
    }

    /**
     * Forgets the marks the server has answered, and the ones it never will.
     *
     * <h2>What this replaces, and why the replacement is the whole point</h2>
     *
     * <p>Every mark used to go at once whenever the progress revision moved — and that is <b>any</b>
     * progress message: a teammate's claim, another quest's task tick, or the once-a-second condition
     * refresh. So a row the player had just pressed went back to ready mid-flight and collected again
     * when its own answer landed. The player saw a claim that flickered, and the flicker was this client
     * forgetting a press it had not yet heard about.
     *
     * <p>Now each mark goes on its own evidence: the server's answer agrees with it, the thing it named
     * left the tree, or it has been believed past the backstop — and the last of those is <b>said</b>,
     * because a row that quietly reverts three seconds later is indistinguishable from a bug.
     *
     * <p>Run every tick rather than only while the rewards card is open: a press outlives the card it was
     * made from, and the task rows it marks are on the reader's card.
     */
    void reconcilePendingClaims(long nowMillis) {
        UUID self = selfId();
        // Built on first need: a chapter's canonical answer walks the pack, and most ticks have no
        // chapter mark to ask about.
        Map<String, Integer> chaptersOutstanding = null;
        for (String key : book.bookState.pendingClaims.keys()) {
            PendingClaims.Subject subject = PendingClaims.subjectOf(key);
            if (subject == null) {
                // A key this build cannot read: forget it rather than believe it forever.
                book.bookState.pendingClaims.resolve(key);
                continue;
            }
            ClientQuestCache.Entry entry = ClientQuestCache.entry(subject.owner());
            // A switch *expression* rather than a statement with a default, deliberately: this is
            // exhaustive over the kinds, so a new one cannot be added without the compiler saying so --
            // the rule every switch over an op in this codebase already keeps.
            boolean done = switch (subject.kind()) {
                case TASK -> entry == null
                        || ClientQuestCache.taskDone(subject.owner(), subject.index());
                case REWARD -> entry == null
                        || (self != null
                            && ClientQuestCache.rewardClaimedBy(self, entry, subject.index()));
                case QUEST -> entry == null
                        || ClientQuestCache.outstandingRewards(self, subject.owner()) == 0;
                case CHAPTER -> {
                    if (chaptersOutstanding == null) {
                        chaptersOutstanding = ClientQuestCache.claimableByChapter(self);
                    }
                    yield chaptersOutstanding.getOrDefault(subject.owner(), 0) == 0;
                }
            };
            if (done) {
                book.bookState.pendingClaims.resolve(key);
            }
        }
        for (String key : book.bookState.pendingClaims.expired(nowMillis)) {
            report("The server did not answer " + markDescription(key) + ", so it was put back");
        }
    }

    /** What a mark was about, in words a player reads — the backstop's sentence needs a subject. */
    private static String markDescription(String key) {
        PendingClaims.Subject subject = PendingClaims.subjectOf(key);
        if (subject == null) {
            return "a press";
        }
        return switch (subject.kind()) {
            case TASK -> "the hand-in of " + subject.owner();
            case REWARD -> "a reward of " + subject.owner();
            case QUEST -> "the rewards of " + subject.owner();
            case CHAPTER -> "the rewards of chapter " + subject.owner();
        };
    }

    /**
     * Folds or unfolds a chapter's quests, or a quest's rewards, from a press on the row's body.
     *
     * <p>Asked of the layout rather than of a rectangle written here — the same contract as every other
     * press in this screen, so what is hit-tested is what was drawn. Only the two accordion kinds are
     * tested: a single row has nothing to fold, and a reward row has nothing under it.
     *
     * <p>The two folds are separate sets, so opening a chapter does not open every quest in it. That is
     * the difference between a menu a player can read and one that unfolds to forty rows the first time
     * it is touched.
     */
    boolean toggleRewardRow(double mouseX, double mouseY) {
        if (rewardLayout == null || !book.bookState.rewardView.viewport().containsScreen(mouseX, mouseY)) {
            // Outside the list's own rectangle: a row scrolled under the card's header or footer is
            // clipped away, and a press there is the card's, not a hidden row's.
            return false;
        }
        for (RewardInboxLayout.Row row : rewardRows) {
            if (!row.expands()) {
                continue;
            }
            Slot slot = rewardLayout.slot(row.key());
            if (slot == null) {
                continue;
            }
            Slot onScreen = InspectLayout.onScreen(book.bookState.rewardView.viewport(), RewardInboxLayout.body(slot));
            if (!onScreen.contains(mouseX, mouseY)) {
                continue;
            }
            Set<String> open = row.isChapter() ? expandedChapters : expandedQuests;
            chapterFoldTouched |= row.isChapter();
            String id = row.isChapter() ? row.chapterId() : row.questId();
            if (!open.remove(id)) {
                open.add(id);
            }
            refreshRewardCard();
            return true;
        }
        return false;
    }

    /**
     * Whether a row's body press collects what its strip button would.
     *
     * <p>One predicate for the drawing and the press, so the two cannot disagree about what is on
     * screen: {@link #buildRewardWidgets} leaves a button live exactly when this answers true, and
     * this is what the press asks before running that button's own {@link #rowPress}. A row that is
     * already collected, gated, or still in flight answers false, and so do the accordion kinds —
     * a chapter's banner and a quest's header fold rather than pay.
     */
    static boolean claimsOnRowPress(RewardInboxLayout.Row row) {
        return (row.kind() == RewardInboxLayout.Kind.SINGLE || row.kind() == RewardInboxLayout.Kind.REWARD)
                && row.pressable();
    }

    /**
     * Claims a reward from a press on its row's body, exactly as its Claim button would.
     *
     * <p>The checkmark row's rule, one menu over: the row is the button. The press runs the same
     * {@link #rowPress} the strip's widget was given, so a double press is the same no-op the
     * button has (see {@link #claimReward}) and a choice row opens its picker rather than paying
     * out. Asked of the layout rather than of a rectangle written here, and of the body's half of
     * it — the action column is the widget's, which the widget pass already offered the press, so
     * a press that reaches here is a press on the row and not on its button.
     */
    boolean pressRewardRow(double mouseX, double mouseY) {
        if (rewardLayout == null || !book.bookState.rewardView.viewport().containsScreen(mouseX, mouseY)) {
            // Outside the list's own rectangle: the same culling as the fold, so a row scrolled
            // under the card's header or footer is not claimable from where it is not drawn.
            return false;
        }
        for (RewardInboxLayout.Row row : rewardRows) {
            if (!claimsOnRowPress(row)) {
                continue;
            }
            Slot slot = rewardLayout.slot(row.key());
            if (slot == null) {
                continue;
            }
            Slot onScreen = InspectLayout.onScreen(book.bookState.rewardView.viewport(), RewardInboxLayout.body(slot));
            if (!onScreen.contains(mouseX, mouseY)) {
                continue;
            }
            rowPress(row).run();
            return true;
        }
        return false;
    }

    /**
     * The claim menu: the chapters, their quests, their rewards, and the controls that collect them.
     *
     * <p>The count on the second line is the view's own count rather than the book's, so the sentence
     * agrees with the list under it: "12 ready to collect" beside a list of ready rows, or "7 already
     * collected" beside the history.
     */
    void drawRewardsOverlay(GuiRenderer r, int mouseX, int mouseY) {
        if (rewardCard == null) {
            return;
        }
        int left = rewardCard.x();
        int top = rewardCard.y();
        int w = rewardCard.width();
        int h = rewardCard.height();

        // The band and its rule come off the body's own top edge rather than from numbers written here.
        // They were 45 and 46 while the body began at REWARDS_BODY_TOP, which is three answers to one
        // question -- and the wrong three: the state toggles were drawn straddling the rule.
        BookFrame.drawCard(r, left, top, w, h, REWARDS_BODY_TOP, true);

        int listed = rewardListed;
        int textX = left + 14;
        if (!isRewardChipsNarrow()) {
            r.text(Component.translatable("tenet.screen.rewards.title").getString(), textX, top + 12,
                    ArmatureTheme.title());
            r.text(subtitle(listed).getString(), textX, top + 26, ArmatureTheme.faint());
        }

        if (rewardLayout == null) {
            return;
        }
        Viewport body = rewardBody();
        book.bookState.rewardView.apply(rewardLayout, body.viewWidth());
        if (rewardRows.isEmpty()) {
            // Nothing to show is a sentence rather than an empty card, and it says what would put
            // something here. Which sentence depends on the view, because "finish a quest" is the wrong
            // advice to give somebody who came to answer a choice.
            //
            // Drawn inside the body's own clip, and that is a fix rather than a flourish: the card's
            // floor is MIN_MODAL_HEIGHT, which is only two pixels more than this band plus the footer's
            // chrome, so a hint drawn at the body's origin without a clip lands in the footer's gap. It
            // looked deliberate at one line and would have collided with the buttons at two.
            try (GuiRenderer.Scoped clip = r.clip(body.originX(), body.originY(),
                    body.originX() + body.viewWidth(), body.originY() + body.viewHeight())) {
                // Ten pixels of air under the rule, the gap every card's body opens with: at one pixel
                // the sentence sat on the line like a caption that had slipped.
                r.text(emptyHint().getString(), body.originX() + 4, body.originY() + 10,
                        ArmatureTheme.faint());
            }
        }
        else {
            drawRewardInboxRows(r, body, mouseX, mouseY);
        }
        QuestBookScreen.drawBar(r, book.bookState.rewardView.bar(), mouseX, mouseY);
    }

    /** What an empty view says, in the words the active view is using. */
    private Component emptyHint() {
        return Component.translatable(rewardState == RewardInboxLayout.State.CHOICES
                ? "tenet.screen.rewards.choices_none_hint"
                : "tenet.screen.rewards.none_hint");
    }

    /**
     * The card's second line, which says what the view is showing and how much of it there is.
     *
     * <p>Both halves follow the view, and the first version of this did not: the empty line said
     * "Nothing is waiting" in every state, so the choices view announced that nothing was waiting while
     * its own hint asked the player to finish a quest.
     */
    private Component subtitle(int listed) {
        if (listed == 0) {
            return Component.translatable(rewardState == RewardInboxLayout.State.CHOICES
                    ? "tenet.screen.rewards.choices_none" : "tenet.screen.rewards.none");
        }
        return Component.translatable(rewardState == RewardInboxLayout.State.CHOICES
                ? "tenet.screen.rewards.choices_waiting" : "tenet.screen.rewards.waiting", listed);
    }

    /**
     * The menu's rows: chapter banners, the quests under an expanded one, and their rewards.
     *
     * <p>Drawn rather than hosted as widgets, the division the roster's rows already use: the action
     * buttons are the widgets, and everything the player reads is painted here — inside the body's clip,
     * so a scrolled row is cut at the card's edge rather than drawn over its header.
     *
     * <p>A quest's own reward cells are taken from the reward rows that follow it, which is what makes
     * the details column show exactly what the fold would reveal: the rows are already built and already
     * filtered by the active state, so the drawing does not ask the cache a second time and cannot
     * disagree with the list it is drawing.
     */
    private void drawRewardInboxRows(GuiRenderer r, Viewport body, int mouseX, int mouseY) {
        long now = Util.getMillis();
        Viewport view = book.bookState.rewardView.viewport();
        try (GuiRenderer.Scoped clip = r.clip(body.originX(), body.originY(),
                body.originX() + body.viewWidth(), body.originY() + body.viewHeight())) {
            for (int i = 0; i < rewardRows.size(); i++) {
                RewardInboxLayout.Row row = rewardRows.get(i);
                Slot slot = rewardLayout.slot(row.key());
                if (slot == null) {
                    continue;
                }
                Slot onScreen = InspectLayout.onScreen(view, slot);
                if (onScreen.bottom() <= body.originY()
                        || onScreen.y() >= body.originY() + body.viewHeight()) {
                    continue;
                }
                book.bookState.rowHover.update(row.key(), now);
                float hover = book.bookState.rowHover.amount(row.key(), now);
                switch (row.kind()) {
                    case CHAPTER -> drawRewardChapterRow(r, row, onScreen, hover, mouseX, mouseY);
                    case QUEST -> drawRewardQuestRow(r, row, onScreen, hover, mouseX, mouseY);
                    case SINGLE -> drawRewardInboxSingle(r, row, onScreen, hover, mouseX, mouseY);
                    case REWARD -> drawRewardInboxReward(r, row, onScreen, hover, mouseX, mouseY);
                }
            }
        }
    }

    /**
     * A chapter's banner: its fold marker, its icon, its title with the ready count inline, and Claim
     * Chapter.
     *
     * <p>The title is the chapter's own and nothing else — no group, no ordinal. Those read
     * "First Light › 1 · Th..." on any card that mattered, so they live in the hover now. The badge
     * rides inline after the title ("The Engine · 1 ready") rather than right-aligned in the rewards
     * cell: a count floating 150 pixels from what it counts reads as a second fact, and the rewards
     * cell stands empty on this kind. When even the compact count does not fit, the title alone is
     * drawn — the hover still carries the number, and a broken number reads worse than none.
     *
     * <p>No fill behind the banner, no rule under it, no hover wash on any row in this menu:
     * every background that ever lived here ended mid-row — the fill at the action column, the wash
     * at the words, the rule at the slot — and beside a Claim button any such edge reads as a broken
     * box. A banner is its height, its gap and its inline count; hover feedback lives on the buttons
     * and in the tooltips, which have no edges to get wrong.
     */
    private void drawRewardChapterRow(GuiRenderer r, RewardInboxLayout.Row row, Slot slot, float hover,
                                      int mouseX, int mouseY) {
        boolean expanded = expandedChapters.contains(row.chapterId());
        String marker = expanded ? "\u25bc" : "\u203a";
        int textY = slot.y() + (slot.height() - 8) / 2;
        int iconY = slot.y() + (slot.height() - QuestBookScreen.ROW_ICON) / 2;

        Slot context = RewardInboxLayout.column(slot, RewardInboxLayout.Column.CONTEXT);

        String fullBadge = Component.translatable(choosing()
                        ? "tenet.screen.rewards.chapter_choices" : "tenet.screen.rewards.chapter_ready", row.count())
                .getString();
        String compactBadge = String.valueOf(row.count());

        int markerWidth = markerRoom(r);
        boolean hasIcon = !chapterIcon(row.chapterId()).isEmpty()
                || (!chapterTextureIcon(row.chapterId()).isEmpty()
                        && net.minecraft.resources.Identifier
                                .tryParse(chapterTextureIcon(row.chapterId())) != null)
                || (!chapterSpriteIcon(row.chapterId()).isEmpty()
                        && net.minecraft.resources.Identifier
                                .tryParse(chapterSpriteIcon(row.chapterId())) != null);
        int iconRoom = hasIcon ? QuestBookScreen.ROW_ICON + 5 : 0;
        int avail = Math.max(0, context.width() - markerWidth - iconRoom);

        String inlineFull = "\u00b7 " + fullBadge;
        String inlineCompact = "\u00b7 " + compactBadge;
        String title = row.label();
        String badgeDraw = inlineFull;
        int badgeW = r.textWidth(inlineFull);
        int titleAvail = Math.max(0, avail - badgeW - RewardInboxLayout.COLUMN_GAP);
        String shownTitle = Measure.truncate(title, titleAvail, book.textMeasure(r));
        if (shownTitle.isEmpty() && !title.isEmpty()) {
            badgeDraw = inlineCompact;
            badgeW = r.textWidth(inlineCompact);
            titleAvail = Math.max(0, avail - badgeW - RewardInboxLayout.COLUMN_GAP);
            shownTitle = Measure.truncate(title, titleAvail, book.textMeasure(r));
            if (shownTitle.isEmpty()) {
                badgeDraw = "";
                badgeW = 0;
                shownTitle = Measure.truncate(title, avail, book.textMeasure(r));
            }
        }

        int x = context.x();
        r.text(marker, x, textY, ArmatureTheme.faint());
        x += markerWidth;
        if (hasIcon) {
            drawRewardIcon(r, chapterIcon(row.chapterId()), chapterTextureIcon(row.chapterId()),
                    chapterSpriteIcon(row.chapterId()), x, iconY, QuestBookScreen.ROW_ICON);
            x += iconRoom;
        }
        r.text(shownTitle, x, textY, ArmatureTheme.title());
        x += r.textWidth(shownTitle);
        if (!badgeDraw.isEmpty()) {
            // The "you may take this" colour rather than inProgress(), which this theme also uses as its
            // danger ink: a badge that counts something the player *can* have should not borrow the colour
            // the UI reserves for caution.
            r.text(badgeDraw, x + RewardInboxLayout.COLUMN_GAP, textY, ArmatureTheme.available());
        }

        if (slot.contains(mouseX, mouseY)) {
            List<String> lines = new ArrayList<>();
            String group = groupTitleOf(row.chapterId());
            lines.add(group.isEmpty() ? row.label() : group + " \u203a " + row.label());
            lines.add(Component.translatable(expanded
                    ? "tenet.screen.rewards.collapse_hint"
                    : "tenet.screen.rewards.expand_hint").getString());
            lines.add(countLine(row));
            book.bookState.rowTooltips.add(new QuestBookScreen.RowTooltip(slot, lines));
        }
    }

    /**
     * A container row's hover: how much is inside it.
     *
     * <p>The count on a banner or a quest header is the number of rewards the fold would reveal, and the
     * hover is where it is stated in words — which is also why a badge that does not fit its cell can be
     * dropped without losing the number. A string rather than a {@code Component}, because a row's hover
     * is a list of lines the screen draws itself — see {@link RowTooltip}.
     */
    private String countLine(RewardInboxLayout.Row row) {
        return Component.translatable("tenet.screen.rewards.header_count", row.count()).getString();
    }

    /**
     * The room a row's fold marker occupies, reserved by every row whether or not it draws one.
     *
     * <p>One expression rather than three, because the whole point of reserving it is that the icons
     * after it land on one axis — and two rows computing "how wide is a marker" separately is how that
     * stops being true the day a marker changes.
     */
    private int markerRoom(GuiRenderer r) {
        return r.textWidth("\u25bc") + MARKER_GAP;
    }

    /**
     * A quest's row: its fold marker, icon and title, its rewards, and its action.
     *
     * <p>The details column is filled whether or not the quest is open — it is the same walk the fold
     * lists, so a player reads what a quest gives without unfolding it, and the icons above cannot
     * disagree with the rows below.
     */
    private void drawRewardQuestRow(GuiRenderer r, RewardInboxLayout.Row row, Slot slot, float hover,
                                    int mouseX, int mouseY) {
        ClientQuestCache.Entry entry = ClientQuestCache.entry(row.questId());
        if (entry == null) {
            return;
        }
        boolean expanded = expandedQuests.contains(row.questId());
        String marker = expanded ? "\u25bc" : "\u203a";
        int textY = slot.y() + (slot.height() - 8) / 2;
        int iconY = slot.y() + (slot.height() - QuestBookScreen.ROW_ICON) / 2;

        Slot context = RewardInboxLayout.column(slot, RewardInboxLayout.Column.CONTEXT);
        Slot rewards = RewardInboxLayout.column(slot, RewardInboxLayout.Column.REWARDS);

        int x = context.x();
        int markerWidth = markerRoom(r);
        boolean hasIcon = !entry.icon().isEmpty() || !entry.textureIcon().isEmpty()
                || !entry.spriteIcon().isEmpty();
        int iconRoom = hasIcon ? QuestBookScreen.ROW_ICON + 5 : 0;
        int titleRoom = Math.max(0,
                rewards.x() - RewardInboxLayout.COLUMN_GAP - (x + markerWidth + iconRoom));
        String title = Measure.truncate(row.label(), titleRoom, book.textMeasure(r));

        r.text(marker, x, textY, ArmatureTheme.faint());
        x += markerWidth;
        if (hasIcon) {
            drawRewardIcon(r, entry.icon(), entry.textureIcon(), entry.spriteIcon(), x, iconY,
                    QuestBookScreen.ROW_ICON);
            x += iconRoom;
        }
        r.text(title, x, textY, ArmatureTheme.title());
        drawRewardCells(r, rewards, questRewardCells.getOrDefault(row.questId(), List.of()), textY,
                iconY);

        if (slot.contains(mouseX, mouseY)) {
            book.bookState.rowTooltips.add(new QuestBookScreen.RowTooltip(slot, List.of(
                    Component.translatable(expanded
                            ? "tenet.screen.rewards.collapse_hint"
                            : "tenet.screen.rewards.expand_hint").getString(),
                    countLine(row))));
        }
    }

    /**
     * The details column: one cell per reward, packed from the column's left edge, with a count.
     *
     * <p>A "+N" carries whatever did not fit rather than the column overflowing into the action beside
     * it — a quest with six rewards has six cells' worth of information and only three cells' worth of
     * room, and the fold is where the rest is read.
     */
    private void drawRewardCells(GuiRenderer r, Slot column, List<RewardInboxLayout.Row> cells,
                                 int textY, int iconY) {
        int x = column.x();
        int drawn = 0;
        for (RewardInboxLayout.Row cell : cells) {
            ClientQuestCache.Entry entry = ClientQuestCache.entry(cell.questId());
            if (entry == null || cell.rewardIndex() < 0
                    || cell.rewardIndex() >= entry.rewards().size()) {
                continue;
            }
            ClientQuestCache.RewardEntry reward = entry.rewards().get(cell.rewardIndex());
            String label = rewardCellText(cell, reward);
            int width = QuestBookScreen.ROW_ICON + (label.isEmpty() ? 0 : 5 + r.textWidth(label));
            boolean last = drawn == cells.size() - 1;
            if (drawn > 0 && !last && x + width + REWARD_CELL_GAP > column.right()) {
                break;
            }
            boolean missingItem = !reward.hasItem() && !reward.itemId().isEmpty();
            if (missingItem) {
                QuestBookScreen.drawItemPlaceholder(r, x, iconY, QuestBookScreen.ROW_ICON);
            }
            else {
                r.icon(reward.shown(), x, iconY, QuestBookScreen.ROW_ICON);
            }
            if (!label.isEmpty()) {
                r.text(label, x + QuestBookScreen.ROW_ICON + 5, textY, ArmatureTheme.faint());
            }
            x += width + REWARD_CELL_GAP;
            drawn++;
        }

        int left = cells.size() - drawn;
        if (left > 0) {
            // The overflow measured from the string it will draw, not from a three-character stand-in:
            // "+7" and "+107" are different widths, and the stand-in was wrong for anything over two
            // digits -- the one case where the count is most likely to overflow in the first place.
            String more = "+" + left;
            if (x + r.textWidth(more) <= column.right()) {
                r.text(more, x, textY, ArmatureTheme.faint());
            }
        }
    }

    /**
     * One reward cell's text: the count for an item, the sentence for anything else, and the word for a
     * reward that is waiting on the player's decision.
     *
     * <p>A choice has no count to show and its own sentence is its label, so its cell would be an icon
     * with nothing beside it — which reads as a reward whose details failed to load. What goes there is
     * what the row is asking for, which is also how a player finds the choices in a column of items.
     */
    private String rewardCellText(RewardInboxLayout.Row row, ClientQuestCache.RewardEntry reward) {
        if (reward.hasItem()) {
            return row.count() > 1 ? "x" + row.count() : "";
        }
        if (isChoiceType(reward.type())) {
            return Component.translatable("tenet.screen.rewards.choose").getString();
        }
        return BookQuestEditor.rowText("rewards", reward);
    }

    /** A chapter's icon, from the explicit chapter list, or empty for a chapter the server did not send. */
    private static ItemStack chapterIcon(String chapterId) {
        for (ClientQuestCache.ChapterEntry chapter : ClientQuestCache.chapters()) {
            if (chapter.id().equals(chapterId)) {
                return chapter.icon();
            }
        }
        return ItemStack.EMPTY;
    }

    /** A chapter's texture path, or "" when it wears an item, a sprite, or nothing. */
    private static String chapterTextureIcon(String chapterId) {
        for (ClientQuestCache.ChapterEntry chapter : ClientQuestCache.chapters()) {
            if (chapter.id().equals(chapterId)) {
                return chapter.textureIcon();
            }
        }
        return "";
    }

    /** A chapter's atlas region, or "" when it wears an item, a texture, or nothing. */
    private static String chapterSpriteIcon(String chapterId) {
        for (ClientQuestCache.ChapterEntry chapter : ClientQuestCache.chapters()) {
            if (chapter.id().equals(chapterId)) {
                return chapter.spriteIcon();
            }
        }
        return "";
    }

    /**
     * One reward-inbox icon in any arm: the stack, the blit, or the atlas region.
     *
     * <p>The same precedence the quest header uses — item, then texture, then sprite — so a quest
     * or chapter reads the same in the inbox as on its card. An entity arm never reaches here as
     * such: the cache resolves it to its egg stack (or to empty with the id kept), so it draws
     * through the item arm like everywhere else.
     *
     * @return whether anything was drawn, which is what decides the title's indent
     */
    private static boolean drawRewardIcon(GuiRenderer r, ItemStack stack, String texture, String sprite,
                                          int x, int y, int size) {
        if (!stack.isEmpty()) {
            return r.icon(stack, x, y, size);
        }
        if (texture != null && !texture.isEmpty()
                && net.minecraft.resources.Identifier.tryParse(texture) != null) {
            r.texture(net.minecraft.resources.Identifier.parse(texture), x, y, size, size);
            return true;
        }
        if (sprite != null && !sprite.isEmpty()
                && net.minecraft.resources.Identifier.tryParse(sprite) != null) {
            r.sprite(net.minecraft.resources.Identifier.parse(sprite), x, y, size, size, 0xFFFFFFFF);
            return true;
        }
        return false;
    }

    /**
     * A quest whose definition holds one reward: one icon, one line, one Claim.
     *
     * <p>It used to be two icons with a moat between them — the quest's icon and title on the left,
     * the payout's icon and bare count stranded in the details column — so the row read as two facts
     * that had nothing to do with each other, and "x8" of nothing. Now the context carries both halves
     * in one line ("Make a Table · x8 Stick") under the payout's own icon: the quest names the row for
     * finding, the payout names it for collecting, and there is one icon because there is one thing to
     * take. The details column stands empty on this kind, like the rewards cell on a banner.
     *
     * <p>The hover carries both halves in full — the quest it belongs to, and the reward's tooltip —
     * because a single line is where an abstract title ("Botanical Wonders") would otherwise say nothing
     * about what is being collected.
     */
    private void drawRewardInboxSingle(GuiRenderer r, RewardInboxLayout.Row row, Slot slot, float hover,
                                       int mouseX, int mouseY) {
        ClientQuestCache.Entry entry = ClientQuestCache.entry(row.questId());
        if (entry == null || row.rewardIndex() < 0 || row.rewardIndex() >= entry.rewards().size()) {
            return;
        }
        ClientQuestCache.RewardEntry reward = entry.rewards().get(row.rewardIndex());
        int textY = slot.y() + (slot.height() - 8) / 2;
        int iconY = slot.y() + (slot.height() - QuestBookScreen.ROW_ICON) / 2;

        Slot context = RewardInboxLayout.column(slot, RewardInboxLayout.Column.CONTEXT);

        // The payout, in words: the item's name with its count, the sentence for anything else, and
        // the word for a reward waiting on a decision.
        String payout;
        if (isChoiceType(reward.type())) {
            payout = Component.translatable("tenet.screen.rewards.choose").getString();
        }
        else if (reward.hasItem()) {
            payout = RewardText.withCount(reward.item().getHoverName().getString(), row.count());
        }
        else {
            payout = BookQuestEditor.rowText("rewards", reward);
        }
        boolean missingItem = !reward.hasItem() && !reward.itemId().isEmpty();
        boolean shut = !row.pressable();

        // One line. It never folds, but it keeps the marker's room all the same: without it this row's
        // icon stood in the arrow column of the quest rows around it, and the list's icons zigzagged.
        // The payout is what the press takes, so when the two halves do not fit it wins and the quest
        // yields — a row reading only its payout still collects correctly, while a row reading only
        // its quest collects blind.
        int iconX = context.x() + markerRoom(r);
        int textX = iconX + QuestBookScreen.ROW_ICON + 5;
        int avail = Math.max(0, context.right() - textX);
        String sep = " \u00b7 ";
        String shownTitle = Measure.truncate(row.label(),
                Math.max(0, avail - r.textWidth(sep + payout)), book.textMeasure(r));
        boolean hasTitle = !shownTitle.isEmpty();
        String shownPayout = hasTitle ? payout : Measure.truncate(payout, avail, book.textMeasure(r));

        if (missingItem) {
            QuestBookScreen.drawItemPlaceholder(r, iconX, iconY, QuestBookScreen.ROW_ICON);
        }
        else {
            r.icon(reward.shown(), iconX, iconY, QuestBookScreen.ROW_ICON);
        }
        int x = textX;
        if (hasTitle) {
            r.text(shownTitle, x, textY, ArmatureTheme.title());
            x += r.textWidth(shownTitle);
            r.text(sep, x, textY, ArmatureTheme.faint());
            x += r.textWidth(sep);
        }
        r.text(shownPayout, x, textY,
                missingItem || shut ? ArmatureTheme.blocked() : ArmatureTheme.body());

        if (slot.contains(mouseX, mouseY)) {
            List<String> lines = new ArrayList<>();
            // The quest first: the reward's own lines say what is being collected, and this says which
            // quest is giving it.
            lines.add(entry.titleText());
            lines.addAll(rewardTooltipLines(row.questId(), row.rewardIndex()));
            book.bookState.rowTooltips.add(new QuestBookScreen.RowTooltip(slot, lines));
        }
    }

    /**
     * One reward under its quest: its icon, name, count, and its own Claim.
     *
     * <h2>Why this row has no details cell</h2>
     *
     * <p>It had one, and the icon and the sentence appeared twice on the row — once in the context cell
     * and again in the details cell — because a reward row's context <i>is</i> its reward. That reads as
     * a duplicate rather than as two facts, so the details cell is gone on this kind and the count moved
     * beside the name, where it was before the menu had columns at all. A quest's row keeps its details
     * cell: there the context is the quest and the cell is what it gives, so the two are different
     * things and the reward icons line up down the column.
     *
     * <p>The viewer's reward row, one list over — the icon, the sentence and the count are the same
     * facts drawn the same way, so a reward reads the same wherever it appears.
     */
    private void drawRewardInboxReward(GuiRenderer r, RewardInboxLayout.Row row, Slot slot, float hover,
                                       int mouseX, int mouseY) {
        ClientQuestCache.Entry entry = ClientQuestCache.entry(row.questId());
        if (entry == null || row.rewardIndex() < 0 || row.rewardIndex() >= entry.rewards().size()) {
            return;
        }
        ClientQuestCache.RewardEntry reward = entry.rewards().get(row.rewardIndex());
        int textY = slot.y() + (slot.height() - 8) / 2;
        int iconY = slot.y() + (slot.height() - QuestBookScreen.ROW_ICON) / 2;

        Slot context = RewardInboxLayout.column(slot, RewardInboxLayout.Column.CONTEXT);
        Slot action = RewardInboxLayout.column(slot, RewardInboxLayout.Column.ACTION);

        boolean missingItem = !reward.hasItem() && !reward.itemId().isEmpty();
        boolean shut = !row.pressable();
        String count = reward.hasItem() && row.count() > 1 ? "x" + row.count() : "";
        int textX = context.x() + QuestBookScreen.ROW_ICON + 5;
        // The name owns everything up to the action's own edge now that there is no cell between them,
        // less the count it has to leave room for.
        int room = Math.max(0, action.x() - RewardInboxLayout.COLUMN_GAP - textX
                - (count.isEmpty() ? 0 : 5 + r.textWidth(count)));
        String shown = Measure.truncate(row.label(), room, book.textMeasure(r));

        if (missingItem) {
            QuestBookScreen.drawItemPlaceholder(r, context.x(), iconY, QuestBookScreen.ROW_ICON);
        }
        else {
            r.icon(reward.shown(), context.x(), iconY, QuestBookScreen.ROW_ICON);
        }
        r.text(shown, textX, textY,
                missingItem || shut ? ArmatureTheme.blocked() : ArmatureTheme.body());
        if (!count.isEmpty()) {
            r.text(count, textX + r.textWidth(shown) + 5, textY, ArmatureTheme.faint());
        }

        if (slot.contains(mouseX, mouseY)) {
            book.bookState.rowTooltips.add(new QuestBookScreen.RowTooltip(slot, rewardTooltipLines(row.questId(),
                    row.rewardIndex())));
        }
    }

    /**
     * A row's strip button hover: what the press does, then everything the row is about.
     *
     * <p>A live button says "Press to collect"; a disabled one says its state instead, because the
     * sentence a player needs from a button they cannot press is why not. A reward carries its own
     * lines, {@link #rewardTooltipLines}. A header used to name its quest id here — a title can be
     * renamed, and the id is what an author and a bug report name — and naming an id at a player is
     * exactly what this file's hovers are not for: the title on the row is the same row, said the way
     * the player reading it can use.
     */
    private List<Component> buttonTooltip(RewardInboxLayout.Row row) {
        List<Component> lines = new ArrayList<>();
        if (row.pressable()) {
            lines.add(Component.translatable("tenet.screen.rewards.claim"));
        }
        else {
            lines.add(Component.translatable(row.status() == RewardInboxLayout.Status.LOCKED
                    ? "tenet.viewer.locked" : "tenet.viewer.claimed"));
        }
        if (row.isReward()) {
            for (String line : rewardTooltipLines(row.questId(), row.rewardIndex())) {
                lines.add(Component.literal(line));
            }
        }
        // A banner has no quest to ask about, and the block it would hit is the team's -- which spans
        // every quest in the chapter, so it is asked once for the whole record rather than per row.
        boolean held = row.isChapter()
                ? ClientQuestCache.rewardsHeld()
                : ClientQuestCache.rewardsBlockedOf(row.questId());
        if (held) {
            lines.add(Component.translatable("tenet.screen.rewards.held"));
        }
        return lines;
    }

    /**
     * A Claim button's hover, with the held line when the team's payouts are blocked.
     *
     * <p>The block is the server's and the server is the one that refuses; the button stays live because
     * a block is team-wide while a reward may except itself with {@code ignoreRewardBlocking}, which never
     * travels. So the hover says why a press may be refused rather than pretending the button cannot work
     * -- which is the honest half of what the wire's `rewardsBlocked` is for.
     */
    static List<Component> claimTooltip(String questId) {
        List<Component> lines = new ArrayList<>(List.of(
                Component.translatable("tenet.screen.collect_this_quest_s_rewards"),
                Component.translatable("tenet.screen.nothing_more_is_needed_it_is_already_finished")));
        if (ClientQuestCache.rewardsBlockedOf(questId)) {
            lines.add(Component.translatable("tenet.screen.rewards.held"));
        }
        return List.copyOf(lines);
    }

    /**
     * One reward's hover, in full: the item's own tooltip and the reward's own explanation.
     *
     * <p>The item's lines are vanilla's — its name, its lore, its enchantments, its attributes — and
     * they are what the player is deciding about, so they are not summarised. The reward's own lines
     * name the type, and a locked row names what it waits for. An item's raw id used to be the last
     * line, for the case a tooltip cannot name the thing; the name is already the first line, and an
     * id is for the picker, where the author is the one who can act on it.
     */
    private List<String> rewardTooltipLines(String questId, int rewardIndex) {
        ClientQuestCache.Entry entry = ClientQuestCache.entry(questId);
        if (entry == null || rewardIndex < 0 || rewardIndex >= entry.rewards().size()) {
            return List.of();
        }
        ClientQuestCache.RewardEntry reward = entry.rewards().get(rewardIndex);
        List<String> lines = new ArrayList<>();
        if (reward.hasItem()) {
            for (Component line : QuestBookScreen.getTooltipFromItem(book.minecraft(), reward.item())) {
                lines.add(line.getString());
            }
        }
        else {
            lines.add(BookQuestEditor.rowText("rewards", reward));
        }
        lines.addAll(QuestPanelLayout.playerTooltip("rewards", reward.type(), false, false));
        BookQuestRows.appendConditionLines(lines, reward.conditions(),
                ClientQuestCache.rewardLockOf(questId, rewardIndex));
        return lines;
    }

    /**
     * Writes the chapter, and asks the server to read it again.
     *
     * <p>Validating first is the model's job — {@link QuestEditor#save()} refuses the whole save if any
     * file would not load — so what is left here is telling the author what happened: the count on
     * success, and the loader's own messages on refusal, as toasts over the book. They used to go to
     * chat as well; they do not any more, because chat is unreadable behind the very screen the author
     * is working in and the toast is the copy they can see.
     */

    /** One line for the author, over the book. */
    private void report(String message) {
        book.toast(message, false);
    }
}
