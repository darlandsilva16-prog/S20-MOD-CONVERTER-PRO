import io
import json
import pathlib
import sys
import unittest
import zipfile

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parents[2] / "main" / "python"))
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
        self.assertEqual(5, i3d.inspect_shapes_header(bytes([5, 0, 7, 0]))["version"])

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
        self.assertEqual("mod/textures/t.dds", scene_report["materials"][0]["maps"][0]["resolved_path"])
        self.assertFalse(result["export_allowed"])
        self.assertEqual([], result["converted"])

    def test_mod_assessment_marks_missing_i3d_reference(self):
        buf = io.BytesIO()
        with zipfile.ZipFile(buf, "w") as zf:
            zf.writestr("mod/a.i3d", b'<i3d><Shapes externalShapesFile="missing.i3d.shapes"/></i3d>')
        result = conversion.assess_zip(buf.getvalue())
        self.assertTrue(any("ausente" in item["detail"] for item in result["incompatible"]))

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

if __name__ == "__main__": unittest.main()
