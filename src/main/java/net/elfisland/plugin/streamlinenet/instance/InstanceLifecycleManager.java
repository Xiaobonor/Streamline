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
    private final FlexNetConfig config;
    private final Logger logger;
    private static final HashMap<String, Boolean> serverShutdownInProgress = new HashMap<>();
    private static final HashMap<String, Boolean> instanceLifecycleInProgress = new HashMap<>();
    private static final ConcurrentHashMap<String, Long> serverShutdownTimestamps = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, Long> instanceCreationTimestamps = new ConcurrentHashMap<>();
    private static final HashMap<String, Integer> retryCounts = new HashMap<>();
    private static final HashMap<String, String> statusMessages = new HashMap<>();
    private static final HashMap<String, Set<UUID>> pendingTransfers = new HashMap<>();
    private Random random = new Random();
    private static final int MAX_SHUTDOWN_DELAY = 120;

    public InstanceLifecycleManager(FlexNetProxy proxy, InstanceManager instanceManager,
                                    FlexNetVelocityInstanceController instanceController, JoinNewCommand joinNewCommand,
                                    FlexNetConfig config, Logger logger) {
        this.proxy = proxy;
        this.instanceManager = instanceManager;
        this.config = config;
        this.logger = logger;
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
}
