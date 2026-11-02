package com.fs20modconverterpro.viewer3d;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;

/** Leitor JSON estrito e limitado usado para os descritores glTF. */
final class MiniJsonParser {
    private static final int MAX_DEPTH = 96;
    private static final int MAX_VALUES = 250_000;
    private final String text;
    private int position;
    private int values;

    private MiniJsonParser(String text) { this.text = text; }

    static Object parse(String text) throws ModelFormatException {
        MiniJsonParser parser = new MiniJsonParser(text);
        Object value = parser.readValue(0);
        parser.skipSpace();
        if (parser.position != text.length()) throw parser.error("Dados extras após o JSON.");
        return value;
    }

    private Object readValue(int depth) throws ModelFormatException {
        if (depth > MAX_DEPTH || ++values > MAX_VALUES) throw error("JSON glTF excede limites de segurança.");
        skipSpace();
        if (position >= text.length()) throw error("JSON glTF incompleto.");
        char current = text.charAt(position);
        if (current == '{') return readObject(depth + 1);
        if (current == '[') return readArray(depth + 1);
        if (current == '"') return readString();
        if (current == 't') { literal("true"); return Boolean.TRUE; }
        if (current == 'f') { literal("false"); return Boolean.FALSE; }
        if (current == 'n') { literal("null"); return null; }
        if (current == '-' || current >= '0' && current <= '9') return readNumber();
        throw error("Token JSON glTF inválido.");
    }

    private Map<String, Object> readObject(int depth) throws ModelFormatException {
        position++;
        LinkedHashMap<String, Object> result = new LinkedHashMap<>();
        skipSpace();
        if (take('}')) return result;
        while (true) {
            skipSpace();
            if (position >= text.length() || text.charAt(position) != '"') throw error("Chave JSON esperada.");
            String key = readString();
            skipSpace();
            if (!take(':')) throw error("Faltou ':' após uma chave JSON.");
            result.put(key, readValue(depth));
            skipSpace();
            if (take('}')) return result;
            if (!take(',')) throw error("Faltou ',' entre propriedades JSON.");
        }
    }

    private ArrayList<Object> readArray(int depth) throws ModelFormatException {
        position++;
        ArrayList<Object> result = new ArrayList<>();
        skipSpace();
        if (take(']')) return result;
        while (true) {
            result.add(readValue(depth));
            skipSpace();
            if (take(']')) return result;
            if (!take(',')) throw error("Faltou ',' entre itens JSON.");
        }
    }

    private String readString() throws ModelFormatException {
        position++;
        StringBuilder result = new StringBuilder();
        while (position < text.length()) {
            char value = text.charAt(position++);
            if (value == '"') return result.toString();
            if (value < 0x20) throw error("Caractere de controle dentro de string JSON.");
            if (value != '\\') { result.append(value); continue; }
            if (position >= text.length()) throw error("Escape JSON incompleto.");
            char escape = text.charAt(position++);
            switch (escape) {
                case '"': case '\\': case '/': result.append(escape); break;
                case 'b': result.append('\b'); break;
                case 'f': result.append('\f'); break;
                case 'n': result.append('\n'); break;
                case 'r': result.append('\r'); break;
                case 't': result.append('\t'); break;
                case 'u':
                    if (position + 4 > text.length()) throw error("Escape Unicode JSON incompleto.");
                    try { result.append((char) Integer.parseInt(text.substring(position, position + 4), 16)); }
                    catch (NumberFormatException e) { throw error("Escape Unicode JSON inválido."); }
                    position += 4;
                    break;
                default: throw error("Escape JSON inválido.");
            }
        }
        throw error("String JSON não foi fechada.");
    }

    private Number readNumber() throws ModelFormatException {
        int start = position;
        if (take('-') && position >= text.length()) throw error("Número JSON incompleto.");
        if (take('0')) {
            if (position < text.length() && Character.isDigit(text.charAt(position))) throw error("Número JSON inválido.");
        } else {
            if (position >= text.length() || !Character.isDigit(text.charAt(position))) throw error("Número JSON inválido.");
            while (position < text.length() && Character.isDigit(text.charAt(position))) position++;
        }
        if (take('.')) {
            int fraction = position;
            while (position < text.length() && Character.isDigit(text.charAt(position))) position++;
            if (fraction == position) throw error("Fração JSON inválida.");
        }
        if (position < text.length() && (text.charAt(position) == 'e' || text.charAt(position) == 'E')) {
            position++;
            if (position < text.length() && (text.charAt(position) == '+' || text.charAt(position) == '-')) position++;
            int exponent = position;
            while (position < text.length() && Character.isDigit(text.charAt(position))) position++;
            if (exponent == position) throw error("Expoente JSON inválido.");
        }
        try { return Double.parseDouble(text.substring(start, position)); }
        catch (NumberFormatException e) { throw error("Número JSON inválido."); }
    }

    private void literal(String expected) throws ModelFormatException {
        if (!text.regionMatches(position, expected, 0, expected.length())) throw error("Literal JSON inválido.");
        position += expected.length();
    }
    private void skipSpace() { while (position < text.length() && " \t\r\n".indexOf(text.charAt(position)) >= 0) position++; }
    private boolean take(char value) {
        if (position < text.length() && text.charAt(position) == value) { position++; return true; }
        return false;
    }
    private ModelFormatException error(String message) { return new ModelFormatException(message + " (posição " + position + ")."); }
}
