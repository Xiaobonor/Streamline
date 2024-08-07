package net.elfisland.plugin.streamlinenet.util;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Objects;
import java.util.logging.Level;
import java.util.logging.Logger;

public class FileUtils {

    private static final Logger LOGGER = Logger.getLogger(FileUtils.class.getName());

    /**
     * Copies a file from the JAR resources to the specified destination path.
     *
     * @param classLoader  the class loader to use for resource loading
     * @param resourcePath the path to the resource within the JAR
     * @param destination  the destination path where the file should be copied
     */
    public static void copyFileFromJar(ClassLoader classLoader, String resourcePath, Path destination) {
        Objects.requireNonNull(classLoader, "ClassLoader must not be null");
        Objects.requireNonNull(resourcePath, "Resource path must not be null");
        Objects.requireNonNull(destination, "Destination path must not be null");

        try (InputStream inputStream = classLoader.getResourceAsStream(resourcePath)) {
            if (inputStream == null) {
                throw new IllegalArgumentException(resourcePath + " not found in resources");
            }

            createDirectoriesIfNeeded(destination);

            Files.copy(inputStream, destination, StandardCopyOption.REPLACE_EXISTING);
            LOGGER.info("Successfully copied resource " + resourcePath + " to " + destination);
        } catch (IOException e) {
            LOGGER.log(Level.SEVERE, "Failed to copy resource " + resourcePath + " to " + destination, e);
        }
    }

    /**
     * Creates directories for the destination path if they do not already exist.
     *
     * @param destination the destination path
     * @throws IOException if an error occurs creating directories
     */
    private static void createDirectoriesIfNeeded(Path destination) throws IOException {
        Path parentDir = destination.getParent();
        if (parentDir != null && !Files.exists(parentDir)) {
            Files.createDirectories(parentDir);
            LOGGER.info("Created directories for path " + parentDir);
        }
    }
}
