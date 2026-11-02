package com.fs20modconverterpro.editor;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public final class LuaFormatterTest {
    @Test public void indentaBlocosMantendoTokensEComentarios() {
        String source = "if ok then\nprint('end')\nelse\nprint(2)\nend -- fim\n";
        String expected = "if ok then\n    print('end')\nelse\n    print(2)\nend -- fim\n";
        assertEquals(expected, new LuaFormatter().format(source, 4));
    }

    @Test public void indentaFuncaoERepeat() {
        String source = "function f()\nrepeat\nx=x+1\nuntil x>2\nend";
        String expected = "function f()\n    repeat\n        x=x+1\n    until x>2\nend";
        assertEquals(expected, new LuaFormatter().format(source, 4));
    }
}
