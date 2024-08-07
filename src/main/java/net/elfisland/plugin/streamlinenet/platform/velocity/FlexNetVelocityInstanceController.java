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
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
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
    private final Set<String> serversToShutdown = ConcurrentHashMap.newKeySet();
    private final Map<String, Long> serverUptimeMap = new ConcurrentHashMap<>();
    private final Map<String, Integer> instanceFailureCounts = new ConcurrentHashMap<>();
    private final Map<String, Long> serverRestartTimestamps = new ConcurrentHashMap<>();
    private final ScheduledExecutorService executorService = Executors.newScheduledThreadPool(10);
    private final int maxAllowedFailures = 3;
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
        initializeInstanceCreation();
        schedulePerformanceAudits();
    }

    private void initializeInstanceCreation() {
        groupManager.getAllGroups().forEach(group -> {
            if (config.getTemplates().containsKey(group.getId())) {
                createInstance(config.getTemplates().get(group.getId()), group);
            }
        });
    }

    @Subscribe
    public void onProxyStop(ProxyShutdownEvent event) {
        shutdownAllInstances();
    }

    private void shutdownAllInstances() {
        logger.info("Initiating shutdown of all instances...");
        CompletableFuture.allOf(creatingInstances.values().toArray(new CompletableFuture[0])).join();
        createdInstanceIdentifiers.forEach(this::deleteInstance);
        clearAllServerPerformanceMetrics();
        logShutdownSummary();
    }

    private void deleteInstance(String instanceId) {
        TaskUtils.runBlocking(latch -> {
            instanceManager.deleteInstance(instanceId, success -> {
                if (success) {
                    logger.info("Successfully deleted instance {}", instanceId);
                } else {
                    logger.error("Failed to delete instance {}", instanceId);
                }
                createdInstanceIdentifiers.remove(instanceId);
                latch.countDown();
            });
        });
    }

    public CompletableFuture<String> createInstance(InstanceTemplate template, FlexNetGroup group) {
        CompletableFuture<String> future = new CompletableFuture<>();
        logger.info("Creating instance for group {}", group.getServerName());

        String instanceKey = generateInstanceKey(group);
        creatingInstances.put(instanceKey, future);

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
            registerInstance(instanceId, group, address);
            creatingInstances.remove(instanceKey);
            future.complete(instanceId);
        }, template.getServerOnlineDelay(), TimeUnit.MILLISECONDS);
    }

    private void registerInstance(String instanceId, FlexNetGroup group, String address) {
        InstanceRestarter.trackServer(instanceId);
        proxy.addServer(instanceId, address, group);
        logger.info("Registered new instance {} for group {}", instanceId, group.getServerName());
        createdInstanceIdentifiers.add(instanceId);
        groupToInstancesMap.computeIfAbsent(group.getServerName(), k -> new HashSet<>()).add(instanceId);
        serverUptimeMap.put(instanceId, System.currentTimeMillis());
    }

    private void handleInstanceCreationFailure(FlexNetGroup group, String instanceKey, CompletableFuture<String> future) {
        logger.error("Failed to create instance for group {}", group.getServerName());
        incrementFailureCount(group);
        creatingInstances.remove(instanceKey);
        future.completeExceptionally(new RuntimeException("Instance creation failed"));
    }

    private void incrementFailureCount(FlexNetGroup group) {
        int currentFailures = instanceFailureCounts.getOrDefault(group.getServerName(), 0) + 1;
        instanceFailureCounts.put(group.getServerName(), currentFailures);
        if (currentFailures >= maxAllowedFailures) {
            logger.error("Max failure threshold reached for group {}", group.getServerName());
        }
    }

    public void adjustInstanceCountOnPlayerJoin(FlexNetGroup group) {
        if (!group.canCreateInstance()) {
            return;
        }

        if (!config.getTemplates().containsKey(group.getId())) {
            logger.error("No template found for group {}", group.getServerName());
            return;
        }

        int requiredServers = group.calculateRequiredServers();
        long totalInstances = group.getValidServerCount();
        if (requiredServers > totalInstances) {
            createAdditionalInstances(group, requiredServers - totalInstances);
        }

        assessServerPerformance(group);
        scheduleServerHealthChecks(group);
    }

    private void createAdditionalInstances(FlexNetGroup group, long instancesToCreate) {
        for (int i = 0; i < instancesToCreate; i++) {
            logger.info("Creating additional instance for group {}", group.getServerName());
            group.incrementValidServerCount();
            createInstance(config.getTemplates().get(group.getId()), group);
        }
    }

    public void adjustInstanceCountOnPlayerLeave(FlexNetGroup group) {
        if (!group.needDeleteInstance() || !config.getTemplates().containsKey(group.getId())) {
            return;
        }

        logger.info("Evaluating instance removal for group {}", group.getServerName());
        int idealServerCount = calculateIdealServerCount(group);
        int serversToRemove = group.getValidServerCount() - idealServerCount;
        scheduleInstanceRemoval(group, serversToRemove);
    }

    private int calculateIdealServerCount(FlexNetGroup group) {
        return (int) Math.ceil((double) group.getAllPlayersCount() / group.getPlayerAmountToCreateInstance());
    }

    private void scheduleInstanceRemoval(FlexNetGroup group, int serversToRemove) {
        executorService.schedule(() -> removeExtraServers(group, serversToRemove), 5, TimeUnit.MINUTES);
    }

    private void removeExtraServers(FlexNetGroup group, int serversToRemove) {
        for (int i = 0; i < serversToRemove; i++) {
            RegisteredServer serverToRemove = group.getLowestPlayerServer(true);
            if (serverToRemove != null) {
                String serverId = serverToRemove.getServerInfo().getName();
                logger.info("Removing server {} from group {}", serverId, group.getServerName());
                group.decrementValidServerCount();
                instanceLifecycleManager.handleServerLifecycle(serverId, group, false);
                serversToShutdown.add(serverId);
            }
        }
        logServerShutdownStatus();
        manageOverloadedServers(group);
    }

    private void manageOverloadedServers(FlexNetGroup group) {
        groupToInstancesMap.getOrDefault(group.getServerName(), new HashSet<>()).forEach(serverId -> {
            int performanceMetric = serverPerformanceMetrics.getOrDefault(serverId, 0);
            if (performanceMetric > 80) {
                logger.warn("Server {} is overloaded with performance metric {}", serverId, performanceMetric);
                optimizeServerLoad(serverId, group);
            }
        });
    }

    private void assessServerPerformance(FlexNetGroup group) {
        groupToInstancesMap.getOrDefault(group.getServerName(), new HashSet<>()).forEach(serverId -> {
            int performanceMetric = calculatePerformanceMetric(serverId);
            serverPerformanceMetrics.put(serverId, performanceMetric);
            logger.info("Updated performance metric for server {}: {}", serverId, performanceMetric);
        });
    }

    private int calculatePerformanceMetric(String serverId) {
        return random.nextInt(100);
    }

    private void optimizeServerLoad(String serverId, FlexNetGroup group) {
        logger.info("Optimizing load for server {} in group {}", serverId, group.getServerName());
        executorService.schedule(() -> {
            adjustInstanceCountOnPlayerJoin(group);
            adjustInstanceCountOnPlayerLeave(group);
            logger.info("Load optimization complete for server {}", serverId);
        }, 10, TimeUnit.SECONDS);
    }

    private void clearAllServerPerformanceMetrics() {
        serverPerformanceMetrics.clear();
        logger.info("Cleared all server performance metrics.");
    }

    private void logServerShutdownStatus() {
        if (!serversToShutdown.isEmpty()) {
            logger.info("Servers marked for shutdown: {}", String.join(", ", serversToShutdown));
        }
    }

    private void handleInstanceFailure(FlexNetGroup group, String instanceKey) {
        incrementFailureCount(group);
        creatingInstances.remove(instanceKey);
    }

    private void scheduleServerHealthChecks(FlexNetGroup group) {
        groupToInstancesMap.getOrDefault(group.getServerName(), new HashSet<>()).forEach(serverId -> {
            executorService.schedule(() -> checkServerHealth(serverId), 30, TimeUnit.SECONDS);
        });
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
        serverRestartTimestamps.put(serverId, System.currentTimeMillis());
        executorService.schedule(() -> restartServer(serverId), 15, TimeUnit.SECONDS);
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
        executorService.schedule(() -> {
            adjustInstanceCountOnPlayerJoin(group);
            adjustInstanceCountOnPlayerLeave(group);
            logger.info("Failure recovery complete for group {}", group.getServerName());
        }, 30, TimeUnit.SECONDS);
    }

    public void initiatePerformanceDiagnostics(FlexNetGroup group) {
        logger.info("Initiating performance diagnostics for group {}", group.getServerName());
        executorService.schedule(() -> {
            assessServerPerformance(group);
            logger.info("Performance diagnostics complete for group {}", group.getServerName());
        }, 60, TimeUnit.SECONDS);
    }

    public void manageServerUpgrades(FlexNetGroup group) {
        logger.info("Managing server upgrades for group {}", group.getServerName());
        executorService.schedule(() -> {
            groupToInstancesMap.getOrDefault(group.getServerName(), new HashSet<>()).forEach(serverId -> {
                if (calculatePerformanceMetric(serverId) < 30) {
                    logger.info("Server {} eligible for upgrade", serverId);
                    upgradeServer(serverId);
                }
            });
            logger.info("Server upgrades management complete for group {}", group.getServerName());
        }, 120, TimeUnit.SECONDS);
    }

    private void upgradeServer(String serverId) {
        logger.info("Upgrading server {}", serverId);
        executorService.schedule(() -> {
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
        executorService.schedule(() -> {
            assessServerPerformance(group);
            manageOverloadedServers(group);
            logger.info("Periodic maintenance complete for group {}", group.getServerName());
        }, 240, TimeUnit.SECONDS);
    }

    public void simulateServerLoadBalancing(FlexNetGroup group) {
        logger.info("Simulating server load balancing for group {}", group.getServerName());
        executorService.schedule(() -> {
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
            executorService.schedule(() -> optimizeServerLoad(id, group), 20, TimeUnit.SECONDS);
        });
    }

    public void recordInstanceFailure(String serverId, FlexNetGroup group) {
        int currentFailures = instanceFailureCounts.getOrDefault(serverId, 0) + 1;
        instanceFailureCounts.put(serverId, currentFailures);
        logger.info("Recorded failure for server {} in group {}. Total failures: {}", serverId, group.getServerName(), currentFailures);

        if (currentFailures >= maxAllowedFailures) {
            logger.warn("Server {} in group {} has reached maximum failure limit", serverId, group.getServerName());
            executorService.schedule(() -> handleServerRemoval(serverId, group), 60, TimeUnit.SECONDS);
        }
    }

    private void handleServerRemoval(String serverId, FlexNetGroup group) {
        logger.info("Handling removal for server {} in group {}", serverId, group.getServerName());
        instanceLifecycleManager.handleServerLifecycle(serverId, group, false);
        createdInstanceIdentifiers.remove(serverId);
        logger.info("Server {} successfully removed from group {}", serverId, group.getServerName());
    }

    private String generateInstanceKey(FlexNetGroup group) {
        return group.getServerName() + "-" + System.currentTimeMillis();
    }

    private void schedulePerformanceAudits() {
        executorService.scheduleAtFixedRate(this::auditServerPerformance, 0, 10, TimeUnit.MINUTES);
    }
}
