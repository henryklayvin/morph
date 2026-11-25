# 0001. Java 21, Maven multimódulo, sem Spring e sem JPMS

**Situação:** aceita

## Decisão

- Compilar com `--release 21`, qualquer que seja o JDK da compilação. JDK 21 é o runtime mínimo; JDK 25 é testado.
- Maven com wrapper (3.10.2, com SHA-256 conferido). Avisos do compilador são erros.
- Sem Spring. Sem `module-info.java`: o classpath é a forma suportada.

## Por quê

`--release` impede o uso acidental de APIs posteriores, o que `-source/-target` não faz. O Enforcer recusa qualquer
dependência, direta ou transitiva, com bytecode acima de 21, e um teste confere que toda classe do Morph é versão 65.

JPMS daria encapsulamento forte, mas Javalin, Kotlin e TwelveMonkeys não são módulos nomeados de ponta a ponta; o
resultado seria um grafo de módulos automáticos frágil. As mesmas fronteiras são garantidas pelos módulos Maven e por
regras ArchUnit executadas sobre as classes compiladas.

## Consequências

- Nada de APIs que só existem depois do 21, como a FFM API estável (22). Código nativo entra por JNI (ADR 0006).
- JDK 24+ exige `--enable-native-access` para carregar código nativo sem aviso (JEP 472). Os lançadores e o manifesto
  do JAR já declaram isso; o JDK 21 aceita a opção e ignora o atributo.
- `System.console()` e `SecurityManager` mudaram entre versões; nenhum dos dois é base de segurança no Morph.
