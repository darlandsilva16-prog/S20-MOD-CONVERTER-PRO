package com.fs20modconverterpro.editor;

import android.text.Editable;
import android.text.style.ForegroundColorSpan;

/** Realce de sintaxe Lua em cores distintas para cada grupo léxico. */
public final class LuaSyntaxHighlighter {
    private final LuaSyntaxAnalyzer analyzer = new LuaSyntaxAnalyzer();
    private final int keywordColor, functionColor, commentColor, stringColor, numberColor, identifierColor;

    public LuaSyntaxHighlighter(int keywordColor, int functionColor, int commentColor,
                                int stringColor, int numberColor, int identifierColor) {
        this.keywordColor = keywordColor;
        this.functionColor = functionColor;
        this.commentColor = commentColor;
        this.stringColor = stringColor;
        this.numberColor = numberColor;
        this.identifierColor = identifierColor;
    }

    public void apply(Editable text) {
        for (ForegroundColorSpan span : text.getSpans(0, text.length(), ForegroundColorSpan.class)) {
            text.removeSpan(span);
        }
        for (LuaSyntaxAnalyzer.Token token : analyzer.analyze(text.toString()).getTokens()) {
            int color;
            switch (token.getType()) {
                case KEYWORD: color = keywordColor; break;
                case FUNCTION: color = functionColor; break;
                case COMMENT: color = commentColor; break;
                case STRING: color = stringColor; break;
                case NUMBER: color = numberColor; break;
                case IDENTIFIER:
                case OPERATOR:
                default: color = identifierColor;
            }
            text.setSpan(new ForegroundColorSpan(color), token.getStart(), token.getEnd(),
                    Editable.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
    }
}
