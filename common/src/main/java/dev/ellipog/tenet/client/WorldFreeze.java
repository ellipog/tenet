package dev.ellipog.tenet.client;

import dev.ellipog.tenet.Constants;

import dev.ellipog.armature.api.ArmatureApi;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.platform.GlStateManager;

import net.minecraft.client.Minecraft;

import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;

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
 * game still draws every chunk, entity and particle behind the book on every frame, and on a real chapter
 * that was as much time as the book itself. So the first frame drawn under the book is kept, and later
 * frames copy it into the screen instead of drawing the world again. The copy is one framebuffer blit on
 * the GPU.
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
 * <p>{@code GameRendererFreezeMixin} asks {@link #drawCaptured} in place of drawing the world, then hands a
 * normally drawn frame to {@link #afterLevel}. The hook is optional: if another mod has taken the draw
 * call, the world is simply drawn every frame.
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
        return wanted(mode, minecraft.screen instanceof QuestBookScreen, minecraft.isPaused());
    }

    /**
     * In place of drawing the world: copies the capture into the screen and answers true, or answers false
     * when this frame draws the world as usual.
     */
    public static boolean drawCaptured(RenderTarget main) {
        TextureTarget held = capture;
        if (held == null || held.width != main.width || held.height != main.height || !wanted()) {
            return false;
        }
        try {
            copyColour(held, main);
        }
        catch (RuntimeException e) {
            Constants.LOG.warn("the world behind the book could not be copied, so it is drawn every frame"
                    + " until the book is closed.", e);
            release();
            return false;
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
                held = new TextureTarget(main.width, main.height, false, Minecraft.ON_OSX);
                capture = held;
            }
            copyColour(main, held);
            // Back to the screen, which is where the frame goes on drawing.
            main.bindWrite(false);
        }
        catch (RuntimeException e) {
            Constants.LOG.warn("the world behind the book could not be kept, so it is drawn every frame.", e);
            release();
            mode = Mode.OFF;
        }
    }

    /**
     * Copies one target's colour into another of the same size, as {@code RenderTarget.copyDepthFrom} copies
     * depth: a framebuffer blit, which never leaves the GPU. Leaves {@code into} bound for drawing.
     */
    private static void copyColour(RenderTarget from, RenderTarget into) {
        GlStateManager._glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, from.frameBufferId);
        GlStateManager._glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, into.frameBufferId);
        GlStateManager._glBlitFrameBuffer(0, 0, from.width, from.height, 0, 0, into.width, into.height,
                GL11.GL_COLOR_BUFFER_BIT, GL11.GL_NEAREST);
        GlStateManager._glBindFramebuffer(GL30.GL_FRAMEBUFFER, 0);
        into.bindWrite(false);
    }

    /** Drops the capture. The book calls this when it closes, so a reopened book never shows an old world. */
    public static void release() {
        TextureTarget held = capture;
        capture = null;
        if (held != null) {
            held.destroyBuffers();
        }
    }
}
