package com.fs20modconverterpro.texture;

import java.io.IOException;
import java.io.OutputStream;

/** Grava a saída já codificada no destino SAF sem acesso ao arquivo de origem. */
public final class TextureExportWriter {
    private TextureExportWriter() { }
    public static void write(OutputStream output, byte[] encoded) throws IOException {
        if (output == null || encoded == null || encoded.length == 0
                || encoded.length > TextureInspector.MAX_FILE_BYTES) {
            throw new IOException("A imagem de saída está vazia ou excede 32 MiB.");
        }
        output.write(encoded);
        output.flush();
    }
}
