package dev.dediamondpro.omniloader0;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.accesswidener.AccessWidenerReader;
import net.fabricmc.api.EnvType;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import net.fabricmc.loader.api.Version;
import net.fabricmc.loader.api.VersionParsingException;
import net.fabricmc.loader.api.entrypoint.EntrypointContainer;
import net.fabricmc.loader.api.entrypoint.PreLaunchEntrypoint;
import net.fabricmc.loader.impl.FabricLoaderImpl;
import net.fabricmc.loader.impl.ModContainerImpl;
import net.fabricmc.loader.impl.discovery.ModCandidate;
import net.fabricmc.loader.impl.entrypoint.EntrypointStorage;
import net.fabricmc.loader.impl.game.minecraft.MinecraftGameProvider;
import net.fabricmc.loader.impl.launch.FabricLauncherBase;
import net.fabricmc.loader.impl.metadata.*;
import net.fabricmc.loader.impl.util.version.VersionPredicateParser;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.spongepowered.asm.mixin.Mixins;

import java.io.*;
import java.lang.reflect.*;
import java.nio.file.*;
import java.util.*;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.stream.Collectors;

public class OmniLoader implements PreLaunchEntrypoint {
    private static final Logger LOGGER = LogManager.getLogger("OmniLoader");
    private static final Gson gson = new GsonBuilder().create();
    private final FabricLoaderImpl fabricLoader = (FabricLoaderImpl) FabricLoader.getInstance();
    private final File loaderDir = new File(fabricLoader.getConfigDir().toFile(), "omniloader0");

    @Override
    public void onPreLaunch() {
        long startTime = System.currentTimeMillis();
        if (!setup()) return;
        Version mcVersion = fabricLoader.getModContainer("minecraft").get().getMetadata().getVersion();
        ArrayList<ModContainer> loadedMods = new ArrayList<>();
        for (ModContainer mod : new ArrayList<>(fabricLoader.getAllMods())) {
            if (!mod.getMetadata().containsCustomValue("omniloader0")) continue;
            List<OmniLoaderSchema.Jar> jars = collectJars(mod);
            if (jars == null || jars.isEmpty()) continue;
            jars = filterJars(jars, mcVersion, "fabric");
            Map<OmniLoaderSchema.Jar, File> extractedJars = extractJars(mod, jars);
            LOGGER.info("Loading jars for " + mod.getMetadata().getId() + ": " + extractedJars.values().stream().map(File::getName).collect(Collectors.toList()));
            ArrayList<Path> paths = new ArrayList<>();
            for (OmniLoaderSchema.Jar jar : extractedJars.keySet()) {
                if (jar.isPrimary()) paths.add(extractedJars.get(jar).toPath());
            }
            for (OmniLoaderSchema.Jar jar : extractedJars.keySet()) {
                loadJar(extractedJars.get(jar));
            }
            for (OmniLoaderSchema.Jar jar : extractedJars.keySet()) {
                File file = extractedJars.get(jar);
                ModContainer modContainer = addAsMod(file, jar.isPrimary() ? paths : Collections.singletonList(file.toPath()));
                if (modContainer != null) loadedMods.add(modContainer);
            }
        }
        LOGGER.info("Finished loading omniloader mods, took {}ms", System.currentTimeMillis() - startTime);
        if (fabricLoader.hasEntrypoints("preLaunch")) {
            for (EntrypointContainer<PreLaunchEntrypoint> container : fabricLoader.getEntrypointContainers("preLaunch", PreLaunchEntrypoint.class)) {
                if (!loadedMods.contains(container.getProvider())) continue;
                container.getEntrypoint().onPreLaunch();
            }
        }
    }

    public List<OmniLoaderSchema.Jar> collectJars(ModContainer mod) {
        for (Path path : mod.getOrigin().getPaths()) {
            try (JarFile jar = new JarFile(path.toString())) {
                JarEntry omniLoaderFile = jar.getJarEntry("omniloader0.json");
                if (omniLoaderFile == null) continue;
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(jar.getInputStream(omniLoaderFile)))) {
                    OmniLoaderSchema schema = gson.fromJson(reader, OmniLoaderSchema.class);
                    if (schema.getSchemaVersion() >= 1) {
                        throw new IllegalStateException("Schema version requested (" + schema.getSchemaVersion() + ") is greater then the highest schema version supported by this version of OmniLoader (0)");
                    }
                    return schema.getJars();
                }
            } catch (IOException e) {
                LOGGER.error("Failed to check jar file:", e);
            }
        }
        return null;
    }

    public List<OmniLoaderSchema.Jar> filterJars(List<OmniLoaderSchema.Jar> jars, Version version, String loader) {
        ArrayList<OmniLoaderSchema.Jar> requiredJars = new ArrayList<>();
        for (OmniLoaderSchema.Jar jar : jars) {
            if (!jar.getLoaders().contains(loader)) continue;
            for (String requestedVersion : jar.getVersions()) {
                try {
                    if (VersionPredicateParser.parse(requestedVersion).test(version)) {
                        requiredJars.add(jar);
                        break;
                    }
                } catch (VersionParsingException e) {
                    LOGGER.error("Failed to parse version predicate:", e);
                }
            }
        }
        return requiredJars;
    }

    private Map<OmniLoaderSchema.Jar, File> extractJars(ModContainer mod, List<OmniLoaderSchema.Jar> jars) {
        HashMap<OmniLoaderSchema.Jar, File> extractedJars = new HashMap<>();
        File dir = new File(loaderDir, mod.getMetadata().getId());
        dir.mkdirs();
        for (Path path : mod.getOrigin().getPaths()) {
            try (JarFile jar = new JarFile(path.toString())) {
                for (OmniLoaderSchema.Jar jarToLoad : jars) {
                    JarEntry entry = jar.getJarEntry(jarToLoad.getPath());
                    if (entry == null) {
                        LOGGER.warn("Jar file " + jarToLoad.getPath() + " not found.");
                        continue;
                    }
                    File file = new File(dir, entry.getName());
                    new File(file.getParent()).mkdirs();
                    Files.copy(jar.getInputStream(entry), file.toPath(), StandardCopyOption.REPLACE_EXISTING);
                    extractedJars.put(jarToLoad, file);
                }
            } catch (IOException e) {
                LOGGER.error("Failed to load jar file:", e);
            }
        }
        return extractedJars;
    }

    private Field envType;
    private Constructor<?> constructor;
    private Method addField;
    private Field entryPointStorageField;
    private Field adapterMapField;
    private Method addDeprecatedMethod;
    private Method addMethod;

    private boolean setup() {
        try {
            envType = MinecraftGameProvider.class.getDeclaredField("envType");
            constructor = ModCandidate.class.getDeclaredConstructors()[0];
            addField = FabricLoaderImpl.class.getDeclaredMethod("addMod", ModCandidate.class);
            entryPointStorageField = FabricLoaderImpl.class.getDeclaredField("entrypointStorage");
            adapterMapField = FabricLoaderImpl.class.getDeclaredField("adapterMap");
            addDeprecatedMethod = EntrypointStorage.class.getDeclaredMethod("addDeprecated", ModContainerImpl.class, String.class, String.class);
            addMethod = EntrypointStorage.class.getDeclaredMethod("add", ModContainerImpl.class, String.class, EntrypointMetadata.class, Map.class);
            envType.setAccessible(true);
            constructor.setAccessible(true);
            addField.setAccessible(true);
            entryPointStorageField.setAccessible(true);
            adapterMapField.setAccessible(true);
            addDeprecatedMethod.setAccessible(true);
            addMethod.setAccessible(true);
            return true;
        } catch (NoSuchFieldException | NoSuchMethodException e) {
            LOGGER.error("Failed to load omniloader0", e);
            return false;
        }
    }

    private void loadJar(File jar) {
        FabricLauncherBase.getLauncher().addToClassPath(jar.toPath());
    }

    private ModContainer addAsMod(File jar, List<Path> paths) {
        try (JarFile jarFile = new JarFile(jar)) {
            JarEntry fabricJson = jarFile.getJarEntry("fabric.mod.json");
            if (fabricJson == null) return null;
            VersionOverrides versionOverrides = new VersionOverrides();
            DependencyOverrides depOverrides = new DependencyOverrides(fabricLoader.getConfigDir());
            LoaderModMetadata metadata = ModMetadataParser.parseMetadata(jarFile.getInputStream(fabricJson), jar.getPath(), new ArrayList<>(), versionOverrides, depOverrides, false);
            ModCandidate candidate = (ModCandidate) constructor.newInstance(paths, jar.getAbsolutePath(), -1, metadata, false, Collections.emptyList());
            addField.invoke(fabricLoader, candidate);
            ModContainerImpl modContainer = (ModContainerImpl) fabricLoader.getModContainer(metadata.getId()).get();
            Object entrypointStorage = entryPointStorageField.get(fabricLoader);
            Object adapterMap = adapterMapField.get(fabricLoader);
            for (String in : modContainer.getInfo().getOldInitializers()) {
                String adapter = modContainer.getInfo().getOldStyleLanguageAdapter();
                addDeprecatedMethod.invoke(entrypointStorage, modContainer, adapter, in);
            }
            for (String key : modContainer.getInfo().getEntrypointKeys()) {
                for (EntrypointMetadata in : modContainer.getInfo().getEntrypoints(key)) {
                    addMethod.invoke(entrypointStorage, modContainer, key, in, adapterMap);
                }
            }
            MinecraftGameProvider provider = (MinecraftGameProvider) fabricLoader.tryGetGameProvider();
            for (String mixinConfig : metadata.getMixinConfigs((EnvType) envType.get(provider))) {
                Mixins.addConfigurations(mixinConfig);
            }
            String accessWidener = modContainer.getMetadata().getAccessWidener();
            if (accessWidener != null) {
                Path path = modContainer.findPath(accessWidener).orElse(null);
                if (path == null) {
                    LOGGER.error(String.format("Missing accessWidener file %s from mod %s", accessWidener, modContainer.getMetadata().getId()));
                } else {
                    AccessWidenerReader accessWidenerReader = new AccessWidenerReader(fabricLoader.getAccessWidener());
                    try (BufferedReader reader = Files.newBufferedReader(path)) {
                        accessWidenerReader.read(reader, FabricLauncherBase.getLauncher().getTargetNamespace());
                    } catch (Exception e) {
                        LOGGER.error("Failed to read accessWidener file from mod " + modContainer.getMetadata().getId(), e);
                    }
                }
            }
            return modContainer;
        } catch (IllegalAccessException | InvocationTargetException | InstantiationException | IOException |
                 ParseMetadataException e) {
            LOGGER.error("Failed to add " + jar + " as a mod:", e);
        }
        return null;
    }
}
