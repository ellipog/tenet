package dev.ellipog.tenet.net;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

import java.util.List;

/**
 * The stages one player has, sent to that player's own client.
 *
 * <h2>Why this is not on the progress payload</h2>
 *
 * <p>Two reasons, and either alone would decide it. The progress payload is a flat record already at the
 * six components a {@code StreamCodec.composite} allows, so there is no room for a seventh. And its body is
 * a per-quest delta -- each quest compared as encoded text against the last sync -- which has no notion of
 * a root-level fact that changes on its own; a stage granted in a script would ride nothing and be seen only
 * at the next full sync.
 *
 * <p>So stages travel on their own, like the roster and the world's dimension list: sent on join with
 * everything else, and re-sent whenever they change. Ids, sorted, so two arrivals cannot reorder a list a
 * script is walking.
 */
public record StageSyncPayload(List<String> stages) implements CustomPacketPayload {

    /**
     * The payload's id. The constructor, not {@code createType} — see
     * {@link QuestSyncPayload#TYPE} for why that method silently rewrites the namespace.
     */
    public static final CustomPacketPayload.Type<StageSyncPayload> TYPE =
            new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath("tenet", "stage_sync"));

    public static final StreamCodec<? super RegistryFriendlyByteBuf, StageSyncPayload> CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.stringUtf8(256).apply(ByteBufCodecs.list()),
                    StageSyncPayload::stages,
                    StageSyncPayload::new);

    public StageSyncPayload {
        stages = List.copyOf(stages);
    }

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
