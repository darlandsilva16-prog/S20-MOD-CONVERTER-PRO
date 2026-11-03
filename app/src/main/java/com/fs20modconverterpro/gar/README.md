# GERENCIADOR GAR PRO e GAR ENGINE PRO

`gar_engine.gar_py` é um módulo Python separado, importado sob demanda pela `GarPythonBridge` via Chaquopy. O código deste módulo implementa apenas validação básica de nome/tamanho e análise de até 128 bytes. Não importa nem disponibiliza operações de criptografia/descriptografia, extração, reconstrução, pesquisa interna ou execução de conteúdo.

## Estados e limite da análise

As telas distinguem `CABEÇALHO IDENTIFICADO`, `ESTRUTURA NÃO VALIDADA` e `FORMATO NÃO SUPORTADO`. Identificar um cabeçalho não valida nem lê o índice. O leitor atual examina no máximo 128 bytes e não altera o documento original.

O fixture **sintético** reproduz observações previamente fornecidas: banner GIANTS no deslocamento 14; assinatura ASCII `GAR ` no deslocamento 96; bytes seguintes `04 00 05 00 A4 08 00 00`. Os bytes são exibidos como uint16 little-endian 4, uint16 little-endian 5 e uint32 little-endian 2212, sem atribuir significado. Nenhum arquivo GAR real é incluído ou usado pelos testes. Versão, quantidade de entradas e organização do índice permanecem desconhecidas.

## Interface para leitores futuros

`GarIndexReader<T>` define o contrato futuro: identificar formatos suportados e ler índice, mantendo genérico o modelo de resultado até haver evidência. `GarIndexReaderRegistry` começa vazio; nenhum parser está registrado ou implementado. A interface não deve ser conectada a operações de pesquisa, extração, descriptografia ou reconstrução neste estágio.

Um leitor validado só poderá ser adicionado quando houver uma especificação técnica confiável ou amostra autorizada e não protegida. Documente a procedência e autorização da evidência, os offsets/larguras comprovados e limites de versão. Mantenha os fixtures sintéticos em `app/src/test/.../synthetic/`. Testes de arquivos reais autorizados devem ficar em uma suíte separada (`real/`), exigir fixtures locais explicitamente fornecidos e nunca incluir esses arquivos no repositório. Valide leituras truncadas, limites, versões suportadas e rejeição de formatos desconhecidos antes de registrar o leitor.

O seletor concede acesso de leitura. A aplicação não carrega o arquivo inteiro, não o escreve e não sobrescreve o original. Os relatórios exibem o prefixo bruto limitado, os bytes de assinatura e os bytes de cada valor observado. Scripts Python/Lua encontrados no arquivo não são executados.

Os testes unitários Java e Python e o teste instrumentado Chaquopy usam apenas cabeçalhos sintéticos. O teste instrumentado requer dispositivo/emulador; não é um teste com arquivo GAR real.
