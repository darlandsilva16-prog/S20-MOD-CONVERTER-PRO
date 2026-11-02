package com.fs20modconverterpro.editor;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.ByteArrayInputStream;

public final class LuaDocumentCodecTest {
    @Test public void abreEExportaPreservandoBomUtf8() throws Exception {
        byte[] original = new byte[]{(byte) 0xEF, (byte) 0xBB, (byte) 0xBF,
                'p', 'r', 'i', 'n', 't', '(', '1', ')'};
        LuaDocumentCodec.DecodedLua decoded = LuaDocumentCodec.decode(original);
        assertEquals("print(1)", decoded.getText());
        assertArrayEquals(original, LuaDocumentCodec.encode(decoded.getText(), decoded));
    }

    @Test public void preservaBytesDeCodificacaoLegada() throws Exception {
        byte[] original = new byte[]{'l', 'o', 'c', 'a', 'l', ' ', 'n', '=', ' ', (byte) 0xE9};
        LuaDocumentCodec.DecodedLua decoded = LuaDocumentCodec.decode(original);
        assertEquals("ISO-8859-1", decoded.getCharset().name());
        assertArrayEquals(original, LuaDocumentCodec.encode(decoded.getText(), decoded));
    }

    @Test public void aceitaEdicaoDesfazRefazESalva() throws Exception {
        LuaDocumentCodec.DecodedLua opened = LuaDocumentCodec.decode("local x = 1".getBytes("UTF-8"));
        UndoRedoHistory history = new UndoRedoHistory();
        String edited = "local x = 2";
        history.recordEdit(10, "1", "2", 10);
        assertEquals("local x = 1", history.undo(edited).getText());
        assertEquals(edited, history.redo("local x = 1").getText());
        assertEquals(edited, LuaDocumentCodec.decode(LuaDocumentCodec.encode(edited, opened)).getText());
    }

    @Test public void limiteDeLeituraEAplicado() throws Exception {
        byte[] small = "return true".getBytes("UTF-8");
        assertTrue(LuaDocumentCodec.readBounded(new ByteArrayInputStream(small)).length > 0);
    }

    @Test public void rejeitaDocumentoComLinhasDemais() throws Exception {
        byte[] input = new byte[LuaDocumentCodec.MAX_LINES + 1];
        java.util.Arrays.fill(input, (byte) '\n');
        try {
            LuaDocumentCodec.decode(input);
            throw new AssertionError("O limite de linhas deveria ser aplicado.");
        } catch (java.io.IOException expected) {
            assertTrue(expected.getMessage().contains("linhas"));
        }
    }
}
