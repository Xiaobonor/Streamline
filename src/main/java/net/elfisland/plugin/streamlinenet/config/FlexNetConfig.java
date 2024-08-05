package net.elfisland.plugin.streamlinenet.config;

import lombok.Data;
import net.elfisland.plugin.streamlinenet.model.InstanceTemplate;

import java.util.Map;

@Data
public class FlexNetConfig {
    private PterodactylConfig pterodactyl;
    private LocaleConfig locale;
    private Map<String, SingleGroupConfig> groups;
    private Map<String, InstanceTemplate> templates;
}
