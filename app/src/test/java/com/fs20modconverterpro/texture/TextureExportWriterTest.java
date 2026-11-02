package com.fs20modconverterpro.texture;

import static org.junit.Assert.assertArrayEquals;

import org.junit.Test;

import java.io.ByteArrayOutputStream;

public final class TextureExportWriterTest {
    @Test public void gravaBytesCodificadosNoDestinoEscolhido() throws Exception {
        byte[] pngSignature = new byte[]{(byte) 137, 80, 78, 71};
        ByteArrayOutputStream destination = new ByteArrayOutputStream();
        TextureExportWriter.write(destination, pngSignature);
        assertArrayEquals(pngSignature, destination.toByteArray());
    }
}
