# Morph

Conversor universal de arquivos. **Tudo acontece no seu computador; nada é enviado.**

```bash
morph convert foto.png --to webp
morph convert pagina1.png pagina2.jpg --to pdf --merge -o documento.pdf
morph convert documento.pdf --to png -O dpi=200
morph web          # a mesma coisa, no navegador, usando o mesmo motor
```

> **Situação:** Fase 0 concluída (prova da arquitetura). Ainda não há versão publicada nem licença definida; veja o
> [ADR 0013](docs/adr/0013-licenca-e-identidade.md).

## O que já funciona

| De | Para |
|---|---|
| PNG | JPEG, WebP, PDF |
| JPEG | PNG, WebP, PDF |
| PDF | PNG, JPEG (uma imagem por página) |

Várias imagens podem virar um único PDF. Todo resultado é relido e verificado antes de ser entregue. `morph formats -v`
mostra opções, perdas e limitações de cada conversão.

## Compilar e executar

Requisitos: JDK 21 ou mais novo e Node 22.12 ou mais novo.

```bash
cd web && npm ci && npm run build:local && cd ..
./mvnw verify -Prequire-web
morph-cli/target/morph-0.1.0-SNAPSHOT/morph/bin/morph doctor
```

Para rodar todas as verificações, em todos os JDKs disponíveis e no navegador: `node scripts/verify.mjs`.

## Documentação

- [Arquitetura](docs/arquitetura.md)
- [Análise técnica e riscos](docs/analise-tecnica.md)
- [Segurança e privacidade](docs/seguranca.md)
- [Compatibilidade](docs/compatibilidade.md)
- [Relatório da Fase 0](docs/relatorio-fase-0.md): testes, resultados e limitações
- [Decisões arquiteturais](docs/adr/README.md)
- [Como contribuir](CONTRIBUTING.md)
