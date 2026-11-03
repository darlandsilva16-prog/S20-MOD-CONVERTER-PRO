import json
import pathlib
import sys
import unittest

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parents[3] / "main" / "python"))
from gar_engine import gar_py


def observed_header():
    header = bytearray(128)
    banner = b"Copyright (C) GIANTS Software GmbH, All Rights Reserved"
    header[14:14 + len(banner)] = banner
    header[96:100] = b"GAR "
    header[100:108] = bytes.fromhex("04 00 05 00 A4 08 00 00")
    return header


class GarEngineTests(unittest.TestCase):
    def test_importa_modulo_gar_py_sem_inicializar_rotinas_de_operacao(self):
        self.assertTrue(callable(gar_py.analyze_header))
        self.assertFalse(hasattr(gar_py, "decrypt"))
        self.assertFalse(hasattr(gar_py, "extract"))
        self.assertFalse(hasattr(gar_py, "rebuild"))

    def test_cabecalho_confirmado_reproduz_bytes_e_valores_observados(self):
        original = observed_header()
        before = original[:]
        report = json.loads(gar_py.analyze_header("dataS.gar", 36_095_240, original))
        self.assertEqual(14, report["giants_banner_offset"])
        self.assertEqual(96, report["gar_signature_offset"])
        self.assertEqual("CABECALHO_IDENTIFICADO", report["state_code"])
        self.assertEqual("ESTRUTURA_NAO_VALIDADA", report["index_state_code"])
        self.assertFalse(report["index_read_available"])
        self.assertEqual("47 41 52 20", report["gar_signature_hex"])
        fields = report["observed_fields"]
        self.assertEqual([16, 16, 32], [field["width_bits"] for field in fields])
        self.assertEqual([4, 5, 2212], [field["observed_value"] for field in fields])
        self.assertEqual(["04 00", "05 00", "A4 08 00 00"], [field["raw_hex"] for field in fields])
        self.assertEqual(36_095_240, report["size_bytes"])
        self.assertIsNone(report["entry_count"])
        self.assertIsNone(report["version"])
        self.assertTrue(report["read_only"])
        self.assertEqual(before, original)

    def test_assinatura_ausente_nao_e_inferida_pelo_nome(self):
        report = json.loads(gar_py.analyze_header("dataS.gar", 128, bytes(128)))
        self.assertFalse(report["gar_signature_found"])
        self.assertEqual(-1, report["gar_signature_offset"])
        self.assertFalse(report["can_extract"])
        self.assertFalse(report["can_rebuild"])

    def test_arquivo_truncado_e_marcado_sem_acessar_dados_ausentes(self):
        header = observed_header()[:80]
        report = json.loads(gar_py.analyze_header("dataS.gar", 80, header))
        self.assertEqual(14, report["giants_banner_offset"])
        self.assertFalse(report["gar_signature_found"])
        self.assertEqual(0, len(report["observed_fields"]))

    def test_campos_incompletos_sao_omitidos_sem_leitura_parcial_como_inteiro(self):
        header = observed_header()[:106]  # Dois campos completos; terceiro tem apenas dois bytes.
        report = json.loads(gar_py.analyze_header("dataS.gar", 106, header))
        self.assertEqual([4, 5], [field["observed_value"] for field in report["observed_fields"]])
        self.assertEqual(1, report["incomplete_field_count"])

    def test_operacoes_de_extracao_reconstrucao_e_pesquisa_seguem_bloqueadas(self):
        report = json.loads(gar_py.analyze_header("dataS.gar", 36_095_240, observed_header()))
        self.assertFalse(report["can_extract"])
        self.assertFalse(report["can_rebuild"])
        self.assertFalse(report["can_search_contents"])
        self.assertFalse(report["content_validated"])
        self.assertEqual("ESTRUTURA_NAO_VALIDADA", report["index_state_code"])

    def test_estados_separam_cabecalho_parcial_de_formato_nao_suportado(self):
        header = observed_header()
        partial = json.loads(gar_py.analyze_header("dataS.gar", 106, header[:106]))
        unsupported = json.loads(gar_py.analyze_header("unknown.gar", 128, bytes(128)))
        self.assertEqual("ESTRUTURA_NAO_VALIDADA", partial["state_code"])
        self.assertEqual("FORMATO_NAO_SUPORTADO", unsupported["state_code"])

    def test_limite_de_128_bytes_nomes_e_tamanhos_sao_validados(self):
        with self.assertRaisesRegex(ValueError, "128 bytes"):
            gar_py.analyze_header("dataS.gar", 1000, bytes(129))
        with self.assertRaisesRegex(ValueError, "sem caminho"):
            gar_py.analyze_header("../dataS.gar", 100, bytes(20))
        with self.assertRaisesRegex(ValueError, "fora dos limites"):
            gar_py.analyze_header("dataS.gar", -2, bytes(20))


if __name__ == "__main__":
    unittest.main()
