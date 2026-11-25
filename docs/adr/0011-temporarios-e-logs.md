# 0011. Arquivos temporários privados e ausência de logs

**Situação:** aceita

## Decisão

Arquivos temporários são cópias de documentos pessoais e recebem o mesmo cuidado.

- Um diretório por processo, criado com permissão exclusiva da conta, com um subdiretório por trabalho.
- Cada trabalho apaga o seu ao terminar, com sucesso ou não. O processo apaga tudo ao sair, inclusive com Ctrl+C.
- **Após uma queda**, o próximo Morph apaga o que ficou: o diretório é mantido por uma trava de arquivo, e trava livre
  significa dono morto. Só são tocados diretórios da mesma conta, e links simbólicos nunca são seguidos.
- No Morph Local, arquivos recebidos têm nomes aleatórios em disco e são apagados após uma hora sem uso.

O Morph **não escreve logs**. Caminhos, nomes e mensagens de bibliotecas podem conter dados pessoais. Mensagens de
`MorphException` não podem conter caminhos; a CLI os acrescenta, pois quem lê já os conhece. O HTTP nunca os revela.

## Consequências

Diagnosticar erros inesperados fica mais difícil. Se fizer falta, um modo explícito de diagnóstico poderá gravar um
arquivo local que a própria pessoa decide compartilhar. Nunca automático.
