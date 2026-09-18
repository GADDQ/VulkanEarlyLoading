package top.earthstudio.vulkanearlyloading.vulkan;

import com.mojang.logging.LogUtils;
import net.neoforged.fml.loading.progress.ProgressMeter;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;
import org.slf4j.Logger;

import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.nio.LongBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.KHRSwapchain.*;
import static org.lwjgl.vulkan.VK10.*;

public class VulkanRenderer {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final float DESIRED_ASPECT_RATIO = VulkanLoadingUi.VIRTUAL_WIDTH / VulkanLoadingUi.VIRTUAL_HEIGHT;

    private final VulkanContext context;
    private final VulkanSwapchain swapchain;
    private final Supplier<String> versionSupplier;
    private final Supplier<ProgressMeter> mainProgressSupplier;

    private long renderPass;
    private long descriptorPool;
    private long descriptorSetLayout;
    private long pipelineLayout;
    private long graphicsPipeline;
    private final List<Long> framebuffers = new ArrayList<>();

    private VkCommandBuffer commandBuffer;
    private long imageAvailableSemaphore;
    private long renderFinishedSemaphore;
    private long inFlightFence;

    private VulkanLoadingUi ui;
    private volatile boolean resized = false;

    public VulkanRenderer(long windowHandle, int width, int height,
                          Supplier<String> versionSupplier, Supplier<ProgressMeter> mainProgressSupplier) {
        this.context = new VulkanContext(windowHandle);
        this.swapchain = new VulkanSwapchain(this.context, width, height);
        this.versionSupplier = versionSupplier;
        this.mainProgressSupplier = mainProgressSupplier;
    }

    public void init() {
        createDescriptorResources();
        createRenderPass();
        createPipeline();
        createFramebuffers();
        createCommandBuffer();
        createSyncObjects();

        // 将两个 Supplier 同时传给 UI 装配器
        this.ui = new VulkanLoadingUi(this.context, this.descriptorPool, this.descriptorSetLayout,
                this.versionSupplier, this.mainProgressSupplier);
    }

    public void notifyResize() {
        this.resized = true;
    }

    private void createDescriptorResources() {
        try (MemoryStack stack = stackPush()) {
            VkDescriptorPoolSize.Buffer poolSize = VkDescriptorPoolSize.calloc(1, stack)
                    .type(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER)
                    .descriptorCount(32);

            VkDescriptorPoolCreateInfo poolInfo = VkDescriptorPoolCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO)
                    .pPoolSizes(poolSize)
                    .maxSets(32);

            LongBuffer pPool = stack.longs(VK_NULL_HANDLE);
            vkCreateDescriptorPool(this.context.device(), poolInfo, null, pPool);
            this.descriptorPool = pPool.get(0);

            VkDescriptorSetLayoutBinding.Buffer binding = VkDescriptorSetLayoutBinding.calloc(1, stack)
                    .binding(0)
                    .descriptorType(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER)
                    .descriptorCount(1)
                    .stageFlags(VK_SHADER_STAGE_FRAGMENT_BIT);

            VkDescriptorSetLayoutCreateInfo layoutInfo = VkDescriptorSetLayoutCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO)
                    .pBindings(binding);

            LongBuffer pLayout = stack.longs(VK_NULL_HANDLE);
            vkCreateDescriptorSetLayout(this.context.device(), layoutInfo, null, pLayout);
            this.descriptorSetLayout = pLayout.get(0);
        }
    }

    private void createRenderPass() {
        try (MemoryStack stack = stackPush()) {
            VkAttachmentDescription.Buffer colorAttachment = VkAttachmentDescription.calloc(1, stack)
                    .format(this.swapchain.imageFormat())
                    .samples(VK_SAMPLE_COUNT_1_BIT)
                    .loadOp(VK_ATTACHMENT_LOAD_OP_CLEAR)
                    .storeOp(VK_ATTACHMENT_STORE_OP_STORE)
                    .initialLayout(VK_IMAGE_LAYOUT_UNDEFINED)
                    .finalLayout(VK_IMAGE_LAYOUT_PRESENT_SRC_KHR);

            VkAttachmentReference.Buffer colorRef = VkAttachmentReference.calloc(1, stack)
                    .attachment(0)
                    .layout(VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL);

            VkSubpassDescription.Buffer subpass = VkSubpassDescription.calloc(1, stack)
                    .pipelineBindPoint(VK_PIPELINE_BIND_POINT_GRAPHICS)
                    .colorAttachmentCount(1)
                    .pColorAttachments(colorRef);

            VkRenderPassCreateInfo renderPassInfo = VkRenderPassCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_RENDER_PASS_CREATE_INFO)
                    .pAttachments(colorAttachment)
                    .pSubpasses(subpass);

            LongBuffer pPass = stack.longs(VK_NULL_HANDLE);
            vkCreateRenderPass(this.context.device(), renderPassInfo, null, pPass);
            this.renderPass = pPass.get(0);
        }
    }

    private void createPipeline() {
        try (MemoryStack stack = stackPush()) {
            long vertModule = loadShaderModule("/shaders/gui.vert.spv");
            long fragModule = loadShaderModule("/shaders/gui.frag.spv");

            VkPipelineShaderStageCreateInfo.Buffer stages = VkPipelineShaderStageCreateInfo.calloc(2, stack);
            stages.get(0).sType(VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO).stage(VK_SHADER_STAGE_VERTEX_BIT).module(vertModule).pName(stack.UTF8("main"));
            stages.get(1).sType(VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO).stage(VK_SHADER_STAGE_FRAGMENT_BIT).module(fragModule).pName(stack.UTF8("main"));

            VkVertexInputBindingDescription.Buffer bindingDesc = VkVertexInputBindingDescription.calloc(1, stack)
                    .binding(0)
                    .stride(VulkanBatch.VERTEX_SIZE)
                    .inputRate(VK_VERTEX_INPUT_RATE_VERTEX);

            VkVertexInputAttributeDescription.Buffer attrDesc = VkVertexInputAttributeDescription.calloc(3, stack);
            attrDesc.get(0).location(0).binding(0).format(VK_FORMAT_R32G32_SFLOAT).offset(0);
            attrDesc.get(1).location(1).binding(0).format(VK_FORMAT_R32G32_SFLOAT).offset(8);
            attrDesc.get(2).location(2).binding(0).format(VK_FORMAT_R32G32B32A32_SFLOAT).offset(16);

            VkPipelineVertexInputStateCreateInfo vertexInput = VkPipelineVertexInputStateCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_PIPELINE_VERTEX_INPUT_STATE_CREATE_INFO)
                    .pVertexBindingDescriptions(bindingDesc)
                    .pVertexAttributeDescriptions(attrDesc);

            VkPipelineInputAssemblyStateCreateInfo inputAssembly = VkPipelineInputAssemblyStateCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_PIPELINE_INPUT_ASSEMBLY_STATE_CREATE_INFO)
                    .topology(VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST);

            VkPipelineDynamicStateCreateInfo dynamicState = VkPipelineDynamicStateCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_PIPELINE_DYNAMIC_STATE_CREATE_INFO)
                    .pDynamicStates(stack.ints(VK_DYNAMIC_STATE_VIEWPORT, VK_DYNAMIC_STATE_SCISSOR));

            VkPipelineViewportStateCreateInfo viewportState = VkPipelineViewportStateCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_PIPELINE_VIEWPORT_STATE_CREATE_INFO)
                    .viewportCount(1).scissorCount(1);

            VkPipelineRasterizationStateCreateInfo rasterizer = VkPipelineRasterizationStateCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_PIPELINE_RASTERIZATION_STATE_CREATE_INFO)
                    .polygonMode(VK_POLYGON_MODE_FILL)
                    .cullMode(VK_CULL_MODE_NONE);

            VkPipelineMultisampleStateCreateInfo multisampling = VkPipelineMultisampleStateCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_PIPELINE_MULTISAMPLE_STATE_CREATE_INFO)
                    .rasterizationSamples(VK_SAMPLE_COUNT_1_BIT);

            VkPipelineColorBlendAttachmentState.Buffer blendAttachment = VkPipelineColorBlendAttachmentState.calloc(1, stack)
                    .blendEnable(true)
                    .srcColorBlendFactor(VK_BLEND_FACTOR_SRC_ALPHA)
                    .dstColorBlendFactor(VK_BLEND_FACTOR_ONE_MINUS_SRC_ALPHA)
                    .colorBlendOp(VK_BLEND_OP_ADD)
                    .srcAlphaBlendFactor(VK_BLEND_FACTOR_SRC_ALPHA)
                    .dstAlphaBlendFactor(VK_BLEND_FACTOR_ONE_MINUS_SRC_ALPHA)
                    .colorWriteMask(VK_COLOR_COMPONENT_R_BIT | VK_COLOR_COMPONENT_G_BIT | VK_COLOR_COMPONENT_B_BIT | VK_COLOR_COMPONENT_A_BIT);

            VkPipelineColorBlendStateCreateInfo colorBlending = VkPipelineColorBlendStateCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_PIPELINE_COLOR_BLEND_STATE_CREATE_INFO)
                    .pAttachments(blendAttachment);

            VkPushConstantRange.Buffer pushRange = VkPushConstantRange.calloc(1, stack)
                    .stageFlags(VK_SHADER_STAGE_VERTEX_BIT)
                    .offset(0)
                    .size(8);

            VkPipelineLayoutCreateInfo layoutInfo = VkPipelineLayoutCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO)
                    .pSetLayouts(stack.longs(this.descriptorSetLayout))
                    .pPushConstantRanges(pushRange);

            LongBuffer pLayout = stack.longs(VK_NULL_HANDLE);
            vkCreatePipelineLayout(this.context.device(), layoutInfo, null, pLayout);
            this.pipelineLayout = pLayout.get(0);

            VkGraphicsPipelineCreateInfo.Buffer pipelineInfo = VkGraphicsPipelineCreateInfo.calloc(1, stack)
                    .sType(VK_STRUCTURE_TYPE_GRAPHICS_PIPELINE_CREATE_INFO)
                    .pStages(stages).pVertexInputState(vertexInput).pInputAssemblyState(inputAssembly)
                    .pViewportState(viewportState).pRasterizationState(rasterizer).pMultisampleState(multisampling)
                    .pColorBlendState(colorBlending).pDynamicState(dynamicState).layout(this.pipelineLayout)
                    .renderPass(this.renderPass).subpass(0);

            LongBuffer pPipe = stack.longs(VK_NULL_HANDLE);
            vkCreateGraphicsPipelines(this.context.device(), VK_NULL_HANDLE, pipelineInfo, null, pPipe);
            this.graphicsPipeline = pPipe.get(0);

            vkDestroyShaderModule(this.context.device(), vertModule, null);
            vkDestroyShaderModule(this.context.device(), fragModule, null);
        }
    }

    private void createFramebuffers() {
        try (MemoryStack stack = stackPush()) {
            this.framebuffers.clear();
            for (long imageView : this.swapchain.imageViews()) {
                LongBuffer attachments = stack.longs(imageView);
                VkFramebufferCreateInfo info = VkFramebufferCreateInfo.calloc(stack)
                        .sType(VK_STRUCTURE_TYPE_FRAMEBUFFER_CREATE_INFO)
                        .renderPass(this.renderPass)
                        .pAttachments(attachments)
                        .width(this.swapchain.width())
                        .height(this.swapchain.height())
                        .layers(1);

                LongBuffer pFb = stack.longs(VK_NULL_HANDLE);
                vkCreateFramebuffer(this.context.device(), info, null, pFb);
                this.framebuffers.add(pFb.get(0));
            }
        }
    }

    private void createCommandBuffer() {
        try (MemoryStack stack = stackPush()) {
            VkCommandBufferAllocateInfo allocInfo = VkCommandBufferAllocateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO)
                    .commandPool(this.context.commandPool())
                    .level(VK_COMMAND_BUFFER_LEVEL_PRIMARY)
                    .commandBufferCount(1);
            PointerBuffer pCmd = stack.mallocPointer(1);
            vkAllocateCommandBuffers(this.context.device(), allocInfo, pCmd);
            this.commandBuffer = new VkCommandBuffer(pCmd.get(0), this.context.device());
        }
    }

    private void createSyncObjects() {
        try (MemoryStack stack = stackPush()) {
            VkSemaphoreCreateInfo semInfo = VkSemaphoreCreateInfo.calloc(stack).sType(VK_STRUCTURE_TYPE_SEMAPHORE_CREATE_INFO);
            VkFenceCreateInfo fenceInfo = VkFenceCreateInfo.calloc(stack).sType(VK_STRUCTURE_TYPE_FENCE_CREATE_INFO).flags(VK_FENCE_CREATE_SIGNALED_BIT);

            LongBuffer pSem1 = stack.longs(VK_NULL_HANDLE);
            LongBuffer pSem2 = stack.longs(VK_NULL_HANDLE);
            LongBuffer pFence = stack.longs(VK_NULL_HANDLE);

            vkCreateSemaphore(this.context.device(), semInfo, null, pSem1);
            vkCreateSemaphore(this.context.device(), semInfo, null, pSem2);
            vkCreateFence(this.context.device(), fenceInfo, null, pFence);

            this.imageAvailableSemaphore = pSem1.get(0);
            this.renderFinishedSemaphore = pSem2.get(0);
            this.inFlightFence = pFence.get(0);
        }
    }

    public void drawFrame() {
        try (MemoryStack stack = stackPush()) {
            vkWaitForFences(this.context.device(), this.inFlightFence, true, Long.MAX_VALUE);

            IntBuffer pImageIndex = stack.ints(0);
            int acquireResult = vkAcquireNextImageKHR(this.context.device(), this.swapchain.handle(), Long.MAX_VALUE, this.imageAvailableSemaphore, VK_NULL_HANDLE, pImageIndex);
            if (acquireResult == VK_ERROR_OUT_OF_DATE_KHR || this.resized) {
                handleResize();
                return;
            }

            vkResetFences(this.context.device(), this.inFlightFence);
            int imageIndex = pImageIndex.get(0);

            vkResetCommandBuffer(this.commandBuffer, 0);
            VkCommandBufferBeginInfo beginInfo = VkCommandBufferBeginInfo.calloc(stack).sType(VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO);
            vkBeginCommandBuffer(this.commandBuffer, beginInfo);

            VkClearValue.Buffer clearValues = VkClearValue.calloc(1, stack);
            clearValues.color().float32(0, 0.937f).float32(1, 0.196f).float32(2, 0.239f).float32(3, 1.0f);

            VkRenderPassBeginInfo renderPassInfo = VkRenderPassBeginInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_RENDER_PASS_BEGIN_INFO)
                    .renderPass(this.renderPass)
                    .framebuffer(this.framebuffers.get(imageIndex))
                    .pClearValues(clearValues);
            renderPassInfo.renderArea().extent().width(this.swapchain.width()).height(this.swapchain.height());

            vkCmdBeginRenderPass(this.commandBuffer, renderPassInfo, VK_SUBPASS_CONTENTS_INLINE);
            vkCmdBindPipeline(this.commandBuffer, VK_PIPELINE_BIND_POINT_GRAPHICS, this.graphicsPipeline);

            // 等比黑边换算
            float fbWidth = this.swapchain.width();
            float fbHeight = this.swapchain.height();
            float actualAspect = fbWidth / fbHeight;

            float vpWidth, vpHeight, offsetX, offsetY, scale;
            if (actualAspect > DESIRED_ASPECT_RATIO) {
                vpWidth = DESIRED_ASPECT_RATIO * fbHeight;
                vpHeight = fbHeight;
                offsetX = (fbWidth - vpWidth) / 2.0f;
                offsetY = 0.0f;
                scale = fbHeight / VulkanLoadingUi.VIRTUAL_HEIGHT;
            } else {
                vpWidth = fbWidth;
                vpHeight = fbWidth / DESIRED_ASPECT_RATIO;
                offsetX = 0.0f;
                offsetY = (fbHeight - vpHeight) / 2.0f;
                scale = fbWidth / VulkanLoadingUi.VIRTUAL_WIDTH;
            }

            VkViewport.Buffer viewport = VkViewport.calloc(1, stack).x(offsetX).y(offsetY).width(vpWidth).height(vpHeight).minDepth(0.0f).maxDepth(1.0f);
            vkCmdSetViewport(this.commandBuffer, 0, viewport);

            VkRect2D.Buffer defaultScissor = VkRect2D.calloc(1, stack);
            defaultScissor.offset().x((int) offsetX).y((int) offsetY);
            defaultScissor.extent().width((int) vpWidth).height((int) vpHeight);
            vkCmdSetScissor(this.commandBuffer, 0, defaultScissor);

            ByteBuffer pc = stack.malloc(8);
            pc.putFloat(VulkanLoadingUi.VIRTUAL_WIDTH).putFloat(VulkanLoadingUi.VIRTUAL_HEIGHT).flip();
            vkCmdPushConstants(this.commandBuffer, this.pipelineLayout, VK_SHADER_STAGE_VERTEX_BIT, 0, pc);

            int animFrame = (int) (System.currentTimeMillis() / 50L);

            // 委托给 UI 装配器全量渲染
            this.ui.render(this.commandBuffer, this.pipelineLayout, stack, offsetX, offsetY, scale, defaultScissor, animFrame);

            vkCmdEndRenderPass(this.commandBuffer);
            vkEndCommandBuffer(this.commandBuffer);

            VkSubmitInfo submitInfo = VkSubmitInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_SUBMIT_INFO)
                    .waitSemaphoreCount(1)
                    .pWaitSemaphores(stack.longs(this.imageAvailableSemaphore))
                    .pWaitDstStageMask(stack.ints(VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT))
                    .pCommandBuffers(stack.pointers(this.commandBuffer.address()))
                    .pSignalSemaphores(stack.longs(this.renderFinishedSemaphore));

            vkQueueSubmit(this.context.graphicsQueue(), submitInfo, this.inFlightFence);

            VkPresentInfoKHR presentInfo = VkPresentInfoKHR.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_PRESENT_INFO_KHR)
                    .pWaitSemaphores(stack.longs(this.renderFinishedSemaphore))
                    .swapchainCount(1)
                    .pSwapchains(stack.longs(this.swapchain.handle()))
                    .pImageIndices(pImageIndex);

            int presentResult = vkQueuePresentKHR(this.context.graphicsQueue(), presentInfo);
            if (presentResult == VK_ERROR_OUT_OF_DATE_KHR || presentResult == VK_SUBOPTIMAL_KHR || this.resized) {
                handleResize();
            }
        }
    }

    private void handleResize() {
        this.context.waitIdle();
        for (long fb : this.framebuffers) {
            vkDestroyFramebuffer(this.context.device(), fb, null);
        }
        this.framebuffers.clear();

        this.swapchain.recreate();
        createFramebuffers();
        this.resized = false;
    }

    private long loadShaderModule(String path) {
        try (InputStream in = getClass().getResourceAsStream(path)) {
            if (in == null) throw new RuntimeException("Shader not found: " + path);
            byte[] bytes = in.readAllBytes();
            try (MemoryStack stack = stackPush()) {
                ByteBuffer buf = stack.malloc(bytes.length);
                buf.put(bytes).flip();
                VkShaderModuleCreateInfo info = VkShaderModuleCreateInfo.calloc(stack)
                        .sType(VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO).pCode(buf);
                LongBuffer pMod = stack.longs(VK_NULL_HANDLE);
                vkCreateShaderModule(this.context.device(), info, null, pMod);
                return pMod.get(0);
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public void destroyPipelineAndContext() {
        LOGGER.info("Destroying Vulkan pipeline & context...");
        this.context.waitIdle();

        if (this.ui != null) this.ui.close();

        vkDestroySemaphore(this.context.device(), this.imageAvailableSemaphore, null);
        vkDestroySemaphore(this.context.device(), this.renderFinishedSemaphore, null);
        vkDestroyFence(this.context.device(), this.inFlightFence, null);

        for (long fb : this.framebuffers) {
            vkDestroyFramebuffer(this.context.device(), fb, null);
        }
        this.framebuffers.clear();

        vkDestroyPipeline(this.context.device(), this.graphicsPipeline, null);
        vkDestroyPipelineLayout(this.context.device(), this.pipelineLayout, null);
        vkDestroyDescriptorSetLayout(this.context.device(), this.descriptorSetLayout, null);
        vkDestroyDescriptorPool(this.context.device(), this.descriptorPool, null);
        vkDestroyRenderPass(this.context.device(), this.renderPass, null);

        this.swapchain.destroy();
        this.context.destroy();
        LOGGER.info("Vulkan resources cleanly torn down!");
    }
}