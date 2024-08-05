package net.elfisland.plugin.streamlinenet.command;

import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import net.elfisland.plugin.streamlinenet.config.FlexNetConfig;
import net.elfisland.plugin.streamlinenet.config.LocaleConfig;
import net.elfisland.plugin.streamlinenet.group.FlexNetGroup;
import net.elfisland.plugin.streamlinenet.group.FlexNetGroupManager;
import net.elfisland.plugin.streamlinenet.instance.InstanceLifecycleManager;
import net.kyori.adventure.text.Component;
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

    private final String usageMessage;
    private final String serverNotFoundMessage;
    private final String groupNotFoundMessage;
    private final String serverRestartingMessage;
    private final String playerInServerMessage;

    public JoinNewCommand(ProxyServer proxyServer, Logger logger, FlexNetConfig config,
                          FlexNetGroupManager groupManager, Map<UUID, String> playerTargetServerMap) {
        this.proxyServer = proxyServer;
        this.logger = logger;
        this.groupManager = groupManager;
        this.playerTargetServerMap = playerTargetServerMap;
        LocaleConfig locale = config.getLocale();

        this.usageMessage = locale.getJoinNewCommandUsage();
        this.serverNotFoundMessage = locale.getJoinNewServerNotFound();
        this.groupNotFoundMessage = locale.getJoinNewGroupNotFound();
        this.serverRestartingMessage = locale.getJoinNewServerRestarting();
        this.playerInServerMessage = locale.getPlayerInServerMessage();
    }

    @Override
    public void execute(Invocation invocation) {
        if (invocation.arguments().length < 1) {
            invocation.source().sendMessage(Component.text(usageMessage));
            return;
        }

        String groupName = invocation.arguments()[0];
        String targetServerId = invocation.arguments().length > 1 ? invocation.arguments()[1] : null;
        Player player = (Player) invocation.source();

        if (!groupManager.hasGroup(groupName)) {
            player.sendMessage(Component.text(MessageFormat.format(groupNotFoundMessage, groupName)));
            return;
        }

        FlexNetGroup group = groupManager.getGroup(groupName);
        if (targetServerId == null) {
            targetServerId = group.getLowestPlayerServer(true).getServerInfo().getName();
        }

        Optional<?> server = proxyServer.getServer(targetServerId);
        if (server.isEmpty()) {
            player.sendMessage(Component.text(MessageFormat.format(serverNotFoundMessage, targetServerId)));
            return;
        }

        if (InstanceLifecycleManager.isInstanceInLifecycleProcess(targetServerId)) {
            player.sendMessage(Component.text(MessageFormat.format(serverRestartingMessage, targetServerId)));
            return;
        }

        if (player.getCurrentServer().isPresent() && player.getCurrentServer().get().getServerInfo().getName().equals(targetServerId)) {
            player.sendMessage(Component.text(MessageFormat.format(playerInServerMessage, targetServerId)));
            return;
        }

    }
}
