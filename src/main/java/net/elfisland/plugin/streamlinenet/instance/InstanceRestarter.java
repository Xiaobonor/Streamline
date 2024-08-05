package net.elfisland.plugin.streamlinenet.instance;

import com.velocitypowered.api.proxy.server.RegisteredServer;
import lombok.extern.slf4j.Slf4j;
import net.elfisland.plugin.streamlinenet.group.FlexNetGroup;
import net.elfisland.plugin.streamlinenet.group.FlexNetGroupManager;
import net.elfisland.plugin.streamlinenet.platform.FlexNetProxy;
import org.slf4j.Logger;

import java.util.HashMap;

@Slf4j
public class InstanceRestarter {
    private final FlexNetProxy proxy;
    private final FlexNetGroupManager groupManager;
    private final InstanceLifecycleManager instanceLifecycleManager;
    private final Logger logger;

    private static final HashMap<String, Long> serverUptime = new HashMap<>();

    public InstanceRestarter(FlexNetProxy proxy, FlexNetGroupManager groupManager, InstanceLifecycleManager instanceLifecycleManager, Logger logger) {
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
        group.getAllServers().stream()
                .filter(entry -> !InstanceLifecycleManager.isInstanceInLifecycleProcess(entry.getKey()))
                .forEach(entry -> checkServerForRestart(entry.getKey(), entry.getValue(), group));
    }

    private void checkServerForRestart(String serverId, RegisteredServer server, FlexNetGroup group) {
        long uptime = getServerUptime(serverId);
        int restartInterval = group.getAutoRestartInterval();

        if (uptime >= restartInterval) {
            instanceLifecycleManager.handleServerLifecycle(serverId, group, true);
        }
    }

    private long getServerUptime(String serverId) {
        Long startTime = serverUptime.get(serverId);
        if (startTime == null) {
            logger.error("Server {} not found in serverUptime", serverId);
            return 0;
        }
        long serverUptimeValue = (System.currentTimeMillis() - startTime) / (60 * 1000);
        logger.info("Server {} uptime: {} minutes", serverId, serverUptimeValue);
        return serverUptimeValue;
    }

    public static void removeFromServerUptime(String serverId) {
        serverUptime.remove(serverId);
    }

}
