package net.elfisland.plugin.streamlinenet.group;

import com.velocitypowered.api.proxy.server.RegisteredServer;
import lombok.Builder;
import lombok.Getter;
import lombok.Setter;
import net.elfisland.plugin.streamlinenet.instance.InstanceLifecycleManager;

import java.util.*;
import java.util.stream.Collectors;

@Builder
public class FlexNetGroup {

    private static final Random RANDOM = new Random();

    @Getter
    private final String id;
    @Getter
    private final String fromHostname;
    @Getter
    private final String serverName;
    @Getter
    private final String hubServer;
    @Getter
    private final int maxInstance;
    @Getter
    private final int playerAmountToCreateInstance;
    @Getter
    private final int autoRestartInterval;
    @Getter
    private final int[] transferWarningIntervals;
    @Getter
    private final int postShutdownWait;

    @Builder.Default
    private final Map<String, RegisteredServer> serverMap = new HashMap<>();

    @Setter
    @Getter
    @Builder.Default
    private int validServerCount = 1;

    // Server management methods
    public void addServer(String id, RegisteredServer server) {
        serverMap.put(id, server);
    }

    public RegisteredServer getServer(String id) {
        return serverMap.get(id);
    }

    public void removeServer(String id) {
        serverMap.remove(id);
    }

    public boolean hasServer(String id) {
        return serverMap.containsKey(id);
    }

    public Set<RegisteredServer> getAllServers() {
        return new HashSet<>(serverMap.values());
    }

    public int getServerAmount() {
        return serverMap.size();
    }

    // Server selection methods
    public RegisteredServer getLowestPlayerServer(boolean detectLifecycleProcess) {
        return serverMap.entrySet().stream()
                .filter(entry -> isServerEligible(entry.getKey(), detectLifecycleProcess))
                .min(Comparator.comparingInt(entry -> entry.getValue().getPlayersConnected().size()))
                .map(Map.Entry::getValue)
                .orElse(null);
    }

    private boolean isServerEligible(String serverId, boolean detectLifecycleProcess) {
        return detectLifecycleProcess ?
                !InstanceLifecycleManager.isInstanceInLifecycleProcess(serverId) :
                !InstanceLifecycleManager.isInShutdownProcess(serverId);
    }

    public int getAllPlayersCount() {
        return serverMap.values().stream()
                .mapToInt(server -> server.getPlayersConnected().size())
                .sum();
    }

    // Instance management methods
    public boolean canCreateInstance() {
        return validServerCount < maxInstance &&
                (validServerCount == 0 || (getAllPlayersCount() / validServerCount) >= playerAmountToCreateInstance);
    }

    public boolean needDeleteInstance() {
        return validServerCount > 1 &&
                (getAllPlayersCount() / validServerCount) < playerAmountToCreateInstance;
    }

    public int calculateRequiredServers() {
        return (int) Math.ceil((double) getAllPlayersCount() / playerAmountToCreateInstance);
    }

    public boolean canConnect() {
        return !serverMap.isEmpty();
    }
}
