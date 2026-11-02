package com.fs20modconverterpro.editor;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Análise léxica e verificações estruturais básicas; não executa nem interpreta Lua. */
public final class LuaSyntaxAnalyzer {
    private static final int MAX_TOKENS = 50_000;
    public enum Type { KEYWORD, FUNCTION, IDENTIFIER, NUMBER, STRING, COMMENT, OPERATOR }

    public static final class Token {
        private final int start, end, line;
        private final String text;
        private final Type type;
        Token(int start, int end, int line, String text, Type type) {
            this.start = start; this.end = end; this.line = line; this.text = text; this.type = type;
        }
        public int getStart() { return start; }
        public int getEnd() { return end; }
        public int getLine() { return line; }
        public String getText() { return text; }
        public Type getType() { return type; }
    }

    public static final class Diagnostic {
        private final int line;
        private final String message;
        Diagnostic(int line, String message) { this.line = line; this.message = message; }
        public int getLine() { return line; }
        public String getMessage() { return message; }
    }

    public static final class Result {
        private final List<Token> tokens;
        private final List<Diagnostic> diagnostics;
        Result(List<Token> tokens, List<Diagnostic> diagnostics) {
            this.tokens = Collections.unmodifiableList(tokens);
            this.diagnostics = Collections.unmodifiableList(diagnostics);
        }
        public List<Token> getTokens() { return tokens; }
        public List<Diagnostic> getDiagnostics() { return diagnostics; }
        public boolean isValid() { return diagnostics.isEmpty(); }
    }

    private static final Set<String> KEYWORDS = new HashSet<>(Arrays.asList(
            "and", "break", "do", "else", "elseif", "end", "false", "for", "function",
            "goto", "if", "in", "local", "nil", "not", "or", "repeat", "return", "then",
            "true", "until", "while"));

    public Result analyze(String source) {
        List<Token> tokens = new ArrayList<>();
        List<Diagnostic> diagnostics = new ArrayList<>();
        if (source == null) source = "";
        int position = 0;
        int line = 1;
        boolean expectFunctionName = false;
        while (position < source.length()) {
            if (tokens.size() >= MAX_TOKENS) {
                diagnostics.add(new Diagnostic(line, "O arquivo excede o limite de tokens da análise básica."));
                break;
            }
            char current = source.charAt(position);
            if (Character.isWhitespace(current)) {
                if (current == '\n') line++;
                position++;
                continue;
            }
            int start = position;
            int tokenLine = line;
            if (current == '-' && position + 1 < source.length() && source.charAt(position + 1) == '-') {
                position += 2;
                int equals = longBracketEquals(source, position);
                if (equals >= 0) {
                    position += equals + 2;
                    int close = findLongClose(source, position, equals);
                    if (close < 0) {
                        diagnostics.add(new Diagnostic(tokenLine, "Comentário longo não foi fechado."));
                        position = source.length();
                    } else {
                        position = close + equals + 2;
                    }
                    line += countLines(source, start, position);
                } else {
                    while (position < source.length() && source.charAt(position) != '\n') position++;
                }
                tokens.add(token(source, start, position, tokenLine, Type.COMMENT));
            } else if (current == '\'' || current == '"') {
                char quote = current;
                position++;
                boolean closed = false;
                while (position < source.length()) {
                    char value = source.charAt(position++);
                    if (value == '\n') line++;
                    if (value == '\\' && position < source.length()) {
                        if (source.charAt(position) == '\n') line++;
                        position++;
                    } else if (value == quote) {
                        closed = true;
                        break;
                    } else if (value == '\n') {
                        break;
                    }
                }
                if (!closed) diagnostics.add(new Diagnostic(tokenLine, "String entre aspas não foi fechada."));
                tokens.add(token(source, start, position, tokenLine, Type.STRING));
            } else if (current == '[' && longBracketEquals(source, position) >= 0) {
                int equals = longBracketEquals(source, position);
                position += equals + 2;
                int close = findLongClose(source, position, equals);
                if (close < 0) {
                    line += countLines(source, position, source.length());
                    diagnostics.add(new Diagnostic(tokenLine, "String longa não foi fechada."));
                    position = source.length();
                } else {
                    line += countLines(source, position, close + equals + 2);
                    position = close + equals + 2;
                }
                tokens.add(token(source, start, position, tokenLine, Type.STRING));
            } else if (isIdentifierStart(current)) {
                position++;
                while (position < source.length() && isIdentifierPart(source.charAt(position))) position++;
                String word = source.substring(start, position);
                Type type = "function".equals(word) ? Type.FUNCTION
                        : KEYWORDS.contains(word) ? Type.KEYWORD
                        : expectFunctionName ? Type.FUNCTION : Type.IDENTIFIER;
                if ("function".equals(word)) expectFunctionName = true;
                else if (!KEYWORDS.contains(word)) expectFunctionName = false;
                tokens.add(new Token(start, position, tokenLine, word, type));
            } else if (Character.isDigit(current)) {
                position++;
                while (position < source.length()) {
                    char value = source.charAt(position);
                    if (!(Character.isLetterOrDigit(value) || value == '.' || value == '_')) break;
                    position++;
                }
                tokens.add(token(source, start, position, tokenLine, Type.NUMBER));
            } else {
                position++;
                if (current == '\n') line++;
                if (expectFunctionName) expectFunctionName = false;
                tokens.add(token(source, start, position, tokenLine, Type.OPERATOR));
            }
        }
        checkStructure(tokens, diagnostics);
        return new Result(tokens, diagnostics);
    }

    private static void checkStructure(List<Token> tokens, List<Diagnostic> diagnostics) {
        Deque<Token> delimiters = new ArrayDeque<>();
        Deque<Token> blocks = new ArrayDeque<>();
        int pendingIf = 0;
        int pendingLoop = 0;
        for (Token token : tokens) {
            if (token.type == Type.OPERATOR) {
                String value = token.text;
                if ("(".equals(value) || "{".equals(value) || "[".equals(value)) delimiters.push(token);
                if (")".equals(value) || "}".equals(value) || "]".equals(value)) {
                    String expected = ")".equals(value) ? "(" : "}".equals(value) ? "{" : "[";
                    if (delimiters.isEmpty() || !expected.equals(delimiters.peek().text)) {
                        diagnostics.add(new Diagnostic(token.line, "Delimitador '" + value + "' sem par correspondente."));
                    } else delimiters.pop();
                }
                continue;
            }
            String word = token.text;
            if (token.type == Type.FUNCTION && "function".equals(word)) blocks.push(token);
            else if ("if".equals(word)) pendingIf++;
            else if ("then".equals(word) && pendingIf > 0) { blocks.push(token); pendingIf--; }
            else if ("for".equals(word) || "while".equals(word)) pendingLoop++;
            else if ("do".equals(word)) { blocks.push(token); if (pendingLoop > 0) pendingLoop--; }
            else if ("repeat".equals(word)) blocks.push(token);
            else if ("else".equals(word) || "elseif".equals(word)) {
                if (blocks.isEmpty() || !"then".equals(blocks.peek().text)) {
                    diagnostics.add(new Diagnostic(token.line, "'" + word + "' sem bloco 'if' correspondente."));
                }
            }
            else if ("end".equals(word)) {
                if (blocks.isEmpty() || "repeat".equals(blocks.peek().text))
                    diagnostics.add(new Diagnostic(token.line, "'end' sem bloco aberto correspondente."));
                else blocks.pop();
            } else if ("until".equals(word)) {
                if (blocks.isEmpty() || !"repeat".equals(blocks.peek().text))
                    diagnostics.add(new Diagnostic(token.line, "'until' sem bloco 'repeat' correspondente."));
                else blocks.pop();
            }
        }
        while (!delimiters.isEmpty()) {
            Token token = delimiters.removeLast();
            diagnostics.add(new Diagnostic(token.line, "Delimitador '" + token.text + "' não foi fechado."));
        }
        while (!blocks.isEmpty()) {
            Token token = blocks.removeLast();
            String name = "function".equals(token.text) ? "function" : "repeat".equals(token.text) ? "repeat" : "bloco";
            diagnostics.add(new Diagnostic(token.line, "Bloco '" + name + "' não foi fechado."));
        }
        if (pendingIf > 0) diagnostics.add(new Diagnostic(-1, "Há 'if' sem 'then'."));
        if (pendingLoop > 0) diagnostics.add(new Diagnostic(-1, "Há 'for' ou 'while' sem 'do'."));
    }

    static boolean isKeyword(String word) { return KEYWORDS.contains(word); }
    private static boolean isIdentifierStart(char c) { return c == '_' || Character.isLetter(c); }
    private static boolean isIdentifierPart(char c) { return c == '_' || Character.isLetterOrDigit(c); }
    private static Token token(String source, int start, int end, int line, Type type) {
        return new Token(start, end, line, source.substring(start, end), type);
    }
    private static int longBracketEquals(String text, int start) {
        if (start >= text.length() || text.charAt(start) != '[') return -1;
        int cursor = start + 1;
        while (cursor < text.length() && text.charAt(cursor) == '=') cursor++;
        return cursor < text.length() && text.charAt(cursor) == '[' ? cursor - start - 1 : -1;
    }
    private static int findLongClose(String text, int from, int equals) {
        StringBuilder close = new StringBuilder("]");
        for (int i = 0; i < equals; i++) close.append('=');
        close.append(']');
        return text.indexOf(close.toString(), from);
    }
    private static int countLines(String text, int from, int to) {
        int result = 0;
        for (int i = from; i < to; i++) if (text.charAt(i) == '\n') result++;
        return result;
    }
}
