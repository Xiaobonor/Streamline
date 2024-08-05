package net.elfisland.plugin.streamlinenet.platform.velocity;

import com.google.inject.Inject;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.scheduler.ScheduledTask;
import org.slf4j.Logger;

import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Plugin(
        id = "streamlinenet",
        name = "StreamlineNet",
        version = "1.0.0",
        description = "Velocity plugin for adding sub-servers dynamically",
        authors = { "Xiaobo (Elf Island)" }
)
public class FlexNetVelocityPlugin implements FlexNetProxy {

    private final ProxyServer proxyServer;
    private final Logger logger;
    private final Path dataFolder;

    private final Map<UUID, String> playerTargetServerMap = new ConcurrentHashMap<>();

    public static ScheduledTask restartTask;

    @Inject
    public FlexNetVelocityPlugin(ProxyServer server, Logger logger, @DataDirectory Path dataFolder) {
        this.proxyServer = server;
        this.logger = logger;
        this.dataFolder = dataFolder;
    }

    @Subscribe
    public void onProxyInitialization(ProxyInitializeEvent event) {
        logger.info("FlexNet is initializing...");
    }

}
