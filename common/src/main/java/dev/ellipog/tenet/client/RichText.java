package dev.ellipog.tenet.client;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Rich-text tokens in pack prose, read at render time into segments.
 *
 * <h2>Why a parser, and why here rather than in the file format</h2>
 *
 * <p>Real pack text carries {@code &} colour/style codes, {@code &#RRGGBB} hex, {@code &z} rainbow,
 * an author-escaped {@code \&}, {@code {@pagebreak}}, {@code {image:...}}, {@code {open_url:...}},
 * {@code {substitute:...}}, and rarely a raw JSON text component. The quest schema does not change
 * for any of them: the converter passes pack text through verbatim, and this parser — applied after
 * the locale overlay has resolved, where the reader's prose is built — is what gives the tokens
 * meaning. A token nobody recognises stays literal text, because a description with one stray brace
 * in it should read as prose rather than as a parse error.
 *
 * <p>Game-free, like the prose rules it sits beside: a pure function of a string, asserted without
 * a client. Colour/style resolution stops here too — no Armature release — because the card already
 * draws one styled call per piece, so a prepass emitting coloured runs slots into the existing loop.
 * Two degradations are recorded rather than hidden: obfuscated ({@code &k}) has no run flag to land
 * on and arrives as plain, and rainbow ({@code &z}) is a static per-character gradient rather than
 * an animation, so it costs no repaint.
 *
 * <h2>Vanilla semantics for the vanilla codes</h2>
 *
 * <p>A colour code sets the ink and keeps the flags; a style code sets its flag and keeps the ink;
 * {@code &r} resets both. That is what {@code §} does in the game, and {@code &} is its author-side
 * spelling here — both markers, either letter case. A marker at the end of the string, or before a
 * character that is no code, is literal text: dropping it would eat words.
 */
public final class RichText {

    private RichText() {
    }

    /** One piece of a parsed string, in order. */
    public sealed interface Segment {
    }

    /**
     * Visible words with their own look.
     *
     * @param argb plain prose arrives white
     * @param link a URL this run opens, or empty; only ever http/https by the time a consumer draws
     *             it — the opener refuses anything else
     */
    public record Text(String text, boolean bold, boolean italic, boolean underline, boolean strike,
                       boolean obfuscated, int argb, String link) implements Segment {
    }

    /** A page boundary: the card after this starts a new page. */
    public record PageBreak() implements Segment {
    }

    /**
     * A picture inside the prose.
     *
     * @param src    the file path or sprite id, exactly as written; the {@code .png} suffix rule is
     *               the same one the chapter-image source uses, applied by the consumer
     * @param width  requested width in pixels, or 0 when the token names none
     * @param height requested height in pixels, or 0 when the token names none
     * @param align  {@code left}, {@code center} or {@code right}; anything else arrives as written
     *               and the consumer treats it as {@code center}
     * @param params every other {@code key:value} pair the token carried, in order, so a future
     *               property survives the parse even before a consumer reads it
     */
    public record InlineImage(String src, int width, int height, String align,
                              Map<String, String> params) implements Segment {
    }

    /**
     * A link the author wrote by hand.
     *
     * <p>Approximate by admission: the legacy reader applies this to the text around it in ways
     * this parser does not reconstruct. What is pinned is the safe half — the URL is captured
     * whole, and the consumer draws it through the same http/https-only opener every other link
     * uses. A label the token carries travels beside it; without one the address is its own words.
     */
    public record OpenUrl(String url, String label) implements Segment {
    }

    /** A translation lookup, resolved by the consumer after the locale overlay has resolved. */
    public record Substitute(String key) implements Segment {
    }

    /**
     * A raw JSON text component's clickable run: one leaf's words with the look they inherit.
     *
     * <p>Vanilla inheritance: a child's colour, emphasis and click event win over its parent's,
     * and what neither names arrives as white unflagged prose with nowhere to press. The converter
     * passes pack text through verbatim, so this is where a component's {@code clickEvent} becomes
     * something the book can press: {@code open_url} travels as {@code link} and opens through the
     * same http/https-only opener every other link uses ({@code docs:} addresses have no guide to
     * open with and arrive with nowhere to press, the prose half of the image click's shelf
     * message), and the legacy {@code change_page} — whose value the legacy reader treats as a
     * quest to open rather than a book page — travels as {@code quest} for the client to resolve
     * by id or alias. Any other action arrives as words with nowhere to press.
     *
     * @param raw      the whole component source, so a richer reader can do better later;
     *                 unparseable JSON never arrives here — it stays prose
     * @param fallback this run's visible words
     * @param link     a URL this run opens, or empty
     * @param quest    a quest id this run opens, or empty; never both this and {@code link}
     * @param argb     this run's ink
     */
    public record JsonText(String raw, String fallback, String link, String quest, int argb,
                           boolean bold, boolean italic, boolean underline) implements Segment {
    }

    /** Plain prose arrives in this ink. */
    public static final int WHITE = 0xFFFFFFFF;

    /**
     * One styled span of a rendered paragraph: {@code [start, end)} into the residual.
     *
     * @param argb      the ink as {@code 0xAARRGGBB}; {@link #WHITE} is unstyled prose
     * @param link      a URL this span opens, or empty
     * @param quest     a quest id this span opens, or empty; never both this and {@code link}
     * @param underline struck-through text arrives with this set: the seam has no strikethrough run,
     *                  and an underline is the closest visible emphasis that survives it
     */
    public record Span(int start, int end, int argb, String link, String quest, boolean bold,
                       boolean italic, boolean underline) {
    }

    /**
     * One page of a paragraph: the visible words for the markdown pass, the rich-text look over
     * them, and the pictures standing among them.
     *
     * <p>The spans tile the residual in order and cover every character of it: a lookup by index
     * always answers, so the drawing never has to ask what an unstyled character is. The pictures
     * are zero-width markers at residual offsets, in segment order: splitting the residual at their
     * offsets is how a caller lays text and pictures out in one flow.
     */
    public record Rendered(String residual, List<Span> spans, List<PlacedImage> images) {
    }

    /**
     * An inline picture standing at a residual offset.
     *
     * <p>Zero-width: it sits <i>between</i> characters rather than covering any, so a colour span
     * reaching across it continues on both sides. The offset is where the token stood.
     */
    public record PlacedImage(InlineImage image, int offset) {
    }

    /**
     * A picture's drawn box: fitted to the column, in screen pixels.
     *
     * <p>Width is what the token asked clamped to the column, height follows proportionally — which
     * is stretching only when the token's own numbers stretch, the same reading chapter pictures
     * get. A token naming no size arrives at 64 pixels square: the legacy default, and the honest
     * answer for a picture whose size nobody wrote down.
     */
    public record ImageBox(int width, int height) {
    }

    /** A token's picture fitted to a column of prose. Never empty: both sides are at least one pixel. */
    public static ImageBox fitImage(InlineImage image, int columnWidth) {
        int askedWidth = image.width() > 0 ? image.width() : image.height() > 0 ? image.height() : 64;
        int askedHeight =
                image.height() > 0 ? image.height() : image.width() > 0 ? image.width() : 64;
        int width = Math.max(1, Math.min(askedWidth, Math.max(1, columnWidth)));
        int height = Math.max(1, (int) Math.round((double) askedHeight * width / askedWidth));
        return new ImageBox(width, height);
    }

    /**
     * A paragraph as pages: split on {@code {@pagebreak}}, each page rendered.
     *
     * <p>One page per returned element, in order, each with no page boundary in it: the card this
     * feeds is a scrolling body rather than pages, so a boundary is a paragraph break and not a
     * pager state. An empty page contributes an empty residual rather than nothing, because two
     * breaks in a row are a wider gap and not one.
     *
     * @param substitute how a {@code {substitute:key}} resolves; missing keys arrive as blank rather
     *                   than as a raw key on screen
     */
    public static List<Rendered> pages(String text,
                                       java.util.function.Function<String, String> substitute) {
        Objects.requireNonNull(text, "text");
        Objects.requireNonNull(substitute, "substitute");
        List<Rendered> out = new ArrayList<>();
        StringBuilder residual = new StringBuilder();
        List<Span> spans = new ArrayList<>();
        List<PlacedImage> images = new ArrayList<>();
        for (Segment segment : parse(text)) {
            if (segment instanceof PageBreak) {
                out.add(new Rendered(residual.toString(), List.copyOf(spans), List.copyOf(images)));
                residual = new StringBuilder();
                spans = new ArrayList<>();
                images = new ArrayList<>();
                continue;
            }
            if (segment instanceof InlineImage image) {
                images.add(new PlacedImage(image, residual.length()));
                continue;
            }
            appendRendered(residual, spans, segment, substitute);
        }
        out.add(new Rendered(residual.toString(), List.copyOf(spans), List.copyOf(images)));
        return List.copyOf(out);
    }

    /** One segment's words onto the residual, with its look recorded over them. */
    private static void appendRendered(StringBuilder residual, List<Span> spans, Segment segment,
                                       java.util.function.Function<String, String> substitute) {
        switch (segment) {
            // Struck-through arrives underlined: the seam has no strikethrough run, and an underline
            // is the closest visible emphasis that survives it. Obfuscated is dropped to plain, and
            // both degradations are recorded rather than hidden.
            case Text text -> span(residual, spans, text.text(), text.argb(), "", "",
                    text.bold(), text.italic(), text.underline() || text.strike());
            case OpenUrl open -> span(residual, spans, open.label(), WHITE, open.url(), "", false,
                    false, true);
            case Substitute sub -> {
                String resolved = substitute.apply(sub.key());
                span(residual, spans, resolved == null ? "" : resolved, WHITE, "", "", false, false,
                        false);
            }
            case JsonText json -> span(residual, spans, json.fallback(), json.argb(), json.link(),
                    json.quest(), json.bold(), json.italic(), json.underline());
            // An inline picture contributes no words: it is recorded with its offset above, and the
            // caller lays it out. Consumed rather than left as prose, because the alternative
            // is the raw token on screen.
            case InlineImage ignored -> {
            }
            case PageBreak ignored -> {
            }
        }
    }

    /** Words plus the look that covers exactly them. Empty words record nothing. */
    private static void span(StringBuilder residual, List<Span> spans, String words, int argb,
                             String link, String quest, boolean bold, boolean italic,
                             boolean underline) {
        if (words.isEmpty()) {
            return;
        }
        int from = residual.length();
        residual.append(words);
        spans.add(new Span(from, residual.length(), argb, link, quest, bold, italic, underline));
    }

    /** The look covering an index, or null where the residual has no words at all. */
    public static Span spanAt(List<Span> spans, int index) {
        for (Span span : spans) {
            if (index >= span.start() && index < span.end()) {
                return span;
            }
        }
        return null;
    }

    /**
     * Words with their rich-text ink, drawn and truncated in one pass.
     *
     * <p>For the surfaces that draw one line in one ink but read strings that carry codes: quest titles
     * on the canvas, the card's header, and anywhere else a title is drawn from the entry in scope.
     * Colours are widthless, so measuring stays plain — what is drawn here is exactly as wide as the
     * stripped words the callers measured, truncated and laid out before calling.
     *
     * <p>Styles do not survive: bold and italic change widths, and a run drawn wider than measured is
     * a label that overruns its room, its backdrop and its collision box. Titles wear colours only;
     * emphasis lives in the card. Struck-through arrives underlined and obfuscated arrives plain, the
     * same degradations the prose pass makes. Links open nothing here — a title is not a press — so
     * their labels draw in the default ink with no underline. Pictures and page breaks contribute
     * nothing; substitutions resolve against the locale overlay.
     *
     * <p>A title with no tokens takes the plain path: one {@code text} call, exactly as before, so
     * ninety-nine titles in a hundred cost nothing and draw nothing differently.
     *
     * @return the advance, in pixels: where the next thing on the line starts
     */
    public static int drawTruncated(dev.ellipog.armature.client.render.GuiRenderer r, String text, int x,
                                    int y, int maxWidth, int defaultInk) {
        Objects.requireNonNull(r, "r");
        Objects.requireNonNull(text, "text");
        // Through the cut: the widths below are the renderer's own memo, so the numbers are
        // the ones this method has always drawn with, and the pixels are the ones it has
        // always drawn — decided once now rather than once per call site per frame.
        return draw(r,
                truncate(text, maxWidth,
                        dev.ellipog.armature.client.ui.kit.Measure.of(r::textWidth, r.lineHeight())),
                x, y, defaultInk);
    }

    /**
     * Words with their rich-text ink, drawn whole.
     *
     * <p>The untruncated half of {@link #drawTruncated}: same runs, same inks, no ellipsis. For the
     * lines nothing truncates — the card's header, where the state tag that follows is placed off
     * the width this returns.
     *
     * @return the advance, in pixels
     */
    public static int drawColored(dev.ellipog.armature.client.render.GuiRenderer r, String text, int x,
                                  int y, int defaultInk) {
        return drawTruncated(r, text, x, y, Integer.MAX_VALUE, defaultInk);
    }

    /** One drawable run: visible words in one ink, underline or not. */
    public record InkRun(String text, int ink, boolean underline) {
    }

    /**
     * A title cut to a width, computed once and drawn many times: the runs that fit, the
     * advance they and any ellipsis draw, and whether the ellipsis follows them.
     *
     * <p>What {@link #drawTruncated} decides on every call, split so a per-frame pass can
     * decide per stamp and only draw per frame. The runs are the exact sequence the draw
     * would have issued — whole runs that fit, then single characters of the cut run —
     * so drawing a cut is pixel-identical to truncating at the draw site.
     */
    public record Cut(List<InkRun> runs, int advance, boolean ellipsis) {
    }

    /**
     * What a remembered cut is remembered against: the text, the width, and the stamp.
     *
     * <p>The stamp is the caller's because a cut depends on things this file cannot see: the
     * font and the text scale (which {@code TextEpoch} names), the locale overlay a
     * substitution resolves against (which {@code ClientQuestCache.textRevision} names), and
     * the renderer the widths were measured with. A caller that passes a constant holds its
     * cuts across a font change, which lays text out to widths it no longer has.
     */
    public record CutKey(String text, int maxWidth, long epoch) {
    }

    /** How many cuts one memo holds before it starts again, eldest half first. */
    private static final int CUT_LIMIT = 512;

    private static final Map<CutKey, Cut> CUTS = new LinkedHashMap<>();

    /**
     * Cuts a title to a width, remembered while its stamp holds still.
     *
     * <p>For the per-frame passes — the card's header cuts three titles a frame while one is
     * open — where re-parsing (per-character look copies plus a throwaway Gson attempt on every
     * brace-led string) is the cost and the answer is the same every frame. A hit returns the
     * identical cut object; a new stamp recomputes through {@link #truncate}, so what a stamp
     * change draws is what an uncached frame would have drawn.
     *
     * @param epoch what the cut is remembered against — font, scale, locale text and renderer;
     *              see {@link CutKey}
     */
    public static Cut cut(String text, int maxWidth,
                          dev.ellipog.armature.client.ui.kit.Measure measure, long epoch) {
        Objects.requireNonNull(text, "text");
        Objects.requireNonNull(measure, "measure");
        CutKey key = new CutKey(text, maxWidth, epoch);
        Cut hit = CUTS.get(key);
        if (hit != null) {
            return hit;
        }
        Cut built = truncate(text, maxWidth, measure);
        if (CUTS.size() >= CUT_LIMIT) {
            var it = CUTS.keySet().iterator();
            int remove = CUTS.size() / 2;
            for (int i = 0; i < remove && it.hasNext(); i++) {
                it.next();
                it.remove();
            }
        }
        CUTS.put(key, built);
        return built;
    }

    /**
     * Cuts a title to a width without drawing it.
     *
     * <p>Pure: widths come from the measure, so a caller with a memoised one pays the
     * glyph walk once per distinct string rather than once per frame. The ellipsis is
     * {@code …}, and its width is part of the advance exactly as {@link #drawTruncated}
     * counts it — which delegates here, so the two cannot disagree.
     */
    public static Cut truncate(String text, int maxWidth,
                               dev.ellipog.armature.client.ui.kit.Measure measure) {
        Objects.requireNonNull(text, "text");
        Objects.requireNonNull(measure, "measure");
        List<InkRun> runs = runs(text);
        int total = 0;
        for (InkRun run : runs) {
            total += measure.width(run.text());
        }
        if (maxWidth <= 0) {
            return new Cut(List.of(), 0, false);
        }
        if (total <= maxWidth) {
            return new Cut(List.copyOf(runs), total, false);
        }
        String ellipsis = "\u2026";
        int room = maxWidth - measure.width(ellipsis);
        if (room > 0) {
            List<InkRun> kept = cutRuns(runs, room, measure);
            int drawn = 0;
            for (InkRun run : kept) {
                drawn += measure.width(run.text());
            }
            return new Cut(kept, drawn + measure.width(ellipsis), true);
        }
        List<InkRun> kept = cutRuns(runs, maxWidth, measure);
        int drawn = 0;
        for (InkRun run : kept) {
            drawn += measure.width(run.text());
        }
        return new Cut(kept, drawn, false);
    }

    /**
     * Draws a cut where the caller measured it.
     *
     * @return the advance, in pixels: where the next thing on the line starts
     */
    public static int draw(dev.ellipog.armature.client.render.GuiRenderer r, Cut cut, int x, int y,
                           int defaultInk) {
        Objects.requireNonNull(r, "r");
        Objects.requireNonNull(cut, "cut");
        drawRuns(r, cut.runs(), x, y, defaultInk);
        if (cut.ellipsis()) {
            String ellipsis = "\u2026";
            r.text(ellipsis, x + cut.advance() - r.textWidth(ellipsis), y, defaultInk);
        }
        return cut.advance();
    }

    /**
     * A string's drawable runs, in order.
     *
     * <p>Colours kept, styles dropped for the reason {@link #drawTruncated} gives, links flattened to
     * their labels, pictures and page breaks skipped, substitutions resolved. Empty runs never
     * appear: a run with no words is nothing to draw and nothing to measure.
     */
    private static List<InkRun> runs(String text) {
        List<InkRun> runs = new ArrayList<>();
        for (Segment segment : parse(text)) {
            switch (segment) {
                case Text words -> {
                    if (!words.text().isEmpty()) {
                        runs.add(new InkRun(words.text(), words.argb(),
                                words.underline() || words.strike()));
                    }
                }
                case OpenUrl open -> {
                    if (!open.label().isEmpty()) {
                        runs.add(new InkRun(open.label(), WHITE, false));
                    }
                }
                case Substitute sub -> {
                    String resolved = ClientLocale.find(sub.key());
                    if (resolved != null && !resolved.isEmpty()) {
                        runs.add(new InkRun(resolved, WHITE, false));
                    }
                }
                case JsonText json -> {
                    if (!json.fallback().isEmpty()) {
                        runs.add(new InkRun(json.fallback(), json.argb(), json.underline()));
                    }
                }
                default -> {
                }
            }
        }
        return runs;
    }

    /** Every run drawn whole, left to right. */
    private static void drawRuns(dev.ellipog.armature.client.render.GuiRenderer r, List<InkRun> runs,
                                 int x, int y, int defaultInk) {
        int atX = x;
        for (InkRun run : runs) {
            atX += drawRun(r, run, atX, y, defaultInk);
        }
    }

    /**
     * One run: plain text when the default ink does, one styled call when it does not.
     *
     * <p>The split is what keeps untokened titles byte-identical to before: a run in the default
     * ink with no underline is a {@code text} call, exactly as it always was, and only a run that
     * needs something else pays for a styled one.
     *
     * @return the run's advance
     */
    private static int drawRun(dev.ellipog.armature.client.render.GuiRenderer r, InkRun run, int x,
                               int y, int defaultInk) {
        if (run.ink() == defaultInk && !run.underline()) {
            r.text(run.text(), x, y, defaultInk);
        }
        else {
            r.styledText(
                    List.of(new dev.ellipog.armature.client.render.GuiRenderer.StyledRun(run.text(),
                            false, false, run.underline(), 1F)),
                    x, y, run.ink());
        }
        return r.textWidth(run.text());
    }

    /**
     * The runs a cut budget keeps: whole runs that fit, then single characters of the
     * run it cuts through.
     *
     * <p>The draw decision without the drawing, so a stamp can keep it and a frame only
     * re-issues it. Into the run character by character: a run is a label fragment, never
     * a document, and the linear walk cannot be off by one the way a hand-written binary
     * search can.
     */
    private static List<InkRun> cutRuns(List<InkRun> runs, int budget,
                                        dev.ellipog.armature.client.ui.kit.Measure measure) {
        List<InkRun> kept = new ArrayList<>();
        int drawn = 0;
        for (InkRun run : runs) {
            int width = measure.width(run.text());
            if (drawn + width <= budget) {
                kept.add(run);
                drawn += width;
                continue;
            }
            for (int i = 0; i < run.text().length(); i++) {
                String ch = run.text().substring(i, i + 1);
                int charWidth = measure.width(ch);
                if (drawn + charWidth > budget) {
                    break;
                }
                kept.add(new InkRun(ch, run.ink(), run.underline()));
                drawn += charWidth;
            }
            break;
        }
        return kept;
    }

    /**
     * A string with every token read out of it: colours gone, escapes kept, brace tokens replaced
     * by the words they stand for.
     *
     * <p>For the surfaces that draw one ink: titles, labels, toasts and rows. A title carrying
     * {@code &a} reads the words without the ink; an escaped {@code \&} reads as {@code &}, because
     * that is what the author meant; a link reads its label; anything with no words reads as
     * nothing. An ampersand before no code is literal, so {@code Fish & Chips} survives — but
     * {@code R&B} does not, and that is documented where titles are: {@code &} starts a colour
     * code, legacy-style, and a literal one is written {@code \&}.
     */
    public static String plain(String text) {
        Objects.requireNonNull(text, "text");
        StringBuilder out = new StringBuilder();
        for (Segment segment : parse(text)) {
            switch (segment) {
                case Text words -> out.append(words.text());
                case OpenUrl open -> out.append(open.label());
                case JsonText json -> out.append(json.fallback());
                default -> {
                }
            }
        }
        return out.toString();
    }

    /**
     * The game's sixteen inks, {@code 0} through {@code f}, in order.
     *
     * <p>Vanilla's own palette, opaque throughout: these are the colours a pack author means by
     * {@code &a}, and matching them exactly is what makes a converted title the same green rather
     * than a nearby one.
     */
    private static final int[] PALETTE = {
            0xFF000000, 0xFF0000AA, 0xFF00AA00, 0xFF00AAAA,
            0xFFAA0000, 0xFFAA00AA, 0xFFFFAA00, 0xFFAAAAAA,
            0xFF555555, 0xFF5555FF, 0xFF55FF55, 0xFF55FFFF,
            0xFFFF5555, 0xFFFF55FF, 0xFFFFFF55, 0xFFFFFFFF,
    };

    /** The look words are accumulating in: flags, ink, and whether the ink is a gradient. */
    private static final class Look {
        boolean bold;
        boolean italic;
        boolean underline;
        boolean strike;
        boolean obfuscated;
        int argb = WHITE;
        boolean rainbow;
        float hue;

        Look copy() {
            Look out = new Look();
            out.bold = bold;
            out.italic = italic;
            out.underline = underline;
            out.strike = strike;
            out.obfuscated = obfuscated;
            out.argb = argb;
            out.rainbow = rainbow;
            out.hue = hue;
            return out;
        }

        boolean sameAs(Look other) {
            return bold == other.bold && italic == other.italic && underline == other.underline
                    && strike == other.strike && obfuscated == other.obfuscated
                    && !rainbow && !other.rainbow && argb == other.argb;
        }

        /** The ink one character is drawn in: its gradient step, or the flat ink. */
        int ink() {
            return rainbow ? rainbowArgb(hue) : argb;
        }
    }

    /**
     * The tokens in a string, in order.
     *
     * <p>Adjacent words with the same look arrive as one {@link Text}: merging is what keeps a
     * sentence with one code in it two runs rather than a run per character. Rainbow text never
     * merges — each character carries its own ink — which is the honest cost of a gradient. A string
     * that is a raw JSON component arrives as one {@link JsonText} run per leaf, each with the look
     * it inherits, and nothing else.
     */
    public static List<Segment> parse(String text) {
        Objects.requireNonNull(text, "text");
        List<Segment> component = components(text);
        if (component != null) {
            return component;
        }
        List<Segment> out = new ArrayList<>();
        StringBuilder words = new StringBuilder();
        Look look = new Look();
        Look pending = new Look();

        int i = 0;
        while (i < text.length()) {
            char c = text.charAt(i);
            if (c == '{') {
                Segment token = token(text, i);
                if (token != null) {
                    flush(out, words, pending);
                    out.add(token);
                    i = tokenEnd(text, i);
                    continue;
                }
            }
            if (c == '\\' && i + 1 < text.length() && text.charAt(i + 1) == '&') {
                // An escaped ampersand is one literal `&`.
                words = append(out, words, pending, look, "&");
                look.hue += hueStep();
                i += 2;
                continue;
            }
            if ((c == '&' || c == '§') && i + 1 < text.length()) {
                int after = consumeCode(text, i + 1, look);
                if (after > 0) {
                    words = flushed(out, words, pending, look);
                    i = after;
                    continue;
                }
                // No code: the marker is literal, and so is what follows.
            }
            words = append(out, words, pending, look, String.valueOf(c));
            look.hue += hueStep();
            i++;
        }
        flush(out, words, pending);
        return List.copyOf(out);
    }

    /**
     * One character onto the pending words, flushing first when the look changed.
     *
     * <p>Returns the builder to keep appending to: the same one while the look holds still, a fresh
     * one after a flush. Rainbow text flushes every character, because no two gradient steps share
     * an ink — {@link Look#sameAs} answers false whenever either side is a gradient.
     */
    private static StringBuilder append(List<Segment> out, StringBuilder words, Look pending, Look look,
                                        String word) {
        Look now = look.copy();
        now.argb = look.ink();
        if (words.length() > 0 && !pending.sameAs(now)) {
            flush(out, words, pending);
        }
        if (words.length() == 0) {
            pending.bold = now.bold;
            pending.italic = now.italic;
            pending.underline = now.underline;
            pending.strike = now.strike;
            pending.obfuscated = now.obfuscated;
            pending.argb = now.argb;
            pending.rainbow = false;
            words = new StringBuilder();
        }
        words.append(word);
        return words;
    }

    /** The pending words as one run, when there are any. */
    private static void flush(List<Segment> out, StringBuilder words, Look pending) {
        if (words.length() == 0) {
            return;
        }
        out.add(new Text(words.toString(), pending.bold, pending.italic, pending.underline,
                pending.strike, pending.obfuscated, pending.argb, ""));
        words.setLength(0);
    }

    /** The pending words flushed and their builder replaced, for a look change mid-sentence. */
    private static StringBuilder flushed(List<Segment> out, StringBuilder words, Look pending, Look look) {
        flush(out, words, pending);
        pending.bold = look.bold;
        pending.italic = look.italic;
        pending.underline = look.underline;
        pending.strike = look.strike;
        pending.obfuscated = look.obfuscated;
        pending.argb = look.ink();
        pending.rainbow = false;
        return new StringBuilder();
    }

    /**
     * A formatting code at {@code from}, applied to {@code look}.
     *
     * @return just past the code, or -1 when the marker opens no code and stays literal
     */
    private static int consumeCode(String text, int from, Look look) {
        char code = text.charAt(from);
        int colour = paletteIndex(code);
        if (colour >= 0) {
            look.argb = PALETTE[colour];
            look.rainbow = false;
            return from + 1;
        }
        if (code == '#' && isHex(text, from + 1, 6)) {
            look.argb = 0xFF000000 | Integer.parseUnsignedInt(text.substring(from + 1, from + 7), 16);
            look.rainbow = false;
            return from + 7;
        }
        switch (Character.toLowerCase(code)) {
            case 'k' -> look.obfuscated = true;
            case 'l' -> look.bold = true;
            case 'm' -> look.strike = true;
            case 'n' -> look.underline = true;
            case 'o' -> look.italic = true;
            case 'r' -> {
                look.bold = false;
                look.italic = false;
                look.underline = false;
                look.strike = false;
                look.obfuscated = false;
                look.argb = WHITE;
                look.rainbow = false;
            }
            case 'z' -> {
                // A static gradient until the next colour or reset — see the class note.
                look.rainbow = true;
                look.hue = 0f;
            }
            default -> {
                return -1;
            }
        }
        if (Character.toLowerCase(code) != 'z') {
            look.rainbow = false;
        }
        return from + 1;
    }

    /**
     * One of the brace tokens, or null when the brace opens prose.
     *
     * <p>Only the four this format gives meaning: {@code {@pagebreak}}, {@code {image:...}},
     * {@code {open_url:...}} and {@code {substitute:...}}. Anything else — a JSON component is
     * handled before this is ever asked, and a stray brace is prose — is null.
     */
    private static Segment token(String text, int from) {
        int end = text.indexOf('}', from);
        if (end < 0) {
            return null;
        }
        String body = text.substring(from + 1, end);
        if (body.equals("@pagebreak")) {
            return new PageBreak();
        }
        if (body.startsWith("image:")) {
            return image(body.substring("image:".length()));
        }
        if (body.startsWith("open_url:")) {
            String rest = body.substring("open_url:".length()).strip();
            if (rest.isEmpty()) {
                return null;
            }
            int split = rest.indexOf(' ');
            String url = split < 0 ? rest : rest.substring(0, split);
            String label = split < 0 ? rest : rest.substring(split + 1).strip();
            return new OpenUrl(url, label.isEmpty() ? url : label);
        }
        if (body.startsWith("substitute:")) {
            String key = body.substring("substitute:".length()).strip();
            return key.isEmpty() ? null : new Substitute(key);
        }
        return null;
    }

    /** Just past the token starting at {@code from}: after its closing brace. */
    private static int tokenEnd(String text, int from) {
        int end = text.indexOf('}', from);
        return end < 0 ? text.length() : end + 1;
    }

    /**
     * An inline picture's properties.
     *
     * <p>The source first, then {@code key:value} pairs separated by spaces — the shape converted
     * packs actually carry ({@code {image:path width:150 height:150 align:center}}). A pair without
     * a colon is ignored rather than refused: an unknown property must not cost the picture.
     */
    private static InlineImage image(String body) {
        String[] parts = body.strip().split("\\s+");
        if (parts.length == 0 || parts[0].isEmpty()) {
            return new InlineImage("", 0, 0, "center", Map.of());
        }
        int width = 0;
        int height = 0;
        String align = "center";
        Map<String, String> params = new LinkedHashMap<>();
        for (int i = 1; i < parts.length; i++) {
            int colon = parts[i].indexOf(':');
            if (colon <= 0) {
                continue;
            }
            String key = parts[i].substring(0, colon).toLowerCase(java.util.Locale.ROOT);
            String value = parts[i].substring(colon + 1);
            switch (key) {
                case "width" -> width = number(value);
                case "height" -> height = number(value);
                case "align" -> align = value.toLowerCase(java.util.Locale.ROOT);
                default -> params.put(key, value);
            }
        }
        return new InlineImage(parts[0], width, height, align, Map.copyOf(params));
    }

    /** A whole-pixel number, or 0 when the token names something that is not one. */
    private static int number(String value) {
        try {
            return Math.max(0, Integer.parseInt(value));
        }
        catch (NumberFormatException notANumber) {
            return 0;
        }
    }

    /** The palette slot for a colour code, either marker and either case, or -1. */
    private static int paletteIndex(char code) {
        char lower = Character.toLowerCase(code);
        if (lower >= '0' && lower <= '9') {
            return lower - '0';
        }
        if (lower >= 'a' && lower <= 'f') {
            return 10 + lower - 'a';
        }
        return -1;
    }

    /** Whether {@code length} hex digits start at {@code from}. */
    private static boolean isHex(String text, int from, int length) {
        if (from + length > text.length()) {
            return false;
        }
        for (int i = from; i < from + length; i++) {
            char c = text.charAt(i);
            boolean hex = c >= '0' && c <= '9'
                    || c >= 'a' && c <= 'f'
                    || c >= 'A' && c <= 'F';
            if (!hex) {
                return false;
            }
        }
        return true;
    }

    /** One hue step of the rainbow gradient: twelve characters around the wheel. */
    private static float hueStep() {
        return 1f / 12f;
    }

    /** The gradient's ink at one hue: full saturation and brightness, opaque. */
    static int rainbowArgb(float hue) {
        float h = hue - (float) Math.floor(hue);
        int sector = (int) (h * 6);
        float fraction = h * 6 - sector;
        int rising = Math.round(fraction * 255);
        int falling = Math.round((1 - fraction) * 255);
        int rgb = switch (sector % 6) {
            case 0 -> 0xFF0000 | rising << 8;
            case 1 -> 0x00FF00 | falling << 16;
            case 2 -> 0x00FF00 | rising;
            case 3 -> 0x0000FF | falling << 8;
            case 4 -> rising << 16 | 0x0000FF;
            default -> 0xFF0000 | falling;
        };
        return 0xFF000000 | rgb;
    }

    /**
     * A string that is a raw JSON text component, as its clickable runs, or null when prose.
     *
     * <p>Strict on purpose: an object must hold at least one of {@code text}, {@code translate} or
     * {@code extra} — anything else, including a brace a description uses as prose (which Gson's
     * lenient reader would happily call an object), stays prose. An array must be non-empty. Runs
     * with no words are skipped rather than recorded, because a token the reader cannot draw is
     * still a string the file was allowed to hold.
     */
    static List<Segment> components(String text) {
        String stripped = text.strip();
        if (stripped.length() < 2) {
            return null;
        }
        char open = stripped.charAt(0);
        if (open != '{' && open != '[') {
            return null;
        }
        com.google.gson.JsonElement parsed;
        try {
            parsed = com.google.gson.JsonParser.parseString(stripped);
        }
        catch (com.google.gson.JsonSyntaxException notJson) {
            return null;
        }
        if (parsed.isJsonObject()) {
            com.google.gson.JsonObject object = parsed.getAsJsonObject();
            if (!object.has("text") && !object.has("translate") && !object.has("extra")) {
                return null;
            }
        }
        else if (parsed.isJsonArray()) {
            if (parsed.getAsJsonArray().isEmpty()) {
                return null;
            }
        }
        else {
            return null;
        }
        List<Segment> runs = new ArrayList<>();
        collect(stripped, parsed, WHITE, false, false, false, "", "", runs);
        if (!runs.isEmpty()) {
            // The whole-component strip the old reader did, kept at the edges: a component that
            // is all padding reads as nothing, and inner joins stay verbatim.
            JsonText first = (JsonText) runs.get(0);
            String leading = first.fallback().stripLeading();
            if (leading.isEmpty()) {
                runs.remove(0);
            }
            else if (leading.length() != first.fallback().length()) {
                runs.set(0, withFallback(first, leading));
            }
        }
        if (!runs.isEmpty()) {
            JsonText last = (JsonText) runs.get(runs.size() - 1);
            String trailing = last.fallback().stripTrailing();
            if (trailing.isEmpty()) {
                runs.remove(runs.size() - 1);
            }
            else if (trailing.length() != last.fallback().length()) {
                runs.set(runs.size() - 1, withFallback(last, trailing));
            }
        }
        return List.copyOf(runs);
    }

    /** One run with different words: the edge strip above, preserving the look. */
    private static JsonText withFallback(JsonText run, String fallback) {
        return new JsonText(run.raw(), fallback, run.link(), run.quest(), run.argb(), run.bold(),
                run.italic(), run.underline());
    }

    /**
     * One component subtree's clickable runs, in order, with the look runs inherit.
     *
     * <p>A child's colour, emphasis and click event win over its parent's; what neither names
     * keeps the inherited look. That is vanilla's own rule, and matching it is what makes a
     * converted video link the same blue rather than a nearby one.
     */
    private static void collect(String raw, com.google.gson.JsonElement element, int argb,
                                boolean bold, boolean italic, boolean underline, String link,
                                String quest, List<Segment> runs) {
        if (element == null || element.isJsonNull()) {
            return;
        }
        if (element.isJsonPrimitive()) {
            if (element.getAsJsonPrimitive().isString()) {
                String words = element.getAsString();
                if (!words.isEmpty()) {
                    runs.add(new JsonText(raw, words, link, quest, argb, bold, italic, underline));
                }
            }
            return;
        }
        if (element.isJsonArray()) {
            for (com.google.gson.JsonElement each : element.getAsJsonArray()) {
                collect(raw, each, argb, bold, italic, underline, link, quest, runs);
            }
            return;
        }
        com.google.gson.JsonObject object = element.getAsJsonObject();
        int ink = object.has("color") && object.get("color").isJsonPrimitive()
                ? colorOf(object.get("color").getAsString(), argb) : argb;
        // Vanilla's key is `underlined`; `strikethrough` degrades the way `&m` does, and
        // `obfuscated` has no run flag to land on, so it arrives as plain — both recorded.
        boolean ownBold = flagOf(object, "bold", bold);
        boolean ownItalic = flagOf(object, "italic", italic);
        boolean ownUnderline = flagOf(object, "underlined", underline)
                || flagOf(object, "strikethrough", false);
        String ownLink = link;
        String ownQuest = quest;
        if (object.has("clickEvent") && object.get("clickEvent").isJsonObject()) {
            com.google.gson.JsonObject event = object.getAsJsonObject("clickEvent");
            String action = event.has("action") && event.get("action").isJsonPrimitive()
                    ? event.get("action").getAsString() : "";
            String value = event.has("value") && event.get("value").isJsonPrimitive()
                    ? event.get("value").getAsString() : "";
            if (action.equals("open_url") && !value.isEmpty() && !value.startsWith("docs:")) {
                ownLink = value;
                ownQuest = "";
            }
            else if (action.equals("change_page") && !value.isEmpty()) {
                // The legacy repurposing: the value is a quest to open (`HEXID` or `HEXID/page`),
                // never a book page — see the record note.
                ownLink = "";
                ownQuest = value;
            }
            else if (!action.isEmpty()) {
                // A press Tenet has no reader for arrives as words with nowhere to press.
                ownLink = "";
                ownQuest = "";
            }
        }
        if (object.has("text")) {
            collect(raw, object.get("text"), ink, ownBold, ownItalic, ownUnderline, ownLink,
                    ownQuest, runs);
        }
        else if (object.has("translate")) {
            // A key with no fallback on hand: the key itself, which is what the game shows when a
            // translation is missing too.
            com.google.gson.JsonElement key = object.get("translate");
            String words = key.isJsonPrimitive() ? key.getAsString() : "";
            if (!words.isEmpty()) {
                runs.add(new JsonText(raw, words, ownLink, ownQuest, ink, ownBold, ownItalic,
                        ownUnderline));
            }
        }
        if (object.has("extra")) {
            collect(raw, object.get("extra"), ink, ownBold, ownItalic, ownUnderline, ownLink,
                    ownQuest, runs);
        }
    }

    /** A vanilla colour name or {@code #RRGGBB}, or the inherited ink when it names neither. */
    private static int colorOf(String name, int fallback) {
        if (name != null) {
            if (name.startsWith("#") && name.length() == 7 && isHex(name, 1, 6)) {
                return 0xFF000000 | Integer.parseUnsignedInt(name.substring(1), 16);
            }
            int slot = switch (name.toLowerCase(java.util.Locale.ROOT)) {
                case "black" -> 0;
                case "dark_blue" -> 1;
                case "dark_green" -> 2;
                case "dark_aqua" -> 3;
                case "dark_red" -> 4;
                case "dark_purple" -> 5;
                case "gold" -> 6;
                case "gray" -> 7;
                case "dark_gray" -> 8;
                case "blue" -> 9;
                case "green" -> 10;
                case "aqua" -> 11;
                case "red" -> 12;
                case "light_purple" -> 13;
                case "yellow" -> 14;
                case "white" -> 15;
                default -> -1;
            };
            if (slot >= 0) {
                return PALETTE[slot];
            }
        }
        return fallback;
    }

    /** A JSON true/false (or its string spelling) with the inherited flag when it names neither. */
    private static boolean flagOf(com.google.gson.JsonObject object, String key, boolean inherited) {
        if (!object.has(key) || !object.get(key).isJsonPrimitive()) {
            return inherited;
        }
        com.google.gson.JsonPrimitive value = object.getAsJsonPrimitive(key);
        if (value.isBoolean()) {
            return value.getAsBoolean();
        }
        if (value.isString()) {
            String word = value.getAsString().toLowerCase(java.util.Locale.ROOT);
            if (word.equals("true") || word.equals("false")) {
                return word.equals("true");
            }
        }
        return inherited;
    }
}
