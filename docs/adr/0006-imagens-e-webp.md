# 0006. Imagens: decodificação na JVM, libwebp só para gravar

**Situação:** aceita

## Decisão

| Tarefa | Mecanismo |
|---|---|
| Ler PNG; gravar PNG e JPEG | JDK ImageIO |
| Ler JPEG | TwelveMonkeys (CMYK e arquivos incomuns que o JDK recusa) |
| Gravar WebP | `webp4j` 2.5.0, libwebp 1.6.0 via JNI |
| Reler WebP na verificação | TwelveMonkeys, Java puro |

Bytes não confiáveis nunca chegam à libwebp: ela recebe apenas pixels que o Morph decodificou. Isso elimina a classe
de falhas do CVE-2023-4863, que está na decodificação. Nenhuma outra classe pode referenciar `webp4j`.

## Alternativas para WebP

| Candidato | Por que não |
|---|---|
| `org.sejda:webp-imageio` 0.1.6 | Parado desde 2020, libwebp antiga, sem ARM |
| `com.github.usefulness:webp-imageio` 0.11.0 | Ativo e com libwebp 1.6.0, mas registra um **leitor** nativo global no ImageIO, exige Kotlin 2.4 (conflita com o Javalin) e não tem Windows ARM64 |
| `scrimage-webp` | Executa binários embutidos a cada imagem |
| Codificador próprio | Fora da proposta do projeto |

## Detalhes que custam caro quando ignorados

- A orientação EXIF é aplicada aos pixels, já que os metadados são descartados.
- Cinza não passa pelo Java 2D, que trata `CS_GRAY` como luz linear: 128 viraria cerca de 188.
- Dimensões e memória são conferidas no cabeçalho, antes de alocar.
- `ImageIO.setUseCache(false)`: o padrão copia imagens para o diretório temporário do sistema.

## Riscos aceitos

- `webp4j` é um projeto pequeno. Mitigação: um único ponto de uso, disponibilidade por capacidade, licença MIT.
- No Windows ele não apaga a própria DLL (1,2 MB por execução). O Morph remove as que ficaram para trás.
- Não há binário para Linux musl (Alpine). Ali o WebP aparece como indisponível e o resto funciona.
- Políticas de controle de aplicativos podem recusar a DLL não assinada (ver ADR 0012).
