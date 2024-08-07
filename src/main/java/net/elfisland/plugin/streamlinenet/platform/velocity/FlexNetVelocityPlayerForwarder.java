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

@Slf4j
public class FlexNetVelocityPlayerForwarder {

    private final ProxyServer proxyServer;
    private final LocaleConfig locale;
    private final FlexNetGroupManager groupManager;
    private final FlexNetVelocityInstanceController instanceController;
    private final Logger logger;


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

        if(player.getVirtualHost().isEmpty()) {
            event.setResult(ResultedEvent.ComponentResult.denied(
                    Component.text(locale.getInvalidHostname())
            ));
            logger.warn(
                    "Player {} ({}) attempt to join the server without VHost",
                    player.getGameProfile().getName(),
                    player.getUniqueId()
            );
        }
        InetSocketAddress address = player.getVirtualHost().get();
        if(address.getHostName() == null || !groupManager.hasGroupFromHost(address.getHostName())) {
            // Kick player if their hostname is not listed in config
            event.setResult(ResultedEvent.ComponentResult.denied(
                    Component.text(locale.getInvalidHostname())
            ));
            logger.warn(
                    "Player {} ({}) attempt to join the server with invalid VHost: {}",
                    player.getGameProfile().getName(),
                    player.getUniqueId(),
                    address.getHostName()
            );
        } else if(!groupManager.getGroupFromHost(address.getHostName()).canConnect()) {
            // Kick player if the group has no server available
            event.setResult(ResultedEvent.ComponentResult.denied(
                    Component.text(locale.getNoServerAvailable())
            ));
            logger.warn(
                    "Player {} ({}) attempt to join the server with no available server in group {}",
                    player.getGameProfile().getName(),
                    player.getUniqueId(),
                    groupManager.getGroupFromHost(address.getHostName()).getId()
            );
        }
    }

    @Subscribe
    public void onChooseInitServer(PlayerChooseInitialServerEvent event) {
        Player player = event.getPlayer();

        if(player.getVirtualHost().isEmpty()) return;
        InetSocketAddress address = player.getVirtualHost().get();
        FlexNetGroup group = groupManager.getGroupFromHost(address.getHostName());
        RegisteredServer server = group.getLowestPlayerServer(false);
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
        if(player.getVirtualHost().isEmpty()) return; // TODO: maybe handle this case?
        InetSocketAddress address = player.getVirtualHost().get();
        FlexNetGroup group = groupManager.getGroupFromHost(address.getHostName());

        if (group != null) {
            instanceController.adjustInstanceCountOnPlayerLeave(group);
        }
    }


}
