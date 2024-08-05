package net.elfisland.plugin.streamlinenet.config;

import lombok.Data;

@Data
public class LocaleConfig {
    private String invalidHostname;
    private String failedToConnect;
    private String noServerAvailable;
    private String serverTransferWarning;
    private String joinNewCommandUsage;
    private String joinNewServerNotFound;
    private String joinNewGroupNotFound;
    private String joinNewServerRestarting;
    private String playerInServerMessage;
}
