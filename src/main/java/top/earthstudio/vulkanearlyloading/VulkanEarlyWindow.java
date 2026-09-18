package top.earthstudio.vulkanearlyloading;

import com.mojang.logging.LogUtils;
import net.neoforged.fml.ModLoadingIssue;
import net.neoforged.fml.loading.ProgramArgs;
import net.neoforged.fml.loading.progress.ProgressMeter;
import net.neoforged.fml.loading.progress.StartupNotificationManager;
import net.neoforged.neoforgespi.earlywindow.ImmediateWindowProvider;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import top.earthstudio.vulkanearlyloading.vulkan.VulkanRenderer;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

public class VulkanEarlyWindow implements ImmediateWindowProvider {
    private static final Logger LOGGER = LogUtils.getLogger();

    private long windowHandle = 0L;
    private VulkanRenderer renderer;
    private Thread renderThread;
    private volatile boolean running = true;

    // 严格全局唯一主进度条（绝不重复注册！）
    private ProgressMeter mainProgress = null;
    private String neoForgeVersion = null;
    private String minecraftVersion = null;

    public VulkanEarlyWindow() {
        this.mainProgress = StartupNotificationManager.addProgressBar("", 0);
    }


    @Override
    public String name() {
        return "vulkanearlyloading";
    }

    @Override
    public void initialize(ProgramArgs args) {
        LOGGER.info("Launching dedicated Vulkan Early Window...");

        // 1. 严格只注册一次唯一主进度条（保证排在第 1 格最顶端！）
        this.mainProgress = StartupNotificationManager.addProgressBar("", 0);

        // 2. 1:1 对齐 DisplayWindow 第 218 行
        updateProgress("Initializing Game Graphics");

        // 3. 如果在 initialize 之前已经收到了版本号，补发开屏日志
        if (this.neoForgeVersion != null) {
            final String ver = this.neoForgeVersion;
            StartupNotificationManager.modLoaderConsumer().ifPresent(c -> c.accept("Starting NeoForge " + ver));
        }

        if (!GLFW.glfwInit()) {
            throw new IllegalStateException("Failed to initialize GLFW");
        }

        GLFW.glfwDefaultWindowHints();
        GLFW.glfwWindowHint(GLFW.GLFW_CLIENT_API, GLFW.GLFW_NO_API);
        GLFW.glfwWindowHint(GLFW.GLFW_VISIBLE, GLFW.GLFW_TRUE);
        GLFW.glfwWindowHint(GLFW.GLFW_RESIZABLE, GLFW.GLFW_TRUE);

        int width = 854;
        int height = 480;
        this.windowHandle = GLFW.glfwCreateWindow(width, height, "Minecraft: NeoForge Loading...", 0L, 0L);
        if (this.windowHandle == 0L) {
            throw new RuntimeException("Failed to create GLFW window with GLFW_NO_API!");
        }

        this.renderer = new VulkanRenderer(this.windowHandle, width, height, this::getVersionString, () -> this.mainProgress);
        this.renderer.init();

        GLFW.glfwSetFramebufferSizeCallback(this.windowHandle, (window, w, h) -> {
            if (this.renderer != null) {
                this.renderer.notifyResize();
            }
        });

        this.renderThread = new Thread(this::renderLoop, "VulkanEarlyLoading-RenderThread");
        this.renderThread.start();
    }

    private void renderLoop() {
        long lastTick = System.currentTimeMillis();
        while (this.running && !GLFW.glfwWindowShouldClose(this.windowHandle)) {
            long now = System.currentTimeMillis();
            if (now - lastTick >= 50L) { // 严格 20 FPS (50ms)
                lastTick = now;
                if (this.renderer != null) {
                    this.renderer.drawFrame();
                }
            }
            GLFW.glfwPollEvents();
            try {
                Thread.sleep(5L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
    }

    @Override
    public long takeOverGlfwWindow() {
        LOGGER.info("Minecraft is claiming window via takeOverGlfwWindow. Initiating Vulkan handoff...");

        this.running = false;
        if (this.renderThread != null) {
            try {
                this.renderThread.join(2000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            this.renderThread = null;
        }

        if (this.renderer != null) {
            this.renderer.destroyPipelineAndContext();
            this.renderer = null;
        }

        GLFW.glfwDefaultWindowHints();
        GLFW.glfwSetWindowAttrib(this.windowHandle, GLFW.GLFW_RESIZABLE, GLFW.GLFW_TRUE);

        try {
            ClassLoader gameClassLoader = Thread.currentThread().getContextClassLoader();
            Class<?> bootstrapClass = Class.forName("com.mojang.blaze3d.platform.NativeLibrariesBootstrap", true, gameClassLoader);

            java.lang.reflect.Field field = bootstrapClass.getDeclaredField("vulkanLoaderAvailable");
            field.setAccessible(true);
            field.setBoolean(null, true);

            for (String libMethodName : new String[]{"loadShaderc", "loadSpvc", "loadVma"}) {
                try {
                    java.lang.reflect.Method method = bootstrapClass.getDeclaredMethod(libMethodName);
                    method.setAccessible(true);
                    method.invoke(null);
                } catch (Throwable ignored) {}
            }
        } catch (Throwable t) {
            LOGGER.error("Failed to patch NativeLibrariesBootstrap in GameClassLoader", t);
        }

        if (org.lwjgl.vulkan.VK.getFunctionProvider() == null) {
            try {
                org.lwjgl.vulkan.VK.create();
            } catch (Throwable ignored) {}
        }

        LOGGER.info("Handoff complete. Handing clean windowHandle to Minecraft: {}", this.windowHandle);
        return this.windowHandle;
    }

    @Override
    public void setMinecraftVersion(String version) {
        this.minecraftVersion = version;
    }

    /**
     * 1:1 对齐 DisplayWindow.java 第 228-232 行：激活开屏 Starting NeoForge 日志！
     */
    @Override
    public void setNeoForgeVersion(String version) {
        if (!Objects.equals(this.neoForgeVersion, version)) {
            this.neoForgeVersion = version;
            StartupNotificationManager.modLoaderConsumer().ifPresent(c -> c.accept("Starting NeoForge " + version));
        }
    }

    @Override
    public void updateProgress(String label) {
        if (this.mainProgress != null) {
            this.mainProgress.label(label);
        }
    }

    @Override
    public void completeProgress() {
        if (this.mainProgress != null) {
            this.mainProgress.complete();
        }
    }

    @Override
    public void periodicTick() {}

    @Override
    public void crash(String message) {
        LOGGER.error("Vulkan early window crash: {}", message);
    }

    @Override
    public void displayFatalErrorAndExit(List<ModLoadingIssue> issues, Path modsFolder, Path logFile, Path crashReportFile) {
        this.running = false;
        if (this.renderer != null) {
            this.renderer.destroyPipelineAndContext();
        }
        if (this.windowHandle != 0L) {
            GLFW.glfwDestroyWindow(this.windowHandle);
        }
        System.exit(1);
    }

    private String getVersionString() {
        StringBuilder result = new StringBuilder();
        if (this.minecraftVersion != null && !this.minecraftVersion.isEmpty()) {
            result.append(this.minecraftVersion);
        }
        if (this.neoForgeVersion != null && !this.neoForgeVersion.isEmpty()) {
            if (!result.isEmpty()) result.append("-");
            result.append(this.neoForgeVersion.split("-")[0]);
        }
        return result.toString();
    }
}