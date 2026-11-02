package com.fs20modconverterpro.texture;

/** Metadados de imagem e resultado explícito da análise de suporte. */
public final class TextureInfo {
    public enum Format { PNG, JPG, BMP, TGA, DDS, WEBP, KTX, PVR, ASTC, UNKNOWN }
    public enum Transparency { YES, NO, UNKNOWN }

    private final Format format;
    private final int width, height;
    private final long size;
    private final Transparency transparency;
    private final boolean convertible;
    private final String variant, message;

    TextureInfo(Format format, int width, int height, long size, Transparency transparency,
                boolean convertible, String variant, String message) {
        this.format = format; this.width = width; this.height = height; this.size = size;
        this.transparency = transparency; this.convertible = convertible;
        this.variant = variant; this.message = message;
    }
    public Format getFormat() { return format; }
    public int getWidth() { return width; }
    public int getHeight() { return height; }
    public long getSize() { return size; }
    public Transparency getTransparency() { return transparency; }
    public boolean canConvert() { return convertible; }
    public String getVariant() { return variant; }
    public String getMessage() { return message; }
    public boolean hasDimensions() { return width > 0 && height > 0; }
}
