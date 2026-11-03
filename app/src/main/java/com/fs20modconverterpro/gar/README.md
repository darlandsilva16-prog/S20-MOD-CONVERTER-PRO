# GERENCIADOR GAR PRO

O inspetor lê até 128 bytes do documento escolhido. Reconhece a assinatura ZIP `PK` para recusar ZIPs renomeados e compara o início do arquivo com o banner público associado a alguns arquivos GAR da GIANTS. O banner é somente uma indicação de origem: não valida índice, versão ou conteúdo do arquivo.

Não há especificação pública oficial do contêiner GAR no projeto. O fórum GDN descreve GAR como formato proprietário. Por isso, listar, pesquisar, extrair, selecionar conteúdo e reconstruir ficam desativados para todos os GARs nesta etapa. O botão habilitado salva apenas um relatório `.txt` por meio do seletor SAF; nunca escreve sobre o original.

Os testes usam somente cabeçalhos sintéticos e dados de ZIP simples. Nenhum arquivo real ou protegido do jogo é fixture. A identificação de banner usa dados de referência pública de terceiros e precisa de validação oficial antes de ser tratada como assinatura completa GAR.
