package com.fs20modconverterpro.editor;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CharsetEncoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Leitura e gravação XML limitadas, com detecção e preservação de codificação. */
public final class XmlDocumentCodec {
    public static final int MAX_XML_BYTES = 2 * 1024 * 1024;
    public static final int MAX_XML_CHARACTERS = 2 * 1024 * 1024;
    private static final Pattern XML_ENCODING = Pattern.compile(
            "^\\s*<\\?xml\\s+[^?]*?encoding\\s*=\\s*(['\"])([^'\"]+)\\1",
            Pattern.CASE_INSENSITIVE);

    private XmlDocumentCodec() {
    }

    public static byte[] readBounded(InputStream input) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[16 * 1024];
        int total = 0;
        int read;
        while ((read = input.read(buffer)) != -1) {
            if (read == 0) {
                continue;
            }
            if (total > MAX_XML_BYTES - read) {
                throw new IOException("O XML excede o limite de 2 MiB.");
            }
            output.write(buffer, 0, read);
            total += read;
        }
        return output.toByteArray();
    }

    public static DecodedXml decode(byte[] bytes) throws IOException {
        if (bytes == null) {
            throw new IOException("O arquivo XML não pôde ser lido.");
        }
        if (bytes.length == 0) {
            return new DecodedXml("", StandardCharsets.UTF_8, new byte[0], false);
        }
        if (bytes.length > MAX_XML_BYTES) {
            throw new IOException("O XML excede o limite de 2 MiB.");
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
        } else if (startsWith(bytes, 0x00, 0x3C, 0x00, 0x3F)) {
            charset = StandardCharsets.UTF_16BE;
            offset = 0;
        } else if (startsWith(bytes, 0x3C, 0x00, 0x3F, 0x00)) {
            charset = StandardCharsets.UTF_16LE;
            offset = 0;
        } else {
            charset = declaredCharset(bytes);
            offset = 0;
        }

        String text = decodeStrict(bytes, offset, charset);
        if (text.length() > MAX_XML_CHARACTERS) {
            throw new IOException("O XML excede o limite de caracteres do editor.");
        }
        String declaredEncoding = findDeclaredEncoding(text);
        if (declaredEncoding != null && !isCompatible(charset, declaredEncoding)) {
            throw new IOException("A declaração de codificação XML não corresponde aos bytes do arquivo.");
        }
        return new DecodedXml(text, charset, bom, declaredEncoding != null);
    }

    public static byte[] encode(String text, DecodedXml original) throws IOException {
        if (text == null || original == null) {
            throw new IOException("O conteúdo ou a codificação do XML não está disponível.");
        }
        if (text.length() > MAX_XML_CHARACTERS) {
            throw new IOException("O XML excede o limite de caracteres do editor.");
        }
        String declaredEncoding = findDeclaredEncoding(text);
        if (declaredEncoding != null && !isCompatible(original.charset, declaredEncoding)) {
            throw new IOException("Mantenha a codificação da declaração XML como "
                    + original.charset.name() + " para preservar o arquivo.");
        }

        try {
            CharsetEncoder encoder = original.charset.newEncoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT);
            ByteBuffer encoded = encoder.encode(CharBuffer.wrap(text));
            if (original.bom.length + encoded.remaining() > MAX_XML_BYTES) {
                throw new IOException("O XML editado excede o limite de 2 MiB.");
            }
            byte[] result = new byte[original.bom.length + encoded.remaining()];
            System.arraycopy(original.bom, 0, result, 0, original.bom.length);
            encoded.get(result, original.bom.length, encoded.remaining());
            return result;
        } catch (CharacterCodingException exception) {
            throw new IOException("O texto contém caracteres que não podem ser salvos na codificação original.",
                    exception);
        }
    }

    private static Charset declaredCharset(byte[] bytes) throws IOException {
        int prefixLength = Math.min(bytes.length, 512);
        String prefix = new String(bytes, 0, prefixLength, StandardCharsets.ISO_8859_1);
        String encoding = findDeclaredEncoding(prefix);
        if (encoding == null) {
            return StandardCharsets.UTF_8;
        }
        try {
            return Charset.forName(encoding);
        } catch (IllegalArgumentException exception) {
            throw new IOException("A codificação declarada no XML não é suportada.", exception);
        }
    }

    private static String decodeStrict(byte[] bytes, int offset, Charset charset) throws IOException {
        try {
            CharsetDecoder decoder = charset.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT);
            return decoder.decode(ByteBuffer.wrap(bytes, offset, bytes.length - offset)).toString();
        } catch (CharacterCodingException exception) {
            throw new IOException("Os bytes não correspondem à codificação " + charset.name() + ".", exception);
        }
    }

    private static String findDeclaredEncoding(String text) {
        Matcher matcher = XML_ENCODING.matcher(text);
        return matcher.find() ? matcher.group(2).trim() : null;
    }

    private static boolean isCompatible(Charset detected, String declaredName) throws IOException {
        try {
            Charset declared = Charset.forName(declaredName);
            if (detected.equals(declared)) {
                return true;
            }
            return "UTF-16".equalsIgnoreCase(declaredName)
                    && (StandardCharsets.UTF_16LE.equals(detected)
                    || StandardCharsets.UTF_16BE.equals(detected));
        } catch (IllegalArgumentException exception) {
            throw new IOException("A codificação declarada no XML não é suportada.", exception);
        }
    }

    private static boolean startsWith(byte[] bytes, int... values) {
        if (bytes.length < values.length) {
            return false;
        }
        for (int index = 0; index < values.length; index++) {
            if ((bytes[index] & 0xff) != values[index]) {
                return false;
            }
        }
        return true;
    }

    public static final class DecodedXml {
        private final String text;
        private final Charset charset;
        private final byte[] bom;
        private final boolean hasDeclaration;

        DecodedXml(String text, Charset charset, byte[] bom, boolean hasDeclaration) {
            this.text = text;
            this.charset = charset;
            this.bom = Arrays.copyOf(bom, bom.length);
            this.hasDeclaration = hasDeclaration;
        }

        public String getText() {
            return text;
        }

        public Charset getCharset() {
            return charset;
        }

        public boolean hasDeclaration() {
            return hasDeclaration;
        }
    }
}
