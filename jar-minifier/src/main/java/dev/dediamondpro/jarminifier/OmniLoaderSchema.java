package dev.dediamondpro.jarminifier;

import java.util.ArrayList;
import java.util.List;

public class OmniLoaderSchema {
    private final int schemaVersion;
    private final ArrayList<Jar> jars;

    public OmniLoaderSchema(int schemaVersion, ArrayList<Jar> jars) {
        this.schemaVersion = schemaVersion;
        this.jars = jars;
    }


    public int getSchemaVersion() {
        return schemaVersion;
    }

    public ArrayList<Jar> getJars() {
        return jars;
    }

    public static class Jar {
        private final String path;
        private final List<String> versions;
        private final List<String> loaders;
        private final boolean isPrimary;

        public Jar(String path, List<String> versions, List<String> loaders, boolean isPrimary) {
            this.path = path;
            this.versions = versions;
            this.loaders = loaders;
            this.isPrimary = isPrimary;
        }

        public String getPath() {
            return path;
        }

        public List<String> getVersions() {
            return versions;
        }

        public List<String> getLoaders() {
            return loaders;
        }

        public boolean isPrimary() {
            return isPrimary;
        }
    }
}