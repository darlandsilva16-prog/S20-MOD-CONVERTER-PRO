import io
import json
import pathlib
import sys
import unittest
import zipfile

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parents[2] / "main" / "python"))
from fs20_engine import archive, i3d, lua, runtime, textures, xml_tools

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

if __name__ == "__main__": unittest.main()
