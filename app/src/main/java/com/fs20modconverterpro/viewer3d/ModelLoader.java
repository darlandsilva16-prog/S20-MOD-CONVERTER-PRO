package com.fs20modconverterpro.viewer3d;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.StringReader;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Leitores OBJ/STL; os resultados são triângulos reais enviados ao renderizador GLES. */
public final class ModelLoader {
    public static final int MAX_MODEL_BYTES = 32 * 1024 * 1024;
    private static final int MAX_SOURCE_VERTICES = 200_000;
    private static final float[] DEFAULT_COLOR = {0.66f, 0.72f, 0.62f, 1f};
    private final GltfModelLoader gltfLoader = new GltfModelLoader();

    public ModelLoadResult load(String filename, byte[] bytes) throws ModelFormatException {
        if (bytes == null || bytes.length == 0 || bytes.length > MAX_MODEL_BYTES)
            throw new ModelFormatException("O modelo está vazio ou excede o limite de 32 MiB.");
        ModelFormat format = ModelFormat.fromName(filename);
        switch (format) {
            case OBJ: return loadObj(bytes);
            case STL: return loadStl(bytes);
            case GLB: return gltfLoader.loadGlb(bytes);
            case GLTF: return gltfLoader.loadGltf(bytes);
            default: throw new ModelFormatException(format == ModelFormat.I3D
                    ? "I3D é analisado como XML; renderização completa dos recursos proprietários não está implementada."
                    : "Formato reconhecido apenas para consulta ou ainda não suportado para visualização.");
        }
    }

    private ModelLoadResult loadObj(byte[] bytes) throws ModelFormatException {
        String text = decodeUtf8(bytes);
        MeshData.Builder mesh = new MeshData.Builder();
        ArrayList<float[]> vertices = new ArrayList<>();
        ArrayList<String> warnings = new ArrayList<>();
        int faceTriangles = 0;
        try (BufferedReader reader = new BufferedReader(new StringReader(text))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String trimmed = line.trim();
                if (trimmed.isEmpty() || trimmed.startsWith("#")) continue;
                String[] parts = trimmed.split("\\s+");
                if ("v".equals(parts[0])) {
                    if (parts.length < 4) throw new ModelFormatException("Linha OBJ com vértice incompleto.");
                    if (vertices.size() >= MAX_SOURCE_VERTICES) throw new ModelFormatException("OBJ ultrapassa 200 mil vértices de origem.");
                    vertices.add(new float[]{parseFloat(parts[1]), parseFloat(parts[2]), parseFloat(parts[3])});
                } else if ("f".equals(parts[0])) {
                    if (parts.length < 4) throw new ModelFormatException("Face OBJ tem menos de três vértices.");
                    int first = objVertexIndex(parts[1], vertices.size());
                    int previous = objVertexIndex(parts[2], vertices.size());
                    for (int i = 3; i < parts.length; i++) {
                        int current = objVertexIndex(parts[i], vertices.size());
                        mesh.addTriangle(vertices.get(first), vertices.get(previous), vertices.get(current), DEFAULT_COLOR);
                        faceTriangles++;
                        previous = current;
                    }
                } else if ("usemtl".equals(parts[0]) && parts.length > 1) {
                    mesh.addMaterial(trimmed.substring("usemtl".length()).trim());
                } else if ("mtllib".equals(parts[0])) {
                    warnings.add("Arquivo(s) MTL/material textual referenciado(s), mas as bibliotecas externas não são carregadas.");
                }
            }
        } catch (IOException e) {
            throw new ModelFormatException("Não foi possível ler o texto OBJ.", e);
        }
        mesh.setSourceVertices(vertices.size());
        MeshData result = mesh.build();
        if (faceTriangles == 0) throw new ModelFormatException("OBJ sem faces triangulares para renderizar.");
        return new ModelLoadResult(ModelFormat.OBJ, result, "Malha OBJ renderizada com iluminação básica.", warnings);
    }

    private ModelLoadResult loadStl(byte[] bytes) throws ModelFormatException {
        long triangleCount = bytes.length >= 84 ? u32(bytes, 80) : -1;
        long binaryLength = triangleCount < 0 ? Long.MAX_VALUE : 84L + triangleCount * 50L;
        boolean binary = bytes.length >= 84 && binaryLength <= bytes.length
                && (binaryLength == bytes.length || !asciiPrefix(bytes, "solid"));
        return binary ? loadBinaryStl(bytes, (int) triangleCount) : loadAsciiStl(bytes);
    }

    private ModelLoadResult loadBinaryStl(byte[] bytes, int count) throws ModelFormatException {
        if (count < 1 || count > MeshData.MAX_TRIANGLES || 84L + count * 50L > bytes.length)
            throw new ModelFormatException("Quantidade de triângulos STL binário inválida ou acima do limite.");
        MeshData.Builder mesh = new MeshData.Builder();
        ByteBuffer data = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        int offset = 84;
        for (int triangle = 0; triangle < count; triangle++) {
            offset += 12; // normal gravada; a normal da face é recalculada para iluminação.
            float[] a = readStlVertex(data, offset); offset += 12;
            float[] b = readStlVertex(data, offset); offset += 12;
            float[] c = readStlVertex(data, offset); offset += 12;
            mesh.addTriangle(a, b, c, DEFAULT_COLOR);
            offset += 2;
        }
        mesh.setSourceVertices(count * 3);
        return new ModelLoadResult(ModelFormat.STL, mesh.build(), "STL binário renderizado com iluminação básica.", Collections.emptyList());
    }

    private ModelLoadResult loadAsciiStl(byte[] bytes) throws ModelFormatException {
        String text = decodeUtf8(bytes);
        MeshData.Builder mesh = new MeshData.Builder();
        float[][] pending = new float[3][];
        int vertex = 0, triangles = 0;
        try (BufferedReader reader = new BufferedReader(new StringReader(text))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String trimmed = line.trim();
                if (!trimmed.regionMatches(true, 0, "vertex", 0, 6)) continue;
                String[] parts = trimmed.split("\\s+");
                if (parts.length != 4) throw new ModelFormatException("Vértice ASCII STL inválido.");
                pending[vertex++] = new float[]{parseFloat(parts[1]), parseFloat(parts[2]), parseFloat(parts[3])};
                if (vertex == 3) {
                    mesh.addTriangle(pending[0], pending[1], pending[2], DEFAULT_COLOR);
                    triangles++; vertex = 0;
                }
            }
        } catch (IOException e) {
            throw new ModelFormatException("Não foi possível ler o texto STL.", e);
        }
        if (vertex != 0 || triangles == 0) throw new ModelFormatException("STL ASCII inválido ou sem triângulos completos.");
        mesh.setSourceVertices(triangles * 3);
        return new ModelLoadResult(ModelFormat.STL, mesh.build(), "STL ASCII renderizado com iluminação básica.", Collections.emptyList());
    }

    private static float[] readStlVertex(ByteBuffer data, int offset) throws ModelFormatException {
        if (offset < 0 || offset + 12 > data.limit()) throw new ModelFormatException("Triângulo STL truncado.");
        float[] result = {data.getFloat(offset), data.getFloat(offset + 4), data.getFloat(offset + 8)};
        if (!Float.isFinite(result[0]) || !Float.isFinite(result[1]) || !Float.isFinite(result[2]))
            throw new ModelFormatException("STL contém coordenadas inválidas.");
        return result;
    }
    private static int objVertexIndex(String vertex, int count) throws ModelFormatException {
        String indexText = vertex.split("/", -1)[0];
        try {
            int index = Integer.parseInt(indexText);
            int absolute = index > 0 ? index - 1 : count + index;
            if (absolute < 0 || absolute >= count) throw new ModelFormatException("Índice de vértice OBJ fora do intervalo.");
            return absolute;
        } catch (NumberFormatException e) { throw new ModelFormatException("Índice de face OBJ inválido.", e); }
    }
    static float parseFloat(String text) throws ModelFormatException {
        try {
            float value = Float.parseFloat(text);
            if (!Float.isFinite(value)) throw new NumberFormatException();
            return value;
        } catch (NumberFormatException e) { throw new ModelFormatException("Coordenada numérica inválida no modelo.", e); }
    }
    static String decodeUtf8(byte[] bytes) throws ModelFormatException {
        try {
            return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException e) { throw new ModelFormatException("O arquivo texto não está em UTF-8 válido.", e); }
    }
    private static long u32(byte[] bytes, int p) {
        return bytes[p] & 255L | (bytes[p + 1] & 255L) << 8 | (bytes[p + 2] & 255L) << 16 | (bytes[p + 3] & 255L) << 24;
    }
    private static boolean asciiPrefix(byte[] bytes, String prefix) {
        if (bytes.length < prefix.length()) return false;
        for (int i = 0; i < prefix.length(); i++) if (Character.toLowerCase((char) bytes[i]) != prefix.charAt(i)) return false;
        return true;
    }
}
