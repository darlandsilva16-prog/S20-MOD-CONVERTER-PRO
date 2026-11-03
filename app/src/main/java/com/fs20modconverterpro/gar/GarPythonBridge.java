package com.fs20modconverterpro.gar;

import android.content.Context;

import com.chaquo.python.PyObject;
import com.chaquo.python.Python;
import com.chaquo.python.android.AndroidPlatform;
import com.fs20modconverterpro.python.PythonBridge;

/** Inicializa Chaquopy sob demanda e chama somente a rotina de análise de cabeçalho GAR. */
public final class GarPythonBridge {
    public static final int MAX_HEADER_BYTES = 128;

    public String analyzeHeader(Context context, String filename, long fileSize, byte[] header) {
        validate(filename, fileSize, header);
        startIfNeeded(context);
        PyObject result = Python.getInstance().getModule("gar_engine.gar_py")
                .callAttr("analyze_header", filename, fileSize, header);
        return result.toJava(String.class);
    }

    private static void startIfNeeded(Context context) {
        if (context == null) throw new IllegalArgumentException("Contexto Android ausente.");
        synchronized (PythonBridge.class) {
            if (!Python.isStarted()) {
                Python.start(new AndroidPlatform(context.getApplicationContext()));
            }
        }
    }

    private static void validate(String filename, long fileSize, byte[] header) {
        if (filename == null || filename.length() == 0 || filename.length() > 1024
                || filename.indexOf('/') >= 0 || filename.indexOf('\\') >= 0) {
            throw new IllegalArgumentException("Nome de arquivo inválido; informe somente o nome exibido pelo seletor.");
        }
        if (fileSize < -1) throw new IllegalArgumentException("Tamanho de arquivo fora dos limites.");
        if (header == null || header.length > MAX_HEADER_BYTES) {
            throw new IllegalArgumentException("O cabeçalho deve conter no máximo 128 bytes.");
        }
    }
}
