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

@Slf4j
public class HubServerListener {

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
        if (targetServerId != null) {
            proxyServer.getServer(targetServerId).ifPresent(targetServer -> {
                if (!targetServer.getServerInfo().equals(event.getServer().getServerInfo())) {
                    retryRedirect(player, targetServerId, 0);
                }
            });
        }
    }

    @Subscribe
    public void onDisconnect(KickedFromServerEvent event) {
        Player player = event.getPlayer();
        String disconnectReason = String.valueOf(event.getServerKickReason());

        if (disconnectReason.contains("Your player failed to sync. Please reconnect.")) {
            logger.warn("Player {} disconnected due to sync failure, Try reconnect", player.getUsername());
            String server = event.getServer().getServerInfo().getName();
            retryRedirect(player, server, 0);
        }
    }

    private void retryRedirect(Player player, String targetServerId, int attempts) {
        if (attempts >= 5) {
            logger.error("Failed to redirect player {} to server {} after {} attempts", player.getUsername(), targetServerId, attempts);
            playerTargetServerMap.remove(player.getUniqueId());
            return;
        }

        proxy.scheduleTask(() -> {
            if (player.getCurrentServer().isEmpty() || !player.getCurrentServer().get().getServerInfo().getName().equals(targetServerId)) {
                logger.info("Redirecting player {} to server {}, attempt {}", player.getUsername(), targetServerId, attempts + 1);
                proxyServer.getServer(targetServerId).ifPresent(
                        server -> player.createConnectionRequest(server).fireAndForget()
                );
                retryRedirect(player, targetServerId, attempts + 1);
            } else {
                playerTargetServerMap.remove(player.getUniqueId());
            }
        }, 150 + attempts * 350L, true);
    }
}
