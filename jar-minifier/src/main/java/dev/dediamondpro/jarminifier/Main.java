package dev.dediamondpro.jarminifier;

import com.google.gson.*;
import org.apache.commons.codec.digest.DigestUtils;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.*;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;
import java.util.zip.ZipEntry;

public class Main {
    private static final HashMap<String, ArrayList<String>> completedHashes = new HashMap<>();
    private static final Gson gson = new GsonBuilder().setPrettyPrinting().create();
    private static final ArrayList<String> noSplitFiles = new ArrayList<>();
    private static final ArrayList<String> excludedFiles = new ArrayList<>();

    private static void setupExclusions() {
        //noSplitFiles.add("fabric.mod.json");
    }

    public static void main(String[] args) throws IOException {
        setupExclusions();
        List<JarFile> jars = Arrays.stream(Objects.requireNonNull(new File("jars").listFiles(((dir, name) -> name.endsWith(".jar"))))).map(file -> {
            try {
                return new JarFile(file);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }).toList();
        File destDir = new File("out");
        destDir.mkdir();
        Arrays.stream(Objects.requireNonNull(destDir.listFiles())).forEach(file -> file.delete());
        HashMap<JarFile, String> versions = new HashMap<>();
        HashMap<String, ArrayList<JarFile>> nestedJars = new HashMap<>();
        JsonObject generatedFabricJson = JsonParser.parseReader(new InputStreamReader(Objects.requireNonNull(Main.class.getResourceAsStream("/fabric.mod.json")))).getAsJsonObject();
        String iconPath = null;
        byte[] iconBytes = null;
        HashMap<String, HashMap<String, ArrayList<String>>> allDependencies = new HashMap<>();
        HashMap<String, HashMap<String, Integer>> dependencyCounts = new HashMap<>();
        String modId = null;
        for (JarFile jar : jars) {
            JarEntry fabricJson = jar.getJarEntry("fabric.mod.json");
            if (fabricJson == null) {
                System.err.println("No fabric.mod.json found in " + jar.getName());
                continue;
            }
            JsonObject json = JsonParser.parseReader(new InputStreamReader(jar.getInputStream(fabricJson))).getAsJsonObject();
            String mcVersion = json.getAsJsonObject("depends").get("minecraft").getAsString();
            versions.put(jar, mcVersion);
            if (json.has("mixins")) for (JsonElement element : json.getAsJsonArray("mixins")) {
                //noSplitFiles.add(element.getAsString());
            }
            if (json.has("jars")) for (JsonElement element : json.getAsJsonArray("jars")) {
                String file = element.getAsJsonObject().get("file").getAsString();
                excludedFiles.add(file);
                nestedJars.computeIfAbsent(file, (str) -> new ArrayList<>()).add(jar);
            }
            if (json.has("id")) {
                generatedFabricJson.addProperty("id", json.get("id").getAsString() + "-container");
                generatedFabricJson.getAsJsonObject("custom").getAsJsonObject("modmenu")
                        .getAsJsonObject("parent").add("id", json.get("id"));
                modId = json.get("id").getAsString();
            }
            if (json.has("name")) generatedFabricJson.add("name", json.get("name"));
            if (json.has("version")) generatedFabricJson.add("version", json.get("version"));
            if (json.has("description")) generatedFabricJson.add("description", json.get("description"));
            if (json.has("contact")) generatedFabricJson.add("contact", json.get("contact"));
            if (json.has("authors")) generatedFabricJson.add("authors", json.get("authors"));
            if (json.has("contributors")) generatedFabricJson.add("contributors", json.get("contributors"));
            if (json.has("license")) generatedFabricJson.add("license", json.get("license"));
            if (json.has("environment")) generatedFabricJson.add("environment", json.get("environment"));
            if (json.has("icon")) {
                JarEntry icon = jar.getJarEntry(json.get("icon").getAsString());
                if (icon != null) {
                    generatedFabricJson.add("icon", json.get("icon"));
                    iconPath = json.get("icon").getAsString();
                    iconBytes = jar.getInputStream(icon).readAllBytes();
                }
            }
            if (json.has("depends")) collectDependencies("depends", json, allDependencies, dependencyCounts);
            if (json.has("suggests")) collectDependencies("suggests", json, allDependencies, dependencyCounts);
            if (json.has("recommends")) collectDependencies("recommends", json, allDependencies, dependencyCounts);
            if (json.has("breaks")) collectDependencies("breaks", json, allDependencies, dependencyCounts);
        }
        generatedFabricJson.getAsJsonArray("provides").add(modId);
        for (String dependencyType : allDependencies.keySet()) {
            JsonObject object = new JsonObject();
            for (String dependency : allDependencies.get(dependencyType).keySet()) {
                if (dependencyCounts.get(dependencyType).get(dependency) != jars.size()) continue;
                List<String> depVersions = allDependencies.get(dependencyType).get(dependency);
                JsonArray array = new JsonArray(depVersions.size());
                for (String ver : depVersions) array.add(ver);
                object.add(dependency, array);
            }
            generatedFabricJson.add(dependencyType, object);
        }
        OmniLoaderSchema schema = new OmniLoaderSchema(0, new ArrayList<>());
        ArrayList<File> files = new ArrayList<>();
        ArrayList<File> nestedFiles = new ArrayList<>();
        for (String file : nestedJars.keySet()) {
            ArrayList<JarFile> parentJars = nestedJars.get(file);
            String[] fileParts = file.split("/");
            File fileLocation = new File(destDir, fileParts[fileParts.length - 1]);
            JarFile jar = parentJars.get(0);
            Files.copy(jar.getInputStream(jar.getEntry(file)), fileLocation.toPath(), StandardCopyOption.REPLACE_EXISTING);
            List<String> jarVersions = parentJars.stream().map(versions::get).toList();
            if (parentJars.size() != jars.size()) {
                schema.getJars().add(new OmniLoaderSchema.Jar("omniloader/" + fileLocation.getName(), jarVersions, Collections.singletonList("fabric"), false));
                files.add(fileLocation);
            } else {
                JsonObject object = new JsonObject();
                object.addProperty("file", "META-INF/jars/" + fileLocation.getName());
                generatedFabricJson.getAsJsonArray("jars").add(object);
                nestedFiles.add(fileLocation);
            }
        }
        HashMap<String, JarOutputStream> outputStreams = new HashMap<>();
        for (int i = 0; i < jars.size(); i++) {
            System.out.println(i + ": " + jars.get(i).getName());
            for (Iterator<JarEntry> it = jars.get(i).entries().asIterator(); it.hasNext(); ) {
                JarEntry entry = it.next();
                if (excludedFiles.contains(entry.getName())) continue;
                String md5 = DigestUtils.md5Hex(jars.get(i).getInputStream(entry));
                if (completedHashes.computeIfAbsent(entry.getName(), e -> new ArrayList<>()).contains(md5)) continue;
                byte[] identifier = new byte[jars.size()];
                identifier[i] = 1;
                if (isSplitAllowed(entry.getName())) {
                    completedHashes.computeIfAbsent(entry.getName(), e -> new ArrayList<>()).add(md5);
                    for (int l = i + 1; l < jars.size(); l++) {
                        InputStream in = jars.get(l).getInputStream(entry);
                        if (in == null) continue;
                        if (!md5.equals(DigestUtils.md5Hex(in))) continue;
                        identifier[l] = 1;
                    }
                }
                StringBuilder identifierBuilder = new StringBuilder();
                for (byte b : identifier) identifierBuilder.append(b);
                String identifierString = identifierBuilder.toString();
                String finalModId = modId;
                JarOutputStream out = outputStreams.computeIfAbsent(identifierString, (key) -> {
                    try {
                        File file = new File(destDir, finalModId + "-" + identifierString + ".jar");
                        files.add(file);
                        file.createNewFile();
                        ArrayList<String> jarVersions = new ArrayList<>();
                        for (int j = 0; j < identifier.length; j++) {
                            if (identifier[j] != 1) continue;
                            jarVersions.add(versions.get(jars.get(j)));
                        }
                        schema.getJars().add(new OmniLoaderSchema.Jar("omniloader/" + file.getName(), jarVersions, Collections.singletonList("fabric"), true));
                        return new JarOutputStream(new FileOutputStream(file));
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                });
                add(entry, jars.get(i), out);
            }
        }
        for (JarFile jar : jars) {
            jar.close();
        }
        for (JarOutputStream stream : outputStreams.values()) {
            stream.close();
        }
        try (JarOutputStream out = new JarOutputStream(new FileOutputStream(modId + ".jar"))) {
            add("META-INF/MANIFEST.MF", new ByteArrayInputStream("Manifest-Version: 1.0".getBytes()), out);
            add("fabric.mod.json", new ByteArrayInputStream(gson.toJson(generatedFabricJson).getBytes()), out);
            add("omniloader0.json", new ByteArrayInputStream(gson.toJson(schema).getBytes()), out);
            add("OmniLoader-Info.txt", Objects.requireNonNull(Main.class.getResourceAsStream("/OmniLoader-Info.txt")), out);
            add("META-INF/jars/omniloader.jar", new FileInputStream("omniloader.jar"), out);
            if (iconBytes != null) add(iconPath, new ByteArrayInputStream(iconBytes), out);
            for (File jar : files) {
                add("omniloader/" + jar.getName(), new FileInputStream(jar), out);
            }
            for (File jar : nestedFiles) {
                add("META-INF/jars/" + jar.getName(), new FileInputStream(jar), out);
            }
        }
    }

    private static void collectDependencies(String type, JsonObject json, HashMap<String, HashMap<String, ArrayList<String>>> allDependencies, HashMap<String, HashMap<String, Integer>> dependencyCounts) {
        HashMap<String, ArrayList<String>> dependencies = allDependencies.computeIfAbsent(type, it -> new HashMap<>());
        HashMap<String, Integer> dependenciesCount = dependencyCounts.computeIfAbsent(type, it -> new HashMap<>());
        for (Map.Entry<String, JsonElement> dependency : json.getAsJsonObject(type).entrySet()) {
            ArrayList<String> dependencyList = dependencies.computeIfAbsent(dependency.getKey(), it -> new ArrayList<>());
            if (dependency.getValue().isJsonPrimitive()) {
                String version = dependency.getValue().getAsString();
                if (!dependencyList.contains(version)) dependencyList.add(version);
            } else {
                for (JsonElement element : dependency.getValue().getAsJsonArray()) {
                    String version = element.getAsString();
                    if (!dependencyList.contains(version)) dependencyList.add(version);
                }
            }
            dependenciesCount.compute(dependency.getKey(), (k, v) -> v == null ? 1 : v + 1);
        }
    }

    private static void add(JarEntry entry, JarFile source, JarOutputStream out) throws IOException {
        out.putNextEntry(entry);
        try (BufferedInputStream in = new BufferedInputStream(source.getInputStream(entry))) {
            byte[] buffer = new byte[1024];
            while (true) {
                int count = in.read(buffer);
                if (count == -1)
                    break;
                out.write(buffer, 0, count);
            }
            out.closeEntry();
        }
    }

    private static void add(String name, InputStream in, JarOutputStream out) throws IOException {
        out.putNextEntry(new ZipEntry(name));
        byte[] buffer = new byte[1024];
        while (true) {
            int count = in.read(buffer);
            if (count == -1)
                break;
            out.write(buffer, 0, count);
        }
        out.closeEntry();
    }

    private static boolean isSplitAllowed(String name) {
        return !noSplitFiles.contains(name);
    }
}
