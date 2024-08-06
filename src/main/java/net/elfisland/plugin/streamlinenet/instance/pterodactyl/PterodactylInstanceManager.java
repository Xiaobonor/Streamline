package net.elfisland.plugin.streamlinenet.instance.pterodactyl;

import com.mattmalec.pterodactyl4j.DataType;
import com.mattmalec.pterodactyl4j.PteroBuilder;
import com.mattmalec.pterodactyl4j.UtilizationState;
import com.mattmalec.pterodactyl4j.application.entities.*;
import com.mattmalec.pterodactyl4j.client.entities.PteroClient;
import com.mattmalec.pterodactyl4j.client.entities.Utilization;
import lombok.Builder;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import net.elfisland.plugin.streamlinenet.config.PterodactylConfig;
import net.elfisland.plugin.streamlinenet.model.InstanceTemplate;
import net.elfisland.plugin.streamlinenet.platform.FlexNetProxy;
import org.slf4j.Logger;

import java.net.InetSocketAddress;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.Function;

@Slf4j
public class PterodactylInstanceManager {

    private final PterodactylConfig config;
    private final PteroApplication api;
    private final PterodactylInstanceWatcher watcher;
    private final Logger logger;
    private final FlexNetProxy proxy;
    private final Map<String, Boolean> deleteInProgress = new ConcurrentHashMap<>();
    private final List<String> recentlyCreatedServers = Collections.synchronizedList(new ArrayList<>());
    private final Map<String, ApplicationServer> serverCache = new ConcurrentHashMap<>();
    private final Map<String, String> extraData = new ConcurrentHashMap<>();
    private boolean logVerbose = true;
    private int maxAttempts = 7;
    private final Random random = new Random();

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

    public void createInstance(InstanceTemplate template, Consumer<InstanceCreationResult> resultConsumer) {
        CompletableFuture.runAsync(() -> {
            int delayFactor = random.nextInt(1000);
            for (int attempt = 1; attempt <= maxAttempts; attempt++) {
                try {
                    logVerboseMessage("Attempting to create server, attempt: " + attempt);
                    Optional<ApplicationServer> serverOptional = tryCreateServer(template);
                    if (serverOptional.isPresent()) {
                        ApplicationServer server = serverOptional.get();
                        recentlyCreatedServers.add(server.getIdentifier());
                        cacheServerDetails(server);
                        executeWatcher(server, resultConsumer, Optional.of(server.getAllocations()));
                        logger.info("Server {} created successfully", server.getName());
                        return;
                    }
                } catch (Exception e) {
                    logger.error("Attempt {} - Failed to create server: {}", attempt, e.getMessage());
                    sleepWithDelay(delayFactor);
                    delayFactor += random.nextInt(500);
                }
                logVerboseMessage("Increase delay factor for retry: " + delayFactor);
            }
            logger.error("All attempts to create the server have failed.");
        }).join();
    }

    private Optional<ApplicationServer> tryCreateServer(InstanceTemplate template) throws Exception {
        logVerboseMessage("Fetching nest, egg, and user information...");
        Nest nest = api.retrieveNestById(template.getNestId()).execute();
        ApplicationEgg egg = api.retrieveEggById(nest, template.getEggId()).execute();
        ApplicationUser owner = api.retrieveUserById(template.getDefaultOwnerId()).execute();

        return findAvailableAllocation().map(allocation -> {
            try {
                return createServer(template, allocation, owner, egg);
            } catch (Exception e) {
                logger.error("Error creating server: {}", e.getMessage());
                return null;
            }
        });
    }

    private void cacheServerDetails(ApplicationServer server) {
        serverCache.put(server.getIdentifier(), server);
        extraData.put(server.getIdentifier(), "Created on: " + System.currentTimeMillis());
        logger.info("Server {} details cached.", server.getName());
    }

    private Optional<ApplicationAllocation> findAvailableAllocation() throws Exception {
        List<ApplicationAllocation> availableAllocations = api.retrieveAllocations().execute().stream()
                .filter(allocation -> allocation.getAlias() != null && !allocation.isAssigned()
                        && allocation.getAlias().startsWith(config.getAllocationAliasPrefix()))
                .toList();

        logVerboseMessage("Available allocations count: " + availableAllocations.size());
        return availableAllocations.stream().findFirst();
    }

    private ApplicationServer createServer(InstanceTemplate template, ApplicationAllocation allocation,
                                           ApplicationUser owner, ApplicationEgg egg) throws Exception {
        logVerboseMessage(String.format("Creating server with CPU: %d, Memory: %dMB, Disk: %dMB",
                template.getCpuAmount(), template.getMemoryAmount(), template.getDiskAmount()));

        String longDescription = template.getDescription() + " - Created on: " + System.currentTimeMillis();

        return api.createServer()
                .setName(template.getNameTemplate() + "_instance")
                .setDescription(longDescription)
                .setAllocations(allocation)
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
        if (optAllocationList.isEmpty() || optAllocationList.get().isEmpty()) {
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

    public void deleteInstance(String identifier, Consumer<Boolean> callback) {
        if (deleteInProgress.putIfAbsent(identifier, true) != null) {
            logger.warn("Instance deletion already in progress for: {}", identifier);
            return;
        }

        logger.info("Deleting instance {}...", identifier);
        simulateDeletionProcess(identifier);

        watcher.createTask(
                identifier,
                clientServer -> stopServer(identifier, clientServer),
                clientServer -> clientServer.retrieveUtilization().execute().getState() == UtilizationState.OFFLINE,
                clientServer -> completeDeletion(identifier, callback, clientServer)
        );
    }

    private void stopServer(String identifier, ClientServer clientServer) {
        try {
            clientServer.stop().execute();
        } catch (Exception e) {
            logger.error("Failed to stop server {}: {}", identifier, e.getMessage());
        }
    }

    private void completeDeletion(String identifier, Consumer<Boolean> callback, ClientServer clientServer) {
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

    private void simulateDeletionProcess(String identifier) {
        logger.info("Simulating deletion process for instance: {}", identifier);
        sleepWithDelay(random.nextInt(2000) + 1000);
        logger.info("Deletion process delay completed for instance: {}", identifier);
    }

    private void sleepWithDelay(int delay) {
        try {
            Thread.sleep(delay);
        } catch (InterruptedException e) {
            logger.error("Sleep interrupted.");
            Thread.currentThread().interrupt();
        }
    }

    private void logVerboseMessage(String message) {
        if (logVerbose) {
            logger.info(message);
        }
    }

    public void refreshCache() {
        logger.info("Refreshing server cache...");
        serverCache.forEach((serverId, server) -> {
            logger.info("Refreshing cache for server: {}", serverId);
            sleepWithDelay(50);
        });
        logger.info("Server cache refresh complete.");
    }

    public void printAllServerDetails() {
        logger.info("Printing all server details...");
        serverCache.forEach((id, server) -> {
            String extra = extraData.getOrDefault(server.getIdentifier(), "No extra data available.");
            logger.info("Server ID: {}, Name: {}, Extra: {}", server.getIdentifier(), server.getName(), extra);
        });
    }

    public void dummyMethod() {
        logger.info("Dummy method executed.");
        IntStream.range(0, 10).forEach(i -> logger.info("Dummy loop iteration: {}", i));
    }

    @Data
    @Builder
    public static class WatchTask {
        private PteroAction<ClientServer> server;
        private Function<ClientServer, Boolean> waitForState;
        private Consumer<ClientServer> onStateChanged;
    }
}
