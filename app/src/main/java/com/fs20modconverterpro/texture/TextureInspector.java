package com.fs20modconverterpro.texture;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import com.fs20modconverterpro.texture.TextureInfo.Format;
import com.fs20modconverterpro.texture.TextureInfo.Transparency;

/** Reconhece cabeçalhos sem carregar nem decodificar toda a imagem. */
public final class TextureInspector {
    public static final int MAX_FILE_BYTES = 32 * 1024 * 1024;
    public static final long MAX_DECODE_PIXELS = 16_000_000L;
    private static final int UNKNOWN = -1;

    public TextureInfo inspect(byte[] header, String filename, String mimeType, long size) {
        byte[] data = header == null ? new byte[0] : header;
        String name = filename == null ? "" : filename.toLowerCase(Locale.ROOT);
        if (starts(data, 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a)) return png(data, size);
        if (starts(data, 0xff, 0xd8)) return jpeg(data, size);
        if (starts(data, 'B', 'M')) return bmp(data, size);
        if (starts(data, 'D', 'D', 'S', ' ')) return dds(data, size);
        if (name.endsWith(".tga")) return tga(data, size);
        if (name.endsWith(".webp") || starts(data, 'R', 'I', 'F', 'F') && ascii(data, 8, "WEBP"))
            return unsupported(Format.WEBP, size, "WebP reconhecido; o conversor ainda não oferece leitura garantida neste módulo.");
        if (name.endsWith(".ktx") || name.endsWith(".ktx2"))
            return unsupported(Format.KTX, size, "KTX/KTX2 é um contêiner de GPU e não está implementado para conversão.");
        if (name.endsWith(".pvr"))
            return unsupported(Format.PVR, size, "PVR é uma textura comprimida de GPU sem decodificador implementado.");
        if (name.endsWith(".astc"))
            return unsupported(Format.ASTC, size, "ASTC não está implementado neste conversor.");
        String lowerMime = mimeType == null ? "" : mimeType.toLowerCase(Locale.ROOT);
        if (lowerMime.contains("webp")) return unsupported(Format.WEBP, size, "WebP identificado, mas não é aceito para conversão neste módulo.");
        return unsupported(Format.UNKNOWN, size, "Formato de textura não identificado ou não suportado.");
    }

    private static TextureInfo png(byte[] data, long size) {
        if (data.length < 26) return unsupported(Format.PNG, size, "Cabeçalho PNG incompleto ou corrompido.");
        int width = be32(data, 16), height = be32(data, 20), color = data[25] & 0xff;
        boolean alpha = color == 4 || color == 6;
        int p = 8;
        while (!alpha && p + 12 <= data.length) {
            long length = be32u(data, p);
            if (length > data.length - p - 12L) break;
            if (ascii(data, p + 4, "tRNS")) alpha = true;
            p += (int) length + 12;
            if (ascii(data, p - 8, "IEND")) break;
        }
        boolean dimensions = validDimensions(width, height);
        return new TextureInfo(Format.PNG, width, height, size,
                alpha ? Transparency.YES : color == 2 || color == 0 ? Transparency.NO : Transparency.UNKNOWN,
                dimensions, "PNG", dimensions ? "PNG será decodificado pelo Android." : "Dimensões PNG inválidas ou excessivas.");
    }

    private static TextureInfo jpeg(byte[] data, long size) {
        int p = 2, width = UNKNOWN, height = UNKNOWN;
        while (p + 4 <= data.length) {
            if ((data[p] & 0xff) != 0xff) { p++; continue; }
            while (p < data.length && (data[p] & 0xff) == 0xff) p++;
            if (p >= data.length) break;
            int marker = data[p++] & 0xff;
            if (marker == 0xd9 || marker == 0xda) break;
            if (marker == 0x01 || marker >= 0xd0 && marker <= 0xd7) continue;
            if (p + 2 > data.length) break;
            int length = be16(data, p);
            if (length < 2 || p + length > data.length) break;
            if (isSof(marker) && length >= 7) {
                height = be16(data, p + 3); width = be16(data, p + 5); break;
            }
            p += length;
        }
        boolean dimensions = validDimensions(width, height);
        return new TextureInfo(Format.JPG, width, height, size, Transparency.NO, dimensions,
                "JPEG", dimensions ? "JPEG será decodificado pelo Android." : "Dimensões JPEG ausentes ou cabeçalho incompleto.");
    }

    private static TextureInfo bmp(byte[] data, long size) {
        if (data.length < 54) return unsupported(Format.BMP, size, "Cabeçalho BMP incompleto.");
        long dib = le32u(data, 14);
        int width = le32(data, 18), signedHeight = le32(data, 22);
        int height = signedHeight == Integer.MIN_VALUE ? -1 : Math.abs(signedHeight);
        int bits = le16(data, 28), compression = le32(data, 30);
        boolean supported = dib >= 40 && le16(data, 26) == 1 && compression == 0
                && (bits == 24 || bits == 32) && validDimensions(width, height);
        TextureInfo info = new TextureInfo(Format.BMP, width, height, size,
                Transparency.NO, supported,
                "BMP BI_RGB " + bits + " bits", supported
                ? "BMP BI_RGB de 24/32 bits será convertido pelo decodificador interno."
                : "Só são aceitos BMP BI_RGB de 24/32 bits com cabeçalho DIB de 40 bytes ou maior.");
        return info;
    }

    private static TextureInfo tga(byte[] data, long size) {
        if (data.length < 18) return unsupported(Format.TGA, size, "Cabeçalho TGA incompleto.");
        int colorMap = data[1] & 0xff, type = data[2] & 0xff;
        int width = le16(data, 12), height = le16(data, 14), bits = data[16] & 0xff;
        boolean supported = colorMap == 0 && (type == 2 || type == 10)
                && (bits == 24 || bits == 32) && validDimensions(width, height);
        boolean alpha = bits == 32 && ((data[17] & 0x0f) > 0);
        return new TextureInfo(Format.TGA, width, height, size,
                alpha ? Transparency.YES : bits == 24 || bits == 32 ? Transparency.NO : Transparency.UNKNOWN,
                supported, type == 2 ? "TGA true-color sem compressão" : "TGA true-color RLE",
                supported ? "TGA true-color de 24/32 bits (tipo 2 ou 10) será convertido pelo decodificador interno."
                        : "São aceitos apenas TGA true-color 24/32 bits, sem mapa de cores, tipo 2 ou RLE tipo 10.");
    }

    private static TextureInfo dds(byte[] data, long size) {
        if (data.length < 128 || le32(data, 4) != 124 || le32(data, 76) != 32)
            return unsupported(Format.DDS, size, "Cabeçalho DDS incompleto ou inválido.");
        int height = le32(data, 12), width = le32(data, 16), flags = le32(data, 80);
        int fourcc = le32(data, 84), bits = le32(data, 88);
        String code = fourccString(fourcc);
        boolean compressed = (flags & 0x4) != 0;
        boolean knownCompression = compressed && ("DXT1".equals(code) || "DXT3".equals(code) || "DXT5".equals(code));
        int rMask = le32(data, 92), gMask = le32(data, 96), bMask = le32(data, 100), aMask = le32(data, 104);
        boolean masksValid = validMask(rMask) && validMask(gMask) && validMask(bMask)
                && (rMask & gMask) == 0 && (rMask & bMask) == 0 && (gMask & bMask) == 0
                && (aMask == 0 || validMask(aMask) && (aMask & (rMask | gMask | bMask)) == 0);
        boolean rgb = !compressed && (flags & 0x40) != 0 && bits == 32 && masksValid;
        boolean dimensions = validDimensions(width, height);
        boolean supported = dimensions && (knownCompression || rgb);
        String variant = compressed ? code : rgb ? "RGB 32 bits com máscaras" : "DDS não reconhecido";
        String message = knownCompression ? "DDS " + code + " será descomprimido para uma imagem comum."
                : rgb ? "DDS RGB de 32 bits com máscaras será convertido."
                : "DDS identificado, mas este FourCC/pixel format ainda não possui decodificador.";
        Transparency transparency = "DXT1".equals(code) || "DXT3".equals(code) || "DXT5".equals(code)
                ? Transparency.YES : aMask != 0 ? Transparency.YES : rgb ? Transparency.NO : Transparency.UNKNOWN;
        if (!dimensions) message = "Dimensões DDS inválidas ou acima do limite seguro.";
        return new TextureInfo(Format.DDS, width, height, size, transparency, supported, variant, message);
    }

    private static TextureInfo unsupported(Format format, long size, String message) {
        return new TextureInfo(format, UNKNOWN, UNKNOWN, size, Transparency.UNKNOWN, false, "", message);
    }
    private static boolean validDimensions(int width, int height) {
        return width > 0 && height > 0 && (long) width * height <= MAX_DECODE_PIXELS;
    }
    private static boolean validMask(int mask) {
        if (mask == 0) return false;
        int normalized = mask >>> Integer.numberOfTrailingZeros(mask);
        return (normalized & (normalized + 1)) == 0;
    }
    private static boolean isSof(int marker) {
        return marker >= 0xc0 && marker <= 0xc3 || marker >= 0xc5 && marker <= 0xc7
                || marker >= 0xc9 && marker <= 0xcb || marker >= 0xcd && marker <= 0xcf;
    }
    private static boolean starts(byte[] data, int... values) {
        if (data.length < values.length) return false;
        for (int i = 0; i < values.length; i++) if ((data[i] & 0xff) != values[i]) return false;
        return true;
    }
    private static int le16(byte[] d, int p) { return (d[p] & 255) | (d[p + 1] & 255) << 8; }
    private static int le32(byte[] d, int p) { return (int) le32u(d, p); }
    private static long le32u(byte[] d, int p) {
        return (d[p] & 255L) | (d[p + 1] & 255L) << 8 | (d[p + 2] & 255L) << 16 | (d[p + 3] & 255L) << 24;
    }
    private static int be16(byte[] d, int p) { return (d[p] & 255) << 8 | d[p + 1] & 255; }
    private static int be32(byte[] d, int p) { return (d[p] & 255) << 24 | (d[p + 1] & 255) << 16 | (d[p + 2] & 255) << 8 | d[p + 3] & 255; }
    private static long be32u(byte[] d, int p) { return be32(d, p) & 0xffffffffL; }
    private static boolean ascii(byte[] d, int p, String s) {
        if (p + s.length() > d.length) return false;
        for (int i = 0; i < s.length(); i++) if (d[p + i] != (byte) s.charAt(i)) return false;
        return true;
    }
    private static String fourccString(int value) {
        return new String(new byte[]{(byte) value, (byte) (value >>> 8), (byte) (value >>> 16), (byte) (value >>> 24)}, StandardCharsets.US_ASCII);
    }
}
