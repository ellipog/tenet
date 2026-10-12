package dev.ellipog.tenet.client;

/**
 * The first-run intro: a guided making of one chapter, one quest, one task and one reward.
 *
 * <h2>What it is, and what it is not</h2>
 *
 * <p>A "do it with me" tour inside the quest book, not a popup essay. Each rung spotlights the
 * real control — the sidebar's {@code + Chapter} strip, the canvas, the quest card's add rows —
 * and advances when the author actually does it: the chapter rung on the tree gaining a chapter,
 * the quest rung on the tree gaining a quest or the server naming the id it minted, the task and
 * reward rungs on the next accepted chapter edit. Every rung also has Next and Skip, because a
 * gate that cannot be walked around is a trap when the sync lags.
 *
 * <h2>Where the state lives</h2>
 *
 * <p>Here, asstatics, because the screen is rebuilt constantly and the tour must survive a close
 * and reopen: closing the book mid-tour keeps the rung, and reopening resumes it. The durable
 * half — whether the tour has been finished or dismissed for good — is
 * {@link DevMode}'s {@code guideSeen}, in {@code config/tenet/client.json} beside every other
 * client preference. A session suppression covers a Skip: without it the empty-book trigger
 * below would re-fire on the very next tick.
 *
 * <h2>When it starts</h2>
 *
 * <p>On its own, only for an author facing an empty book: edit permission, edit mode on, no
 * player preview, the tree arrived, no chapters, and the flag unseen. That is the whole of the
 * nag window — the moment a chapter exists the trigger goes quiet forever, seen or not.
 *
 * <p>By ask, through {@code /tenet intro}: the payload handler parks {@link #requestFresh},
 * the next tick clears the seen flag and starts at the first rung that is still undone — a book
 * with content skips straight to making a task, and a finished book lands on the done card.
 *
 * <h2>When it ends</h2>
 *
 * <p>Doing the last rung finishes it and marks it seen: the tour earned its keep. Dismissing the
 * done card marks it seen too — walked or skipped, a finished tour does not return. Skipping
 * mid-tour only suppresses this session, so a waved-away tour on a still-empty book is back
 * next session rather than gone on a misclick. Losing edit permission or entering the player
 * preview drops the tour silently: it resumes where it was, because no rung was unlearned.
 *
 * <h2>How exact the gates are</h2>
 *
 * <p>Advisory rather than exact, and that is stated rather than hidden. The chapter and quest
 * rungs read the tree the canvas reads, which is exact. The task and reward rungs read the next
 * accepted chapter edit, which is usually the insert the rung just coached — and sometimes an
 * unrelated rename the author made instead, which advances the tour one rung early. The tour is
 * a coach, not a lock: nothing is gated on it, every rung can be skipped, and an early advance
 * costs one tooltip.
 */
public final class IntroGuide {

    /** One rung of the first quest: chapter, quest node, task, reward, and the letting-go. */
    public enum Step {
        CHAPTER,
        QUEST,
        TASK,
        REWARD,
        DONE
    }

    private static boolean freshRequest;
    private static boolean active;
    private static Step step = Step.CHAPTER;
    private static boolean completedByDoing;
    private static boolean sessionSuppressed;

    private IntroGuide() {
    }

    /**
     * Parks a fresh-start ask from {@code /tenet intro}, for the next tick to consume.
     *
     * <p>Parked rather than started, like the preview ask beside it: the handler cannot assume
     * the book is open, and the rung to start on needs the tree counts only the tick has.
     */
    public static void requestFresh() {
        freshRequest = true;
    }

    /** Whether the tour is currently walking an author through the book. */
    public static boolean isActive() {
        return active;
    }

    /** The rung showing, while active. */
    public static Step step() {
        return step;
    }

    /** Whether the done card was earned by making the quest rather than skipping to it. */
    public static boolean completedByDoing() {
        return completedByDoing;
    }

    /**
     * The tick's evaluation: start asked-for and empty-book tours, drop tours whose permission
     * or preview changed, and advance the rungs the tree counts satisfy.
     *
     * @param hasPermission whether the viewer may edit at all
     * @param devOn         whether edit mode is latched on
     * @param previewing    whether the player's view is showing instead of the editor
     * @param treeArrived   whether the server's tree has arrived — even an empty one
     * @param chapters      how many chapters the tree holds
     * @param quests        how many quests the tree holds
     */
    public static void tick(boolean hasPermission, boolean devOn, boolean previewing,
            boolean treeArrived, int chapters, int quests) {
        if (!active) {
            if (freshRequest) {
                freshRequest = false;
                sessionSuppressed = false;
                DevMode.setGuideSeen(false);
                start(chapters, quests);
            }
            else if (!sessionSuppressed && !DevMode.hasSeenGuide() && hasPermission && devOn
                    && !previewing && treeArrived && chapters == 0) {
                start(chapters, quests);
            }
            return;
        }
        if (!hasPermission || previewing) {
            // Dropped, not finished: a de-op or a preview ends the walk, marks nothing, and the
            // rung waits — reopening resumes it, because no rung was unlearned.
            active = false;
            return;
        }
        observe(chapters, quests);
    }

    /**
     * One drained edit reply, for the rungs the tree counts cannot see.
     *
     * <p>Only chapter ops reach here — the caller keeps table replies out, because a table is a
     * file rather than a rung. A refusal changes nothing, so only an acceptance moves the tour.
     *
     * @param ok      whether the server accepted the edit
     * @param questId the quest the reply names, or empty when it names none
     */
    public static void noteReply(boolean ok, String questId) {
        if (!active || !ok) {
            return;
        }
        if (step == Step.QUEST && questId != null && !questId.isEmpty()) {
            // The id is the server's to mint, so this is the first moment the made quest exists
            // to the client — the same signal the screen selects the new quest on.
            step = Step.TASK;
        }
        else if (step == Step.TASK) {
            step = Step.REWARD;
        }
        else if (step == Step.REWARD) {
            finish(true);
        }
    }

    /**
     * The Next press: one rung forward without doing it.
     *
     * <p>Skipping the last rung finishes without the making — the done card then says where to
     * read on rather than celebrating a quest nobody made.
     */
    public static void skipStep() {
        if (!active || step == Step.DONE) {
            return;
        }
        if (step == Step.REWARD) {
            finish(false);
        }
        else {
            step = Step.values()[step.ordinal() + 1];
        }
    }

    /**
     * The Skip press: away for this session, back next session while the book is still empty.
     *
     * <p>Suppression rather than the durable flag, because a waved-away tour on a still-empty
     * book should return — the flag is for finished and never-again, not for not-now.
     */
    public static void skip() {
        if (!active) {
            return;
        }
        active = false;
        sessionSuppressed = true;
    }

    /**
     * The done card's way out: the tour is over and stays over.
     *
     * <p>Walked or skipped, a finished tour does not return — that is what separates leaving
     * the done card from leaving mid-tour.
     */
    public static void dismiss() {
        if (!active) {
            return;
        }
        active = false;
        DevMode.setGuideSeen(true);
    }

    /** The "Don't show again" checkbox: the durable flag, and nothing else. */
    public static void setDontShowAgain(boolean dont) {
        DevMode.setGuideSeen(dont);
    }

    /** Whether "Don't show again" is currently on — the checkbox's own state. */
    public static boolean dontShowAgain() {
        return DevMode.hasSeenGuide();
    }

    /**
     * Forgets the tour for a server left behind: the rung names that server's book, so a book
     * opened on the next server starts its own tour rather than resuming this one's.
     */
    public static void clearSession() {
        freshRequest = false;
        active = false;
        step = Step.CHAPTER;
        completedByDoing = false;
        sessionSuppressed = false;
    }

    /** Starts at the first rung that is still undone. */
    private static void start(int chapters, int quests) {
        active = true;
        completedByDoing = false;
        if (chapters == 0) {
            step = Step.CHAPTER;
        }
        else if (quests == 0) {
            step = Step.QUEST;
        }
        else {
            step = Step.TASK;
        }
    }

    /** Advances the rungs the tree counts satisfy — the chapter's and the quest's. */
    private static void observe(int chapters, int quests) {
        switch (step) {
            case CHAPTER -> {
                if (chapters >= 1) {
                    step = Step.QUEST;
                }
            }
            case QUEST -> {
                // No baseline: this rung is only ever started on an empty book, so any quest is
                // the made one — and a fresh start on a book with quests begins past this rung.
                if (quests >= 1) {
                    step = Step.TASK;
                }
            }
            default -> {
                // The task's and the reward's arrivals are replies, not counts — see noteReply —
                // because the tree cannot say which quest gained them.
            }
        }
    }

    /** Arrives on the done card. */
    private static void finish(boolean byDoing) {
        step = Step.DONE;
        completedByDoing = byDoing;
        if (byDoing) {
            // Earned its keep: a made quest means the tour taught, so it does not return even if
            // the book is emptied again. A skipped-to done card marks nothing — leaving it does.
            DevMode.setGuideSeen(true);
        }
    }
}
