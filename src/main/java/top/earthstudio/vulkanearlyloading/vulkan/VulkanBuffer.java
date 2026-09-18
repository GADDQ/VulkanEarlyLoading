package top.earthstudio.vulkanearlyloading.vulkan;

import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.*;

import java.nio.ByteBuffer;
import java.nio.LongBuffer;

import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK10.*;

public class VulkanBuffer implements AutoCloseable {
    private final VulkanContext context;
    private final long size;
    private long buffer = VK_NULL_HANDLE;
    private long memory = VK_NULL_HANDLE;
    private long mappedAddress = 0L;

    public VulkanBuffer(VulkanContext context, long size, int usage, int properties) {
        this.context = context;
        this.size = size;

        try (MemoryStack stack = stackPush()) {
            VkBufferCreateInfo bufferInfo = VkBufferCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO)
                    .size(size)
                    .usage(usage)
                    .sharingMode(VK_SHARING_MODE_EXCLUSIVE);

            LongBuffer pBuffer = stack.longs(VK_NULL_HANDLE);
            if (vkCreateBuffer(context.device(), bufferInfo, null, pBuffer) != VK_SUCCESS) {
                throw new RuntimeException("Failed to create Vulkan Buffer!");
            }
            this.buffer = pBuffer.get(0);

            VkMemoryRequirements memReqs = VkMemoryRequirements.calloc(stack);
            vkGetBufferMemoryRequirements(context.device(), this.buffer, memReqs);

            VkMemoryAllocateInfo allocInfo = VkMemoryAllocateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO)
                    .allocationSize(memReqs.size())
                    .memoryTypeIndex(context.findMemoryType(memReqs.memoryTypeBits(), properties));

            LongBuffer pMemory = stack.longs(VK_NULL_HANDLE);
            if (vkAllocateMemory(context.device(), allocInfo, null, pMemory) != VK_SUCCESS) {
                throw new RuntimeException("Failed to allocate Vulkan Buffer Memory!");
            }
            this.memory = pMemory.get(0);

            vkBindBufferMemory(context.device(), this.buffer, this.memory, 0);

            if ((properties & VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT) != 0) {
                PointerBuffer pData = stack.mallocPointer(1);
                vkMapMemory(context.device(), this.memory, 0, this.size, 0, pData);
                this.mappedAddress = pData.get(0);
            }
        }
    }

    public void upload(ByteBuffer data) {
        upload(data, 0L);
    }

    public void upload(ByteBuffer data, long offset) {
        if (this.mappedAddress != 0L) {
            MemoryUtil.memCopy(MemoryUtil.memAddress(data), this.mappedAddress + offset, data.remaining());
        }
    }

    @Override
    public void close() {
        if (this.mappedAddress != 0L) {
            vkUnmapMemory(context.device(), this.memory);
            this.mappedAddress = 0L;
        }
        if (this.buffer != VK_NULL_HANDLE) {
            vkDestroyBuffer(context.device(), this.buffer, null);
            this.buffer = VK_NULL_HANDLE;
        }
        if (this.memory != VK_NULL_HANDLE) {
            vkFreeMemory(context.device(), this.memory, null);
            this.memory = VK_NULL_HANDLE;
        }
    }

    public long handle() { return buffer; }
    public long size() { return size; }
}