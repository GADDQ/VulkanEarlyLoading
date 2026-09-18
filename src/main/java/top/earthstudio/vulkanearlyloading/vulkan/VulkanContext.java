package top.earthstudio.vulkanearlyloading.vulkan;

import org.lwjgl.PointerBuffer;
import org.lwjgl.glfw.GLFWVulkan;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;

import java.nio.IntBuffer;
import java.nio.LongBuffer;

import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.KHRSurface.vkDestroySurfaceKHR;
import static org.lwjgl.vulkan.KHRSwapchain.VK_KHR_SWAPCHAIN_EXTENSION_NAME;
import static org.lwjgl.vulkan.VK10.*;

public class VulkanContext {
    private final long windowHandle;
    private VkInstance instance;
    private long surface;
    private VkPhysicalDevice physicalDevice;
    private VkDevice device;
    private VkQueue graphicsQueue;
    private long commandPool;

    public VulkanContext(long windowHandle) {
        this.windowHandle = windowHandle;
        initInstance();
        initSurface();
        pickPhysicalDevice();
        initLogicalDevice();
        initCommandPool();
        resetVkGlobalState();
    }

    private void resetVkGlobalState() {
        try {
            java.lang.reflect.Field fpField = org.lwjgl.vulkan.VK.class.getDeclaredField("functionProvider");
            fpField.setAccessible(true);
            fpField.set(null, null);

            java.lang.reflect.Field gcField = org.lwjgl.vulkan.VK.class.getDeclaredField("globalCommands");
            gcField.setAccessible(true);
            gcField.set(null, null);

            com.mojang.logging.LogUtils.getLogger().info("Reset LWJGL VK global state successfully, Mojang VK.create() will pass cleanly.");
        } catch (Throwable t) {
            com.mojang.logging.LogUtils.getLogger().warn("Failed to reset VK global state: {}", t.getMessage());
        }
    }

    private void initInstance() {
        try (MemoryStack stack = stackPush()) {
            VkApplicationInfo appInfo = VkApplicationInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_APPLICATION_INFO)
                    .pApplicationName(stack.UTF8("VulkanEarlyLoading"))
                    .apiVersion(VK_API_VERSION_1_0);

            PointerBuffer glfwExtensions = GLFWVulkan.glfwGetRequiredInstanceExtensions();
            if (glfwExtensions == null) throw new RuntimeException("Failed to find GLFW Vulkan extensions!");

            VkInstanceCreateInfo createInfo = VkInstanceCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO)
                    .pApplicationInfo(appInfo)
                    .ppEnabledExtensionNames(glfwExtensions);

            PointerBuffer pInstance = stack.mallocPointer(1);
            if (vkCreateInstance(createInfo, null, pInstance) != VK_SUCCESS) {
                throw new RuntimeException("Failed to create Vulkan Instance!");
            }
            this.instance = new VkInstance(pInstance.get(0), createInfo);
        }
    }

    private void initSurface() {
        try (MemoryStack stack = stackPush()) {
            LongBuffer pSurface = stack.longs(VK_NULL_HANDLE);
            if (GLFWVulkan.glfwCreateWindowSurface(this.instance, this.windowHandle, null, pSurface) != VK_SUCCESS) {
                throw new RuntimeException("Failed to create Vulkan Surface!");
            }
            this.surface = pSurface.get(0);
        }
    }

    private void pickPhysicalDevice() {
        try (MemoryStack stack = stackPush()) {
            IntBuffer pCount = stack.ints(0);
            vkEnumeratePhysicalDevices(this.instance, pCount, null);
            if (pCount.get(0) == 0) throw new RuntimeException("No Vulkan capable GPU found!");

            PointerBuffer pDevices = stack.mallocPointer(pCount.get(0));
            vkEnumeratePhysicalDevices(this.instance, pCount, pDevices);
            this.physicalDevice = new VkPhysicalDevice(pDevices.get(0), this.instance);
        }
    }

    private void initLogicalDevice() {
        try (MemoryStack stack = stackPush()) {
            VkPhysicalDeviceFeatures deviceFeatures = VkPhysicalDeviceFeatures.calloc(stack)
                    .fillModeNonSolid(true);

            VkDeviceQueueCreateInfo.Buffer queueCreateInfo = VkDeviceQueueCreateInfo.calloc(1, stack)
                    .sType(VK_STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO)
                    .queueFamilyIndex(0)
                    .pQueuePriorities(stack.floats(1.0f));

            PointerBuffer extensions = stack.pointers(stack.UTF8(VK_KHR_SWAPCHAIN_EXTENSION_NAME));

            VkDeviceCreateInfo createInfo = VkDeviceCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO)
                    .pQueueCreateInfos(queueCreateInfo)
                    .pEnabledFeatures(deviceFeatures)
                    .ppEnabledExtensionNames(extensions);

            PointerBuffer pDevice = stack.mallocPointer(1);
            if (vkCreateDevice(this.physicalDevice, createInfo, null, pDevice) != VK_SUCCESS) {
                throw new RuntimeException("Failed to create Logical Device!");
            }
            this.device = new VkDevice(pDevice.get(0), this.physicalDevice, createInfo);

            PointerBuffer pQueue = stack.mallocPointer(1);
            vkGetDeviceQueue(this.device, 0, 0, pQueue);
            this.graphicsQueue = new VkQueue(pQueue.get(0), this.device);
        }
    }

    private void initCommandPool() {
        try (MemoryStack stack = stackPush()) {
            VkCommandPoolCreateInfo poolInfo = VkCommandPoolCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO)
                    .flags(VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT)
                    .queueFamilyIndex(0);

            LongBuffer pPool = stack.longs(VK_NULL_HANDLE);
            vkCreateCommandPool(this.device, poolInfo, null, pPool);
            this.commandPool = pPool.get(0);
        }
    }

    /**
     * 后续用于上传贴图、更新字体的核心工具：开始记录一次性指令
     */
    public VkCommandBuffer beginSingleTimeCommands() {
        try (MemoryStack stack = stackPush()) {
            VkCommandBufferAllocateInfo allocInfo = VkCommandBufferAllocateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO)
                    .level(VK_COMMAND_BUFFER_LEVEL_PRIMARY)
                    .commandPool(this.commandPool)
                    .commandBufferCount(1);

            PointerBuffer pCmd = stack.mallocPointer(1);
            vkAllocateCommandBuffers(this.device, allocInfo, pCmd);
            VkCommandBuffer cmd = new VkCommandBuffer(pCmd.get(0), this.device);

            VkCommandBufferBeginInfo beginInfo = VkCommandBufferBeginInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO)
                    .flags(VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT);
            vkBeginCommandBuffer(cmd, beginInfo);
            return cmd;
        }
    }

    /**
     * 结束并同步执行一次性指令
     */
    public void endSingleTimeCommands(VkCommandBuffer cmd) {
        vkEndCommandBuffer(cmd);

        try (MemoryStack stack = stackPush()) {
            VkSubmitInfo submitInfo = VkSubmitInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_SUBMIT_INFO)
                    .pCommandBuffers(stack.pointers(cmd.address()));

            vkQueueSubmit(this.graphicsQueue, submitInfo, VK_NULL_HANDLE);
            vkQueueWaitIdle(this.graphicsQueue);
            vkFreeCommandBuffers(this.device, this.commandPool, cmd);
        }
    }

    /**
     * 查找适合当前 GPU 内存特性的内存类型索引
     */
    public int findMemoryType(int typeFilter, int properties) {
        try (MemoryStack stack = stackPush()) {
            VkPhysicalDeviceMemoryProperties memProperties = VkPhysicalDeviceMemoryProperties.calloc(stack);
            vkGetPhysicalDeviceMemoryProperties(this.physicalDevice, memProperties);

            for (int i = 0; i < memProperties.memoryTypeCount(); i++) {
                if ((typeFilter & (1 << i)) != 0 && (memProperties.memoryTypes(i).propertyFlags() & properties) == properties) {
                    return i;
                }
            }
            throw new RuntimeException("Failed to find suitable memory type!");
        }
    }

    public void waitIdle() {
        if (this.device != null) {
            vkDeviceWaitIdle(this.device);
        }
    }

    public void destroy() {
        waitIdle();
        if (this.commandPool != VK_NULL_HANDLE) {
            vkDestroyCommandPool(this.device, this.commandPool, null);
        }
        if (this.device != null) {
            vkDestroyDevice(this.device, null);
            this.device = null;
        }
        if (this.surface != VK_NULL_HANDLE) {
            vkDestroySurfaceKHR(this.instance, this.surface, null);
            this.surface = VK_NULL_HANDLE;
        }
        if (this.instance != null) {
            vkDestroyInstance(this.instance, null);
            this.instance = null;
        }
    }

    // Getters
    public VkDevice device() { return device; }
    public VkQueue graphicsQueue() { return graphicsQueue; }
    public long commandPool() { return commandPool; }
    public long surface() { return surface; }
    public long windowHandle() { return windowHandle; }
}