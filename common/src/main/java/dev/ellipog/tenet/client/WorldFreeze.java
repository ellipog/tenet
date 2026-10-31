package dev.ellipog.tenet.client;

import dev.ellipog.tenet.Constants;

import dev.ellipog.armature.api.ArmatureApi;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.renderpearl.api.GpuFormat;

import net.minecraft.client.Minecraft;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/**
 * The world behind an open book, drawn once and then shown as a picture.
 *
 * <h2>Why</h2>
 *
 * <p>The book covers the world with a dim wash, and while the game is paused nothing in it moves. The
 * game still extracts and draws every chunk, entity and particle behind the book on every frame, and on a
 * real chapter that was as much time as the book itself. So the first frame drawn under the book is kept,
 * and later frames copy it into the screen instead of drawing the world again. The copy is one texture
 * copy on the GPU, and the world's extraction is skipped too.
 *
 * <h2>The three modes, from {@code config/tenet/backdrop.json}</h2>
 *
 * <ul>
 *   <li><b>{@code "paused"}</b>, the default: only while the game is paused, which is single player with
 *       the book's pause on. Nothing behind the book moves then, so the picture is the world.</li>
 *   <li><b>{@code "always"}</b>: whenever the book is open, on a server too. The world behind it stands
 *       still until the book closes.</li>
 *   <li><b>{@code "off"}</b>: the world is drawn every frame, as before.</li>
 * </ul>
 *
 * <p>The capture is dropped as soon as a frame is drawn normally (the book closed, or the game stopped
 * being paused), and taken again when the window changes size, so a stale picture never outlives the
 * moment it was true.
 *
 * <h2>How it is wired</h2>
 *
 * <p>{@code GameRendererFreezeMixin} asks {@link #extractLevel} before the world's extraction and
 * {@link #drawCaptured} in place of drawing it, then hands a normally drawn frame to {@link #afterLevel}.
 * Both hooks are optional: if another mod has taken the draw call, the world is simply drawn every frame.
 */
public final class WorldFreeze {

    /** The client file this reads, beside {@code canvas.json}. */
    public static final String FILE_NAME = "backdrop.json";

    /** When the world behind the book is held still. */
    public enum Mode {
        OFF, PAUSED, ALWAYS;

        /** The word the file uses, lower case. */
        public String word() {
            return name().toLowerCase(Locale.ROOT);
        }

        /** The mode a word names, in any case, or null for a word that names none. */
        public static Mode of(String word) {
            for (Mode mode : values()) {
                if (mode.word().equalsIgnoreCase(word.trim())) {
                    return mode;
                }
            }
            return null;
        }
    }

    public static final Mode DEFAULT_MODE = Mode.PAUSED;

    private static Mode mode = DEFAULT_MODE;

    /** Set by the draw hook the first time it runs: the extraction is only skipped when the draw can follow. */
    private static boolean drawHookSeen;

    /** Whether this frame skipped the world's extraction, and so must not draw the world from it. */
    private static boolean frozenThisFrame;

    private static TextureTarget capture;

    private WorldFreeze() {
    }

    public static Mode mode() {
        return mode;
    }

    /** Reads the mode from the platform's config directory. Called once by each loader's client. */
    public static void loadFromConfig() {
        Path path = null;
        try {
            path = ArmatureApi.platform().configDir(Constants.MOD_ID).resolve(FILE_NAME);
        }
        catch (RuntimeException e) {
            Constants.LOG.warn("the platform layer was not ready, so {} was not read. The world behind"
                    + " the book is held still while the game is paused.", FILE_NAME, e);
        }
        if (path != null) {
            load(path);
        }
    }

    /** Reads the mode from one file, leaving the default in force when it says nothing usable. */
    public static void load(Path path) {
        mode = DEFAULT_MODE;
        if (!Files.isRegularFile(path)) {
            return;
        }
        try {
            mode = parse(Files.readString(path, StandardCharsets.UTF_8));
        }
        catch (IOException | RuntimeException e) {
            Constants.LOG.warn("{} could not be read, so the world behind the book is held still while the"
                    + " game is paused. Deleting the file will stop this message.", path, e);
        }
    }

    /**
     * The mode a file's text names: its {@code "freeze"} field, or the default when the field is missing,
     * not a string, or not one of the three words.
     *
     * @throws com.google.gson.JsonSyntaxException if the text is not JSON at all; {@link #load} catches it
     */
    public static Mode parse(String json) {
        JsonObject root = JsonParser.parseString(json).getAsJsonObject();
        if (!root.has("freeze") || !root.get("freeze").isJsonPrimitive()
                || !root.getAsJsonPrimitive("freeze").isString()) {
            return DEFAULT_MODE;
        }
        Mode named = Mode.of(root.get("freeze").getAsString());
        return named == null ? DEFAULT_MODE : named;
    }

    /** For a test: the mode as if a file had said it. */
    static void set(Mode next) {
        mode = next == null ? DEFAULT_MODE : next;
    }

    /** Whether the mode asks for a still world, given what the screen and the game are doing. */
    static boolean wanted(Mode mode, boolean bookOpen, boolean paused) {
        return bookOpen && switch (mode) {
            case OFF -> false;
            case PAUSED -> paused;
            case ALWAYS -> true;
        };
    }

    private static boolean wanted() {
        Minecraft minecraft = Minecraft.getInstance();
        return wanted(mode, minecraft.gui.screen() instanceof QuestBookScreen, minecraft.isPaused());
    }

    /**
     * Whether this frame should extract the world. Asked once per frame, before the extraction, and the
     * answer holds for the draw that follows: a frame that skips the extraction shows the capture.
     */
    public static boolean extractLevel() {
        Minecraft minecraft = Minecraft.getInstance();
        TextureTarget held = capture;
        frozenThisFrame = drawHookSeen && held != null && wanted()
                && held.width == minecraft.getWindow().getWidth() && held.height == minecraft.getWindow().getHeight();
        return !frozenThisFrame;
    }

    /**
     * In place of drawing the world: copies the capture into the screen and answers true, or answers false
     * when this frame draws the world as usual.
     */
    public static boolean drawCaptured(RenderTarget main) {
        drawHookSeen = true;
        if (!frozenThisFrame) {
            return false;
        }
        frozenThisFrame = false;
        TextureTarget held = capture;
        if (held == null || held.width != main.width || held.height != main.height) {
            // The extraction was skipped and the size moved between the two halves of the frame. Nothing
            // true to draw: this one frame shows the clear colour, and the next draws the world again.
            release();
            return true;
        }
        try {
            main.copyColorFrom(held);
        }
        catch (RuntimeException e) {
            Constants.LOG.warn("the world behind the book could not be copied, so it is drawn every frame"
                    + " until the book is closed.", e);
            release();
        }
        return true;
    }

    /** After the world was drawn normally: keeps a copy when the next frames may show it, else lets go. */
    public static void afterLevel(RenderTarget main) {
        if (!wanted()) {
            release();
            return;
        }
        try {
            TextureTarget held = capture;
            if (held == null || held.width != main.width || held.height != main.height) {
                release();
                held = new TextureTarget("Tenet book backdrop", main.width, main.height, GpuFormat.RGBA8_UNORM, null);
                capture = held;
            }
            held.copyColorFrom(main);
        }
        catch (RuntimeException e) {
            Constants.LOG.warn("the world behind the book could not be kept, so it is drawn every frame.", e);
            release();
            mode = Mode.OFF;
        }
    }

    /** Drops the capture. The book calls this when it closes, so a reopened book never shows an old world. */
    public static void release() {
        TextureTarget held = capture;
        capture = null;
        frozenThisFrame = false;
        if (held != null) {
            held.destroyBuffers();
        }
    }
}
