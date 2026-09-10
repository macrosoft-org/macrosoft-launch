# Relatório técnico — Macrosoft Launcher v11

## Objetivo

A v11 reduz a intervenção necessária quando uma modpack não inicia por causa do
Java. O launcher agora valida runtimes, corrige caminhos inválidos, tenta
alternativas controladas, integra o gerenciador de Java ao perfil da modpack e
executa um health check do CloudScript no ambiente real do Minecraft.

## Escopo entregue

### Descoberta e validação de Java

- Todo candidato é executado com `java -version` e possui limite de cinco
  segundos para responder.
- A validação exige um executável funcional do Java 8.
- A descoberta automática considera, nesta ordem:
  1. o Java 8 válido já configurado no perfil;
  2. os runtimes mantidos pelo launcher em `.macrosoft/.java/`;
  3. os Java 8 encontrados no sistema.
- A descoberta percorre `PATH`, variáveis de ambiente, `/opt`, `/usr/lib/jvm`,
  locais conhecidos do macOS e o Registro do Windows.
- O botão **Detectar JAVA** preserva a mesma busca completa para seleção manual.

### Caminhos portáteis

Runtimes instalados pelo gerenciador são salvos no perfil com caminho relativo:

```text
.macrosoft/.java/<runtime>/bin/java
```

No Windows, o executável pode ser `javaw.exe` ou `java.exe`. O runner resolve o
caminho relativo a partir do diretório real do launcher antes de criar o processo
do jogo. O editor de perfil também reconhece esse formato.

### Recuperação de falhas

Ao terminar **Preparar**, o launcher monta uma lista ordenada de candidatos e
configura o primeiro runtime válido. Se a inicialização produzir uma falha de JVM
reconhecida, o processo termina e o próximo candidato é aplicado ao perfil antes
de uma nova tentativa.

Entre os sinais reconhecidos estão:

- `Unrecognized VM option`;
- `Could not create the Java Virtual Machine`;
- `UnsupportedClassVersionError`;
- versão de bytecode incompatível;
- falha ao localizar ou carregar a JVM;
- falha do sistema operacional ao executar o caminho configurado.

Quando os candidatos acabam, o launcher mostra um aviso e abre o gerenciador de
Java após a confirmação do usuário. Ao concluir o download, o callback da janela
aplica e salva imediatamente o novo executável no perfil da modpack. O usuário
continua responsável por clicar em **Jogar** novamente.

### Health check do CloudScript

Antes de iniciar o processo, o launcher acrescenta de forma idempotente esta
chamada ao binding `Macro[1000]`, correspondente ao `onJoinGame`:

```text
$${run(macrosoft_healthcheck)}$$
```

O binding existente `$${event}$$` é preservado. O resultado final fica equivalente
a:

```text
Macro[1000].Macro=$${event}$$|$${run(macrosoft_healthcheck)}$$
```

O health check começa efetivamente quando o Game Output contém:

```text
[CloudScript] Requesting macro: macrosoft_healthcheck
```

A partir desse ponto, o launcher aguarda até 45 segundos pelo marcador:

```text
[MacrosoftHealth] CLOUDSCRIPT_OK
```

O reconhecimento é feito por substring. Prefixos adicionados pelo Minecraft ou
pelo Macro/Keybind, como `[CHAT]`, `[LOG]`, data e thread, não interferem.

As seguintes condições reprovam a tentativa:

- resposta da macro com `ok=false`;
- resultado de inicialização da macro igual a `false`;
- execução ignorada porque o socket está desconectado;
- ausência de `CLOUDSCRIPT_OK` durante 45 segundos após o pedido.

Quando ocorre uma reprovação, o runner encerra o processo atual e reutiliza o
fluxo de candidatos para experimentar o próximo Java. O timer roda em thread
daemon e é cancelado quando o marcador chega ou quando o jogo termina.

## Evidência funcional

O teste integrado produziu a sequência esperada:

```text
[CloudScript] Requesting macro: macrosoft_healthcheck
[CloudScript] Macro response for macrosoft_healthcheck: ok=true
[CloudScript] Starting macro: macrosoft_healthcheck
[LOG] [MacrosoftHealth] CLOUDSCRIPT_OK
[CloudScript] Macro start result for macrosoft_healthcheck: true
```

Isso comprova carregamento do módulo, conexão, obtenção da macro, início da
execução e entrega do marcador ao Game Output. A macro atual valida a operação do
CloudScript. Para comprovar renderização de uma GUI específica, ela precisaria
construir essa GUI antes de emitir `CLOUDSCRIPT_OK`.

Também foi validada a descoberta do runtime externo presente no ambiente de
desenvolvimento:

```text
[/opt/jdk1.8.0_202/bin/java]
```

O resultado demonstra que o fluxo automático encontra e valida Java 8 fora da
pasta gerenciada quando necessário.

## Arquivos principais

| Arquivo | Responsabilidade |
|---|---|
| `JavaLocator.java` | Descoberta completa, descoberta gerenciada e validação com `java -version` |
| `MacrosoftModpackBrowser.java` | Ordenação dos candidatos, persistência no perfil, tentativas e abertura do gerenciador |
| `MinecraftGameRunner.java` | Resolução do executável, análise do Game Output e health check do CloudScript |
| `GameLaunchDispatcher.java` | Encaminhamento das falhas do runner ao fluxo de recuperação |
| `JavaRuntimeManagerDialog.java` | Retorno do runtime instalado ao perfil solicitante |
| `ProfileJavaPanel.java` | Edição, detecção manual e suporte a caminhos relativos |
| `LauncherConstants.java` | Versão pública v11 |

## Validação de build

Foram executados:

```text
./gradlew test shadowJar
git diff --check
```

O projeto não possui fontes de teste automatizado neste momento. A compilação,
o empacotamento do fat JAR, a transformação idempotente de `.macros.txt` e os dois
modos de descoberta foram verificados. O artefato resultante é:

```text
build/libs/mclaunch-all.jar
```

## Limitações conhecidas

- O health check acontece em `onJoinGame`; antes de entrar em um mundo ou servidor
  ainda não existe resultado funcional da macro.
- A ausência do marcador depois do pedido pode representar incompatibilidade,
  erro da macro ou indisponibilidade temporária do serviço. Atualmente essas
  situações percorrem os candidatos de Java pelo mesmo fluxo.
- `LOGTO` não é necessário. O launcher acompanha stdout/stderr ao vivo e não lê o
  arquivo produzido por essa ação.

## Commits

```text
f4606e5 v11: automatiza recuperação do Java e health check do CloudScript
fdb1ab5 chore: ignora artefatos de evidência
```
