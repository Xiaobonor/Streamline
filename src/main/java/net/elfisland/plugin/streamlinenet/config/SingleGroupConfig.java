package net.elfisland.plugin.streamlinenet.config;

import lombok.Data;

@Data
public class SingleGroupConfig {
    private String fromHostname;
    private String serverName;
    private int maxInstance;
    private int playerAmountToCreateInstance;
    private String hubServer;
    private int autoRestartInterval;
    private int[] transferWarningIntervals;
    private int postShutdownWait;
}
