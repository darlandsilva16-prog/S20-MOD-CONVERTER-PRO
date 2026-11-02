package com.fs20modconverterpro.viewer3d;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;

/** Malha triangular real, em coordenadas do modelo, limitada para renderização móvel. */
public final class MeshData {
    public static final int MAX_TRIANGLES = 150_000;
    private final float[] positions, normals, colors;
    private final int sourceVertices, triangles;
    private final float[] minimum, maximum;
    private final List<String> materials, textures, warnings;

    private MeshData(Builder builder) {
        positions = toArray(builder.positions);
        normals = toArray(builder.normals);
        colors = toArray(builder.colors);
        sourceVertices = builder.sourceVertices;
        triangles = positions.length / 9;
        minimum = builder.minimum.clone();
        maximum = builder.maximum.clone();
        materials = Collections.unmodifiableList(new ArrayList<>(builder.materials));
        textures = Collections.unmodifiableList(new ArrayList<>(builder.textures));
        warnings = Collections.unmodifiableList(new ArrayList<>(builder.warnings));
    }
    public float[] getPositions() { return positions; }
    public float[] getNormals() { return normals; }
    public float[] getColors() { return colors; }
    public int getSourceVertices() { return sourceVertices; }
    public int getTriangleCount() { return triangles; }
    public int getRenderedVertexCount() { return triangles * 3; }
    public float[] getMinimum() { return minimum.clone(); }
    public float[] getMaximum() { return maximum.clone(); }
    public List<String> getMaterials() { return materials; }
    public List<String> getTextures() { return textures; }
    public List<String> getWarnings() { return warnings; }

    private static float[] toArray(FloatArray values) { return values.toArray(); }

    public static final class Builder {
        private final FloatArray positions = new FloatArray();
        private final FloatArray normals = new FloatArray();
        private final FloatArray colors = new FloatArray();
        private final float[] minimum = {Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY};
        private final float[] maximum = {Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY};
        private final LinkedHashSet<String> materials = new LinkedHashSet<>();
        private final LinkedHashSet<String> textures = new LinkedHashSet<>();
        private final LinkedHashSet<String> warnings = new LinkedHashSet<>();
        private int sourceVertices;

        public void setSourceVertices(int count) { sourceVertices = count; }
        public void addMaterial(String value) { if (value != null && !value.isEmpty()) materials.add(value); }
        public void addTexture(String value) { if (value != null && !value.isEmpty()) textures.add(value); }
        public void addWarning(String value) { if (value != null && !value.isEmpty()) warnings.add(value); }
        public int getTriangleCount() { return positions.size() / 9; }

        public void addTriangle(float[] a, float[] b, float[] c, float[] color) throws ModelFormatException {
            if (getTriangleCount() >= MAX_TRIANGLES) throw new ModelFormatException("O modelo ultrapassa o limite de 150 mil faces.");
            if (!finite(a) || !finite(b) || !finite(c)) throw new ModelFormatException("O modelo contém coordenadas inválidas.");
            float ux = b[0] - a[0], uy = b[1] - a[1], uz = b[2] - a[2];
            float vx = c[0] - a[0], vy = c[1] - a[1], vz = c[2] - a[2];
            float nx = uy * vz - uz * vy, ny = uz * vx - ux * vz, nz = ux * vy - uy * vx;
            float length = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
            if (length > 0.000001f) { nx /= length; ny /= length; nz /= length; }
            else { nx = 0; ny = 1; nz = 0; }
            float[][] vertices = {a, b, c};
            for (float[] vertex : vertices) {
                for (int axis = 0; axis < 3; axis++) {
                    positions.add(vertex[axis]);
                    minimum[axis] = Math.min(minimum[axis], vertex[axis]);
                    maximum[axis] = Math.max(maximum[axis], vertex[axis]);
                }
                normals.add(nx); normals.add(ny); normals.add(nz);
                colors.add(color[0]); colors.add(color[1]); colors.add(color[2]); colors.add(color[3]);
            }
        }

        public MeshData build() throws ModelFormatException {
            if (positions.size() == 0) throw new ModelFormatException("Não há faces triangulares visualizáveis neste modelo.");
            return new MeshData(this);
        }

        private static boolean finite(float[] v) {
            return v != null && v.length >= 3 && Float.isFinite(v[0]) && Float.isFinite(v[1]) && Float.isFinite(v[2]);
        }
    }

    private static final class FloatArray {
        private float[] data = new float[1024];
        private int size;
        void add(float value) {
            if (size == data.length) {
                float[] grown = new float[data.length + Math.max(data.length / 2, 1024)];
                System.arraycopy(data, 0, grown, 0, size);
                data = grown;
            }
            data[size++] = value;
        }
        int size() { return size; }
        float[] toArray() {
            float[] result = new float[size];
            System.arraycopy(data, 0, result, 0, size);
            return result;
        }
    }
}
