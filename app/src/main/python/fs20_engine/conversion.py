"""Avaliação de mods somente leitura; nunca anuncia cópia ou conversão como FS20 compatível."""
import io
import posixpath
import re
import zipfile
from collections import Counter
from xml.parsers import expat

from .i3d import inspect_shapes_header

MAX_ENTRIES = 20000
MAX_ARCHIVE_BYTES = 32 * 1024 * 1024
MAX_EXPANDED = 256 * 1024 * 1024
MAX_XML = 16 * 1024 * 1024
MAX_REPORT_ITEMS = 1000


def assess_zip(data):
    """Inventaria e relaciona dependências I3D sem extrair, decodificar ou converter."""
    if not isinstance(data, (bytes, bytearray)) or not data:
        raise ValueError("O ZIP está vazio.")
    if len(data) > MAX_ARCHIVE_BYTES:
        raise ValueError("O ZIP excede o limite comprimido de 32 MiB.")
    converted, incompatible, pending = [], [], []
    try:
        archive = zipfile.ZipFile(io.BytesIO(data), "r")
        infos = archive.infolist()
    except (zipfile.BadZipFile, OSError) as exc:
        raise ValueError("O arquivo selecionado não contém um ZIP válido.") from exc
    if not infos or len(infos) > MAX_ENTRIES:
        raise ValueError("ZIP vazio ou acima do limite de 20 mil entradas.")

    by_path = {}
    total = 0
    for info in infos:
        raw_name = info.filename
        name = raw_name.replace("\\", "/")
        parts = name.rstrip("/").split("/")
        mode = (info.external_attr >> 16) & 0xF000
        if (not name or name.endswith("//") or name.startswith("/") or re.match(r"^[A-Za-z]:", name)
                or "\\" in raw_name or any(p in ("", ".", "..") for p in parts)
                or info.flag_bits & 1 or mode == 0xA000):
            raise ValueError("ZIP contém caminho inseguro, item criptografado ou link simbólico: " + raw_name[:160])
        key = name.rstrip("/")
        if key in by_path:
            raise ValueError("ZIP contém caminhos duplicados ou ambíguos: " + key[:160])
        if info.file_size < 0 or info.file_size > MAX_EXPANDED:
            raise ValueError("Uma entrada excede o limite de tamanho seguro.")
        if info.is_dir() and info.file_size != 0:
            raise ValueError("Uma entrada declarada como pasta contém dados.")
        total += info.file_size
        if total > MAX_EXPANDED:
            raise ValueError("O conteúdo descompactado excede 256 MiB.")
        by_path[key] = info

    # Ler cada entrada em blocos valida CRC e tamanho sem manter o ZIP expandido na memória.
    actual_total = 0
    try:
        for info in infos:
            if info.is_dir():
                continue
            actual_entry = 0
            with archive.open(info, "r") as stream:
                while True:
                    chunk = stream.read(64 * 1024)
                    if not chunk:
                        break
                    actual_entry += len(chunk)
                    actual_total += len(chunk)
                    if actual_entry > MAX_EXPANDED or actual_total > MAX_EXPANDED:
                        raise ValueError("Conteúdo real do ZIP excede 256 MiB.")
            if actual_entry != info.file_size:
                raise ValueError("Tamanho real divergente no item ZIP: " + info.filename[:160])
    except (OSError, RuntimeError, zipfile.BadZipFile, NotImplementedError) as exc:
        raise ValueError("ZIP corrompido ou com método de compressão não suportado: " + str(exc)[:160]) from exc

    resolve = _make_path_resolver({path for path, entry in by_path.items() if not entry.is_dir()})
    seen_incompatible, seen_pending = set(), set()

    mod_descs = [p for p in by_path if not by_path[p].is_dir()
                 and posixpath.basename(p).lower() == "moddesc.xml"]
    for desc in mod_descs:
        info = by_path[desc]
        if info.file_size > MAX_XML:
            _append_unique(pending, seen_pending, ("MODDESC_LIMIT", desc.casefold()),
                           _item(desc, "modDesc.xml excede 16 MiB; versão de origem não foi lida."))
            continue
        try:
            with archive.open(info) as stream:
                xml = stream.read(MAX_XML + 1)
            if len(xml) > MAX_XML:
                raise ValueError("XML acima do limite.")
            declared = _parse_desc_version(xml)
            _append_unique(pending, seen_pending, ("MODDESC_VERSION", (declared or "").casefold()),
                           _item(desc, "descVersion=" + declared + "; não há mapeamento confiável para compatibilidade FS20." if declared else "descVersion não identificada; compatibilidade de versão pendente."))
        except Exception as exc:
            reason = str(exc)[:160]
            _append_unique(pending, seen_pending, ("MODDESC_ERROR", reason.casefold()),
                           _item(desc, "Não foi possível analisar modDesc.xml com segurança: " + reason))

    links = []
    i3d_inventory = []
    duplicate_references = []
    duplicate_identifiers = []
    resource_groups = {"found": [], "missing": [], "unverifiable": [], "invalid": []}
    resource_seen = {key: set() for key in resource_groups}
    resource_totals = Counter()
    inventory_counts = Counter()
    extension_counts = Counter()
    shapes_inventory = []
    shapes_by_path = {}
    texture_extensions = (".dds", ".png", ".jpg", ".jpeg", ".bmp", ".tga")
    for path, info in by_path.items():
        if info.is_dir():
            continue
        dot = path.rfind(".")
        extension_counts[path[dot:].lower() if dot >= 0 else "[sem extensão]"] += 1
        if path.lower().endswith(texture_extensions):
            inventory_counts["texture_files"] += 1
    i3d_paths = [p for p, item in by_path.items() if not item.is_dir() and p.lower().endswith(".i3d")]
    shapes_paths = [p for p, item in by_path.items() if not item.is_dir() and p.lower().endswith(".i3d.shapes")]

    for path in shapes_paths[:MAX_REPORT_ITEMS]:
        try:
            with archive.open(by_path[path], "r") as stream:
                header = stream.read(4)
            shapes_row = {"path": path, **inspect_shapes_header(header)}
            shapes_inventory.append(shapes_row)
            shapes_by_path[path] = shapes_row
        except Exception as exc:
            shapes_row = {"path": path, "status": "CABECALHO_INVALIDO",
                          "header_hex": "", "payload_decoded": False,
                          "message": str(exc)[:180]}
            shapes_inventory.append(shapes_row)
            shapes_by_path[path] = shapes_row

    def add_resource(category, row, identity):
        if category not in resource_groups or identity in resource_seen[category]:
            return
        resource_seen[category].add(identity)
        resource_totals[category] += 1
        if len(resource_groups[category]) < MAX_REPORT_ITEMS:
            resource_groups[category].append(row)

    for path in i3d_paths[:MAX_REPORT_ITEMS]:
        info = by_path[path]
        if info.file_size > MAX_XML:
            i3d_inventory.append({"path": path, "status": "LIMITE_EXCEDIDO", "models": 0,
                                  "materials": 0, "references": 0,
                                  "message": "I3D acima de 16 MiB; conteúdo não foi analisado."})
            _append_unique(pending, seen_pending, ("I3D_LIMIT", path.casefold()),
                           _item(path, "I3D acima de 16 MiB; referências não foram analisadas."))
            continue
        try:
            with archive.open(info) as stream:
                xml = stream.read(MAX_XML + 1)
            if len(xml) > MAX_XML:
                raise ValueError("I3D acima de 16 MiB.")
            scene = _parse_i3d(xml)
        except Exception as exc:
            reason = str(exc)[:180]
            i3d_inventory.append({"path": path, "status": "XML_INVALIDO", "models": 0,
                                  "materials": 0, "references": 0, "message": reason})
            _append_unique(incompatible, seen_incompatible, ("I3D_ERROR", path.casefold(), reason.casefold()),
                           _item(path, "I3D inválido ou não seguro: " + reason))
            continue

        inventory_counts["i3d_analyzed"] += 1
        inventory_counts["models"] += len(scene["scene_shapes"])
        inventory_counts["materials"] += len(scene["materials"])
        inventory_counts["texture_references"] += sum(1 for kind, _, _ in scene["references"] if kind == "textura")
        inventory_counts["shapes_references"] += sum(1 for kind, _, _ in scene["references"] if kind == "geometria")
        references_by_key = {}
        for kind, ref, owner in scene["references"]:
            result = resolve(path, ref)
            target, status = result["path"], result["status"]
            key = (kind, status, (target or ref).casefold())
            row = references_by_key.get(key)
            if row is None:
                row = {"type": kind, "reference": ref, "owner": owner,
                       "owners": [owner], "resolved_path": target,
                       "status": status, "issue": result["reason"], "occurrences": 0}
                if kind == "geometria" and status == "ENCONTRADO" and target in shapes_by_path:
                    row["shapes_header"] = shapes_by_path[target]
                references_by_key[key] = row
            row["occurrences"] += 1
            if owner not in row["owners"]:
                row["owners"].append(owner)

        for key, row in references_by_key.items():
            status = row["status"]
            if status == "ENCONTRADO":
                category = "found"
            elif status == "AUSENTE":
                category = "missing"
            elif status in ("DADOS_DO_JOGO", "EXTERNO", "CAIXA_DIFERENTE"):
                category = "unverifiable"
            else:
                category = "invalid"
            identity = (path.casefold(), row["type"], status, (row["resolved_path"] or row["reference"]).casefold())
            group_row = dict(row)
            group_row["i3d"] = path
            add_resource(category, group_row, identity)
            if row["occurrences"] > 1:
                duplicate = dict(group_row)
                duplicate["duplicate_count"] = row["occurrences"]
                duplicate["detail"] = "A mesma dependência aparece mais de uma vez na cena; a repetição é informativa e não implica erro."
                duplicate_references.append(duplicate)

            if status in ("AUSENTE", "AMBIGUO", "FORA_DO_MOD", "INVALIDO"):
                issue_key = (status, (row["resolved_path"] or row["reference"]).casefold())
                _append_unique(incompatible, seen_incompatible, issue_key,
                               _item(row["resolved_path"] or row["reference"], row["issue"]))
            elif status in ("EXTERNO", "CAIXA_DIFERENTE"):
                issue_key = (status, (row["resolved_path"] or row["reference"]).casefold())
                _append_unique(pending, seen_pending, issue_key,
                               _item(row["resolved_path"] or row["reference"], row["issue"]))
            elif status == "ENCONTRADO" and row["type"] == "textura" and row["resolved_path"].lower().endswith(".dds"):
                with archive.open(by_path[row["resolved_path"]], "r") as stream:
                    header = stream.read(128)
                _append_unique(pending, seen_pending, ("DDS", row["resolved_path"].casefold()),
                               _item(row["resolved_path"], _dds_status(header, by_path[row["resolved_path"]].file_size)))
            elif status == "ENCONTRADO" and row["type"] == "geometria":
                _append_unique(pending, seen_pending, ("SHAPES", row["resolved_path"].casefold()),
                               _item(row["resolved_path"], "SHAPES relacionado; somente o cabeçalho observado é reportado nesta análise."))
        for material in scene["materials"]:
            for texture_map in material["maps"]:
                if texture_map["path"]:
                    mapping = resolve(path, texture_map["path"])
                    texture_map["resolved_path"] = mapping["path"]
                    texture_map["status"] = mapping["status"]
                    texture_map["issue"] = mapping["reason"]
                    texture_map["dependency_category"] = _dependency_category(mapping["status"])
                else:
                    texture_map["resolved_path"] = None
                    texture_map["status"] = "INVALIDO"
                    texture_map["issue"] = "O fileId da textura não corresponde a uma entrada File neste I3D."
                    texture_map["dependency_category"] = "invalid"
                    missing_file_id = "fileId=" + str(texture_map["file_id"])
                    invalid_row = {"i3d": path, "type": "fileId", "reference": missing_file_id,
                                   "resolved_path": None, "status": "INVALIDO",
                                   "issue": texture_map["issue"]}
                    add_resource("invalid", invalid_row,
                                 (path.casefold(), "fileId", missing_file_id.casefold()))
                    _append_unique(incompatible, seen_incompatible,
                                   ("TEXTURE_FILE_ID", path.casefold(), missing_file_id.casefold()),
                                   _item(path + " · " + missing_file_id, texture_map["issue"]))

        materials_by_id = {material["id"]: material for material in scene["materials"]}
        geometry_dependencies = [row for row in references_by_key.values() if row["type"] == "geometria"]
        model_dependencies = []
        for model in scene["scene_shapes"]:
            linked_materials = [materials_by_id[mid] for mid in model["material_ids"] if mid in materials_by_id]
            missing_material_ids = [mid for mid in model["material_ids"] if mid not in materials_by_id]
            for missing_id in missing_material_ids:
                missing_row = {"i3d": path, "type": "materialId", "reference": missing_id,
                               "resolved_path": None, "status": "INVALIDO",
                               "issue": "materialIds não corresponde a uma definição materialId neste I3D."}
                add_resource("invalid", missing_row,
                             (path.casefold(), "materialId", missing_id.casefold()))
                _append_unique(incompatible, seen_incompatible,
                               ("MATERIAL_ID", path.casefold(), missing_id.casefold()),
                               _item(path + " · materialId=" + missing_id, missing_row["issue"]))
            dependencies = []
            for geometry in geometry_dependencies:
                dependencies.append({"type": "geometria", "reference": geometry["reference"],
                                     "resolved_path": geometry["resolved_path"], "status": geometry["status"],
                                     "shapes_header": geometry.get("shapes_header")})
            for material in linked_materials:
                for texture_map in material["maps"]:
                    dependencies.append({"type": "textura", "material_id": material["id"],
                                         "map": texture_map["map"],
                                         "reference": texture_map["path"] or ("fileId=" + str(texture_map["file_id"])),
                                         "resolved_path": texture_map.get("resolved_path"),
                                         "status": texture_map.get("status", "NAO_VERIFICAVEL"),
                                         "issue": texture_map.get("issue", "")})
            model_dependencies.append({"element": model["element"], "name": model["name"],
                                       "shape_id": model["shape_id"],
                                       "material_ids": model["material_ids"],
                                       "materials": linked_materials,
                                       "missing_material_ids": missing_material_ids,
                                       "dependencies": dependencies})

        links.append({"i3d": path, "shape_ids": scene["shape_ids"],
                      "scene_shapes": scene["scene_shapes"], "model_dependencies": model_dependencies,
                      "materials": scene["materials"], "references": list(references_by_key.values()),
                      "duplicate_identifiers": scene["duplicate_identifiers"]})
        i3d_inventory.append({"path": path, "status": "ANALISADO",
                              "models": len(scene["scene_shapes"]), "materials": len(scene["materials"]),
                              "references": len(scene["references"]),
                              "duplicate_identifiers": len(scene["duplicate_identifiers"]),
                              "external_shapes": [row["reference"] for row in references_by_key.values()
                                                  if row["type"] == "geometria"]})
        for duplicate in scene["duplicate_identifiers"]:
            duplicate_identifiers.append({"i3d": path, "type": duplicate["kind"],
                                          "reference": duplicate["id"], "status": "DUPLICADO",
                                          "detail": "Identificador repetido no mesmo I3D; confira a associação antes de confiar no vínculo.",
                                          **{k: v for k, v in duplicate.items() if k not in ("kind", "id")}})
        _append_unique(pending, seen_pending, ("I3D", path.casefold()),
                       _item(path, "I3D analisado e relacionado; a transformação de cena para FS20 não está implementada."))

    if len(i3d_paths) > MAX_REPORT_ITEMS:
        _append_unique(pending, seen_pending, ("I3D_LIMIT",),
                       _item("I3D", "Relatório limitado às primeiras 1.000 cenas."))
    if not mod_descs:
        _append_unique(pending, seen_pending, ("MODDESC",),
                       _item("modDesc.xml", "Não encontrado; metadados do jogo de origem não identificados."))
    _append_unique(pending, seen_pending, ("FS20",),
                   _item("FS20", "FS20 mobile não oferece suporte oficial a mods; não é possível validar um ZIP instalável/compatível para esse destino."))
    for key, rows in resource_groups.items():
        inventory_counts["resources_" + key] = resource_totals[key]
    inventory_counts["duplicate_references"] = len(duplicate_references)
    inventory_counts["duplicate_identifiers"] = len(duplicate_identifiers)
    inventory_counts["i3d_files"] = len(i3d_paths)
    inventory_counts["shapes_files"] = len(shapes_paths)
    inventory_counts["texture_files"] = sum(1 for p in by_path if not by_path[p].is_dir()
                                             and p.lower().endswith(texture_extensions))
    return {"status": "ANALISE_SEM_CONVERSAO", "target": "FS20 mobile",
            "converted": converted, "incompatible": incompatible[:MAX_REPORT_ITEMS],
            "pending": pending[:MAX_REPORT_ITEMS], "i3d": links,
            "i3d_inventory": i3d_inventory[:MAX_REPORT_ITEMS],
            "shapes_inventory": shapes_inventory,
            "resource_groups": resource_groups,
            "duplicate_references": duplicate_references[:MAX_REPORT_ITEMS],
            "duplicate_identifiers": duplicate_identifiers[:MAX_REPORT_ITEMS],
            "inventory": {"files": len([i for i in infos if not i.is_dir()]),
                          "extensions": dict(sorted(extension_counts.items())),
                          "i3d_files": len(i3d_paths), "shapes_files": len(shapes_paths),
                          "texture_files": inventory_counts["texture_files"],
                          "detail_limit_per_category": MAX_REPORT_ITEMS},
            "totals": {"converted": 0, "incompatible": len(incompatible), "pending": len(pending),
                       **dict(inventory_counts)},
            "export_allowed": False,
            "message": "Nenhuma conversão foi executada. A exportação foi bloqueada porque não há transformação validada para FS20; o mod original permanece inalterado."}


def _dependency_category(status):
    if status == "ENCONTRADO":
        return "found"
    if status == "AUSENTE":
        return "missing"
    if status in ("DADOS_DO_JOGO", "EXTERNO", "CAIXA_DIFERENTE"):
        return "unverifiable"
    return "invalid"


def _item(name, detail):
    return {"name": str(name)[:512], "detail": str(detail)[:512]}


def resolve_i3d_reference(i3d_path, reference, archive_paths):
    """Resolve e classifica referências sem confundir $data ou arquivos externos com ausências."""
    return _make_path_resolver(set(archive_paths))(i3d_path, reference)


def _make_path_resolver(archive_paths):
    exact = set(archive_paths)
    lower_paths, ambiguous_case_paths = {}, set()
    for path in exact:
        key = path.lower()
        if key in lower_paths and lower_paths[key] != path:
            ambiguous_case_paths.add(key)
        else:
            lower_paths[key] = path

    def resolve(base, reference):
        if not isinstance(reference, str):
            return {"path": None, "status": "INVALIDO", "reason": "Referência não textual no I3D."}
        original = "" if reference is None else reference.strip()
        ref = original.replace("\\", "/")
        if not ref or any(ord(char) < 32 for char in ref):
            return {"path": None, "status": "INVALIDO", "reason": "Referência vazia ou com caractere de controle."}
        if re.match(r"^\$data(?:/|$)", ref, re.IGNORECASE):
            suffix = ref[5:].lstrip("/")
            normalized = posixpath.normpath(suffix) if suffix else ""
            if normalized == ".." or normalized.startswith("../"):
                return {"path": ref, "status": "INVALIDO", "reason": "A referência $data sai da raiz dos dados do jogo."}
            return {"path": "$data" + ("/" + normalized if normalized else ""),
                    "status": "DADOS_DO_JOGO", "reason": "Referência aos dados internos do jogo; não é um arquivo ausente do ZIP."}
        if re.match(r"^\$moddir(?:/|$)", ref, re.IGNORECASE):
            ref = ref[7:].lstrip("/")
            base = ""
        elif ref.startswith("$"):
            return {"path": ref, "status": "EXTERNO", "reason": "Alias externo do jogo ou DLC; conteúdo não fornecido pelo ZIP."}
        elif "://" in ref or ref.startswith("/") or re.match(r"^[A-Za-z]:", ref):
            return {"path": ref, "status": "EXTERNO", "reason": "Caminho absoluto ou URI externa; não foi tratado como arquivo ausente do ZIP."}

        normalized = posixpath.normpath(posixpath.join(posixpath.dirname(base), ref))
        if normalized == ".." or normalized.startswith("../") or normalized.startswith("/"):
            return {"path": normalized, "status": "FORA_DO_MOD", "reason": "A referência sai da raiz do mod."}
        if normalized in exact:
            return {"path": normalized, "status": "ENCONTRADO", "reason": ""}
        if normalized.lower() in ambiguous_case_paths:
            return {"path": normalized, "status": "AMBIGUO", "reason": "Há arquivos com o mesmo caminho e capitalização diferente no ZIP."}
        case_match = lower_paths.get(normalized.lower())
        if case_match:
            return {"path": case_match, "status": "CAIXA_DIFERENTE", "reason": "Arquivo encontrado apenas após ignorar maiúsculas/minúsculas."}
        return {"path": normalized, "status": "AUSENTE", "reason": "Arquivo referenciado não existe no ZIP nem corresponde a um alias de dados do jogo."}

    return resolve


def _append_unique(items, seen, key, item):
    if key not in seen:
        seen.add(key)
        items.append(item)


def _dds_status(header, size):
    if len(header) < 128 or header[:4] != b"DDS " or int.from_bytes(header[4:8], "little") != 124:
        return "DDS com cabeçalho inválido; nenhuma conversão foi feita."
    height = int.from_bytes(header[12:16], "little")
    width = int.from_bytes(header[16:20], "little")
    if width < 1 or height < 1 or width * height > 16_000_000:
        return "DDS com dimensões inválidas ou acima do limite do decodificador."
    flags = int.from_bytes(header[80:84], "little")
    code = header[84:88]
    supported = (flags & 4 and code in (b"DXT1", b"DXT3", b"DXT5"))
    if not supported and not (flags & 0x40 and int.from_bytes(header[88:92], "little") == 32
                              and _valid_rgba_masks(header[92:108])):
        label = code.decode("ascii", "replace").strip("\0") or "pixel format sem FourCC"
        return "DDS identificado (" + label + "), mas o subformato não é decodificado por este aplicativo; pendente."
    if size > 32 * 1024 * 1024:
        return "DDS usa formato decodificável, mas excede o limite de 32 MiB do módulo; pendente."
    return "DDS " + (code.decode("ascii", "replace") if supported else "RGB 32 bits") + ": subformato decodificável pelo Conversor de Texturas; não convertido no pacote porque não existe perfil de saída FS20 validado."


def _valid_rgba_masks(data):
    masks = [int.from_bytes(data[i:i + 4], "little") for i in (0, 4, 8, 12)]
    red, green, blue, alpha = masks
    def contiguous(mask):
        if not mask:
            return False
        normalized = mask >> ((mask & -mask).bit_length() - 1)
        return normalized & (normalized + 1) == 0
    return (contiguous(red) and contiguous(green) and contiguous(blue)
            and not (red & green or red & blue or green & blue)
            and (alpha == 0 or contiguous(alpha) and not (alpha & (red | green | blue))))


def _parse_desc_version(data):
    parser = expat.ParserCreate()
    result = [None]
    elements = [0]

    def start(name, attrs):
        elements[0] += 1
        if elements[0] > 100000:
            raise ValueError("modDesc.xml excede 100 mil elementos.")
        if name.rsplit("}", 1)[-1].lower() == "moddesc":
            result[0] = attrs.get("descVersion")

    def forbidden(*_):
        raise ValueError("DOCTYPE e entidades não são permitidos em modDesc.xml.")

    parser.StartElementHandler = start
    parser.StartDoctypeDeclHandler = forbidden
    parser.EntityDeclHandler = forbidden
    parser.ExternalEntityRefHandler = forbidden
    parser.SetParamEntityParsing(expat.XML_PARAM_ENTITY_PARSING_NEVER)
    parser.Parse(data, True)
    return result[0]


def _parse_i3d(data):
    parser = expat.ParserCreate(namespace_separator="}")
    files, materials, refs, shape_ids, scene_shapes = {}, {}, [], [], []
    duplicate_identifiers = []
    elements = 0
    material_stack = []
    in_scene = [False]
    def start(name, attrs):
        nonlocal elements
        elements += 1
        if elements > 200000:
            raise ValueError("I3D excede 200 mil elementos.")
        tag = name.rsplit("}", 1)[-1].lower()
        if tag == "scene":
            in_scene[0] = True
        if tag == "include" and name.startswith("http://www.w3.org/2001/XInclude}"):
            raise ValueError("I3D contém XInclude, que não é permitido.")
        if tag == "file":
            file_id = attrs.get("fileId")
            filename = attrs.get("filename")
            if file_id and filename is not None and len(files) < 20000:
                if file_id in files:
                    duplicate_identifiers.append({"kind": "fileId", "id": file_id,
                                                  "first": files[file_id], "repeated": filename})
                else:
                    files[file_id] = filename
        elif tag == "material":
            material_id = attrs.get("materialId") or attrs.get("name") or "material"
            maps = []
            for key, value in attrs.items():
                key_l = key.rsplit("}", 1)[-1].lower()
                if (key_l.endswith("mapid") or key_l.endswith("textureid")) and len(maps) < 64:
                    maps.append((key_l, value))
            if len(materials) < MAX_REPORT_ITEMS:
                if material_id in materials:
                    duplicate_identifiers.append({"kind": "materialId", "id": material_id})
                else:
                    materials[material_id] = maps
                material_stack.append(material_id)
            else:
                material_stack.append(None)
        elif tag == "texture" and material_stack:
            file_id = attrs.get("fileId")
            material_id = material_stack[-1]
            if file_id and material_id in materials and len(materials[material_id]) < 64:
                map_name = attrs.get("name") or attrs.get("type") or "texture"
                materials[material_id].append((map_name, file_id))
        elif tag in ("shape", "model") and in_scene[0]:
            shape_id = attrs.get("shapeId")
            if shape_id and shape_id not in shape_ids and len(shape_ids) < MAX_REPORT_ITEMS:
                shape_ids.append(shape_id)
            material_ids = attrs.get("materialIds", "").split()
            if shape_id and len(scene_shapes) < MAX_REPORT_ITEMS:
                scene_shapes.append({"element": tag, "name": attrs.get("name", ""),
                                     "shape_id": shape_id, "material_ids": material_ids[:64]})
        ext = attrs.get("externalShapesFile")
        if ext:
            refs.append(("geometria", ext, "externalShapesFile"))

    def forbidden(*_):
        raise ValueError("I3D contém DOCTYPE/entidade externa, não permitido.")

    def end(name):
        tag = name.rsplit("}", 1)[-1].lower()
        if tag == "material" and material_stack:
            material_stack.pop()
        elif tag == "scene":
            in_scene[0] = False

    parser.StartElementHandler = start
    parser.EndElementHandler = end
    parser.StartDoctypeDeclHandler = forbidden
    parser.EntityDeclHandler = forbidden
    parser.ExternalEntityRefHandler = forbidden
    parser.SetParamEntityParsing(expat.XML_PARAM_ENTITY_PARSING_NEVER)
    parser.Parse(data, True)
    for file_id, filename in files.items():
        if len(refs) < MAX_REPORT_ITEMS:
            refs.append(("textura" if filename.lower().endswith((".dds", ".png", ".jpg", ".jpeg", ".tga", ".bmp")) else "arquivo", filename[:512], "fileId=" + file_id[:128]))
    material_report = []
    for material, maps in materials.items():
        map_report = []
        for map_name, file_id in maps:
            filename = files.get(file_id)
            if filename:
                if len(refs) < MAX_REPORT_ITEMS:
                    refs.append(("textura", filename[:512], "material=" + material[:128] + "/" + map_name))
                map_report.append({"map": map_name, "file_id": file_id, "path": filename[:512]})
            else:
                if len(refs) < MAX_REPORT_ITEMS:
                    refs.append(("material", "fileId=" + file_id[:128], "material=" + material[:128] + "/" + map_name))
                map_report.append({"map": map_name, "file_id": file_id, "path": None})
        material_report.append({"id": material, "maps": map_report})
    if len(refs) > MAX_REPORT_ITEMS:
        refs = refs[:MAX_REPORT_ITEMS]
    material_report = material_report[:MAX_REPORT_ITEMS]
    materials_by_id = {item["id"]: item for item in material_report}
    for shape in scene_shapes:
        related, missing = [], []
        for material_id in shape["material_ids"]:
            material = materials_by_id.get(material_id)
            if material is None:
                missing.append(material_id)
            else:
                related.append(material)
        shape["materials"] = related
        shape["missing_material_ids"] = missing
    return {"references": refs, "shape_ids": shape_ids, "scene_shapes": scene_shapes,
            "materials": material_report, "duplicate_identifiers": duplicate_identifiers[:MAX_REPORT_ITEMS]}
