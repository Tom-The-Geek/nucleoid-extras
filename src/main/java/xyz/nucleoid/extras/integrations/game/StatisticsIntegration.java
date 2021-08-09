package xyz.nucleoid.extras.integrations.game;

import com.google.gson.JsonObject;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.LiteralText;
import net.minecraft.text.TranslatableText;
import net.minecraft.util.Formatting;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import xyz.nucleoid.extras.integrations.IntegrationSender;
import xyz.nucleoid.extras.integrations.IntegrationsConfig;
import xyz.nucleoid.extras.integrations.NucleoidIntegrations;
import xyz.nucleoid.plasmid.event.GameEvents;
import xyz.nucleoid.plasmid.game.GameCloseReason;
import xyz.nucleoid.plasmid.game.player.PlayerSet;
import xyz.nucleoid.plasmid.game.stats.GameStatisticBundle;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

public class StatisticsIntegration {
    private static final Logger LOGGER = LogManager.getLogger();

    private final IntegrationSender statisticSender;
    private final AtomicInteger nextMessageId = new AtomicInteger(0);

    private final Int2ObjectMap<PlayerSet> messageIdToPlayers = new Int2ObjectOpenHashMap<>();
    private final List<JsonObject> queuedBundles = new ArrayList<>();

    private StatisticsIntegration(IntegrationSender statisticSender) {
        this.statisticSender = statisticSender;
    }

    private void handleStatisticsBundle(PlayerSet players, String namespace, GameStatisticBundle bundle) {
        for (ServerPlayerEntity player : players) {
            var stats = bundle.forPlayer(player);
            if (!stats.isEmpty()) {
                player.sendMessage(new LiteralText("+--------------------------------------+")
                        .formatted(Formatting.DARK_GRAY), false);

                var bundleName = new TranslatableText(GameStatisticBundle.getTranslationKey(namespace));

                player.sendMessage(new TranslatableText("text.nucleoid_extras.statistics.bundle_header", bundleName)
                        .formatted(Formatting.GREEN), false);

                stats.visitAllStatistics((key, value) ->
                        player.sendMessage(new TranslatableText("text.nucleoid_extras.statistics.stat",
                                new TranslatableText(key.getTranslationKey()), value), false));

                player.sendMessage(new LiteralText("+--------------------------------------+")
                        .formatted(Formatting.DARK_GRAY), false);
            }
        }

        LOGGER.debug("Submitting statistic bundle for '{}'...", namespace);

        JsonObject body = new JsonObject();
        JsonObject bundleObject = new JsonObject();
        bundleObject.addProperty("namespace", namespace);
        bundleObject.add("stats", bundle.encodeBundle());
        body.add("bundle", bundleObject);
        int messageId = nextMessageId.incrementAndGet();
        body.addProperty("message_id", messageId);
        this.sendBundle(body);
        this.messageIdToPlayers.put(messageId, players);
    }

    private void onUploadResponse(JsonObject obj) {
        int messageId = obj.get("message_id").getAsInt();
        String gameId = obj.get("game_id").getAsString();
        PlayerSet players = this.messageIdToPlayers.remove(messageId);
        if (players == null) {
            LOGGER.warn("Received upload response for unknown message: {} (game id: {})", messageId, gameId);
            return;
        }
        // TODO: Enable this when the web thing is done
//        players.sendMessage(new TranslatableText("text.nucleoid_extras.statistics.web_url", gameId)
//                .formatted(Formatting.GRAY, Formatting.ITALIC)
//                .styled(style -> style.withClickEvent(new ClickEvent(ClickEvent.Action.OPEN_URL,
//                        "https://nucleoid.xyz/game/#" + gameId))));
    }

    private void sendBundle(JsonObject bundle) {
        if (!this.statisticSender.send(bundle)) {
            this.queuedBundles.add(bundle);
        }
    }

    private void onConnectionOpen() {
        Iterator<JsonObject> iter = this.queuedBundles.iterator();
        while (iter.hasNext()) {
            JsonObject bundle = iter.next();
            iter.remove();
            this.sendBundle(bundle);
        }
    }

    public static void bind(NucleoidIntegrations integrations, IntegrationsConfig config) {
        if (config.sendStatistics()) {
            var instance = new StatisticsIntegration(integrations.openSender("upload_statistics"));
            integrations.bindConnectionOpen(instance::onConnectionOpen);
            GameEvents.CLOSING.register((space, reason) -> {
                if (reason == GameCloseReason.FINISHED) {
                    space.visitAllStatistics((namespace, bundle) ->
                            instance.handleStatisticsBundle(space.getPlayers().copy(space.getServer()), namespace, bundle));
                }
            });
            integrations.bindReceiver("upload_statistics_response", instance::onUploadResponse);
        }
    }
}
