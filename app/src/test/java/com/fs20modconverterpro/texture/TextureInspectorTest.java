package com.fs20modconverterpro.texture;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class TextureInspectorTest {
    private final TextureInspector inspector = new TextureInspector();

    @Test public void identificaPngDimensoesEAlpha() {
        byte[] png = new byte[26];
        byte[] signature = new byte[]{(byte) 137, 80, 78, 71, 13, 10, 26, 10};
        System.arraycopy(signature, 0, png, 0, signature.length);
        putBe32(png, 16, 128); putBe32(png, 20, 64); png[25] = 6;
        TextureInfo info = inspector.inspect(png, "folha.png", "image/png", 512);
        assertEquals(TextureInfo.Format.PNG, info.getFormat());
        assertEquals(128, info.getWidth()); assertEquals(64, info.getHeight());
        assertEquals(TextureInfo.Transparency.YES, info.getTransparency());
        assertTrue(info.canConvert());
    }

    @Test public void leDimensoesJpegNoMarcadorSof() {
        byte[] jpg = new byte[]{(byte) 0xff, (byte) 0xd8, (byte) 0xff, (byte) 0xc0,
                0, 17, 8, 0, 2, 0, 3, 3, 1, 0x11, 0, 2, 0x11, 0, 3, 0x11, 0};
        TextureInfo info = inspector.inspect(jpg, "roda.jpg", "image/jpeg", jpg.length);
        assertEquals(TextureInfo.Format.JPG, info.getFormat());
        assertEquals(3, info.getWidth()); assertEquals(2, info.getHeight());
        assertEquals(TextureInfo.Transparency.NO, info.getTransparency());
        assertTrue(info.canConvert());
    }

    @Test public void identificaDdsDxtERecusaDx10() {
        TextureInfo supported = inspector.inspect(dds("DXT5", 4, 4), "textura.dds", null, 144);
        assertEquals(TextureInfo.Format.DDS, supported.getFormat());
        assertEquals("DXT5", supported.getVariant());
        assertTrue(supported.canConvert());
        TextureInfo unsupported = inspector.inspect(dds("DX10", 4, 4), "textura.dds", null, 128);
        assertFalse(unsupported.canConvert());
        assertTrue(unsupported.getMessage().contains("decodificador"));
    }

    @Test public void identificaFormatosDeGpuNaoSuportadosComMotivo() {
        TextureInfo info = inspector.inspect(new byte[0], "material.ktx2", null, 500);
        assertEquals(TextureInfo.Format.KTX, info.getFormat());
        assertFalse(info.canConvert());
        assertTrue(info.getMessage().contains("GPU"));
    }

    @Test public void rejeitaCabecalhoIncompletoEDimensaoExcessiva() {
        TextureInfo broken = inspector.inspect(new byte[]{'D', 'D', 'S', ' '}, "bad.dds", null, 4);
        assertFalse(broken.canConvert());
        byte[] large = dds("DXT1", 8192, 8192);
        assertFalse(inspector.inspect(large, "large.dds", null, large.length).canConvert());
    }

    static byte[] dds(String fourcc, int width, int height) {
        byte[] bytes = new byte[128 + 8]; bytes[0] = 'D'; bytes[1] = 'D'; bytes[2] = 'S'; bytes[3] = ' ';
        putLe32(bytes, 4, 124); putLe32(bytes, 8, 0x1007); putLe32(bytes, 12, height); putLe32(bytes, 16, width);
        putLe32(bytes, 76, 32); putLe32(bytes, 80, 4);
        for (int i = 0; i < 4; i++) bytes[84 + i] = (byte) fourcc.charAt(i);
        return bytes;
    }
    static void putLe32(byte[] bytes, int at, int value) {
        for (int i = 0; i < 4; i++) bytes[at + i] = (byte) (value >>> (8 * i));
    }
    static void putBe32(byte[] bytes, int at, int value) {
        for (int i = 0; i < 4; i++) bytes[at + i] = (byte) (value >>> (8 * (3 - i)));
    }
}
