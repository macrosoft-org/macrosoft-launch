# Macrosoft Launcher — Changelog de Performance e Estabilidade

> **v11** — Gerenciamento assistido de Java: detecção e configuração automáticas, recuperação de falhas de inicialização e integração com o instalador isolado do launcher.

## Novidades da v11

- Valida automaticamente o Java 8 configurado e os runtimes instalados em
  `.macrosoft/.java` ao terminar **Preparar**.
- Reserva a procura de Java no sistema para a ação explícita **Detectar JAVA**.
- Substitui caminhos inválidos pelo primeiro runtime funcional encontrado.
- Prioriza o Java do perfil e os runtimes mantidos em `.macrosoft/.java/`.
- Tenta o próximo candidato quando o output indica incompatibilidade de Java.
- Abre o gerenciador de Java somente depois que todos os candidatos falham.
- Configura e salva automaticamente no perfil o runtime instalado pelo gerenciador.
- Mantém a nova tentativa de jogo como uma ação do usuário.
- Executa `macrosoft_healthcheck` automaticamente no evento `onJoinGame` e
  reconhece seu resultado no Game Output.
- Avança para o próximo Java quando a macro responde com falha, não inicia ou não
  produz o marcador de sucesso dentro de 45 segundos.

---

> **v10** — Correção de regressão de performance v8→v9, implementação de ocultação do launcher durante o gameplay e restauração dos logs do Minecraft em tempo real.

---

## Problemas identificados na v9 (corrigidos na v10)

A v9 apresentava dois grandes problemas em relação à v8:

1. **Lentidão dentro do Minecraft** — menus animados travavam, hover nos botões respondia com atraso. Fechando o launcher, o problema desaparecia, confirmando que o launcher competia por recursos com o jogo.
2. **Botão "Parar" não respondia** — após um erro na inicialização do jogo, o launcher ficava preso sem transitar de volta para o estado `IDLE`.
3. **Launcher não sumia** ao iniciar o jogo — o browser ficava visível consumindo recursos durante toda a sessão.

---

## Correções aplicadas

### 1. Bug crítico: spin-loop no `DirectProcessInputMonitor`

**Arquivo:** `DirectProcessInputMonitor.java`

**Problema:** O bloco `finally { IOUtils.closeQuietly(reader) }` estava _dentro_ do loop externo `while (this.process.isRunning())`. Quando o stdout chegava ao EOF, `readLine()` retornava `null`, o `finally` fechava o reader, e o loop externo reiniciava — tentando ler de um stream já fechado → `IOException` → catch → loop novamente → **busy-loop a 100% de CPU** durante toda a sessão do jogo.

**Correção:** O monitor foi substituído por uma thread de leitura embutida no `DirectGameProcess` com loop correto — sai naturalmente quando `readLine()` retorna `null` (EOF).

---

### 2. Bug: `IllegalThreadStateException` ao parar o jogo

**Arquivo:** `DirectGameProcess.java`

**Problema:** Quando o stdout chegava ao EOF antes de o processo terminar completamente, a thread do monitor chamava `onGameProcessEnded()` → `getExitCode()` → `process.exitValue()` → `IllegalThreadStateException: process hasn't exited`. A exceção não tratada impedia a transição para o estado `IDLE`, deixando o botão "Parar" sem resposta.

**Stack trace:**
```
java.lang.IllegalThreadStateException: process hasn't exited
  at com.mojang.launcher.game.process.direct.DirectGameProcess.getExitCode(DirectGameProcess.java:52)
  at net.minecraft.launcher.game.MinecraftGameRunner.onGameProcessEnded(MinecraftGameRunner.java:457)
  at com.mojang.launcher.game.process.direct.DirectProcessInputMonitor.run(DirectProcessInputMonitor.java:53)
```

**Correção:** A thread de leitura agora chama `process.waitFor()` após o loop, garantindo que o processo terminou antes de notificar `onGameProcessEnded`. O método `getExitCode()` também usa `waitFor()` em vez de `exitValue()`.

---

### 3. Inundação da EDT pelo `GameOutputTab`

**Arquivo:** `GameOutputTab.java`

**Problema:** Cada linha de log do Minecraft disparava um `SwingUtilities.invokeLater` separado, saturando a Event Dispatch Thread com milhares de tasks por segundo durante a inicialização do jogo.

**Correção:** Adicionado sistema de batching com `ConcurrentLinkedQueue<String>` + `javax.swing.Timer` a 100ms. As linhas são acumuladas na fila e enviadas para a EDT em lote a cada 100ms — reduzindo de milhares para ~10 updates/segundo.

---

### 4. Limpeza de arquivos conflitando com inicialização do Minecraft

**Arquivo:** `Launcher.java`

**Problema:** `performCleanups()` era chamado imediatamente após o lançamento do jogo, fazendo scan de milhares de arquivos de assets/libraries ao mesmo tempo que o Minecraft fazia o mesmo durante sua própria inicialização — gerando I/O contention grave.

**Correção:** Adicionado `performCleanupsAsync()` — executa a limpeza em uma thread daemon com prioridade mínima após um delay de 60 segundos, quando o Minecraft já terminou sua inicialização.

```java
public void performCleanupsAsync() {
    Thread cleanupThread = new Thread("launcher-cleanup") {
        @Override
        public void run() {
            try { Thread.sleep(60_000L); } catch (InterruptedException e) { return; }
            Launcher.this.performCleanups();
        }
    };
    cleanupThread.setDaemon(true);
    cleanupThread.setPriority(Thread.MIN_PRIORITY);
    cleanupThread.start();
}
```

---

### 5. Launcher não ocultava ao iniciar o jogo

**Arquivos:** `GameLaunchDispatcher.java`, `MacrosoftModpackBrowser.java`

**Problema (arquitetural):** O launcher usa `MacrosoftModpackBrowser` como UI principal. Ao lançar uma modpack, é criado um `Launcher` interno em modo headless (`suppressUI=true`). O método `SwingUserInterface.setVisible()` tem `if (suppressUI) return` — todas as chamadas de visibilidade eram **silenciosamente ignoradas**. A janela real é o `parentFrame` do browser, que nunca era tocado pela lógica de visibilidade do `MinecraftGameRunner`.

**Correção em dois passos:**

**a)** `GameLaunchDispatcher.play()` — força `HIDE_LAUNCHER` independente da configuração do perfil:
```java
// Antes (respeitava a configuração do perfil, que podia ser DO_NOTHING):
gameRunner.setVisibility(Objects.firstNonNull(
    profile.getLauncherVisibilityOnGameClose(),
    Profile.DEFAULT_LAUNCHER_VISIBILITY));

// Depois (sempre oculta):
gameRunner.setVisibility(LauncherVisibilityRule.HIDE_LAUNCHER);
```

**b)** `MacrosoftModpackBrowser.updateCardStates()` — detecta transições de estado e controla a visibilidade do `parentFrame`:
```java
private boolean wasAnyPlaying = false;

private void updateCardStates() {
    boolean isAnyPlaying = false;
    for (CardUi cu : cardUiList) {
        // ... atualiza botões ...
        if (state == ActionState.PLAYING) {
            isAnyPlaying = true;
            if (!wasAnyPlaying) openLogWindow(cu.entry); // abre logs automaticamente
        }
    }
    if (isAnyPlaying && !wasAnyPlaying) {
        parentFrame.setVisible(false);          // esconde ao iniciar
    } else if (!isAnyPlaying && wasAnyPlaying) {
        parentFrame.setVisible(true);           // reaparece ao fechar
        parentFrame.toFront();
        parentFrame.requestFocus();
    }
    wasAnyPlaying = isAnyPlaying;
}
```

---

### 6. Janela de logs sumia junto com o browser

**Arquivo:** `MacrosoftModpackBrowser.java`

**Problema:** O `JDialog` de logs era criado com `parentFrame` como dono. No Swing, quando uma janela pai é ocultada, todos os seus diálogos filhos são automaticamente ocultados junto.

**Correção:** O diálogo é criado sem dono (`null`), tornando-o uma janela independente:
```java
// Antes:
JDialog dialog = new JDialog(parentFrame, "Logs da " + entry.name, false);

// Depois:
JDialog dialog = new JDialog((java.awt.Frame) null, "Logs da " + entry.name, false);
```

---

### 7. Aba "Jogo" ausente na janela de logs / logs do Minecraft não capturados

**Arquivos:** `MinecraftGameRunner.java`, `DirectGameProcess.java`, `DirectGameProcessFactory.java`

**Problema:** Em uma iteração anterior, o stdout foi redirecionado para `DISCARD` e a chamada a `showGameOutputTab()` foi removida. Resultado: nenhum log do Minecraft era capturado e a aba "Jogo" não aparecia na janela de logs.

**Correção:**
- `DirectGameProcessFactory`: volta a usar `redirectErrorStream(true)` — stdout e stderr do Minecraft são capturados pelo launcher
- `DirectGameProcess`: thread de leitura com loop correto (sem spin), buffer de 64KB, prioridade mínima, daemon
- `MinecraftGameRunner`: restaura chamada a `showGameOutputTab()` e registra o `logProcessor` no `processBuilder`

```java
// DirectGameProcessFactory.java
Process process = new ProcessBuilder(full)
        .directory(builder.getDirectory())
        .redirectErrorStream(true)   // stderr → stdout
        .start();

// DirectGameProcess.java — thread de leitura segura
Thread reader = new Thread("mc-stdout-reader") {
    @Override
    public void run() {
        try (BufferedReader br = new BufferedReader(
                new InputStreamReader(process.getInputStream(), Charset.defaultCharset()), 65536)) {
            String line;
            while ((line = br.readLine()) != null) {       // termina no EOF, sem spin
                if (logProcessor != null)
                    logProcessor.onGameOutput(DirectGameProcess.this, line);
            }
        } catch (Exception ignored) {}
        // waitFor() garante que o processo terminou antes de notificar onGameProcessEnded
        try { process.waitFor(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        if (onExit != null) onExit.onGameProcessEnded(DirectGameProcess.this);
    }
};
reader.setDaemon(true);
reader.setPriority(Thread.MIN_PRIORITY);
reader.start();
```

---

## Comportamento final

| Evento | Antes | Depois |
|---|---|---|
| Jogo iniciando | Browser visível, competindo por CPU/I/O | Browser some automaticamente |
| Durante o jogo | Launcher consumindo recursos, jogo lagando | Launcher inativo, janela de logs aberta independente |
| Jogo fechando | Browser permanecia sem atualizar | Browser reaparece com foco |
| Stdout do Minecraft | Spin-loop a 100% CPU **ou** descartado | Thread daemon MIN_PRIORITY, batching 100ms |
| Botão "Parar" após crash | Travado (IllegalThreadStateException) | Funciona corretamente |
| Limpeza de arquivos | Imediata, concorrente com startup do jogo | 60s de delay, thread daemon |

---

## Arquivos modificados

| Arquivo | Mudança principal |
|---|---|
| `com/mojang/launcher/game/process/direct/DirectGameProcess.java` | Thread de leitura correta, `waitFor()` no `getExitCode()` |
| `com/mojang/launcher/game/process/direct/DirectGameProcessFactory.java` | `redirectErrorStream(true)` em vez de `DISCARD` |
| `net/minecraft/launcher/game/MinecraftGameRunner.java` | `performCleanupsAsync()`, `showGameOutputTab()` restaurado, `withLogProcessor()` |
| `net/minecraft/launcher/game/GameLaunchDispatcher.java` | Força `HIDE_LAUNCHER` |
| `net/minecraft/launcher/Launcher.java` | `performCleanupsAsync()` com delay de 60s |
| `net/minecraft/launcher/ui/tabs/GameOutputTab.java` | Batching via `ConcurrentLinkedQueue` + `Timer` |
| `net/minecraft/launcher/Macrosoft/MacrosoftModpackBrowser.java` | Hide/show do `parentFrame`, abertura automática de logs, `JDialog` sem dono |
