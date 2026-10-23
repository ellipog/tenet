package dev.ellipog.tenet.gametest;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import com.mojang.blaze3d.platform.InputConstants;

import dev.ellipog.tenet.client.DevMode;
import dev.ellipog.tenet.client.QuestBookScreen;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.TestInput;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;

import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.screens.Screen;

/**
 * Walks a real client through Tenet's screens and photographs each one.
 *
 * <p>The walk is a script rather than code, so a new shot is an edited text file and a rerun, not a
 * rebuild. The script is the file named by {@code TENET_TOUR}, or {@link #DEFAULT} when that is unset.
 * One command per line; {@code #} starts a comment. Coordinates are GUI pixels at the current scale,
 * the space every Tenet layout class works in.
 *
 * <pre>
 *   world op|player         first line: whether the world allows commands, which is whether the
 *                           player is an author. Read before the world exists, so it is not a step.
 *   scale N                 GUI scale (the window is 1920x1080)
 *   key NAME                press a key: B, H, E, ESCAPE, TAB, ENTER, LEFT ... (InputConstants.KEY_*)
 *   ctrl NAME | shift NAME  the same, with the modifier held
 *   move X Y                put the pointer there (hover)
 *   click X Y [right]       move there and press
 *   press LABEL [right]     press the widget whose label is exactly LABEL
 *   node QUEST [right|hover] press (or hover) a quest's node on the canvas
 *   drag X1 Y1 X2 Y2        hold the left button across
 *   scroll N                wheel notches, positive is up
 *   type TEXT               type characters
 *   wait TICKS
 *   shot NAME               screenshot to screenshots/NAME.png
 *   button TEXT             press the vanilla widget with that label, if there is one
 *   dev on|off              edit mode
 *   advanced on|off         the editor's Advanced depth
 *   open QUEST_ID           open the book on a quest
 *   cmd COMMAND             run a server command (no slash)
 *   book                    open the book unless it is already the screen
 *   close                   close every screen
 *   dump                    print the screen's class, its widgets and edit permission to the log
 * </pre>
 *
 * <p>A step that throws is logged and skipped, so one wrong coordinate costs one picture, not the run.
 */
public final class TenetUiTour implements FabricClientGameTest {

    private static final String DEFAULT = """
            scale 2
            wait 40
            shot hud_world
            key B
            wait 20
            shot book
            dump
            """;

    @Override
    public void runTest(ClientGameTestContext context) {
        context.getInput().resizeWindow(1920, 1080);
        List<String> script = script();
        boolean op = script.stream().anyMatch(line -> line.strip().equals("world op"));
        try (TestSingleplayerContext world = context.worldBuilder()
                .setUseConsistentSettings(true)
                .adjustSettings(state -> {
                    state.setSeed("20261022");
                    state.setAllowCommands(op);
                })
                .create()) {
            world.getConnection().waitForChunksDownload();
            world.getConnection().waitForChunksRender();
            String name = context.computeOnClient(client -> client.getUser().getName());
            world.getServer().runCommand("gamemode creative " + name);
            world.getServer().runCommand("time set noon");
            world.getServer().runCommand("weather clear");
            context.waitTicks(20);

            for (String raw : script) {
                String line = raw.strip();
                if (line.isEmpty() || line.startsWith("#") || line.startsWith("world ")) {
                    continue;
                }
                try {
                    step(context, world, line);
                } catch (Throwable failure) {
                    System.out.println("TOUR: step failed: " + line + " -> " + failure);
                }
            }
        }
    }

    private static List<String> script() {
        String path = System.getenv("TENET_TOUR");
        if (path == null || path.isBlank()) {
            return DEFAULT.lines().toList();
        }
        try {
            return Files.readAllLines(Path.of(path));
        } catch (IOException failure) {
            throw new IllegalStateException("cannot read TENET_TOUR " + path, failure);
        }
    }

    private static void step(ClientGameTestContext context, TestSingleplayerContext world, String line) {
        String[] word = line.split("\\s+", 2);
        String rest = word.length > 1 ? word[1] : "";
        String[] arg = rest.isEmpty() ? new String[0] : rest.split("\\s+");
        TestInput input = context.getInput();
        int scale = context.computeOnClient(client -> client.getWindow().getGuiScale());
        System.out.println("TOUR: " + line);
        switch (word[0].toLowerCase(Locale.ROOT)) {
            case "scale" -> {
                int next = Integer.parseInt(arg[0]);
                context.runOnClient(client -> {
                    client.options.guiScale().set(next);
                    client.resizeGui();
                });
                context.waitTicks(2);
            }
            case "key" -> input.pressKey(key(arg[0]));
            case "ctrl" -> {
                input.holdControl();
                input.pressKey(key(arg[0]));
                input.releaseControl();
            }
            case "shift" -> {
                input.holdShift();
                input.pressKey(key(arg[0]));
                input.releaseShift();
            }
            case "move" -> {
                input.setCursorPos(Double.parseDouble(arg[0]) * scale, Double.parseDouble(arg[1]) * scale);
                context.waitTicks(2);
            }
            case "click" -> click(context, Double.parseDouble(arg[0]) * scale, Double.parseDouble(arg[1]) * scale,
                    arg.length > 2 && arg[2].equals("right"));
            case "press" -> {
                boolean right = rest.endsWith(" right");
                String label = right ? rest.substring(0, rest.length() - " right".length()) : rest;
                int[] at = context.computeOnClient(client -> widgetCentre(client, label));
                if (at == null) {
                    throw new IllegalStateException("no widget labelled '" + label + "'");
                }
                click(context, at[0] * (double) scale, at[1] * (double) scale, right);
            }
            case "node" -> {
                int[] at = context.computeOnClient(client -> client.gui.screen() instanceof QuestBookScreen book
                        ? book.nodeOnScreen(arg[0]) : null);
                if (at == null) {
                    throw new IllegalStateException("no node " + arg[0]);
                }
                if (arg.length > 1 && arg[1].equals("hover")) {
                    input.setCursorPos(at[0] * (double) scale, at[1] * (double) scale);
                    context.waitTicks(10);
                }
                else {
                    click(context, at[0] * (double) scale, at[1] * (double) scale,
                            arg.length > 1 && arg[1].equals("right"));
                }
            }
            case "drag" -> {
                double x1 = Double.parseDouble(arg[0]) * scale;
                double y1 = Double.parseDouble(arg[1]) * scale;
                double x2 = Double.parseDouble(arg[2]) * scale;
                double y2 = Double.parseDouble(arg[3]) * scale;
                input.setCursorPos(x1, y1);
                context.waitTick();
                input.holdMouse(InputConstants.MOUSE_BUTTON_LEFT);
                for (int i = 1; i <= 10; i++) {
                    input.setCursorPos(x1 + (x2 - x1) * i / 10, y1 + (y2 - y1) * i / 10);
                    context.waitTick();
                }
                input.releaseMouse(InputConstants.MOUSE_BUTTON_LEFT);
                context.waitTicks(2);
            }
            case "scroll" -> {
                input.scroll(Double.parseDouble(arg[0]));
                context.waitTicks(2);
            }
            case "type" -> input.typeChars(rest);
            case "wait" -> context.waitTicks(Integer.parseInt(arg[0]));
            case "shot" -> {
                context.waitTicks(2);
                context.takeScreenshot(arg[0]);
            }
            case "button" -> System.out.println("TOUR: button " + rest + " pressed=" + context.tryClickScreenButton(rest));
            case "dev" -> context.runOnClient(client -> DevMode.setOn(arg[0].equals("on")));
            case "advanced" -> context.runOnClient(client -> DevMode.setAdvanced(arg[0].equals("on")));
            case "open" -> {
                context.runOnClient(client -> QuestBookScreen.openOn(arg[0]));
                context.waitTicks(10);
            }
            case "cmd" -> world.getServer().runCommand(rest);
            case "book" -> {
                if (!context.computeOnClient(client -> client.gui.screen() instanceof QuestBookScreen)) {
                    context.setScreen(() -> null);
                    context.waitTick();
                    input.pressKey(InputConstants.KEY_B);
                    context.waitTicks(20);
                }
            }
            case "close" -> {
                context.setScreen(() -> null);
                context.waitTicks(2);
            }
            case "dump" -> System.out.println("TOUR: " + context.computeOnClient(TenetUiTour::describe));
            default -> System.out.println("TOUR: unknown command " + word[0]);
        }
    }

    /** Held across ticks like a hand would: Armature's buttons act on the release. */
    private static void click(ClientGameTestContext context, double x, double y, boolean right) {
        TestInput input = context.getInput();
        input.setCursorPos(x, y);
        context.waitTick();
        int button = right ? InputConstants.MOUSE_BUTTON_RIGHT : InputConstants.MOUSE_BUTTON_LEFT;
        input.holdMouse(button);
        context.waitTicks(3);
        input.releaseMouse(button);
        context.waitTicks(2);
    }

    /** The centre of the first widget whose label is exactly this text, in GUI pixels, or null. */
    private static int[] widgetCentre(net.minecraft.client.Minecraft client, String label) {
        Screen screen = client.gui.screen();
        if (screen == null) {
            return null;
        }
        for (var child : screen.children()) {
            if (child instanceof AbstractWidget widget && widget.visible
                    && widget.getMessage().getString().strip().equals(label)) {
                return new int[] {widget.getX() + widget.getWidth() / 2, widget.getY() + widget.getHeight() / 2};
            }
        }
        return null;
    }

    private static String describe(net.minecraft.client.Minecraft client) {
        Screen screen = client.gui.screen();
        String perm = "edit=" + dev.ellipog.tenet.QuestAuthority.mayEditClient(client) + " dev=" + DevMode.on()
                + " advanced=" + DevMode.advanced();
        System.out.println("TOUR: " + perm);
        if (screen == null) {
            return "no screen; gui " + client.getWindow().getGuiScaledWidth() + "x" + client.getWindow().getGuiScaledHeight();
        }
        List<String> parts = new ArrayList<>();
        parts.add(screen.getClass().getName() + " gui " + client.getWindow().getGuiScaledWidth() + "x"
                + client.getWindow().getGuiScaledHeight());
        for (var child : screen.children()) {
            if (child instanceof AbstractWidget widget) {
                parts.add("  [" + widget.getX() + "," + widget.getY() + " " + widget.getWidth() + "x" + widget.getHeight()
                        + "] " + widget.getClass().getSimpleName() + " '" + widget.getMessage().getString() + "'");
            } else {
                parts.add("  " + child.getClass().getSimpleName());
            }
        }
        return String.join("\n", parts);
    }

    private static int key(String name) {
        String upper = name.toUpperCase(Locale.ROOT);
        try {
            Field field = InputConstants.class.getField("KEY_" + upper);
            return field.getInt(null);
        } catch (ReflectiveOperationException missing) {
            throw new IllegalArgumentException("no key " + name, missing);
        }
    }
}
