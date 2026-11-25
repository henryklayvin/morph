# Arquitetura

## Visão geral

```text
   terminal                          navegador (interface React)
      |                                        | HTTP em 127.0.0.1, token
  morph-cli  ------ morph web ------>  morph-local-web (Javalin)
      |                                        |
      +-------------- morph-application -------+      descobre Providers, monta o motor
                             |
                        morph-engine                  inspeciona, planeja, executa, verifica, entrega
                             |
                       morph-contracts                modelos e SPI
                             |
             provider-image        provider-pdf       (office, media, archive e data: próximas fases)
```

A CLI não usa HTTP: chama o motor diretamente. O Morph Local é uma segunda porta de entrada para o mesmo objeto
`MorphEngine`. Direção das dependências e como ela é garantida: [ADR 0002](adr/0002-modulos.md).

## O caminho de uma conversão

A ordem está definida em um único lugar, `ConversionPipeline`:

| # | Etapa | O que acontece | Falhas típicas |
|---|---|---|---|
| 1 | Inspeção | formato pelo conteúdo, tamanho, leitura | `INPUT_NOT_FOUND`, `UNRECOGNIZED_FORMAT` |
| 2 | Compatibilidade | existe Provider para origem, destino e quantidade? | `UNSUPPORTED_CONVERSION` |
| 3 | Planejamento | melhor Provider disponível; opções validadas | `PROVIDER_UNAVAILABLE`, `INVALID_OPTION` |
| 4 | Validação | nome, extensão e conflito de saída, antes de trabalhar | `INVALID_REQUEST`, `OUTPUT_EXISTS` |
| 5 | Execução | o Provider escreve na área temporária do trabalho | `CORRUPT_INPUT`, `RESOURCE_LIMIT_EXCEEDED` |
| 6 | Verificação | cada arquivo é relido e comparado com o prometido | `OUTPUT_VERIFICATION_FAILED` |
| 7 | Entrega | cópia ao lado do destino e troca atômica | `OUTPUT_WRITE_FAILED` |
| 8 | Limpeza | a área do trabalho é apagada, sempre | |

`TIMED_OUT` e `CANCELLED` podem ocorrer até o início da etapa 7. A partir dali o trabalho está confirmado, então nunca
termina ao mesmo tempo entregue e cancelado.

## Contratos principais

| Tipo | Papel |
|---|---|
| `FormatDescriptor`, `Signature`, `StandardFormats` | o que é um formato e como reconhecê-lo |
| `ConversionProvider` | o que um mecanismo implementa |
| `Capability` | uma linha da matriz: origem, destino, cardinalidade, opções, perdas, limitações |
| `OptionSpec`, `ResolvedOptions` | configurações tipadas, validadas antes de chegar ao Provider |
| `ConversionRequest`, `ConversionPlan`, `ConversionResult` | pedido, decisão e resultado |
| `ConversionContext` | tudo que um Provider pode tocar |
| `ProducedOutput`, `OutputCheck` | arquivo produzido e sua verificação obrigatória |
| `ResourceLimits`, `CancellationToken` | limites e cancelamento |
| `ErrorCode` | razões estáveis, iguais na CLI e no HTTP |

## Códigos de saída da CLI

0 sucesso; 1 erro interno; 2 uso incorreto; 3 problema no arquivo; 4 conversão não disponível; 5 limite ou tempo;
6 não foi possível gravar; 7 conversão ou verificação falhou; 8 lote parcial; 130 cancelado.

## API local (`/api/v1`)

Uso interno da interface; não é estável para terceiros.

| Rota | Função |
|---|---|
| `POST /session` | troca o código de uso único por um token |
| `GET /info`, `GET /capabilities` | versão e matriz |
| `POST /files`, `DELETE /files/{id}` | entrega e remove um arquivo (`application/octet-stream`) |
| `POST /jobs`, `GET /jobs/{id}`, `DELETE /jobs/{id}` | cria, consulta e remove uma conversão |
| `POST /jobs/{id}/cancel` | cancela |
| `GET /jobs/{id}/outputs/{n}` | baixa um resultado |
| `POST /shutdown` | encerra o Morph Local |
