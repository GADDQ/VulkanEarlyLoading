package top.earthstudio.vulkanearlyloading.vulkan;

import com.mojang.logging.LogUtils;
import net.neoforged.fml.earlydisplay.theme.NativeBuffer;
import org.lwjgl.BufferUtils;
import org.lwjgl.stb.*;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.slf4j.Logger;

import java.nio.ByteBuffer;

public class VulkanFont implements AutoCloseable {
    private static final Logger LOGGER = LogUtils.getLogger();

    private static final int ATLAS_SIZE = 512;
    private static final int FONT_SIZE = 24; // 1:1 对齐原版 SimpleFont.java 第 121 行
    private static final int FIRST_CHAR = 32;
    private static final int ASCII_COUNT = 95;

    public record Glyph(char c, int charWidth, int x0, int y0, int x1, int y1, float u0, float v0, float u1, float v1) {}

    private final Glyph[] glyphs = new Glyph[ASCII_COUNT];
    private final VulkanTexture fontTexture;
    private final int lineSpacing;

    public VulkanFont(VulkanContext context, long pool, long layout) {
        STBTTPackedchar.Buffer packedChars = STBTTPackedchar.malloc(ASCII_COUNT);
        STBTTPackRange.Buffer packRanges = STBTTPackRange.malloc(1);
        ByteBuffer fontBitmap = BufferUtils.createByteBuffer(ATLAS_SIZE * ATLAS_SIZE);

        int spacing = 24;
        try (NativeBuffer ttfBuf = NativeBuffer.loadFromClasspath("net/neoforged/fml/earlydisplay/theme/Monocraft.ttf", null)) {
            ByteBuffer ttf = ttfBuf.buffer();

            STBTTFontinfo info = STBTTFontinfo.create();
            if (STBTruetype.stbtt_InitFont(info, ttf)) {
                float[] ascent = new float[1];
                float[] descent = new float[1];
                float[] lineGap = new float[1];
                STBTruetype.stbtt_GetScaledFontVMetrics(ttf, 0, FONT_SIZE, ascent, descent, lineGap);
                spacing = (int) (ascent[0] - descent[0] + lineGap[0]);
            }

            try (STBTTPackContext pc = STBTTPackContext.malloc()) {
                STBTruetype.stbtt_PackBegin(pc, fontBitmap, ATLAS_SIZE, ATLAS_SIZE, 0, 1, 0L);
                STBTTPackRange packRange = STBTTPackRange.malloc();
                packRanges.put(packRange.set(FONT_SIZE, FIRST_CHAR, null, ASCII_COUNT, packedChars, (byte) 1, (byte) 1));
                packRanges.flip();
                packRange.close();

                STBTruetype.stbtt_PackFontRanges(pc, ttf, 0, packRanges);
                STBTruetype.stbtt_PackEnd(pc);
            }

            try (STBTTAlignedQuad q = STBTTAlignedQuad.malloc()) {
                float[] x = new float[1];
                float[] y = new float[1];
                for (int i = 0; i < ASCII_COUNT; i++) {
                    x[0] = 0.0F;
                    y[0] = FONT_SIZE;
                    STBTruetype.stbtt_GetPackedQuad(packedChars, ATLAS_SIZE, ATLAS_SIZE, i, x, y, q, true);
                    this.glyphs[i] = new Glyph(
                            (char) (i + FIRST_CHAR),
                            (int) x[0],
                            (int) q.x0(), (int) q.y0(), (int) q.x1(), (int) q.y1(),
                            q.s0(), q.t0(), q.s1(), q.t1()
                    );
                }
            }
        } catch (Throwable t) {
            LOGGER.error("Failed to load Monocraft font", t);
        } finally {
            packedChars.free();
            packRanges.free();
        }

        this.lineSpacing = spacing;

        ByteBuffer rgba = BufferUtils.createByteBuffer(ATLAS_SIZE * ATLAS_SIZE * 4);
        for (int i = 0; i < ATLAS_SIZE * ATLAS_SIZE; i++) {
            byte a = fontBitmap.get(i);
            rgba.put((byte) 255).put((byte) 255).put((byte) 255).put(a);
        }
        rgba.flip();

        this.fontTexture = new VulkanTexture(context, ATLAS_SIZE, ATLAS_SIZE, rgba, pool, layout);
    }

    public int stringWidth(String text) {
        if (text == null) return 0;
        int w = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c >= FIRST_CHAR && c < FIRST_CHAR + ASCII_COUNT) {
                w += this.glyphs[c - FIRST_CHAR].charWidth();
            }
        }
        return w;
    }

    public void drawText(VulkanBatch batch, VkCommandBuffer cmd, long pipelineLayout,
                         String text, float startX, float startY, float r, float g, float b, float a) {
        if (text == null || text.isEmpty() || this.fontTexture == null) return;

        float posX = startX;
        float posY = startY;

        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\n') {
                posX = startX;
                posY += this.lineSpacing;
                continue;
            }
            if (c < FIRST_CHAR || c >= FIRST_CHAR + ASCII_COUNT) {
                posX += 12;
                continue;
            }

            Glyph glyph = this.glyphs[c - FIRST_CHAR];
            float x0 = posX + glyph.x0();
            float y0 = posY + glyph.y0();
            float x1 = posX + glyph.x1();
            float y1 = posY + glyph.y1();

            batch.drawQuad(cmd, pipelineLayout, this.fontTexture,
                    x0, y0, x1, y1,
                    glyph.u0(), glyph.v0(), glyph.u1(), glyph.v1(),
                    r, g, b, a);

            posX += glyph.charWidth();
        }
    }

    public int lineSpacing() {
        return this.lineSpacing;
    }

    @Override
    public void close() {
        if (this.fontTexture != null) this.fontTexture.close();
    }
}