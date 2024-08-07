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

import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;
import org.slf4j.Logger;

@Slf4j
public class FlexNetVelocityInstanceController {

    private final FlexNetProxy proxy;
    private final FlexNetGroupManager groupManager;
    private final Logger logger;
    private final InstanceManager instanceManager;
    private final FlexNetConfig config;
    @Setter
    private InstanceLifecycleManager instanceLifecycleManager;

    private final Set<String> createdInstanceIdentifiers = new CopyOnWriteArraySet<>();
    private final ConcurrentHashMap<String, CompletableFuture<String>> creatingInstances = new ConcurrentHashMap<>();
    private boolean shouldStopProcessingFlag = false;
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
    }

    public void removeInstanceId(String instanceId) {
        if (createdInstanceIdentifiers.contains(instanceId)) {
            createdInstanceIdentifiers.remove(instanceId);
            logger.info("Instance id {} removed from createdInstanceIdentifiers.", instanceId);
        } else {
            logger.warn("Attempted to remove non-existing instance id {} from createdInstanceIdentifiers.", instanceId);
        }
    }

    // TODO: check the is necessary?
//    private void createServerCleanupTask() {
//        proxy.scheduleRepeatTask(() -> {
//            groupManager.getAllGroups()
//                    .stream()
//                    .filter(group -> group.getServerAmount() > 1)
//                    .forEach(group -> {
//                        HashSet<String> pendingDeleteIds = new HashSet<>();
//                        group.getAllServers()
//                                .forEach(entry -> {
//                                    if(entry.getValue().getPlayersConnected().isEmpty()) {
//                                        pendingDeleteIds.add(entry.getKey());
//                                    }
//                                });
//                        pendingDeleteIds.forEach(id -> {
//                            logger.info("createServerCleanupTask: Removing server {} from group {}", id, group.getServerName());
//                            proxy.removeServer(id, group);
//                            instanceManager.deleteInstance(id, (b) -> {});
//                        });
//                    });
//        }, 0L, 300L);
//    }

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
                    creatingInstances.remove(instanceKey);
                    future.complete(instanceId);
                }, template.getServerOnlineDelay());
            } else {
                // TODO: handle error
                logger.warn("Failed to create instance for group {}", group.getServerName());
                creatingInstances.remove(instanceKey);
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
    }

    private void removeExtraServers(FlexNetGroup group, int serversToRemove) {
        for (int i = 0; i < serversToRemove; i++) {
            RegisteredServer serverToRemove = group.getLowestPlayerServer(true);
            if (serverToRemove != null) {
                String serverId = serverToRemove.getServerInfo().getName();
                logger.info("Removing server {} from group {} due to low player count", serverId, group.getServerName());
                group.setValidServerCount(group.getValidServerCount() - 1);
                instanceLifecycleManager.handleServerLifecycle(serverId, group, false);
            }
        }
    }
}

