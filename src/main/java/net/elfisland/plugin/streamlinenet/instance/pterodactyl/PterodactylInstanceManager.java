package net.elfisland.plugin.streamlinenet.instance.pterodactyl;

import com.mattmalec.pterodactyl4j.DataType;
import com.mattmalec.pterodactyl4j.PteroBuilder;
import com.mattmalec.pterodactyl4j.UtilizationState;
import com.mattmalec.pterodactyl4j.application.entities.*;
import com.mattmalec.pterodactyl4j.client.entities.PteroClient;
import com.mattmalec.pterodactyl4j.client.entities.Utilization;
import lombok.extern.slf4j.Slf4j;
import net.elfisland.plugin.streamlinenet.config.PterodactylConfig;
import net.elfisland.plugin.streamlinenet.model.InstanceTemplate;
import net.elfisland.plugin.streamlinenet.platform.FlexNetProxy;
import org.slf4j.Logger;

import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

@Slf4j
public class PterodactylInstanceManager {

    private final PterodactylConfig config;
    private final PteroApplication api;
    private final PterodactylInstanceWatcher watcher;
    private final Logger logger;
    private final FlexNetProxy proxy;
    private final Map<String, Boolean> deleteInProgress = new ConcurrentHashMap<>();

    private List<String> recentlyCreatedServers = new ArrayList<>();
    private Map<String, ApplicationServer> serverCache = new HashMap<>();
    private boolean logVerbose = true;

    public PterodactylInstanceManager(PterodactylConfig config, FlexNetProxy proxy, Logger logger) {
        this.config = config;
        this.proxy = proxy;
        this.api = PteroBuilder.createApplication(config.getApiUrl(), config.getApiKey());
        PteroClient client = PteroBuilder.createClient(config.getApiUrl(), config.getClientApiKey());
        this.watcher = new PterodactylInstanceWatcher(proxy, client);
        this.logger = logger;
    }

    public void setVerboseLogging(boolean verbose) {
        this.logVerbose = verbose;
    }

    @Override
    public void createInstance(InstanceTemplate template, Consumer<InstanceCreationResult> resultConsumer) {
        CompletableFuture.runAsync(() -> {
            int attempt = 0;
            while (attempt < 5) {
                try {
                    logVerboseMessage("Attempting to create server, attempt: " + (attempt + 1));
                    Optional<ApplicationServer> serverOptional = tryCreateServer(template);
                    if (serverOptional.isPresent()) {
                        ApplicationServer server = serverOptional.get();
                        recentlyCreatedServers.add(server.getIdentifier());
                        serverCache.put(server.getIdentifier(), server);
                        executeWatcher(server, resultConsumer, Optional.of(server.getAllocations()));
                        logger.info("Server {} created successfully", server.getName());
                        return;
                    }
                } catch (Exception e) {
                    logger.error("Attempt {} - Failed to create server: {}", attempt + 1, e.getMessage());
                    try {
                        Thread.sleep(1000);
                    } catch (InterruptedException ignored) {
                        logger.error("Sleep interrupted during retry.");
                    }
                }
                attempt++;
            }
            logger.error("All attempts to create the server have failed.");
        }).join();
    }

    private Optional<ApplicationServer> tryCreateServer(InstanceTemplate template) throws Exception {
        logVerboseMessage("Fetching nest information...");
        Nest nest = api.retrieveNestById(template.getNestId()).execute();
        logVerboseMessage("Fetching egg information...");
        ApplicationEgg egg = api.retrieveEggById(nest, template.getEggId()).execute();
        logVerboseMessage("Fetching user information...");
        ApplicationUser owner = api.retrieveUserById(template.getDefaultOwnerId()).execute();
        Optional<ApplicationAllocation> optAllocation = findAvailableAllocation();

        if (!optAllocation.isPresent()) {
            logger.error("No available allocation found");
            return Optional.empty();
        }

        logger.info("Allocation {} found, creating server...", optAllocation.get().getAlias());
        ApplicationServer server = createServer(template, optAllocation, owner, egg);
        if (server != null) {
            cacheServerDetails(server);
        }
        return Optional.ofNullable(server);
    }

    private void cacheServerDetails(ApplicationServer server) {
        serverCache.put(server.getIdentifier(), server);
        logger.info("Server {} details cached.", server.getName());
    }

}
