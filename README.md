# FS20 MOD CONVERTER PRO

Aplicativo Android nativo em Java, com interface em português brasileiro. A primeira ferramenta permite selecionar um mod ZIP, consultar seu tamanho e conteúdo e extrair a estrutura para uma pasta escolhida pelo seletor de documentos do Android.

## Recursos disponíveis

- Seleção de arquivos ZIP pelo Storage Access Framework (SAF).
- Inspeção das entradas e dos tamanhos descompactados, sem alterar o arquivo original.
- Extração preservando pastas e nomes dentro do destino escolhido.
- Bloqueio de caminhos absolutos, travessia de diretórios, nomes conflitantes e arquivos acima dos limites definidos.
- Limites atuais: 20.000 entradas, 256 MiB por arquivo e 1 GiB de conteúdo descompactado por pacote.
- Interface de leitor de arquivos separada do formato; nesta versão, somente ZIP é suportado. GAR ainda não foi implementado.

## Compilação e testes

Requer JDK 17, Gradle 9.6.0, Android SDK Platform 36 e Build Tools 36.0.0. Os testes unitários do extrator podem ser executados com `gradle testDebugUnitTest`; para gerar o APK de depuração, use `gradle assembleDebug`. O APK será criado em `app/build/outputs/apk/debug/`.

O workflow do GitHub Actions executa os testes e compila o APK em cada envio para `main`, solicitação de integração ou execução manual. O APK é publicado como artefato do workflow.
