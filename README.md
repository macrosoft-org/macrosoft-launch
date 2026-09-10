# 🟣 Macrosoft Launcher

> Launcher de Minecraft customizado com foco em **segurança**, **privacidade** e suporte a **modpacks gerenciados**.
>
> Versão atual: **v11**

---

## Índice

- [Sobre](#sobre)
- [Funcionalidades](#funcionalidades)
- [Segurança](#segurança)
  - [Criptografia de credenciais](#1-criptografia-de-credenciais--aes-256-gcm)
  - [Autenticação Yggdrasil](#2-autenticação-yggdrasil--tokens-sem-senha-em-disco)
  - [Integridade dos arquivos](#3-integridade-dos-arquivos--sha-1)
  - [Permissões de arquivo](#4-permissões-de-arquivo--posix)
  - [HTTPS obrigatório](#5-https-obrigatório-em-todas-as-conexões)
- [Modpacks](#modpacks)
- [Gerenciamento de Java](#gerenciamento-de-java)
- [Build](#build)
- [Requisitos](#requisitos)
- [Estrutura do Projeto](#estrutura-do-projeto)
- [Licença](#licença)

---

## Sobre

O **Macrosoft Launcher** é um launcher de Minecraft construído sobre a base oficial da Mojang
(`com.mojang.launcher`), estendido com:

- Interface gráfica em Swing com tema escuro personalizado
- Suporte a múltiplos **modpacks gerenciados** via API
- Criptografia robusta de credenciais sensíveis armazenadas em disco

---

## Funcionalidades

| Recurso | Descrição |
|---|---|
| 🎮 **Multi-modpack** | Navega, baixa e lança diferentes modpacks a partir de uma API centralizada |
| 👤 **Múltiplos perfis** | Cada perfil tem versão, diretório, JVM e resolução independentes |
| 🔐 **Credenciais criptografadas** | Senhas e tokens protegidos com AES-256-GCM vinculado à máquina |
| 📋 **Console de logs** | Saída do jogo em tempo real por aba, com histórico |
| ⬇️ **Downloader com progresso** | Download de modpacks com barra de progresso e cancelamento |
| ☕ **Java customizável** | Caminho e argumentos JVM configuráveis por perfil |
| 🔎 **Detecção automática de Java** | Localiza, testa e configura um Java 8 funcional antes de liberar o jogo |
| 🩹 **Recuperação de inicialização** | Tenta outros Java 8 instalados e oferece o gerenciador quando nenhum funciona |
| 🖥️ **Multi-plataforma** | Linux, Windows e macOS |

---

## Segurança

Esta seção descreve as camadas de segurança implementadas. O objetivo é garantir que
**credenciais nunca fiquem expostas em texto plano** durante o uso do launcher.

---

### 1. Criptografia de Credenciais — AES-256-GCM

**Arquivo:** `src/.../utils/CryptoUtils.java`

Toda credencial sensível armazenada em disco (tokens de sessão) é
protegida com a stack criptográfica mais robusta disponível na JVM padrão:

| Parâmetro | Valor |
|---|---|
| Algoritmo | `AES/GCM/NoPadding` |
| Tamanho da chave | **256 bits** |
| Tag de autenticação (GCM) | **128 bits** |
| IV | 12 bytes — gerado com `SecureRandom` por operação |
| Salt | 16 bytes — gerado com `SecureRandom` por operação |
| Derivação de chave | `PBKDF2WithHmacSHA256` |
| Iterações PBKDF2 | **65.536** |

#### Por que AES-GCM?

O modo **GCM (Galois/Counter Mode)** é um modo de cifragem **autenticada** (AEAD).
Além de cifrar os dados, ele gera uma **tag de autenticação de 128 bits** que detecta
qualquer adulteração do ciphertext em disco. Se o arquivo for modificado por um processo
externo, a descriptografia **falha explicitamente** — nunca silenciosamente.

#### Chave vinculada à máquina

A chave AES é derivada de um **segredo aleatório gerado na primeira execução**, armazenado em:

```
~/.macrosoft/.launcher_secret                              (Linux/Windows)
~/Library/Application Support/macrosoft/.launcher_secret  (macOS)
```

Esse arquivo é criado com `SecureRandom` (UUID duplo = ~72 caracteres de entropia) e tem
**permissões restritas ao dono** (`chmod 600`) via `PosixFilePermissions` em sistemas Unix.

**Consequência prática:** mesmo que o arquivo de perfis (`launcher_profiles.json`) seja
copiado para outra máquina, as senhas armazenadas **serão ilegíveis** — a chave
está vinculada ao dispositivo original.

---

### 2. Autenticação Yggdrasil — Tokens sem senha em disco

**Arquivos:** `YggdrasilUserAuthentication.java`, `AuthenticationDatabase.java`

O launcher implementa o protocolo **Yggdrasil** da Mojang:

1. O usuário digita a senha **apenas uma vez** no campo de login
2. A Mojang retorna um `accessToken` + `clientToken` via HTTPS
3. Apenas os **tokens** são persistidos em disco — **a senha nunca é salva**
4. Nas sessões seguintes, o `accessToken` é usado para autenticação automática

```
Senha digitada ──► Mojang API (HTTPS) ──► accessToken + clientToken
                                                    │
                                         Salvo em launcher_profiles.json
                                         (apenas tokens, nunca a senha)
```

A assinatura digital do servidor de autenticação é verificada usando a chave pública
oficial da Mojang incluída no launcher (`yggdrasil_session_pubkey.der`).

---

### 3. Integridade dos Arquivos — SHA-1

**Arquivos:** `ChecksummedDownloadable.java`, `PreHashedDownloadable.java`

Todos os arquivos do jogo (JAR principal, bibliotecas, assets de textura e som) são
verificados após cada download:

- O manifesto de versão da Mojang contém o hash SHA-1 esperado de cada arquivo
- Após o download, o launcher **recalcula o SHA-1** e compara byte a byte
- Arquivos corrompidos ou adulterados são **rejeitados e rebaixados automaticamente**

Isso protege contra:
- Corrupção silenciosa durante o download
- Ataques de intermediário (MITM) que substituam arquivos por versões maliciosas
- Modificação acidental de arquivos locais do jogo

---

### 4. Permissões de Arquivo — POSIX

Em Linux e macOS, o segredo criptográfico é protegido por permissões de sistema:

```java
// CryptoUtils.java
Set<PosixFilePermission> perms = EnumSet.of(
    PosixFilePermission.OWNER_READ,
    PosixFilePermission.OWNER_WRITE   // chmod 600
);
Files.setPosixFilePermissions(secretFilePath, perms);
```

Outros usuários do mesmo sistema **não conseguem ler** o arquivo `.launcher_secret`,
impedindo que um processo ou usuário não-privilegiado derive a chave AES.

---

### 5. HTTPS Obrigatório em Todas as Conexões

Todas as URLs hardcoded no launcher usam HTTPS:

| Serviço | URL |
|---|---|
| Manifesto de versões | `https://launchermeta.mojang.com/mc/game/version_manifest.json` |
| Autenticação Yggdrasil | `https://authserver.mojang.com` |
| Status dos serviços Mojang | `https://status.mojang.com/check` |
| Download de bibliotecas | `https://libraries.minecraft.net/` |
| Download de assets | `https://resources.download.minecraft.net/` |
| API de modpacks Macrosoft | `https://www.macrosoft.website/launcher/info` |

---

## Modpacks

Os modpacks são carregados da API `https://www.macrosoft.website/launcher/info`.
Cada entrada contém nome, autor, ícone e URL de download.

O launcher instala cada modpack em um diretório **completamente isolado**:

```
.macrosoft/
├── modpack-a/
│   ├── versions/
│   ├── libraries/
│   ├── assets/
│   └── mods/
└── modpack-b/
    └── ...
```

Saves, configurações e versões de um modpack **nunca interferem** com os demais.

---

## Gerenciamento de Java

Cada modpack possui sua própria configuração de Java. Ao concluir a etapa
**Preparar**, o launcher procura instalações de Java 8 e executa `java -version` em
cada candidato. Caminhos inexistentes, executáveis que não respondem e versões
incompatíveis são descartados.

A ordem de preferência é:

1. Java válido já configurado no perfil
2. Runtimes instalados pelo Macrosoft em `.macrosoft/.java/`
3. Outras instalações de Java 8 encontradas no sistema

A procura inclui `PATH`, variáveis como `JAVA_HOME`, locais conhecidos do Linux e
macOS e o Registro do Windows. O botão **Detectar JAVA** continua disponível para
seleção manual.

O primeiro candidato funcional é salvo automaticamente no perfil. Runtimes
gerenciados usam um caminho portátil como:

```text
.macrosoft/.java/jre-8/jre1.8.0_202/bin/java
```

Se o Minecraft produzir uma mensagem reconhecida de incompatibilidade com Java
durante a inicialização, o launcher tenta o próximo candidato. Somente depois de
esgotar a lista ele oferece a tela do botão **Java**, onde o usuário pode baixar um
runtime isolado. Ao terminar a instalação, o novo caminho é aplicado ao perfil da
modpack automaticamente; a nova tentativa de jogo permanece sob controle do usuário.

A verificação confirma que o Java 8 é executável e acompanha falhas conhecidas do
carregamento. Para confirmar o CloudScript no ambiente real, o launcher acrescenta
`$${run(macrosoft_healthcheck)}$$` ao evento `onJoinGame` do Macro/Keybind. A macro
é executada automaticamente na entrada no mundo ou servidor, sem simular a tecla
Home. O launcher reconhece `[MacrosoftHealth] CLOUDSCRIPT_OK` no Game Output e
registra que o Java atual passou na verificação de compatibilidade. Resposta
negativa, falha ao iniciar a macro ou ausência do marcador por 45 segundos após o
pedido encerram a tentativa e fazem o launcher experimentar o próximo Java.

---

## Build

### Compilar

```bash
./gradlew shadowJar
```

O JAR executável (`mclaunch-all.jar`) será gerado em `build/libs/`.

### Executar

```bash
java -jar build/libs/mclaunch-all.jar
```

## Requisitos

| Componente | Versão mínima |
|---|---|
| Java do launcher | 8 (recomendado: 17 ou 21) |
| Java das modpacks atuais | 8 (detectado ou instalado pelo launcher) |
| Java (compilação) | 8+ (`source/target 1.8`) |
| Sistema Operacional | Linux, Windows 10+, macOS |
| RAM | 512 MB (launcher) + RAM do modpack |
| Disco | ~500 MB por modpack instalado |

---

## Estrutura do Projeto

```
src/main/java/
├── com/mojang/
│   ├── authlib/           # Protocolo Yggdrasil (autenticação Mojang)
│   └── launcher/          # Core: download, versões, processo do jogo
└── net/minecraft/
    ├── launcher/
    │   ├── Macrosoft/     # Browser de modpacks, downloader, bootstrapper
    │   ├── game/
    │   │   └── MinecraftGameRunner.java  # Orquestra o lançamento do jogo
    │   ├── profile/       # Perfis e banco de autenticação
    │   └── utils/
    │       └── CryptoUtils.java  # AES-256-GCM + PBKDF2WithHmacSHA256
    └── hopper/            # Relatório de crashes (Hopper Service)
```

---

## Licença

Este projeto é derivado do launcher oficial da Mojang.  
Consulte o arquivo [LICENSE](LICENSE) para os termos completos.

---

<p align="center">
  <sub>Macrosoft Launcher — Seguro por design, privado por configuração.</sub>
</p>
