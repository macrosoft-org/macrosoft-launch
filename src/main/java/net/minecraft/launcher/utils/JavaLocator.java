package net.minecraft.launcher.utils;

import com.mojang.launcher.OperatingSystem;
import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet; // Para ordenar e evitar duplicatas automaticamente
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class JavaLocator {

    // Padrão para capturar "1.8.0" ou "build 1.8.0"
    private static final Pattern JAVA_8_VERSION_PATTERN = Pattern.compile("(?:java|openjdk) version \"1\\.8\\.0_([^\"]+)\"|build 1\\.8\\.0_([^\"]+)");


    /**
     * Procura instalações do Java 8 no sistema. Usa heurísticas baseadas em user.dir para
     * tentar localizar o diretório gerenciado pelo Macrosoft. Prefira
     * {@link #findJava8Installations(Path)} quando o caminho real for conhecido.
     */
    public static List<String> findJava8Installations() {
        return findJava8Installations(null);
    }

    /**
     * Procura instalações do Java 8 no sistema.
     *
     * @param macrosoftBaseDir  o diretório ".macrosoft" real do launcher (ex: /opt/mclaunch/.macrosoft),
     *                          ou {@code null} para usar a heurística de user.dir. Quando fornecido,
     *                          os Javas instalados em {@code macrosoftBaseDir/.java/} são incluídos
     *                          independentemente de versão (pois foram explicitamente gerenciados pelo usuário).
     */
    public static List<String> findJava8Installations(Path macrosoftBaseDir) {
        Set<String> javaHomes = new TreeSet<>(); // Usar TreeSet para ordenação e evitar duplicatas

        // 1. Variáveis de Ambiente
        addPathIfValidJavaHome(System.getenv("JAVA_HOME"), javaHomes);
        addPathIfValidJavaHome(System.getenv("JDK_HOME"), javaHomes); // Comum para JDKs
        addPathIfValidJavaHome(System.getenv("JRE_HOME"), javaHomes); // Comum para JREs

        // 2. Caminhos Padrão de Instalação e Comandos Específicos do OS
        OperatingSystem os = OperatingSystem.getCurrentPlatform();
        switch (os) {
            case WINDOWS:
                findJavaOnWindows(javaHomes);
                break;
            case OSX:
                findJavaOnMac(javaHomes);
                break;
            case LINUX:
                findJavaOnLinux(javaHomes);
                break;
            default:
                break;
        }

        // 3. Verificar o Java atualmente em uso pelo launcher
        addPathIfValidJavaHome(System.getProperty("java.home"), javaHomes);

        // 4. Javas gerenciados pelo Macrosoft (qualquer versão — foram instalados explicitamente pelo usuário)
        //    Coletados separadamente para não passarem pelo filtro isJava8.
        Set<String> macrosoftManagedHomes = new LinkedHashSet<>();
        if (macrosoftBaseDir != null) {
            // Caminho explícito e confiável: usar diretamente
            discoverMacrosoftManagedJavasFromDir(macrosoftBaseDir.resolve(".java"), macrosoftManagedHomes);
        } else {
            // Fallback: heurísticas baseadas em user.dir (menos confiável)
            discoverMacrosoftManagedJavas(macrosoftManagedHomes);
        }

        // Filtrar e retornar apenas os que são Java 8 válidos e contêm o executável
        List<String> validExecutables = new ArrayList<>();
        for (String homePath : javaHomes) {
            String executablePath = getExecutableFromJavaHome(homePath, os);
            if (executablePath != null && Files.exists(Paths.get(executablePath)) && isFunctionalJava8(executablePath)) {
                validExecutables.add(executablePath);
            }
        }
        // Javas do Macrosoft também precisam responder e ser Java 8. Isso impede
        // selecionar automaticamente uma instalação incompleta ou incompatível.
        for (String homePath : macrosoftManagedHomes) {
            String executablePath = getExecutableFromJavaHome(homePath, os);
            if (executablePath != null && Files.exists(Paths.get(executablePath))
                    && isFunctionalJava8(executablePath)) {
                validExecutables.add(executablePath);
            }
        }
        // Remover duplicatas que possam ter surgido de caminhos diferentes para o mesmo executável
        return validExecutables.stream().distinct().collect(Collectors.toList());
    }

    private static String getExecutableFromJavaHome(String javaHome, OperatingSystem os) {
        if (javaHome == null || javaHome.isEmpty()) {
            return null;
        }
        Path binDir = Paths.get(javaHome, "bin");
        Path executable;
        if (os == OperatingSystem.WINDOWS) {
            executable = binDir.resolve("javaw.exe");
            if (!Files.exists(executable)) {
                executable = binDir.resolve("java.exe");
            }
        } else {
            executable = binDir.resolve("java");
        }
        return Files.isExecutable(executable) ? executable.toAbsolutePath().toString() : null;
    }

    private static void addPathIfValidJavaHome(String javaHomePath, Set<String> javaHomes) {
        if (javaHomePath != null && !javaHomePath.isEmpty()) {
            Path path = Paths.get(javaHomePath);
            if (Files.isDirectory(path)) {
                // A validação da versão e do executável será feita depois
                javaHomes.add(path.toAbsolutePath().toString());
            }
        }
    }

    private static void findJavaOnWindows(Set<String> javaHomes) {
        String[] commonRoots = {
            System.getenv("ProgramFiles"),
            System.getenv("ProgramFiles(x86)")
        };
        String[] jdkJreNames = {"Java", "AdoptOpenJDK", "OpenJDK", "Eclipse Adoptium", "BellSoft", "Amazon Corretto", "Microsoft", "Semeru"}; // Adicionar mais conforme necessário

        for (String root : commonRoots) {
            if (root != null) {
                for (String vendorName : jdkJreNames) {
                    Path vendorDir = Paths.get(root, vendorName);
                    searchForJavaSubdirectories(vendorDir, javaHomes);
                }
            }
        }
        // Tentar via comando 'where java' (pode ser menos confiável para achar o home)
        findJavaExecutablesUsingCommand("where java.exe", javaHomes, OperatingSystem.WINDOWS);
        findJavaExecutablesUsingCommand("where javaw.exe", javaHomes, OperatingSystem.WINDOWS);
    }

    private static void findJavaOnMac(Set<String> javaHomes) {
        String[] locations = {
            "/Library/Java/JavaVirtualMachines",
            System.getProperty("user.home") + "/Library/Java/JavaVirtualMachines"
        };
        for (String loc : locations) {
            Path locationPath = Paths.get(loc);
            searchForJavaSubdirectories(locationPath, javaHomes); // Procura por .jdk ou .jre
        }

        // Usar /usr/libexec/java_home (método mais confiável no macOS)
        try {
            Process process = new ProcessBuilder("/usr/libexec/java_home", "-X").start(); // -X lista em formato XML
            // Uma alternativa mais simples é `java_home -V` e parsear, mas -X é mais robusto se parseado corretamente.
            // Por simplicidade, vamos usar -V e um regex mais simples
            process = new ProcessBuilder("/usr/libexec/java_home", "-V").start();
            BufferedReader reader = new BufferedReader(new InputStreamReader(process.getErrorStream())); // java_home -V imprime para stderr
            String line;
            // Exemplo de saída:
            // Matching Java Virtual Machines (2):
            //    1.8.0_292 (x86_64) "Oracle Corporation" - "Java SE 8" /Library/Java/JavaVirtualMachines/jdk1.8.0_292.jdk/Contents/Home
            //    11.0.1 (x86_64) "AdoptOpenJDK" - "OpenJDK 11.0.1" /Library/Java/JavaVirtualMachines/adoptopenjdk-11.jdk/Contents/Home
            Pattern macPattern = Pattern.compile("^\\s*1\\.8[\\w.\\-]*\\s+.*\"(/.*)\"$");
            while ((line = reader.readLine()) != null) {
                Matcher matcher = macPattern.matcher(line);
                if (matcher.find()) {
                    addPathIfValidJavaHome(matcher.group(1), javaHomes); // group(1) é o caminho
                }
            }
            process.waitFor();
        } catch (IOException | InterruptedException e) {
            System.err.println("Erro ao tentar usar /usr/libexec/java_home: " + e.getMessage());
        }
    }

    private static void findJavaOnLinux(Set<String> javaHomes) {
        String[] locations = {
            "/usr/lib/jvm",
            "/usr/java",
            "/opt" // Muitas vezes o diretório pai de SDKs
        };
        for (String loc : locations) {
            Path locationPath = Paths.get(loc);
            searchForJavaSubdirectories(locationPath, javaHomes);
        }
        // Tentar via 'which java' e resolver links simbólicos
        findJavaExecutablesUsingCommand("which java", javaHomes, OperatingSystem.LINUX);
    }

    private static void discoverMacrosoftManagedJavas(Set<String> javaHomes) {
        for (Path macrosoftJavaDir : resolveMacrosoftJavaDirs()) {
            discoverMacrosoftManagedJavasFromDir(macrosoftJavaDir, javaHomes);
        }
    }

    /**
     * Varre {@code macrosoftJavaDir} (o diretório ".macrosoft/.java" real) e adiciona
     * os java-homes encontrados ao conjunto {@code javaHomes}.
     */
    private static void discoverMacrosoftManagedJavasFromDir(Path macrosoftJavaDir, Set<String> javaHomes) {
        if (!Files.isDirectory(macrosoftJavaDir)) {
            return;
        }
        try (Stream<Path> installDirs = Files.list(macrosoftJavaDir)) {
            installDirs.filter(Files::isDirectory).forEach(installDir -> {
                // Ignorar diretórios temporários criados pelo JavaRuntimeManager
                String dirName = installDir.getFileName().toString();
                if (dirName.startsWith(".")) {
                    return;
                }
                try (Stream<Path> walk = Files.walk(installDir, 8)) {
                    walk.filter(Files::isRegularFile)
                        .filter(path -> {
                            String name = path.getFileName().toString().toLowerCase();
                            return name.equals("java") || name.equals("javaw.exe") || name.equals("java.exe");
                        })
                        .findFirst()
                        .ifPresent(javaExe -> {
                            Path bin = javaExe.getParent();
                            if (bin != null && "bin".equalsIgnoreCase(bin.getFileName().toString())) {
                                Path home = bin.getParent();
                                if (home != null) {
                                    addPathIfValidJavaHome(home.toString(), javaHomes);
                                }
                            }
                        });
                } catch (IOException ignored) {
                }
            });
        } catch (IOException ignored) {
        }
    }

    /**
     * Resolve possíveis caminhos para ".macrosoft/.java" sem depender de um único cwd:
     * - <user.dir>/.macrosoft/.java
     * - <user.dir>/.java (caso user.dir já seja ".macrosoft")
     * - sobe a árvore procurando um diretório chamado ".macrosoft" e usa "<found>/.java"
     */
    private static Set<Path> resolveMacrosoftJavaDirs() {
        Set<Path> candidates = new LinkedHashSet<>();
        Path cwd = Paths.get(System.getProperty("user.dir", ".")).toAbsolutePath().normalize();

        candidates.add(cwd.resolve(".macrosoft").resolve(".java"));
        candidates.add(cwd.resolve(".java"));

        Path cursor = cwd;
        for (int i = 0; i < 8 && cursor != null; i++) {
            Path name = cursor.getFileName();
            if (name != null && ".macrosoft".equalsIgnoreCase(name.toString())) {
                candidates.add(cursor.resolve(".java"));
            }
            cursor = cursor.getParent();
        }
        return candidates;
    }

    private static void searchForJavaSubdirectories(Path directory, Set<String> javaHomes) {
        if (directory != null && Files.isDirectory(directory)) {
            try (Stream<Path> stream = Files.list(directory)) {
                stream.filter(Files::isDirectory)
                      .forEach(subDir -> {
                          String name = subDir.getFileName().toString().toLowerCase();
                          // Condições para ser um diretório Java (JDK ou JRE)
                          if (name.contains("jdk") || name.contains("jre") || name.startsWith("java-") || name.contains("adopt") || name.contains("openjdk") || name.contains("corretto") || name.contains("liberica") || name.contains("temurin") || name.contains("semeru") || name.contains("graalvm")) {
                              if (OperatingSystem.getCurrentPlatform() == OperatingSystem.OSX && name.endsWith(".jdk")) {
                                  Path contentsHome = subDir.resolve("Contents/Home");
                                  if (Files.isDirectory(contentsHome)) {
                                      addPathIfValidJavaHome(contentsHome.toString(), javaHomes);
                                  }
                              } else {
                                  addPathIfValidJavaHome(subDir.toString(), javaHomes);
                              }
                          } else if (Files.exists(subDir.resolve("bin").resolve(OperatingSystem.getCurrentPlatform() == OperatingSystem.WINDOWS ? "javaw.exe" : "java"))) {
                            // Verificação genérica se tem um bin/java ou bin/javaw.exe
                            addPathIfValidJavaHome(subDir.toString(), javaHomes);
                          }
                      });
            } catch (IOException e) {
                System.err.println("Erro ao listar diretórios em " + directory + ": " + e.getMessage());
            }
        }
    }

    private static void findJavaExecutablesUsingCommand(String command, Set<String> javaHomes, OperatingSystem os) {
        try {
            Process process = Runtime.getRuntime().exec(command);
            BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
            String line;
            while ((line = reader.readLine()) != null) {
                Path executablePath = Paths.get(line.trim());
                if (Files.isExecutable(executablePath)) {
                    try {
                        // Tenta resolver para o caminho real e depois obter o diretório pai do 'bin'
                        Path realExecutablePath = executablePath.toRealPath();
                        Path binDir = realExecutablePath.getParent();
                        if (binDir != null && binDir.getFileName().toString().equalsIgnoreCase("bin")) {
                            Path homeDir = binDir.getParent();
                            if (homeDir != null) {
                                addPathIfValidJavaHome(homeDir.toString(), javaHomes);
                            }
                        }
                    } catch (IOException e) {
                         System.err.println("Erro ao tentar resolver realpath para " + executablePath + ": " + e.getMessage());
                    }
                }
            }
            process.waitFor();
        } catch (IOException | InterruptedException e) {
            System.err.println("Erro ao executar comando '" + command + "': " + e.getMessage());
        }
    }


    public static boolean isFunctionalJava8(String executablePath) {
        if (executablePath == null || executablePath.isEmpty()) {
            return false;
        }
        try {
            Path executable = Paths.get(executablePath);
            if (!Files.isRegularFile(executable) || !Files.isExecutable(executable)) {
                return false;
            }
            ProcessBuilder builder = new ProcessBuilder(executablePath, "-version");
            // "-version" frequentemente imprime para stderr
            builder.redirectErrorStream(true);
            Process process = builder.start();

            // Esperar antes de ler impede que um executável travado mantenha readLine()
            // bloqueado indefinidamente. `java -version` produz apenas poucas linhas.
            if (!process.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)) {
                process.destroyForcibly();
                System.err.println("Comando '-version' para " + executablePath + " demorou demais.");
                return false;
            }

            if (process.exitValue() == 0) {
                StringBuilder output = new StringBuilder();
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        output.append(line).append(System.lineSeparator());
                    }
                }
                String versionOutput = output.toString();
                //System.out.println("Saída de " + executablePath + " -version:\n" + versionOutput);
                Matcher matcher = JAVA_8_VERSION_PATTERN.matcher(versionOutput);
                return matcher.find();
            } else {
                //System.err.println("Falha ao executar " + executablePath + " -version, código de saída: " + process.exitValue() + "\nSaída: " + output);
            }
        } catch (IOException | InterruptedException e) {
            System.err.println("Erro ao verificar a versão do Java em " + executablePath + ": " + e.getMessage());
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt(); // Restaurar o status de interrupção
            }
        }
        return false;
    }
}
