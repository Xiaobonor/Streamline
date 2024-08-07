package net.elfisland.plugin.streamlinenet.instance;

import com.velocitypowered.api.proxy.server.RegisteredServer;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import net.elfisland.plugin.streamlinenet.command.JoinNewCommand;
import net.elfisland.plugin.streamlinenet.platform.FlexNetProxy;
import net.elfisland.plugin.streamlinenet.platform.velocity.FlexNetVelocityInstanceController;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.TextColor;
import net.elfisland.plugin.streamlinenet.config.FlexNetConfig;
import net.elfisland.plugin.streamlinenet.config.LocaleConfig;
import net.elfisland.plugin.streamlinenet.group.FlexNetGroup;
import org.slf4j.Logger;

import java.text.MessageFormat;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
public class InstanceLifecycleManager {
    private final FlexNetProxy proxy;
    private final InstanceManager instanceManager;
    @Setter
    private FlexNetVelocityInstanceController instanceController;
    private final JoinNewCommand joinNewCommand;
    private final FlexNetConfig config;
    private final Logger logger;

    private static final Map<String, Boolean> serverShutdownInProgress = new HashMap<>();
    private static final Map<String, Boolean> instanceLifecycleInProgress = new HashMap<>();

    private final String clickablePartText;
    private final String transferMessageWithoutClickablePart;

    public InstanceLifecycleManager(FlexNetProxy proxy, InstanceManager instanceManager,
                                    FlexNetVelocityInstanceController instanceController, JoinNewCommand joinNewCommand,
                                    FlexNetConfig config, Logger logger) {
        this.proxy = proxy;
        this.instanceManager = instanceManager;
        this.joinNewCommand = joinNewCommand;
        this.instanceController = instanceController;
        this.config = config;
        this.logger = logger;

        LocaleConfig locale = config.getLocale();
        String restartMessageTemplate = locale.getServerTransferWarning();
        Matcher matcher = Pattern.compile("\\{1}\\[(.*?)\\]").matcher(restartMessageTemplate);
        this.clickablePartText = matcher.find() ? matcher.group(1) : "";
        this.transferMessageWithoutClickablePart = restartMessageTemplate.replaceFirst("\\{1}\\[.*?\\]", "");
    }

    public void handleServerLifecycle(String serverId, FlexNetGroup group, boolean createNewInstance) {
        if (instanceLifecycleInProgress.getOrDefault(serverId, false)) {
            logger.error("InstanceLifecycle process for server {} is already in progress", serverId);
            return;
        }
        instanceLifecycleInProgress.put(serverId, true);

        CompletableFuture<String> future = createNewInstance ?
                instanceController.createInstance(config.getTemplates().get(group.getId()), group) :
                CompletableFuture.completedFuture(getLowestPlayerServerId(group));

        future.thenAccept(newServerId -> handleServerClose(serverId, group, newServerId));
    }

    private String getLowestPlayerServerId(FlexNetGroup group) {
        RegisteredServer newServer = group.getLowestPlayerServer(true);
        return newServer != null ? newServer.getServerInfo().getName() : null;
    }

    private void handleServerClose(String serverId, FlexNetGroup group, String newServerId) {
        serverShutdownInProgress.put(serverId, true);
        scheduleTransferReminders(serverId, group, newServerId);

        long firstWarningTime = group.getTransferWarningIntervals()[0];
        logger.info("Kicking players of server {} in {} seconds", serverId, firstWarningTime);

        CompletableFuture.runAsync(() -> kickPlayersGradually(newServerId, serverId, group),
                CompletableFuture.delayedExecutor(firstWarningTime, TimeUnit.SECONDS))
                .thenRun(() -> deleteServerAfterWait(serverId, group, group.getPostShutdownWait()));
    }

    private void deleteServerAfterWait(String serverId, FlexNetGroup group, int waitTime) {
        logger.info("Deleting server {} in {} minutes", serverId, waitTime);
        proxy.scheduleTask(() -> {
            if (group.getServer(serverId) != null) {
                cleanupServerData(serverId, group);
            }
        }, waitTime * 60L);
    }

    private void cleanupServerData(String serverId, FlexNetGroup group) {
        instanceController.removeInstanceId(serverId);
        serverShutdownInProgress.remove(serverId);
        instanceLifecycleInProgress.remove(serverId);
        InstanceRestarter.removeFromServerUptime(serverId);
        proxy.removeServer(serverId, group);
        instanceManager.deleteInstance(serverId, (b) -> {});
    }

    private void scheduleTransferReminders(String serverId, FlexNetGroup group, String newServerId) {
        int[] intervals = group.getTransferWarningIntervals();
        int firstWarningTime = intervals[0];

        Arrays.stream(intervals)
                .filter(interval -> firstWarningTime - interval >= 0)
                .forEach(interval -> proxy.scheduleTask(() -> notifyPlayersOfTransfer(serverId, group, newServerId, interval), firstWarningTime - interval));
    }

    private void notifyPlayersOfTransfer(String serverId, FlexNetGroup group, String newServerId, int leftTime) {
        RegisteredServer server = group.getServer(serverId);
        if (server == null) {
            logger.error("Server {} not found for notification", serverId);
            return;
        }

        TextComponent finalMessage = buildTransferMessage(newServerId, group, leftTime);
        server.getPlayersConnected().forEach(player -> player.sendMessage(finalMessage));
        logger.info("Notified players of server {} transfer in {} seconds", serverId, leftTime);
    }

    private TextComponent buildTransferMessage(String newServerId, FlexNetGroup group, int leftTime) {
        String transferMessageFormatted = MessageFormat.format(this.transferMessageWithoutClickablePart, leftTime);

        TextComponent clickablePart = Component.text(this.clickablePartText)
                .color(TextColor.fromHexString("#00A5FF"))
                .hoverEvent(HoverEvent.showText(Component.text(this.clickablePartText)))
                .clickEvent(ClickEvent.runCommand("/JoinNew " + newServerId + " " + group.getServerName()));

        return Component.text(transferMessageFormatted).append(clickablePart);
    }

    private void kickPlayersGradually(String newServerId, String serverId, FlexNetGroup group) {
        RegisteredServer server = group.getServer(serverId);
        if (server == null) {
            logger.info("Server {} not found for kicking players", serverId);
            return;
        }

        CompletableFuture<Void> future = CompletableFuture.runAsync(() -> kickPlayers(server, newServerId, group.getServerName()));
        future.thenRun(() -> log.info("Kicking players done"));
    }

    private void kickPlayers(RegisteredServer server, String newServerId, String groupName) {
        while (!server.getPlayersConnected().isEmpty()) {
            log.info("Kicking players of server {}", server.getServerInfo().getName());
            server.getPlayersConnected().stream().limit(5).forEach(player ->
                    joinNewCommand.redirectPlayerToTargetServer(player.getUniqueId(), newServerId, groupName, player, true));

            proxy.scheduleTask(() -> kickPlayers(server, newServerId, groupName), 3);
        }
    }

    public static boolean isInstanceInLifecycleProcess(String serverId) {
        return serverShutdownInProgress.getOrDefault(serverId, false) ||
                instanceLifecycleInProgress.getOrDefault(serverId, false);
    }

    public static boolean isInShutdownProcess(String serverId) {
        return serverShutdownInProgress.getOrDefault(serverId, false);
    }
}
