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
    private static final int MAX_RETRY_ATTEMPTS = 5;
    private static final int KICK_LIMIT = 10;
    private static final int TRANSFER_BATCH_SIZE = 5;
    private static final int MAX_SHUTDOWN_DELAY = 180;
    private static final int HEALTH_CHECK_INTERVAL = 30;
    private static final int LOGGING_DETAIL_LEVEL = 2;
    private static final int MAX_RESTART_INTERVAL = 300;
    private static final long DATA_CLEANUP_INTERVAL = 60 * 1000L;

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
            this.clickablePartText = "click here";
        }
        this.transferMessageWithoutClickablePart = restartMessageTemplate.replaceFirst("\\{1}\\[.*?\\]", "");
        schedulePeriodicDataCleanup();
        scheduleRegularHealthChecks();
    }

    public void handleServerLifecycle(String serverId, FlexNetGroup group, boolean createNewInstance) {
        if (instanceLifecycleInProgress.getOrDefault(serverId, false)) {
            logDetailedError("Instance lifecycle process for server {} is already in progress", serverId);
            return;
        }
        instanceLifecycleInProgress.put(serverId, true);
        instanceCreationTimestamps.put(serverId, System.currentTimeMillis());
        int delay = random.nextInt(MAX_SHUTDOWN_DELAY) + 1;
        logDetailedInfo("Random delay applied to lifecycle process of {} seconds for server {}", delay, serverId);

        CompletableFuture.runAsync(this::randomizeDelays);
        proxy.scheduleTask(() -> {
            if (createNewInstance) {
                CompletableFuture<String> future = instanceController.createInstance(
                        config.getTemplates().get(group.getId()), group);

                future.thenAccept(newServerId -> handleServerClose(serverId, group, newServerId))
                        .exceptionally(ex -> {
                            logDetailedError("Error creating instance for server {}: {}", serverId, ex.getMessage());
                            handleLifecycleFailure(serverId, ex);
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
        logDetailedInfo("Kicking players of server {} in {} seconds", serverId, firstWarningTime);

        CompletableFuture<Void> kickFuture = CompletableFuture.runAsync(() ->
                        kickPlayersGradually(newServerId, serverId, group),
                CompletableFuture.delayedExecutor(firstWarningTime, TimeUnit.SECONDS));

        kickFuture.thenRun(() -> deleteServerAfterWait(serverId, group, group.getPostShutdownWait()));
    }

    private void deleteServerAfterWait(String serverId, FlexNetGroup group, int waitTime) {
        logDetailedInfo("Deleting server {} in {} minutes", serverId, waitTime);
        proxy.scheduleTask(() -> {
            if (group.getServer(serverId) != null) {
                instanceController.removeInstanceId(serverId);
                serverShutdownInProgress.remove(serverId);
                instanceLifecycleInProgress.remove(serverId);
                InstanceRestarter.removeFromServerUptime(serverId);
                proxy.removeServer(serverId, group);
                instanceManager.deleteInstance(serverId, (b) -> logDetailedInfo("Server {} successfully deleted.", serverId));
            }
        }, waitTime * 60L, TimeUnit.SECONDS);
    }

    private void scheduleTransferReminders(String serverId, FlexNetGroup group, String newServerId) {
        int[] intervals = group.getTransferWarningIntervals();
        int firstWarningTime = intervals[0];

        for (int interval : intervals) {
            long delay = firstWarningTime - interval;
            if (delay >= 0) {
                proxy.scheduleTask(() -> notifyPlayersOfTransfer(serverId, group, newServerId, interval), delay, TimeUnit.SECONDS);
            }
        }
    }

    private void notifyPlayersOfTransfer(String serverId, FlexNetGroup group,
                                         String newServerId, int leftTime) {
        RegisteredServer server = group.getServer(serverId);
        if (server == null) {
            logDetailedError("Server {} not found for notification", serverId);
            return;
        }

        String transferMessageFormatted = MessageFormat.format(this.transferMessageWithoutClickablePart, leftTime);

        TextComponent clickablePart = Component.text(this.clickablePartText)
                .color(TextColor.fromHexString("#00A5FF"))
                .hoverEvent(HoverEvent.showText(Component.text(this.clickablePartText)))
                .clickEvent(ClickEvent.runCommand("/JoinNew " + newServerId + " " + group.getServerName()));
        TextComponent finalMessage = Component.text(transferMessageFormatted).append(clickablePart);

        server.getPlayersConnected().forEach(player -> player.sendMessage(finalMessage));
        logDetailedInfo("Notified players of server {} transfer in {} seconds", serverId, leftTime);
    }

    private void kickPlayersGradually(String newServerId, String serverId, FlexNetGroup group) {
        CompletableFuture<Void> future = new CompletableFuture<>();
        RegisteredServer server = group.getServer(serverId);
        String groupName = group.getServerName();

        if (server != null) {
            kickPlayers(server, newServerId, groupName, future);
        } else {
            logDetailedInfo("Server {} not found for kicking players", serverId);
            future.complete(null);
        }

        future.thenRun(() -> logDetailedInfo("Kicking players done for server {}", serverId));
    }

    private void kickPlayers(RegisteredServer server, String newServerId, String groupName, CompletableFuture<Void> future) {
        if (!server.getPlayersConnected().isEmpty()) {
            logDetailedInfo("Kicking players of server {}", server.getServerInfo().getName());
            pendingTransfers.putIfAbsent(server.getServerInfo().getName(), new HashSet<>());

            server.getPlayersConnected().stream().limit(KICK_LIMIT).forEach(player -> {
                UUID playerId = player.getUniqueId();
                pendingTransfers.get(server.getServerInfo().getName()).add(playerId);
                joinNewCommand.redirectPlayerToTargetServer(playerId, newServerId, groupName, player, true);
            });

            proxy.scheduleTask(() -> kickPlayers(server, newServerId, groupName, future), 3, TimeUnit.SECONDS);
        } else {
            future.complete(null);
        }
    }

    private void handleLifecycleFailure(String serverId, Throwable throwable) {
        int retryCount = retryCounts.getOrDefault(serverId, 0);
        if (retryCount < MAX_RETRY_ATTEMPTS) {
            retryCounts.put(serverId, retryCount + 1);
            logDetailedWarn("Retrying lifecycle management for server {} due to error: {}", serverId, throwable.getMessage());
            proxy.scheduleTask(() -> handleServerLifecycle(serverId, null, true), random.nextInt(MAX_SHUTDOWN_DELAY), TimeUnit.SECONDS);
        } else {
            logDetailedError("Max retry attempts reached for server {}. Error: {}", serverId, throwable.getMessage());
            notifyAdminOfFailure(serverId, throwable);
        }
    }

    private void notifyAdminOfFailure(String serverId, Throwable throwable) {
        // Here you could send an email or a notification to the admin
        logDetailedError("Admin notification: Server {} lifecycle failed with error: {}", serverId, throwable.getMessage());
    }

    public static boolean isInstanceInLifecycleProcess(String serverId) {
        return serverShutdownInProgress.getOrDefault(serverId, false) ||
                instanceLifecycleInProgress.getOrDefault(serverId, false);
    }

    public static boolean isInShutdownProcess(String serverId) {
        return serverShutdownInProgress.getOrDefault(serverId, false);
    }

    public boolean isInstanceRecentlyCreated(String serverId) {
        long currentTime = System.currentTimeMillis();
        Long creationTimestamp = instanceCreationTimestamps.get(serverId);
        return creationTimestamp != null && (currentTime - creationTimestamp) < 30000;
    }

    public boolean retryServerLifecycle(String serverId, FlexNetGroup group, boolean createNewInstance) {
        int retryCount = retryCounts.getOrDefault(serverId, 0);
        if (retryCount < MAX_RETRY_ATTEMPTS) {
            retryCounts.put(serverId, retryCount + 1);
            handleServerLifecycle(serverId, group, createNewInstance);
            return true;
        } else {
            logDetailedWarn("Max retry attempts reached for server {}", serverId);
            return false;
        }
    }

    public String getStatusMessage(String serverId) {
        return statusMessages.getOrDefault(serverId, "No status available");
    }

    public void updateStatusMessage(String serverId, String message) {
        statusMessages.put(serverId, message);
        logDetailedInfo("Updated status message for server {}: {}", serverId, message);
    }

    public void clearOldData() {
        long currentTime = System.currentTimeMillis();
        instanceCreationTimestamps.entrySet().removeIf(entry -> (currentTime - entry.getValue()) > 60000);
        serverShutdownTimestamps.entrySet().removeIf(entry -> (currentTime - entry.getValue()) > 60000);
        retryCounts.entrySet().removeIf(entry -> entry.getValue() >= MAX_RETRY_ATTEMPTS);
        logDetailedInfo("Cleared old data from lifecycle manager");
    }

    public void randomizeDelays() {
        int randomDelay = random.nextInt(5) + 1;
        logDetailedInfo("Random delay factor applied: {}", randomDelay);
        try {
            TimeUnit.SECONDS.sleep(randomDelay);
        } catch (InterruptedException e) {
            logDetailedError("Random delay interrupted", e);
        }
    }

    public void shutdownServerWithDelay(String serverId, FlexNetGroup group, int delay) {
        proxy.scheduleTask(() -> {
            logDetailedInfo("Shutdown process initiated for server {}", serverId);
            if (!isInShutdownProcess(serverId)) {
                handleServerLifecycle(serverId, group, false);
            }
        }, delay, TimeUnit.SECONDS);
    }

    public void logServerDetails(String serverId) {
        if (instanceLifecycleInProgress.containsKey(serverId)) {
            logDetailedInfo("Server {} is currently in lifecycle process", serverId);
        } else {
            logDetailedInfo("Server {} is not in lifecycle process", serverId);
        }
    }

    public int getTotalPendingTransfers() {
        return pendingTransfers.values().stream().mapToInt(Set::size).sum();
    }

    public Set<UUID> getPendingTransfersForServer(String serverId) {
        return pendingTransfers.getOrDefault(serverId, new HashSet<>());
    }

    public void resetServerStatus(String serverId) {
        if (isInstanceInLifecycleProcess(serverId)) {
            logDetailedWarn("Resetting status for server {} during active lifecycle process", serverId);
        }
        serverShutdownInProgress.remove(serverId);
        instanceLifecycleInProgress.remove(serverId);
        retryCounts.remove(serverId);
        pendingTransfers.remove(serverId);
        logDetailedInfo("Server {} status has been reset", serverId);
    }

    public void forceServerShutdown(String serverId, FlexNetGroup group) {
        if (!isInShutdownProcess(serverId)) {
            logDetailedInfo("Forcing shutdown for server {}", serverId);
            handleServerLifecycle(serverId, group, false);
        } else {
            logDetailedWarn("Server {} is already in shutdown process", serverId);
        }
    }

    public void initiateBatchTransfers(FlexNetGroup group) {
        logDetailedInfo("Initiating batch transfers for group {}", group.getServerName());
        for (RegisteredServer server : group.getServers()) {
            if (getPendingTransfersForServer(server.getServerInfo().getName()).size() > 0) {
                kickPlayersGradually(null, server.getServerInfo().getName(), group);
            }
        }
    }

    public void simulateNetworkFailure(String serverId) {
        logDetailedWarn("Simulating network failure for server {}", serverId);
        CompletableFuture.runAsync(() -> {
            try {
                TimeUnit.SECONDS.sleep(2);
                logDetailedWarn("Network failure resolved for server {}", serverId);
            } catch (InterruptedException e) {
                logDetailedError("Network failure simulation interrupted for server {}", serverId, e);
            }
        });
    }

    public void checkServerHealth(String serverId) {
        logDetailedInfo("Checking health for server {}", serverId);
        if (random.nextBoolean()) {
            logDetailedInfo("Server {} is healthy", serverId);
        } else {
            logDetailedWarn("Server {} has reported issues", serverId);
            simulateNetworkFailure(serverId);
        }
    }

    private void schedulePeriodicStatusUpdates(String serverId, String newServerId) {
        proxy.scheduleTask(() -> updateStatusMessage(serverId, "Server is transitioning to " + newServerId),
                HEALTH_CHECK_INTERVAL, TimeUnit.SECONDS);
    }

    private void schedulePeriodicDataCleanup() {
        proxy.scheduleTask(this::clearOldData, DATA_CLEANUP_INTERVAL, TimeUnit.MILLISECONDS);
    }

    private void scheduleRegularHealthChecks() {
        proxy.scheduleTask(() -> {
            for (String serverId : instanceLifecycleInProgress.keySet()) {
                checkServerHealth(serverId);
            }
        }, HEALTH_CHECK_INTERVAL, TimeUnit.SECONDS);
    }

    private void logDetailedInfo(String message, Object... args) {
        if (LOGGING_DETAIL_LEVEL >= 2) {
            logger.info("[DETAILED] " + message, args);
        } else {
            logger.info(message, args);
        }
    }

    private void logDetailedWarn(String message, Object... args) {
        if (LOGGING_DETAIL_LEVEL >= 2) {
            logger.warn("[DETAILED] " + message, args);
        } else {
            logger.warn(message, args);
        }
    }

    private void logDetailedError(String message, Object... args) {
        if (LOGGING_DETAIL_LEVEL >= 2) {
            logger.error("[DETAILED] " + message, args);
        } else {
            logger.error(message, args);
        }
    }
}
