package top.earthstudio.vulkanearlyloading.util;

import com.mojang.logging.LogUtils;
import net.neoforged.fml.loading.FMLPaths;
import org.slf4j.Logger;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public class FmlConfigLock {
    private static final Logger LOGGER = LogUtils.getLogger();

    public static void ensureLocked(String targetProvider) {
        try {
            File configFile = getConfigFile();
            if (configFile == null) return;

            if (!configFile.exists()) {
                configFile.getParentFile().mkdirs();
                List<String> defaultLines = List.of(
                        "# Configured by VulkanEarlyLoading",
                        "earlyWindowProvider = \"" + targetProvider + "\""
                );
                Files.write(configFile.toPath(), defaultLines, StandardCharsets.UTF_8);
                LOGGER.info("Created config/fml.toml with earlyWindowProvider = \"{}\"", targetProvider);
                return;
            }

            List<String> lines = Files.readAllLines(configFile.toPath(), StandardCharsets.UTF_8);
            List<String> newLines = new ArrayList<>();

            boolean hasProvider = false;
            boolean modified = false;

            for (String line : lines) {
                String trimmed = line.trim();

                if (trimmed.startsWith("earlyWindowProvider")) {
                    hasProvider = true;
                    if (!trimmed.contains("\"" + targetProvider + "\"")) {
                        newLines.add("earlyWindowProvider = \"" + targetProvider + "\"");
                        modified = true;
                        continue;
                    }
                }
                newLines.add(line);
            }

            if (!hasProvider) {
                newLines.add("earlyWindowProvider = \"" + targetProvider + "\"");
                modified = true;
            }

            if (modified) {
                Files.write(configFile.toPath(), newLines, StandardCharsets.UTF_8);
                LOGGER.info("Locked earlyWindowProvider to '{}' in {}", targetProvider, configFile.getName());
            }
        } catch (Throwable t) {
            LOGGER.error("Failed to inspect/lock earlyWindowProvider", t);
        }
    }

    private static File getConfigFile() {
        try {
            Path configDir = FMLPaths.CONFIGDIR.get();
            if (configDir != null) {
                return configDir.resolve("fml.toml").toFile();
            }
        } catch (Throwable ignored) {}

        File f1 = new File("config", "fml.toml");
        if (f1.getParentFile().exists()) return f1;
        return new File("run/config/fml.toml");
    }
}