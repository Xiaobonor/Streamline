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
}
