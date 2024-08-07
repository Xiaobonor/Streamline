package net.elfisland.plugin.streamlinenet.group;

import lombok.extern.slf4j.Slf4j;
import net.elfisland.plugin.streamlinenet.config.FlexNetConfig;
import net.elfisland.plugin.streamlinenet.config.GroupConfig;
import org.slf4j.Logger;

import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.stream.Collectors;

@Slf4j
public class FlexNetGroupManager {

    private final FlexNetConfig config;
    private final Logger logger;
    private final Map<String, FlexNetGroup> groupIdMap = new HashMap<>();
    private final Map<String, FlexNetGroup> groupFromHostMap = new HashMap<>();

    public FlexNetGroupManager(FlexNetConfig config, Logger logger) {
        this.config = config;
        this.logger = logger;
        initializeGroups();
    }

    private void initializeGroups() {
        config.getGroups().forEach(this::createAndRegisterGroup);
        logger.info("Loaded {} groups: {}", groupIdMap.size(), String.join(", ", groupIdMap.keySet()));
    }

    private void createAndRegisterGroup(String key, GroupConfig value) {
        FlexNetGroup group = buildGroupFromConfig(key, value);
        groupIdMap.put(key, group);
        groupFromHostMap.put(group.getFromHostname(), group);
    }

    private FlexNetGroup buildGroupFromConfig(String id, GroupConfig config) {
        return FlexNetGroup.builder()
                .id(id)
                .fromHostname(config.getFromHostname())
                .serverName(config.getServerName())
                .hubServer(config.getHubServer())
                .autoRestartInterval(config.getAutoRestartInterval())
                .transferWarningIntervals(config.getTransferWarningIntervals())
                .playerAmountToCreateInstance(config.getPlayerAmountToCreateInstance())
                .maxInstance(config.getMaxInstance())
                .postShutdownWait(config.getPostShutdownWait())
                .build();
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

    public List<FlexNetGroup> getAllGroups() {
        return List.copyOf(groupIdMap.values());
    }
}
