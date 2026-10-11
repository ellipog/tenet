package dev.ellipog.tenet.quest.reward;

/**
 * How a reward type hands something over.
 *
 * <p>One method, and the player is passed in rather than returned as a list of items. That is
 * deliberate: a reward that grants items has to cope with a full inventory, and the way it copes — and
 * where the overflow goes — is the reward type's business, not the engine's.
 *
 * <h2>Granting must be idempotent-safe, but is not required to be idempotent</h2>
 *
 * <p>Called from four paths, and they do not persist in the same order — which is the part a caller has
 * to know, because it decides what a crash costs. The claim ({@code ProgressService.claim}, which a
 * player triggers) <b>grants first and writes once after</b> the loop: each reward is marked as it is
 * handed over, so a crash mid-grant leaves that reward unmarked and outstanding — given twice at worst
 * rather than lost. Completion's auto-claim and the join sweep are the other way round: they mark and
 * save first, each in its own order, so a crash mid-grant leaves the reward marked collected with part
 * of it given. A table's leaves are granted by the same walk as any other reward, so a table pays its
 * entries the way its parent quest does, in whichever order the path that reached it had already taken.
 *
 * <p>The automatic paths chose the loss over the duplication deliberately: duplicating a diamond is an
 * exploit and losing one to a crash is an annoyance, and legacy packs take the same direction. The claim
 * is the one press with the player watching, and it can afford the other trade.
 */
public interface RewardBehaviour<T> {

    /**
     * Hands the reward to {@code player}.
     *
     * <p>Runs on the server thread. Implementations must not throw for an ordinary failure — a full
     * inventory is ordinary, and must not abort the remaining rewards on the same quest.
     */
    void grant(T reward, RewardContext context);
}
