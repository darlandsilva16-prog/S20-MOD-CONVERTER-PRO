import io
import pathlib
import sys
import unittest
import zipfile

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parents[3] / "main" / "python"))
from fs20_engine import conversion, i3d


def make_zip(entries):
    buffer = io.BytesIO()
    with zipfile.ZipFile(buffer, "w", compression=zipfile.ZIP_DEFLATED) as archive:
        for name, content in entries.items():
            archive.writestr(name, content)
    return buffer.getvalue()


class I3dDependencyAnalyzerSyntheticTests(unittest.TestCase):
    def test_associa_model_material_texture_e_shapes_sem_decodificar_shapes(self):
        scene = (b'<i3d><Files>'
                 b'<File fileId="1" filename="textures/body.png"/>'
                 b'<File fileId="2" filename="textures/body.png"/>'
                 b'<File fileId="3" filename="model.i3d.shapes"/>'
                 b'</Files><Materials>'
                 b'<Material materialId="paint"><Texture fileId="1" name="diffuse"/></Material>'
                 b'<Material materialId="trim"><Texture fileId="2" name="normal"/></Material>'
                 b'</Materials><Shapes externalShapesFile="model.i3d.shapes"/>'
                 b'<Scene><Shape shapeId="42" name="cabine" materialIds="paint trim"/></Scene></i3d>')
        original_shapes = bytes([5, 0, 7, 0]) + b"payload-opaco"
        report = conversion.assess_zip(make_zip({
            "mod/vehicle.i3d": scene,
            "mod/model.i3d.shapes": original_shapes,
            "mod/textures/body.png": b"synthetic-texture",
        }))

        linked = report["i3d"][0]
        model = linked["model_dependencies"][0]
        self.assertEqual("42", model["shape_id"])
        self.assertEqual(["paint", "trim"], [material["id"] for material in model["materials"]])
        self.assertEqual(["ENCONTRADO", "ENCONTRADO"],
                         [dependency["status"] for dependency in model["dependencies"]
                          if dependency["type"] == "textura"])
        self.assertEqual("ENCONTRADO", linked["references"][0]["status"])
        geometry = next(dependency for dependency in model["dependencies"]
                        if dependency["type"] == "geometria")
        self.assertEqual(5, geometry["shapes_header"]["version_candidate"])
        self.assertEqual(1, report["totals"]["duplicate_references"])
        self.assertEqual("CANDIDATO_DE_CABECALHO", report["shapes_inventory"][0]["status"])
        self.assertEqual("05 00 07 00", report["shapes_inventory"][0]["header_hex"])
        self.assertFalse(report["shapes_inventory"][0]["payload_decoded"])
        self.assertNotIn("decoded_shapes", linked)
        self.assertFalse(report["export_allowed"])
        self.assertEqual([], report["converted"])

    def test_separa_dependencias_ausentes_nao_verificaveis_e_caminhos_invalidos(self):
        scene = (b'<i3d><Files>'
                 b'<File fileId="1" filename="textures/missing.dds"/>'
                 b'<File fileId="2" filename="$data/shared/common.dds"/>'
                 b'<File fileId="3" filename="../../outside/escape.png"/>'
                 b'</Files><Materials><Material materialId="1">'
                 b'<Texture fileId="1" name="diffuse"/>'
                 b'<Texture fileId="2" name="normal"/>'
                 b'<Texture fileId="3" name="specular"/>'
                 b'</Material></Materials><Scene><Shape shapeId="1" materialIds="1"/></Scene></i3d>')
        report = conversion.assess_zip(make_zip({"mod/vehicle.i3d": scene}))
        groups = report["resource_groups"]
        statuses = {item["reference"]: item["status"] for group in groups.values() for item in group}
        self.assertEqual("AUSENTE", statuses["textures/missing.dds"])
        self.assertEqual("DADOS_DO_JOGO", statuses["$data/shared/common.dds"])
        self.assertEqual("FORA_DO_MOD", statuses["../../outside/escape.png"])
        self.assertGreaterEqual(report["totals"]["resources_missing"], 1)
        self.assertGreaterEqual(report["totals"]["resources_unverifiable"], 1)
        self.assertGreaterEqual(report["totals"]["resources_invalid"], 1)

    def test_detecta_xml_i3d_invalido_sem_abortar_o_inventario(self):
        report = conversion.assess_zip(make_zip({
            "mod/good.i3d": b'<i3d><Scene><Shape shapeId="1"/></Scene></i3d>',
            "mod/bad.i3d": b'<i3d><Scene>',
        }))
        states = {row["path"]: row["status"] for row in report["i3d_inventory"]}
        self.assertEqual("ANALISADO", states["mod/good.i3d"])
        self.assertEqual("XML_INVALIDO", states["mod/bad.i3d"])
        self.assertTrue(any(item["name"] == "mod/bad.i3d" for item in report["incompatible"]))

    def test_detecta_fileid_repetido_e_repeticao_de_recurso(self):
        scene = (b'<i3d><Files><File fileId="1" filename="texture.png"/>'
                 b'<File fileId="1" filename="other.png"/>'
                 b'<File fileId="2" filename="texture.png"/></Files>'
                 b'<Materials><Material materialId="1"><Texture fileId="1" name="diffuse"/>'
                 b'<Texture fileId="2" name="normal"/></Material></Materials></i3d>')
        report = conversion.assess_zip(make_zip({
            "mod/model.i3d": scene,
            "mod/texture.png": b"image",
            "mod/other.png": b"image",
        }))
        self.assertEqual(1, report["totals"]["duplicate_identifiers"])
        self.assertTrue(report["duplicate_identifiers"])
        self.assertEqual(1, report["totals"]["duplicate_references"])

    def test_identifica_candidato_shapes_sem_tentar_ler_payload(self):
        header = i3d.inspect_shapes_header(bytes([5, 0, 7, 0]) + b"ignored")
        self.assertEqual("CANDIDATO_DE_CABECALHO", header["status"])
        self.assertEqual(5, header["version_candidate"])
        self.assertEqual("05 00 07 00", header["header_hex"])
        self.assertFalse(header["payload_decoded"])
        self.assertEqual("CANDIDATO_DE_CABECALHO",
                         i3d.inspect_shapes_header(bytes([0, 0, 0, 3]))["status"])
        self.assertEqual("FORMATO_NAO_RECONHECIDO",
                         i3d.inspect_shapes_header(bytes([0, 0, 0, 0]))["status"])
        with self.assertRaisesRegex(ValueError, "truncado"):
            i3d.inspect_shapes_header(b"\x05\x00")

    def test_rejeita_referencias_de_caminho_invalidas(self):
        archive_paths = {"mod/model.i3d"}
        outside = conversion.resolve_i3d_reference("mod/model.i3d", "../../../private.dds", archive_paths)
        bad_game_alias = conversion.resolve_i3d_reference("mod/model.i3d", "$data/../../private.dds", archive_paths)
        control = conversion.resolve_i3d_reference("mod/model.i3d", "bad\x00name.png", archive_paths)
        self.assertEqual("FORA_DO_MOD", outside["status"])
        self.assertEqual("INVALIDO", bad_game_alias["status"])
        self.assertEqual("INVALIDO", control["status"])


if __name__ == "__main__":
    unittest.main()
