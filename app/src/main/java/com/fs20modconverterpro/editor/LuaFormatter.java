package com.fs20modconverterpro.editor;

import java.util.ArrayList;
import java.util.List;

/** Indentador de blocos Lua sem reescrever tokens ou executar o código. */
public final class LuaFormatter {
    private static final int MAX_INDENT_DEPTH = 128;
    private final LuaSyntaxAnalyzer analyzer = new LuaSyntaxAnalyzer();

    public String format(String source, int spacesPerIndent) {
        if (source == null || source.isEmpty()) return source == null ? "" : source;
        int lineCount = 1;
        for (int i = 0; i < source.length(); i++) {
            if (source.charAt(i) == '\n' && ++lineCount > LuaDocumentCodec.MAX_LINES) return source;
        }
        int indentSize = Math.max(1, Math.min(8, spacesPerIndent));
        String[] lines = source.split("\n", -1);
        LuaSyntaxAnalyzer.Result analysis = analyzer.analyze(source);
        for (LuaSyntaxAnalyzer.Diagnostic diagnostic : analysis.getDiagnostics()) {
            if (diagnostic.getMessage().contains("limite de tokens")) return source;
        }
        List<List<LuaSyntaxAnalyzer.Token>> byLine = new ArrayList<>();
        for (int line = 0; line <= lines.length; line++) byLine.add(new ArrayList<>());
        for (LuaSyntaxAnalyzer.Token token : analysis.getTokens()) {
            if (token.getLine() < byLine.size()) byLine.get(token.getLine()).add(token);
        }
        StringBuilder result = new StringBuilder(source.length() + lines.length * indentSize);
        int depth = 0;
        int pendingIf = 0;
        boolean branchThen = false;
        for (int index = 0; index < lines.length; index++) {
            String trimmed = lines[index].trim();
            List<LuaSyntaxAnalyzer.Token> tokens = byLine.get(index + 1);
            boolean closesAtStart = !tokens.isEmpty() && isDedentWord(tokens.get(0).getText());
            int visualDepth = closesAtStart ? Math.max(0, depth - 1) : depth;
            if (!trimmed.isEmpty()) {
                for (int space = 0; space < Math.min(visualDepth, MAX_INDENT_DEPTH) * indentSize; space++) {
                    result.append(' ');
                }
                result.append(trimmed);
            }
            for (LuaSyntaxAnalyzer.Token token : tokens) {
                String word = token.getText();
                if ("if".equals(word)) pendingIf++;
                else if ("elseif".equals(word)) branchThen = true;
                else if ("then".equals(word)) {
                    if (branchThen) branchThen = false;
                    else if (pendingIf > 0) { pendingIf--; depth++; }
                } else if ("function".equals(word) || "do".equals(word) || "repeat".equals(word)) {
                    depth = Math.min(MAX_INDENT_DEPTH, depth + 1);
                } else if ("end".equals(word) || "until".equals(word)) {
                    depth = Math.max(0, depth - 1);
                }
            }
            if (index + 1 < lines.length) result.append('\n');
        }
        return result.toString();
    }

    private static boolean isDedentWord(String word) {
        return "end".equals(word) || "until".equals(word) || "else".equals(word) || "elseif".equals(word);
    }
}
