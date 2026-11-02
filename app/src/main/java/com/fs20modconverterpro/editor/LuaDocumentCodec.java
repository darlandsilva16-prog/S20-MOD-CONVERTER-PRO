package com.fs20modconverterpro.editor;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/** Leitura e exportação limitadas que preservam uma codificação detectada. */
public final class LuaDocumentCodec {
    public static final int MAX_FILE_BYTES = 2 * 1024 * 1024;
    public static final int MAX_TEXT_CHARACTERS = 2 * 1024 * 1024;
    public static final int MAX_LINES = 100_000;

    private LuaDocumentCodec() { }

    public static byte[] readBounded(InputStream input) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int total = 0;
        int count;
        while ((count = input.read(buffer)) != -1) {
            if (count == 0) continue;
            if (total > MAX_FILE_BYTES - count) {
                throw new IOException("O arquivo Lua excede o limite de 2 MiB.");
            }
            output.write(buffer, 0, count);
            total += count;
        }
        return output.toByteArray();
    }

    public static DecodedLua decode(byte[] bytes) throws IOException {
        if (bytes == null || bytes.length > MAX_FILE_BYTES) {
            throw new IOException("O arquivo Lua excede o limite de 2 MiB.");
        }
        byte[] bom = new byte[0];
        Charset charset;
        int offset;
        if (startsWith(bytes, 0xEF, 0xBB, 0xBF)) {
            bom = new byte[]{(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};
            charset = StandardCharsets.UTF_8;
            offset = 3;
        } else if (startsWith(bytes, 0xFE, 0xFF)) {
            bom = new byte[]{(byte) 0xFE, (byte) 0xFF};
            charset = StandardCharsets.UTF_16BE;
            offset = 2;
        } else if (startsWith(bytes, 0xFF, 0xFE)) {
            bom = new byte[]{(byte) 0xFF, (byte) 0xFE};
            charset = StandardCharsets.UTF_16LE;
            offset = 2;
        } else if (bytes.length >= 2 && bytes[0] == 0 && bytes[1] != 0) {
            charset = StandardCharsets.UTF_16BE;
            offset = 0;
        } else if (bytes.length >= 2 && bytes[0] != 0 && bytes[1] == 0) {
            charset = StandardCharsets.UTF_16LE;
            offset = 0;
        } else {
            charset = StandardCharsets.UTF_8;
            offset = 0;
        }
        String text;
        try {
            text = charset.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes, offset, bytes.length - offset)).toString();
        } catch (CharacterCodingException malformedUtf8) {
            if (offset != 0) throw new IOException("A codificação do arquivo não é válida.", malformedUtf8);
            // Lua legado pode usar páginas de código de byte único; ISO-8859-1 mantém os bytes reversíveis.
            charset = StandardCharsets.ISO_8859_1;
            text = new String(bytes, charset);
        }
        if (text.length() > MAX_TEXT_CHARACTERS) {
            throw new IOException("O arquivo Lua excede o limite de texto do editor.");
        }
        int lines = 1;
        for (int i = 0; i < text.length(); i++) if (text.charAt(i) == '\n' && ++lines > MAX_LINES) {
            throw new IOException("O arquivo Lua excede o limite de linhas do editor.");
        }
        return new DecodedLua(text, charset, bom);
    }

    public static byte[] encode(String text, DecodedLua original) throws IOException {
        if (text == null || original == null || text.length() > MAX_TEXT_CHARACTERS) {
            throw new IOException("O texto Lua excede o limite do editor.");
        }
        int lines = 1;
        for (int i = 0; i < text.length(); i++) if (text.charAt(i) == '\n' && ++lines > MAX_LINES) {
            throw new IOException("O arquivo Lua excede o limite de linhas do editor.");
        }
        try {
            ByteBuffer encoded = original.charset.newEncoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).encode(CharBuffer.wrap(text));
            if (encoded.remaining() + original.bom.length > MAX_FILE_BYTES) {
                throw new IOException("O arquivo Lua editado excede o limite de 2 MiB.");
            }
            byte[] result = new byte[original.bom.length + encoded.remaining()];
            System.arraycopy(original.bom, 0, result, 0, original.bom.length);
            encoded.get(result, original.bom.length, encoded.remaining());
            return result;
        } catch (CharacterCodingException exception) {
            throw new IOException("Há caracteres que não podem ser salvos na codificação original.", exception);
        }
    }

    private static boolean startsWith(byte[] bytes, int... values) {
        if (bytes.length < values.length) return false;
        for (int i = 0; i < values.length; i++) {
            if ((bytes[i] & 0xff) != values[i]) return false;
        }
        return true;
    }

    public static final class DecodedLua {
        private final String text;
        private final Charset charset;
        private final byte[] bom;

        DecodedLua(String text, Charset charset, byte[] bom) {
            this.text = text;
            this.charset = charset;
            this.bom = Arrays.copyOf(bom, bom.length);
        }

        public String getText() { return text; }
        public Charset getCharset() { return charset; }
    }
}
