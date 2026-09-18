package top.earthstudio.vulkanearlyloading.util;

import com.mojang.logging.LogUtils;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.fml.loading.ProgramArgs;
import org.slf4j.Logger;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class BackendDetector {
    private static final Logger LOGGER = LogUtils.getLogger();

    /**
     * 判断当前启动是否应该使用 Vulkan 模式
     */
    public static boolean isVulkanBackend(ProgramArgs args) {
        // 1. 检查 JVM 系统属性 (例如 -Dminecraft.gpuBackend=vulkan)
        String jvmProp = System.getProperty("minecraft.gpuBackend");
        if (jvmProp == null) {
            jvmProp = System.getProperty("minecraft.preferredGraphicsBackend");
        }
        if (jvmProp != null && "vulkan".equalsIgnoreCase(jvmProp.trim())) {
            LOGGER.info("Vulkan specified via JVM args.");
            return true;
        }

        // 2. 检查启动命令行参数 (例如 --preferredGraphicsBackend vulkan)
        if (args != null && args.getArguments() != null) {
            String[] rawArgs = args.getArguments();
            for (int i = 0; i < rawArgs.length - 1; i++) {
                if ("--preferredGraphicsBackend".equalsIgnoreCase(rawArgs[i]) || "--gpuBackend".equalsIgnoreCase(rawArgs[i])) {
                    if ("vulkan".equalsIgnoreCase(rawArgs[i + 1])) {
                        LOGGER.info("Vulkan specified via CLI args.");
                        return true;
                    }
                }
            }
        }

        // 3. 从 options.txt 读取 preferredGraphicsBackend
        List<File> candidates = new ArrayList<>();
        try {
            Path fmlGameDir = FMLPaths.GAMEDIR.get();
            candidates.add(fmlGameDir.resolve("options.txt").toFile());
        } catch (Throwable ignored) {}

        candidates.add(new File("options.txt"));
        candidates.add(new File("run", "options.txt"));

        for (File file : candidates) {
            if (file.exists() && file.isFile()) {
                if (checkOptionsFile(file)) {
                    return true;
                }
            }
        }

        LOGGER.info("preferredGraphicsBackend is 'default' or 'opengl'. Using native OpenGL early window.");
        return false;
    }

    private static boolean checkOptionsFile(File file) {
        try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.startsWith("preferredGraphicsBackend:")) {
                    String[] parts = line.split(":", 2);
                    if (parts.length > 1) {
                        // 去掉引号与前后空格，例如把 "vulkan" -> vulkan
                        String backend = parts[1].replace("\"", "").trim().toLowerCase(Locale.ROOT);
                        LOGGER.info("Parsed preferredGraphicsBackend from " + file.getName() + ": [" + backend + "]");
                        return "vulkan".equals(backend);
                    }
                }
            }
        } catch (Throwable t) {
            LOGGER.error("Error reading options file: " + t.getMessage());
        }
        return false;
    }
}