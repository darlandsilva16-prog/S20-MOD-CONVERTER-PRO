"""Identifica cabeçalhos comuns, sem decodificar nem salvar imagens."""
import struct


def inspect(data, extension):
    fmt, width, height = "NAO_IDENTIFICADO", None, None
    if data.startswith(b"\x89PNG\r\n\x1a\n") and len(data) >= 24:
        fmt, width, height = "PNG", *struct.unpack(">II", data[16:24])
    elif data.startswith(b"\xff\xd8\xff"):
        fmt = "JPEG"
    elif data.startswith(b"BM"):
        fmt = "BMP"
    elif data.startswith(b"DDS ") and len(data) >= 128:
        height, width = struct.unpack("<II", data[12:20])
        fmt = "DDS/" + data[84:88].decode("ascii", errors="replace").strip() if data[84:88].strip(b"\x00") else "DDS"
    elif extension == ".tga" and len(data) >= 18:
        width, height = struct.unpack("<HH", data[12:16])
        fmt = "TGA (cabeçalho presumido)"
    if fmt == "NAO_IDENTIFICADO":
        raise ValueError("Cabeçalho de textura não reconhecido ou insuficiente.")
    return {"format": fmt, "status": "IDENTIFICADA", "width": width, "height": height,
            "message": "Somente cabeçalho identificado; a imagem original não foi alterada."}
