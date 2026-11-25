# 0003. Contrato de Provider e descoberta

**Situação:** aceita

## Decisão

Um `ConversionProvider` declara um descritor (identificador, dependências, licenças, requisitos) e uma lista de
`Capability`. Cada uma é uma linha da matriz: origem, destino, cardinalidade, prioridade, opções, perdas e limitações.

- A descoberta usa `ServiceLoader` sobre o classpath da própria aplicação. Não há diretório de plugins.
- **Disponibilidade é por capacidade, não por Provider.** Sem o codificador WebP, PNG e JPEG continuam funcionando.
- Opções são dados tipados (`OptionSpec`): a CLI valida, a interface desenha, o Provider usa como chave. Opção
  desconhecida é erro, porque ignorá-la produziria o arquivo errado em silêncio.
- Formatos novos chegam com o Provider (`additionalFormats`), sem alterar o motor. Declarações conflitantes impedem o
  início.
- Um Provider que falha ao carregar não derruba os demais; o `morph doctor` informa.
- Identificação é por conteúdo. A extensão só desempata famílias de contêiner (ZIP) e gera aviso quando diverge.

## Alternativas

Um `convert(Path, Path)` simples não expressa N entradas, N saídas, cancelamento, limites nem verificação, e todos já
eram necessários na primeira etapa.

## Como é garantido

`ProviderContractTest` roda sobre todo Provider descoberto, presente ou futuro: entrega verificada, falha limpa com
lixo, origem intacta, área temporária vazia, mensagens sem caminhos.
