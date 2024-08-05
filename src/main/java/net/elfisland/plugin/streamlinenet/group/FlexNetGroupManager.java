package net.elfisland.plugin.streamlinenet.group;

import lombok.extern.slf4j.Slf4j;
import net.elfisland.plugin.streamlinenet.config.FlexNetConfig;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.HashMap;

@Slf4j
public class FlexNetGroupManager {

    private final FlexNetConfig config;
    private final Logger logger;
    private final HashMap<String, FlexNetGroup> groupIdMap = new HashMap<>();
    private final HashMap<String, FlexNetGroup> groupFromHostMap = new HashMap<>();

    public FlexNetGroupManager(FlexNetConfig config, Logger logger) {
        this.config = config;
        this.logger = logger;
        initMap();
    }

    private void initMap() {
        config.getGroups().forEach((key, value) -> {
            FlexNetGroup group = FlexNetGroup.builder()
                    .id(key)
                    .fromHostname(value.getFromHostname())
                    .serverName(value.getServerName())
                    .hubServer(value.getHubServer())
                    .autoRestartInterval(value.getAutoRestartInterval())
                    .transferWarningIntervals(value.getTransferWarningIntervals())
                    .playerAmountToCreateInstance(value.getPlayerAmountToCreateInstance())
                    .maxInstance(value.getMaxInstance())
                    .postShutdownWait(value.getPostShutdownWait())
                    .build();
            groupIdMap.put(key, group);
            groupFromHostMap.put(group.getFromHostname(), group);
        });
        logger.info("Loaded {} groups: {}", groupIdMap.size(), String.join(", ", groupIdMap.keySet()));
    }

    public FlexNetGroup getGroup(String id) {
        return groupIdMap.get(id);
    }

    public FlexNetGroup getGroupFromHost(String fromHostname) {
        return groupFromHostMap.get(fromHostname);
    }

    public boolean hasGroup(String id) {
        return groupIdMap.containsKey(id);
    }

    public boolean hasGroupFromHost(String fromHostname) {
        return groupFromHostMap.containsKey(fromHostname);
    }

    public ArrayList<FlexNetGroup> getAllGroups() {
        return new ArrayList<>(groupIdMap.values());
    }

}
