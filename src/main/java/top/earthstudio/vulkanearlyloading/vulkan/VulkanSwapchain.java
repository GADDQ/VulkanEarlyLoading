package top.earthstudio.vulkanearlyloading.vulkan;

import org.lwjgl.glfw.GLFW;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;

import java.nio.IntBuffer;
import java.nio.LongBuffer;
import java.util.ArrayList;
import java.util.List;

import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.KHRSurface.*;
import static org.lwjgl.vulkan.KHRSwapchain.*;
import static org.lwjgl.vulkan.VK10.*;

public class VulkanSwapchain {
    private final VulkanContext context;
    private long swapchain = VK_NULL_HANDLE;
    private int imageFormat;
    private int width;
    private int height;

    private final List<Long> images = new ArrayList<>();
    private final List<Long> imageViews = new ArrayList<>();

    public VulkanSwapchain(VulkanContext context, int width, int height) {
        this.context = context;
        this.width = width;
        this.height = height;
        create(VK_NULL_HANDLE);
    }

    private void create(long oldSwapchain) {
        try (MemoryStack stack = stackPush()) {
            this.imageFormat = VK_FORMAT_B8G8R8A8_UNORM;

            VkSwapchainCreateInfoKHR createInfo = VkSwapchainCreateInfoKHR.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_SWAPCHAIN_CREATE_INFO_KHR)
                    .surface(this.context.surface())
                    .minImageCount(2)
                    .imageFormat(this.imageFormat)
                    .imageColorSpace(VK_COLOR_SPACE_SRGB_NONLINEAR_KHR)
                    .imageArrayLayers(1)
                    .imageUsage(VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT)
                    .imageSharingMode(VK_SHARING_MODE_EXCLUSIVE)
                    .preTransform(VK_SURFACE_TRANSFORM_IDENTITY_BIT_KHR)
                    .compositeAlpha(VK_COMPOSITE_ALPHA_OPAQUE_BIT_KHR)
                    .presentMode(VK_PRESENT_MODE_FIFO_KHR)
                    .clipped(true)
                    .oldSwapchain(oldSwapchain);

            createInfo.imageExtent().width(this.width).height(this.height);

            LongBuffer pSwapchain = stack.longs(VK_NULL_HANDLE);
            if (vkCreateSwapchainKHR(this.context.device(), createInfo, null, pSwapchain) != VK_SUCCESS) {
                throw new RuntimeException("Failed to create Swapchain!");
            }
            this.swapchain = pSwapchain.get(0);

            // 获取 Images
            IntBuffer pCount = stack.ints(0);
            vkGetSwapchainImagesKHR(this.context.device(), this.swapchain, pCount, null);
            LongBuffer pImages = stack.mallocLong(pCount.get(0));
            vkGetSwapchainImagesKHR(this.context.device(), this.swapchain, pCount, pImages);
            this.images.clear();
            for (int i = 0; i < pCount.get(0); i++) {
                this.images.add(pImages.get(i));
            }

            // 创建 ImageViews
            this.imageViews.clear();
            for (long image : this.images) {
                VkImageViewCreateInfo viewInfo = VkImageViewCreateInfo.calloc(stack)
                        .sType(VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO)
                        .image(image)
                        .viewType(VK_IMAGE_VIEW_TYPE_2D)
                        .format(this.imageFormat);
                viewInfo.subresourceRange().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).levelCount(1).layerCount(1);

                LongBuffer pView = stack.longs(VK_NULL_HANDLE);
                vkCreateImageView(this.context.device(), viewInfo, null, pView);
                this.imageViews.add(pView.get(0));
            }
        }
    }

    public void recreate() {
        try (MemoryStack stack = stackPush()) {
            IntBuffer pW = stack.ints(0);
            IntBuffer pH = stack.ints(0);
            GLFW.glfwGetFramebufferSize(this.context.windowHandle(), pW, pH);
            while (pW.get(0) == 0 || pH.get(0) == 0) {
                GLFW.glfwGetFramebufferSize(this.context.windowHandle(), pW, pH);
                GLFW.glfwWaitEvents();
            }
            this.width = pW.get(0);
            this.height = pH.get(0);
        }

        this.context.waitIdle();
        cleanupViews();

        long oldSwapchain = this.swapchain;
        create(oldSwapchain);
        vkDestroySwapchainKHR(this.context.device(), oldSwapchain, null);
    }

    private void cleanupViews() {
        for (long view : this.imageViews) {
            vkDestroyImageView(this.context.device(), view, null);
        }
        this.imageViews.clear();
        this.images.clear();
    }

    public void destroy() {
        cleanupViews();
        if (this.swapchain != VK_NULL_HANDLE) {
            vkDestroySwapchainKHR(this.context.device(), this.swapchain, null);
            this.swapchain = VK_NULL_HANDLE;
        }
    }

    public long handle() { return swapchain; }
    public int imageFormat() { return imageFormat; }
    public int width() { return width; }
    public int height() { return height; }
    public List<Long> imageViews() { return imageViews; }
}