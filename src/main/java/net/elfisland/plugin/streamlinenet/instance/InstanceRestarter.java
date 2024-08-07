package net.elfisland.plugin.streamlinenet.instance;

import com.velocitypowered.api.proxy.server.RegisteredServer;
import lombok.extern.slf4j.Slf4j;
import net.elfisland.plugin.streamlinenet.platform.FlexNetProxy;
import net.elfisland.plugin.streamlinenet.group.FlexNetGroup;
import net.elfisland.plugin.streamlinenet.group.FlexNetGroupManager;
import org.slf4j.Logger;

import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;

@Slf4j
public class InstanceRestarter {
    private final FlexNetProxy proxy;
    private final FlexNetGroupManager groupManager;
    private final InstanceLifecycleManager instanceLifecycleManager;
    private final Logger logger;

    // Use WeakHashMap to prevent memory leaks
    private static final Map<String, Long> serverUptime = Collections.synchronizedMap(new WeakHashMap<>());

    public InstanceRestarter(FlexNetProxy proxy, FlexNetGroupManager groupManager,
                             InstanceLifecycleManager instanceLifecycleManager, Logger logger) {
        this.proxy = proxy;
        this.groupManager = groupManager;
        this.instanceLifecycleManager = instanceLifecycleManager;
        this.logger = logger;
    }

    public static void trackServer(String serverId) {
        serverUptime.put(serverId, System.currentTimeMillis());
    }

    public void checkAndRestartServers() {
        groupManager.getAllGroups().forEach(this::processGroupForRestart);
    }

    private void processGroupForRestart(FlexNetGroup group) {
        group.getAllServers().forEach((serverId, server) -> {
            if (!InstanceLifecycleManager.isInstanceInLifecycleProcess(serverId)) {
                checkServerForRestart(serverId, server, group);
            }
        });
    }

    private void checkServerForRestart(String serverId, RegisteredServer server, FlexNetGroup group) {
        long uptime = getServerUptime(serverId);
        int restartInterval = group.getAutoRestartInterval();

        if (uptime >= restartInterval) {
            logger.info("Server {} reached the restart interval of {} minutes. Initiating restart process.", serverId, restartInterval);
            instanceLifecycleManager.handleServerLifecycle(serverId, group, true);
        }
    }

    private long getServerUptime(String serverId) {
        Long startTime = serverUptime.get(serverId);
        if (startTime == null) {
            return 0;
        }
        return (System.currentTimeMillis() - startTime) / (60 * 1000);
    }

    public static void removeFromServerUptime(String serverId) {
        serverUptime.remove(serverId);
    }
}
