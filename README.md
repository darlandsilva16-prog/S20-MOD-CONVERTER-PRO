# FS20 MOD CONVERTER PRO

Projeto Android nativo em Java, com interface inicial em português brasileiro e organização preparada para receber módulos de conversão.

## Ambiente de compilação

A compilação requer JDK 17, Gradle 9.6.0 e Android SDK com Android SDK Platform 36 e Build Tools 36.0.0. O ambiente Termux inspecionado não possui JDK, Gradle nem Android SDK; portanto, ainda não é possível gerar o APK nele. Abra o projeto em um ambiente com Android Studio/SDK ou instale e configure essas ferramentas antes de compilar.

Quando Gradle estiver disponível, gere os scripts do Gradle Wrapper na raiz do projeto com `gradle wrapper --gradle-version 9.6.0`; depois, compile o APK de depuração com `./gradlew assembleDebug`. O APK será criado em `app/build/outputs/apk/debug/`.

## Organização

- `app/src/main/java/.../ui`: tela inicial.
- `app/src/main/java/.../conversion`: espaço reservado aos futuros conversores.
- `app/src/main/java/.../archive`: espaço reservado ao tratamento de arquivos.
- `app/src/main/res`: textos, cores, tema e layout Android.
