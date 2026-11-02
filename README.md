# FS20 MOD CONVERTER PRO

Aplicativo Android nativo em Java, com interface em português brasileiro. A primeira ferramenta permite selecionar um mod ZIP, consultar seu tamanho e conteúdo e extrair a estrutura para uma pasta escolhida pelo seletor de documentos do Android.

## Recursos disponíveis

- Seleção de arquivos ZIP pelo Storage Access Framework (SAF).
- Inspeção das entradas e dos tamanhos descompactados, sem alterar o arquivo original.
- Extração preservando pastas e nomes dentro do destino escolhido.
- Bloqueio de caminhos absolutos, travessia de diretórios, nomes conflitantes e arquivos acima dos limites definidos.
- Limites atuais: 20.000 entradas, 256 MiB por arquivo e 1 GiB de conteúdo descompactado por pacote.
- Editor XML com abertura de XML pelo seletor do Android, incluindo arquivos extraídos para uma pasta acessível; edição com numeração de linhas e realce de sintaxe; formatação, validação, desfazer/refazer e exportação por “Salvar como”. O original não é sobrescrito.
- O editor limita cada XML a 2 MiB, preserva BOM/codificação reconhecida e rejeita DTD e entidades externas. A formatação mantém conteúdo de texto misto e espaços marcados com `xml:space="preserve"`.
- Editor Lua com abertura pelo seletor Android, realce de palavras-chave, funções, comentários, strings e números, indentação, localização/substituição, desfazer/refazer e exportação. A verificação é estrutural e básica, não executa scripts e não substitui um interpretador Lua; salvar sobre o arquivo aberto exige confirmação explícita.
- O Editor Lua limita arquivos a 2 MiB e 100.000 linhas, preserva BOM e codificações reconhecidas (UTF-8, UTF-16 e fallback reversível ISO-8859-1). Scripts nunca são executados pelo aplicativo.
- Conversor de texturas: PNG/JPG/JPEG via decodificadores Android; BMP BI_RGB 24/32 bits; TGA true-color tipos 2 e 10 em 24/32 bits; DDS DXT1/DXT3/DXT5 e RGB32 com máscaras válidas. Exporta PNG/JPG com redimensionamento, qualidade JPEG, pré-visualização com zoom e seletor de destino. Variantes não implementadas são identificadas com uma explicação e não são convertidas.
- O conversor limita arquivos a 32 MiB, texturas de origem a 16 milhões de pixels e imagens de saída a 8 milhões; imagens grandes são amostradas e os arquivos originais não são alterados.
- Interface de leitor de arquivos separada do formato; nesta versão, somente ZIP é suportado. GAR ainda não foi implementado.

## Compilação e testes

Requer JDK 17, Gradle 9.6.0, Android SDK Platform 36 e Build Tools 36.0.0. Os testes unitários podem ser executados com `gradle testDebugUnitTest`; para gerar o APK de depuração, use `gradle assembleDebug`. O APK será criado em `app/build/outputs/apk/debug/`.

O workflow do GitHub Actions executa os testes e compila o APK em cada envio para `main`, solicitação de integração ou execução manual. O APK é publicado como artefato do workflow.
