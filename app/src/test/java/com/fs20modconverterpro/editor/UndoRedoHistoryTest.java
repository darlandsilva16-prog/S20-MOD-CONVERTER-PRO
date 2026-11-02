package com.fs20modconverterpro.editor;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class UndoRedoHistoryTest {
    @Test
    public void desfazERefazInsercao() {
        UndoRedoHistory history = new UndoRedoHistory();
        history.recordEdit(1, "", "x", 100);
        history.recordEdit(2, "", "y", 200);

        UndoRedoHistory.Change undo = history.undo("axy");
        assertEquals("a", undo.getText());
        assertEquals(1, undo.getSelection());
        assertTrue(history.canRedo());

        UndoRedoHistory.Change redo = history.redo("a");
        assertEquals("axy", redo.getText());
        assertFalse(history.canRedo());
    }

    @Test
    public void desfazSubstituicaoDeDocumentoInteiro() {
        UndoRedoHistory history = new UndoRedoHistory();
        history.recordEdit(0, "<a/> ", "<a>\n</a>", 1_000);

        assertEquals("<a/> ", history.undo("<a>\n</a>").getText());
        assertEquals("<a>\n</a>", history.redo("<a/> ").getText());
    }

    @Test
    public void desfazBackspacesAgrupados() {
        UndoRedoHistory history = new UndoRedoHistory();
        history.recordEdit(2, "c", "", 100);
        history.recordEdit(1, "b", "", 200);

        assertEquals("abc", history.undo("a").getText());
    }

    @Test
    public void invalidaHistoricoSeTextoAtualNaoCorresponde() {
        UndoRedoHistory history = new UndoRedoHistory();
        history.recordEdit(1, "", "x", 100);

        assertEquals(null, history.undo("different"));
        assertFalse(history.canUndo());
    }
}
