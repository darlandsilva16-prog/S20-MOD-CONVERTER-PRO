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
            "message": "Estrutura XML analisada; referências SHAPES identificadas sem decodificar geometria binária."}


def inspect_shapes_header(data):
    """Classifica somente quatro bytes observados; não interpreta o payload SHAPES."""
    if len(data) < 4:
        raise ValueError("Cabeçalho .i3d.shapes truncado.")
    header = bytes(data[:4])
    b0, _, _, b3 = header
    if 4 <= b0 <= 7:
        candidate = b0
        convention = "byte_0"
    elif b3 in (2, 3):
        candidate = b3
        convention = "byte_3"
    else:
        return {"format": "I3D.SHAPES", "status": "FORMATO_NAO_RECONHECIDO",
                "header_hex": header.hex(" ").upper(), "payload_decoded": False,
                "message": "Os quatro bytes não correspondem às convenções de cabeçalho atualmente observadas."}
    return {"format": "I3D.SHAPES", "status": "CANDIDATO_DE_CABECALHO",
            "version_candidate": candidate, "version_convention_observed": convention,
            "header_hex": header.hex(" ").upper(), "payload_decoded": False,
            "message": "Candidato baseado somente em bytes observados; versão e estrutura não foram validadas por especificação."}
