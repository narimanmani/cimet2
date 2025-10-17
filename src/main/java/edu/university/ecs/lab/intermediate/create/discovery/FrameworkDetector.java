package edu.university.ecs.lab.intermediate.create.discovery;

import edu.university.ecs.lab.common.models.enums.WebFramework;
import edu.university.ecs.lab.common.services.LoggerManager;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.nio.file.Files;
import java.util.EnumSet;
import java.util.Optional;
import java.util.Set;

/**
 * Detects active web frameworks inside a source root by scanning source files for framework-specific imports.
 */
public class FrameworkDetector {

    private static final int MAX_FILES_TO_SCAN = 50;

    public Set<WebFramework> detectFrameworks(File sourceRoot) {
        EnumSet<WebFramework> frameworks = EnumSet.noneOf(WebFramework.class);
        if (sourceRoot == null || !sourceRoot.isDirectory()) {
            return frameworks;
        }

        int scanned = 0;
        try {
            try (var paths = Files.walk(sourceRoot.toPath())) {
                for (var iterator = paths.iterator(); iterator.hasNext() && scanned < MAX_FILES_TO_SCAN; ) {
                    var path = iterator.next();
                    if (!Files.isRegularFile(path)) {
                        continue;
                    }

                    String fileName = path.getFileName().toString().toLowerCase();
                    if (!(fileName.endsWith(".java") || fileName.endsWith(".kt") || fileName.endsWith(".kts") || fileName.endsWith(".groovy"))) {
                        continue;
                    }

                    if (frameworks.size() == WebFramework.values().length) {
                        break;
                    }

                    scanned++;
                    detectFrameworkForFile(path.toFile()).ifPresent(frameworks::add);
                }
            }
        } catch (IOException e) {
            LoggerManager.debug(() -> "Failed to scan source root " + sourceRoot + " for frameworks: " + e.getMessage());
        }

        return frameworks;
    }

    private Optional<WebFramework> detectFrameworkForFile(File file) {
        try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String trimmed = line.trim();
                if (trimmed.startsWith("import ")) {
                    for (WebFramework framework : WebFramework.values()) {
                        for (String indicator : framework.getIndicatorImports()) {
                            if (trimmed.contains(indicator)) {
                                return Optional.of(framework);
                            }
                        }
                    }
                }
            }
        } catch (IOException e) {
            LoggerManager.debug(() -> "Unable to inspect file " + file + " for framework hints: " + e.getMessage());
        }

        return Optional.empty();
    }
}
