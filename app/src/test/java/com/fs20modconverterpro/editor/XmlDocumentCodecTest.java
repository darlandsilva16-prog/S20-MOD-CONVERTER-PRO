package com.fs20modconverterpro.editor;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

public final class XmlDocumentCodecTest {
    @Test
    public void preservaBOMUtf8AoExportar() throws Exception {
        byte[] payload = "<?xml version=\"1.0\" encoding=\"UTF-8\"?><root>á</root>"
                .getBytes(StandardCharsets.UTF_8);
        byte[] source = new byte[payload.length + 3];
        source[0] = (byte) 0xEF;
        source[1] = (byte) 0xBB;
        source[2] = (byte) 0xBF;
        System.arraycopy(payload, 0, source, 3, payload.length);

        XmlDocumentCodec.DecodedXml document = XmlDocumentCodec.decode(source);

        assertEquals("UTF-8", document.getCharset().name());
        assertEquals("\uFEFF" + new String(payload, StandardCharsets.UTF_8),
                "\uFEFF" + document.getText());
        assertArrayEquals(source, XmlDocumentCodec.encode(document.getText(), document));
    }

    @Test
    public void preservaCodificacaoDeclaradaLatin1() throws Exception {
        byte[] source = "<?xml version=\"1.0\" encoding=\"ISO-8859-1\"?><root>á</root>"
                .getBytes(StandardCharsets.ISO_8859_1);

        XmlDocumentCodec.DecodedXml document = XmlDocumentCodec.decode(source);

        assertEquals("ISO-8859-1", document.getCharset().name());
        assertArrayEquals(source, XmlDocumentCodec.encode(document.getText(), document));
    }

    @Test
    public void leituraRecusaArquivoAcimaDoLimite() throws Exception {
        byte[] oversized = new byte[XmlDocumentCodec.MAX_XML_BYTES + 1];
        try {
            XmlDocumentCodec.readBounded(new ByteArrayInputStream(oversized));
            fail("A leitura deveria respeitar o limite de tamanho.");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("2 MiB"));
        }
    }

    @Test
    public void xmlVazioPodeSerAbertoParaEdicao() throws Exception {
        XmlDocumentCodec.DecodedXml document = XmlDocumentCodec.decode(new byte[0]);

        assertEquals("", document.getText());
        assertArrayEquals(new byte[0], XmlDocumentCodec.encode(document.getText(), document));
    }
}
