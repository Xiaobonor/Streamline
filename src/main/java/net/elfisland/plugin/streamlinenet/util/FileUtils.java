package net.elfisland.plugin.streamlinenet.util;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

public class FileUtils {

    public static void copyFileFromJar(ClassLoader classLoader, String resourcePath, Path destination) {
        try (InputStream in = classLoader.getResourceAsStream(resourcePath)) {
            Objects.requireNonNull(in, resourcePath + " not found in resources folder");
            Files.copy(in, destination);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

}
