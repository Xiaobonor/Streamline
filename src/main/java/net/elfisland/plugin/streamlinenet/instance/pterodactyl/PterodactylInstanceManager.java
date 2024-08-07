package net.elfisland.plugin.streamlinenet.instance.pterodactyl;

import com.mattmalec.pterodactyl4j.DataType;
import com.mattmalec.pterodactyl4j.PteroBuilder;
import com.mattmalec.pterodactyl4j.UtilizationState;
import com.mattmalec.pterodactyl4j.application.entities.*;
import com.mattmalec.pterodactyl4j.client.entities.PteroClient;
import com.mattmalec.pterodactyl4j.client.entities.Utilization;
import lombok.extern.slf4j.Slf4j;
import net.elfisland.plugin.streamlinenet.instance.InstanceCreationResult;
import net.elfisland.plugin.streamlinenet.instance.InstanceManager;
import net.elfisland.plugin.streamlinenet.platform.FlexNetProxy;
import net.elfisland.plugin.streamlinenet.config.PterodactylConfig;
import net.elfisland.plugin.streamlinenet.model.InstanceTemplate;

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
    private final FlexNetProxy proxy;
    private final Map<String, Boolean> deleteInProgress = new ConcurrentHashMap<>();

    public PterodactylInstanceManager(PterodactylConfig config, FlexNetProxy proxy) {
        this.config = config;
        this.proxy = proxy;
        this.api = PteroBuilder.createApplication(config.getApiUrl(), config.getApiKey());
        PteroClient client = PteroBuilder.createClient(config.getApiUrl(), config.getClientApiKey());
        this.watcher = new PterodactylInstanceWatcher(proxy, client);
    }

    /**
     * Create the server using Pterodactyl panel API
     * @param template Instance template
     */
    @Override
    public void createInstance(InstanceTemplate template, Consumer<InstanceCreationResult> resultConsumer) {
        CompletableFuture.runAsync(() -> {
            boolean success = false;
            for (int attempt = 0; attempt < 3; attempt++) {
                try {
                    if (attemptToCreateServer(template, resultConsumer)) {
                        success = true;
                        break;
                    }
                } catch (Exception e) {
                    log.error("Attempt {} - Failed to create server: {}", attempt + 1, e.getMessage());
                }
            }
            if (!success) {
                log.error("All attempts to create the server have failed.");
                resultConsumer.accept(InstanceCreationResult.builder().success(false).build());
            }
        }).join();
    }

    private boolean attemptToCreateServer(InstanceTemplate template, Consumer<InstanceCreationResult> resultConsumer) throws Exception {
        Optional<ApplicationServer> serverOptional = tryCreateServer(template);
        if (serverOptional.isPresent()) {
            ApplicationServer server = serverOptional.get();
            executeWatcher(server, resultConsumer, server.getAllocations().stream().findFirst());
            log.info("Server {} created successfully", server.getName());
            return true;
        }
        return false;
    }

    private Optional<ApplicationServer> tryCreateServer(InstanceTemplate template) throws Exception {
        Nest nest = api.retrieveNestById(template.getNestId()).execute();
        ApplicationEgg egg = api.retrieveEggById(nest, template.getEggId()).execute();
        ApplicationUser owner = api.retrieveUserById(template.getDefaultOwnerId()).execute();
        Optional<ApplicationAllocation> optAllocation = findAvailableAllocation();

        if (optAllocation.isEmpty()) {
            log.error("No available allocation found");
            return Optional.empty();
        }

        log.info("Allocation {} found, creating server...", optAllocation.get().getAlias());
        log.info("Owner: {} | Egg: {}", owner.getFullName(), egg.getName());

        return Optional.of(createServer(template, optAllocation.get(), owner, egg));
    }

    private Optional<ApplicationAllocation> findAvailableAllocation() throws Exception {
        return api.retrieveAllocations()
                .execute()
                .stream()
                .filter(allocation -> allocation.getAlias() != null &&
                        !allocation.isAssigned() &&
                        allocation.getAlias().startsWith(config.getAllocationAliasPrefix()))
                .findFirst();
    }

    private ApplicationServer createServer(InstanceTemplate template, ApplicationAllocation allocation,
                                           ApplicationUser owner, ApplicationEgg egg) throws Exception {
        return api.createServer()
                .setName(template.getNameTemplate())
                .setDescription(template.getDescription())
                .setAllocations(allocation)
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
                    log.info("Server {} installing: {} suspended: {}", clientServer.getName(), clientServer.isInstalling(), clientServer.isSuspended());
                    if (clientServer.isInstalling() || clientServer.isSuspended()) return false;
                    Utilization utilization = clientServer.retrieveUtilization().execute();
                    log.info("Server {} state = {}", clientServer.getName(), utilization.getState());
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
            log.warn("Instance deletion already in progress for: {}", identifier);
            return;
        }

        log.info("Deleting instance {}...", identifier);
        watcher.createTask(
                identifier,
                clientServer -> clientServer.stop().execute(),
                clientServer -> {
                    Utilization utilization = clientServer.retrieveUtilization().execute();
                    log.info("Server {} state = {}", identifier, utilization.getState());
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
