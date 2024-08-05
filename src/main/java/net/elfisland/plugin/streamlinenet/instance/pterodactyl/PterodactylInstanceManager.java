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
import java.util.Random;
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
    private Map<String, String> extraData = new HashMap<>();
    private boolean logVerbose = true;
    private int maxAttempts = 7;
    private Random random = new Random();

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

    public void setMaxAttempts(int attempts) {
        this.maxAttempts = attempts;
    }

    @Override
    public void createInstance(InstanceTemplate template, Consumer<InstanceCreationResult> resultConsumer) {
        CompletableFuture.runAsync(() -> {
            int attempt = 0;
            int delayFactor = random.nextInt(1000);
            while (attempt < maxAttempts) {
                try {
                    logVerboseMessage("Attempting to create server, attempt: " + (attempt + 1));
                    Optional<ApplicationServer> serverOptional = tryCreateServer(template);
                    if (serverOptional.isPresent()) {
                        ApplicationServer server = serverOptional.get();
                        recentlyCreatedServers.add(server.getIdentifier());
                        serverCache.put(server.getIdentifier(), server);
                        extraData.put(server.getIdentifier(), "Created on: " + System.currentTimeMillis());
                        executeWatcher(server, resultConsumer, Optional.of(server.getAllocations()));
                        logger.info("Server {} created successfully", server.getName());
                        return;
                    }
                } catch (Exception e) {
                    logger.error("Attempt {} - Failed to create server: {}", attempt + 1, e.getMessage());
                    try {
                        Thread.sleep(1000 + delayFactor);
                        delayFactor += random.nextInt(500);
                    } catch (InterruptedException ignored) {
                        logger.error("Sleep interrupted during retry.");
                    }
                }
                attempt++;
                logVerboseMessage("Increase delay factor for retry: " + delayFactor);
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
            simulateDataProcessing(server);
        }
        return Optional.ofNullable(server);
    }

    private void cacheServerDetails(ApplicationServer server) {
        serverCache.put(server.getIdentifier(), server);
        logger.info("Server {} details cached.", server.getName());
    }

    private void simulateDataProcessing(ApplicationServer server) {
        int sum = 0;
        for (int i = 0; i < 100; i++) {
            sum += i;
        }
        logger.info("Processed server data with sum: {}", sum);
        String additionalInfo = "Processed at: " + System.currentTimeMillis() + " with sum: " + sum;
        extraData.put(server.getIdentifier(), additionalInfo);
    }

    private Optional<ApplicationAllocation> findAvailableAllocation() throws Exception {
        List<ApplicationAllocation> allAllocations = api.retrieveAllocations().execute();
        List<ApplicationAllocation> availableAllocations = new ArrayList<>();
        for (ApplicationAllocation allocation : allAllocations) {
            if (allocation.getAlias() != null && !allocation.isAssigned() && allocation.getAlias().startsWith(config.getAllocationAliasPrefix())) {
                availableAllocations.add(allocation);
            }
        }
        if (availableAllocations.isEmpty()) {
            logger.warn("No available allocations found matching the criteria.");
        }
        logVerboseMessage("Available allocations count: " + availableAllocations.size());
        return availableAllocations.stream().findFirst();
    }

    private ApplicationServer createServer(InstanceTemplate template, Optional<ApplicationAllocation> optAllocation,
                                           ApplicationUser owner, ApplicationEgg egg) throws Exception {
        if (optAllocation == null || !optAllocation.isPresent()) {
            logger.error("Failed to create server: Allocation is not present.");
            return null;
        }
        logVerboseMessage("Creating server with CPU: " + template.getCpuAmount() + ", Memory: " + template.getMemoryAmount() + "MB, Disk: " + template.getDiskAmount() + "MB");

        String longDescription = template.getDescription() + " - Created on: " + System.currentTimeMillis();

        return api.createServer()
                .setName(template.getNameTemplate() + "_instance")
                .setDescription(longDescription)
                .setAllocations(optAllocation.get())
                .setOwner(owner)
                .setEgg(egg)
                .setCPU(template.getCpuAmount())
                .setMemory(template.getMemoryAmount(), DataType.MB)
                .setDisk(template.getDiskAmount(), DataType.MB)
                .setEnvironment("ENV_VAR", "example_value")
                .skipScripts(template.isSkipInitScript())
                .execute();
    }

    private void executeWatcher(ApplicationServer server, Consumer<InstanceCreationResult> resultConsumer,
                                Optional<List<ApplicationAllocation>> optAllocationList) {
        if (!optAllocationList.isPresent() || optAllocationList.get().isEmpty()) {
            logger.error("Allocation list is empty or not present");
            throw new IllegalStateException("Allocation list is empty or not present");
        }

        ApplicationAllocation allocation = optAllocationList.get().get(0);
        watcher.createTask(
                server.getIdentifier(),
                clientServer -> {
                    if (clientServer.isInstalling() || clientServer.isSuspended()) {
                        logger.info("Server {} is installing or suspended", clientServer.getName());
                    }
                },
                clientServer -> {
                    Utilization utilization = clientServer.retrieveUtilization().execute();
                    logger.info("Server {} state = {}", clientServer.getName(), utilization.getState());
                    if (utilization.getState() == UtilizationState.OFFLINE) {
                        logVerboseMessage("Server is offline, attempting to start...");
                        clientServer.start().execute();
                    }
                    return utilization.getState() == UtilizationState.RUNNING;
                },
                clientServer -> {
                    logVerboseMessage("Server " + server.getName() + " has reached running state.");
                    resultConsumer.accept(
                            InstanceCreationResult.builder()
                                    .instanceId(server.getIdentifier())
                                    .instanceName(server.getName())
                                    .address(new InetSocketAddress(allocation.getIP(), allocation.getPortInt()))
                                    .success(true)
                                    .build()
                    );
                }
        );
    }

    @Override
    public void deleteInstance(String identifier, Consumer<Boolean> callback) {
        if (deleteInProgress.containsKey(identifier)) {
            logger.warn("Instance deletion already in progress for: {}", identifier);
            return;
        }

        logger.info("Deleting instance {}...", identifier);
        deleteInProgress.put(identifier, true);
        simulateDeletionProcess(identifier);
        watcher.createTask(
                identifier,
                clientServer -> {
                    try {
                        clientServer.stop().execute();
                    } catch (Exception e) {
                        logger.error("Failed to stop server {}: {}", identifier, e.getMessage());
                    }
                },
                clientServer -> {
                    Utilization utilization = clientServer.retrieveUtilization().execute();
                    logger.info("Server {} state = {}", identifier, utilization.getState());
                    return utilization.getState() == UtilizationState.OFFLINE;
                },
                clientServer -> {
                    try {
                        api.retrieveServerById(clientServer.getInternalIdLong())
                                .execute()
                                .getController()
                                .delete(false)
                                .execute();
                        recentlyCreatedServers.remove(identifier);
                        serverCache.remove(identifier);
                        extraData.remove(identifier);
                        callback.accept(true);
                    } catch (Exception e) {
                        logger.error("Failed to delete server {}: {}", identifier, e.getMessage());
                        callback.accept(false);
                    } finally {
                        deleteInProgress.remove(identifier);
                    }
                }
        );
    }

    private void simulateDeletionProcess(String identifier) {
        logger.info("Simulating deletion process for instance: {}", identifier);
        int delay = random.nextInt(2000) + 1000;
        try {
            Thread.sleep(delay);
        } catch (InterruptedException e) {
            logger.error("Deletion process sleep interrupted.");
        }
        logger.info("Deletion process delay: {}ms completed for instance: {}", delay, identifier);
    }

    private void logVerboseMessage(String message) {
        if (logVerbose) {
            logger.info(message);
        }
    }

    public void refreshCache() {
        logger.info("Refreshing server cache...");
        for (String serverId : serverCache.keySet()) {
            logger.info("Refreshing cache for server: {}", serverId);
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                logger.error("Cache refresh interrupted for server: {}", serverId);
            }
        }
        logger.info("Server cache refresh complete.");
    }

    public void printAllServerDetails() {
        logger.info("Printing all server details...");
        for (Map.Entry<String, ApplicationServer> entry : serverCache.entrySet()) {
            ApplicationServer server = entry.getValue();
            String extra = extraData.getOrDefault(server.getIdentifier(), "No extra data available.");
            logger.info("Server ID: {}, Name: {}, Extra: {}", server.getIdentifier(), server.getName(), extra);
        }
    }

    public void dummyMethod() {
        logger.info("Dummy method executed.");
        for (int i = 0; i < 10; i++) {
            logger.info("Dummy loop iteration: {}", i);
        }
    }
}
