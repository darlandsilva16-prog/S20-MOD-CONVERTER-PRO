"""Fachada de operações permitidas do motor; nenhum conteúdo recebido é executado."""
import json
import os
import platform

from . import archive, conversion, i3d, lua, textures, xml_tools

MAX_INPUT_BYTES = 32 * 1024 * 1024
TOOLS = {
    "ZIP": "Inspeção de entradas e caminhos, sem extração",
    "XML": "Análise XML com bloqueio de DTD e entidades externas",
    "I3D": "Análise XML I3D e identificação de .i3d.shapes",
    "I3D.SHAPES": "Leitura de cabeçalho binário; decodificação completa disponível no leitor Java",
    "LUA": "Análise estática básica; scripts não são executados",
    "TEXTURA": "Identificação de cabeçalhos de imagem, sem alteração do original",
    "FS": "Diagnóstico conservador de metadados de versão do mod",
    "CONVERSOR": "Avaliação de referências I3D e compatibilidade; sem conversão ou execução de Lua",
}
LIBRARIES = ["json", "zipfile", "xml.parsers.expat", "re", "struct", "platform"]


def runtime_info_json():
    return json.dumps({"state": "PRONTO", "python": platform.python_version(),
                       "implementation": platform.python_implementation(),
                       "modules": TOOLS, "libraries": LIBRARIES,
                       "third_party": []}, ensure_ascii=False)


def analyze_bytes(filename, data):
    """Recebe conteúdo binário da ponte Java e devolve relatório JSON."""
    if not isinstance(filename, str) or len(filename) > 1024:
        raise ValueError("Nome de arquivo inválido.")
    try:
        raw = bytes(data)
    except Exception as exc:
        raise ValueError("O conteúdo recebido não é um vetor de bytes.") from exc
    if not raw or len(raw) > MAX_INPUT_BYTES:
        raise ValueError("Arquivo vazio ou acima do limite de 32 MiB.")
    lower_name = filename.lower()
    ext = ".i3d.shapes" if lower_name.endswith(".i3d.shapes") else os.path.splitext(lower_name)[1]
    if ext == ".zip":
        report = archive.inspect(raw)
    elif ext in (".xml", ".i3d"):
        report = i3d.inspect(raw) if ext == ".i3d" else xml_tools.inspect(raw)
    elif ext == ".i3d.shapes":
        report = i3d.inspect_shapes_header(raw)
    elif ext == ".lua":
        report = lua.inspect(raw)
    elif ext in (".png", ".jpg", ".jpeg", ".bmp", ".tga", ".dds"):
        report = textures.inspect(raw, ext)
    else:
        raise ValueError("Formato não reconhecido pelo motor Python.")
    report["file"] = filename
    report["size_bytes"] = len(raw)
    if ext in (".xml", ".i3d"):
        report["fs_diagnostic"] = diagnose_game_version(raw.decode("utf-8-sig", errors="replace"))
    return json.dumps(report, ensure_ascii=False)


def diagnose_game_version(xml_text):
    """Só reporta a versão declarada; não infere compatibilidade sem metadados."""
    import re
    match = re.search(r'<\s*modDesc\b[^>]*\bdescVersion\s*=\s*["\']([^"\']+)', xml_text, re.I)
    if not match:
        return {"status": "INCONCLUSIVO", "message": "Não há metadado descVersion identificável para comparar FS19/FS22/FS25 com FS20."}
    return {"status": "VERSAO_DECLARADA", "descVersion": match.group(1),
            "message": "Metadado identificado. A compatibilidade entre versões do jogo não é inferida automaticamente."}


def assess_mod_bytes(filename, data):
    """Avalia um ZIP sem extrair, modificar, converter ou executar arquivos do mod."""
    if not isinstance(filename, str) or len(filename) > 1024 or not filename.lower().endswith(".zip"):
        raise ValueError("Selecione um arquivo ZIP de mod.")
    raw = bytes(data)
    if not raw or len(raw) > MAX_INPUT_BYTES:
        raise ValueError("ZIP vazio ou acima do limite de 32 MiB.")
    return json.dumps(conversion.assess_zip(raw), ensure_ascii=False)
