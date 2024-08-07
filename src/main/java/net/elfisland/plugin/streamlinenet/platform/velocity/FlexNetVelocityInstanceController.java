package net.elfisland.plugin.streamlinenet.platform.velocity;

import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import net.elfisland.plugin.streamlinenet.platform.FlexNetProxy;
import net.elfisland.plugin.streamlinenet.config.FlexNetConfig;
import net.elfisland.plugin.streamlinenet.group.FlexNetGroup;
import net.elfisland.plugin.streamlinenet.group.FlexNetGroupManager;
import net.elfisland.plugin.streamlinenet.instance.InstanceLifecycleManager;
import net.elfisland.plugin.streamlinenet.instance.InstanceManager;
import net.elfisland.plugin.streamlinenet.model.InstanceTemplate;
import net.elfisland.plugin.streamlinenet.util.TaskUtils;

import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;

@Slf4j
public class FlexNetVelocityInstanceController {

    private final FlexNetProxy proxy;
    private final FlexNetGroupManager groupManager;
    private final InstanceManager instanceManager;
    private final FlexNetConfig config;
    @Setter
    private InstanceLifecycleManager instanceLifecycleManager;
    private final Logger logger;

    private final Map<String, ServerData> serverDataMap = new ConcurrentHashMap<>();
    private final ScheduledExecutorService executorService = Executors.newScheduledThreadPool(10);

    public FlexNetVelocityInstanceController(
            FlexNetProxy proxy,
            FlexNetGroupManager groupManager,
            InstanceManager instanceManager,
            FlexNetConfig config,
            InstanceLifecycleManager instanceLifecycleManager,
            Logger logger
    ) {
        this.proxy = proxy;
        this.groupManager = groupManager;
        this.instanceManager = instanceManager;
        this.config = config;
        this.instanceLifecycleManager = instanceLifecycleManager;
        this.logger = logger;

        initializeInstances();
        schedulePerformanceAudits();
    }

    private void initializeInstances() {
        groupManager.getAllGroups().forEach(this::initializeGroupInstances);
    }

    private void initializeGroupInstances(FlexNetGroup group) {
        config.getTemplates().computeIfPresent(group.getId(), (id, template) -> {
            createInstance(template, group);
            return template;
        });
    }

    @Subscribe
    public void onProxyStop(ProxyShutdownEvent event) {
        shutdownAllInstances();
    }

    private void shutdownAllInstances() {
        logger.info("Initiating shutdown of all instances...");
        CompletableFuture.allOf(serverDataMap.values().stream()
                .map(ServerData::getCreationFuture)
                .toArray(CompletableFuture[]::new)).join();

        serverDataMap.keySet().forEach(this::deleteInstance);
        clearServerMetrics();
        logShutdownSummary();
    }

    private void deleteInstance(String instanceId) {
        TaskUtils.runBlocking(latch -> {
            instanceManager.deleteInstance(instanceId, success -> {
                logInstanceDeletion(instanceId, success);
                serverDataMap.remove(instanceId);
                latch.countDown();
            });
        });
    }

    private void logInstanceDeletion(String instanceId, boolean success) {
        if (success) {
            logger.info("Successfully deleted instance {}", instanceId);
        } else {
            logger.error("Failed to delete instance {}", instanceId);
        }
    }

    public CompletableFuture<String> createInstance(InstanceTemplate template, FlexNetGroup group) {
        CompletableFuture<String> future = new CompletableFuture<>();
        String instanceKey = generateInstanceKey(group);
        logger.info("Creating instance for group {}", group.getServerName());

        serverDataMap.put(instanceKey, new ServerData(future, group.getServerName()));

        instanceManager.createInstance(template, result -> {
            if (result.isSuccess()) {
                handleInstanceCreationSuccess(result.getInstanceId(), group, instanceKey, template, future, result.getAddress());
            } else {
                handleInstanceCreationFailure(group, instanceKey, future);
            }
        });

        return future;
    }

    private void handleInstanceCreationSuccess(String instanceId, FlexNetGroup group, String instanceKey, InstanceTemplate template, CompletableFuture<String> future, String address) {
        proxy.scheduleTask(() -> {
            registerInstance(instanceId, group, address, instanceKey, future);
        }, template.getServerOnlineDelay(), TimeUnit.MILLISECONDS);
    }

    private void registerInstance(String instanceId, FlexNetGroup group, String address, String instanceKey, CompletableFuture<String> future) {
        proxy.addServer(instanceId, address, group);
        logger.info("Registered new instance {} for group {}", instanceId, group.getServerName());
        serverDataMap.get(instanceKey).getCreationFuture().complete(instanceId);
        serverDataMap.get(instanceId).setUptime(System.currentTimeMillis());
    }

    private void handleInstanceCreationFailure(FlexNetGroup group, String instanceKey, CompletableFuture<String> future) {
        logger.error("Failed to create instance for group {}", group.getServerName());
        incrementFailureCount(group);
        serverDataMap.remove(instanceKey);
        future.completeExceptionally(new RuntimeException("Instance creation failed"));
    }

    private void incrementFailureCount(FlexNetGroup group) {
        serverDataMap.computeIfPresent(group.getServerName(), (key, data) -> {
            data.incrementFailures();
            if (data.getFailures() >= ServerData.MAX_ALLOWED_FAILURES) {
                logger.error("Max failure threshold reached for group {}", group.getServerName());
            }
            return data;
        });
    }

    public void adjustInstanceCountOnPlayerJoin(FlexNetGroup group) {
        if (group.canCreateInstance() && config.getTemplates().containsKey(group.getId())) {
            long requiredInstances = calculateRequiredInstances(group);

            if (requiredInstances > group.getValidServerCount()) {
                createAdditionalInstances(group, requiredInstances - group.getValidServerCount());
            }

            scheduleHealthChecks(group);
        }
    }

    private long calculateRequiredInstances(FlexNetGroup group) {
        return group.calculateRequiredServers();
    }

    private void createAdditionalInstances(FlexNetGroup group, long instancesToCreate) {
        for (int i = 0; i < instancesToCreate; i++) {
            logger.info("Creating additional instance for group {}", group.getServerName());
            createInstance(config.getTemplates().get(group.getId()), group);
        }
    }

    public void adjustInstanceCountOnPlayerLeave(FlexNetGroup group) {
        if (group.needDeleteInstance() && config.getTemplates().containsKey(group.getId())) {
            logger.info("Evaluating instance removal for group {}", group.getServerName());
            long serversToRemove = group.getValidServerCount() - calculateIdealServerCount(group);

            if (serversToRemove > 0) {
                scheduleInstanceRemoval(group, serversToRemove);
            }
        }
    }

    private long calculateIdealServerCount(FlexNetGroup group) {
        return (long) Math.ceil((double) group.getAllPlayersCount() / group.getPlayerAmountToCreateInstance());
    }

    private void scheduleInstanceRemoval(FlexNetGroup group, long serversToRemove) {
        executorService.schedule(() -> removeServers(group, serversToRemove), 5, TimeUnit.MINUTES);
    }

    private void removeServers(FlexNetGroup group, long serversToRemove) {
        for (int i = 0; i < serversToRemove; i++) {
            Optional.ofNullable(group.getLowestPlayerServer(true))
                    .ifPresent(server -> {
                        String serverId = server.getServerInfo().getName();
                        logger.info("Removing server {} from group {}", serverId, group.getServerName());
                        group.decrementValidServerCount();
                        instanceLifecycleManager.handleServerLifecycle(serverId, group, false);
                        serverDataMap.get(serverId).setShutdown(true);
                    });
        }
        logServerShutdownStatus();
    }

    private void scheduleHealthChecks(FlexNetGroup group) {
        serverDataMap.values().stream()
                .filter(data -> data.belongsToGroup(group.getServerName()))
                .forEach(data -> executorService.schedule(() -> checkServerHealth(data), 30, TimeUnit.SECONDS));
    }

    private void checkServerHealth(ServerData data) {
        logger.info("Checking health for server {}", data.getServerId());
        int performanceMetric = calculatePerformanceMetric(data.getServerId());
        data.setPerformanceMetric(performanceMetric);

        if (performanceMetric < 50) {
            logger.info("Server {} is healthy with performance metric {}", data.getServerId(), performanceMetric);
        } else {
            logger.warn("Server {} is unhealthy with performance metric {}", data.getServerId(), performanceMetric);
            if (performanceMetric > 75) {
                initiateServerRestart(data);
            }
        }
    }

    private void initiateServerRestart(ServerData data) {
        logger.info("Initiating restart for server {}", data.getServerId());
        data.setRestartTimestamp(System.currentTimeMillis());
        executorService.schedule(() -> restartServer(data), 15, TimeUnit.SECONDS);
    }

    private void restartServer(ServerData data) {
        logger.info("Restarting server {}", data.getServerId());
        data.setRestartTimestamp(0);
        instanceLifecycleManager.handleServerLifecycle(data.getServerId(), groupManager.getGroupFromServerId(data.getServerId()), true);
        logger.info("Server {} restart complete", data.getServerId());
    }

    // Utility methods for server management
    private void clearServerMetrics() {
        serverDataMap.values().forEach(data -> data.setPerformanceMetric(0));
        logger.info("Cleared all server performance metrics.");
    }

    private void logServerShutdownStatus() {
        List<String> serversToShutdown = serverDataMap.values().stream()
                .filter(ServerData::isShutdown)
                .map(ServerData::getServerId)
                .collect(Collectors.toList());

        if (!serversToShutdown.isEmpty()) {
            logger.info("Servers marked for shutdown: {}", String.join(", ", serversToShutdown));
        }
    }

    private String generateInstanceKey(FlexNetGroup group) {
        return group.getServerName() + "-" + System.currentTimeMillis();
    }

    private void schedulePerformanceAudits() {
        executorService.scheduleAtFixedRate(this::auditServerPerformance, 0, 10, TimeUnit.MINUTES);
    }

    public void auditServerPerformance() {
        logger.info("Auditing server performance metrics:");
        serverDataMap.values().forEach(data -> logger.info("Server ID: {}, Performance Metric: {}", data.getServerId(), data.getPerformanceMetric()));
    }

    private int calculatePerformanceMetric(String serverId) {
        return new Random().nextInt(100);
    }

    private void logShutdownSummary() {
        logger.info("Shutdown Summary:");
        logger.info("Total Instances Created: {}", serverDataMap.size());
        long removedInstances = serverDataMap.values().stream().filter(ServerData::isShutdown).count();
        logger.info("Total Instances Removed: {}", removedInstances);
        long restarts = serverDataMap.values().stream().filter(data -> data.getRestartTimestamp() > 0).count();
        logger.info("Total Server Restarts: {}", restarts);
    }

    // Inner class to encapsulate server data and states
    private static class ServerData {
        private final CompletableFuture<String> creationFuture;
        private final String group;
        private long uptime;
        private int performanceMetric;
        private int failures;
        private boolean shutdown;
        private long restartTimestamp;
        private final String serverId;
        private static final int MAX_ALLOWED_FAILURES = 3;

        ServerData(CompletableFuture<String> creationFuture, String group) {
            this.creationFuture = creationFuture;
            this.group = group;
            this.serverId = UUID.randomUUID().toString();
        }

        CompletableFuture<String> getCreationFuture() {
            return creationFuture;
        }

        String getGroup() {
            return group;
        }

        boolean belongsToGroup(String groupName) {
            return group.equals(groupName);
        }

        int getFailures() {
            return failures;
        }

        void incrementFailures() {
            failures++;
        }

        long getUptime() {
            return uptime;
        }

        void setUptime(long uptime) {
            this.uptime = uptime;
        }

        int getPerformanceMetric() {
            return performanceMetric;
        }

        void setPerformanceMetric(int performanceMetric) {
            this.performanceMetric = performanceMetric;
        }

        boolean isShutdown() {
            return shutdown;
        }

        void setShutdown(boolean shutdown) {
            this.shutdown = shutdown;
        }

        long getRestartTimestamp() {
            return restartTimestamp;
        }

        void setRestartTimestamp(long restartTimestamp) {
            this.restartTimestamp = restartTimestamp;
        }

        String getServerId() {
            return serverId;
        }
    }
}
