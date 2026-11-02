package com.fs20modconverterpro.viewer3d;

import org.xml.sax.Attributes;
import org.xml.sax.helpers.DefaultHandler;

import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import javax.xml.parsers.SAXParserFactory;

/** Análise estrutural I3D em modo somente leitura; não renderiza recursos proprietários. */
public final class I3dAnalyzer {
    public static final int MAX_BYTES = 16 * 1024 * 1024;
    private static final int MAX_REFS = 10000;

    public Report analyze(byte[] xml, Set<String> archivePaths) throws Exception {
        if (xml == null || xml.length == 0 || xml.length > MAX_BYTES) throw new IllegalArgumentException("I3D vazio ou acima do limite de 16 MiB.");
        Handler handler = new Handler();
        SAXParserFactory factory = SAXParserFactory.newInstance();
        factory.setNamespaceAware(false);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
        javax.xml.parsers.SAXParser parser = factory.newSAXParser();
        parser.setProperty(javax.xml.XMLConstants.ACCESS_EXTERNAL_DTD, "");
        parser.setProperty(javax.xml.XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        parser.parse(new ByteArrayInputStream(xml), handler);
        ArrayList<String> missing = new ArrayList<>();
        if (archivePaths != null) for (String ref : handler.textures) {
            String normalized = ref.replace('\\', '/');
            if (!archivePaths.contains(normalized) && !archivePaths.contains(baseName(normalized))) missing.add(ref);
        }
        return new Report(handler.nodes, handler.transforms, handler.materials, handler.models,
                handler.textures, missing, archivePaths == null);
    }

    private static String baseName(String value) { int i = value.lastIndexOf('/'); return i < 0 ? value : value.substring(i + 1); }

    private static final class Handler extends DefaultHandler {
        int nodes, transforms, materials, models;
        final List<String> textures = new ArrayList<>();
        @Override public void startElement(String uri, String local, String qName, Attributes a) {
            String tag = qName.toLowerCase(Locale.ROOT);
            if (tag.equals("node")) nodes++;
            if (tag.equals("transform") || tag.startsWith("transformgroup")) transforms++;
            if (tag.equals("material")) materials++;
            if (tag.equals("shape") || tag.equals("model")) models++;
            for (int i = 0; i < a.getLength() && textures.size() < MAX_REFS; i++) {
                String name = a.getQName(i).toLowerCase(Locale.ROOT);
                String value = a.getValue(i).trim();
                if (!value.isEmpty() && (name.contains("texture") || name.equals("normalmap") || name.equals("filename"))) {
                    String lower = value.toLowerCase(Locale.ROOT);
                    if (lower.endsWith(".png") || lower.endsWith(".dds") || lower.endsWith(".jpg") || lower.endsWith(".jpeg") || lower.endsWith(".tga") || lower.endsWith(".bmp")) {
                        if (!textures.contains(value)) textures.add(value);
                    }
                }
            }
        }
    }

    public static final class Report {
        public final int nodes, transforms, materials, models;
        public final List<String> textureReferences, missingTextures;
        public final boolean referencesUnverified;
        Report(int n,int t,int m,int mo,List<String> refs,List<String> missing,boolean unverified) {
            nodes=n; transforms=t; materials=m; models=mo;
            textureReferences=Collections.unmodifiableList(new ArrayList<>(refs));
            missingTextures=Collections.unmodifiableList(missing); referencesUnverified=unverified;
        }
        public String summary() {
            return "Análise I3D somente leitura\nNós: " + nodes + "\nTransformações: " + transforms + "\nReferências de modelo/forma: " + models + "\nMateriais: " + materials +
                    "\nTexturas referenciadas: " + textureReferences.size() + (referencesUnverified ? "\nExistência de referências não verificada fora de um ZIP." : "\nReferências ausentes: " + missingTextures.size()) +
                    "\n\nRenderização completa de I3D não está implementada; o formato contém recursos específicos do jogo.";
        }
    }
}
