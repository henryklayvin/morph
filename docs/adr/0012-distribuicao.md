# 0012. Distribuição: jlink, jpackage e assinatura

**Situação:** proposta (Fase 6)

## O que foi medido

| Item | Resultado |
|---|---|
| Distribuição portátil (`bin/` + `lib/`), sem runtime | 16 MB |
| Runtime reduzido com `jlink` (JDK 21) | 47 MB |
| `jpackage --type app-image` (JDK 21) | gera a imagem, 117 MB sem otimizar |
| Execução do `Morph.exe` gerado | **bloqueada** pelo Controle de Aplicativo do Windows 11 |

Módulos necessários: `java.base, java.desktop, java.logging, java.management, java.naming, java.xml, jdk.unsupported,
jdk.localedata, jdk.zipfs`. Sem `jdk.zipfs` a CLI funciona, mas o `morph web` não inicia: o Jetty o usa para ler a
interface de dentro do JAR. Com ele, o teste no navegador passa inteiro sobre o runtime reduzido.

## Consequência principal

Em Windows 11 com controle de aplicativos ativo, um executável **não assinado não abre**. Não é um aviso que a pessoa
possa ignorar. Distribuir para pessoas comuns exige certificado de assinatura no Windows e conta de desenvolvedor com
notarização no macOS. São custos e prazos a planejar antes da Fase 6, não durante.

O pacote portátil não é afetado do mesmo modo, porque executa o `java` já instalado e assinado.

## GraalVM Native Image

Adiado, como previsto. AWT/ImageIO, JNI e `ServiceLoader` são os pontos difíceis. A conversão de uma imagem pequena
leva cerca de 1,6 s em processo novo, quase tudo início da JVM; AppCDS deve ser tentado antes.
