"""Inspeção segura de XML I3D e do cabeçalho binário Shapes."""
from xml.parsers import expat
from .xml_tools import inspect as inspect_xml


def inspect(data):
    base = inspect_xml(data)
    refs = []
    parser = expat.ParserCreate()
    def read_shape_file(name, attrs):
        if name.lower().endswith("shapes"):
            ref = attrs.get("externalShapesFile")
            if ref and ref not in refs and len(refs) < 1000:
                refs.append(ref[:512])
    parser.StartElementHandler = read_shape_file
    parser.SetParamEntityParsing(expat.XML_PARAM_ENTITY_PARSING_NEVER)
    def reject(*_args):
        raise ValueError("DTD e entidades externas são proibidos em I3D.")
    parser.StartDoctypeDeclHandler = reject
    parser.EntityDeclHandler = reject
    parser.ExternalEntityRefHandler = reject
    parser.Parse(data, True)
    return {"format": "I3D", "status": "ANALISADO", "shapes_references": refs,
            "message": "Estrutura XML analisada; consulte o leitor I3D Java para associar e decodificar a geometria."}


def inspect_shapes_header(data):
    if len(data) < 4:
        raise ValueError("Cabeçalho .i3d.shapes truncado.")
    b0, b1, b2, b3 = data[:4]
    if b0 >= 4:
        version, seed = b0, b2
    elif b3 in (2, 3):
        version, seed = b3, b1
    else:
        raise ValueError("Cabeçalho .i3d.shapes desconhecido.")
    if version < 2 or version > 7:
        raise ValueError("Versão .i3d.shapes fora do intervalo conhecido 2–7.")
    return {"format": "I3D.SHAPES", "status": "CABECALHO_IDENTIFICADO",
            "version": version, "seed": seed,
            "message": "O motor Python só identifica o cabeçalho; a decodificação geométrica é feita pelo leitor Java."}
