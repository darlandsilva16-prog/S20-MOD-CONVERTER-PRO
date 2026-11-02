package com.fs20modconverterpro.editor;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Divide XML em faixas de sintaxe sem alterar o texto editado. */
public final class XmlSyntaxLexer {
    public enum Type {
        DELIMITER,
        TAG,
        ATTRIBUTE,
        VALUE,
        COMMENT,
        CDATA,
        PROCESSING,
        ENTITY
    }

    public static final class Token {
        private final int start;
        private final int end;
        private final Type type;

        Token(int start, int end, Type type) {
            this.start = start;
            this.end = end;
            this.type = type;
        }

        public int getStart() {
            return start;
        }

        public int getEnd() {
            return end;
        }

        public Type getType() {
            return type;
        }
    }

    public List<Token> tokenize(String xml) {
        if (xml == null || xml.isEmpty()) {
            return Collections.emptyList();
        }
        List<Token> tokens = new ArrayList<>();
        int position = 0;
        while (position < xml.length()) {
            if (xml.startsWith("<!--", position)) {
                int end = findEnd(xml, "-->", position + 4);
                add(tokens, position, end, Type.COMMENT);
                position = end;
            } else if (xml.startsWith("<![CDATA[", position)) {
                int end = findEnd(xml, "]]>", position + 9);
                add(tokens, position, end, Type.CDATA);
                position = end;
            } else if (xml.startsWith("<?", position)) {
                int end = findEnd(xml, "?>", position + 2);
                add(tokens, position, end, Type.PROCESSING);
                position = end;
            } else if (xml.charAt(position) == '<') {
                position = tokenizeTag(xml, position, tokens);
            } else if (xml.charAt(position) == '&') {
                int end = entityEnd(xml, position);
                if (end > position) {
                    add(tokens, position, end, Type.ENTITY);
                    position = end;
                } else {
                    position++;
                }
            } else {
                position++;
            }
        }
        return tokens;
    }

    private static int tokenizeTag(String xml, int start, List<Token> tokens) {
        int position = start + 1;
        add(tokens, start, position, Type.DELIMITER);
        if (position < xml.length() && xml.charAt(position) == '/') {
            add(tokens, position, position + 1, Type.DELIMITER);
            position++;
        }

        int nameStart = position;
        while (position < xml.length() && !isTagBoundary(xml.charAt(position))) {
            position++;
        }
        if (position > nameStart) {
            add(tokens, nameStart, position, Type.TAG);
        }

        while (position < xml.length()) {
            char current = xml.charAt(position);
            if (current == '>') {
                add(tokens, position, position + 1, Type.DELIMITER);
                return position + 1;
            }
            if (current == '/') {
                add(tokens, position, position + 1, Type.DELIMITER);
                position++;
                continue;
            }
            if (Character.isWhitespace(current)) {
                position++;
                continue;
            }

            int attributeStart = position;
            while (position < xml.length() && !isAttributeBoundary(xml.charAt(position))) {
                position++;
            }
            if (position == attributeStart) {
                position++;
                continue;
            }
            add(tokens, attributeStart, position, Type.ATTRIBUTE);
            while (position < xml.length() && Character.isWhitespace(xml.charAt(position))) {
                position++;
            }
            if (position >= xml.length() || xml.charAt(position) != '=') {
                continue;
            }
            add(tokens, position, position + 1, Type.DELIMITER);
            position++;
            while (position < xml.length() && Character.isWhitespace(xml.charAt(position))) {
                position++;
            }
            if (position >= xml.length()) {
                break;
            }
            char quote = xml.charAt(position);
            if (quote == '\'' || quote == '"') {
                int valueStart = position;
                position++;
                int contentStart = position;
                while (position < xml.length() && xml.charAt(position) != quote) {
                    position++;
                }
                int contentEnd = position;
                if (position < xml.length()) {
                    position++;
                }
                add(tokens, valueStart, position, Type.VALUE);
                addEntities(xml, contentStart, contentEnd, tokens);
            } else {
                int valueStart = position;
                while (position < xml.length() && !isAttributeBoundary(xml.charAt(position))) {
                    position++;
                }
                add(tokens, valueStart, position, Type.VALUE);
                addEntities(xml, valueStart, position, tokens);
            }
        }
        return position;
    }

    private static void addEntities(String xml, int start, int end, List<Token> tokens) {
        int position = start;
        while (position < end) {
            if (xml.charAt(position) == '&') {
                int entityEnd = entityEnd(xml, position);
                if (entityEnd > position && entityEnd <= end) {
                    add(tokens, position, entityEnd, Type.ENTITY);
                    position = entityEnd;
                    continue;
                }
            }
            position++;
        }
    }

    private static int entityEnd(String xml, int start) {
        int semicolon = xml.indexOf(';', start + 1);
        if (semicolon < 0 || semicolon - start > 32) {
            return -1;
        }
        for (int index = start + 1; index < semicolon; index++) {
            char character = xml.charAt(index);
            if (Character.isWhitespace(character) || character == '<' || character == '&') {
                return -1;
            }
        }
        return semicolon + 1;
    }

    private static int findEnd(String xml, String terminator, int start) {
        int index = xml.indexOf(terminator, start);
        return index < 0 ? xml.length() : index + terminator.length();
    }

    private static boolean isTagBoundary(char character) {
        return Character.isWhitespace(character) || character == '/' || character == '>'
                || character == '?' || character == '=';
    }

    private static boolean isAttributeBoundary(char character) {
        return Character.isWhitespace(character) || character == '=' || character == '/'
                || character == '>';
    }

    private static void add(List<Token> tokens, int start, int end, Type type) {
        if (end > start) {
            tokens.add(new Token(start, end, type));
        }
    }
}
