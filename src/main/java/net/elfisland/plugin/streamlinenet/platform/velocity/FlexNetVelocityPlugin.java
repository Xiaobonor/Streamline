package net.elfisland.plugin.streamlinenet.platform.velocity;

import com.google.common.base.Suppliers;
import com.google.inject.Inject;
import com.moandjiezana.toml.Toml;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import com.velocitypowered.api.proxy.server.ServerInfo;
import com.velocitypowered.api.scheduler.ScheduledTask;
import net.elfisland.plugin.streamlinenet.command.JoinNewCommand;
import net.elfisland.plugin.streamlinenet.config.FlexNetConfig;
import net.elfisland.plugin.streamlinenet.group.FlexNetGroup;
import net.elfisland.plugin.streamlinenet.group.FlexNetGroupManager;
import net.elfisland.plugin.streamlinenet.instance.InstanceLifecycleManager;
import net.elfisland.plugin.streamlinenet.instance.InstanceManager;
import net.elfisland.plugin.streamlinenet.instance.InstanceRestarter;
import net.elfisland.plugin.streamlinenet.instance.pterodactyl.PterodactylInstanceManager;
import net.elfisland.plugin.streamlinenet.listeners.HubServerListener;
import net.elfisland.plugin.streamlinenet.platform.FlexNetProxy;
import net.elfisland.plugin.streamlinenet.util.FileUtils;
import org.slf4j.Logger;

import java.io.File;
import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

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
    private final Supplier<FlexNetConfig> configSupplier = Suppliers.memoize(this::getConfig);

    private InstanceManager instanceManager;
    private FlexNetVelocityInstanceController instanceController;
    private FlexNetGroupManager groupManager;
    private InstanceRestarter instanceRestarter;
    private JoinNewCommand joinNewCommand;
    private InstanceLifecycleManager instanceLifecycleManager;
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

        FlexNetConfig config = configSupplier.get(); // init config

        this.instanceManager = new PterodactylInstanceManager(config.getPterodactyl(), this, logger);
        this.groupManager = new FlexNetGroupManager(config, logger);
        this.instanceController = new FlexNetVelocityInstanceController(this, groupManager, instanceManager,
                config, instanceLifecycleManager, logger);
        this.joinNewCommand = new JoinNewCommand(proxyServer, logger, config, groupManager, playerTargetServerMap);
        this.instanceLifecycleManager = new InstanceLifecycleManager(this, instanceManager,
                instanceController, joinNewCommand, config, logger);
        this.instanceRestarter = new InstanceRestarter(this, groupManager, instanceLifecycleManager, logger);
        HubServerListener hubServerListener = new HubServerListener(this, proxyServer, playerTargetServerMap, logger);

        proxyServer.getEventManager().register(this,
                new FlexNetVelocityPlayerForwarder(proxyServer, groupManager, config, proxyServer, instanceController, logger));
        proxyServer.getEventManager().register(this, instanceController);
        proxyServer.getCommandManager().register("JoinNew", joinNewCommand);
        proxyServer.getEventManager().register(this, hubServerListener);

        this.instanceLifecycleManager.setInstanceController(instanceController);
        this.instanceController.setInstanceLifecycleManager(instanceLifecycleManager);

        // Check and restart servers every 60 seconds
        restartTask = this.scheduleRepeatTask(instanceRestarter::checkAndRestartServers, 1L, 60L);
    }

    /**
     * Method to read the config file, create a new one if not exists
     * @return FlexNetConfig object converted from toml config
     */
    private FlexNetConfig getConfig() {
        File dataFolder = this.dataFolder.toFile();
        if (!dataFolder.exists()) dataFolder.mkdirs();

        File file = new File(dataFolder, "config.toml");
        if (!file.exists())
            FileUtils.copyFileFromJar(getClass().getClassLoader(), "config.toml", file.toPath());

        return new Toml().read(file).to(FlexNetConfig.class);
    }

    @Override
    public void addServer(String identifier, InetSocketAddress address, FlexNetGroup group) {
        RegisteredServer server = proxyServer.registerServer(new ServerInfo(identifier, address));
        group.addServer(identifier, server);
    }

    @Override
    public void removeServer(String identifier, FlexNetGroup group) {
        proxyServer.getServer(identifier).ifPresent(server -> proxyServer.unregisterServer(server.getServerInfo()));
        group.removeServer(identifier);
    }

    @Override
    public void scheduleTask(Runnable runnable, long delay) {
        proxyServer.getScheduler().buildTask(this, runnable)
                .delay(Duration.of(delay, ChronoUnit.SECONDS))
                .schedule();
    }

    @Override
    public void scheduleTask(Runnable runnable, long delay, boolean isMillisecond) {
        proxyServer.getScheduler().buildTask(this, runnable)
                .delay(Duration.of(delay, ChronoUnit.MILLIS))
                .schedule();
    }

    @Override
    public ScheduledTask scheduleRepeatTask(Runnable runnable, long delay, long interval) {
        return proxyServer.getScheduler().buildTask(this, runnable)
                .delay(Duration.of(delay, ChronoUnit.SECONDS))
                .repeat(Duration.of(interval, ChronoUnit.SECONDS))
                .schedule();
    }

}
