package com.fs20modconverterpro.editor;

import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.EnumSet;

public final class XmlSyntaxLexerTest {
    @Test
    public void reconheceTagsAtributosValoresEntidadesEComentarios() {
        String xml = "<vehicle type=\"tractor &amp; trailer\"><!-- nota --><![CDATA[x]]></vehicle>";
        EnumSet<XmlSyntaxLexer.Type> found = EnumSet.noneOf(XmlSyntaxLexer.Type.class);
        for (XmlSyntaxLexer.Token token : new XmlSyntaxLexer().tokenize(xml)) {
            found.add(token.getType());
        }

        assertTrue(found.contains(XmlSyntaxLexer.Type.TAG));
        assertTrue(found.contains(XmlSyntaxLexer.Type.ATTRIBUTE));
        assertTrue(found.contains(XmlSyntaxLexer.Type.VALUE));
        assertTrue(found.contains(XmlSyntaxLexer.Type.ENTITY));
        assertTrue(found.contains(XmlSyntaxLexer.Type.COMMENT));
        assertTrue(found.contains(XmlSyntaxLexer.Type.CDATA));
    }
}
