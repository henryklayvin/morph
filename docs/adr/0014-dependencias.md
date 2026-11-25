# 0014. Política de dependências

**Situação:** aceita

## Decisão

- Versões exatas e iguais para todos: `dependencyConvergence` no Maven, `package-lock.json` e versões fixas no npm.
- Salvo correção de segurança, adotar versões publicadas há pelo menos 14 dias. Por isso Javalin 7.2.4 e não 7.3.0, e
  Jackson 3.2.4 e não 3.3.0, lançadas na semana deste registro.
- Toda dependência tem motivo escrito no `pom.xml` ou em um ADR, e licença declarada no descritor do Provider.
- Bytecode acima de Java 21 e Spring são recusados pelo Enforcer.
- Ações de CI são fixadas por commit; a distribuição do Maven tem SHA-256 conferido.

## Situação atual

37 bibliotecas Java e 96 pacotes npm (apenas 3 chegam ao navegador: `react`, `react-dom` e `scheduler`) consultados na
base OSV em 2026-11-25: nenhuma vulnerabilidade conhecida. A consulta ainda é manual; automatizá-la é parte da Fase 6.

## Por que não há Mockito, AssertJ, ESLint ou biblioteca de componentes

Não fizeram falta. Testes usam Providers e servidores reais; a interface tem quatro componentes.
