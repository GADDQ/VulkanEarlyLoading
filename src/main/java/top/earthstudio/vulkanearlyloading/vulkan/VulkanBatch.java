package top.earthstudio.vulkanearlyloading.vulkan;

import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkCommandBuffer;

import java.nio.ByteBuffer;

import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK10.*;

public class VulkanBatch implements AutoCloseable {
    public static final int VERTEX_SIZE = 32;
    private static final int MAX_VERTICES = 16384;

    private final VulkanContext context;
    private final VulkanBuffer vertexBuffer;
    private final ByteBuffer cpuBuffer;

    private long gpuByteOffset = 0;
    private int currentBatchVertexCount = 0;
    private VulkanTexture currentTexture = null;

    public VulkanBatch(VulkanContext context) {
        this.context = context;
        this.vertexBuffer = new VulkanBuffer(context, (long) MAX_VERTICES * VERTEX_SIZE,
                VK_BUFFER_USAGE_VERTEX_BUFFER_BIT,
                VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT);
        this.cpuBuffer = org.lwjgl.BufferUtils.createByteBuffer(MAX_VERTICES * VERTEX_SIZE);
    }

    public void beginFrame() {
        this.gpuByteOffset = 0;
        this.currentBatchVertexCount = 0;
        this.currentTexture = null;
        this.cpuBuffer.clear();
    }

    public void drawQuad(VkCommandBuffer cmd, long pipelineLayout, VulkanTexture texture,
                         float x0, float y0, float x1, float y1,
                         float u0, float v0, float u1, float v1,
                         float r, float g, float b, float a) {
        if (texture == null) return;

        if (texture != this.currentTexture && this.currentTexture != null && this.currentBatchVertexCount > 0) {
            flush(cmd, pipelineLayout);
        }
        this.currentTexture = texture;

        putVertex(x0, y0, u0, v0, r, g, b, a);
        putVertex(x1, y0, u1, v0, r, g, b, a);
        putVertex(x1, y1, u1, v1, r, g, b, a);

        putVertex(x1, y1, u1, v1, r, g, b, a);
        putVertex(x0, y1, u0, v1, r, g, b, a);
        putVertex(x0, y0, u0, v0, r, g, b, a);
    }

    private void putVertex(float x, float y, float u, float v, float r, float g, float b, float a) {
        this.cpuBuffer.putFloat(x).putFloat(y);
        this.cpuBuffer.putFloat(u).putFloat(v);
        this.cpuBuffer.putFloat(r).putFloat(g).putFloat(b).putFloat(a);
        this.currentBatchVertexCount++;
    }

    public void flush(VkCommandBuffer cmd, long pipelineLayout) {
        if (this.currentBatchVertexCount == 0 || this.currentTexture == null) return;

        int bytesToWrite = this.currentBatchVertexCount * VERTEX_SIZE;

        if (this.gpuByteOffset + bytesToWrite > this.vertexBuffer.size()) {
            this.gpuByteOffset = 0;
        }

        this.cpuBuffer.flip();
        this.vertexBuffer.upload(this.cpuBuffer, this.gpuByteOffset);

        if (this.currentTexture.descriptorSet() != VK_NULL_HANDLE) {
            try (MemoryStack stack = stackPush()) {
                vkCmdBindDescriptorSets(cmd, VK_PIPELINE_BIND_POINT_GRAPHICS, pipelineLayout, 0,
                        stack.longs(this.currentTexture.descriptorSet()), null);
            }
        }

        try (MemoryStack stack = stackPush()) {
            vkCmdBindVertexBuffers(cmd, 0, stack.longs(this.vertexBuffer.handle()), stack.longs(this.gpuByteOffset));
            vkCmdDraw(cmd, this.currentBatchVertexCount, 1, 0, 0);
        }

        this.gpuByteOffset += bytesToWrite;
        this.currentBatchVertexCount = 0;
        this.cpuBuffer.clear();
    }

    @Override
    public void close() {
        this.vertexBuffer.close();
    }
}