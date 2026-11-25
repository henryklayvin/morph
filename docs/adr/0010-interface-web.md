# 0010. Uma interface, backends intercambiáveis, contrato fixado em arquivo

**Situação:** aceita

## Decisão

Uma única aplicação React e TypeScript. Quem converte é um `ConversionBackend`: `LocalEngineBackend` (motor Java via
loopback) hoje; um backend de navegador, com Web Workers e WebAssembly, na Fase 3.

O que as duas experiências compartilham: formatos, identificadores de operação (`png-to-webp`), tipos de opção,
códigos de erro e toda a interface. O que **não** compartilham: codificadores.

`web/contract/capabilities.json` é gerado pelos testes Java e lido pelos testes TypeScript. Uma mudança em um lado
reprova o outro.

## Correção de expectativa

A visão do produto diz que o resultado é o mesmo nas duas experiências. Isso é verdade, e testado byte a byte, entre a
**CLI e o Morph Local**. Não pode ser verdade entre o motor Java e o **site público**: cada navegador usa seu próprio
codificador, e o Safari não grava WebP por canvas. Ali a garantia será de equivalência: mesmo formato, mesmas dimensões
e semelhança visual dentro de tolerância. A interface não deve prometer mais do que isso.

## Consequências

- Nenhum recurso externo: sem fontes remotas, CDN ou telemetria, e nada embutido em `data:`.
- Os testes do backend de navegador usarão o mesmo arquivo de contrato e as mesmas amostras.
