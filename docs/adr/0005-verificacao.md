# 0005. Verificação obrigatória e entrega atômica

**Situação:** aceita

## Decisão

Todo arquivo produzido carrega um `OutputCheck`, sem valor padrão: um Provider não consegue omiti-lo. O motor, antes
de entregar, confere quantidade, localização, tamanho e assinatura, e então executa a verificação, que **relê o
arquivo do disco**:

- saída sem perdas: SHA-256 dos pixels visíveis idêntico ao da origem;
- saída com perdas: dimensões iguais e semelhança em grade de cores, o que reprova imagem preta, canais trocados ou
  rotação perdida;
- PDF: número de páginas e prévia de cada página comparada com a imagem.

A entrega grava ao lado do destino e renomeia atomicamente. O nome é reservado antes com criação exclusiva, então
"não sobrescrever" vale mesmo se outro programa criar o arquivo no mesmo instante. Várias saídas são tudo ou nada.

## Por quê

"O arquivo existe" não prova nada. WebP é gravado por libwebp e relido pelo TwelveMonkeys: dois códigos independentes
precisam concordar.

## Custo

Uma decodificação extra por saída. Aceito: é o que sustenta a palavra "verificado" na interface.

## Limite conhecido

A semelhança é grosseira por projeto (grade 8x8). Não detecta defeitos locais pequenos.
