package net.minecraft.launcher.game;

import com.google.common.base.Objects;
import com.google.common.base.Predicate;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.mojang.authlib.GameProfile;
import com.mojang.authlib.UserAuthentication;
import com.mojang.authlib.UserType;
import com.mojang.authlib.properties.PropertyMap;
import com.mojang.authlib.yggdrasil.YggdrasilUserAuthentication;
import com.mojang.launcher.LegacyPropertyMapSerializer;
import com.mojang.launcher.OperatingSystem;
import com.mojang.launcher.events.GameOutputLogProcessor;
import com.mojang.launcher.game.GameInstanceStatus;
import com.mojang.launcher.game.process.GameProcess;
import com.mojang.launcher.game.process.GameProcessBuilder;
import com.mojang.launcher.game.process.GameProcessFactory;
import com.mojang.launcher.game.process.GameProcessRunnable;
import com.mojang.launcher.game.process.direct.DirectGameProcessFactory;
import com.mojang.launcher.game.runner.AbstractGameRunner;
import com.mojang.launcher.updater.DateTypeAdapter;
import com.mojang.launcher.updater.VersionSyncInfo;
import com.mojang.launcher.updater.download.Downloadable;
import com.mojang.launcher.updater.download.assets.AssetIndex;
import com.mojang.launcher.versions.CompleteVersion;
import com.mojang.launcher.versions.ExtractRules;
import com.mojang.launcher.versions.ReleaseType;
import com.mojang.util.UUIDTypeAdapter;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.Reader;
import java.lang.reflect.Type;
import java.util.Collection;
import java.util.Collection;
import java.util.Date;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.util.Set;
import java.util.Timer;
import java.util.TimerTask;
import java.util.TreeSet;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import net.minecraft.launcher.CompatibilityRule;
import net.minecraft.launcher.CurrentLaunchFeatureMatcher;
import net.minecraft.launcher.Launcher;
import net.minecraft.launcher.LauncherConstants;
import net.minecraft.launcher.MinecraftUserInterface;
import net.minecraft.launcher.profile.AuthenticationDatabase;
import net.minecraft.launcher.profile.LauncherVisibilityRule;
import net.minecraft.launcher.profile.Profile;
import net.minecraft.launcher.profile.ProfileManager;
import net.minecraft.launcher.updater.ArgumentType;
import net.minecraft.launcher.updater.AssetIndexInfo;
import net.minecraft.launcher.updater.CompleteMinecraftVersion;
import net.minecraft.launcher.updater.Library;
import org.apache.commons.io.Charsets;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.filefilter.FileFilterUtils;
import org.apache.commons.io.filefilter.IOFileFilter;
import org.apache.commons.io.filefilter.TrueFileFilter;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.text.StrSubstitutor;
import org.apache.logging.log4j.Logger;

public class MinecraftGameRunner
extends AbstractGameRunner
implements GameProcessRunnable {
    private static final String CRASH_IDENTIFIER_MAGIC = "#@!@#";
    private static final String CLOUDSCRIPT_HEALTHCHECK_MACRO =
        "$${run(macrosoft_healthcheck)}$$";
    private static final String CLOUDSCRIPT_HEALTHCHECK_OK =
        "[MacrosoftHealth] CLOUDSCRIPT_OK";
    private static final long CLOUDSCRIPT_HEALTHCHECK_TIMEOUT_MS = 45_000L;
    private final Gson gson = new Gson();
    private final DateTypeAdapter dateAdapter = new DateTypeAdapter();
    private final Launcher minecraftLauncher;
    private final String[] additionalLaunchArgs;
    private final GameProcessFactory processFactory = new DirectGameProcessFactory();
    private GameProcess currentProcess;
    private File nativeDir;
    private LauncherVisibilityRule visibilityRule = LauncherVisibilityRule.CLOSE_LAUNCHER;
    private UserAuthentication auth;
    private Profile selectedProfile;
    private final GameLaunchDispatcher.JavaProblemListener javaProblemListener;
    private volatile String detectedJavaProblem;
    private volatile boolean cloudScriptHealthCheckPassed;
    private volatile boolean cloudScriptHealthCheckFailed;
    private Timer cloudScriptHealthCheckTimer;
    /** Timestamp (ms) do momento em que o processo do jogo foi iniciado — usado para localizar crash reports. */
    private long gameStartTime = 0;

    public MinecraftGameRunner(Launcher minecraftLauncher, String[] additionalLaunchArgs,
                               GameLaunchDispatcher.JavaProblemListener javaProblemListener) {
        this.minecraftLauncher = minecraftLauncher;
        this.additionalLaunchArgs = additionalLaunchArgs;
        this.javaProblemListener = javaProblemListener;
    }

    /*
     * WARNING - Removed try catching itself - possible behaviour change.
     */
    @Override
    protected void setStatus(GameInstanceStatus status) {
        Object object = this.lock;
        synchronized (object) {
            if (this.nativeDir != null && status == GameInstanceStatus.IDLE) {
                LOGGER.info("Deleting " + this.nativeDir);
                if (!this.nativeDir.isDirectory() || FileUtils.deleteQuietly(this.nativeDir)) {
                    this.nativeDir = null;
                } else {
                    LOGGER.warn("Couldn't delete " + this.nativeDir + " - scheduling for deletion upon exit");
                    try {
                        FileUtils.forceDeleteOnExit(this.nativeDir);
                    }
                    catch (Throwable throwable) {
                        // empty catch block
                    }
                }
            }
            super.setStatus(status);
        }
    }

    @Override
    protected com.mojang.launcher.Launcher getLauncher() {
        return this.minecraftLauncher.getLauncher();
    }

    @Override
    protected void downloadRequiredFiles(VersionSyncInfo syncInfo) {
        this.migrateOldAssets();
        super.downloadRequiredFiles(syncInfo);
    }

    @Override
    protected void launchGame() throws IOException {
        File serverResourcePacksDir;
        File assetsDir;
        LOGGER.info("Launching game");
        this.selectedProfile = this.minecraftLauncher.getProfileManager().getSelectedProfile();
        this.auth = this.minecraftLauncher.getProfileManager().getAuthDatabase().getByUUID(this.minecraftLauncher.getProfileManager().getSelectedUser());
        if (this.getVersion() == null) {
            LOGGER.error("Aborting launch; version is null?");
            return;
        }
        this.nativeDir = new File(this.getLauncher().getWorkingDirectory(), "versions/" + this.getVersion().getId() + "/" + this.getVersion().getId() + "-natives-" + System.nanoTime());
        if (!this.nativeDir.isDirectory()) {
            this.nativeDir.mkdirs();
        }
        LOGGER.info("Unpacking natives to " + this.nativeDir);
        try {
            this.unpackNatives(this.nativeDir);
        }
        catch (IOException e) {
            LOGGER.error("Couldn't unpack natives!", (Throwable)e);
            return;
        }
        try {
            assetsDir = this.reconstructAssets();
        }
        catch (IOException e) {
            LOGGER.error("Couldn't unpack natives!", (Throwable)e);
            return;
        }
        File gameDirectory = this.selectedProfile.getGameDir() == null ? this.getLauncher().getWorkingDirectory() : this.selectedProfile.getGameDir();
        LOGGER.info("Launching in " + gameDirectory);
        if (!gameDirectory.exists()) {
            if (!gameDirectory.mkdirs()) {
                LOGGER.error("Aborting launch; couldn't create game directory");
                return;
            }
        } else if (!gameDirectory.isDirectory()) {
            LOGGER.error("Aborting launch; game directory is not actually a directory");
            return;
        }
        if (!(serverResourcePacksDir = new File(gameDirectory, "server-resource-packs")).exists()) {
            serverResourcePacksDir.mkdirs();
        }
        installCloudScriptHealthCheckTrigger(gameDirectory);
        // Normalizar separadores de caminho (perfis criados no Windows podem ter '\')
        String rawJavaPath = Objects.firstNonNull(
                this.selectedProfile.getJavaPath(),
                OperatingSystem.getCurrentPlatform().getJavaDir());
        // Resolver caminhos relativos ao launcher (ex: .macrosoft/.java/...)
        // workingDirectory = .macrosoft/<context>  →  parent = .macrosoft  →  parent.parent = diretório que *contém* .macrosoft
        if (rawJavaPath.startsWith(".macrosoft") || rawJavaPath.startsWith(".macrosoft/") || rawJavaPath.startsWith(".macrosoft\\")) {
            java.nio.file.Path base = this.getLauncher().getWorkingDirectory().toPath().getParent().getParent().toAbsolutePath().normalize();
            rawJavaPath = base.resolve(rawJavaPath).normalize().toString();
        }
        String javaExecutable = rawJavaPath.replace('\\', File.separatorChar);
        LOGGER.info("Java executable (normalized): " + javaExecutable);

        final GameProcessBuilder processBuilder = new GameProcessBuilder(javaExecutable);

        processBuilder.withSysOutFilter(new Predicate<String>(){

            @Override
            public boolean apply(String input) {
                return input.contains(MinecraftGameRunner.CRASH_IDENTIFIER_MAGIC);
            }
        });
        processBuilder.directory(gameDirectory);
        // Cria a aba "Jogo" e registra o logProcessor para receber as linhas do stdout
        final GameOutputLogProcessor logProcessor = ((net.minecraft.launcher.MinecraftUserInterface) this.getLauncher().getUserInterface()).showGameOutputTab(this);
        processBuilder.withLogProcessor(new GameOutputLogProcessor() {
            @Override
            public void onGameOutput(GameProcess process, String logLine) {
                detectJavaProblem(logLine);
                detectCloudScriptHealthCheck(logLine);
                if (logProcessor != null) {
                    logProcessor.onGameOutput(process, logLine);
                }
            }
        });
        String profileArgs = this.selectedProfile.getJavaArgs();
        if (profileArgs != null) {
            processBuilder.withArguments(profileArgs.split(" "));
        } else {
            boolean is32Bit = "32".equals(System.getProperty("sun.arch.data.model"));
            String defaultArgument = is32Bit ? "-Xmx512M -XX:+UseConcMarkSweepGC -XX:+CMSIncrementalMode -XX:-UseAdaptiveSizePolicy -Xmn128M" : "-Xmx1G -XX:+UseConcMarkSweepGC -XX:+CMSIncrementalMode -XX:-UseAdaptiveSizePolicy -Xmn128M";
            processBuilder.withArguments(defaultArgument.split(" "));
        }
        CompatibilityRule.FeatureMatcher featureMatcher = this.createFeatureMatcher();
        StrSubstitutor argumentsSubstitutor = this.createArgumentsSubstitutor(this.getVersion(), this.selectedProfile, gameDirectory, assetsDir, this.auth);
        this.getVersion().addArguments(ArgumentType.JVM, featureMatcher, processBuilder, argumentsSubstitutor);


        processBuilder.withArguments(this.getVersion().getMainClass());
        LOGGER.info("Half command: " + StringUtils.join(processBuilder.getFullCommands(), " "));
        this.getVersion().addArguments(ArgumentType.GAME, featureMatcher, processBuilder, argumentsSubstitutor);

        processBuilder.withArguments(this.additionalLaunchArgs);
        try {
            LOGGER.debug("Running " + StringUtils.join(processBuilder.getFullCommands(), " "));
            this.gameStartTime = System.currentTimeMillis();
            this.currentProcess = this.processFactory.startGame(processBuilder);
            this.currentProcess.setExitRunnable(this);
            this.setStatus(GameInstanceStatus.PLAYING);
            if (this.visibilityRule != LauncherVisibilityRule.DO_NOTHING) {
                this.minecraftLauncher.getUserInterface().setVisible(false);
            }
        }
        catch (IOException e) {
            LOGGER.error("Couldn't launch game", (Throwable)e);
            this.setStatus(GameInstanceStatus.IDLE);
            if (this.javaProblemListener != null) {
                String detail = "Não foi possível executar o Java configurado:\n"
                    + javaExecutable + "\n\n" + e.getMessage();
                this.javaProblemListener.onJavaProblemDetected(detail);
            }
            return;
        }
        this.minecraftLauncher.performCleanupsAsync();
    }

    protected CompleteMinecraftVersion getVersion() {
        return (CompleteMinecraftVersion)this.version;
    }


    private AssetIndex getAssetIndex() throws IOException {
        String assetVersion = this.getVersion().getAssetIndex().getId();
        File indexFile = new File(new File(this.getAssetsDir(), "indexes"), assetVersion + ".json");
        return this.gson.fromJson(FileUtils.readFileToString(indexFile, Charsets.UTF_8), AssetIndex.class);
    }

    private File getAssetsDir() {
        return new File(this.getLauncher().getWorkingDirectory(), "assets");
    }

    private File reconstructAssets() throws IOException {
        File assetsDir = this.getAssetsDir();
        File indexDir = new File(assetsDir, "indexes");
        File objectDir = new File(assetsDir, "objects");
        String assetVersion = this.getVersion().getAssetIndex().getId();
        File indexFile = new File(indexDir, assetVersion + ".json");
        File virtualRoot = new File(new File(assetsDir, "virtual"), assetVersion);
        if (!indexFile.isFile()) {
            LOGGER.warn("No assets index file " + virtualRoot + "; can't reconstruct assets");
            return virtualRoot;
        }
        AssetIndex index = this.gson.fromJson(FileUtils.readFileToString(indexFile, Charsets.UTF_8), AssetIndex.class);
        if (index.isVirtual()) {
            LOGGER.info("Reconstructing virtual assets folder at " + virtualRoot);
            for (Map.Entry<String, AssetIndex.AssetObject> entry : index.getFileMap().entrySet()) {
                File target = new File(virtualRoot, entry.getKey());
                File original = new File(new File(objectDir, entry.getValue().getHash().substring(0, 2)), entry.getValue().getHash());
                if (target.isFile()) continue;
                FileUtils.copyFile(original, target, false);
            }
            FileUtils.writeStringToFile(new File(virtualRoot, ".lastused"), this.dateAdapter.serializeToString(new Date()));
        }
        return virtualRoot;
    }

    public StrSubstitutor createArgumentsSubstitutor(CompleteMinecraftVersion version, Profile selectedProfile, File gameDirectory, File assetsDirectory, UserAuthentication authentication) {
        HashMap<String, String> map = new HashMap<String, String>();
        String authToken = authentication.getAuthenticatedToken();
        map.put("auth_access_token", authToken != null ? authToken : "0");
        map.put("user_properties", new GsonBuilder().registerTypeAdapter((Type)((Object)PropertyMap.class), new LegacyPropertyMapSerializer()).create().toJson(authentication.getUserProperties()));
        map.put("user_property_map", new GsonBuilder().registerTypeAdapter((Type)((Object)PropertyMap.class), new PropertyMap.Serializer()).create().toJson(authentication.getUserProperties()));
        if (authentication.isLoggedIn() && authentication.canPlayOnline()) {
            if (authentication instanceof YggdrasilUserAuthentication) {
                String tok = authentication.getAuthenticatedToken();
                map.put("auth_session", String.format("token:%s:%s",
                        tok != null ? tok : "0",
                        UUIDTypeAdapter.fromUUID(authentication.getSelectedProfile().getId())));
            } else {
                String tok = authentication.getAuthenticatedToken();
                map.put("auth_session", tok != null ? tok : "0");
            }
        } else {
            map.put("auth_session", "-");
        }
        if (authentication.getSelectedProfile() != null) {
            map.put("auth_player_name", authentication.getSelectedProfile().getName());
            map.put("auth_uuid", UUIDTypeAdapter.fromUUID(authentication.getSelectedProfile().getId()));
            map.put("user_type", authentication.getUserType().getName());
        } else {
            map.put("auth_player_name", "Player");
            map.put("auth_uuid", new UUID(0L, 0L).toString());
            map.put("user_type", UserType.LEGACY.getName());
        }

        // ── Nickname por perfil: sobrescreve auth_player_name (e UUID derivado) ──────────
        // Prioridade: nickname do perfil > nome do GameProfile do login global
        String profileNick = selectedProfile.getNickname();
        if (profileNick != null && !profileNick.trim().isEmpty()) {
            map.put("auth_player_name", profileNick.trim());
            // UUID offline determinístico — mesma fórmula usada pelo Minecraft em modo offline:
            // UUID.nameUUIDFromBytes("OfflinePlayer:<nick>")
            try {
                UUID offlineUUID = UUID.nameUUIDFromBytes(
                        ("OfflinePlayer:" + profileNick.trim())
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8));
                map.put("auth_uuid", UUIDTypeAdapter.fromUUID(offlineUUID));
            } catch (Exception e) {
                // ignora — mantém o UUID do GameProfile já colocado
            }
            LOGGER.info("Usando nickname de perfil: '" + profileNick.trim() + "'");
        }
        map.put("profile_name", selectedProfile.getName());
        map.put("version_name", version.getId());
        map.put("game_directory", gameDirectory.getAbsolutePath());
        map.put("game_assets", assetsDirectory.getAbsolutePath());
        map.put("assets_root", this.getAssetsDir().getAbsolutePath());
        map.put("assets_index_name", this.getVersion().getAssetIndex().getId());
        map.put("version_type", this.getVersion().getType().getName());
        if (selectedProfile.getResolution() != null) {
            map.put("resolution_width", String.valueOf(selectedProfile.getResolution().getWidth()));
            map.put("resolution_height", String.valueOf(selectedProfile.getResolution().getHeight()));
        } else {
            map.put("resolution_width", "");
            map.put("resolution_height", "");
        }
        map.put("language", "en-us");
        try {
            AssetIndex assetIndex = this.getAssetIndex();
            for (Map.Entry<String, AssetIndex.AssetObject> entry : assetIndex.getFileMap().entrySet()) {
                String hash = entry.getValue().getHash();
                String path = new File(new File(this.getAssetsDir(), "objects"), hash.substring(0, 2) + "/" + hash).getAbsolutePath();
                map.put("asset=" + entry.getKey(), path);
            }
        }
        catch (IOException assetIndex) {
            // empty catch block
        }
        map.put("launcher_name", "java-minecraft-launcher");
        map.put("launcher_version", LauncherConstants.getVersionName());
        map.put("natives_directory", this.nativeDir.getAbsolutePath());
        map.put("classpath", this.constructClassPath(this.getVersion()));
        map.put("classpath_separator", System.getProperty("path.separator"));
        map.put("primary_jar", new File(this.getLauncher().getWorkingDirectory(), "versions/" + this.getVersion().getJar() + "/" + this.getVersion().getJar() + ".jar").getAbsolutePath());
        return new StrSubstitutor(map);
    }

    private void migrateOldAssets() {
        File sourceDir = this.getAssetsDir();
        File objectsDir = new File(sourceDir, "objects");
        if (!sourceDir.isDirectory()) {
            return;
        }
        IOFileFilter migratableFilter = FileFilterUtils.notFileFilter(FileFilterUtils.or(FileFilterUtils.nameFileFilter("indexes"), FileFilterUtils.nameFileFilter("objects"), FileFilterUtils.nameFileFilter("virtual"), FileFilterUtils.nameFileFilter("skins")));
        for (File file : new TreeSet<File>(FileUtils.listFiles(sourceDir, TrueFileFilter.TRUE, migratableFilter))) {
            String hash = Downloadable.getDigest(file, "SHA-1", 40);
            File destinationFile = new File(objectsDir, hash.substring(0, 2) + "/" + hash);
            if (!destinationFile.exists()) {
                LOGGER.info("Migrated old asset {} into {}", file, destinationFile);
                try {
                    FileUtils.copyFile(file, destinationFile);
                }
                catch (IOException e) {
                    LOGGER.error("Couldn't migrate old asset", (Throwable)e);
                }
            }
            FileUtils.deleteQuietly(file);
        }
        File[] assets = sourceDir.listFiles();
        if (assets != null) {
            for (File file : assets) {
                if (file.getName().equals("indexes") || file.getName().equals("objects") || file.getName().equals("virtual") || file.getName().equals("skins")) continue;
                LOGGER.info("Cleaning up old assets directory {} after migration", file);
                FileUtils.deleteQuietly(file);
            }
        }
    }

    /*
     * WARNING - Removed try catching itself - possible behaviour change.
     */
    private void unpackNatives(File targetDir) throws IOException {
        OperatingSystem os = OperatingSystem.getCurrentPlatform();
        Collection<Library> libraries = this.getVersion().getRelevantLibraries(this.createFeatureMatcher());
        for (Library library : libraries) {
            Map<OperatingSystem, String> nativesPerOs = library.getNatives();
            if (nativesPerOs == null || nativesPerOs.get((Object)os) == null) continue;
            File file = new File(this.getLauncher().getWorkingDirectory(), "libraries/" + library.getArtifactPath(nativesPerOs.get((Object)os)));
            ZipFile zip = new ZipFile(file);
            ExtractRules extractRules = library.getExtractRules();
            try {
                Enumeration<? extends ZipEntry> entries = zip.entries();
                while (entries.hasMoreElements()) {
                    ZipEntry entry = entries.nextElement();
                    if (extractRules != null && !extractRules.shouldExtract(entry.getName())) continue;
                    File targetFile = new File(targetDir, entry.getName());
                    if (targetFile.getParentFile() != null) {
                        targetFile.getParentFile().mkdirs();
                    }
                    if (entry.isDirectory()) continue;
                    BufferedInputStream inputStream = new BufferedInputStream(zip.getInputStream(entry));
                    byte[] buffer = new byte[2048];
                    FileOutputStream outputStream = new FileOutputStream(targetFile);
                    BufferedOutputStream bufferedOutputStream = new BufferedOutputStream(outputStream);
                    try {
                        int length;
                        while ((length = inputStream.read(buffer, 0, buffer.length)) != -1) {
                            bufferedOutputStream.write(buffer, 0, length);
                        }
                    }
                    finally {
                        Downloadable.closeSilently(bufferedOutputStream);
                        Downloadable.closeSilently(outputStream);
                        Downloadable.closeSilently(inputStream);
                    }
                }
            }
            finally {
                zip.close();
            }
        }
    }

    private CompatibilityRule.FeatureMatcher createFeatureMatcher() {
        return new CurrentLaunchFeatureMatcher(this.selectedProfile, this.getVersion(), this.minecraftLauncher.getProfileManager().getAuthDatabase().getByUUID(this.minecraftLauncher.getProfileManager().getSelectedUser()));
    }

    private String constructClassPath(CompleteMinecraftVersion version) {
        StringBuilder result = new StringBuilder();
        Collection<File> classPath = version.getClassPath(OperatingSystem.getCurrentPlatform(), this.getLauncher().getWorkingDirectory(), this.createFeatureMatcher());
        String separator = System.getProperty("path.separator");
        for (File file : classPath) {
            if (!file.isFile()) {
                throw new RuntimeException("Classpath file not found: " + file);
            }
            if (result.length() > 0) {
                result.append(separator);
            }
            result.append(file.getAbsolutePath());
        }
        return result.toString();
    }

    /*
     * WARNING - Removed try catching itself - possible behaviour change.
     */
    @Override
    public void onGameProcessEnded(final GameProcess process) {
        cancelCloudScriptHealthCheckTimeout();
        final int exitCode = process.getExitCode();
        if (exitCode == 0) {
            MinecraftGameRunner.LOGGER.info("Game ended with no troubles detected (exit code " + exitCode + ")");
            if (this.visibilityRule == LauncherVisibilityRule.CLOSE_LAUNCHER) {
                MinecraftGameRunner.LOGGER.info("Following visibility rule and exiting launcher as the game has ended");
                this.getLauncher().shutdownLauncher();
            } else if (this.visibilityRule == LauncherVisibilityRule.HIDE_LAUNCHER) {
                MinecraftGameRunner.LOGGER.info("Following visibility rule and showing launcher as the game has ended");
                this.minecraftLauncher.getUserInterface().setVisible(true);
            }
        } else {
            MinecraftGameRunner.LOGGER.error("Game ended with bad state (exit code " + exitCode + ")");
            MinecraftGameRunner.LOGGER.info("Ignoring visibility rule and showing launcher due to a game crash");
            this.minecraftLauncher.getUserInterface().setVisible(true);
            // Como o stdout do jogo é descartado (DISCARD), detectamos o crash report
            // pelo filesystem: procuramos o arquivo mais recente em crash-reports/ criado
            // após o início do jogo.
            File crashFile = this.findNewestCrashReport();
            if (crashFile != null) {
                MinecraftGameRunner.LOGGER.info("Crash report detected, opening: " + crashFile.getAbsolutePath());
                InputStream inputStream = null;
                try {
                    inputStream = new FileInputStream(crashFile);
                    final BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream));
                    final StringBuilder result = new StringBuilder();
                    String line2;
                    while ((line2 = reader.readLine()) != null) {
                        if (result.length() > 0) result.append("\n");
                        result.append(line2);
                    }
                    reader.close();
                    this.minecraftLauncher.getUserInterface().showCrashReport(this.getVersion(), crashFile, result.toString());
                } catch (IOException e) {
                    MinecraftGameRunner.LOGGER.error("Couldn't open crash report", e);
                } finally {
                    Downloadable.closeSilently(inputStream);
                }
            }
        }
        this.setStatus(GameInstanceStatus.IDLE);
        if ((exitCode != 0 || this.cloudScriptHealthCheckFailed)
                && this.detectedJavaProblem != null && this.javaProblemListener != null) {
            this.javaProblemListener.onJavaProblemDetected(this.detectedJavaProblem);
        }
    }

    private void detectJavaProblem(String line) {
        if (line == null || this.detectedJavaProblem != null) {
            return;
        }
        String normalized = line.toLowerCase(Locale.ROOT);
        if (normalized.contains("unrecognized vm option")
                || normalized.contains("could not create the java virtual machine")
                || normalized.contains("unsupportedclassversionerror")
                || normalized.contains("unsupported major.minor version")
                || normalized.contains("compiled by a more recent version of the java runtime")
                || normalized.contains("only recognizes class file versions up to")
                || normalized.contains("a jni error has occurred")
                || normalized.contains("inaccessibleobjectexception")
                || normalized.contains("unable to locate a java runtime")
                || normalized.contains("no java runtime present")
                || normalized.contains("could not find java.dll")
                || normalized.contains("failed to load jvm dll")) {
            this.detectedJavaProblem = line.trim();
        }
    }

    private void detectCloudScriptHealthCheck(String line) {
        if (line == null || this.cloudScriptHealthCheckPassed
                || this.cloudScriptHealthCheckFailed) {
            return;
        }
        String normalized = line.toLowerCase(Locale.ROOT);
        if (normalized.contains("[cloudscript] requesting macro: macrosoft_healthcheck")) {
            scheduleCloudScriptHealthCheckTimeout();
            return;
        }
        if (normalized.contains("macro response for macrosoft_healthcheck: ok=false")
                || normalized.contains("macro start result for macrosoft_healthcheck: false")
                || normalized.contains("ignoring run(macrosoft_healthcheck)")) {
            failCloudScriptHealthCheck("O teste do CloudScript falhou: " + line.trim());
            return;
        }
        if (!line.contains(CLOUDSCRIPT_HEALTHCHECK_OK)) {
            return;
        }
        this.cloudScriptHealthCheckPassed = true;
        cancelCloudScriptHealthCheckTimeout();
        LOGGER.info("CloudScript compatibility check passed for Java: "
            + (this.selectedProfile == null ? "unknown" : this.selectedProfile.getJavaPath()));
    }

    private synchronized void scheduleCloudScriptHealthCheckTimeout() {
        if (this.cloudScriptHealthCheckTimer != null
                || this.cloudScriptHealthCheckPassed
                || this.cloudScriptHealthCheckFailed) {
            return;
        }
        this.cloudScriptHealthCheckTimer = new Timer("cloudscript-healthcheck", true);
        this.cloudScriptHealthCheckTimer.schedule(new TimerTask() {
            @Override
            public void run() {
                failCloudScriptHealthCheck(
                    "O CloudScript iniciou a macro de teste, mas não produziu "
                    + CLOUDSCRIPT_HEALTHCHECK_OK + " em 45 segundos.");
            }
        }, CLOUDSCRIPT_HEALTHCHECK_TIMEOUT_MS);
    }

    private synchronized void cancelCloudScriptHealthCheckTimeout() {
        if (this.cloudScriptHealthCheckTimer != null) {
            this.cloudScriptHealthCheckTimer.cancel();
            this.cloudScriptHealthCheckTimer = null;
        }
    }

    private void failCloudScriptHealthCheck(String detail) {
        synchronized (this) {
            if (this.cloudScriptHealthCheckPassed || this.cloudScriptHealthCheckFailed) {
                return;
            }
            this.cloudScriptHealthCheckFailed = true;
            this.detectedJavaProblem = detail;
            cancelCloudScriptHealthCheckTimeout();
        }
        LOGGER.warn(detail);
        GameProcess process = this.currentProcess;
        if (process != null && process.isRunning()) {
            process.stop();
        }
    }

    /**
     * Anexa o health check ao evento nativo onJoinGame do Macro/Keybind. O
     * CloudScript preserva bindings que já contêm $${event}$$, portanto ambos
     * continuam sendo executados. A escrita é idempotente.
     */
    private void installCloudScriptHealthCheckTrigger(File gameDirectory) {
        File macrosFile = new File(gameDirectory,
            "liteconfig/common/macros/.macros.txt");
        if (!macrosFile.isFile()) {
            LOGGER.warn("CloudScript health check was not installed: "
                + macrosFile + " does not exist");
            return;
        }
        try {
            String contents = FileUtils.readFileToString(macrosFile, Charsets.UTF_8);
            String lineSeparator = contents.contains("\r\n") ? "\r\n" : "\n";
            String bindingPrefix = "Macro[1000].Macro=";
            int bindingStart = contents.indexOf(bindingPrefix);
            if (bindingStart < 0) {
                String suffix = contents.endsWith("\n") || contents.endsWith("\r")
                    ? "" : lineSeparator;
                contents = contents + suffix + bindingPrefix
                    + "$${event}$$|" + CLOUDSCRIPT_HEALTHCHECK_MACRO
                    + lineSeparator;
            } else {
                int valueStart = bindingStart + bindingPrefix.length();
                int lineEnd = contents.indexOf('\n', valueStart);
                if (lineEnd < 0) lineEnd = contents.length();
                int valueEnd = lineEnd;
                if (valueEnd > valueStart && contents.charAt(valueEnd - 1) == '\r') {
                    valueEnd--;
                }
                String binding = contents.substring(valueStart, valueEnd);
                if (binding.contains(CLOUDSCRIPT_HEALTHCHECK_MACRO)) {
                    return;
                }
                String separator = binding.trim().isEmpty() ? "" : "|";
                contents = contents.substring(0, valueEnd) + separator
                    + CLOUDSCRIPT_HEALTHCHECK_MACRO + contents.substring(valueEnd);
            }
            FileUtils.writeStringToFile(macrosFile, contents, Charsets.UTF_8);
            LOGGER.info("CloudScript health check attached to onJoinGame");
        } catch (IOException e) {
            LOGGER.warn("Could not install CloudScript health check trigger", e);
        }
    }

    /**
     * Procura o crash report mais recente gerado após o início do jogo.
     * Minecraft escreve crashes em &lt;gameDir&gt;/crash-reports/ com extensão .txt.
     */
    private File findNewestCrashReport() {
        File gameDir = (this.selectedProfile != null && this.selectedProfile.getGameDir() != null)
                ? this.selectedProfile.getGameDir()
                : this.getLauncher().getWorkingDirectory();
        File crashDir = new File(gameDir, "crash-reports");
        if (!crashDir.isDirectory()) return null;
        File[] reports = crashDir.listFiles();
        if (reports == null) return null;
        File newest = null;
        for (File f : reports) {
            if (!f.isFile() || !f.getName().endsWith(".txt")) continue;
            if (f.lastModified() < this.gameStartTime) continue; // anterior ao início do jogo
            if (newest == null || f.lastModified() > newest.lastModified()) {
                newest = f;
            }
        }
        return newest;
    }

    public void setVisibility(LauncherVisibilityRule visibility) {
        this.visibilityRule = visibility;
    }

    /** Para o processo do jogo se estiver em execução. */
    public void stopCurrentGame() {
        if (this.currentProcess != null && this.currentProcess.isRunning()) {
            this.currentProcess.stop();
        }
    }

    public UserAuthentication getAuth() {
        return this.auth;
    }

    public Profile getSelectedProfile() {
        return this.selectedProfile;
    }

}
