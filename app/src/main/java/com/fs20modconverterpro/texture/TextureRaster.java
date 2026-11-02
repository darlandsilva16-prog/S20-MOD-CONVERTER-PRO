package com.fs20modconverterpro.texture;

/** Pixels ARGB reduzidos por amostragem para controlar memória. */
public final class TextureRaster {
    private final int width, height;
    private final int[] pixels;
    private final boolean hasAlpha;
    TextureRaster(int width, int height, int[] pixels, boolean hasAlpha) {
        this.width = width; this.height = height; this.pixels = pixels; this.hasAlpha = hasAlpha;
    }
    public int getWidth() { return width; }
    public int getHeight() { return height; }
    public int[] getPixels() { return pixels; }
    public boolean hasAlpha() { return hasAlpha; }
}
