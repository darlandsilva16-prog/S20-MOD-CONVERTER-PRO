package com.fs20modconverterpro.viewer3d;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Resultado inequívoco: malha renderizável ou análise informativa sem renderização. */
public final class ModelLoadResult {
    private final ModelFormat format;
    private final MeshData mesh;
    private final String message;
    private final List<String> warnings;

    public ModelLoadResult(ModelFormat format, MeshData mesh, String message, List<String> warnings) {
        this.format = format; this.mesh = mesh; this.message = message;
        this.warnings = Collections.unmodifiableList(new ArrayList<>(warnings));
    }
    public ModelFormat getFormat() { return format; }
    public MeshData getMesh() { return mesh; }
    public String getMessage() { return message; }
    public List<String> getWarnings() { return warnings; }
    public boolean isRenderable() { return mesh != null; }
}
