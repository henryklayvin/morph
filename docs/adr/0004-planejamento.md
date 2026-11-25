# 0004. Plano de um único passo e cardinalidade explícita

**Situação:** aceita

## Decisão

- O planejador escolhe **um** Provider para **uma** operação. Não encadeia conversões.
- Entre os disponíveis, vence a maior prioridade; o empate é resolvido pelo identificador, para ser determinístico.
- A cardinalidade faz parte do contrato: um para um, um para muitos (PDF para imagens), muitos para um (imagens para
  PDF). Entradas mistas são aceitas se o mesmo Provider as aceita com as mesmas opções.

## Por quê

Encadear `docx -> pdf -> png` automaticamente soma perdas que a pessoa não pediu e torna erros difíceis de explicar.
Quando um encadeamento for desejável, ele será uma capacidade declarada, com perdas próprias.

As conversões prioritárias do MVP já incluem 1->N e N->1. Um contrato 1->1 teria de ser quebrado na semana seguinte;
por isso o Provider de PDF entrou nesta etapa, como prova do contrato e não como ampliação do catálogo.

## Consequências

Pedidos impossíveis respondem com os destinos existentes. `docx -> pdf` já é compreendido e respondido com clareza.
