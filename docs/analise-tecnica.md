# Análise técnica e riscos

Avaliação crítica da arquitetura proposta, feita em 2026-11-25.

## Onde a proposta se sustenta

Núcleo único independente de interfaces, Providers, processamento local e Java 21 foram comprovados com código e
testes. Nenhum princípio precisou ser alterado.

## Onde ela precisou de ajuste

| Proposta | Ajuste | Motivo |
|---|---|---|
| Resultado igual na Web e no terminal | Igual, byte a byte, entre CLI e Morph Local; **equivalente** no site público | Navegadores usam codificadores próprios ([ADR 0010](adr/0010-interface-web.md)) |
| Seis módulos de Providers desde o início | Dois, os que têm código | Módulos vazios não provam nada |
| Timeout para operações | Cooperativo; isolamento por processo antes da Fase 5 | A JVM não mata threads ([ADR 0009](adr/0009-execucao-e-isolamento.md)) |
| TwelveMonkeys e "bibliotecas para WebP" | TwelveMonkeys só **lê** WebP; gravar exige código nativo | [ADR 0006](adr/0006-imagens-e-webp.md) |
| Pacotes com `jpackage` | Viável, mas exige assinatura de código | Windows bloqueou o executável não assinado ([ADR 0012](adr/0012-distribuicao.md)) |

## Compatibilidade das tecnologias com Java 21

| Tecnologia | Versão | Mínimo | JDK 21 | JDK 25 | Observação |
|---|---|---|---|---|---|
| Picocli | 4.7.7 | 5 | sim | sim | |
| Javalin / Jetty | 7.2.4 / 12.1.14 | 17 | sim | sim | traz Kotlin; runtime reduzido precisa de `jdk.zipfs` |
| Jackson | 3.2.4 | 17 | sim | sim | |
| PDFBox / JBIG2 | 3.0.8 / 3.0.5 | 8 | sim | sim | |
| TwelveMonkeys | 3.15.3 | 8 | sim | sim | |
| webp4j (libwebp 1.6.0) | 2.5.0 | 8 | sim | sim | JDK 24+ pede `--enable-native-access` |
| JUnit / ArchUnit | 6.1.3 / 1.5.1 | 17 / 8 | sim | sim | |
| Vite / TypeScript / React | 8.3.8 / 7.0.2 / 19.3.1 | | | | Node 22.12+ |

Todas executadas nos dois JDKs, em Windows e Linux. Em macOS, nada foi executado ainda.

## Riscos, por ordem de importância

1. **LibreOffice e FFmpeg podem acessar a rede.** Um DOCX pode apontar para imagens remotas e uma lista HLS para
   servidores: converter abriria conexões, quebrando a promessa central sem que ninguém percebesse. Antes da Fase 5:
   perfil do LibreOffice isolado, com vínculos e conteúdo ativo bloqueados; FFmpeg com `-protocol_whitelist file,pipe`;
   processo filho sem rede onde o sistema permitir; e o teste de rede do Linux aplicado a cada ferramenta.
   O mesmo vale para SVG, HTML, XML (XXE) e EPUB.
2. **Sem isolamento, um arquivo hostil pode prender um trabalhador ou esgotar a memória.** Ver ADR 0009.
3. **Assinatura de código.** Sem ela, a versão para pessoas comuns não abre no Windows 11 com controle de aplicativos.
4. **`webp4j` é pequeno** (cerca de 70 estrelas, um mantenedor). Contido em uma classe e substituível.
5. **Fontes.** PDFs sem fontes incorporadas mudam conforme as fontes instaladas: as imagens podem diferir entre
   máquinas. Declarado como limitação.
6. **Cores.** PNG com perfil embutido é tratado como sRGB; perfis em JPEG são convertidos. Fotos Display P3 em PNG
   podem mudar levemente.
7. **Multiplataforma.** O CI está escrito, mas ainda não foi executado em nenhum servidor.
8. **Patentes e licenças de codecs** (H.264, AAC, HEVC). Usar FFmpeg instalado pela pessoa, não distribuí-lo, adia a
   questão.

## Sugestões de produto

- **HEIC não está no catálogo** de longo prazo e é o formato padrão das fotos do iPhone. Provavelmente será o primeiro
  pedido de pessoas comuns.
- Início da JVM: cerca de 1,5 s por comando. AppCDS deve ser avaliado cedo, pois afeta quem usa a CLI em scripts.
