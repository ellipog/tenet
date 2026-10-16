package dev.ellipog.tenet.client.dev;

import dev.ellipog.armature.client.render.GuiRenderer;

import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * A renderer that keeps what it was told to draw.
 *
 * <h2>Why the panel needs one</h2>
 *
 * <p>Because every fault this panel has had was a <b>drawing</b> fault that no arithmetic could see: the
 * radius row's arrows built as widgets at the list's coordinates and placed outside the panel; then drawn by
 * nobody at all; then a row that fell through the panel's dispatch into the switch-label branch and drew as a
 * bare label with no controls, while the hit test answered presses perfectly. Three reports from play, and the
 * question behind each of them is one line: <i>was anything drawn here?</i> A recorded call answers it. A
 * screen cannot be instantiated by a test; the panel can, and this is the part of {@link GuiRenderer} it needs
 * to be instantiated with.
 *
 * <p>No window, no font, no items. Text width is a stand-in — six pixels a character, which is what
 * Minecraft's own font is within a few tenths, and the arithmetic under test is position rather than
 * typesetting. The three methods that need a client answer "no" rather than throwing, so a test may draw a
 * panel that happens to contain an item or a player head.
 */
final class RecordingRenderer implements GuiRenderer {

    /** One filled rectangle, as it was drawn. */
    record Fill(int left, int top, int right, int bottom, int argb) {
    }

    /** One line of text, with the left edge it was drawn from. */
    record Drawn(String text, int x, int y, int argb) {
    }

    /** One drawn region of a file, as {@link #scaled} was told to draw it. */
    record Scaled(Identifier texture, int x, int y, int width, int height) {
    }

    /** One whole-file blit, as {@link #texture} was told to draw it. */
    record Textured(Identifier texture, int x, int y, int width, int height) {
    }

    /** One atlas-region draw, as {@link #sprite} was told to draw it. */
    record Sprited(Identifier sprite, int x, int y, int width, int height) {
    }

    private final List<Fill> fills = new ArrayList<>();
    private final List<Drawn> texts = new ArrayList<>();
    private final Map<Identifier, TextureSize> sizes = new LinkedHashMap<>();
    private final List<Scaled> scaled = new ArrayList<>();
    private final List<Textured> textures = new ArrayList<>();
    private final List<Sprited> sprites = new ArrayList<>();
    private int batches;

    @Override
    public void fill(int left, int top, int right, int bottom, int argb) {
        fills.add(new Fill(left, top, right, bottom, argb));
    }

    @Override
    public void text(String text, int x, int y, int argb) {
        texts.add(new Drawn(text, x, y, argb));
    }

    @Override
    public void shadowedText(String text, int x, int y, int argb, float scale) {
        // Recorded as a plain line, exactly as `styledText` is and for the same reason: the panels this
        // recorder exists for draw no shadowed text, and a caller that does still has to appear in the log.
        // An assertion about WHICH labels asked for a shadow, and at what size, belongs to
        // `client.render.RecordingRenderer`, which keeps the distinction as its own op.
        texts.add(new Drawn(text, x, y, argb));
    }

    @Override
    public void flush() {
    }

    @Override
    public <T> T batched(java.util.function.Supplier<T> draw) {
        // Recorded as a count, and the supplier runs: the panels this double exists for draw no batched
        // regions of their own, and a test that wants to assert the canvas is one batch reads the count.
        batches++;
        return draw.get();
    }

    @Override
    public int textWidth(String text) {
        return text.length() * 6;
    }

    @Override
    public void styledText(java.util.List<StyledRun> runs, int x, int y, int argb) {
        // The panels this recorder exists for draw no styled text -- the description's own recorder is
        // Armature's, where the runs are kept. Recorded as a plain line so a caller that does draw one still
        // appears in the log, which is what a recorder is for.
        StringBuilder whole = new StringBuilder();
        for (StyledRun run : runs) {
            whole.append(run.text());
        }
        texts.add(new Drawn(whole.toString(), x, y, argb));
    }

    @Override
    public int styledWidth(String text, boolean bold, boolean italic, float scale) {
        return Math.round(textWidth(text) * scale);
    }

    @Override
    public int lineHeight() {
        return 9;
    }

    @Override
    public boolean icon(ItemStack stack, int boxX, int boxY, int box) {
        return false;
    }

    @Override
    public boolean face(UUID player, int boxX, int boxY, int box) {
        return false;
    }

    @Override
    public void texture(Identifier texture, int x, int y, int width, int height) {
        // Recorded, like every other draw on this recorder: a node wearing a texture icon draws one
        // blit, and a test that wants to tell it from an item icon reads this list. (It used to be a
        // no-op on the grounds that no panel drew a texture; nodes do now.)
        textures.add(new Textured(texture, x, y, width, height));
    }

    /**
     * {@inheritDoc}
     *
     * <p>Empty by default, because a PNG's header is not a thing a recorder can read. A test that wants
     * the image branch of the texture row supplies a size through {@link #putTextureSize} rather than
     * pretending the resource manager answered -- and the row's own test does exactly that.
     */
    @Override
    public Optional<TextureSize> textureSize(Identifier texture) {
        return Optional.ofNullable(sizes.get(texture));
    }

    @Override
    public void scaled(Identifier texture, int x, int y, int width, int height,
                       float u, float v, int sourceWidth, int sourceHeight,
                       int textureWidth, int textureHeight, int argb) {
        scaled.add(new Scaled(texture, x, y, width, height));
    }

    /** Teaches the recorder one file's size, so a thumbnail has something to draw at its aspect. */
    void putTextureSize(Identifier texture, int width, int height) {
        sizes.put(texture, new TextureSize(width, height));
    }

    /**
     * {@inheritDoc}
     *
     * <p>Recorded, like every other draw on this recorder: nodes wear atlas sprites now, so a
     * recorder that dropped them could not tell a sprite icon from no icon. Kept apart from
     * {@link #texture} because the two lookups fail differently, like the render recorder's.
     */
    @Override
    public void sprite(Identifier atlasSprite, int x, int y, int width, int height, int argb) {
        sprites.add(new Sprited(atlasSprite, x, y, width, height));
    }

    @Override
    public void guiSprite(Identifier guiSprite, int x, int y, int width, int height) {
        // Recorded with the atlas draws: the panels this recorder exists for draw none yet, and a
        // caller that does still has to appear in the log. A GUI sprite carries no tint, so the
        // tintless Sprited record holds everything it chooses.
        sprites.add(new Sprited(guiSprite, x, y, width, height));
    }

    @Override
    public boolean blur(float partialTick) {
        return false;
    }

    @Override
    public Scoped clip(int left, int top, int right, int bottom) {
        return () -> {
        };
    }

    /**
     * {@inheritDoc}
     *
     * <p>A no-op scope, like {@link #clip}: nothing this recorder exists for turns its drawing. A test that
     * needs to assert a picture was turned, and about which pivot, reads
     * {@code client.render.RecordingRenderer}, whose turn is a recorded op with its own angle.
     */
    @Override
    public Scoped turned(int pivotX, int pivotY, float degrees) {
        return () -> {
        };
    }

    // ------------------------------------------------------------------
    // What a test asks
    // ------------------------------------------------------------------

    /**
     * Whether some filled rectangle covers this one.
     *
     * <p>Covered rather than exactly equal, because a surface with a radius is drawn as spans rather than as
     * one rectangle: a test that demanded one exact fill would pass at radius zero and fail at six, which is
     * a test that would rather be about the theme than about the control.
     */
    boolean covered(int left, int top, int right, int bottom) {
        return fills.stream().anyMatch(fill -> fill.left() <= left && fill.top() <= top
                && fill.right() >= right && fill.bottom() >= bottom);
    }

    /** Whether a line of text was drawn with its left edge and baseline inside this rectangle. */
    boolean wroteWithin(String text, int left, int top, int right, int bottom) {
        return texts.stream().anyMatch(drawn -> drawn.text().equals(text) && drawn.x() >= left
                && drawn.x() <= right && drawn.y() >= top - 8 && drawn.y() <= bottom);
    }

    boolean wrote(String text) {
        return texts.stream().anyMatch(drawn -> drawn.text().equals(text));
    }

    List<Fill> fills() {
        return List.copyOf(fills);
    }

    /** The regions of files drawn through {@link #scaled}, in order. */
    List<Scaled> scaled() {
        return List.copyOf(scaled);
    }

    /** The whole-file blits drawn through {@link #texture}, in order. */
    List<Textured> textures() {
        return List.copyOf(textures);
    }

    /** The atlas regions drawn through {@link #sprite}, in order. */
    List<Sprited> sprites() {
        return List.copyOf(sprites);
    }

    List<Drawn> texts() {
        return List.copyOf(texts);
    }

    /** How many batched regions were opened. The canvas must be exactly one. */
    int batchCount() {
        return batches;
    }

    /** Everything drawn, for an assertion's message: a missing control is not visible in a false. */
    String describe() {
        return "fills=" + fills + " texts=" + texts + " scaled=" + scaled + " textures=" + textures;
    }
}
