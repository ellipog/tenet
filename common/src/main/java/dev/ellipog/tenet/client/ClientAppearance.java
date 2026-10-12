package dev.ellipog.tenet.client;

import dev.ellipog.armature.api.ArmatureApi;
import dev.ellipog.armature.client.Look;
import dev.ellipog.tenet.Constants;

import java.nio.file.Path;

/**
 * Tenet's own appearance: where its settings file and its themes live.
 *
 * <h2>Why this exists rather than a call into the library</h2>
 *
 * <p>Because the paths are the mod's. Armature used to resolve them itself -- a settings file and a theme
 * directory under {@code config/armature/} -- which meant any two mods built on it shared one player's one
 * look, so a quest book in Tome and a panel in Modern was not expressible. A library owns things and no
 * choices; this is Tenet's choice, in Tenet's directory, and Armature is handed the paths.
 *
 * <p>It is the same shape as {@link DevMode} deliberately: two settings, one file each, both under
 * {@code config/tenet/}, both read by the same two lines of each loader's client initialiser.
 */
public final class ClientAppearance {

    /** The settings file, beside {@link DevMode}'s. */
    public static final String FILE_NAME = "appearance.json";

    /** Where a theme saved from the editor is written. */
    public static final String THEMES_DIRECTORY = "themes";

    /**
     * Tenet's look: the one instance this mod draws with.
     *
     * <p>An instance rather than a static on the library's class, and that is the whole of the last
     * correction: a library that holds *the* current theme makes every mod built on it share one player's
     * one choice, so two different looks in one client are not expressible. Here, they are -- another mod
     * owns another {@code Look}, and neither can see the other's.
     *
     * <p>Static <i>here</i> is fine and is the point: it is this mod's own field, in this mod's own class.
     * The rule is who owns it, not whether it is reachable.
     */
    public static final Look LOOK = new Look();

    private ClientAppearance() {
    }

    /**
     * Reads Tenet's appearance, and tells Armature where to write a theme it saves.
     *
     * <p>Called once per client, from the loader's client initialiser, for the reason {@code DevMode}'s
     * note gives: a setting read late is a setting that is wrong for the first second of a session.
     */
    public static void loadFromConfig() {
        Path config;
        try {
            config = ArmatureApi.platform().configDir(Constants.MOD_ID);
        }
        catch (RuntimeException e) {
            Constants.LOG.warn("the platform layer was not ready, so the appearance was not read."
                    + " The default theme is in use.", e);
            return;
        }
        ClientAppearance.LOOK.load(config.resolve(FILE_NAME), config.resolve(THEMES_DIRECTORY));
    }
}
