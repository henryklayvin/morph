# Relatório da Fase 0

Data: 2026-11-25. Objetivo: comprovar que a arquitetura funciona, que CLI e Web usam o mesmo motor Java e que o
processamento é local.

## Conclusão

Os três pontos estão comprovados, com JDK 21 e JDK 25, em Windows e Linux.

| Pergunta | Resposta | Evidência |
|---|---|---|
| CLI e Web usam o mesmo motor? | Sim | as 8 conversões produzem bytes idênticos pelas duas entradas (`CliWebParityTest` e navegador real) |
| Os arquivos ficam na máquina? | Sim | sem interface de rede, tudo funciona; o kernel registrou zero tentativas de saída |
| Java 21 é respeitado? | Sim | `--release 21`, classes versão 65, Enforcer sobre dependências; o mesmo pacote roda no 25 |

## Testes executados

| Conjunto | Windows, JDK 21 | Windows, JDK 25 | Linux, JDK 21 | Linux, JDK 25 |
|---|---|---|---|---|
| Java (unidade, contrato, integração, segurança, arquitetura) | 212, 2 pulados | 212, 2 pulados | 212 | 212 |
| Navegador, 15 passos | Chrome e Edge | Chrome e Edge | não executado | não executado |
| Conversão sem rede, 8 operações x 2 entradas | | | passou | passou |
| Ctrl+C e morte do processo | passou | passou | | |

Interface: 18 testes TypeScript. Nenhuma falha. Os 2 testes pulados no Windows tratam de permissões POSIX e links
simbólicos, e rodam no Linux.

Para repetir: `node scripts/verify.mjs`. Os testes sem rede e de interrupção foram feitos com roteiros avulsos, fora do
repositório; trazê-los para o CI é um dos próximos passos.

### Os testes falham quando deveriam?

Vinte sabotagens deliberadas, como remover limites, trocar a verificação por nada, aceitar qualquer Host, escutar
em todas as interfaces ou reutilizar o código de acesso, rodadas contra a suíte. Duas passaram despercebidas na primeira
rodada (limite de pixels e trava de cancelamento antes da entrega). Os testes que faltavam foram escritos, e hoje todas
são detectadas.

### Defeitos que os testes encontraram

- cancelar durante a leitura de um PDF era relatado como "PDF corrompido";
- erro de digitação na CLI saía com código 1 em vez de 2;
- envio recusado pelo Morph Local descontava bytes a mais do limite total;
- o lançador `sh` não funcionava no Git Bash;
- sem `jdk.zipfs`, o `morph web` não inicia em um runtime reduzido.

Todos corrigidos, exceto o último, que é um requisito registrado no ADR 0012.

## Processamento local, por camada

1. **Código:** nenhuma classe do Morph referencia APIs que abrem conexões.
2. **JVM:** o Flight Recorder observou zero operações de socket na CLI; no Morph Local, só loopback.
3. **DNS:** nenhuma consulta.
4. **Kernel (Linux):** em um espaço de rede só com loopback, 8 de 8 conversões na CLI e 8 de 8 na Web; `OutNoRoutes`
   permaneceu em zero e subiu para um em uma tentativa deliberada, o que prova que o contador funciona.
5. **Navegador:** toda requisição da página foi ao próprio Morph; envio por fetch, beacon, WebSocket, imagem, script,
   iframe e formulário foi bloqueado; uma página hostil em outra origem recebeu apenas 403.
6. **Disco:** nenhum resto após sucesso, falha, cancelamento, encerramento ou Ctrl+C (código 130, cerca de 0,6 s). Após
   morte do processo, a execução seguinte apagou o que havia ficado.

## Medições

Imagem de 24 megapixels, processo novo, incluindo início da JVM e verificação, no Windows:

| Conversão | JDK 21 | JDK 25 | Pico de memória |
|---|---|---|---|
| JPEG para PNG | 4,0 s | 4,0 s | 304 a 424 MB |
| JPEG para WebP | 4,1 s | 5,4 s | 524 a 599 MB |
| PNG para JPEG | 1,8 s | 1,8 s | 404 a 431 MB |
| PNG para WebP | 4,5 s | 3,9 s | 516 a 662 MB |
| JPEG para PDF | 1,4 s | 1,3 s | 286 a 323 MB |

Uma execução de cada: servem como ordem de grandeza, não como comparação entre JDKs. Imagem pequena: cerca de 1,6 s.
Pacote: 16 MB sem runtime; runtime reduzido: 47 MB.

## Limitações

- macOS, Firefox e Safari não foram testados. O CI está escrito e **nunca foi executado**.
- Não há isolamento de processo ([ADR 0009](adr/0009-execucao-e-isolamento.md)).
- O executável do `jpackage` foi gerado, mas não executado: o Windows bloqueou por falta de assinatura
  ([ADR 0012](adr/0012-distribuicao.md)).
- PDF com JPEG 2000 é recusado. PNG com perfil de cor é tratado como sRGB.
- O download pela interface passa pela memória do navegador; resultados muito grandes podem falhar.
- Textos apenas em português. Mensagens do próprio Picocli ainda saem em inglês.
- A CLI não percorre pastas (recursão é da Fase 2).
- Os testes do PDF usam o índice de fontes real da conta (`%LOCALAPPDATA%\Morph\cache` no Windows).
- Não há testes de carga prolongada nem de vazamento de memória.

## Próxima etapa recomendada

Antes de ampliar o catálogo, nesta ordem:

1. **Decisões do responsável:** licença e identificadores ([ADR 0013](adr/0013-licenca-e-identidade.md)).
2. Repositório Git e primeira execução do CI, incluindo macOS e o teste sem rede.
3. **Fase 2, CLI:** recursão, padrões de nome, mensagens e AppCDS.
4. **Fase 3, site público:** backend de navegador com o contrato já existente, começando por PNG, JPEG e WebP.
5. **Isolamento por processo**, obrigatório antes de LibreOffice e FFmpeg.
