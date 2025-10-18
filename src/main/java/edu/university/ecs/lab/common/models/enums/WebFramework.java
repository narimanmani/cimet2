package edu.university.ecs.lab.common.models.enums;

import java.util.Set;

/**
 * Enumeration of supported web frameworks.
 */
public enum WebFramework {
    SPRING(Set.of("org.springframework", "jakarta.servlet"));

    private final Set<String> indicatorImports;

    WebFramework(Set<String> indicatorImports) {
        this.indicatorImports = indicatorImports;
    }

    public Set<String> getIndicatorImports() {
        return indicatorImports;
    }
}
