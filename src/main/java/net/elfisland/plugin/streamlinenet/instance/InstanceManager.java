package net.elfisland.plugin.streamlinenet.instance;

import net.elfisland.plugin.streamlinenet.model.InstanceTemplate;

import java.util.function.Consumer;

public interface InstanceManager {

    void createInstance(InstanceTemplate template, Consumer<InstanceCreationResult> callback);
    void deleteInstance(String identifier, Consumer<Boolean> callback);

}
