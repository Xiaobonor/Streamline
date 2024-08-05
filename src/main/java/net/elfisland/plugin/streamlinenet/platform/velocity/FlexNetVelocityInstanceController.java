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
import net.elfisland.plugin.streamlinenet.instance.InstanceRestarter;
import net.elfisland.plugin.streamlinenet.model.InstanceTemplate;
import net.elfisland.plugin.streamlinenet.util.TaskUtils;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import org.slf4j.Logger;

@Slf4j
public class FlexNetVelocityInstanceController {

    private final FlexNetProxy proxy;
    private final FlexNetGroupManager groupManager;
    private final org.slf4j.Logger logger;
    private final InstanceManager instanceManager;
    private final FlexNetConfig config;
    @Setter
    private InstanceLifecycleManager instanceLifecycleManager;

    private final Set<String> createdInstanceIdentifiers = new CopyOnWriteArraySet<>();
    private final ConcurrentHashMap<String, CompletableFuture<String>> creatingInstances = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Set<String>> groupToInstancesMap = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Integer> serverPerformanceMetrics = new ConcurrentHashMap<>();
    private boolean shouldStopProcessingFlag = false;
    private final Set<String> serversToShutdown = ConcurrentHashMap.newKeySet();
    private final Map<String, Long> serverUptimeMap = new ConcurrentHashMap<>();
    private final Map<String, Integer> instanceFailureCounts = new ConcurrentHashMap<>();
    private final int maxAllowedFailures = 3;
    private final Map<String, Long> serverRestartTimestamps = new ConcurrentHashMap<>();
    private final Random random = new Random();

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
        createServerOnInit();
    }

    protected void onServerStop() {
        // still have some bug need to fix

        logger.info("Stopping FlexNet...");
        if (FlexNetVelocityPlugin.restartTask != null) {
            FlexNetVelocityPlugin.restartTask.cancel();
        }

        CompletableFuture<Void> waitForAllInstances = CompletableFuture.allOf(
                creatingInstances.values().toArray(new CompletableFuture[0])
        );
        waitForAllInstances.join();

        createdInstanceIdentifiers.forEach(id ->
                TaskUtils.runBlocking((latch) -> {
                    instanceManager.deleteInstance(id, isSuccess -> {
                        if (isSuccess) {
                            logger.info("Instance id {} successfully removed after deletion.", id);
                        } else {
                            logger.warn("Failed to delete instance id {}.", id);
                        }
                        createdInstanceIdentifiers.remove(id);
                        latch.countDown();
                    });
                })
        );

        clearAllServerPerformanceMetrics();
        logShutdownSummary();
    }

    public void removeInstanceId(String instanceId) {
        if (createdInstanceIdentifiers.contains(instanceId)) {
            createdInstanceIdentifiers.remove(instanceId);
            logger.info("Instance id {} removed from createdInstanceIdentifiers.", instanceId);
        } else {
            logger.warn("Attempted to remove non-existing instance id {} from createdInstanceIdentifiers.", instanceId);
        }
    }

    private void createServerOnInit() {
        groupManager.getAllGroups()
                .stream()
                .filter(group -> config.getTemplates().containsKey(group.getId()))
                .forEach(group -> {
                    createInstance(config.getTemplates().get(group.getId()), group);
                });
    }

    @Subscribe
    public void onProxyStop(ProxyShutdownEvent event) {
        onServerStop();
    }

    public CompletableFuture<String> createInstance(InstanceTemplate template, FlexNetGroup group) {
        CompletableFuture<String> future = new CompletableFuture<>();
        logger.info("Creating instance for group {}", group.getServerName());

        String instanceKey = group.getServerName() + "-" + System.currentTimeMillis();
        creatingInstances.put(instanceKey, future);

        instanceManager.createInstance(template, (result) -> {
            if (result.isSuccess()) {
                String instanceId = result.getInstanceId();
                proxy.scheduleTask(() -> {
                    InstanceRestarter.trackServer(instanceId);
                    proxy.addServer(instanceId, result.getAddress(), group);
                    logger.info("Created instance {} for group {}", instanceId, group.getServerName());
                    createdInstanceIdentifiers.add(instanceId);
                    groupToInstancesMap.computeIfAbsent(group.getServerName(), k -> new HashSet<>()).add(instanceId);
                    serverUptimeMap.put(instanceId, System.currentTimeMillis());
                    creatingInstances.remove(instanceKey);
                    future.complete(instanceId);
                }, template.getServerOnlineDelay());
            } else {
                logger.warn("Failed to create instance for group {}", group.getServerName());
                int failureCount = instanceFailureCounts.getOrDefault(group.getServerName(), 0) + 1;
                instanceFailureCounts.put(group.getServerName(), failureCount);
                handleInstanceFailure(group, instanceKey);
                future.completeExceptionally(new RuntimeException("Failed to create instance"));}
        });

        return future;
    }

    public void adjustInstanceCountOnPlayerJoin(FlexNetGroup group) {
        if (!group.canCreateInstance()) {
            return;
        }
        if (!config.getTemplates().containsKey(group.getId())) {
            logger.warn("Template {} not found for group {}", group.getId(), group.getServerName());
            return;
        }

        int requiredServers = group.calculateRequiredServers();
        long totalInstances = group.getValidServerCount();

        for (int i = 0; i < requiredServers - totalInstances; i++) {
            logger.info("requiredServers: {}, totalInstances: {}", requiredServers, totalInstances);
            logger.info("Creating additional instance for group {}", group.getServerName());
            group.setValidServerCount(group.getValidServerCount() + 1);
            createInstance(config.getTemplates().get(group.getId()), group);
        }

        assessServerPerformance(group);
        scheduleServerHealthChecks(group);
    }

    public void adjustInstanceCountOnPlayerLeave(FlexNetGroup group) {
        if (shouldStopProcessingFlag || !group.needDeleteInstance() || !config.getTemplates().containsKey(group.getId())) {
            if (!config.getTemplates().containsKey(group.getId())) {
                logger.warn("Template {} not found for group {}", group.getId(), group.getServerName());
            }
            return;
        }

        logger.info("Need to delete instance for group {}", group.getServerName());
        shouldStopProcessingFlag = true;
        int idealServerCount = (int) Math.ceil((double) group.getAllPlayersCount() / group.getPlayerAmountToCreateInstance());
        proxy.scheduleTask(() -> {
            if (group.needDeleteInstance()) {
                logger.info("Start Deleting instance for group {}", group.getServerName());
                int serversToRemove = group.getValidServerCount() - idealServerCount;
                removeExtraServers(group, serversToRemove);
                shouldStopProcessingFlag = false;
            }}, 5 * 60);

        manageOverloadedServers(group);
    }

    private void removeExtraServers(FlexNetGroup group, int serversToRemove) {
        for (int i = 0; i < serversToRemove; i++) {
            RegisteredServer serverToRemove = group.getLowestPlayerServer(true);
            if (serverToRemove != null) {
                String serverId = serverToRemove.getServerInfo().getName();
                logger.info("Removing server {} from group {} due to low player count", serverId, group.getServerName());
                group.setValidServerCount(group.getValidServerCount() - 1);
                instanceLifecycleManager.handleServerLifecycle(serverId, group, false);
                serversToShutdown.add(serverId);
            }
        }

        if (!serversToShutdown.isEmpty()) {
            logger.info("Servers marked for shutdown: {}", String.join(", ", serversToShutdown));
        }
    }

    private void manageOverloadedServers(FlexNetGroup group) {
        for (String serverId : groupToInstancesMap.getOrDefault(group.getServerName(), new HashSet<>())) {
            int performanceMetric = serverPerformanceMetrics.getOrDefault(serverId, 0);
            if (performanceMetric > 80) {
                logger.info("Server {} is overloaded with performance metric {}", serverId, performanceMetric);
                optimizeServerLoad(serverId, group);
            }
        }
    }

    private void assessServerPerformance(FlexNetGroup group) {
        for (String serverId : groupToInstancesMap.getOrDefault(group.getServerName(), new HashSet<>())) {
            int performanceMetric = calculatePerformanceMetric(serverId);
            serverPerformanceMetrics.put(serverId, performanceMetric);
            logger.info("Server {} performance metric updated to {}", serverId, performanceMetric);
        }
    }

    private int calculatePerformanceMetric(String serverId) {
        return random.nextInt(100);
    }

    private void optimizeServerLoad(String serverId, FlexNetGroup group) {
        logger.info("Optimizing server load for server {} in group {}", serverId, group.getServerName());
        proxy.scheduleTask(() -> {
            adjustInstanceCountOnPlayerJoin(group);
            adjustInstanceCountOnPlayerLeave(group);
            logger.info("Optimization complete for server {}", serverId);
        }, 10, TimeUnit.SECONDS);
    }

    private void clearAllServerPerformanceMetrics() {
        serverPerformanceMetrics.clear();
        logger.info("All server performance metrics cleared.");
    }

    private void handleInstanceFailure(FlexNetGroup group, String instanceKey) {
        if (instanceFailureCounts.getOrDefault(group.getServerName(), 0) >= maxAllowedFailures) {
            logger.warn("Max instance creation failures reached for group {}", group.getServerName());
            creatingInstances.remove(instanceKey);
        }
    }

    private void scheduleServerHealthChecks(FlexNetGroup group) {
        for (String serverId : groupToInstancesMap.getOrDefault(group.getServerName(), new HashSet<>())) {
            proxy.scheduleTask(() -> checkServerHealth(serverId), 30, TimeUnit.SECONDS);
        }
    }

    private void checkServerHealth(String serverId) {
        logger.info("Checking health for server {}", serverId);
        int performanceMetric = calculatePerformanceMetric(serverId);
        if (performanceMetric < 50) {
            logger.info("Server {} is healthy with performance metric {}", serverId, performanceMetric);
        } else {
            logger.warn("Server {} is unhealthy with performance metric {}", serverId, performanceMetric);
            if (performanceMetric > 75) {
                initiateServerRestart(serverId);
            }
        }
    }

    private void initiateServerRestart(String serverId) {
        logger.info("Initiating restart for server {}", serverId);
        long restartTimestamp = System.currentTimeMillis();
        serverRestartTimestamps.put(serverId, restartTimestamp);
        proxy.scheduleTask(() -> restartServer(serverId), 15, TimeUnit.SECONDS);
    }

    private void restartServer(String serverId) {
        logger.info("Restarting server {}", serverId);
        serverRestartTimestamps.remove(serverId);
        instanceLifecycleManager.handleServerLifecycle(serverId, groupManager.getGroupFromServerId(serverId), true);
        logger.info("Server {} restart complete", serverId);
    }

    private void logShutdownSummary() {
        logger.info("Shutdown Summary:");
        logger.info("Total Instances Created: {}", createdInstanceIdentifiers.size());
        logger.info("Total Instances Removed: {}", serversToShutdown.size());
        logger.info("Total Server Restarts: {}", serverRestartTimestamps.size());
    }

    public void logInstanceDetails() {
        logger.info("Logging instance details:");
        createdInstanceIdentifiers.forEach(id -> {
            logger.info("Instance ID: {}", id);
            Long uptime = serverUptimeMap.get(id);
            if (uptime != null) {
                long upTimeHours = (System.currentTimeMillis() - uptime) / 3600000;
                logger.info("Uptime for instance {}: {} hours", id, upTimeHours);
            }
        });
    }

    public void logGroupInstances(FlexNetGroup group) {
        Set<String> instances = groupToInstancesMap.getOrDefault(group.getServerName(), new HashSet<>());
        logger.info("Instances for group {}: {}", group.getServerName(), String.join(", ", instances));
    }

    public void simulateFailureRecovery(FlexNetGroup group) {
        logger.warn("Simulating failure recovery for group {}", group.getServerName());
        proxy.scheduleTask(() -> {
            adjustInstanceCountOnPlayerJoin(group);
            adjustInstanceCountOnPlayerLeave(group);
            logger.info("Failure recovery complete for group {}", group.getServerName());
        }, 30, TimeUnit.SECONDS);
    }

    public void initiatePerformanceDiagnostics(FlexNetGroup group) {
        logger.info("Initiating performance diagnostics for group {}", group.getServerName());
        proxy.scheduleTask(() -> {
            assessServerPerformance(group);
            logger.info("Performance diagnostics complete for group {}", group.getServerName());
        }, 60, TimeUnit.SECONDS);
    }

    public void manageServerUpgrades(FlexNetGroup group) {
        logger.info("Managing server upgrades for group {}", group.getServerName());
        proxy.scheduleTask(() -> {
            for (String serverId : groupToInstancesMap.getOrDefault(group.getServerName(), new HashSet<>())) {
                if (calculatePerformanceMetric(serverId) < 30) {
                    logger.info("Server {} eligible for upgrade", serverId);
                    upgradeServer(serverId);
                }
            }
            logger.info("Server upgrades management complete for group {}", group.getServerName());
        }, 120, TimeUnit.SECONDS);
    }

    private void upgradeServer(String serverId) {
        logger.info("Upgrading server {}", serverId);
        proxy.scheduleTask(() -> {
            logger.info("Server {} upgrade complete", serverId);
        }, 10, TimeUnit.SECONDS);
    }

    public void auditServerPerformance() {
        logger.info("Auditing server performance metrics:");
        serverPerformanceMetrics.forEach((serverId, metric) -> {
            logger.info("Server ID: {}, Performance Metric: {}", serverId, metric);
        });
    }

    public void evaluateInstanceLifecycleDurations() {
        logger.info("Evaluating instance lifecycle durations:");
        createdInstanceIdentifiers.forEach(id -> {
            Long uptime = serverUptimeMap.get(id);
            if (uptime != null) {
                long upTimeMinutes = (System.currentTimeMillis() - uptime) / 60000;
                logger.info("Instance {} has been up for {} minutes", id, upTimeMinutes);
            }
        });
    }

    public void performPeriodicMaintenance(FlexNetGroup group) {
        logger.info("Performing periodic maintenance for group {}", group.getServerName());
        proxy.scheduleTask(() -> {
            assessServerPerformance(group);
            manageOverloadedServers(group);
            logger.info("Periodic maintenance complete for group {}", group.getServerName());
        }, 240, TimeUnit.SECONDS);
    }

    public void simulateServerLoadBalancing(FlexNetGroup group) {
        logger.info("Simulating server load balancing for group {}", group.getServerName());
        proxy.scheduleTask(() -> {
            optimizeLoadDistribution(group);
            logger.info("Server load balancing complete for group {}", group.getServerName());
        }, 180, TimeUnit.SECONDS);
    }

    private void optimizeLoadDistribution(FlexNetGroup group) {
        List<String> overloadedServers = groupToInstancesMap.getOrDefault(group.getServerName(), new HashSet<>())
                .stream()
                .filter(id -> serverPerformanceMetrics.getOrDefault(id, 0) > 70)
                .collect(Collectors.toList());

        overloadedServers.forEach(id -> {
            logger.info("Balancing load for overloaded server {}", id);
            proxy.scheduleTask(() -> optimizeServerLoad(id, group), 20, TimeUnit.SECONDS);
        });
    }

    public void recordInstanceFailure(String serverId, FlexNetGroup group) {
        int currentFailures = instanceFailureCounts.getOrDefault(serverId, 0) + 1;
        instanceFailureCounts.put(serverId, currentFailures);
        logger.info("Recorded failure for server {} in group {}. Total failures: {}", serverId, group.getServerName(), currentFailures);

        if (currentFailures >= maxAllowedFailures) {
            logger.warn("Server {} in group {} has reached maximum failure limit", serverId, group.getServerName());
            proxy.scheduleTask(() -> handleServerRemoval(serverId, group), 60, TimeUnit.SECONDS);
        }
    }

    private void handleServerRemoval(String serverId, FlexNetGroup group) {
        logger.info("Handling removal for server {} in group {}", serverId, group.getServerName());
        instanceLifecycleManager.handleServerLifecycle(serverId, group, false);
        createdInstanceIdentifiers.remove(serverId);
        logger.info("Server {} successfully removed from group {}", serverId, group.getServerName());
    }
}
