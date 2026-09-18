package top.earthstudio.vulkanearlyloading.early;

import com.mojang.logging.LogUtils;
import net.neoforged.fml.loading.ProgramArgs;
import net.neoforged.neoforgespi.earlywindow.GraphicsBootstrapper;
import org.slf4j.Logger;
import top.earthstudio.vulkanearlyloading.util.BackendDetector;
import top.earthstudio.vulkanearlyloading.util.FmlConfigLock;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

public class VulkanEarlyGraphicsBootstrapper implements GraphicsBootstrapper {
    private static final Logger LOGGER = LogUtils.getLogger();

    @Override
    public String name() {
        return "vulkanearlyloading_bootstrapper";
    }

    @Override
    public void bootstrap(String[] arguments) {
        // 1. 核心前线判断：当前启动究竟是 Vulkan 还是 OpenGL？
        boolean isVulkan = BackendDetector.isVulkanBackend(ProgramArgs.from(arguments));
        String targetProvider = isVulkan ? "vulkanearlyloading" : "fmlearlywindow";

        LOGGER.info("GraphicsBootstrapper: Active backend is [{}]. Dispatching provider to: '{}'",
                isVulkan ? "Vulkan" : "OpenGL", targetProvider);

        // 2. 物理锁定对应的 Provider
        FmlConfigLock.ensureLocked(targetProvider);

        // 3. 内存热补丁
        try {
            Class<?> fmlConfigClz = Class.forName("net.neoforged.fml.loading.FMLConfig");

            boolean reloaded = false;
            for (Method m : fmlConfigClz.getDeclaredMethods()) {
                m.setAccessible(true);
                if (m.getParameterCount() == 0 && ("load".equals(m.getName()) || "reload".equals(m.getName()))) {
                    m.invoke(null);
                    reloaded = true;
                    LOGGER.info("FMLConfig reloaded from disk via {}()!", m.getName());
                    break;
                }
            }

            if (!reloaded) {
                patchNightConfigTree(fmlConfigClz, targetProvider);
            }
        } catch (Throwable t) {
            LOGGER.error("Failed to patch FMLConfig in bootstrap", t);
        }
    }

    private void patchNightConfigTree(Class<?> fmlConfigClz, String targetProvider) {
        try {
            for (Field f : fmlConfigClz.getDeclaredFields()) {
                f.setAccessible(true);
                Object val = f.get(null);
                if (val instanceof com.electronwill.nightconfig.core.Config config) {
                    config.set("earlyWindowProvider", targetProvider);
                    LOGGER.info("Successfully patched NightConfig tree field [{}] -> '{}'!", f.getName(), targetProvider);
                    return;
                }
            }

            for (Field f : fmlConfigClz.getDeclaredFields()) {
                f.setAccessible(true);
                Object val = f.get(null);
                if (val != null && f.getName().toLowerCase().contains("earlywindowprovider")) {
                    for (Field innerF : val.getClass().getDeclaredFields()) {
                        innerF.setAccessible(true);
                        if (innerF.getType() == Object.class || innerF.getType() == String.class) {
                            innerF.set(val, targetProvider);
                            LOGGER.info("Patched inner field of ConfigValue [{}] -> '{}'!", innerF.getName(), targetProvider);
                        }
                    }
                }
            }
        } catch (Throwable t) {
            LOGGER.error("Failed to patch NightConfig tree", t);
        }
    }
}