## Macrosoft Launcher v11 — atualização técnica

Finalizamos o novo fluxo de Java e compatibilidade do CloudScript.

**Java**
- Ao concluir **Preparar**, o launcher valida o Java 8 configurado e os runtimes de `.macrosoft/.java` usando `java -version`.
- Caminhos inválidos são corrigidos automaticamente.
- Se uma tentativa falhar por incompatibilidade, o próximo runtime válido é configurado e testado.
- Quando todos falham, o launcher oferece o gerenciador de Java.
- O Java baixado é salvo no perfil com caminho portátil `.macrosoft/.java/...`.
- A ordem é: Java do perfil, runtimes de `.macrosoft/.java` e Java 8 encontrados no sistema.
- A busca inclui `PATH`, variáveis de ambiente, diretórios conhecidos e o Registro do Windows. O botão **Detectar JAVA** continua disponível para seleção manual.

**CloudScript**
- O launcher acrescenta `$${run(macrosoft_healthcheck)}$$` ao evento `onJoinGame`, sem duplicar ou remover o `$${event}$$` existente.
- Após o pedido da macro, aguarda até 45 segundos por `[MacrosoftHealth] CLOUDSCRIPT_OK` no Game Output.
- `ok=false`, falha ao iniciar, socket desconectado ou timeout fazem o launcher tentar o próximo Java.
- Prefixos como `[CHAT]` e `[LOG]` não interferem na detecção.

**Teste confirmado**
```text
Requesting macro: macrosoft_healthcheck
Macro response: ok=true
[LOG] [MacrosoftHealth] CLOUDSCRIPT_OK
Macro start result: true
```

Build validado com `./gradlew test shadowJar` e `git diff --check`.

Commit principal: `f4606e5`

Observação: o health check atual confirma carregamento, conexão e execução do CloudScript. Ele não valida a renderização visual do menu Home.
