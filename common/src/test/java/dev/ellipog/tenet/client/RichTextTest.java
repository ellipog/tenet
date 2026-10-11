package dev.ellipog.tenet.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Every token converted packs actually carry, against the strings they came from.
 */
class RichTextTest {

    private static List<RichText.Segment> parse(String text) {
        return RichText.parse(text);
    }

    private static RichText.Text onlyText(String text) {
        List<RichText.Segment> segments = parse(text);
        assertEquals(1, segments.size(), "one run: " + segments);
        return assertInstanceOf(RichText.Text.class, segments.get(0));
    }

    @Test
    @DisplayName("plain prose is one white run, with nothing set")
    void plainProseIsOneRun() {
        RichText.Text run = onlyText("Punch a Tree");
        assertEquals("Punch a Tree", run.text());
        assertEquals(RichText.WHITE, run.argb());
        assertTrue(!run.bold() && !run.italic() && !run.underline() && !run.strike()
                && !run.obfuscated(), "no flags: " + run);
        assertTrue(run.link().isEmpty());
    }

    @Test
    @DisplayName("a converted chapter title reads in three runs")
    void convertedTitleReadsInRuns() {
        // The shape every ATM chapter title arrives in: `&aChapter 2&r: &6The ATM Star`.
        List<RichText.Segment> segments = parse("&aChapter 2&r: &6The ATM Star");
        assertEquals(3, segments.size(), "green words, a white join, gold words: " + segments);

        RichText.Text first = assertInstanceOf(RichText.Text.class, segments.get(0));
        assertEquals("Chapter 2", first.text());
        assertEquals(0xFF55FF55, first.argb(), "`&a` is vanilla green");

        RichText.Text join = assertInstanceOf(RichText.Text.class, segments.get(1));
        assertEquals(": ", join.text());
        assertEquals(RichText.WHITE, join.argb(), "`&r` resets to white");

        RichText.Text last = assertInstanceOf(RichText.Text.class, segments.get(2));
        assertEquals("The ATM Star", last.text());
        assertEquals(0xFFFFAA00, last.argb(), "`&6` is vanilla gold");
    }

    @Test
    @DisplayName("section signs read exactly like ampersands, in either case")
    void sectionSignsMatchAmpersands() {
        assertEquals(parse("&aGreen"), parse("§aGreen"));
        assertEquals(parse("&A Green"), parse("&a Green"));
        assertEquals(0xFFFF5555, onlyText("§cRed").argb(), "`§c` is vanilla red");
    }

    @Test
    @DisplayName("a colour keeps the flags, a style keeps the ink, and reset clears both")
    void coloursAndStylesCompose() {
        List<RichText.Segment> segments = parse("&lBold &cBold Red&r Plain");
        assertEquals(3, segments.size(), segments.toString());

        RichText.Text bold = assertInstanceOf(RichText.Text.class, segments.get(0));
        assertTrue(bold.bold());
        assertEquals(RichText.WHITE, bold.argb());

        RichText.Text boldRed = assertInstanceOf(RichText.Text.class, segments.get(1));
        assertTrue(boldRed.bold(), "a colour does not clear bold");
        assertEquals(0xFFFF5555, boldRed.argb());

        RichText.Text plain = assertInstanceOf(RichText.Text.class, segments.get(2));
        assertTrue(!plain.bold() && plain.argb() == RichText.WHITE, "reset clears both: " + plain);
    }

    @Test
    @DisplayName("hex inks and underline/italic/strike arrive on their runs")
    void hexAndStyles() {
        RichText.Text hex = onlyText("&#FF0000Red");
        assertEquals(0xFFFF0000, hex.argb());

        RichText.Text styled = onlyText("&n&o&mAll Three");
        assertTrue(styled.underline() && styled.italic() && styled.strike(), styled.toString());
        assertTrue(!styled.bold() && !styled.obfuscated());
    }

    @Test
    @DisplayName("an escaped ampersand is one literal &, and a stray marker is words")
    void escapesAndStrays() {
        assertEquals("Forbidden & Arcanus", onlyText("Forbidden \\& Arcanus").text());
        assertEquals("100&", onlyText("100&").text(), "a trailing marker is literal");
        assertEquals("&x", onlyText("&x").text(), "a marker before no code is two characters");
        assertEquals("a & b", onlyText("a & b").text());
    }

    @Test
    @DisplayName("a pagebreak is a boundary, not words")
    void pagebreakIsABoundary() {
        List<RichText.Segment> segments = parse("First page{@pagebreak}Second page");
        assertEquals(3, segments.size(), segments.toString());
        assertEquals("First page", assertInstanceOf(RichText.Text.class, segments.get(0)).text());
        assertInstanceOf(RichText.PageBreak.class, segments.get(1));
        assertEquals("Second page", assertInstanceOf(RichText.Text.class, segments.get(2)).text());
    }

    @Test
    @DisplayName("an inline picture carries its source, size, alignment and spare pairs")
    void inlineImage() {
        // The shape converted packs actually carry.
        List<RichText.Segment> segments = parse("Look:{image:atm:textures/questpics/pneumaticcraft/block_tracker.png"
                + " width:150 height:150 align:center}Done");
        assertEquals(3, segments.size(), segments.toString());

        RichText.InlineImage image = assertInstanceOf(RichText.InlineImage.class, segments.get(1));
        assertEquals("atm:textures/questpics/pneumaticcraft/block_tracker.png", image.src());
        assertEquals(150, image.width());
        assertEquals(150, image.height());
        assertEquals("center", image.align());

        RichText.InlineImage sparse = assertInstanceOf(RichText.InlineImage.class,
                parse("{image:pack:textures/x.png}").get(0));
        assertEquals(0, sparse.width(), "unnamed size is zero, for the consumer to fill");
        assertEquals("center", sparse.align());
    }

    @Test
    @DisplayName("a hand link captures its address, and a lookup captures its key")
    void openUrlAndSubstitute() {
        RichText.OpenUrl bare = assertInstanceOf(RichText.OpenUrl.class,
                parse("{open_url:https://example.invalid}").get(0));
        assertEquals("https://example.invalid", bare.url());
        assertEquals("https://example.invalid", bare.label(), "without words the address is its own");

        RichText.OpenUrl labelled = assertInstanceOf(RichText.OpenUrl.class,
                parse("{open_url:https://example.invalid Our Site}").get(0));
        assertEquals("https://example.invalid", labelled.url());
        assertEquals("Our Site", labelled.label());

        RichText.Substitute substitute = assertInstanceOf(RichText.Substitute.class,
                parse("{substitute:quest.intro}").get(0));
        assertEquals("quest.intro", substitute.key());
    }

    @Test
    @DisplayName("a raw JSON component arrives as one run per leaf, words unjoined")
    void jsonComponent() {
        List<RichText.Segment> parts =
                parse("{\"text\": \"Hello \", \"extra\": [{\"text\": \"there\"}]}");
        assertEquals(2, parts.size(), parts.toString());
        assertEquals("Hello ", assertInstanceOf(RichText.JsonText.class, parts.get(0)).fallback());
        assertEquals("there", assertInstanceOf(RichText.JsonText.class, parts.get(1)).fallback());
        assertEquals("Hello there",
                RichText.pages("{\"text\": \"Hello \", \"extra\": [{\"text\": \"there\"}]}", key -> "")
                        .get(0).residual(),
                "the residual joins the runs verbatim");

        RichText.JsonText keyed = assertInstanceOf(RichText.JsonText.class,
                parse("{\"translate\": \"quest.a.title\"}").get(0));
        assertEquals("quest.a.title", keyed.fallback(), "a key with no fallback is its own words");
    }

    @Test
    @DisplayName("a JSON open_url arrives as a blue underlined link run")
    void jsonOpenUrl() {
        // The shape ATM10's video links arrive in (potion-brewer quest).
        String video =
                "{\"clickEvent\":{\"action\":\"open_url\",\"value\":\"https://youtu.be/DOW4xkxqp6g\"},"
                        + "\"color\":\"blue\",\"text\":\"VIDEO LINK HERE\",\"underlined\":true}";
        List<RichText.Segment> segments = parse(video);
        assertEquals(1, segments.size(), segments.toString());
        RichText.JsonText link = assertInstanceOf(RichText.JsonText.class, segments.get(0));
        assertEquals("VIDEO LINK HERE", link.fallback());
        assertEquals("https://youtu.be/DOW4xkxqp6g", link.link());
        assertTrue(link.quest().isEmpty(), "a URL opens nowhere else");
        assertEquals(0xFF5555FF, link.argb(), "vanilla blue, not a nearby one");
        assertTrue(link.underline());

        RichText.Rendered rendered = RichText.pages(video, key -> "").get(0);
        assertEquals("VIDEO LINK HERE", rendered.residual());
        RichText.Span span = RichText.spanAt(rendered.spans(), 0);
        assertEquals("https://youtu.be/DOW4xkxqp6g", span.link(), "the span opens its address");
        assertTrue(span.underline());
        assertEquals("VIDEO LINK HERE",
                RichText.plain(video), "one-ink surfaces read the label");
    }

    @Test
    @DisplayName("a JSON change_page arrives as a quest jump in its own ink")
    void jsonChangePage() {
        // The shape ATM10's questline jumps arrive in (dissolution-chamber quest).
        String jump =
                "{ \"text\": \"INDUSTRIAL FOREGOING QUESTLINE\", \"color\": \"#55FF55\", "
                        + "\"underlined\": true, \"clickEvent\": { \"action\": \"change_page\", "
                        + "\"value\": \"193F91842D2ED7D9\" } }";
        RichText.JsonText quest = assertInstanceOf(RichText.JsonText.class, parse(jump).get(0));
        assertEquals("INDUSTRIAL FOREGOING QUESTLINE", quest.fallback());
        assertTrue(quest.link().isEmpty(), "a quest jump opens no URL");
        assertEquals("193F91842D2ED7D9", quest.quest(), "the legacy value is a quest id, not a page");
        assertEquals(0xFF55FF55, quest.argb(), "the hex ink survives");
        assertTrue(quest.underline());

        RichText.Rendered rendered = RichText.pages(jump, key -> "").get(0);
        RichText.Span span = RichText.spanAt(rendered.spans(), 0);
        assertEquals("193F91842D2ED7D9", span.quest());
        assertTrue(span.link().isEmpty());
    }

    @Test
    @DisplayName("JSON runs inherit the look, and unknown presses open nothing")
    void jsonInheritanceAndRefusals() {
        // A parent's ink with a child's own press: the press wins, the ink stays inherited.
        List<RichText.Segment> segments = parse("{\"color\": \"green\", \"extra\": ["
                + "{\"text\": \"plain \"},"
                + "{\"text\": \"linked\", \"clickEvent\": {\"action\": \"open_url\", "
                + "\"value\": \"https://example.invalid\"}}]}");
        assertEquals(2, segments.size(), segments.toString());
        RichText.JsonText plain = assertInstanceOf(RichText.JsonText.class, segments.get(0));
        assertEquals("plain ", plain.fallback());
        assertTrue(plain.link().isEmpty() && plain.quest().isEmpty());
        assertEquals(0xFF55FF55, plain.argb(), "green inherits down");
        RichText.JsonText linked = assertInstanceOf(RichText.JsonText.class, segments.get(1));
        assertEquals("https://example.invalid", linked.link());
        assertEquals(0xFF55FF55, linked.argb(), "and across to the pressed run");

        // Guide addresses have no shelf here: words, nowhere to press.
        RichText.JsonText docs = assertInstanceOf(RichText.JsonText.class,
                parse("{\"text\": \"Guide\", \"clickEvent\": {\"action\": \"open_url\", "
                        + "\"value\": \"docs:some/page\"}}").get(0));
        assertEquals("Guide", docs.fallback());
        assertTrue(docs.link().isEmpty() && docs.quest().isEmpty(), "docs: opens nothing");

        // Anything outside the two quest-book actions: words, nowhere to press.
        RichText.JsonText other = assertInstanceOf(RichText.JsonText.class,
                parse("{\"text\": \"Run\", \"clickEvent\": {\"action\": \"run_command\", "
                        + "\"value\": \"/say hi\"}}").get(0));
        assertEquals("Run", other.fallback());
        assertTrue(other.link().isEmpty() && other.quest().isEmpty());
    }

    @Test
    @DisplayName("a brace that is prose stays prose")
    void strayBracesAreProse() {
        assertEquals("a { b", onlyText("a { b").text(), "an unclosed brace is words");
        assertEquals("{note}", onlyText("{note}").text(), "an unknown token is words");
        assertEquals("{oops", onlyText("{oops").text());
        assertEquals("{}", onlyText("{}").text(), "empty braces are words, not a component");
    }

    @Test
    @DisplayName("rainbow is one opaque ink per character, advancing around the wheel")
    void rainbowIsAStaticGradient() {
        List<RichText.Segment> segments = parse("&zAB");
        assertEquals(2, segments.size(), "no two gradient steps share an ink: " + segments);
        for (RichText.Segment segment : segments) {
            RichText.Text run = assertInstanceOf(RichText.Text.class, segment);
            assertEquals(1, run.text().length());
            assertEquals(0xFF000000, run.argb() & 0xFF000000, "opaque: " + run);
        }
        assertTrue(assertInstanceOf(RichText.Text.class, segments.get(0)).argb()
                != assertInstanceOf(RichText.Text.class, segments.get(1)).argb(),
                "adjacent steps differ");

        assertEquals(0xFFFF0000, RichText.rainbowArgb(0f), "the wheel starts at red");
        assertEquals(0xFF00FFFF, RichText.rainbowArgb(0.5f), "and crosses cyan halfway");
    }

    @Test
    @DisplayName("nothing in means nothing out, and obfuscated is recorded for the consumer")
    void emptyAndObfuscated() {
        assertTrue(parse("").isEmpty());
        assertTrue(onlyText("&kMagic").obfuscated(), "recorded here, degraded where it is drawn");
    }

    @Test
    @DisplayName("one ink reads the words without it, and keeps what the author escaped")
    void plainStrips() {
        assertEquals("Chapter 2: The ATM Star", RichText.plain("&aChapter 2&r: &6The ATM Star"));
        assertEquals("Forbidden & Arcanus", RichText.plain("Forbidden \\& Arcanus"));
        assertEquals("Fish & Chips", RichText.plain("Fish & Chips"),
                "a marker before no code is words, not ink");
        assertEquals("Punch a Tree", RichText.plain("Punch a Tree"), "plain prose passes through");
        assertEquals("", RichText.plain(""));
    }

    @Test
    @DisplayName("a page boundary splits the paragraph, and each page renders its own words")
    void pagesSplit() {
        List<RichText.Rendered> pages = RichText.pages("First page{@pagebreak}Second page", key -> "");
        assertEquals(2, pages.size());
        assertEquals("First page", pages.get(0).residual());
        assertEquals("Second page", pages.get(1).residual());

        List<RichText.Rendered> single = RichText.pages("&aGreen", key -> "");
        assertEquals(1, single.size(), "no boundary is one page");
        assertEquals("Green", single.get(0).residual(), "the residual is the words without the ink");
        assertEquals(1, single.get(0).spans().size());
        assertEquals(0xFF55FF55, single.get(0).spans().get(0).argb());
    }

    @Test
    @DisplayName("a rendered paragraph tiles its residual, so a lookup by index always answers")
    void spansTileTheResidual() {
        RichText.Rendered rendered =
                RichText.pages("&aGreen &6gold {open_url:https://example.invalid Site}", key -> "")
                        .get(0);

        assertEquals("Green gold Site", rendered.residual());
        List<RichText.Span> spans = rendered.spans();
        assertEquals(0, spans.get(0).start(), "the first span starts where the words do");
        for (int i = 1; i < spans.size(); i++) {
            assertEquals(spans.get(i - 1).end(), spans.get(i).start(),
                    "no gaps and no overlaps between spans");
        }
        assertEquals(rendered.residual().length(), spans.get(spans.size() - 1).end(),
                "and the last span ends where the words do");

        assertEquals(0xFF55FF55, RichText.spanAt(spans, 0).argb(), "green words wear green");
        assertEquals(0xFF55FF55, RichText.spanAt(spans, 5).argb(),
                "the join rides with the words before it, where no eye can see it");
        assertEquals(0xFFFFAA00, RichText.spanAt(spans, 6).argb(), "gold words wear gold");
        RichText.Span link = RichText.spanAt(spans, 12);
        assertEquals("https://example.invalid", link.link(), "the site opens its address");
        assertEquals("Site", rendered.residual().substring(link.start(), link.end()));
    }

    @Test
    @DisplayName("substitutions resolve, pictures are consumed, and components fall back to words")
    void specialSegmentsRender() {
        RichText.Rendered rendered = RichText.pages(
                "See {substitute:quest.intro} here {image:pack:textures/x.png width:10 height:10}",
                key -> key.equals("quest.intro") ? "the start" : "").get(0);

        assertEquals("See the start here ", rendered.residual(),
                "the lookup resolves, and the picture contributes no words");
        assertTrue(rendered.spans().stream().noneMatch(span -> !span.link().isEmpty()),
                "and neither opens anything");

        RichText.Rendered missing = RichText.pages("See {substitute:quest.gone} here", key -> null)
                .get(0);
        assertEquals("See  here", missing.residual(),
                "a missing key arrives as blank rather than as a raw key on screen");

        RichText.Rendered component =
                RichText.pages("{\"text\": \"Hello \"}", key -> "").get(0);
        assertEquals("Hello", component.residual(), "a component reads its fallback");
    }

    @Test
    @DisplayName("struck words arrive underlined, because the seam has no strike")
    void strikeDegradesToUnderline() {
        RichText.Rendered rendered = RichText.pages("&mStruck", key -> "").get(0);
        assertTrue(rendered.spans().get(0).underline(), "underlined, which survives the seam");
    }

    @Test
    @DisplayName("pictures stand at offsets between the words, in segment order")
    void imagesStandAtOffsets() {
        RichText.Rendered rendered = RichText.pages(
                "See {image:pack:textures/a.png width:150 height:75 align:center} this", key -> "")
                .get(0);

        assertEquals("See  this", rendered.residual(), "pictures contribute no words");
        assertEquals(1, rendered.images().size());
        RichText.PlacedImage placed = rendered.images().get(0);
        assertEquals(4, placed.offset(), "where the token stood");
        assertEquals("pack:textures/a.png", placed.image().src());
        assertEquals(150, placed.image().width());
        assertEquals(75, placed.image().height());
        assertEquals("center", placed.image().align());
    }

    @Test
    @DisplayName("a picture fits its column, scaling down and never up past it")
    void imagesFitTheirColumn() {
        RichText.InlineImage asked = new RichText.InlineImage("pack:textures/a.png", 150, 75, "center",
                java.util.Map.of());
        RichText.ImageBox fitted = RichText.fitImage(asked, 400);
        assertEquals(150, fitted.width());
        assertEquals(75, fitted.height(), "asked size, when it fits");

        RichText.ImageBox clamped = RichText.fitImage(asked, 100);
        assertEquals(100, clamped.width(), "clamped to the column");
        assertEquals(50, clamped.height(), "and scaled proportionally with it");

        RichText.ImageBox fallback = RichText.fitImage(
                new RichText.InlineImage("pack:textures/a.png", 0, 0, "left", java.util.Map.of()), 400);
        assertEquals(64, fallback.width());
        assertEquals(64, fallback.height(), "the legacy default when the token names no size");
    }

    @Test
    @DisplayName("coloured words draw in runs, and plain words draw as one text call")
    void drawTruncatedDrawsRuns() {
        dev.ellipog.tenet.client.render.RecordingRenderer r =
                dev.ellipog.tenet.client.render.RecordingRenderer.create();

        int advance = RichText.drawTruncated(r, "&aGreen", 10, 20, 400, RichText.WHITE);
        assertEquals(r.textWidth("Green"), advance, "the advance is the words' own width");
        assertTrue(r.texts().isEmpty(), "a coloured run is not a text call");
        assertEquals(1, r.styled().size(), "but one styled one");

        r.reset();
        int plain = RichText.drawTruncated(r, "Punch a Tree", 10, 20, 400, RichText.WHITE);
        assertEquals(r.textWidth("Punch a Tree"), plain);
        assertEquals(1, r.texts().size(), "untokened words draw exactly as before");
        assertTrue(r.styled().isEmpty());
    }

    @Test
    @DisplayName("a title too wide for its room is cut with an ellipsis, colours kept")
    void drawTruncatedCuts() {
        dev.ellipog.tenet.client.render.RecordingRenderer r =
                dev.ellipog.tenet.client.render.RecordingRenderer.create();
        int room = r.textWidth("Green ") + r.textWidth("…");

        int advance = RichText.drawTruncated(r, "&aGreen Tree", 0, 0, room, RichText.WHITE);
        assertEquals(room, advance, "cut words plus the ellipsis fill the room exactly");
        assertTrue(r.drewText("…"), "the cut is marked");
        assertTrue(r.styled().size() >= 1, "and what drew wore its ink");
    }

    @Test
    @DisplayName("a cut decided once draws exactly what truncating at the draw site draws")
    void truncateDrawsWhatDrawTruncatedDraws() {
        // The canvas's label pass decides per stamp and draws per frame: this is the
        // equivalence that makes the split pixel-identical, over plain words, inks, a
        // mid-run cut, a room that fits nothing, and a room that fits everything.
        for (String text : new String[] {"Punch a Tree", "&aGreen Tree", "&aGreen &bTree &cForest",
                "Fish & Chips", ""}) {
            for (int room : new int[] {0, 3, 20, 200, Integer.MAX_VALUE}) {
                dev.ellipog.tenet.client.render.RecordingRenderer atSite =
                        dev.ellipog.tenet.client.render.RecordingRenderer.create();
                int siteAdvance = RichText.drawTruncated(atSite, text, 10, 20, room, RichText.WHITE);

                dev.ellipog.tenet.client.render.RecordingRenderer fromCut =
                        dev.ellipog.tenet.client.render.RecordingRenderer.create();
                RichText.Cut cut = RichText.truncate(text, room,
                        dev.ellipog.armature.client.ui.kit.Measure.of(fromCut::textWidth,
                                fromCut.lineHeight()));
                int cutAdvance = RichText.draw(fromCut, cut, 10, 20, RichText.WHITE);

                assertEquals(siteAdvance, cutAdvance,
                        "the advance disagrees for \"" + text + "\" in room " + room);
                assertEquals(atSite.calls(), fromCut.calls(),
                        "the draw calls disagree for \"" + text + "\" in room " + room);
            }
        }
    }
}
