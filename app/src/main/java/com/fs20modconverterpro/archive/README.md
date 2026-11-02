# Leitores de arquivos de mods

`ArchiveHandler` define as operações de identificação, inspeção e extração. `ModArchiveManager` registra os leitores disponíveis; nesta etapa, somente `ZipArchiveHandler` é implementado. Para adicionar GAR no futuro, crie outro leitor e registre-o no gerenciador sem acoplar o formato à interface.

O destino é abstraído por `ArchiveDestination`. `SafArchiveDestination` grava somente dentro da pasta escolhida pela pessoa usuária no seletor do Android e apaga, quando possível, apenas os itens criados pela tentativa que falhou.

O leitor ZIP bloqueia caminhos absolutos, segmentos `..`, separadores invertidos, entradas duplicadas/conflitantes, mais de 20.000 itens, arquivos individuais acima de 256 MiB e conteúdo total acima de 1 GiB. O arquivo selecionado é lido, nunca alterado.
