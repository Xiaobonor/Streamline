package net.elfisland.plugin.streamlinenet.instance.pterodactyl;

import com.mattmalec.pterodactyl4j.DataType;
import com.mattmalec.pterodactyl4j.PteroBuilder;
import com.mattmalec.pterodactyl4j.UtilizationState;
import com.mattmalec.pterodactyl4j.application.entities.*;
import com.mattmalec.pterodactyl4j.client.entities.PteroClient;
import com.mattmalec.pterodactyl4j.client.entities.Utilization;
import lombok.extern.slf4j.Slf4j;
import net.elfisland.plugin.streamlinenet.config.PterodactylConfig;
import net.elfisland.plugin.streamlinenet.instance.InstanceCreationResult;
import net.elfisland.plugin.streamlinenet.instance.InstanceManager;
import net.elfisland.plugin.streamlinenet.model.InstanceTemplate;
import net.elfisland.plugin.streamlinenet.platform.FlexNetProxy;
import org.slf4j.Logger;

import java.net.InetSocketAddress;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

@Slf4j
public class PterodactylInstanceManager implements InstanceManager {

    private final PterodactylConfig config;
    private final PteroApplication api;
    private final PterodactylInstanceWatcher watcher;
    private final Logger logger;
    private final FlexNetProxy proxy;
    private final Map<String, Boolean> deleteInProgress = new ConcurrentHashMap<>();

    public PterodactylInstanceManager(PterodactylConfig config, FlexNetProxy proxy, Logger logger) {
        this.config = config;
        this.proxy = proxy;
        this.api = PteroBuilder.createApplication(config.getApiUrl(), config.getApiKey());
        PteroClient client = PteroBuilder.createClient(config.getApiUrl(), config.getClientApiKey());
        this.watcher = new PterodactylInstanceWatcher(proxy, client);
        this.logger = logger;
    }

    /**
     * Create the server using Pterodactyl panel API
     * @param template Instance template
     */
    @Override
    public void createInstance(InstanceTemplate template, Consumer<InstanceCreationResult> resultConsumer) {
        CompletableFuture.runAsync(() -> {
            for (int attempt = 0; attempt < 3; attempt++) {
                try {
                    Optional<ApplicationServer> serverOptional = tryCreateServer(template);
                    if (serverOptional.isPresent()) {
                        ApplicationServer server = serverOptional.get();
                        executeWatcher(server, resultConsumer, server.getAllocations().stream().findFirst());
                        logger.info("Server {} created successfully", server.getName());
                        return;
                    }
                } catch (Exception e) {
                    logger.error("Attempt {} - Failed to create server: {}", attempt + 1, e.getMessage());
                }
            }
            logger.error("All attempts to create the server have failed.");
        }).join();
    }

    private Optional<ApplicationServer> tryCreateServer(InstanceTemplate template) throws Exception {
        Nest nest = api.retrieveNestById(template.getNestId()).execute();
        ApplicationEgg egg = api.retrieveEggById(nest, template.getEggId()).execute();
        ApplicationUser owner = api.retrieveUserById(template.getDefaultOwnerId()).execute(); // Maybe it isn't necessary?
        Optional<ApplicationAllocation> optAllocation = findAvailableAllocation();

        if (optAllocation.isEmpty()) {
            logger.error("No available allocation found");
            return Optional.empty();
        }

        logger.info("Allocation {} found, creating server...", optAllocation.get().getAlias());
        logger.info("Owner: {} | Egg: {}", owner.getFullName(), egg.getName());

        ApplicationServer server = createServer(template, Optional.of(optAllocation.get()), owner, egg);
        return Optional.of(server);
    }

    private Optional<ApplicationAllocation> findAvailableAllocation() throws Exception {
        return api.retrieveAllocations()
                .execute()
                .stream()
                .filter(appAllocation -> appAllocation.getAlias() != null &&
                        !appAllocation.isAssigned() &&
                        appAllocation.getAlias().startsWith(config.getAllocationAliasPrefix()))
                .findFirst();
    }

    private ApplicationServer createServer(InstanceTemplate template, Optional<ApplicationAllocation> optAllocation,
                                           ApplicationUser owner, ApplicationEgg egg) throws Exception {
        if (optAllocation.isEmpty()) {
            throw new IllegalStateException("Allocation is not present");
        }

        return api.createServer()
                .setName(template.getNameTemplate())
                .setDescription(template.getDescription())
                .setAllocations(optAllocation.get())
                .setOwner(owner)
                .setEgg(egg)
                .setCPU(template.getCpuAmount())
                .setMemory(template.getMemoryAmount(), DataType.MB)
                .setDisk(template.getDiskAmount(), DataType.MB)
                .skipScripts(template.isSkipInitScript())
                .execute();
    }

    private void executeWatcher(ApplicationServer server, Consumer<InstanceCreationResult> resultConsumer,
                                Optional<List<ApplicationAllocation>> optAllocationList) {
        if (optAllocationList.isEmpty() || optAllocationList.get().isEmpty()) {
            throw new IllegalStateException("Allocation list is empty or not present");
        }

        ApplicationAllocation allocation = optAllocationList.get().get(0); // Assume first allocation is the desired one
        watcher.createTask(
                server.getIdentifier(),
                clientServer -> {},
                clientServer -> {
                    logger.info("Server {} installing: {} suspended: {}", clientServer.getName(), clientServer.isInstalling(), clientServer.isSuspended());
                    if (clientServer.isInstalling() || clientServer.isSuspended()) return false;
                    Utilization utilization = clientServer.retrieveUtilization().execute();
                    logger.info("Server {} state = {}", clientServer.getName(), utilization.getState());
                    if (utilization.getState() == UtilizationState.OFFLINE) {
                        clientServer.start().execute();
                    }
                    return utilization.getState() == UtilizationState.RUNNING;
                },
                clientServer -> resultConsumer.accept(
                        InstanceCreationResult.builder()
                                .instanceId(server.getIdentifier())
                                .instanceName(server.getName())
                                .address(new InetSocketAddress(allocation.getIP(), allocation.getPortInt()))
                                .success(true)
                                .build()
                )
        );
    }


    @Override
    public void deleteInstance(String identifier, Consumer<Boolean> callback) {
        if (deleteInProgress.putIfAbsent(identifier, true) != null) {
            logger.warn("Instance deletion already in progress for: {}", identifier);
            return;
        }

        logger.info("Deleting instance {}...", identifier);
        watcher.createTask(
                identifier,
                clientServer -> clientServer.stop().execute(),
                clientServer -> {
                    Utilization utilization = clientServer.retrieveUtilization().execute();
                    logger.info("Server {} state = {}", identifier, utilization.getState());
                    return utilization.getState() == UtilizationState.OFFLINE;
                },
                clientServer -> {
                    api.retrieveServerById(clientServer.getInternalIdLong())
                            .execute()
                            .getController()
                            .delete(false)
                            .execute();
                    callback.accept(true);
                    deleteInProgress.remove(identifier);
                }
        );
    }

}
