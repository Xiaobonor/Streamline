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
    public void onDisconnect(KickedFromServerEvent event) {
        Player player = event.getPlayer();
        String disconnectReason = String.valueOf(event.getServerKickReason());

        if (disconnectReason.contains("Your player failed to sync. Please reconnect.")) {
            logger.warn("Player {} disconnected due to sync failure, Try reconnect", player.getUsername());
            String server = event.getServer().getServerInfo().getName();
        }
    }
}
