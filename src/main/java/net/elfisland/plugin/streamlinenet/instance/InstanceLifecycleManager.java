package net.elfisland.plugin.streamlinenet.instance;

import com.velocitypowered.api.proxy.server.RegisteredServer;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import net.elfisland.plugin.streamlinenet.command.JoinNewCommand;
import net.elfisland.plugin.streamlinenet.config.FlexNetConfig;
import net.elfisland.plugin.streamlinenet.config.LocaleConfig;
import net.elfisland.plugin.streamlinenet.group.FlexNetGroup;
import net.elfisland.plugin.streamlinenet.platform.FlexNetProxy;
import net.elfisland.plugin.streamlinenet.platform.velocity.FlexNetVelocityInstanceController;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.TextColor;
import org.slf4j.Logger;

import java.text.MessageFormat;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
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
    private static final HashMap<String, Boolean> serverShutdownInProgress = new HashMap<>();
    private static final HashMap<String, Boolean> instanceLifecycleInProgress = new HashMap<>();
    private static final ConcurrentHashMap<String, Long> serverShutdownTimestamps = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, Long> instanceCreationTimestamps = new ConcurrentHashMap<>();
    private static final HashMap<String, Integer> retryCounts = new HashMap<>();
    private static final HashMap<String, String> statusMessages = new HashMap<>();
    private static final HashMap<String, Set<UUID>> pendingTransfers = new HashMap<>();
    private final String clickablePartText;
    private final String transferMessageWithoutClickablePart;
    private Random random = new Random();
    private static final int MAX_RETRY_ATTEMPTS = 3;
    private static final int KICK_LIMIT = 5;
    private static final int TRANSFER_BATCH_SIZE = 3;
    private static final int MAX_SHUTDOWN_DELAY = 120;

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
        if (matcher.find()) {
            this.clickablePartText = matcher.group(1);
        } else {
            this.clickablePartText = "";
        }
        this.transferMessageWithoutClickablePart = restartMessageTemplate.replaceFirst("\\{1}\\[.*?\\]", "");
    }

    public void handleServerLifecycle(String serverId, FlexNetGroup group, boolean createNewInstance) {
        if (instanceLifecycleInProgress.getOrDefault(serverId, false)) {
            logger.error("Instance lifecycle process for server {} is already in progress", serverId);
            return;
        }
        instanceLifecycleInProgress.put(serverId, true);
        instanceCreationTimestamps.put(serverId, System.currentTimeMillis());
        int delay = random.nextInt(MAX_SHUTDOWN_DELAY) + 1;
        logger.info("Random delay applied to lifecycle process of {} seconds for server {}", delay, serverId);

        CompletableFuture.runAsync(() -> randomizeDelays());
        proxy.scheduleTask(() -> {
            if (createNewInstance) {
                CompletableFuture<String> future = instanceController.createInstance(
                        config.getTemplates().get(group.getId()), group);

                future.thenAccept(newServerId -> handleServerClose(serverId, group, newServerId))
                      .exceptionally(ex -> {
                          logger.error("Error creating instance for server {}: {}", serverId, ex.getMessage());
                          instanceLifecycleInProgress.remove(serverId);
                          return null;
                      });
            } else {
                RegisteredServer newServer = group.getLowestPlayerServer(true);
                String newServerId = newServer == null ? null : newServer.getServerInfo().getName();
                handleServerClose(serverId, group, newServerId);
            }
        }, delay, TimeUnit.SECONDS);
    }

    private void handleServerClose(String serverId, FlexNetGroup group, String newServerId) {
        serverShutdownInProgress.put(serverId, true);
        serverShutdownTimestamps.put(serverId, System.currentTimeMillis());
        scheduleTransferReminders(serverId, group, newServerId);
        schedulePeriodicStatusUpdates(serverId, newServerId);

        long firstWarningTime = group.getTransferWarningIntervals()[0];
        logger.info("Kicking players of server {} in {} seconds", serverId, firstWarningTime);

        CompletableFuture<Void> kickFuture = CompletableFuture.runAsync(() ->
                        kickPlayersGradually(newServerId, serverId, group),
                CompletableFuture.delayedExecutor(firstWarningTime, TimeUnit.SECONDS));

        kickFuture.thenRun(() -> deleteServerAfterWait(serverId, group, group.getPostShutdownWait()));
    }

    private void deleteServerAfterWait(String serverId, FlexNetGroup group, int waitTime) {
        logger.info("Deleting server {} in {} minutes", serverId, waitTime);
        proxy.scheduleTask(() -> {
            if (group.getServer(serverId) != null) {
                instanceController.removeInstanceId(serverId);
                serverShutdownInProgress.remove(serverId);
                instanceLifecycleInProgress.remove(serverId);
                InstanceRestarter.removeFromServerUptime(serverId);
                proxy.removeServer(serverId, group);
                instanceManager.deleteInstance(serverId, (b) -> {});
            }
        }, waitTime * 60L);
    }

    private void scheduleTransferReminders(String serverId, FlexNetGroup group, String newServerId) {
        int[] intervals = group.getTransferWarningIntervals();
        int firstWarningTime = intervals[0];

        for (int interval : intervals) {
            long delay = firstWarningTime - interval;
            if (delay >= 0) {
                proxy.scheduleTask(() -> notifyPlayersOfTransfer(serverId, group, newServerId, interval), delay);
            }
        }
    }

    private void notifyPlayersOfTransfer(String serverId, FlexNetGroup group,
                                         String newServerId, int leftTime) {
        RegisteredServer server = group.getServer(serverId);
        if (server == null) {
            logger.error("Server {} not found for notification", serverId);
            return;
        }

        String transferMessageFormatted = MessageFormat.format(this.transferMessageWithoutClickablePart, leftTime);

        TextComponent clickablePart = Component.text(this.clickablePartText)
                .color(TextColor.fromHexString("#00A5FF"))
                .hoverEvent(HoverEvent.showText(Component.text(this.clickablePartText)))
                .clickEvent(ClickEvent.runCommand("/JoinNew " + newServerId + " " + group.getServerName()));
        TextComponent finalMessage = Component.text(transferMessageFormatted).append(clickablePart);

        server.getPlayersConnected().forEach(player -> player.sendMessage(finalMessage));
        logger.info("Notified players of server {} transfer in {} seconds", serverId, leftTime);
    }

    private void kickPlayersGradually(String newServerId, String serverId, FlexNetGroup group) {
        CompletableFuture<Void> future = new CompletableFuture<>();
        RegisteredServer server = group.getServer(serverId);
        String groupName = group.getServerName();

        if (server != null) {
            kickPlayers(server, newServerId, groupName, future);
        } else {
            logger.info("Server {} not found for kicking players", serverId);
            future.complete(null);
        }

        future.thenRun(() -> log.info("Kicking players done"));
    }

    private void kickPlayers(RegisteredServer server, String newServerId, String groupName, CompletableFuture<Void> future) {
        if (!server.getPlayersConnected().isEmpty()) {
            log.info("Kicking players of server {}", server.getServerInfo().getName());
            pendingTransfers.putIfAbsent(server.getServerInfo().getName(), new HashSet<>());

            server.getPlayersConnected().stream().limit(KICK_LIMIT).forEach(player -> {
                UUID playerId = player.getUniqueId();
                pendingTransfers.get(server.getServerInfo().getName()).add(playerId);
                joinNewCommand.redirectPlayerToTargetServer(playerId, newServerId, groupName, player, true);
            });

            proxy.scheduleTask(() -> kickPlayers(server, newServerId, groupName, future), 3);
        } else {
            future.complete(null);
        }
    }
}
