package com.fs20modconverterpro.editor;

import android.text.Editable;
import android.text.style.ForegroundColorSpan;

import java.util.List;

/** Aplica cores às faixas reconhecidas pelo analisador léxico XML. */
public final class XmlSyntaxHighlighter {
    private final XmlSyntaxLexer lexer = new XmlSyntaxLexer();
    private final int tagColor;
    private final int attributeColor;
    private final int valueColor;
    private final int commentColor;
    private final int declarationColor;
    private final int entityColor;
    private final int delimiterColor;

    public XmlSyntaxHighlighter(
            int tagColor,
            int attributeColor,
            int valueColor,
            int commentColor,
            int declarationColor,
            int entityColor,
            int delimiterColor) {
        this.tagColor = tagColor;
        this.attributeColor = attributeColor;
        this.valueColor = valueColor;
        this.commentColor = commentColor;
        this.declarationColor = declarationColor;
        this.entityColor = entityColor;
        this.delimiterColor = delimiterColor;
    }

    public void apply(Editable text) {
        ForegroundColorSpan[] existing = text.getSpans(0, text.length(), ForegroundColorSpan.class);
        for (ForegroundColorSpan span : existing) {
            text.removeSpan(span);
        }
        List<XmlSyntaxLexer.Token> tokens = lexer.tokenize(text.toString());
        for (XmlSyntaxLexer.Token token : tokens) {
            int color = colorFor(token.getType());
            text.setSpan(
                    new ForegroundColorSpan(color),
                    token.getStart(),
                    token.getEnd(),
                    Editable.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
    }

    private int colorFor(XmlSyntaxLexer.Type type) {
        switch (type) {
            case TAG:
                return tagColor;
            case ATTRIBUTE:
                return attributeColor;
            case VALUE:
                return valueColor;
            case COMMENT:
            case CDATA:
                return commentColor;
            case PROCESSING:
                return declarationColor;
            case ENTITY:
                return entityColor;
            case DELIMITER:
            default:
                return delimiterColor;
        }
    }
}
