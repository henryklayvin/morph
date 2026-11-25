# 0002. Módulos e direção das dependências

**Situação:** aceita

## Decisão

```text
morph-contracts        modelos e SPI; não depende de nada além de java.*
   ^        ^
   |        +---- providers/morph-provider-image  <---- providers/morph-provider-pdf
   |                        ^                                  ^
morph-engine                | (somente em runtime)             |
   ^                        |                                  |
   +-------- morph-application --------------------------------+
                  ^         ^
       morph-local-web <--- morph-cli
```

- O motor não conhece Providers, AWT, HTTP nem terminal.
- Providers dependem só dos contratos. Exceção: o de PDF usa `raster` do de imagem, porque lê e grava imagens.
- CLI e Morph Local não referenciam classes de Providers; recebem o motor pronto do `morph-application`.
- Módulos só são criados quando têm código. `office`, `media`, `archive` e `data` nascem nas respectivas fases.

## Por quê

A dependência de PDF em imagem evita duplicar o código mais sensível do projeto: limites contra bombas de
descompressão, orientação EXIF e verificação. Duplicado, ele divergiria.

## Como é garantido

`ArchitectureTest` reprova a compilação se uma fronteira for violada, se Javalin ou Picocli saírem da sua camada, se
algo além do `BrowserLauncher` iniciar processos ou se qualquer classe do Morph puder abrir uma conexão.
