package dev.ellipog.tenet.client.viewer;

import dev.ellipog.tenet.Constants;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * The seam's entry point: where a mod with quests hands its content in, and where the tick lives
 * that keeps it current and lets the one static viewer catch up.
 *
 * <h2>Who calls what</h2>
 *
 * <p>The content side calls {@link #install} once from the client initialiser. The loaders call
 * {@link #tick()} once per client tick; it forwards to the content so it can notice its own world
 * changing, then to the chosen adapter if it needs a tick of its own. Everything else -- registering
 * categories, drawing a page, deciding to reload -- happens inside a viewer's own discovery of
 * this mod's plugin class, and that class asks {@link Viewers#mayInstall} first.
 *
 * <h2>The ticker list and the class-loading rule</h2>
 *
 * <p>A ticker entry names a <i>holder</i> class, never a viewer's plugin class: a compile-time
 * {@code MOD_ID} constant for the probe, a supplier for the factory so the holder is not loaded
 * before it is asked for, and the instance field typed as {@link ViewerAdapter} so resolving the
 * field needs no viewer type either. That is the same three-part arrangement a soft adapter uses,
 * and it is the difference between "a client without the viewer loses nothing" and a
 * {@code NoClassDefFoundError} at construction. The list is hand-written and ordered like
 * {@link Viewers.Viewer}; it starts empty because the first adapter that needs a tick adds its own
 * entry.
 */
public final class Integrations {

    /**
     * Something that runs on the client tick on behalf of one viewer.
     *
     * <p>Only viewers whose registration is static need one: JEI and REI ask for their content at
     * lookup time, so they have nothing to keep in step. A no-op implementation is a legitimate
     * adapter if that ever changes.
     */
    public interface ViewerAdapter {

        void tick();
    }

    /** One ticker: the viewer it belongs to, its holder's probe id, and how to build the holder. */
    private record Ticker(Viewers.Viewer viewer, String modId, Supplier<ViewerAdapter> factory) {
    }

    /**
     * The tickers, in the same order as {@link Viewers.Viewer}'s priority.
     *
     * <p>Empty today: JEI and REI ask for their content at lookup time and have nothing to keep in
     * step. (EMI was the one viewer with a tick of its own -- its registration is static -- and its
     * entry went with the adapter on the 26.3 port. If a tick-needing adapter returns, its entry
     * goes back here.)
     */
    private static final List<Ticker> TICKERS = List.of();

    private static volatile QuestContent content;

    private Integrations() {
    }

    /**
     * Installs the content a viewer draws. Called once, from the client initialiser, for both
     * loaders -- whichever loader runs, it is the same seam.
     */
    public static void install(QuestContent installed) {
        Objects.requireNonNull(installed, "content");
        if (content != null && content != installed) {
            Constants.LOG.debug("recipe-viewer content replaced by {}",
                    installed.getClass().getName());
        } else {
            Constants.LOG.debug("recipe-viewer content installed by {}",
                    installed.getClass().getName());
        }
        content = installed;
    }

    /** The installed content, if any. Empty means no mod on this client has quests to show. */
    public static Optional<QuestContent> content() {
        return Optional.ofNullable(content);
    }

    /**
     * The client tick. Cheap: no content, no work; with content, the content's own revision check
     * decides whether anything happens, and at most the one chosen viewer's adapter runs.
     */
    public static void tick() {
        QuestContent installed = content;
        if (installed == null) {
            return;
        }
        installed.tick();
        for (Ticker ticker : TICKERS) {
            if (!Viewers.mayInstall(ticker.viewer())) {
                continue;
            }
            ticker.factory().get().tick();
            return;
        }
    }
}
