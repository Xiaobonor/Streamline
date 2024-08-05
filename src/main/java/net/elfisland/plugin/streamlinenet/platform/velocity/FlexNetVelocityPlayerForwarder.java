package net.elfisland.plugin.streamlinenet.platform.velocity;

import com.velocitypowered.api.event.ResultedEvent;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.connection.LoginEvent;
import com.velocitypowered.api.event.player.PlayerChooseInitialServerEvent;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import lombok.extern.slf4j.Slf4j;
import net.elfisland.plugin.streamlinenet.platform.velocity.event.FlexNetVelocityPlayerForwardedEvent;
import net.kyori.adventure.text.Component;
import net.elfisland.plugin.streamlinenet.config.FlexNetConfig;
import net.elfisland.plugin.streamlinenet.config.LocaleConfig;
import net.elfisland.plugin.streamlinenet.group.FlexNetGroup;
import net.elfisland.plugin.streamlinenet.group.FlexNetGroupManager;
import org.slf4j.Logger;

import java.net.InetSocketAddress;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

@Slf4j
public class FlexNetVelocityPlayerForwarder {

    private final ProxyServer proxyServer;
    private final LocaleConfig locale;
    private final FlexNetGroupManager groupManager;
    private final FlexNetVelocityInstanceController instanceController;
    private final Logger logger;
    private final Map<UUID, InetSocketAddress> playerHosts = new ConcurrentHashMap<>();
    private final Map<String, AtomicInteger> playerCountsByGroup = new ConcurrentHashMap<>();
    private final Map<UUID, Long> loginTimestamps = new ConcurrentHashMap<>();

    public FlexNetVelocityPlayerForwarder(ProxyServer server, FlexNetGroupManager groupManager, FlexNetConfig config,
                                          ProxyServer proxyServer, FlexNetVelocityInstanceController instanceController,
                                          Logger logger) {
        this.proxyServer = server;
        this.groupManager = groupManager;
        this.locale = config.getLocale();
        this.instanceController = instanceController;
        this.logger = logger;
    }

    @Subscribe
    public void onLogin(LoginEvent event) {
        Player player = event.getPlayer();

        loginTimestamps.put(player.getUniqueId(), System.currentTimeMillis());

        if (player.getVirtualHost().isEmpty()) {
            event.setResult(ResultedEvent.ComponentResult.denied(
                    Component.text(locale.getInvalidHostname())
            ));
            logger.warn(
                    "Player {} ({}) attempt to join the server without VHost",
                    player.getGameProfile().getName(),
                    player.getUniqueId()
            );
        } else {
            InetSocketAddress address = player.getVirtualHost().get();
            if (address.getHostName() == null || !groupManager.hasGroupFromHost(address.getHostName())) {
                event.setResult(ResultedEvent.ComponentResult.denied(
                        Component.text(locale.getInvalidHostname())
                ));
                logger.warn(
                        "Player {} ({}) attempt to join the server with invalid VHost: {}",
                        player.getGameProfile().getName(),
                        player.getUniqueId(),
                        address.getHostName()
                );
            } else if (!groupManager.getGroupFromHost(address.getHostName()).canConnect()) {
                event.setResult(ResultedEvent.ComponentResult.denied(
                        Component.text(locale.getNoServerAvailable())
                ));
                logger.warn(
                        "Player {} ({}) attempt to join the server with no available server in group {}",
                        player.getGameProfile().getName(),
                        player.getUniqueId(),
                        groupManager.getGroupFromHost(address.getHostName()).getId()
                );
            } else {
                playerHosts.put(player.getUniqueId(), address);
                updatePlayerCount(address.getHostName(), true);
            }
        }
    }

    @Subscribe
    public void onChooseInitServer(PlayerChooseInitialServerEvent event) {
        Player player = event.getPlayer();
        Optional<InetSocketAddress> virtualHost = player.getVirtualHost();

        if (!virtualHost.isPresent()) return;

        InetSocketAddress address = virtualHost.get();
        FlexNetGroup group = groupManager.getGroupFromHost(address.getHostName());
        RegisteredServer server = group.getLowestPlayerServer(false);

        if (server == null) {
            logger.warn("No available server found in group {} for player {} ({})",
                    group.getId(), player.getGameProfile().getName(), player.getUniqueId());
            return;
        }

        event.setInitialServer(server);

        proxyServer.getEventManager().fireAndForget(new FlexNetVelocityPlayerForwardedEvent(player, group, server));
        instanceController.adjustInstanceCountOnPlayerJoin(group);

        logger.info("Forwarded player {} ({}) to server {}",
                player.getGameProfile().getName(),
                player.getUniqueId(),
                server.getServerInfo().getName()
        );
    }

    @Subscribe
    public void onPlayerLeave(DisconnectEvent event) {
        Player player = event.getPlayer();
        Optional<InetSocketAddress> virtualHost = player.getVirtualHost();

        if (!virtualHost.isPresent()) {
            logger.warn("Player {} ({}) disconnected without a known VHost",
                    player.getGameProfile().getName(), player.getUniqueId());
            return;
        }

        InetSocketAddress address = virtualHost.get();
        FlexNetGroup group = groupManager.getGroupFromHost(address.getHostName());

        if (group != null) {
            instanceController.adjustInstanceCountOnPlayerLeave(group);
            updatePlayerCount(address.getHostName(), false);
        }

        loginTimestamps.remove(player.getUniqueId());
        playerHosts.remove(player.getUniqueId());
    }

    private void updatePlayerCount(String hostname, boolean increment) {
        AtomicInteger count = playerCountsByGroup.computeIfAbsent(hostname, k -> new AtomicInteger(0));
        if (increment) {
            count.incrementAndGet();
        } else {
            count.decrementAndGet();
        }
        logger.info("Updated player count for group {}: {}", hostname, count.get());
    }

    public int getPlayerCount(String hostname) {
        return playerCountsByGroup.getOrDefault(hostname, new AtomicInteger(0)).get();
    }

    public long getLoginTimestamp(UUID playerId) {
        return loginTimestamps.getOrDefault(playerId, -1L);
    }

    public void resetPlayerCount(String hostname) {
        playerCountsByGroup.remove(hostname);
        logger.info("Player count reset for group {}", hostname);
    }

    public void logPlayerActivity(UUID playerId) {
        InetSocketAddress host = playerHosts.get(playerId);
        if (host != null) {
            logger.info("Player {} is connected with host {}", playerId, host.getHostName());
        } else {
            logger.warn("Player {} has no recorded host information", playerId);
        }
    }

    public boolean isPlayerConnected(UUID playerId) {
        return playerHosts.containsKey(playerId);
    }

    public void simulateLoginFailure(Player player) {
        logger.warn("Simulating login failure for player {}", player.getGameProfile().getName());
        proxyServer.getScheduler().buildTask(proxyServer, () -> {
            player.disconnect(Component.text(locale.getLoginFailed()));
            logger.info("Player {} has been disconnected due to simulated login failure", player.getGameProfile().getName());
        }).delay(2, TimeUnit.SECONDS).schedule();
    }

    public void handleReconnect(Player player) {
        UUID playerId = player.getUniqueId();
        if (isPlayerConnected(playerId)) {
            logger.info("Handling reconnect for player {}", player.getGameProfile().getName());
            InetSocketAddress host = playerHosts.get(playerId);
            if (host != null) {
                FlexNetGroup group = groupManager.getGroupFromHost(host.getHostName());
                if (group != null && group.canConnect()) {
                    RegisteredServer server = group.getLowestPlayerServer(false);
                    if (server != null) {
                        player.createConnectionRequest(server).fireAndForget();
                        logger.info("Player {} reconnected to server {}", player.getGameProfile().getName(), server.getServerInfo().getName());
                    } else {
                        logger.warn("No available server found for reconnecting player {}", player.getGameProfile().getName());
                    }
                } else {
                    logger.warn("Group for reconnecting player {} is not available or cannot connect", player.getGameProfile().getName());
                }
            } else {
                logger.warn("No known host for reconnecting player {}", player.getGameProfile().getName());
            }
        } else {
            logger.warn("Reconnect attempt for non-connected player {}", player.getGameProfile().getName());
        }
    }

    public void kickPlayer(Player player, String reason) {
        logger.info("Kicking player {}: {}", player.getGameProfile().getName(), reason);
        player.disconnect(Component.text(reason));
        playerHosts.remove(player.getUniqueId());
    }

    public void checkIdlePlayers() {
        long currentTime = System.currentTimeMillis();
        for (UUID playerId : loginTimestamps.keySet()) {
            long loginTime = loginTimestamps.get(playerId);
            if (currentTime - loginTime > 3600000) { // 1 hour
                Player player = proxyServer.getPlayer(playerId).orElse(null);
                if (player != null) {
                    kickPlayer(player, "Idle timeout exceeded");
                    logger.info("Kicked idle player {}", player.getGameProfile().getName());
                }
            }
        }
    }

    public void updateLocaleConfig(LocaleConfig newLocale) {
        this.locale = newLocale;
        logger.info("Locale configuration updated");
    }

    public void simulateServerLoad(String hostname) {
        logger.warn("Simulating high server load for group {}", hostname);
        proxyServer.getScheduler().buildTask(proxyServer, () -> {
            logger.info("Server load simulation complete for group {}", hostname);
        }).delay(5, TimeUnit.SECONDS).schedule();
    }

    public void logGroupServerStatus(String hostname) {
        int count = getPlayerCount(hostname);
        logger.info("Group {} has {} connected players", hostname, count);
    }
}
