package com.fs20modconverterpro.texture;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.IOException;

public final class TextureRasterDecoderTest {
    private final TextureInspector inspector = new TextureInspector();
    private final TextureRasterDecoder decoder = new TextureRasterDecoder();

    @Test public void decodificaBmp24ComOrientacaoECanalRgb() throws Exception {
        byte[] bmp = bmp24();
        TextureInfo info = inspector.inspect(bmp, "bmp.bmp", null, bmp.length);
        TextureRaster image = decoder.decode(bmp, info, 1024);
        assertEquals(2, image.getWidth()); assertEquals(1, image.getHeight());
        assertEquals(0xffff0000, image.getPixels()[0]);
        assertEquals(0xff00ff00, image.getPixels()[1]);
        assertFalse(image.hasAlpha());
    }

    @Test public void decodificaTgaTrueColorETransparencia() throws Exception {
        byte[] tga = tga2();
        TextureInfo info = inspector.inspect(tga, "tga.tga", null, tga.length);
        TextureRaster image = decoder.decode(tga, info, 1024);
        assertEquals(0xff0000ff, image.getPixels()[0]);
        assertEquals(0x8000ff00, image.getPixels()[1]);
        assertTrue(image.hasAlpha());
    }

    @Test public void decodificaTgaRleTipo10() throws Exception {
        byte[] tga = new byte[22]; tga[2] = 10; tga[12] = 2; tga[14] = 1; tga[16] = 24; tga[17] = 0x20;
        tga[18] = (byte) 0x81; // dois pixels repetidos no pacote RLE
        tga[19] = (byte) 255; tga[20] = 0; tga[21] = 0; // azul BGR
        TextureInfo info = inspector.inspect(tga, "rle.tga", null, tga.length);
        TextureRaster image = decoder.decode(tga, info, 1024);
        assertEquals(0xff0000ff, image.getPixels()[0]);
        assertEquals(0xff0000ff, image.getPixels()[1]);
    }

    @Test public void decodificaDxt1ECanalTransparente() throws Exception {
        byte[] dds = TextureInspectorTest.dds("DXT1", 4, 4);
        dds[128] = 0; dds[129] = 0; dds[130] = (byte) 0xff; dds[131] = (byte) 0xff;
        TextureInfo info = inspector.inspect(dds, "dxt.dds", null, dds.length);
        TextureRaster image = decoder.decode(dds, info, 4);
        assertEquals(0xff000000, image.getPixels()[0]);
        assertTrue(image.hasAlpha());
    }

    @Test public void decodificaDxt3ComAlphaExplicito() throws Exception {
        byte[] dds = java.util.Arrays.copyOf(TextureInspectorTest.dds("DXT3", 4, 4), 144);
        dds[128] = (byte) 0xf0; // pixel 0 alpha zero; demais pixels alpha total
        dds[136] = 0; dds[137] = (byte) 0xf8; // c0 = vermelho 565
        dds[138] = (byte) 0xe0; dds[139] = 0x07; // c1 = verde 565
        TextureInfo info = inspector.inspect(dds, "dxt3.dds", null, dds.length);
        TextureRaster image = decoder.decode(dds, info, 4);
        assertEquals(0x00ff0000, image.getPixels()[0]);
        assertTrue(image.hasAlpha());
    }

    @Test public void decodificaDxt5ComBlocoDeAlpha() throws Exception {
        byte[] dds = java.util.Arrays.copyOf(TextureInspectorTest.dds("DXT5", 4, 4), 144);
        dds[128] = (byte) 255; dds[129] = 0;
        dds[136] = 0; dds[137] = (byte) 0xf8;
        dds[138] = (byte) 0xe0; dds[139] = 0x07;
        TextureInfo info = inspector.inspect(dds, "dxt5.dds", null, dds.length);
        TextureRaster image = decoder.decode(dds, info, 4);
        assertEquals(0xffff0000, image.getPixels()[0]);
        assertTrue(image.hasAlpha());
    }

    @Test public void decodificaDdsRgb32ComMascaras() throws Exception {
        byte[] dds = new byte[132]; dds[0] = 'D'; dds[1] = 'D'; dds[2] = 'S'; dds[3] = ' ';
        TextureInspectorTest.putLe32(dds, 4, 124); TextureInspectorTest.putLe32(dds, 8, 0x100f);
        TextureInspectorTest.putLe32(dds, 12, 1); TextureInspectorTest.putLe32(dds, 16, 1);
        TextureInspectorTest.putLe32(dds, 20, 4); TextureInspectorTest.putLe32(dds, 76, 32);
        TextureInspectorTest.putLe32(dds, 80, 0x40); TextureInspectorTest.putLe32(dds, 88, 32);
        TextureInspectorTest.putLe32(dds, 92, 0x00ff0000); TextureInspectorTest.putLe32(dds, 96, 0x0000ff00);
        TextureInspectorTest.putLe32(dds, 100, 0x000000ff); TextureInspectorTest.putLe32(dds, 104, 0xff000000);
        TextureInspectorTest.putLe32(dds, 128, 0x80402010);
        TextureInfo info = inspector.inspect(dds, "rgba.dds", null, dds.length);
        assertTrue(info.canConvert());
        TextureRaster image = decoder.decode(dds, info, 8);
        assertEquals(0x80402010, image.getPixels()[0]);
        assertTrue(image.hasAlpha());
    }

    @Test public void rejeitaPixelsTgaTruncados() throws Exception {
        byte[] tga = tga2();
        byte[] truncated = java.util.Arrays.copyOf(tga, 18);
        TextureInfo info = inspector.inspect(tga, "tga.tga", null, tga.length);
        try {
            decoder.decode(truncated, info, 1024);
            throw new AssertionError("A entrada truncada deveria falhar.");
        } catch (IOException expected) { assertTrue(expected.getMessage().contains("truncados")); }
    }

    @Test public void aplicaAmostragemParaReduzirMemoria() throws Exception {
        assertEquals(4, TextureRasterDecoder.sampleSize(4096, 2048, 1024));
    }

    private static byte[] bmp24() {
        byte[] b = new byte[62]; b[0] = 'B'; b[1] = 'M';
        TextureInspectorTest.putLe32(b, 2, b.length); TextureInspectorTest.putLe32(b, 10, 54);
        TextureInspectorTest.putLe32(b, 14, 40); TextureInspectorTest.putLe32(b, 18, 2);
        TextureInspectorTest.putLe32(b, 22, 1); b[26] = 1; b[28] = 24;
        b[56] = (byte) 255; // B=0, G=0, R=255: vermelho
        b[57] = 0; b[58] = (byte) 255; b[59] = 0; // B=0, G=255, R=0: verde
        return b;
    }
    private static byte[] tga2() {
        byte[] b = new byte[26]; b[2] = 2; b[12] = 2; b[14] = 1; b[16] = 32; b[17] = 0x28;
        b[18] = (byte) 255; b[19] = 0; b[20] = 0; b[21] = (byte) 255; // azul
        b[22] = 0; b[23] = (byte) 255; b[24] = 0; b[25] = (byte) 128; // verde semitransparente
        return b;
    }
}
