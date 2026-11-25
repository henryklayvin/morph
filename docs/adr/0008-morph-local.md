# 0008. Morph Local: Javalin, loopback e autenticação

**Situação:** aceita

## Contexto

"Só escuta em localhost" não é controle de acesso. Qualquer site aberto no navegador consegue enviar requisições para
`127.0.0.1`, e outras contas da mesma máquina também.

## Decisão

Javalin 7.2.4 sobre Jetty 12.1 (Java 17+, portanto compatível). O Javalin 6 foi descartado por usar Jetty 11, sem
suporte. As camadas são independentes entre si:

1. Escuta somente em `127.0.0.1` literal. O nome `localhost` depende do arquivo hosts.
2. `Host` em lista fixa: anula DNS rebinding.
3. `Origin` e `Sec-Fetch-Site` conferidos em tudo, inclusive arquivos estáticos. Nenhum cabeçalho CORS existe.
4. Token de 256 bits no cabeçalho `Authorization`, nunca em cookie: não há credencial automática para CSRF.
5. O token é obtido com um **código de uso único** no fragmento da URL, que o navegador não transmite. Quem ler a
   linha de comando ou o histórico depois encontra um código já gasto.
6. Corpos só em `application/json` e `application/octet-stream`, que exigem preflight a partir de outra origem.
7. CSP `default-src 'none'; connect-src 'self'`: o próprio navegador impede a página de enviar dados a terceiros,
   mesmo que seu código seja comprometido.
8. Resultados saem sempre como download de bytes opacos, para que nada convertido seja interpretado na origem.

Progresso é por consulta periódica: `EventSource` não envia cabeçalhos e obrigaria a pôr o token na URL.

## Alternativas

`jdk.httpserver` eliminaria cerca de 6 MB de dependências, mas exigiria escrever roteamento e arquivos estáticos. O
Javalin fica, sem a parte que o Morph não usa: `jetty-ee10-annotations` e, com ele, JNDI, CDI e ASM foram excluídos.

## Risco aceito

Um programa malicioso rodando na mesma conta já pode ler os arquivos da pessoa; proteger o Morph dele está fora de
alcance.
