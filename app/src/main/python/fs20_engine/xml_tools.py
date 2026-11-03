"""Validador XML estrito para dados não confiáveis."""
from xml.parsers import expat

MAX_XML_BYTES = 16 * 1024 * 1024


def inspect(data):
    if len(data) > MAX_XML_BYTES:
        raise ValueError("XML acima do limite de 16 MiB.")
    parser = expat.ParserCreate()
    elements = [0]
    def count_element(_name, _attrs):
        elements[0] += 1
    parser.StartElementHandler = count_element
    parser.SetParamEntityParsing(expat.XML_PARAM_ENTITY_PARSING_NEVER)
    def reject(*_args):
        raise ValueError("DTD, declaração de entidade e referências externas são proibidos.")
    parser.StartDoctypeDeclHandler = reject
    parser.EntityDeclHandler = reject
    parser.ExternalEntityRefHandler = reject
    parser.SetBase("")
    try:
        parser.Parse(data, True)
    except expat.ExpatError as exc:
        raise ValueError("XML malformado na linha %d, coluna %d." % (exc.lineno, exc.offset)) from exc
    return {"format": "XML", "status": "VALIDO", "elements": elements[0],
            "message": "XML bem formado; DTD e entidades externas bloqueados."}
