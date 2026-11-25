# 0009. Execução no processo agora, isolamento antes da Fase 5

**Situação:** aceita, com pendência

## Decisão

Conversões rodam em um conjunto pequeno de threads de plataforma, pois o trabalho é de CPU e virtual threads não
ajudam. Cancelamento e timeout são cooperativos: token consultado pelos decodificadores, interrupção da thread e
período de tolerância. Depois dele o trabalho é dado como encerrado e **nunca** entrega resultado.

## Limite que precisa ser dito

A JVM não permite matar uma thread. Um arquivo que prenda um decodificador em laço infinito ocupa um dos trabalhadores
até o processo terminar; na CLI isso é o Ctrl+C, no Morph Local é reiniciar. Da mesma forma, os limites de memória são
estimativas conferidas antes de alocar, não uma barreira do sistema operacional.

Para PNG, JPEG e PDF, com limites prévios e testes com arquivos danificados, o risco é aceitável. Para o que vem
depois, não.

## Pendência obrigatória antes da Fase 5

Executar Providers de risco em um processo filho, com heap próprio, tempo limite por término forçado e sem rede. O
contrato já permite: entradas, opções, limites e diretório de trabalho são dados simples, e `onCancel` existe para
destruir o processo. LibreOffice e FFmpeg serão processos filhos de qualquer modo, então o supervisor é trabalho comum.
