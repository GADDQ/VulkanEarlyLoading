package top.earthstudio.vulkanearlyloading.vulkan;

import com.mojang.logging.LogUtils;
import net.neoforged.fml.earlydisplay.theme.ImageLoader;
import net.neoforged.fml.earlydisplay.theme.NativeBuffer;
import net.neoforged.fml.earlydisplay.theme.UncompressedImage;
import org.slf4j.Logger;

public class VulkanResourceLoader {
    private static final Logger LOGGER = LogUtils.getLogger();

    /**
     * 从 Classpath 读取原版 earlydisplay 自带的 PNG 并转为 Vulkan 贴图
     */
    public static VulkanTexture loadClasspathTexture(VulkanContext context, String path, long descriptorPool, long descriptorSetLayout) {
        ClassLoader[] loaders = {
                Thread.currentThread().getContextClassLoader(),
                ClassLoader.getSystemClassLoader(),
                VulkanResourceLoader.class.getClassLoader()
        };

        for (ClassLoader loader : loaders) {
            try (NativeBuffer buffer = NativeBuffer.loadFromClasspath(path, loader)) {
                ImageLoader.Result result = ImageLoader.tryLoadImage(path, null, buffer);
                if (result instanceof ImageLoader.Result.Success success) {
                    UncompressedImage img = success.image();
                    LOGGER.info("Loaded earlydisplay texture: {} ({}x{})", path, img.width(), img.height());
                    return new VulkanTexture(context, img.width(), img.height(), img.imageData(), descriptorPool, descriptorSetLayout);
                }
            } catch (Throwable ignored) {}
        }

        LOGGER.warn("Failed to load texture from classpath: {}", path);
        return null;
    }
}