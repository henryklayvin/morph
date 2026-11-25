# Segurança e privacidade

## O que o Morph promete

1. Nenhum arquivo, nome ou metadado sai do computador.
2. Nenhuma conexão é aberta para fora. O Morph Local aceita conexões apenas de `127.0.0.1`.
3. Não há contas, telemetria, verificação de atualização nem logs.
4. Cópias temporárias são privadas e apagadas, inclusive depois de uma queda.
5. O arquivo original nunca é alterado e nada é sobrescrito sem pedido explícito.

## Como cada promessa é verificada

| Camada | Prova | Onde |
|---|---|---|
| Código do Morph | nenhuma classe pode referenciar APIs que abrem conexões | `ArchitectureTest.nothingOpensConnections` |
| JVM, incluindo bibliotecas | Flight Recorder lista todo socket durante conversões: nenhum na CLI, só loopback no Local | `LocalProcessingTest` |
| DNS | resolvedor substituído nos testes; nenhuma consulta é feita | `DnsRecorder` |
| Sistema operacional | Linux sem interface de rede: 8 de 8 conversões nas duas entradas e zero tentativas de saída no kernel | [relatório](relatorio-fase-0.md) |
| Navegador | toda requisição da página foi para o próprio Morph; sete formas de envio bloqueadas pela CSP | `web/e2e/local.mjs` |
| Arquivos | área temporária vazia após sucesso, falha, cancelamento, Ctrl+C e, na execução seguinte, após morte do processo | `WorkspacesTest`, `MorphEngineTest`, relatório |
| Metadados | EXIF, comentários e dados após a imagem não chegam ao resultado | `ImageProviderTest`, `PdfProviderTest` |

Cada instrumento tem um controle positivo: o teste também prova que ele detectaria o problema.

## Modelo de ameaças

| Ameaça | Defesa |
|---|---|
| Arquivo malicioso (bomba, truncado, corrompido) | limites lidos do cabeçalho antes de alocar; decodificadores Java; testes com arquivos danificados |
| Extensão mentirosa | detecção por conteúdo |
| Nome de arquivo hostil | `FileNames`: separadores, controle, `:` do NTFS, nomes reservados, inversão de texto Unicode |
| Sobrescrita e corrida de arquivos | reserva exclusiva e troca atômica |
| Site malicioso contra o Morph Local | Host, Origin, Fetch Metadata, token em cabeçalho, sem CORS, tipos de conteúdo que exigem preflight |
| DNS rebinding | lista fixa de Host |
| Outra conta da mesma máquina | código de uso único, token, diretórios exclusivos |
| Página embutida em outra | `frame-ancestors 'none'` e `X-Frame-Options` |
| Resultado convertido executado na origem do Morph | sempre download de bytes opacos |
| Disco cheio por envios | limite por arquivo e total, contado nos bytes recebidos |
| Cadeia de suprimentos | versões fixas, quarentena de 14 dias, CI fixado por commit, OSV ([ADR 0014](adr/0014-dependencias.md)) |

## Riscos residuais

- **Sem isolamento de processo.** Um laço infinito em um decodificador não pode ser interrompido
  ([ADR 0009](adr/0009-execucao-e-isolamento.md)).
- **libwebp** é código nativo no processo. Recebe apenas pixels, nunca arquivos.
- Limites de memória são estimativas; falta de memória é capturada e reportada, mas pode afetar trabalhos paralelos.
- Imagens usadas como máscara ou padrão dentro de um PDF não passam pelo limite prévio de pixels.
- O tamanho da saída é conferido após a gravação; um arquivo patológico pode ocupar disco temporariamente.
- Programas na mesma conta podem ler o que a pessoa pode ler. Fora de alcance.
- Apagar não é destruição forense: o sistema de arquivos pode reter blocos.

## Relatar uma falha

O canal privado será definido com a publicação do repositório.
