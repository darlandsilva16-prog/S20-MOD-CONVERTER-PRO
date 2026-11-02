package com.fs20modconverterpro.editor;

import org.w3c.dom.Document;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Serializador XML identado que preserva texto misto e espaços xml:space="preserve". */
final class XmlSerializer {
    private static final Pattern XML_DECLARATION = Pattern.compile(
            "^\\s*(<\\?xml\\s+[^?]*\\?>)", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final String INDENT = "  ";

    private XmlSerializer() {
    }

    static String format(Document document, String original) throws XmlProcessor.XmlProcessingException {
        StringBuilder output = new StringBuilder();
        Matcher declaration = XML_DECLARATION.matcher(original);
        if (declaration.find()) {
            output.append(declaration.group(1)).append('\n');
        }

        List<Node> topLevelNodes = new ArrayList<>();
        for (Node child = document.getFirstChild(); child != null; child = child.getNextSibling()) {
            if (child.getNodeType() == Node.DOCUMENT_TYPE_NODE) {
                throw new XmlProcessor.XmlProcessingException("DTD e entidades externas não são permitidos.");
            }
            if (child.getNodeType() == Node.TEXT_NODE && child.getNodeValue().trim().isEmpty()) {
                continue;
            }
            topLevelNodes.add(child);
        }

        for (int index = 0; index < topLevelNodes.size(); index++) {
            if (output.length() > 0 && output.charAt(output.length() - 1) != '\n') {
                output.append('\n');
            }
            writeNode(topLevelNodes.get(index), 0, false, output);
            if (index + 1 < topLevelNodes.size()) {
                output.append('\n');
            }
        }
        if (output.length() > XmlDocumentCodec.MAX_XML_CHARACTERS) {
            throw new XmlProcessor.XmlProcessingException("A formatação excedeu o limite do editor.");
        }
        return output.toString();
    }

    private static void writeNode(Node node, int depth, boolean inheritedPreserve,
                                  StringBuilder output) throws XmlProcessor.XmlProcessingException {
        switch (node.getNodeType()) {
            case Node.ELEMENT_NODE:
                writeElement(node, depth, inheritedPreserve, output);
                break;
            case Node.TEXT_NODE:
                appendEscapedText(node.getNodeValue(), output);
                break;
            case Node.CDATA_SECTION_NODE:
                output.append("<![CDATA[").append(node.getNodeValue()).append("]]>");
                break;
            case Node.COMMENT_NODE:
                output.append("<!--").append(node.getNodeValue()).append("-->");
                break;
            case Node.PROCESSING_INSTRUCTION_NODE:
                output.append("<?").append(node.getNodeName());
                if (node.getNodeValue() != null && !node.getNodeValue().isEmpty()) {
                    output.append(' ').append(node.getNodeValue());
                }
                output.append("?>");
                break;
            case Node.DOCUMENT_TYPE_NODE:
                throw new XmlProcessor.XmlProcessingException("DTD e entidades externas não são permitidos.");
            default:
                break;
        }
    }

    private static void writeElement(Node element, int depth, boolean inheritedPreserve,
                                     StringBuilder output) throws XmlProcessor.XmlProcessingException {
        boolean preserve = spacePreserved(element, inheritedPreserve);
        List<Node> children = new ArrayList<>();
        boolean hasElement = false;
        boolean hasText = false;
        for (Node child = element.getFirstChild(); child != null; child = child.getNextSibling()) {
            children.add(child);
            if (child.getNodeType() == Node.ELEMENT_NODE) {
                hasElement = true;
            } else if (child.getNodeType() == Node.TEXT_NODE
                    && !child.getNodeValue().isEmpty()) {
                hasText = true;
            }
        }
        boolean elementOnlyContent = hasElement && !hasText && !preserve;

        output.append('<').append(element.getNodeName());
        appendAttributes(element.getAttributes(), output);
        if (children.isEmpty()) {
            output.append("/>");
            return;
        }
        output.append('>');

        if (elementOnlyContent) {
            for (Node child : children) {
                output.append('\n');
                appendIndent(depth + 1, output);
                writeNode(child, depth + 1, false, output);
            }
            output.append('\n');
            appendIndent(depth, output);
        } else {
            boolean preserveChildren = preserve || (hasElement && hasText);
            for (Node child : children) {
                writeNode(child, depth + 1, preserveChildren, output);
            }
        }
        output.append("</").append(element.getNodeName()).append('>');
    }

    private static boolean spacePreserved(Node element, boolean inherited) {
        NamedNodeMap attributes = element.getAttributes();
        Node space = attributes.getNamedItemNS("http://www.w3.org/XML/1998/namespace", "space");
        if (space == null) {
            return inherited;
        }
        if ("preserve".equals(space.getNodeValue())) {
            return true;
        }
        if ("default".equals(space.getNodeValue())) {
            return false;
        }
        return inherited;
    }

    private static void appendAttributes(NamedNodeMap attributes, StringBuilder output) {
        for (int index = 0; index < attributes.getLength(); index++) {
            Node attribute = attributes.item(index);
            output.append(' ').append(attribute.getNodeName()).append("=\"");
            appendEscapedAttribute(attribute.getNodeValue(), output);
            output.append('"');
        }
    }

    private static void appendEscapedText(String text, StringBuilder output) {
        for (int index = 0; index < text.length(); index++) {
            char character = text.charAt(index);
            switch (character) {
                case '&':
                    output.append("&amp;");
                    break;
                case '<':
                    output.append("&lt;");
                    break;
                case '>':
                    output.append("&gt;");
                    break;
                case '\r':
                    output.append("&#13;");
                    break;
                default:
                    output.append(character);
            }
        }
    }

    private static void appendEscapedAttribute(String text, StringBuilder output) {
        for (int index = 0; index < text.length(); index++) {
            char character = text.charAt(index);
            switch (character) {
                case '&':
                    output.append("&amp;");
                    break;
                case '<':
                    output.append("&lt;");
                    break;
                case '"':
                    output.append("&quot;");
                    break;
                case '\t':
                    output.append("&#9;");
                    break;
                case '\n':
                    output.append("&#10;");
                    break;
                case '\r':
                    output.append("&#13;");
                    break;
                default:
                    output.append(character);
            }
        }
    }

    private static void appendIndent(int depth, StringBuilder output) {
        for (int index = 0; index < depth; index++) {
            output.append(INDENT);
        }
    }
}
