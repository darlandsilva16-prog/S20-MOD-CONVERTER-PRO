"""Leitor observacional do prefixo GAR. Não contém extração ou criptografia."""

import json

HEADER_LIMIT = 128
GIANTS_BANNER = b"Copyright (C) GIANTS Software GmbH, All Rights Reserved"
GAR_SIGNATURE = b"GAR "
# Larguras/valores apenas reproduzem as observações fornecidas para o cabeçalho de teste.
OBSERVED_FIELD_WIDTHS = (16, 16, 32)
STATE_LABELS = {
    "CABECALHO_IDENTIFICADO": "CABEÇALHO IDENTIFICADO",
    "ESTRUTURA_NAO_VALIDADA": "ESTRUTURA NÃO VALIDADA",
    "FORMATO_NAO_SUPORTADO": "FORMATO NÃO SUPORTADO",
}


def analyze_header(filename, file_size, header_bytes):
    """Analisa somente até 128 bytes e devolve um relatório JSON sem ações de escrita."""
    name = _validate_name(filename)
    size = _validate_size(file_size)
    try:
        prefix = bytes(header_bytes)
    except Exception as exc:
        raise ValueError("O cabeçalho recebido não é binário.") from exc
    if len(prefix) > HEADER_LIMIT:
        raise ValueError("O cabeçalho excede o limite de 128 bytes.")
    if size >= 0:
        prefix = prefix[:size]

    banner_offset = prefix.find(GIANTS_BANNER)
    signature_offset = prefix.find(GAR_SIGNATURE)
    banner_found = banner_offset >= 0
    fields = []
    incomplete = 0
    if size == 0 or len(prefix) == 0 or (0 < size < 4):
        state_code = "FORMATO_NAO_SUPORTADO"
        message = "Prefixo vazio ou tamanho inconsistente."
    elif prefix[:4] in (b"PK\x03\x04", b"PK\x05\x06", b"PK\x07\x08"):
        state_code = "FORMATO_NAO_SUPORTADO"
        message = "Assinatura ZIP detectada; mudar a extensão não converte o arquivo para GAR."
        signature_offset = -1
    elif signature_offset < 0:
        state_code = "ESTRUTURA_NAO_VALIDADA" if banner_found else "FORMATO_NAO_SUPORTADO"
        message = "Sequência GAR (GAR + espaço) não encontrada no prefixo analisado."
    else:
        cursor = signature_offset + len(GAR_SIGNATURE)
        for index, width_bits in enumerate(OBSERVED_FIELD_WIDTHS, 1):
            byte_count = width_bits // 8
            end = cursor + byte_count
            if end > len(prefix):
                incomplete = len(OBSERVED_FIELD_WIDTHS) - index + 1
                break
            raw = prefix[cursor:end]
            fields.append({
                "index": index,
                "label": "Valor observado %d (significado desconhecido)" % index,
                "width_bits": width_bits,
                "observed_value": int.from_bytes(raw, "little", signed=False),
                "raw_hex": _hex(raw),
                "interpretation": "uint%d little-endian observado; significado não confirmado" % width_bits,
            })
            cursor = end
        complete = incomplete == 0 and len(fields) == len(OBSERVED_FIELD_WIDTHS)
        state_code = ("CABECALHO_IDENTIFICADO"
                 if banner_found and complete
                 else "ESTRUTURA_NAO_VALIDADA")
        message = "Banner GIANTS: %s; assinatura GAR: posição %d. Os valores são observações, " \
                  "sem significado de campo afirmado." % (
                      "posição %d" % banner_offset if banner_found else "não encontrado", signature_offset)

    signature_hex = _hex(prefix[signature_offset:signature_offset + len(GAR_SIGNATURE)]) \
        if 0 <= signature_offset < len(prefix) else ""
    report = {
        "module": "GAR ENGINE PRO",
        "file": name,
        "size_bytes": size,
        "header_limit": HEADER_LIMIT,
        "header_bytes_read": len(prefix),
        "header_hex": _hex(prefix),
        "state_code": state_code,
        "state": STATE_LABELS[state_code],
        "index_state_code": "ESTRUTURA_NAO_VALIDADA",
        "index_state": STATE_LABELS["ESTRUTURA_NAO_VALIDADA"],
        "index_read_available": False,
        "giants_banner_found": banner_found,
        "giants_banner_offset": banner_offset,
        "gar_signature_found": signature_offset >= 0,
        "gar_signature_offset": signature_offset,
        "gar_signature_hex": signature_hex,
        "observed_fields": fields,
        "incomplete_field_count": incomplete,
        "version": None,
        "entry_count": None,
        "content_validated": False,
        "can_extract": False,
        "can_rebuild": False,
        "can_search_contents": False,
        "read_only": True,
        "warnings": [
            "Os campos exibidos não têm significado confirmado.",
            "A leitura do índice GAR ainda não está disponível; nenhum leitor validado foi registrado.",
            "Nenhum código ou conteúdo dentro do mod foi executado.",
            "Criptografia, extração, reconstrução e pesquisa interna estão desativadas.",
        ],
        "message": message,
    }
    return json.dumps(report, ensure_ascii=False)


def _validate_name(filename):
    if not isinstance(filename, str) or not filename.strip() or len(filename) > 1024:
        raise ValueError("Nome de arquivo inválido.")
    if "/" in filename or "\\" in filename or any(ord(char) < 32 for char in filename):
        raise ValueError("O seletor deve fornecer somente um nome de arquivo, sem caminho.")
    return filename


def _validate_size(file_size):
    if isinstance(file_size, bool) or not isinstance(file_size, int):
        raise ValueError("Tamanho do arquivo inválido.")
    if file_size < -1 or file_size > 9223372036854775807:
        raise ValueError("Tamanho do arquivo fora dos limites suportados.")
    return file_size


def _hex(data):
    return " ".join("%02X" % byte for byte in data)
