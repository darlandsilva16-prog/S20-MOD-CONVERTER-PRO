package com.fs20modconverterpro.editor;

/** Resultado validado, com localização quando o parser a informa. */
public final class XmlValidationResult {
    private final boolean valid;
    private final int line;
    private final int column;
    private final String message;

    private XmlValidationResult(boolean valid, int line, int column, String message) {
        this.valid = valid;
        this.line = line;
        this.column = column;
        this.message = message;
    }

    public static XmlValidationResult valid() {
        return new XmlValidationResult(true, -1, -1, "XML válido.");
    }

    public static XmlValidationResult invalid(int line, int column, String message) {
        return new XmlValidationResult(false, line, column, message);
    }

    public boolean isValid() {
        return valid;
    }

    public int getLine() {
        return line;
    }

    public int getColumn() {
        return column;
    }

    public String getMessage() {
        return message;
    }
}
