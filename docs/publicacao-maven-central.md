# Publicação no Maven Central

**Situação:** preparada, não publicada. Nenhum artefato do Morph foi enviado ao Maven Central, por decisão do
responsável pelo projeto ([ADR 0013](adr/0013-licenca-e-identidade.md)).

Fontes consultadas em 2026-11-25: [registro de namespace](https://central.sonatype.org/register/namespace/),
[requisitos](https://central.sonatype.org/publish/requirements/) e
[publicação com Maven](https://central.sonatype.org/publish/publish-portal-maven/). Confira de novo antes de publicar:
o processo do Central muda.

## Coordenadas

| Item | Valor |
|---|---|
| Namespace | `io.github.henryklayvin` |
| `groupId` | `io.github.henryklayvin.morph` |
| `artifactId` | `morph-parent` (POM), `morph-contracts`, `morph-engine`, `morph-provider-image`, `morph-provider-pdf`, `morph-application`, `morph-local-web`, `morph-cli` |
| Versão atual | `0.1.0-SNAPSHOT` (o Central não aceita versões `-SNAPSHOT`) |

## 1. Verificar o namespace

1. Entre em <https://central.sonatype.com> com a conta do GitHub `henryklayvin`. Nesse caso, o Central costuma verificar
   e liberar sozinho o namespace `io.github.henryklayvin`. Só vale para a conta usada no login, não para organizações.
2. No menu da conta (canto superior direito), abra **View Namespaces** e confira se `io.github.henryklayvin` aparece
   como verificado. Se aparecer, a verificação terminou.
3. Se não aparecer:
   1. Em **Publishing Settings**, clique em **Add Namespace**, informe `io.github.henryklayvin` e envie. Ele fica como
      *Unverified*.
   2. Copie a **Verification Key**.
   3. Crie no GitHub um repositório **público** com o nome igual à chave: `https://github.com/henryklayvin/<chave>`.
   4. Só depois que o repositório existir, clique em **Verify Namespace** e confirme. O estado passa a
      *Verification Pending* e, em seguida, a verificado.
   5. Verificado o namespace, o repositório temporário pode ser apagado.
4. Se nada disso funcionar, escreva para central-support@sonatype.com.

O `groupId` `io.github.henryklayvin.morph` fica abaixo do namespace. A documentação diz explicitamente que subgrupos
são aceitos quando o namespace é um domínio próprio; para `io.github`, confirme no primeiro envio de teste.

## 2. O que já está no POM

O POM pai já tem os metadados que o Central exige, e os módulos os herdam:

- `name`, `description` e `url`;
- `licenses`: Apache-2.0, com o endereço do texto;
- `developers`: identificador, nome e perfil do GitHub. **Sem e-mail**: o Central não o exige, e ele ficaria público
  para sempre em cada artefato;
- `scm`, `issueManagement` e `ciManagement`, apontando para o repositório oficial.

O `project.build.outputTimestamp` já está fixado, o que deixa os JARs reproduzíveis.

## 3. O que falta antes da primeira publicação

1. **Escolher os módulos publicados.** Um Provider de terceiros só precisa de `morph-contracts`. A CLI é distribuída
   como pacote ([ADR 0012](adr/0012-distribuicao.md)), não pelo Central. O POM pai vai junto com qualquer módulo
   publicado, porque os POMs dos módulos herdam dele.
2. **Versão sem `-SNAPSHOT`**, por exemplo `0.1.0`.
3. **Código-fonte e Javadoc:** para cada JAR, um `-sources.jar` e um `-javadoc.jar` (`maven-source-plugin` e
   `maven-javadoc-plugin`, em um perfil `release`).
4. **Assinatura GPG:** um `.asc` para cada arquivo (POM, JAR, sources e Javadoc), com `maven-gpg-plugin`. A chave
   pública precisa estar em um servidor de chaves, como o keys.openpgp.org.
5. **Checksums** `.md5` e `.sha1`, gerados pelo plugin de publicação.
6. **Plugin de publicação:** `org.sonatype.central:central-publishing-maven-plugin`, que estava na versão 0.12.0 na
   data da consulta, com `<extensions>true</extensions>` e `<publishingServerId>central</publishingServerId>`. Manter
   `autoPublish` desligado (o padrão), para que cada publicação seja confirmada à mão no portal.
7. **Credenciais:** um token de usuário gerado no portal, guardado em `~/.m2/settings.xml` (servidor com
   `<id>central</id>`) ou nos segredos do CI. **Nunca no repositório.**
8. **Política de dependências:** os plugins novos seguem o [ADR 0014](adr/0014-dependencias.md), com versão fixa e
   publicada há pelo menos 14 dias.
9. **Licenças de terceiros:** reunir os avisos das bibliotecas incluídas no pacote distribuído (pendência do ADR 0013).

Esses plugins não foram configurados agora de propósito: configuração que não roda não é testada e envelhece sem que
ninguém perceba. Eles entram junto com a primeira publicação.
