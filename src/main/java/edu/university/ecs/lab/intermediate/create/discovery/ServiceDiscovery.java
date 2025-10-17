package edu.university.ecs.lab.intermediate.create.discovery;

import edu.university.ecs.lab.common.models.enums.WebFramework;
import edu.university.ecs.lab.common.services.LoggerManager;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Discovers potential services/modules from a repository and detects active web frameworks.
 */
public class ServiceDiscovery {

    private static final List<String> STANDARD_SOURCE_ROOTS = List.of(
            "src/main/java",
            "src/main/kotlin",
            "src/main/groovy",
            "src/main" // fallback for unconventional layouts
    );
    private static final List<String> RESOURCE_ROOTS = List.of(
            "src/main/resources",
            "resources",
            "config"
    );
    private static final Set<String> IGNORED_DIRECTORIES = Set.of(".git", ".svn", ".idea", "target", "build", "out", "node_modules");

    private final FrameworkDetector frameworkDetector = new FrameworkDetector();

    public List<ServiceContext> discoverServices(String repoRoot) {
        File root = new File(repoRoot);
        if (!root.exists() || !root.isDirectory()) {
            return List.of();
        }

        Map<Path, ServiceContext> contexts = new LinkedHashMap<>();
        traverse(root, contexts);
        LoggerManager.info(() -> String.format("Service discovery located %d module(s) under %s", contexts.size(), repoRoot));
        return new ArrayList<>(contexts.values());
    }

    private void traverse(File directory, Map<Path, ServiceContext> contexts) {
        if (directory == null || !directory.isDirectory() || shouldIgnore(directory)) {
            return;
        }

        File[] files = directory.listFiles();
        if (files == null) {
            return;
        }

        Optional<File> pom = findFile(files, "pom.xml");
        Optional<File> gradle = findFile(files, "build.gradle");
        Optional<File> gradleKts = findFile(files, "build.gradle.kts");
        Optional<File> settingsGradle = findFile(files, "settings.gradle");
        Optional<File> settingsGradleKts = findFile(files, "settings.gradle.kts");

        boolean hasBuildFile = pom.isPresent() || gradle.isPresent() || gradleKts.isPresent();

        if (pom.isPresent()) {
            Set<String> modules = parseMavenModules(pom.get());
            for (String module : modules) {
                traverse(new File(directory, module), contexts);
            }
            if (!modules.isEmpty() && !hasSource(directory)) {
                return;
            }
        }

        if (settingsGradle.isPresent()) {
            parseGradleModules(settingsGradle.get()).forEach(module -> traverse(new File(directory, module), contexts));
        }
        if (settingsGradleKts.isPresent()) {
            parseGradleModules(settingsGradleKts.get()).forEach(module -> traverse(new File(directory, module), contexts));
        }

        if (hasBuildFile || hasSource(directory)) {
            Path modulePath = directory.toPath().toAbsolutePath().normalize();
            if (!contexts.containsKey(modulePath)) {
                contexts.put(modulePath, buildContext(directory));
            }
        } else {
            for (File child : files) {
                if (child.isDirectory()) {
                    traverse(child, contexts);
                }
            }
        }
    }

    private boolean hasSource(File directory) {
        return STANDARD_SOURCE_ROOTS.stream().map(path -> new File(directory, path)).anyMatch(File::isDirectory);
    }

    private boolean shouldIgnore(File directory) {
        String name = directory.getName();
        if (IGNORED_DIRECTORIES.contains(name)) {
            return true;
        }

        return name.startsWith(".") && !".".equals(name);
    }

    private Optional<File> findFile(File[] files, String fileName) {
        return Arrays.stream(files).filter(file -> file.isFile() && file.getName().equals(fileName)).findFirst();
    }

    private Set<String> parseMavenModules(File pomFile) {
        try {
            var documentBuilder = javax.xml.parsers.DocumentBuilderFactory.newInstance().newDocumentBuilder();
            var document = documentBuilder.parse(pomFile);
            var modules = document.getElementsByTagName("module");
            Set<String> modulePaths = new HashSet<>();
            for (int i = 0; i < modules.getLength(); i++) {
                modulePaths.add(modules.item(i).getTextContent().trim());
            }
            return modulePaths;
        } catch (Exception e) {
            LoggerManager.debug(() -> "Unable to parse modules from pom.xml " + pomFile + ": " + e.getMessage());
            return Set.of();
        }
    }

    private Set<String> parseGradleModules(File settingsFile) {
        Set<String> modules = new HashSet<>();
        try {
            List<String> lines = Files.readAllLines(settingsFile.toPath());
            for (String line : lines) {
                String trimmed = line.trim();
                if (!trimmed.startsWith("include")) {
                    continue;
                }

                String moduleList = trimmed.substring("include".length()).trim();
                moduleList = moduleList.replace("(", "").replace(")", "");
                String[] parts = moduleList.split(",");
                for (String part : parts) {
                    String cleaned = stripQuotes(part.trim());
                    if (cleaned.isEmpty()) {
                        continue;
                    }

                    String normalized = Arrays.stream(cleaned.split(":"))
                            .map(String::trim)
                            .filter(segment -> !segment.isEmpty())
                            .collect(Collectors.joining(File.separator));

                    if (!normalized.isEmpty()) {
                        modules.add(normalized);
                    }
                }
            }
        } catch (IOException e) {
            LoggerManager.debug(() -> "Unable to parse settings file " + settingsFile + ": " + e.getMessage());
        }
        return modules;
    }

    private String stripQuotes(String value) {
        if (value.isEmpty()) {
            return value;
        }
        String result = value;
        if (result.startsWith("\"")) {
            result = result.substring(1);
        }
        if (result.endsWith("\"")) {
            result = result.substring(0, result.length() - 1);
        }
        if (result.startsWith("'")) {
            result = result.substring(1);
        }
        if (result.endsWith("'")) {
            result = result.substring(0, result.length() - 1);
        }
        return result;
    }

    private ServiceContext buildContext(File moduleDirectory) {
        Set<Path> sourceRoots = STANDARD_SOURCE_ROOTS.stream()
                .map(path -> moduleDirectory.toPath().resolve(path))
                .filter(path -> Files.isDirectory(path))
                .map(Path::toAbsolutePath)
                .collect(Collectors.toCollection(LinkedHashSet::new));

        if (sourceRoots.isEmpty() && Files.isDirectory(moduleDirectory.toPath().resolve("src"))) {
            sourceRoots.add(moduleDirectory.toPath().resolve("src").toAbsolutePath());
        }

        Set<Path> resourceRoots = RESOURCE_ROOTS.stream()
                .map(path -> moduleDirectory.toPath().resolve(path))
                .filter(path -> Files.isDirectory(path))
                .map(Path::toAbsolutePath)
                .collect(Collectors.toCollection(LinkedHashSet::new));

        Set<WebFramework> frameworks = EnumSet.noneOf(WebFramework.class);
        for (Path sourceRoot : sourceRoots) {
            frameworks.addAll(frameworkDetector.detectFrameworks(sourceRoot.toFile()));
        }

        return new ServiceContext(
                moduleDirectory.getName(),
                moduleDirectory.toPath().toAbsolutePath(),
                sourceRoots,
                resourceRoots,
                frameworks
        );
    }

    /**
     * Represents a discovered service/module with relevant scanning context.
     */
    public static class ServiceContext {
        private final String name;
        private final Path modulePath;
        private final Set<Path> sourceRoots;
        private final Set<Path> resourceRoots;
        private final Set<WebFramework> frameworks;

        public ServiceContext(String name, Path modulePath, Set<Path> sourceRoots, Set<Path> resourceRoots, Set<WebFramework> frameworks) {
            this.name = name;
            this.modulePath = modulePath;
            this.sourceRoots = sourceRoots;
            this.resourceRoots = resourceRoots;
            this.frameworks = frameworks;
        }

        public String getName() {
            return name;
        }

        public Path getModulePath() {
            return modulePath;
        }

        public Set<Path> getSourceRoots() {
            return sourceRoots;
        }

        public Set<Path> getResourceRoots() {
            return resourceRoots;
        }

        public Set<WebFramework> getFrameworks() {
            return frameworks;
        }
    }
}
