package top.earthstudio.vulkanearlyloading.vulkan;

import com.mojang.logging.LogUtils;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;
import org.slf4j.Logger;

import java.nio.ByteBuffer;
import java.nio.LongBuffer;

import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK10.*;

public class VulkanTexture implements AutoCloseable {
    private static final Logger LOGGER = LogUtils.getLogger();

    private final VulkanContext context;
    private final int width;
    private final int height;

    private long image = VK_NULL_HANDLE;
    private long memory = VK_NULL_HANDLE;
    private long imageView = VK_NULL_HANDLE;
    private long sampler = VK_NULL_HANDLE;
    private long descriptorSet = VK_NULL_HANDLE;

    public VulkanTexture(VulkanContext context, int width, int height, ByteBuffer rgbaPixels, long descriptorPool, long descriptorSetLayout) {
        this.context = context;
        this.width = width;
        this.height = height;

        long imageSize = (long) width * height * 4;

        try (VulkanBuffer stagingBuffer = new VulkanBuffer(context, imageSize, VK_BUFFER_USAGE_TRANSFER_SRC_BIT,
                VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT)) {
            stagingBuffer.upload(rgbaPixels);

            createImage(width, height);

            transitionImageLayout(VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL);
            copyBufferToImage(stagingBuffer.handle(), width, height);
            transitionImageLayout(VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
        }

        createImageView();
        createSampler(); // 采用 NEAREST 像素采样，消除插值渐变！
        createDescriptorSet(descriptorPool, descriptorSetLayout);
    }

    private void createImage(int width, int height) {
        try (MemoryStack stack = stackPush()) {
            VkImageCreateInfo imageInfo = VkImageCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO)
                    .imageType(VK_IMAGE_TYPE_2D)
                    .format(VK_FORMAT_R8G8B8A8_UNORM)
                    .mipLevels(1)
                    .arrayLayers(1)
                    .samples(VK_SAMPLE_COUNT_1_BIT)
                    .tiling(VK_IMAGE_TILING_OPTIMAL)
                    .usage(VK_IMAGE_USAGE_TRANSFER_DST_BIT | VK_IMAGE_USAGE_SAMPLED_BIT)
                    .sharingMode(VK_SHARING_MODE_EXCLUSIVE)
                    .initialLayout(VK_IMAGE_LAYOUT_UNDEFINED);
            imageInfo.extent().width(width).height(height).depth(1);

            LongBuffer pImage = stack.longs(VK_NULL_HANDLE);
            if (vkCreateImage(context.device(), imageInfo, null, pImage) != VK_SUCCESS) {
                throw new RuntimeException("Failed to create Vulkan Image!");
            }
            this.image = pImage.get(0);

            VkMemoryRequirements memReqs = VkMemoryRequirements.calloc(stack);
            vkGetImageMemoryRequirements(context.device(), this.image, memReqs);

            VkMemoryAllocateInfo allocInfo = VkMemoryAllocateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO)
                    .allocationSize(memReqs.size())
                    .memoryTypeIndex(context.findMemoryType(memReqs.memoryTypeBits(), VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT));

            LongBuffer pMemory = stack.longs(VK_NULL_HANDLE);
            if (vkAllocateMemory(context.device(), allocInfo, null, pMemory) != VK_SUCCESS) {
                throw new RuntimeException("Failed to allocate Image Memory!");
            }
            this.memory = pMemory.get(0);

            vkBindImageMemory(context.device(), this.image, this.memory, 0);
        }
    }

    private void createImageView() {
        try (MemoryStack stack = stackPush()) {
            VkImageViewCreateInfo viewInfo = VkImageViewCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO)
                    .image(this.image)
                    .viewType(VK_IMAGE_VIEW_TYPE_2D)
                    .format(VK_FORMAT_R8G8B8A8_UNORM);
            viewInfo.subresourceRange().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).levelCount(1).layerCount(1);

            LongBuffer pView = stack.longs(VK_NULL_HANDLE);
            if (vkCreateImageView(context.device(), viewInfo, null, pView) != VK_SUCCESS) {
                throw new RuntimeException("Failed to create Image View!");
            }
            this.imageView = pView.get(0);
        }
    }

    /**
     * 1:1 对齐原版 Texture.java 第 96 行：linearScaling 为 false 时使用 NEAREST 滤波！
     * 彻底消灭进度条两端的模糊渐变！
     */
    private void createSampler() {
        try (MemoryStack stack = stackPush()) {
            VkSamplerCreateInfo samplerInfo = VkSamplerCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_SAMPLER_CREATE_INFO)
                    .magFilter(VK_FILTER_NEAREST) // 采用纯正像素邻近采样！
                    .minFilter(VK_FILTER_NEAREST) // 采用纯正像素邻近采样！
                    .addressModeU(VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE)
                    .addressModeV(VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE)
                    .addressModeW(VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE);

            LongBuffer pSampler = stack.longs(VK_NULL_HANDLE);
            if (vkCreateSampler(context.device(), samplerInfo, null, pSampler) != VK_SUCCESS) {
                throw new RuntimeException("Failed to create Sampler!");
            }
            this.sampler = pSampler.get(0);
        }
    }

    private void createDescriptorSet(long pool, long layout) {
        try (MemoryStack stack = stackPush()) {
            VkDescriptorSetAllocateInfo allocInfo = VkDescriptorSetAllocateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO)
                    .descriptorPool(pool)
                    .pSetLayouts(stack.longs(layout));

            LongBuffer pSet = stack.longs(VK_NULL_HANDLE);
            if (vkAllocateDescriptorSets(context.device(), allocInfo, pSet) != VK_SUCCESS) {
                throw new RuntimeException("Failed to allocate Descriptor Set!");
            }
            this.descriptorSet = pSet.get(0);

            VkDescriptorImageInfo.Buffer imageInfo = VkDescriptorImageInfo.calloc(1, stack)
                    .imageLayout(VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL)
                    .imageView(this.imageView)
                    .sampler(this.sampler);

            VkWriteDescriptorSet.Buffer descriptorWrite = VkWriteDescriptorSet.calloc(1, stack)
                    .sType(VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET)
                    .dstSet(this.descriptorSet)
                    .dstBinding(0)
                    .descriptorCount(1)
                    .descriptorType(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER)
                    .pImageInfo(imageInfo);

            vkUpdateDescriptorSets(context.device(), descriptorWrite, null);
        }
    }

    private void transitionImageLayout(int oldLayout, int newLayout) {
        VkCommandBuffer cmd = context.beginSingleTimeCommands();
        try (MemoryStack stack = stackPush()) {
            VkImageMemoryBarrier.Buffer barrier = VkImageMemoryBarrier.calloc(1, stack)
                    .sType(VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER)
                    .oldLayout(oldLayout)
                    .newLayout(newLayout)
                    .srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                    .dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                    .image(this.image);
            barrier.subresourceRange().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).levelCount(1).layerCount(1);

            int sourceStage = VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT;
            int destinationStage = VK_PIPELINE_STAGE_TRANSFER_BIT;

            if (oldLayout == VK_IMAGE_LAYOUT_UNDEFINED && newLayout == VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL) {
                barrier.srcAccessMask(0);
                barrier.dstAccessMask(VK_ACCESS_TRANSFER_WRITE_BIT);
            } else if (oldLayout == VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL && newLayout == VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL) {
                barrier.srcAccessMask(VK_ACCESS_TRANSFER_WRITE_BIT);
                barrier.dstAccessMask(VK_ACCESS_SHADER_READ_BIT);
                sourceStage = VK_PIPELINE_STAGE_TRANSFER_BIT;
                destinationStage = VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT;
            }

            vkCmdPipelineBarrier(cmd, sourceStage, destinationStage, 0, null, null, barrier);
        }
        context.endSingleTimeCommands(cmd);
    }

    private void copyBufferToImage(long buffer, int width, int height) {
        VkCommandBuffer cmd = context.beginSingleTimeCommands();
        try (MemoryStack stack = stackPush()) {
            VkBufferImageCopy.Buffer region = VkBufferImageCopy.calloc(1, stack)
                    .bufferOffset(0)
                    .bufferRowLength(0)
                    .bufferImageHeight(0);
            region.imageSubresource().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).layerCount(1);
            region.imageExtent().width(width).height(height).depth(1);

            vkCmdCopyBufferToImage(cmd, buffer, this.image, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, region);
        }
        context.endSingleTimeCommands(cmd);
    }

    public static VulkanTexture createWhiteTexture(VulkanContext context, long pool, long layout) {
        ByteBuffer whitePixel = org.lwjgl.BufferUtils.createByteBuffer(4);
        whitePixel.put((byte) 255).put((byte) 255).put((byte) 255).put((byte) 255).flip();
        return new VulkanTexture(context, 1, 1, whitePixel, pool, layout);
    }

    @Override
    public void close() {
        if (this.sampler != VK_NULL_HANDLE) vkDestroySampler(context.device(), this.sampler, null);
        if (this.imageView != VK_NULL_HANDLE) vkDestroyImageView(context.device(), this.imageView, null);
        if (this.image != VK_NULL_HANDLE) vkDestroyImage(context.device(), this.image, null);
        if (this.memory != VK_NULL_HANDLE) vkFreeMemory(context.device(), this.memory, null);
    }

    public long descriptorSet() { return descriptorSet; }
    public int width() { return width; }
    public int height() { return height; }
}