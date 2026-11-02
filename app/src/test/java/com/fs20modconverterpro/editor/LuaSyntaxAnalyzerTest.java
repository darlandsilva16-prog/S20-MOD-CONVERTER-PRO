package com.fs20modconverterpro.editor;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class LuaSyntaxAnalyzerTest {
    private final LuaSyntaxAnalyzer analyzer = new LuaSyntaxAnalyzer();

    @Test public void aceitaEstruturaBasicaEIgnoraPalavrasEmComentariosStrings() {
        LuaSyntaxAnalyzer.Result result = analyzer.analyze(
                "-- if broken\nlocal texto = 'end ( não fecha'\nif true then\n print(texto)\nend");
        assertTrue(result.isValid());
    }

    @Test public void apontaDelimitadorSemParNaLinha() {
        LuaSyntaxAnalyzer.Result result = analyzer.analyze("local x = (1 + 2\nprint(x)");
        assertFalse(result.isValid());
        assertEquals(1, result.getDiagnostics().get(0).getLine());
    }

    @Test public void identificaBlocoNaoFechado() {
        LuaSyntaxAnalyzer.Result result = analyzer.analyze("if pronto then\n  return 1");
        assertFalse(result.isValid());
        assertTrue(result.getDiagnostics().get(0).getMessage().contains("não foi fechado"));
    }

    @Test public void detectaElseSemIf() {
        LuaSyntaxAnalyzer.Result result = analyzer.analyze("else\nprint('x')\nend");
        assertFalse(result.isValid());
        assertTrue(result.getDiagnostics().get(0).getMessage().contains("if"));
    }

    @Test public void identificaAspaEComentarioLongoAbertos() {
        assertFalse(analyzer.analyze("local x = 'aberto").isValid());
        assertFalse(analyzer.analyze("--[[ comentário").isValid());
    }

    @Test public void reconheceTokensLuaPrincipais() {
        LuaSyntaxAnalyzer.Result result = analyzer.analyze("local function soma(a) return a + 10 end -- fim");
        assertEquals(LuaSyntaxAnalyzer.Type.KEYWORD, result.getTokens().get(0).getType());
        assertEquals(LuaSyntaxAnalyzer.Type.FUNCTION, result.getTokens().get(1).getType());
        assertEquals(LuaSyntaxAnalyzer.Type.FUNCTION, result.getTokens().get(2).getType());
        assertTrue(result.isValid());
    }

    @Test public void preservaLinhaAposComentarioLongo() {
        LuaSyntaxAnalyzer.Result result = analyzer.analyze("--[[ comentário\nem outra linha ]]\nlocal valor = 1");
        LuaSyntaxAnalyzer.Token local = result.getTokens().get(1);
        assertEquals("local", local.getText());
        assertEquals(3, local.getLine());
    }

    @Test public void limitaQuantidadeDeTokensEmEntradasHostis() {
        StringBuilder source = new StringBuilder();
        for (int i = 0; i < 50_010; i++) source.append('+');
        assertFalse(analyzer.analyze(source.toString()).isValid());
    }
}
