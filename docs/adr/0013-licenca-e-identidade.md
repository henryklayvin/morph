# 0013. Licença e identificadores públicos

**Situação:** aceita em 2026-11-25, por decisão do responsável pelo projeto

## Contexto

Sem um arquivo `LICENSE`, o código publicado não é open source: ninguém recebe permissão de uso. E o `groupId` dos
artefatos Maven fica caro de trocar depois da primeira publicação. Até esta decisão, o projeto não tinha licença e usava
`dev.morph` como identificador provisório, sem posse do domínio.

## Decisão

### Licença: Apache-2.0

Permissiva, com concessão explícita de patentes, igual à de PDFBox, Javalin, Jetty, Picocli e Jackson, e compatível com
todas as dependências atuais (Apache-2.0, MIT, BSD-3-Clause, EPL-2.0, GPL-2.0 com Classpath Exception para o JDK).
LibreOffice (MPL-2.0) e FFmpeg (LGPL ou GPL) serão programas externos, não incorporados.

- `LICENSE` traz o texto integral, sem alterações, e `NOTICE` traz o aviso de direitos autorais do projeto. Os dois
  acompanham o pacote distribuído da CLI.
- Contribuições entram sob a mesma licença (seção 5 da Apache-2.0), sem acordo adicional.

### Identificadores

| Item | Valor |
|---|---|
| Namespace no Maven Central | `io.github.henryklayvin`, ainda a verificar |
| `groupId` | `io.github.henryklayvin.morph` |
| Prefixo dos pacotes Java | `io.github.henryklayvin.morph` |
| Repositório oficial | <https://github.com/henryklayvin/morph> |

Um namespace `io.github.<conta>` não exige domínio próprio: o Central confirma a posse pela conta do GitHub. Os
`artifactId` (`morph-contracts`, `morph-engine` e os demais) não mudam.

## Consequências

- Nada é publicado no Maven Central por enquanto. O POM já tem os metadados exigidos, e a verificação do namespace está
  descrita em [Publicação no Maven Central](../publicacao-maven-central.md).
- Pendente: busca de marca e de conflitos para o nome "Morph" e o comando `morph` nos gerenciadores de pacotes.
- Pendente para a primeira versão distribuída: reunir em um só lugar as licenças e os avisos das bibliotecas incluídas
  no pacote ([ADR 0012](0012-distribuicao.md)). Hoje alguns JARs trazem os próprios avisos e outros, como os do Jetty
  e do TwelveMonkeys, não.
