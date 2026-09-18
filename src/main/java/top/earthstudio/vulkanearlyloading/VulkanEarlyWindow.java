package top.earthstudio.vulkanearlyloading;

import com.mojang.logging.LogUtils;
import net.neoforged.fml.ModLoadingIssue;
import net.neoforged.fml.earlydisplay.theme.ImageLoader;
import net.neoforged.fml.earlydisplay.theme.NativeBuffer;
import net.neoforged.fml.earlydisplay.theme.UncompressedImage;
import net.neoforged.fml.loading.ProgramArgs;
import net.neoforged.fml.loading.progress.ProgressMeter;
import net.neoforged.fml.loading.progress.StartupNotificationManager;
import net.neoforged.neoforgespi.earlywindow.ImmediateWindowProvider;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.glfw.GLFWImage;
import org.lwjgl.glfw.GLFWVidMode;
import org.lwjgl.system.MemoryStack;
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

    private ProgressMeter mainProgress = null;
    private String neoForgeVersion = null;
    private String minecraftVersion = null;

    private int winWidth = 854;
    private int winHeight = 480;

    @Override
    public String name() {
        return "vulkanearlyloading";
    }

    @Override
    public void initialize(ProgramArgs args) {
        LOGGER.info("Launching dedicated Vulkan Early Window...");

        this.mainProgress = StartupNotificationManager.addProgressBar("", 0);
        updateProgress("Initializing Game Graphics");

        if (this.neoForgeVersion != null) {
            final String ver = this.neoForgeVersion;
            StartupNotificationManager.modLoaderConsumer().ifPresent(c -> c.accept("Starting NeoForge " + ver));
        }

        if (!GLFW.glfwInit()) {
            throw new IllegalStateException("Failed to initialize GLFW");
        }

        GLFW.glfwDefaultWindowHints();
        GLFW.glfwWindowHint(GLFW.GLFW_CLIENT_API, GLFW.GLFW_NO_API);
        // 先隐藏窗口，在居中和贴图标完毕后统一显示，防止窗口在左上角闪烁
        GLFW.glfwWindowHint(GLFW.GLFW_VISIBLE, GLFW.GLFW_FALSE);
        GLFW.glfwWindowHint(GLFW.GLFW_RESIZABLE, GLFW.GLFW_TRUE);

        // 1. 1:1 对齐原版标题：Minecraft: NeoForge Loading...
        String windowTitle = "Minecraft: NeoForge Loading...";
        this.windowHandle = GLFW.glfwCreateWindow(this.winWidth, this.winHeight, windowTitle, 0L, 0L);
        if (this.windowHandle == 0L) {
            throw new RuntimeException("Failed to create GLFW window with GLFW_NO_API!");
        }

        // 2. 1:1 对齐原版居中逻辑 (DisplayWindow 第 370-417 行)
        centerWindowOnPrimaryMonitor();

        // 3. 1:1 对齐原版图标：设置 neoforged_icon.png
        setNeoForgeWindowIcon();

        // 4. 显示窗口并刷新
        GLFW.glfwShowWindow(this.windowHandle);
        GLFW.glfwPollEvents();

        this.renderer = new VulkanRenderer(this.windowHandle, this.winWidth, this.winHeight,
                this::getVersionString, () -> this.mainProgress);
        this.renderer.init();

        GLFW.glfwSetWindowSizeCallback(this.windowHandle, (window, w, h) -> {
            if (this.renderer != null && w > 0 && h > 0) {
                this.winWidth = w;
                this.winHeight = h;
                this.renderer.notifyResize();
            }
        });

        this.renderThread = new Thread(this::renderLoop, "VulkanEarlyLoading-RenderThread");
        this.renderThread.start();
    }

    /**
     * 1:1 对齐原版主显示器居中算法
     */
    private void centerWindowOnPrimaryMonitor() {
        long primaryMonitor = GLFW.glfwGetPrimaryMonitor();
        if (primaryMonitor != 0L) {
            GLFWVidMode vidmode = GLFW.glfwGetVideoMode(primaryMonitor);
            if (vidmode != null) {
                try (MemoryStack stack = MemoryStack.stackPush()) {
                    java.nio.IntBuffer pX = stack.ints(0);
                    java.nio.IntBuffer pY = stack.ints(0);
                    GLFW.glfwGetMonitorPos(primaryMonitor, pX, pY);
                    int posX = (vidmode.width() - this.winWidth) / 2 + pX.get(0);
                    int posY = (vidmode.height() - this.winHeight) / 2 + pY.get(0);
                    GLFW.glfwSetWindowPos(this.windowHandle, posX, posY);
                }
            }
        }
    }

    /**
     * 1:1 贴上 NeoForge 官方图标
     */
    private void setNeoForgeWindowIcon() {
        try (NativeBuffer buffer = NativeBuffer.loadFromClasspath("net/neoforged/fml/earlydisplay/theme/neoforged_icon.png", null)) {
            ImageLoader.Result result = ImageLoader.tryLoadImage("neoforged icon", null, buffer);
            if (result instanceof ImageLoader.Result.Success success) {
                UncompressedImage icon = success.image();
                GLFWImage.Buffer imgBuf = GLFWImage.malloc(1);
                GLFWImage img = GLFWImage.malloc();
                img.set(icon.width(), icon.height(), icon.imageData());
                imgBuf.put(0, img);
                GLFW.glfwSetWindowIcon(this.windowHandle, imgBuf);
                imgBuf.free();
                img.free();
            }
        } catch (Throwable t) {
            LOGGER.warn("Failed to set NeoForge window icon: {}", t.getMessage());
        }
    }

    private void renderLoop() {
        long lastTick = System.currentTimeMillis();
        while (this.running && !GLFW.glfwWindowShouldClose(this.windowHandle)) {
            long now = System.currentTimeMillis();
            if (now - lastTick >= 50L) {
                lastTick = now;
                if (this.renderer != null) {
                    this.renderer.drawFrame();
                }
            }
            try {
                Thread.sleep(5L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
    }

    /**
     * 【消灭鼠标转圈的命脉】：由 FML 主线程周期性高频调用！
     * 在主线程上分发 Win32 操作系统事件队列，彻底解决无响应、无法最大化问题！
     */
    @Override
    public void periodicTick() {
        GLFW.glfwPollEvents();
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

        // 1:1 对齐原版 DisplayWindow 第 500 行：注销早期窗口的回调，杜绝本体产生双窗口冲突！
        org.lwjgl.system.Callback cb = GLFW.glfwSetWindowSizeCallback(this.windowHandle, null);
        if (cb != null) cb.close();

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