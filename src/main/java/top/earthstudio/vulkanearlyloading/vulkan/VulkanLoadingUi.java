package top.earthstudio.vulkanearlyloading.vulkan;

import com.sun.management.OperatingSystemMXBean;
import net.neoforged.fml.loading.progress.ProgressMeter;
import net.neoforged.fml.loading.progress.StartupNotificationManager;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkRect2D;

import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.MemoryUsage;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Supplier;

import static org.lwjgl.vulkan.VK10.vkCmdSetScissor;

public class VulkanLoadingUi implements AutoCloseable {
    public static final float VIRTUAL_WIDTH = 854.0f;
    public static final float VIRTUAL_HEIGHT = 480.0f;

    private final VulkanContext context;
    private final VulkanBatch batch;
    private final VulkanFont font;
    private final VulkanTexture whiteTexture;
    private final VulkanTexture mojangLogoTexture;
    private final VulkanTexture progressBarBgTexture;
    private final VulkanTexture progressBarFgTexture;
    private final VulkanTexture foxTexture;

    private final OperatingSystemMXBean osBean;
    private final MemoryMXBean memoryBean;
    private final Supplier<String> versionSupplier;
    private final Supplier<ProgressMeter> mainProgressSupplier;

    public VulkanLoadingUi(VulkanContext context, long descriptorPool, long descriptorSetLayout,
                           Supplier<String> versionSupplier, Supplier<ProgressMeter> mainProgressSupplier) {
        this.context = context;
        this.versionSupplier = versionSupplier;
        this.mainProgressSupplier = mainProgressSupplier;
        this.batch = new VulkanBatch(context);

        this.whiteTexture = VulkanTexture.createWhiteTexture(context, descriptorPool, descriptorSetLayout);
        this.mojangLogoTexture = VulkanResourceLoader.loadClasspathTexture(context,
                "assets/minecraft/textures/gui/title/mojangstudios.png", descriptorPool, descriptorSetLayout);
        this.progressBarBgTexture = VulkanResourceLoader.loadClasspathTexture(context,
                "net/neoforged/fml/earlydisplay/theme/progress_bar_bg.png", descriptorPool, descriptorSetLayout);
        this.progressBarFgTexture = VulkanResourceLoader.loadClasspathTexture(context,
                "net/neoforged/fml/earlydisplay/theme/progress_bar_fg.png", descriptorPool, descriptorSetLayout);
        this.foxTexture = VulkanResourceLoader.loadClasspathTexture(context,
                "net/neoforged/fml/earlydisplay/theme/fox_running.png", descriptorPool, descriptorSetLayout);

        this.font = new VulkanFont(context, descriptorPool, descriptorSetLayout);

        this.osBean = ManagementFactory.getPlatformMXBean(OperatingSystemMXBean.class);
        this.memoryBean = ManagementFactory.getMemoryMXBean();
    }

    public void render(VkCommandBuffer cmd, long pipelineLayout, MemoryStack stack,
                       float vpOffsetX, float vpOffsetY, float vpScale,
                       VkRect2D.Buffer defaultScissor, int animFrame) {

        this.batch.beginFrame();

        // 1. 背景红底 (#ef323d)
        drawSolidRect(cmd, pipelineLayout, 0, 0, VIRTUAL_WIDTH, VIRTUAL_HEIGHT, 0.937f, 0.196f, 0.239f, 1.0f);

        // 2. 顶部内存条
        renderPerformanceElement(cmd, pipelineLayout, stack, vpOffsetX, vpOffsetY, vpScale, defaultScissor);

        // 3. 居中 Mojang Logo (top: 96, 512x128)
        if (this.mojangLogoTexture != null) {
            float logoX = (VIRTUAL_WIDTH - 512.0f) / 2.0f;
            float logoY = 96.0f;
            this.batch.drawQuad(cmd, pipelineLayout, this.mojangLogoTexture,
                    logoX, logoY, logoX + 256.0f, logoY + 128.0f,
                    0.0f, 0.0f, 1.0f, 0.5f, 1.0f, 1.0f, 1.0f, 1.0f);
            this.batch.drawQuad(cmd, pipelineLayout, this.mojangLogoTexture,
                    logoX + 256.0f, logoY, logoX + 512.0f, logoY + 128.0f,
                    0.0f, 0.5f, 1.0f, 1.0f, 1.0f, 1.0f, 1.0f, 1.0f);
        }

        // 4. 双进度条 (主条在上，副条在下且动态显隐)
        renderProgressBarsElement(cmd, pipelineLayout, stack, vpOffsetX, vpOffsetY, vpScale, defaultScissor, animFrame);

        // 5. 左下角启动日志
        renderStartupLogElement(cmd, pipelineLayout);

        // 6. 右下角奔跑小狐狸 (50ms 一帧，28 帧循环)
        if (this.foxTexture != null) {
            float foxW = 151.0f;
            float foxH = 128.0f;
            float foxX = VIRTUAL_WIDTH - foxW - 10.0f;
            float foxY = VIRTUAL_HEIGHT - foxH - 28.0f;

            int frame = animFrame % 28;
            float v0 = (float) frame / 28.0f;
            float v1 = (float) (frame + 1) / 28.0f;

            this.batch.drawQuad(cmd, pipelineLayout, this.foxTexture,
                    foxX, foxY, foxX + foxW, foxY + foxH,
                    0.0f, v0, 1.0f, v1,
                    1.0f, 1.0f, 1.0f, 1.0f);
        }

        // 7. 右下角版本号
        renderVersionElement(cmd, pipelineLayout);

        this.batch.flush(cmd, pipelineLayout);
    }

    private void renderPerformanceElement(VkCommandBuffer cmd, long pipelineLayout, MemoryStack stack,
                                          float vpOffsetX, float vpOffsetY, float vpScale, VkRect2D.Buffer defaultScissor) {
        float barX = 220.0f;
        float barY = 10.0f;
        float barW = VIRTUAL_WIDTH - 440.0f;
        float barH = 20.0f;

        try {
            MemoryUsage heap = this.memoryBean.getHeapMemoryUsage();
            float memoryRatio = Math.max(0.0f, Math.min(1.0f, (float) heap.getUsed() / (float) heap.getMax()));

            renderScissoredProgressBar(cmd, pipelineLayout, stack, vpOffsetX, vpOffsetY, vpScale, defaultScissor,
                    barX, barY, barW, barH, memoryRatio, memoryRatio, 0.498f, 0.0f);

            if (this.font != null) {
                double cpuLoad = this.osBean.getProcessCpuLoad();
                String cpuText = (cpuLoad < 0) ? "*CPU: 0%" : String.format(Locale.ROOT, "CPU: %d%%", Math.round(cpuLoad * 100.0));
                String text = String.format(Locale.ROOT, "Memory: %d/%d MB (%d%%)  %s",
                        heap.getUsed() >> 20L, heap.getMax() >> 20L, Math.round(memoryRatio * 100.0f), cpuText);

                int textW = this.font.stringWidth(text);
                float textX = (VIRTUAL_WIDTH - textW) / 2.0f;
                float textY = barY + barH;
                this.font.drawText(this.batch, cmd, pipelineLayout, text, textX, textY, 1.0f, 1.0f, 1.0f, 1.0f);
            }
        } catch (Throwable ignored) {}
    }

    /**
     * 主条锁死在上方第一格，副条仅在有活跃子任务时显示在第二格
     */
    private void renderProgressBarsElement(VkCommandBuffer cmd, long pipelineLayout, MemoryStack stack,
                                           float vpOffsetX, float vpOffsetY, float vpScale, VkRect2D.Buffer defaultScissor, int animFrame) {
        float barX = 220.0f;
        float barW = VIRTUAL_WIDTH - 440.0f; // 414px
        float barH = 20.0f;
        float curY = 250.0f;

        ProgressMeter mainMeter = this.mainProgressSupplier != null ? this.mainProgressSupplier.get() : null;
        List<ProgressMeter> allMeters = StartupNotificationManager.getCurrentProgress();

        // 寻找当前活跃的子任务（不等于 mainMeter，且未完成、有标签）
        ProgressMeter subMeter = null;
        if (allMeters != null) {
            for (ProgressMeter m : allMeters) {
                if (m != mainMeter) {
                    String subLabel = m.label().getText();
                    if (subLabel != null && !subLabel.isEmpty() && m.progress() < 1.0f) {
                        subMeter = m;
                        break;
                    }
                }
            }
        }

        // 1. 绘制主进度条 (永远在最上方！)
        if (mainMeter != null) {
            String mainText = mainMeter.label().getText();
            if (mainText != null && !mainText.isEmpty() && this.font != null) {
                this.font.drawText(this.batch, cmd, pipelineLayout, mainText, barX, curY - 2.0f, 1.0f, 1.0f, 1.0f, 1.0f);
                curY += (this.font.lineSpacing() + 4.0f); // labelGap = 4
            }

            if (this.progressBarBgTexture != null) {
                drawNineSlice(cmd, pipelineLayout, this.progressBarBgTexture, barX, curY, barW, barH, 2, 2, 2, 2, 1.0f, 1.0f, 1.0f, 1.0f);
            }

            if (this.progressBarFgTexture != null) {
                if (mainMeter.steps() == 0) {
                    int centerPercent = animFrame % 120 - 10;
                    float start = clamp((centerPercent - 10) / 100.0F, 0.0F, 1.0F);
                    float end = clamp((centerPercent + 10) / 100.0F, 0.0F, 1.0F);
                    float indX = barX + barW * start;
                    float indW = barW * (end - start);
                    if (indW > 8.0f) {
                        drawNineSlice(cmd, pipelineLayout, this.progressBarFgTexture, indX, curY, indW, barH, 4, 4, 4, 4, 1.0f, 1.0f, 1.0f, 1.0f);
                    }
                } else {
                    renderScissoredProgressBar(cmd, pipelineLayout, stack, vpOffsetX, vpOffsetY, vpScale, defaultScissor,
                            barX, curY, barW, barH, mainMeter.progress(), 1.0f, 1.0f, 1.0f);
                }
            }

            curY += barH + 5.0f; // barGap = 5
        }

        // 2. 绘制副进度条 (仅当有活跃子任务时才在下方绘制，否则完全隐藏！)
        if (subMeter != null) {
            String subText = subMeter.label().getText();
            if (subText != null && !subText.isEmpty() && this.font != null) {
                this.font.drawText(this.batch, cmd, pipelineLayout, subText, barX, curY - 2.0f, 1.0f, 1.0f, 1.0f, 1.0f);
                curY += (this.font.lineSpacing() + 4.0f);
            }

            if (this.progressBarBgTexture != null) {
                drawNineSlice(cmd, pipelineLayout, this.progressBarBgTexture, barX, curY, barW, barH, 2, 2, 2, 2, 1.0f, 1.0f, 1.0f, 1.0f);
            }

            if (this.progressBarFgTexture != null) {
                if (subMeter.steps() == 0) {
                    int centerPercent = animFrame % 120 - 10;
                    float start = clamp((centerPercent - 10) / 100.0F, 0.0F, 1.0F);
                    float end = clamp((centerPercent + 10) / 100.0F, 0.0F, 1.0F);
                    float indX = barX + barW * start;
                    float indW = barW * (end - start);
                    if (indW > 8.0f) {
                        drawNineSlice(cmd, pipelineLayout, this.progressBarFgTexture, indX, curY, indW, barH, 4, 4, 4, 4, 1.0f, 1.0f, 1.0f, 1.0f);
                    }
                } else {
                    renderScissoredProgressBar(cmd, pipelineLayout, stack, vpOffsetX, vpOffsetY, vpScale, defaultScissor,
                            barX, curY, barW, barH, subMeter.progress(), 1.0f, 1.0f, 1.0f);
                }
            }
        }
    }

    private void renderScissoredProgressBar(VkCommandBuffer cmd, long pipelineLayout, MemoryStack stack,
                                            float vpOffsetX, float vpOffsetY, float vpScale, VkRect2D.Buffer defaultScissor,
                                            float x, float y, float w, float h, float fillFactor, float r, float g, float b) {
        fillFactor = clamp(fillFactor, 0.0f, 1.0f);

        if (this.progressBarBgTexture != null) {
            drawNineSlice(cmd, pipelineLayout, this.progressBarBgTexture, x, y, w, h, 2, 2, 2, 2, 1.0f, 1.0f, 1.0f, 1.0f);
        }

        if (fillFactor > 0.001f && this.progressBarFgTexture != null) {
            this.batch.flush(cmd, pipelineLayout);

            int sx = (int) (vpOffsetX + x * vpScale);
            int sy = (int) (vpOffsetY + y * vpScale);
            int sw = Math.max(1, (int) (w * fillFactor * vpScale));
            int sh = Math.max(1, (int) (h * vpScale));

            VkRect2D.Buffer scissor = VkRect2D.calloc(1, stack);
            scissor.offset().x(sx).y(sy);
            scissor.extent().width(sw).height(sh);
            vkCmdSetScissor(cmd, 0, scissor);

            drawNineSlice(cmd, pipelineLayout, this.progressBarFgTexture, x, y, w, h, 4, 4, 4, 4, r, g, b, 1.0f);
            this.batch.flush(cmd, pipelineLayout);

            vkCmdSetScissor(cmd, 0, defaultScissor);
        }
    }

    private void renderStartupLogElement(VkCommandBuffer cmd, long pipelineLayout) {
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
            this.font.drawText(this.batch, cmd, pipelineLayout, entry.text, startX, y, 1.0f, 1.0f, 1.0f, entry.alpha);
        }
    }

    private void renderVersionElement(VkCommandBuffer cmd, long pipelineLayout) {
        if (this.font == null || this.versionSupplier == null) return;
        String vStr = this.versionSupplier.get();
        if (vStr == null || vStr.isEmpty()) return;

        int textW = this.font.stringWidth(vStr);
        float textX = VIRTUAL_WIDTH - textW - 10.0f;
        float textY = VIRTUAL_HEIGHT - this.font.lineSpacing() - 10.0f;
        this.font.drawText(this.batch, cmd, pipelineLayout, vStr, textX, textY, 1.0f, 1.0f, 1.0f, 1.0f);
    }

    private void drawSolidRect(VkCommandBuffer cmd, long pipelineLayout, float x, float y, float w, float h, float r, float g, float b, float a) {
        this.batch.drawQuad(cmd, pipelineLayout, this.whiteTexture, x, y, x + w, y + h, 0.0f, 0.0f, 1.0f, 1.0f, r, g, b, a);
    }

    private void drawNineSlice(VkCommandBuffer cmd, long pipelineLayout, VulkanTexture tex, float x, float y, float w, float h,
                               int left, int top, int right, int bottom, float r, float g, float b, float a) {
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
                    this.batch.drawQuad(cmd, pipelineLayout, tex,
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

    @Override
    public void close() {
        if (this.batch != null) this.batch.close();
        if (this.font != null) this.font.close();
        if (this.whiteTexture != null) this.whiteTexture.close();
        if (this.mojangLogoTexture != null) this.mojangLogoTexture.close();
        if (this.progressBarBgTexture != null) this.progressBarBgTexture.close();
        if (this.progressBarFgTexture != null) this.progressBarFgTexture.close();
        if (this.foxTexture != null) this.foxTexture.close();
    }
}