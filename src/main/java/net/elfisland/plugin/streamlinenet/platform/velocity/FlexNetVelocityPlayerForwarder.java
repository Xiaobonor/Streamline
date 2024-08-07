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
import java.util.Optional;

@Slf4j
public class FlexNetVelocityPlayerForwarder {

    private final ProxyServer proxyServer;
    private final LocaleConfig locale;
    private final FlexNetGroupManager groupManager;
    private final FlexNetVelocityInstanceController instanceController;
    private final Logger logger;

    public FlexNetVelocityPlayerForwarder(ProxyServer server, FlexNetGroupManager groupManager, FlexNetConfig config,
                                          FlexNetVelocityInstanceController instanceController, Logger logger) {
        this.proxyServer = server;
        this.groupManager = groupManager;
        this.locale = config.getLocale();
        this.instanceController = instanceController;
        this.logger = logger;
    }

    @Subscribe
    public void onLogin(LoginEvent event) {
        Player player = event.getPlayer();
        Optional<InetSocketAddress> virtualHostOptional = player.getVirtualHost();

        if (virtualHostOptional.isEmpty()) {
            denyLogin(event, locale.getInvalidHostname(), player, "without VHost");
            return;
        }

        InetSocketAddress address = virtualHostOptional.get();
        String hostname = address.getHostName();

        if (hostname == null || !groupManager.hasGroupFromHost(hostname)) {
            denyLogin(event, locale.getInvalidHostname(), player, "with invalid VHost: " + hostname);
        } else {
            FlexNetGroup group = groupManager.getGroupFromHost(hostname);
            if (!group.canConnect()) {
                denyLogin(event, locale.getNoServerAvailable(), player, "with no available server in group " + group.getId());
            }
        }
    }

    private void denyLogin(LoginEvent event, String reason, Player player, String logSuffix) {
        event.setResult(ResultedEvent.ComponentResult.denied(Component.text(reason)));
        logger.warn("Player {} ({}) attempted to join the server {}", player.getGameProfile().getName(), player.getUniqueId(), logSuffix);
    }

    @Subscribe
    public void onChooseInitServer(PlayerChooseInitialServerEvent event) {
        Player player = event.getPlayer();
        Optional<InetSocketAddress> virtualHostOptional = player.getVirtualHost();

        if (virtualHostOptional.isEmpty()) {
            return;
        }

        InetSocketAddress address = virtualHostOptional.get();
        String hostname = address.getHostName();
        FlexNetGroup group = groupManager.getGroupFromHost(hostname);

        if (group != null) {
            RegisteredServer server = group.getLowestPlayerServer(false);
            event.setInitialServer(server);

            proxyServer.getEventManager().fireAndForget(new FlexNetVelocityPlayerForwardedEvent(player, group, server));
            instanceController.adjustInstanceCountOnPlayerJoin(group);

            logger.info("Forwarded player {} ({}) to server {}", player.getGameProfile().getName(), player.getUniqueId(), server.getServerInfo().getName());
        }
    }

    @Subscribe
    public void onPlayerLeave(DisconnectEvent event) {
        Player player = event.getPlayer();
        Optional<InetSocketAddress> virtualHostOptional = player.getVirtualHost();

        if (virtualHostOptional.isEmpty()) {
            return;
        }

        InetSocketAddress address = virtualHostOptional.get();
        String hostname = address.getHostName();
        FlexNetGroup group = groupManager.getGroupFromHost(hostname);

        if (group != null) {
            instanceController.adjustInstanceCountOnPlayerLeave(group);
            logger.info("Adjusted instance count for group {} after player {} ({}) left", group.getId(), player.getGameProfile().getName(), player.getUniqueId());
        }
    }
}
