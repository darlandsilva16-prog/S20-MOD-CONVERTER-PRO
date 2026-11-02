package com.fs20modconverterpro.viewer3d;

/** Erro de leitura que pode ser mostrado ao usuário sem encerrar a tela. */
public final class ModelFormatException extends Exception {
    public ModelFormatException(String message) { super(message); }
    public ModelFormatException(String message, Throwable cause) { super(message, cause); }
}
