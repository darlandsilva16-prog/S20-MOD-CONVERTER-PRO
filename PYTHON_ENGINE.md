# Motor Python incorporado

O APK usa Chaquopy 15.0.1 com CPython 3.11; o runtime e os módulos do app são empacotados no APK. Não é necessário Termux, root, rede ou Python instalado no dispositivo. O projeto mantém suporte Android a partir da API 23.

## Compatibilidade adotada

Chaquopy atual exige API 24. A versão 15.0.1 permite API 21+, mas exige AGP até 8.5. Por isso o projeto usa AGP 8.5.2, Gradle 8.7, compileSdk/targetSdk 34 e Java 17. Python 3.11 fornece bibliotecas nativas para `armeabi-v7a`, `arm64-v8a` e `x86_64` (as duas primeiras para aparelhos; a última para o teste instrumentado no emulador).

## Ponte e segurança

`PythonBridge` aceita somente nome e bytes de arquivo, limita a entrada a 32 MiB e chama funções predefinidas no módulo `fs20_engine.runtime`. Não há `eval`, `exec`, subprocessos ou importação indicada pelo arquivo. A Activity lê e processa arquivos em executor de segundo plano, apresenta exceções e conserva até dez operações no histórico da sessão. Os arquivos de origem são somente leitura.

## Módulos incluídos

- ZIP: lista entradas e alerta caminhos inseguros sem extrair.
- XML/I3D: valida XML bloqueando DTD e entidades externas; identifica referências `.i3d.shapes`.
- I3D Shapes: identifica cabeçalho e versão; a decodificação de geometria permanece no leitor Java.
- Lua: análise lexical básica, sem execução e sem alegar validação por interpretador.
- Texturas: identifica cabeçalhos PNG, JPEG, BMP, TGA e DDS; não converte imagens.
- FS19/FS22/FS25: informa `descVersion` declarada quando encontrada; não conclui compatibilidade automática com FS20.

Só são usados módulos da biblioteca padrão Python. Não há pacotes pip adicionais.

## Tamanho do APK

O tamanho final ainda depende da compilação. Chaquopy informa acréscimo de vários megabytes por ABI incluída; com três ABIs, o aumento pode chegar à ordem de dezenas de megabytes. É uma estimativa, não uma medição. O GitHub Actions exibirá o tamanho real do APK após compilá-lo.
