package dev.ellipog.tenet.client.dev;

import java.util.Objects;

/**
 * A link-target pick in flight: the link being edited and the chapter holding it.
 *
 * <h2>Why this is captured rather than read when the click lands</h2>
 *
 * <p>Arming a pick leaves the chapter tab open on the link, and the author is then free to switch to
 * another chapter to find the quest they mean — which is the whole reason a pick exists instead of a
 * text field. By the time the click lands, the chapter on screen may no longer hold the link, so the
 * link and the chapter the operation must be sent to travel with the pick, and the write goes to the
 * chapter that was being edited however far the sidebar has moved since. That is
 * {@link DependencyPick}'s own argument, and a target pick is the same gesture about one value
 * rather than a list.
 *
 * <h2>Why there are no refusals</h2>
 *
 * <p>Because any quest is a legal target: the loader's own check reports a name that resolves to
 * nothing, with the file and the line, so the pick needs no rules of its own. A dependency pick
 * refuses itself and its duplicates because those would break the progression; a link target
 * breaks nothing — the worst a wrong one does is mirror nothing, loudly.
 */
public record LinkPick(String link, String chapter) {

    /**
     * The link a pick names when there is none yet: Add link arms the pick first and makes the link when
     * it lands, because a link with no target is one the server refuses outright -- the old Add link
     * sent exactly that, and every press of it ended in a toast quoting the validator.
     */
    public static final String NEW = "";

    /** Whether this pick makes its link when it lands, rather than retargeting one. */
    public boolean creates() {
        return link.equals(NEW);
    }

    public LinkPick {
        Objects.requireNonNull(link, "link");
        Objects.requireNonNull(chapter, "chapter");
    }
}
