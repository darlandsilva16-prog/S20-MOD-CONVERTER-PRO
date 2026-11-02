package com.fs20modconverterpro.viewer3d;

import java.util.Locale;

/** Formatos reconhecidos pelo leitor 3D e pelo analisador I3D. */
public enum ModelFormat {
    OBJ, STL, GLB, GLTF, I3D, ZIP, UNKNOWN;

    public static ModelFormat fromName(String filename) {
        String name = filename == null ? "" : filename.toLowerCase(Locale.ROOT);
        if (name.endsWith(".obj")) return OBJ;
        if (name.endsWith(".stl")) return STL;
        if (name.endsWith(".glb")) return GLB;
        if (name.endsWith(".gltf")) return GLTF;
        if (name.endsWith(".i3d")) return I3D;
        if (name.endsWith(".zip")) return ZIP;
        return UNKNOWN;
    }

    public boolean canBeRendered() { return this == OBJ || this == STL || this == GLB || this == GLTF; }
}
