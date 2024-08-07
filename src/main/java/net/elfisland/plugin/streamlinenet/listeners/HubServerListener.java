package net.elfisland.plugin.streamlinenet.listeners;

import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.player.KickedFromServerEvent;
import com.velocitypowered.api.event.player.ServerConnectedEvent;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import lombok.extern.slf4j.Slf4j;
import net.elfisland.plugin.streamlinenet.platform.FlexNetProxy;
import org.slf4j.Logger;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Slf4j
public class HubServerListener {

    private static final int MAX_RETRY_ATTEMPTS = 5;
    private static final long INITIAL_RETRY_DELAY = 150L;
    private static final long RETRY_DELAY_INCREMENT = 350L;
    private static final String SYNC_FAILURE_REASON = "Your player failed to sync. Please reconnect.";

    private final ProxyServer proxyServer;
    private final Map<UUID, String> playerTargetServerMap;
    private final FlexNetProxy proxy;
    private final Logger logger;

    public HubServerListener(FlexNetProxy proxy, ProxyServer proxyServer, Map<UUID, String> playerTargetServerMap, Logger logger) {
        this.proxyServer = proxyServer;
        this.playerTargetServerMap = playerTargetServerMap;
        this.proxy = proxy;
        this.logger = logger;
    }

    @Subscribe
    public void onServerConnected(ServerConnectedEvent event) {
        Player player = event.getPlayer();
        UUID playerId = player.getUniqueId();
        String targetServerId = playerTargetServerMap.get(playerId);

        if (targetServerId != null && !isSameServer(event, targetServerId)) {
            redirectPlayer(player, targetServerId);
        }
    }

    @Subscribe
    public void onPlayerKicked(KickedFromServerEvent event) {
        Player player = event.getPlayer();
        if (event.getServerKickReason().map(reason -> reason.contains(SYNC_FAILURE_REASON)).orElse(false)) {
            logger.warn("Player {} disconnected due to sync failure, attempting to reconnect", player.getUsername());
            redirectPlayer(player, event.getServer().getServerInfo().getName());
        }
    }

    private boolean isSameServer(ServerConnectedEvent event, String targetServerId) {
        return event.getServer().getServerInfo().getName().equals(targetServerId);
    }

    private void redirectPlayer(Player player, String targetServerId) {
        retryRedirect(player, targetServerId, 0);
    }

    private void retryRedirect(Player player, String targetServerId, int attempts) {
        if (attempts >= MAX_RETRY_ATTEMPTS) {
            logger.error("Failed to redirect player {} to server {} after {} attempts", player.getUsername(), targetServerId, attempts);
            playerTargetServerMap.remove(player.getUniqueId());
            return;
        }

        long delay = INITIAL_RETRY_DELAY + attempts * RETRY_DELAY_INCREMENT;
        proxy.scheduleTask(() -> {
            if (shouldRedirect(player, targetServerId)) {
                logger.info("Redirecting player {} to server {}, attempt {}", player.getUsername(), targetServerId, attempts + 1);
                proxyServer.getServer(targetServerId).ifPresent(server -> player.createConnectionRequest(server).fireAndForget());
                retryRedirect(player, targetServerId, attempts + 1);
            } else {
                playerTargetServerMap.remove(player.getUniqueId());
            }
        }, delay, TimeUnit.MILLISECONDS);
    }

    private boolean shouldRedirect(Player player, String targetServerId) {
        return player.getCurrentServer().map(current -> !current.getServerInfo().getName().equals(targetServerId)).orElse(true);
    }
}
