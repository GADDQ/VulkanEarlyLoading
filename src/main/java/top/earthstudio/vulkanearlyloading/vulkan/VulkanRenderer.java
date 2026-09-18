package top.earthstudio.vulkanearlyloading.vulkan;

import com.mojang.logging.LogUtils;
import com.sun.management.OperatingSystemMXBean;
import net.neoforged.fml.loading.progress.ProgressMeter;
import net.neoforged.fml.loading.progress.StartupNotificationManager;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;
import org.slf4j.Logger;

import java.io.InputStream;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.MemoryUsage;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.nio.LongBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Supplier;

import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.KHRSwapchain.*;
import static org.lwjgl.vulkan.VK10.*;

public class VulkanRenderer {
    private static final Logger LOGGER = LogUtils.getLogger();

    public static final float VIRTUAL_WIDTH = 854.0f;
    public static final float VIRTUAL_HEIGHT = 480.0f;
    private static final float DESIRED_ASPECT_RATIO = VIRTUAL_WIDTH / VIRTUAL_HEIGHT;

    private final VulkanContext context;
    private final VulkanSwapchain swapchain;
    private final Supplier<String> versionSupplier;

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

    private VulkanBatch batch;
    private VulkanFont font;
    private VulkanTexture whiteTexture;
    private VulkanTexture mojangLogoTexture;
    private VulkanTexture progressBarBgTexture;
    private VulkanTexture progressBarFgTexture;
    private VulkanTexture foxTexture;

    private float vpOffsetX = 0.0f;
    private float vpOffsetY = 0.0f;
    private float vpScale = 1.0f;
    private VkRect2D.Buffer defaultScissor;

    private volatile boolean resized = false;

    private final OperatingSystemMXBean osBean;
    private final MemoryMXBean memoryBean;
    private final Supplier<ProgressMeter> mainProgressSupplier;

    public VulkanRenderer(long windowHandle, int width, int height, Supplier<String> versionSupplier, Supplier<ProgressMeter> mainProgressSupplier) {
        this.context = new VulkanContext(windowHandle);
        this.swapchain = new VulkanSwapchain(this.context, width, height);
        this.versionSupplier = versionSupplier;
        this.mainProgressSupplier = mainProgressSupplier;
        this.osBean = ManagementFactory.getPlatformMXBean(OperatingSystemMXBean.class);
        this.memoryBean = ManagementFactory.getMemoryMXBean();
    }

    public void init() {
        createDescriptorResources();
        createRenderPass();
        createPipeline();
        createFramebuffers();
        createCommandBuffer();
        createSyncObjects();

        this.batch = new VulkanBatch(this.context);

        this.whiteTexture = VulkanTexture.createWhiteTexture(this.context, this.descriptorPool, this.descriptorSetLayout);
        this.mojangLogoTexture = VulkanResourceLoader.loadClasspathTexture(this.context,
                "assets/minecraft/textures/gui/title/mojangstudios.png", this.descriptorPool, this.descriptorSetLayout);
        this.progressBarBgTexture = VulkanResourceLoader.loadClasspathTexture(this.context,
                "net/neoforged/fml/earlydisplay/theme/progress_bar_bg.png", this.descriptorPool, this.descriptorSetLayout);
        this.progressBarFgTexture = VulkanResourceLoader.loadClasspathTexture(this.context,
                "net/neoforged/fml/earlydisplay/theme/progress_bar_fg.png", this.descriptorPool, this.descriptorSetLayout);
        this.foxTexture = VulkanResourceLoader.loadClasspathTexture(this.context,
                "net/neoforged/fml/earlydisplay/theme/fox_running.png", this.descriptorPool, this.descriptorSetLayout);

        this.font = new VulkanFont(this.context, this.descriptorPool, this.descriptorSetLayout);
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
            clearValues.color().float32(0, 0.0f).float32(1, 0.0f).float32(2, 0.0f).float32(3, 1.0f);

            VkRenderPassBeginInfo renderPassInfo = VkRenderPassBeginInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_RENDER_PASS_BEGIN_INFO)
                    .renderPass(this.renderPass)
                    .framebuffer(this.framebuffers.get(imageIndex))
                    .pClearValues(clearValues);
            renderPassInfo.renderArea().extent().width(this.swapchain.width()).height(this.swapchain.height());

            vkCmdBeginRenderPass(this.commandBuffer, renderPassInfo, VK_SUBPASS_CONTENTS_INLINE);
            vkCmdBindPipeline(this.commandBuffer, VK_PIPELINE_BIND_POINT_GRAPHICS, this.graphicsPipeline);

            float fbWidth = this.swapchain.width();
            float fbHeight = this.swapchain.height();
            float actualAspect = fbWidth / fbHeight;

            float vpWidth, vpHeight;
            if (actualAspect > DESIRED_ASPECT_RATIO) {
                vpWidth = DESIRED_ASPECT_RATIO * fbHeight;
                vpHeight = fbHeight;
                this.vpOffsetX = (fbWidth - vpWidth) / 2.0f;
                this.vpOffsetY = 0.0f;
                this.vpScale = fbHeight / 480.0F;
            } else {
                vpWidth = fbWidth;
                vpHeight = fbWidth / DESIRED_ASPECT_RATIO;
                this.vpOffsetX = 0.0f;
                this.vpOffsetY = (fbHeight - vpHeight) / 2.0f;
                this.vpScale = fbWidth / 854.0F;
            }

            VkViewport.Buffer viewport = VkViewport.calloc(1, stack).x(this.vpOffsetX).y(this.vpOffsetY).width(vpWidth).height(vpHeight).minDepth(0.0f).maxDepth(1.0f);
            vkCmdSetViewport(this.commandBuffer, 0, viewport);

            this.defaultScissor = VkRect2D.calloc(1, stack);
            this.defaultScissor.offset().x((int) this.vpOffsetX).y((int) this.vpOffsetY);
            this.defaultScissor.extent().width((int) vpWidth).height((int) vpHeight);
            vkCmdSetScissor(this.commandBuffer, 0, this.defaultScissor);

            ByteBuffer pc = stack.malloc(8);
            pc.putFloat(VIRTUAL_WIDTH).putFloat(VIRTUAL_HEIGHT).flip();
            vkCmdPushConstants(this.commandBuffer, this.pipelineLayout, VK_SHADER_STAGE_VERTEX_BIT, 0, pc);

            int animFrame = (int) (System.currentTimeMillis() / 50L);

            // =========================================================================
            // 2. 每帧开始重置批处理器
            // =========================================================================
            this.batch.beginFrame();

            // A. 背景红底
            drawSolidRect(0, 0, VIRTUAL_WIDTH, VIRTUAL_HEIGHT, 0.937f, 0.196f, 0.239f, 1.0f);

            // B. 顶部状态条
            renderPerformanceElement(stack);

            // C. Mojang Logo
            if (this.mojangLogoTexture != null) {
                float logoX = (VIRTUAL_WIDTH - 512.0f) / 2.0f;
                float logoY = 96.0f;
                this.batch.drawQuad(this.commandBuffer, this.pipelineLayout, this.mojangLogoTexture,
                        logoX, logoY, logoX + 256.0f, logoY + 128.0f,
                        0.0f, 0.0f, 1.0f, 0.5f, 1.0f, 1.0f, 1.0f, 1.0f);
                this.batch.drawQuad(this.commandBuffer, this.pipelineLayout, this.mojangLogoTexture,
                        logoX + 256.0f, logoY, logoX + 512.0f, logoY + 128.0f,
                        0.0f, 0.5f, 1.0f, 1.0f, 1.0f, 1.0f, 1.0f, 1.0f);
            }

            // D. 双进度条
            renderProgressBarsElement(stack, animFrame);

            // E. 左下角日志
            renderStartupLogElement();

            // F. 右下角小狐狸
            if (this.foxTexture != null) {
                float foxW = 151.0f;
                float foxH = 128.0f;
                float foxX = VIRTUAL_WIDTH - foxW - 10.0f;
                float foxY = VIRTUAL_HEIGHT - foxH - 16.0f;

                int frame = animFrame % 28;
                float v0 = (float) frame / 28.0f;
                float v1 = (float) (frame + 1) / 28.0f;

                this.batch.drawQuad(this.commandBuffer, this.pipelineLayout, this.foxTexture,
                        foxX, foxY, foxX + foxW, foxY + foxH,
                        0.0f, v0, 1.0f, v1,
                        1.0f, 1.0f, 1.0f, 1.0f);
            }

            // G. 右下角版本号
            renderVersionElement();

            // 冲刷最终批次
            this.batch.flush(this.commandBuffer, this.pipelineLayout);

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

    private void renderPerformanceElement(MemoryStack stack) {
        float barX = 220.0f;
        float barY = 10.0f;
        float barW = VIRTUAL_WIDTH - 440.0f;
        float barH = 20.0f;

        try {
            MemoryUsage heap = this.memoryBean.getHeapMemoryUsage();
            float memoryRatio = Math.max(0.0f, Math.min(1.0f, (float) heap.getUsed() / (float) heap.getMax()));

            renderScissoredProgressBar(stack, barX, barY, barW, barH, memoryRatio, memoryRatio, 0.498f, 0.0f);

            if (this.font != null) {
                double cpuLoad = this.osBean.getProcessCpuLoad();
                String cpuText = (cpuLoad < 0) ? "*CPU: 0%" : String.format(Locale.ROOT, "CPU: %d%%", Math.round(cpuLoad * 100.0));
                String text = String.format(Locale.ROOT, "Memory: %d/%d MB (%d%%)  %s",
                        heap.getUsed() >> 20L, heap.getMax() >> 20L, Math.round(memoryRatio * 100.0f), cpuText);

                int textW = this.font.stringWidth(text);
                float textX = (VIRTUAL_WIDTH - textW) / 2.0f;
                float textY = barY + barH;
                this.font.drawText(this.batch, this.commandBuffer, this.pipelineLayout, text, textX, textY, 1.0f, 1.0f, 1.0f, 1.0f);
            }
        } catch (Throwable ignored) {}
    }

    /**
     * 1:1 纯正对齐 ProgressBarsElement：主条永远在上方，副条动态跟随
     */
    private void renderProgressBarsElement(MemoryStack stack, int animFrame) {
        float barX = 220.0f;
        float barW = VIRTUAL_WIDTH - 440.0f; // 414px
        float barH = 20.0f;
        float curY = 250.0f;

        ProgressMeter mainMeter = this.mainProgressSupplier != null ? this.mainProgressSupplier.get() : null;
        List<ProgressMeter> allMeters = StartupNotificationManager.getCurrentProgress();

        // 寻找当前正在活跃的副任务条（不等于 mainMeter，且未完成、有标签）
        ProgressMeter subMeter = null;
        for (ProgressMeter m : allMeters) {
            if (m != mainMeter) {
                String subLabel = m.label().getText();
                // 只有当副条有实质内容且进度未超过 1.0 时才展示
                if (subLabel != null && !subLabel.isEmpty() && m.progress() < 1.0f) {
                    subMeter = m;
                    break;
                }
            }
        }

        // =========================================================================
        // 1. 【绘制第一根：主进度条】（固定在最上方，绝不颠倒！）
        // =========================================================================
        if (mainMeter != null) {
            String mainText = mainMeter.label().getText();
            if (mainText != null && !mainText.isEmpty() && this.font != null) {
                this.font.drawText(this.batch, this.commandBuffer, this.pipelineLayout, mainText, barX, curY - 2.0f, 1.0f, 1.0f, 1.0f, 1.0f);
                curY += this.font.lineSpacing() + 4.0f; // labelGap = 4
            }

            // 主条底槽
            if (this.progressBarBgTexture != null) {
                drawNineSlice(this.progressBarBgTexture, barX, curY, barW, barH, 2, 2, 2, 2, 1.0f, 1.0f, 1.0f, 1.0f);
            }

            // 主条填充内容
            if (this.progressBarFgTexture != null) {
                if (mainMeter.steps() == 0) {
                    // 主条在初始阶段的未定滑动动画
                    int centerPercent = animFrame % 120 - 10;
                    float start = clamp((centerPercent - 10) / 100.0F, 0.0F, 1.0F);
                    float end = clamp((centerPercent + 10) / 100.0F, 0.0F, 1.0F);
                    float indX = barX + barW * start;
                    float indW = barW * (end - start);
                    if (indW > 8.0f) {
                        drawNineSlice(this.progressBarFgTexture, indX, curY, indW, barH, 4, 4, 4, 4, 1.0f, 1.0f, 1.0f, 1.0f);
                    }
                } else {
                    renderScissoredProgressBar(stack, barX, curY, barW, barH, mainMeter.progress(), 1.0f, 1.0f, 1.0f);
                }
            }

            curY += barH + 5.0f; // barGap = 5
        }

        // =========================================================================
        // 2. 【绘制第二根：副进度条】（仅在真正有有效子任务时才绘制，否则自动隐藏！）
        // =========================================================================
        if (subMeter != null) {
            String subText = subMeter.label().getText();
            if (subText != null && !subText.isEmpty() && this.font != null) {
                this.font.drawText(this.batch, this.commandBuffer, this.pipelineLayout, subText, barX, curY - 2.0f, 1.0f, 1.0f, 1.0f, 1.0f);
                curY += this.font.lineSpacing() + 4.0f;
            }

            if (this.progressBarBgTexture != null) {
                drawNineSlice(this.progressBarBgTexture, barX, curY, barW, barH, 2, 2, 2, 2, 1.0f, 1.0f, 1.0f, 1.0f);
            }

            if (this.progressBarFgTexture != null) {
                if (subMeter.steps() == 0) {
                    int centerPercent = animFrame % 120 - 10;
                    float start = clamp((centerPercent - 10) / 100.0F, 0.0F, 1.0F);
                    float end = clamp((centerPercent + 10) / 100.0F, 0.0F, 1.0F);
                    float indX = barX + barW * start;
                    float indW = barW * (end - start);
                    if (indW > 8.0f) {
                        drawNineSlice(this.progressBarFgTexture, indX, curY, indW, barH, 4, 4, 4, 4, 1.0f, 1.0f, 1.0f, 1.0f);
                    }
                } else {
                    renderScissoredProgressBar(stack, barX, curY, barW, barH, subMeter.progress(), 1.0f, 1.0f, 1.0f);
                }
            }
        }
    }

    private void renderScissoredProgressBar(MemoryStack stack, float x, float y, float w, float h, float fillFactor, float r, float g, float b) {
        fillFactor = clamp(fillFactor, 0.0f, 1.0f);

        if (this.progressBarBgTexture != null) {
            drawNineSlice(this.progressBarBgTexture, x, y, w, h, 2, 2, 2, 2, 1.0f, 1.0f, 1.0f, 1.0f);
        }

        if (fillFactor > 0.001f && this.progressBarFgTexture != null) {
            this.batch.flush(this.commandBuffer, this.pipelineLayout);

            int sx = (int) (this.vpOffsetX + x * this.vpScale);
            int sy = (int) (this.vpOffsetY + y * this.vpScale);
            int sw = Math.max(1, (int) (w * fillFactor * this.vpScale));
            int sh = Math.max(1, (int) (h * this.vpScale));

            VkRect2D.Buffer scissor = VkRect2D.calloc(1, stack);
            scissor.offset().x(sx).y(sy);
            scissor.extent().width(sw).height(sh);
            vkCmdSetScissor(this.commandBuffer, 0, scissor);

            drawNineSlice(this.progressBarFgTexture, x, y, w, h, 4, 4, 4, 4, r, g, b, 1.0f);
            this.batch.flush(this.commandBuffer, this.pipelineLayout);

            vkCmdSetScissor(this.commandBuffer, 0, this.defaultScissor);
        }
    }

    private void renderStartupLogElement() {
        if (this.font == null) return;
        List<StartupNotificationManager.AgeMessage> messages = StartupNotificationManager.getMessages();
        if (messages == null || messages.isEmpty()) return;

        record LogEntry(String text, float alpha) {}
        List<LogEntry> visible = new ArrayList<>();

        for (int i = messages.size() - 1; i >= 0; i--) {
            StartupNotificationManager.AgeMessage pair = messages.get(i);
            float fade = clamp((4000.0F - pair.age() - (i - 4) * 1000.0F) / 5000.0F, 0.0F, 1.0F);
            if (fade >= 0.01F) {
                visible.add(new LogEntry(pair.message().getText(), fade));
            }
        }

        if (visible.isEmpty()) return;

        int count = Math.min(visible.size(), 5);
        float totalH = count * this.font.lineSpacing();
        float startY = (VIRTUAL_HEIGHT - 10.0f) - totalH;
        float startX = 10.0f;

        for (int idx = 0; idx < count; idx++) {
            LogEntry entry = visible.get(idx);
            float y = startY + idx * this.font.lineSpacing();
            this.font.drawText(this.batch, this.commandBuffer, this.pipelineLayout, entry.text, startX, y, 1.0f, 1.0f, 1.0f, entry.alpha);
        }
    }

    private void renderVersionElement() {
        if (this.font == null || this.versionSupplier == null) return;
        String vStr = this.versionSupplier.get();
        if (vStr == null || vStr.isEmpty()) return;

        int textW = this.font.stringWidth(vStr);
        float textX = VIRTUAL_WIDTH - textW - 10.0f;
        float textY = VIRTUAL_HEIGHT - this.font.lineSpacing() - 10.0f;
        this.font.drawText(this.batch, this.commandBuffer, this.pipelineLayout, vStr, textX, textY, 1.0f, 1.0f, 1.0f, 1.0f);
    }

    private void drawSolidRect(float x, float y, float w, float h, float r, float g, float b, float a) {
        this.batch.drawQuad(this.commandBuffer, this.pipelineLayout, this.whiteTexture,
                x, y, x + w, y + h,
                0.0f, 0.0f, 1.0f, 1.0f,
                r, g, b, a);
    }

    private void drawNineSlice(VulkanTexture tex, float x, float y, float w, float h,
                               int left, int top, int right, int bottom,
                               float r, float g, float b, float a) {
        if (w <= 0.0f || h <= 0.0f || tex == null) return;

        float texW = tex.width();
        float texH = tex.height();

        float l = Math.min(left, w * 0.5f);
        float rt = Math.min(right, w * 0.5f);
        float t = Math.min(top, h * 0.5f);
        float btm = Math.min(bottom, h * 0.5f);

        float[] xs = { x, x + l, x + w - rt, x + w };
        float[] ys = { y, y + t, y + h - btm, y + h };
        float[] us = { 0.0f, l / texW, (texW - rt) / texW, 1.0f };
        float[] vs = { 0.0f, t / texH, (texH - btm) / texH, 1.0f };

        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 3; col++) {
                if (xs[col] < xs[col + 1] && ys[row] < ys[row + 1]) {
                    this.batch.drawQuad(this.commandBuffer, this.pipelineLayout, tex,
                            xs[col], ys[row], xs[col + 1], ys[row + 1],
                            us[col], vs[row], us[col + 1], vs[row + 1],
                            r, g, b, a);
                }
            }
        }
    }

    private static float clamp(float val, float min, float max) {
        return Math.max(min, Math.min(max, val));
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

        if (this.batch != null) this.batch.close();
        if (this.font != null) this.font.close();
        if (this.whiteTexture != null) this.whiteTexture.close();
        if (this.mojangLogoTexture != null) this.mojangLogoTexture.close();
        if (this.progressBarBgTexture != null) this.progressBarBgTexture.close();
        if (this.progressBarFgTexture != null) this.progressBarFgTexture.close();
        if (this.foxTexture != null) this.foxTexture.close();

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