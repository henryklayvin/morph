# 0013. Licença e identificadores públicos

**Situação:** aguarda decisão do responsável pelo projeto

## Licença

Sem um arquivo `LICENSE`, o código publicado não é open source: ninguém recebe permissão de uso. Como a escolha é do
titular dos direitos, nenhum arquivo foi criado.

**Recomendação: Apache-2.0.** Permissiva, com concessão explícita de patentes, igual à de PDFBox, Javalin, Jetty,
Picocli e Jackson, e compatível com todas as dependências atuais (Apache-2.0, MIT, BSD-3-Clause, EPL-2.0, GPL-2.0 com
Classpath Exception para o JDK). LibreOffice (MPL-2.0) e FFmpeg (LGPL ou GPL) serão programas externos, não
incorporados.

Alternativa: GPL-3.0 ou AGPL-3.0, se o objetivo for impedir versões fechadas do Morph.

## Identificadores

`groupId` e pacotes usam `dev.morph`, **provisório**: publicar no Maven Central exige provar posse do domínio ou usar
`io.github.<conta>`. Mudar antes da primeira publicação é barato; depois, não.
O nome "Morph" e o comando `morph` também merecem busca de marca e de conflitos nos gerenciadores de pacotes.
