import io
import json
import pathlib
import sys
import unittest
import zipfile

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parents[3] / "main" / "python"))
from fs20_engine import archive, conversion, i3d, lua, runtime, textures, xml_tools

class EngineTests(unittest.TestCase):
    def test_runtime_info(self):
        info = json.loads(runtime.runtime_info_json())
        self.assertEqual("PRONTO", info["state"])
        self.assertTrue(info["python"].startswith("3."))
        self.assertEqual([], info["third_party"])

    def test_zip_lists_without_extracting(self):
        buf = io.BytesIO()
        with zipfile.ZipFile(buf, "w") as zf: zf.writestr("mod/modDesc.xml", "<modDesc/>")
        self.assertEqual("mod/modDesc.xml", archive.inspect(buf.getvalue())["entries"][0]["name"])

    def test_zip_flags_traversal(self):
        buf = io.BytesIO()
        with zipfile.ZipFile(buf, "w") as zf: zf.writestr("../outside.txt", "x")
        self.assertEqual(["../outside.txt"], archive.inspect(buf.getvalue())["unsafe_entries"])

    def test_xml_accepts_valid_and_counts_nodes(self):
        self.assertEqual(2, xml_tools.inspect(b"<root><child/></root>")["elements"])

    def test_xml_rejects_doctype_and_external_entity(self):
        payload = b'<!DOCTYPE root [<!ENTITY x SYSTEM "file:///etc/passwd">]><root>&x;</root>'
        with self.assertRaises(ValueError): xml_tools.inspect(payload)

    def test_xml_errors_are_in_portuguese(self):
        with self.assertRaisesRegex(ValueError, "linha"):
            xml_tools.inspect(b"<root>")

    def test_i3d_and_shapes_header(self):
        report = i3d.inspect(b'<i3d><Shapes externalShapesFile="model.i3d.shapes"/></i3d>')
        self.assertEqual(["model.i3d.shapes"], report["shapes_references"])
        shapes = i3d.inspect_shapes_header(bytes([5, 0, 7, 0]))
        self.assertEqual("CANDIDATO_DE_CABECALHO", shapes["status"])
        self.assertEqual(5, shapes["version_candidate"])
        self.assertFalse(shapes["payload_decoded"])

    def test_lua_static_delimiter_check(self):
        report = lua.inspect(b"local x = (1 + 2")
        self.assertTrue(report["errors"])
        self.assertIn("não é validação", report["message"])

    def test_png_header(self):
        header = b"\x89PNG\r\n\x1a\n" + b"\x00" * 8 + bytes.fromhex("0000000200000003")
        report = textures.inspect(header, ".png")
        self.assertEqual((2, 3), (report["width"], report["height"]))

    def test_rejects_unknown_extension_and_empty_data(self):
        with self.assertRaises(ValueError): runtime.analyze_bytes("payload.py", b"print(1)")
        with self.assertRaises(ValueError): runtime.analyze_bytes("x.xml", b"")

    def test_rejects_invalid_zip(self):
        with self.assertRaisesRegex(ValueError, "ZIP válido"):
            archive.inspect(b"not a zip")

    def test_mod_assessment_links_i3d_material_textures_and_shapes(self):
        buf = io.BytesIO()
        scene = (b'<i3d><Files><File fileId="4" filename="textures/t.dds"/></Files>'
                 b'<Materials><Material materialId="2"><Texture fileId="4" name="diffuse"/></Material></Materials>'
                 b'<Shapes externalShapesFile="model.i3d.shapes"/>'
                 b'<Scene><Shape shapeId="7" materialIds="2"/></Scene></i3d>')
        with zipfile.ZipFile(buf, "w") as zf:
            zf.writestr("mod/modDesc.xml", '<modDesc descVersion="61"/>')
            zf.writestr("mod/model.i3d", scene)
            zf.writestr("mod/model.i3d.shapes", bytes([5, 0, 7, 0]))
            zf.writestr("mod/textures/t.dds", b"DDS " + b"\0" * 124)
        result = conversion.assess_zip(buf.getvalue())
        scene_report = result["i3d"][0]
        resolved = {(item["type"], item["resolved_path"]) for item in scene_report["references"]}
        self.assertIn(("geometria", "mod/model.i3d.shapes"), resolved)
        self.assertIn(("textura", "mod/textures/t.dds"), resolved)
        self.assertEqual(["7"], scene_report["shape_ids"])
        self.assertEqual(["2"], scene_report["scene_shapes"][0]["material_ids"])
        self.assertEqual("2", scene_report["scene_shapes"][0]["materials"][0]["id"])
        self.assertEqual("mod/textures/t.dds", scene_report["materials"][0]["maps"][0]["resolved_path"])
        self.assertFalse(result["export_allowed"])
        self.assertEqual([], result["converted"])

    def test_mod_assessment_marks_missing_i3d_reference(self):
        buf = io.BytesIO()
        with zipfile.ZipFile(buf, "w") as zf:
            zf.writestr("mod/a.i3d", b'<i3d><Shapes externalShapesFile="missing.i3d.shapes"/></i3d>')
        result = conversion.assess_zip(buf.getvalue())
        self.assertTrue(any("não existe" in item["detail"] for item in result["incompatible"]))

    def test_mod_assessment_rejects_doctype_without_resolving_entities(self):
        buf = io.BytesIO()
        malicious = b'<!DOCTYPE i3d [<!ENTITY x SYSTEM "file:///etc/passwd">]><i3d>&x;</i3d>'
        with zipfile.ZipFile(buf, "w") as zf:
            zf.writestr("mod/a.i3d", malicious)
        result = conversion.assess_zip(buf.getvalue())
        self.assertTrue(any("DOCTYPE" in item["detail"] for item in result["incompatible"]))

    def test_mod_assessment_rejects_ambiguous_duplicate_paths(self):
        buf = io.BytesIO()
        with zipfile.ZipFile(buf, "w") as zf:
            zf.writestr("mod/file.txt", "one")
            zf.writestr("mod/file.txt", "two")
        with self.assertRaisesRegex(ValueError, "duplicados"):
            conversion.assess_zip(buf.getvalue())

    def test_mod_assessment_never_executes_lua_and_does_not_claim_conversion(self):
        buf = io.BytesIO()
        with zipfile.ZipFile(buf, "w") as zf:
            zf.writestr("mod/main.lua", b'os.execute("touch /tmp/fs20-test")')
        before = buf.getvalue()
        report = conversion.assess_zip(before)
        self.assertEqual([], report["converted"])
        self.assertFalse(report["export_allowed"])
        self.assertEqual(before, buf.getvalue())

    def test_mod_assessment_detects_dds_subformats_without_claiming_output_conversion(self):
        dds = bytearray(128)
        dds[:4] = b"DDS "
        dds[4:8] = (124).to_bytes(4, "little")
        dds[12:16] = (4).to_bytes(4, "little")
        dds[16:20] = (4).to_bytes(4, "little")
        dds[80:84] = (4).to_bytes(4, "little")
        dds[84:88] = b"DXT1"
        self.assertIn("decodificável", conversion._dds_status(dds, len(dds)))
        dds[84:88] = b"DX10"
        self.assertIn("não é decodificado", conversion._dds_status(dds, len(dds)))

    def test_mod_desc_version_parser_accepts_utf16_and_rejects_dtd(self):
        xml = '<modDesc descVersion="61"/>'.encode("utf-16")
        self.assertEqual("61", conversion._parse_desc_version(xml))
        malicious = b'<!DOCTYPE modDesc SYSTEM "file:///etc/passwd"><modDesc/>'
        with self.assertRaisesRegex(ValueError, "DOCTYPE"):
            conversion._parse_desc_version(malicious)

    def test_mod_assessment_rejects_zip_with_bad_crc(self):
        buf = io.BytesIO()
        with zipfile.ZipFile(buf, "w", compression=zipfile.ZIP_STORED) as zf:
            zf.writestr("mod/data.bin", b"123456789")
        damaged = bytearray(buf.getvalue())
        payload = damaged.find(b"123456789")
        damaged[payload] ^= 1
        with self.assertRaisesRegex(ValueError, "corrompido"):
            conversion.assess_zip(bytes(damaged))

    def test_i3d_resolver_handles_relative_backslashes_and_moddir(self):
        paths = {"vehicles/tractor/i3d/model.i3d", "vehicles/shared/textures/body.dds",
                 "shared/common.xml"}
        backslash = conversion.resolve_i3d_reference(
            "vehicles/tractor/i3d/model.i3d", r"..\..\shared\textures\body.dds", paths)
        moddir = conversion.resolve_i3d_reference(
            "vehicles/tractor/i3d/model.i3d", "$moddir/shared/common.xml", paths)
        self.assertEqual(("ENCONTRADO", "vehicles/shared/textures/body.dds"),
                         (backslash["status"], backslash["path"]))
        self.assertEqual(("ENCONTRADO", "shared/common.xml"),
                         (moddir["status"], moddir["path"]))

    def test_i3d_resolver_distinguishes_game_data_external_and_missing(self):
        base, paths = "vehicle/tractor.i3d", {"vehicle/local.png"}
        game = conversion.resolve_i3d_reference(base, r"$data\shared\wheel.dds", paths)
        external = conversion.resolve_i3d_reference(base, "https://example.invalid/image.dds", paths)
        missing = conversion.resolve_i3d_reference(base, "textures/absent.dds", paths)
        self.assertEqual("DADOS_DO_JOGO", game["status"])
        self.assertEqual("EXTERNO", external["status"])
        self.assertEqual("AUSENTE", missing["status"])

    def test_duplicate_texture_diagnostics_are_collapsed(self):
        buf = io.BytesIO()
        dds = bytearray(128)
        dds[:4] = b"DDS "
        dds[4:8] = (124).to_bytes(4, "little")
        dds[12:16] = (4).to_bytes(4, "little")
        dds[16:20] = (4).to_bytes(4, "little")
        dds[80:84] = (4).to_bytes(4, "little")
        dds[84:88] = b"DXT1"
        scene = (b'<i3d><Files><File fileId="1" filename="textures\\body.dds"/>'
                 b'<File fileId="2" filename="textures/body.dds"/></Files><Materials>'
                 b'<Material materialId="1"><Texture fileId="1" name="diffuse"/></Material>'
                 b'<Material materialId="2"><Texture fileId="2" name="diffuse"/></Material>'
                 b'</Materials></i3d>')
        with zipfile.ZipFile(buf, "w") as zf:
            zf.writestr("mod/vehicle.i3d", scene)
            zf.writestr("mod/textures/body.dds", dds)
        result = conversion.assess_zip(buf.getvalue())
        dds_messages = [item for item in result["pending"] if item["name"].endswith("body.dds")]
        self.assertEqual(1, len(dds_messages))
        self.assertEqual([], result["converted"])
        self.assertEqual(0, result["totals"]["converted"])

    def test_i3d_report_does_not_call_game_data_or_external_files_missing(self):
        buf = io.BytesIO()
        xml = (b'<i3d><Files><File fileId="1" filename="$data\\shared\\wheel.dds"/>'
               b'<File fileId="2" filename="$pdlc1/shared/external.dds"/>'
               b'<File fileId="3" filename="textures/absent.dds"/></Files></i3d>')
        with zipfile.ZipFile(buf, "w") as zf:
            zf.writestr("mod/vehicle.i3d", xml)
        result = conversion.assess_zip(buf.getvalue())
        refs = result["i3d"][0]["references"]
        statuses = {ref["reference"]: ref["status"] for ref in refs}
        self.assertEqual("DADOS_DO_JOGO", statuses[r"$data\shared\wheel.dds"])
        self.assertEqual("EXTERNO", statuses["$pdlc1/shared/external.dds"])
        self.assertEqual("AUSENTE", statuses["textures/absent.dds"])
        self.assertEqual(1, len(result["incompatible"]))

if __name__ == "__main__": unittest.main()
