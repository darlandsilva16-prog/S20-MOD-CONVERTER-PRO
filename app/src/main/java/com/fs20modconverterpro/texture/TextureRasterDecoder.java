package com.fs20modconverterpro.texture;

import java.io.IOException;

/** Decodificadores próprios para BMP/TGA e subformatos DDS documentados no inspetor. */
public final class TextureRasterDecoder {
    public static final long MAX_OUTPUT_PIXELS = 8_000_000L;
    public TextureRaster decode(byte[] bytes, TextureInfo info, int maximumSide) throws IOException {
        if (bytes == null || info == null || !info.canConvert()) throw new IOException("Formato de textura não conversível.");
        int sample = sampleSize(info.getWidth(), info.getHeight(), maximumSide);
        switch (info.getFormat()) {
            case BMP: return decodeBmp(bytes, sample);
            case TGA: return decodeTga(bytes, sample);
            case DDS: return decodeDds(bytes, info, sample);
            default: throw new IOException("Este formato deve ser decodificado pelo Android.");
        }
    }

    public static int sampleSize(int width, int height, int maximumSide) {
        int sample = 1;
        int target = Math.max(1, maximumSide);
        while ((Math.max(width / sample, height / sample) > target
                || (long) ceilDiv(width, sample) * ceilDiv(height, sample) > MAX_OUTPUT_PIXELS)
                && sample < 1 << 30) sample *= 2;
        return sample;
    }

    private static TextureRaster decodeBmp(byte[] b, int sample) throws IOException {
        if (b.length < 54 || b[0] != 'B' || b[1] != 'M') throw new IOException("Cabeçalho BMP inválido.");
        long offsetLong = u32(b, 10), dib = u32(b, 14);
        int width = s32(b, 18), signedHeight = s32(b, 22), bits = u16(b, 28), compression = s32(b, 30);
        if (dib < 40 || width <= 0 || signedHeight == 0 || signedHeight == Integer.MIN_VALUE
                || u16(b, 26) != 1 || compression != 0 || bits != 24 && bits != 32) {
            throw new IOException("BMP não suportado: use BI_RGB de 24 ou 32 bits.");
        }
        int height = Math.abs(signedHeight);
        checkPixelLimit(width, height);
        if (offsetLong < 14 + dib || offsetLong > b.length) throw new IOException("Deslocamento de pixels BMP inválido.");
        int offset = (int) offsetLong;
        long rowBytes = (((long) width * bits + 31) / 32) * 4;
        if (offsetLong + rowBytes * height > b.length) throw new IOException("Dados BMP truncados.");
        int outW = ceilDiv(width, sample), outH = ceilDiv(height, sample);
        int[] pixels = new int[checkedOutputPixels(outW, outH)];
        int bytesPerPixel = bits / 8;
        for (int y = 0; y < height; y += sample) {
            int sourceY = signedHeight > 0 ? height - 1 - y : y;
            int row = offset + (int) (sourceY * rowBytes);
            for (int x = 0; x < width; x += sample) {
                int p = row + x * bytesPerPixel;
                int blue = b[p] & 255, green = b[p + 1] & 255, red = b[p + 2] & 255;
                pixels[(y / sample) * outW + x / sample] = 0xff000000 | red << 16 | green << 8 | blue;
            }
        }
        return new TextureRaster(outW, outH, pixels, false);
    }

    private static TextureRaster decodeTga(byte[] b, int sample) throws IOException {
        if (b.length < 18) throw new IOException("Cabeçalho TGA incompleto.");
        int idLength = b[0] & 255, colorMap = b[1] & 255, type = b[2] & 255;
        int width = u16(b, 12), height = u16(b, 14), bits = b[16] & 255, descriptor = b[17] & 255;
        if (colorMap != 0 || (type != 2 && type != 10) || (bits != 24 && bits != 32))
            throw new IOException("TGA não suportado: use true-color 24/32 bits, tipo 2 ou RLE tipo 10.");
        checkPixelLimit(width, height);
        int bytesPerPixel = bits / 8, cursor = 18 + idLength;
        if (cursor > b.length) throw new IOException("Identificador TGA truncado.");
        int outW = ceilDiv(width, sample), outH = ceilDiv(height, sample);
        int[] pixels = new int[checkedOutputPixels(outW, outH)];
        boolean alpha = bits == 32 && (descriptor & 0x0f) > 0;
        long total = (long) width * height, index = 0;
        while (index < total) {
            int packet = 1;
            boolean repeated = false;
            if (type == 10) {
                if (cursor >= b.length) throw new IOException("Pacote RLE TGA truncado.");
                int header = b[cursor++] & 255;
                packet = (header & 0x7f) + 1;
                repeated = (header & 0x80) != 0;
                if (index + packet > total) throw new IOException("Pacote RLE TGA ultrapassa a imagem.");
            }
            int repeatedColor = 0;
            if (repeated) {
                if (cursor + bytesPerPixel > b.length) throw new IOException("Pixel RLE TGA truncado.");
                repeatedColor = tgaColor(b, cursor, bytesPerPixel, alpha);
                cursor += bytesPerPixel;
            }
            for (int i = 0; i < packet; i++, index++) {
                int color;
                if (repeated) color = repeatedColor;
                else {
                    if (cursor + bytesPerPixel > b.length) throw new IOException("Pixels TGA truncados.");
                    color = tgaColor(b, cursor, bytesPerPixel, alpha);
                    cursor += bytesPerPixel;
                }
                int fileX = (int) (index % width), fileY = (int) (index / width);
                int x = (descriptor & 0x10) != 0 ? width - 1 - fileX : fileX;
                int y = (descriptor & 0x20) != 0 ? fileY : height - 1 - fileY;
                if (x % sample == 0 && y % sample == 0)
                    pixels[(y / sample) * outW + x / sample] = color;
            }
        }
        return new TextureRaster(outW, outH, pixels, alpha);
    }

    private static TextureRaster decodeDds(byte[] b, TextureInfo info, int sample) throws IOException {
        if (b.length < 128 || s32(b, 4) != 124) throw new IOException("Cabeçalho DDS inválido.");
        int width = s32(b, 16), height = s32(b, 12);
        checkPixelLimit(width, height);
        int outW = ceilDiv(width, sample), outH = ceilDiv(height, sample);
        int[] pixels = new int[checkedOutputPixels(outW, outH)];
        String code = info.getVariant();
        if ("DXT1".equals(code) || "DXT3".equals(code) || "DXT5".equals(code)) {
            decodeBlocks(b, code, width, height, sample, outW, pixels);
            return new TextureRaster(outW, outH, pixels, true);
        }
        if (!"RGB 32 bits com máscaras".equals(code) || s32(b, 88) != 32)
            throw new IOException("Formato de pixels DDS ainda não suportado.");
        int rMask = s32(b, 92), gMask = s32(b, 96), bMask = s32(b, 100), aMask = s32(b, 104);
        if (rMask == 0 || gMask == 0 || bMask == 0) throw new IOException("Máscaras RGB DDS inválidas.");
        long minimumPitch = (long) width * 4;
        long pitch = (s32(b, 8) & 0x8) != 0 ? u32(b, 20) : minimumPitch;
        if (pitch < minimumPitch || 128L + pitch * height > b.length) throw new IOException("Pixels DDS truncados ou pitch inválido.");
        for (int y = 0; y < height; y += sample) {
            int row = 128 + (int) (y * pitch);
            for (int x = 0; x < width; x += sample) {
                int raw = s32(b, row + x * 4);
                int alpha = aMask == 0 ? 255 : component(raw, aMask);
                pixels[(y / sample) * outW + x / sample] = alpha << 24
                        | component(raw, rMask) << 16 | component(raw, gMask) << 8 | component(raw, bMask);
            }
        }
        return new TextureRaster(outW, outH, pixels, aMask != 0);
    }

    private static void decodeBlocks(byte[] b, String format, int width, int height, int sample,
                                     int outW, int[] output) throws IOException {
        int blockBytes = "DXT1".equals(format) ? 8 : 16;
        int blocksX = ceilDiv(width, 4), blocksY = ceilDiv(height, 4);
        long required = 128L + (long) blocksX * blocksY * blockBytes;
        if (required > b.length) throw new IOException("Blocos DDS truncados.");
        int cursor = 128;
        for (int by = 0; by < blocksY; by++) {
            for (int bx = 0; bx < blocksX; bx++, cursor += blockBytes) {
                int colorOffset = cursor + ("DXT1".equals(format) ? 0 : 8);
                int c0 = u16(b, colorOffset), c1 = u16(b, colorOffset + 2);
                int[] colors = colorPalette(c0, c1, "DXT1".equals(format));
                long colorBits = u32(b, colorOffset + 4);
                long alphaBits = 0;
                int[] alphaTable = null;
                if ("DXT3".equals(format)) {
                    for (int i = 0; i < 8; i++) alphaBits |= (long) (b[cursor + i] & 255) << (8 * i);
                } else if ("DXT5".equals(format)) {
                    alphaTable = dxt5AlphaTable(b[cursor] & 255, b[cursor + 1] & 255);
                    for (int i = 0; i < 6; i++) alphaBits |= (long) (b[cursor + 2 + i] & 255) << (8 * i);
                }
                for (int py = 0; py < 4; py++) for (int px = 0; px < 4; px++) {
                    int x = bx * 4 + px, y = by * 4 + py;
                    if (x >= width || y >= height || x % sample != 0 || y % sample != 0) continue;
                    int pixel = py * 4 + px, color = colors[(int) ((colorBits >>> (pixel * 2)) & 3)];
                    int alpha = color >>> 24;
                    if ("DXT3".equals(format)) alpha = (int) ((alphaBits >>> (pixel * 4)) & 15) * 17;
                    else if ("DXT5".equals(format)) alpha = alphaTable[(int) ((alphaBits >>> (pixel * 3)) & 7)];
                    output[(y / sample) * outW + x / sample] = alpha << 24 | color & 0x00ffffff;
                }
            }
        }
    }

    private static int[] colorPalette(int c0, int c1, boolean dxt1) {
        int a = rgb565(c0), b = rgb565(c1);
        int[] colors = new int[4]; colors[0] = 0xff000000 | a; colors[1] = 0xff000000 | b;
        if (!dxt1 || c0 > c1) {
            colors[2] = mix(a, b, 2, 1, 3, 255);
            colors[3] = mix(a, b, 1, 2, 3, 255);
        } else {
            colors[2] = mix(a, b, 1, 1, 2, 255); colors[3] = 0;
        }
        return colors;
    }
    private static int mix(int a, int b, int wa, int wb, int divisor, int alpha) {
        int r = (((a >> 16) & 255) * wa + ((b >> 16) & 255) * wb) / divisor;
        int g = (((a >> 8) & 255) * wa + ((b >> 8) & 255) * wb) / divisor;
        int blue = ((a & 255) * wa + (b & 255) * wb) / divisor;
        return alpha << 24 | r << 16 | g << 8 | blue;
    }
    private static int rgb565(int c) {
        int r = (c >>> 11) & 31, g = (c >>> 5) & 63, b = c & 31;
        return (r * 255 / 31) << 16 | (g * 255 / 63) << 8 | b * 255 / 31;
    }
    private static int[] dxt5AlphaTable(int a0, int a1) {
        int[] table = new int[8]; table[0] = a0; table[1] = a1;
        if (a0 > a1) for (int i = 2; i < 8; i++) table[i] = ((8 - i) * a0 + (i - 1) * a1) / 7;
        else {
            for (int i = 2; i < 6; i++) table[i] = ((6 - i) * a0 + (i - 1) * a1) / 5;
            table[6] = 0; table[7] = 255;
        }
        return table;
    }
    private static int component(int pixel, int mask) {
        int shift = Integer.numberOfTrailingZeros(mask), maximum = mask >>> shift;
        return maximum == 0 ? 0 : ((pixel & mask) >>> shift) * 255 / maximum;
    }
    private static int tgaColor(byte[] b, int p, int bytes, boolean alpha) {
        int a = alpha ? b[p + 3] & 255 : 255;
        return a << 24 | (b[p + 2] & 255) << 16 | (b[p + 1] & 255) << 8 | b[p] & 255;
    }
    private static void checkPixelLimit(int width, int height) throws IOException {
        if (width <= 0 || height <= 0 || (long) width * height > TextureInspector.MAX_DECODE_PIXELS)
            throw new IOException("A resolução excede o limite seguro de 16 milhões de pixels.");
    }
    private static int checkedOutputPixels(int width, int height) throws IOException {
        long total = (long) width * height;
        if (total <= 0 || total > TextureInspector.MAX_DECODE_PIXELS) throw new IOException("Buffer de pixels acima do limite.");
        return (int) total;
    }
    private static int ceilDiv(int n, int d) { return (n + d - 1) / d; }
    private static int u16(byte[] b, int p) throws IOException {
        if (p < 0 || p + 2 > b.length) throw new IOException("Cabeçalho truncado.");
        return (b[p] & 255) | (b[p + 1] & 255) << 8;
    }
    private static int s32(byte[] b, int p) throws IOException {
        if (p < 0 || p + 4 > b.length) throw new IOException("Cabeçalho truncado.");
        return b[p] & 255 | (b[p + 1] & 255) << 8 | (b[p + 2] & 255) << 16 | b[p + 3] << 24;
    }
    private static long u32(byte[] b, int p) throws IOException { return s32(b, p) & 0xffffffffL; }
}
