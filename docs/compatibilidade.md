# Compatibilidade

## Conversões

| Operação | Provider | CLI | Morph Local | Site público | Dependência externa | Perdas |
|---|---|---|---|---|---|---|
| `png-to-jpeg` | image | sim | sim | Fase 3 | nenhuma | qualidade, transparência, metadados, perfil, 16 bits |
| `png-to-webp` | image | sim | sim | Fase 3 | libwebp, incluída | qualidade (salvo `lossless`), metadados, perfil, 16 bits |
| `jpeg-to-png` | image | sim | sim | Fase 3 | nenhuma | metadados; cores convertidas para sRGB |
| `jpeg-to-webp` | image | sim | sim | Fase 3 | libwebp, incluída | qualidade (salvo `lossless`), metadados |
| `png-to-pdf` | pdf | sim | sim | Fase 3 | nenhuma | metadados, 16 bits |
| `jpeg-to-pdf` | pdf | sim | sim | Fase 3 | nenhuma | metadados |
| `pdf-to-png` | pdf | sim | sim | a avaliar | nenhuma | texto e vetores viram pixels |
| `pdf-to-jpeg` | pdf | sim | sim | a avaliar | nenhuma | idem, e qualidade |
| `docx-to-pdf` | office | Fase 5 | Fase 5 | não | LibreOffice | a definir |

A fonte da verdade é o próprio programa: `morph formats --json`. O DOCX já é reconhecido e respondido com clareza.

## Ambientes

| Ambiente | Compilação e testes | Pacote executado | Navegador |
|---|---|---|---|
| Windows 11 x64, JDK 21 (Oracle 21.0.12.1) | sim | sim | Chrome e Edge |
| Windows 11 x64, JDK 25 (Temurin 25.0.5.1) | sim | sim | Chrome e Edge |
| Ubuntu 26.04 (WSL2), JDK 21 (Temurin 21.0.13.1) | sim | sim, sem rede | não executado |
| Ubuntu 26.04 (WSL2), JDK 25 (Temurin 25.0.5.1) | sim | sim, sem rede | não executado |
| macOS | **não executado** | não | não |
| Firefox e Safari | | | **não executado** |

## Política de Java

JDK 21 é o mínimo e o alvo. O pacote é compilado uma vez, com `--release 21`, e precisa rodar sem mudanças no JDK 25.
