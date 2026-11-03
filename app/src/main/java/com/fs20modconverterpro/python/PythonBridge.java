package com.fs20modconverterpro.python;

import android.content.Context;
import com.chaquo.python.android.AndroidPlatform;
import com.chaquo.python.PyObject;
import com.chaquo.python.Python;

/** Ponte limitada a rotinas internas; texto de arquivo nunca é executado como código. */
public final class PythonBridge {
    public static final int MAX_INPUT_BYTES = 32 * 1024 * 1024;
    private static final String MODULE = "fs20_engine.runtime";
    public void start(Context context) {
        synchronized (PythonBridge.class) {
            if (!Python.isStarted()) Python.start(new AndroidPlatform(context.getApplicationContext()));
        }
    }
    public String runtimeInfo() {
        PyObject result = Python.getInstance().getModule(MODULE).callAttr("runtime_info_json");
        return result.toJava(String.class);
    }
    public String analyze(String filename, byte[] contents) {
        validateInput(filename, contents);
        PyObject result = Python.getInstance().getModule(MODULE).callAttr("analyze_bytes", filename, contents);
        return result.toJava(String.class);
    }
    public String assessMod(String filename, byte[] contents) {
        validateInput(filename, contents);
        if (!filename.toLowerCase(java.util.Locale.ROOT).endsWith(".zip")) {
            throw new IllegalArgumentException("Selecione um arquivo ZIP de mod.");
        }
        PyObject result = Python.getInstance().getModule(MODULE)
                .callAttr("assess_mod_bytes", filename, contents);
        return result.toJava(String.class);
    }
    public static void validateInput(String filename, byte[] contents) {
        if (filename == null || filename.length() == 0 || filename.length() > 1024) {
            throw new IllegalArgumentException("Nome de arquivo inválido.");
        }
        if (contents == null || contents.length == 0 || contents.length > MAX_INPUT_BYTES) {
            throw new IllegalArgumentException("Arquivo vazio ou acima do limite seguro de 32 MiB.");
        }
    }
}
