# Conversor real de mods

## Estado atual

A tela separada avalia ZIPs sem extraí-los, executá-los ou alterar o original. Ela valida caminhos, duplicatas, tamanho e CRC; lê `modDesc.xml` e I3D com proteção contra DTD/entidades; relaciona `fileId` de materiais a texturas e `materialIds` das formas aos materiais; e compara `shapeId` do I3D com IDs de `.i3d.shapes` nas versões binárias 2–7 que o leitor existente entende.

Referências relativas são normalizadas a partir da pasta do I3D, aceitam `/` e `\\`, e distinguem entradas presentes no ZIP, diferenças de maiúsculas/minúsculas, referências `$data` aos arquivos do jogo, aliases externos, caminhos que saem do mod e arquivos realmente ausentes. Os diagnósticos repetidos são consolidados e os detalhes ficam em seções expansíveis com totais.

O relatório classifica arquivos ausentes/ambíguos como incompatíveis e transformações sem perfil comprovado como pendentes. DDS DXT1, DXT3, DXT5 e RGB de 32 bits com máscaras compatíveis são identificados como decodificáveis pelo módulo de texturas existente. O editor não grava uma cópia de saída nem afirma que um pacote é compatível quando não aplicou conversões validadas.

## Limite do destino FS20

FS20 mobile não oferece suporte oficial a mods. Portanto, não existe um formato de mod instalável de destino contra o qual validar conversões I3D, `modDesc.xml` ou texturas. A versão declarada em `descVersion` é mostrada sem inferir indevidamente que identifica FS19, FS22 ou FS25. Conversão de geometria, esquema XML e textura para um pacote FS20 permanece bloqueada até haver especificação e ambiente de destino verificáveis.

Referências consultadas:

- [Documentação oficial GDN do formato I3D](https://gdn.giants-software.com/documentation_i3d.php)
- [Fórum GIANTS: FS20 mobile e suporte a mods](https://forum.giants-software.com/viewtopic.php?t=177508)

## Segurança e testes

Lua é tratado como dado e nunca executado. ZIPs acima de 32 MiB comprimidos, 256 MiB expandidos, 20 mil entradas ou limites por XML/SHAPES são rejeitados ou reportados. Os testes Python cobrem referências I3D, associações de material/textura/SHAPES, caminhos ausentes, DOCTYPE, entradas duplicadas e ausência de execução/conversão simulada.
