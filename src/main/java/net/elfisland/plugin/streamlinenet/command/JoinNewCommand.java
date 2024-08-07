package net.elfisland.plugin.streamlinenet.command;

import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import net.kyori.adventure.text.Component;
import net.elfisland.plugin.streamlinenet.config.FlexNetConfig;
import net.elfisland.plugin.streamlinenet.config.LocaleConfig;
import net.elfisland.plugin.streamlinenet.group.FlexNetGroup;
import net.elfisland.plugin.streamlinenet.group.FlexNetGroupManager;
import net.elfisland.plugin.streamlinenet.instance.InstanceLifecycleManager;
import org.slf4j.Logger;

import java.text.MessageFormat;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

public class JoinNewCommand implements SimpleCommand {

    private final ProxyServer proxyServer;
    private final Logger logger;
    private final FlexNetGroupManager groupManager;
    private final Map<UUID, String> playerTargetServerMap;

    private final LocaleConfig locale;

    public JoinNewCommand(ProxyServer proxyServer, Logger logger, FlexNetConfig config,
                          FlexNetGroupManager groupManager, Map<UUID, String> playerTargetServerMap) {
        this.proxyServer = proxyServer;
        this.logger = logger;
        this.groupManager = groupManager;
        this.playerTargetServerMap = playerTargetServerMap;
        this.locale = config.getLocale();
    }

    @Override
    public void execute(Invocation invocation) {
        if (!(invocation.source() instanceof Player player)) {
            return;
        }

        String[] args = invocation.arguments();
        if (args.length < 1) {
            player.sendMessage(Component.text(locale.getJoinNewCommandUsage()));
            return;
        }

        String groupName = args[0];
        String targetServerId = args.length > 1 ? args[1] : null;

        FlexNetGroup group = groupManager.getGroup(groupName);
        if (group == null) {
            player.sendMessage(Component.text(MessageFormat.format(locale.getJoinNewGroupNotFound(), groupName)));
            return;
        }

        if (targetServerId == null) {
            targetServerId = group.getLowestPlayerServer(true).getServerInfo().getName();
        }

        redirectToServer(player, groupName, targetServerId);
    }

    private void redirectToServer(Player player, String groupName, String targetServerId) {
        Optional<RegisteredServer> server = proxyServer.getServer(targetServerId);

        if (server.isEmpty()) {
            player.sendMessage(Component.text(MessageFormat.format(locale.getJoinNewServerNotFound(), targetServerId)));
            return;
        }

        if (InstanceLifecycleManager.isInstanceInLifecycleProcess(targetServerId)) {
            player.sendMessage(Component.text(MessageFormat.format(locale.getJoinNewServerRestarting(), targetServerId)));
            return;
        }

        if (player.getCurrentServer().isPresent() && player.getCurrentServer().get().getServerInfo().getName().equals(targetServerId)) {
            player.sendMessage(Component.text(MessageFormat.format(locale.getPlayerInServerMessage(), targetServerId)));
            return;
        }

        redirectPlayerToTargetServer(player.getUniqueId(), targetServerId, groupName, player, false);
    }

    public void redirectPlayerToTargetServer(UUID playerId, String targetServerId, String groupName, Player player, boolean fullFindLowestServer) {
        FlexNetGroup group = groupManager.getGroup(groupName);

        if (fullFindLowestServer && proxyServer.getServer(targetServerId).map(s -> s.getPlayersConnected().size() > group.getPlayerAmountToCreateInstance() - 5).orElse(false)) {
            targetServerId = group.getLowestPlayerServer(true).getServerInfo().getName();
        }

        playerTargetServerMap.put(playerId, targetServerId);
        connectPlayerToHubServer(player, groupName, targetServerId);
    }

    private void connectPlayerToHubServer(Player player, String groupName, String targetServerId) {
        FlexNetGroup group = groupManager.getGroup(groupName);
        String hubServerId = group.getHubServer();

        if (hubServerId != null && !hubServerId.isEmpty()) {
            proxyServer.getServer(hubServerId).ifPresent(hubServer -> player.createConnectionRequest(hubServer).fireAndForget());
        } else {
            logger.error("Hub server not found for group: {}", groupName);
            playerTargetServerMap.remove(player.getUniqueId());
        }
    }
}
