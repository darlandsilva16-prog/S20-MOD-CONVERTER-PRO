package com.fs20modconverterpro.editor;

import java.util.ArrayDeque;
import java.util.Deque;

/** Histórico de edição por alterações de texto, limitado para conter o uso de memória. */
public final class UndoRedoHistory {
    private static final int MAX_OPERATIONS = 1_000;
    private static final long MAX_HISTORY_BYTES = 10L * 1024L * 1024L;
    private static final long COALESCE_WINDOW_MS = 750;

    private final Deque<Edit> undo = new ArrayDeque<>();
    private final Deque<Edit> redo = new ArrayDeque<>();
    private long historyBytes;

    public void clear() {
        undo.clear();
        redo.clear();
        historyBytes = 0;
    }

    public void recordEdit(int start, String removed, String inserted, long timestampMs) {
        if (start < 0 || removed == null || inserted == null || removed.equals(inserted)) {
            return;
        }
        clearRedo();

        Edit previous = undo.peekLast();
        if (previous != null && timestampMs - previous.timestampMs >= 0
                && timestampMs - previous.timestampMs <= COALESCE_WINDOW_MS
                && previous.combine(start, removed, inserted, timestampMs)) {
            historyBytes -= previous.byteCost;
            previous.updateByteCost();
            historyBytes += previous.byteCost;
            previous.timestampMs = timestampMs;
            trimHistory();
            return;
        }

        Edit edit = new Edit(start, removed, inserted, timestampMs);
        if (edit.byteCost > MAX_HISTORY_BYTES) {
            clear();
            return;
        }
        undo.addLast(edit);
        historyBytes += edit.byteCost;
        trimHistory();
    }

    public Change undo(String currentText) {
        Edit edit = undo.peekLast();
        if (edit == null) {
            return null;
        }
        if (!matches(currentText, edit.start, edit.inserted)) {
            clear();
            return null;
        }
        undo.removeLast();
        redo.addLast(edit);
        String updated = replace(currentText, edit.start, edit.inserted.length(), edit.removed);
        return new Change(updated, edit.start + edit.removed.length());
    }

    public Change redo(String currentText) {
        Edit edit = redo.peekLast();
        if (edit == null) {
            return null;
        }
        if (!matches(currentText, edit.start, edit.removed)) {
            clear();
            return null;
        }
        redo.removeLast();
        undo.addLast(edit);
        String updated = replace(currentText, edit.start, edit.removed.length(), edit.inserted);
        return new Change(updated, edit.start + edit.inserted.length());
    }

    public boolean canUndo() {
        return !undo.isEmpty();
    }

    public boolean canRedo() {
        return !redo.isEmpty();
    }

    private void clearRedo() {
        Edit edit;
        while ((edit = redo.pollLast()) != null) {
            historyBytes -= edit.byteCost;
        }
    }

    private void trimHistory() {
        while (undo.size() + redo.size() > MAX_OPERATIONS || historyBytes > MAX_HISTORY_BYTES) {
            Edit removed = !undo.isEmpty() ? undo.removeFirst() : redo.pollFirst();
            if (removed == null) {
                break;
            }
            historyBytes -= removed.byteCost;
        }
    }

    private static boolean matches(String text, int start, String expected) {
        if (text == null || start < 0 || start + expected.length() > text.length()) {
            return false;
        }
        return text.regionMatches(start, expected, 0, expected.length());
    }

    private static String replace(String text, int start, int removeCount, String insert) {
        return text.substring(0, start) + insert + text.substring(start + removeCount);
    }

    public static final class Change {
        private final String text;
        private final int selection;

        Change(String text, int selection) {
            this.text = text;
            this.selection = selection;
        }

        public String getText() {
            return text;
        }

        public int getSelection() {
            return selection;
        }
    }

    private static final class Edit {
        int start;
        String removed;
        String inserted;
        long timestampMs;
        long byteCost;

        Edit(int start, String removed, String inserted, long timestampMs) {
            this.start = start;
            this.removed = removed;
            this.inserted = inserted;
            this.timestampMs = timestampMs;
            updateByteCost();
        }

        boolean combine(int nextStart, String nextRemoved, String nextInserted, long nextTime) {
            if (removed.isEmpty() && inserted.length() > 0
                    && nextRemoved.isEmpty() && nextInserted.length() > 0
                    && nextStart == start + inserted.length()) {
                inserted += nextInserted;
                return true;
            }
            if (removed.length() > 0 && inserted.isEmpty()
                    && nextRemoved.length() > 0 && nextInserted.isEmpty()
                    && nextStart + nextRemoved.length() == start) {
                removed = nextRemoved + removed;
                start = nextStart;
                return true;
            }
            if (removed.length() > 0 && inserted.isEmpty()
                    && nextRemoved.length() > 0 && nextInserted.isEmpty()
                    && nextStart == start) {
                removed += nextRemoved;
                return true;
            }
            return false;
        }

        void updateByteCost() {
            byteCost = 32L + 2L * (removed.length() + inserted.length());
        }
    }
}
