package net.elfisland.plugin.streamlinenet.instance.pterodactyl;

import com.mattmalec.pterodactyl4j.PteroAction;
import com.mattmalec.pterodactyl4j.client.entities.ClientServer;
import com.mattmalec.pterodactyl4j.client.entities.PteroClient;
import lombok.Builder;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import net.elfisland.plugin.streamlinenet.platform.FlexNetProxy;

import java.util.HashMap;
import java.util.HashSet;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.function.Function;

@Slf4j
public class PterodactylInstanceWatcher {

    private final FlexNetProxy proxy;
    private final PteroClient client;
    private final HashMap<UUID, WatchTask> tasks = new HashMap<>();

    public PterodactylInstanceWatcher(FlexNetProxy proxy, PteroClient client) {
        this.proxy = proxy;
        this.client = client;
        initScheduler();
    }

    private void initScheduler() {
        proxy.scheduleRepeatTask(() -> {
            HashSet<UUID> removableUUIDs = new HashSet<>();
            tasks.forEach((key, watchTask) -> {
                CompletableFuture.runAsync(() -> {
                    ClientServer server = watchTask.server.execute();
                    if(watchTask.waitForState.apply(server)) {
                        watchTask.onStateChanged.accept(server);
                        removableUUIDs.add(key);
                    }
                }).join();
            });
            removableUUIDs.forEach(tasks::remove);
        }, 0L, 15L);
    }

    public void createTask(
            String identifier,
            Consumer<ClientServer> onInit,
            Function<ClientServer, Boolean> waitForState,
            Consumer<ClientServer> onStateChange
    ) {
        client.retrieveServerByIdentifier(identifier).executeAsync(clientServer -> {
            onInit.accept(clientServer);
            WatchTask task = WatchTask.builder()
                    .server(client.retrieveServerByIdentifier(identifier))
                    .waitForState(waitForState)
                    .onStateChanged(onStateChange)
                    .build();
            tasks.put(UUID.randomUUID(), task);
        });
    }

    @Data
    @Builder
    public static class WatchTask {
        private PteroAction<ClientServer> server;
        private Function<ClientServer, Boolean> waitForState;
        private Consumer<ClientServer> onStateChanged;
    }

}
