# Como contribuir

## Convenções

- Identificadores e comentários no código em inglês; documentação e textos vistos por usuários em português.
- Comentários explicam o porquê. Código que apenas repete o que faz não precisa de comentário.
- Avisos do compilador são erros (`-Xlint:all -Werror`; `tsc --strict`).
- Nova dependência: motivo no `pom.xml` ou em um ADR, licença declarada e versão com pelo menos 14 dias
  ([ADR 0014](docs/adr/0014-dependencias.md)).

## Antes de enviar

```bash
node scripts/verify.mjs
```

Para incluir o JDK 25, extraia um em `.tools/jdk-25` (ignorado pelo Git) ou informe os JDKs em `MORPH_JDKS`.

## Escrever um Provider

1. Crie `providers/morph-provider-<categoria>` dependendo apenas de `morph-contracts`.
2. Implemente `ConversionProvider` e registre em
   `META-INF/services/io.github.henryklayvin.morph.contracts.ConversionProvider`.
3. Declare formatos que ainda não existem em `additionalFormats()`.
4. Escreva somente em `context.workDirectory()`, consulte `context.cancellation()`, respeite `context.limits()` antes
   de alocar e devolva um `OutputCheck` que **releia o arquivo do disco**.
5. Declare perdas e limitações com honestidade: elas aparecem para as pessoas.
6. Acrescente o módulo como dependência `runtime` de `morph-application` e uma amostra em `ProviderContractTest` e em
   `CliWebParityTest`. Os dois passam a cobrir o Provider automaticamente.
7. Se as capacidades mudaram, atualize o contrato da interface:
   `./mvnw test -pl morph-local-web -am -Dmorph.updateContract` e depois `npm test` em `web`.

Um teste que não falha quando o código está errado não prova nada. Para proteções, quebre o código de propósito e
confirme que algum teste reprova.

## Licença das contribuições

O Morph é distribuído sob a [Apache-2.0](LICENSE). Ao enviar uma contribuição, você concorda que ela seja licenciada
nos mesmos termos, conforme a seção 5 da licença. Não há acordo adicional a assinar.
