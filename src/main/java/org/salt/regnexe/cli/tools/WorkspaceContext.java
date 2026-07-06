package org.salt.regnexe.cli.tools;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Holds the set of allowed workspace root directories.
 * All file tool operations are restricted to paths within these roots.
 */
public class WorkspaceContext {

    private final List<Path> roots;

    public WorkspaceContext(List<Path> roots) {
        if (roots == null || roots.isEmpty()) {
            throw new IllegalArgumentException("WorkspaceContext requires at least one root directory");
        }
        this.roots = roots.stream()
                .map(p -> p.toAbsolutePath().normalize())
                .toList();
    }

    public List<Path> getRoots() {
        return roots;
    }

    public Path primaryRoot() {
        return roots.get(0);
    }

    /**
     * Resolves a path string against the workspace.
     * Relative paths are resolved against the primary root.
     * Throws if the resolved path escapes all workspace roots.
     */
    public Path resolve(String pathStr) {
        if (pathStr == null || pathStr.isBlank()) {
            return primaryRoot();
        }
        Path candidate = Path.of(pathStr);
        Path resolved = candidate.isAbsolute()
                ? candidate.normalize()
                : primaryRoot().resolve(candidate).normalize();
        for (Path root : roots) {
            if (resolved.startsWith(root)) {
                return resolved;
            }
        }
        throw new SecurityException("Path outside workspace: " + resolved);
    }

    /**
     * Returns the path relative to its containing workspace root, for display.
     * Falls back to the absolute path if no root matches.
     */
    public String displayPath(Path absolute) {
        Path normalized = absolute.normalize();
        for (Path root : roots) {
            if (normalized.startsWith(root)) {
                Path rel = root.relativize(normalized);
                return rel.toString().isEmpty() ? "." : rel.toString();
            }
        }
        return normalized.toString();
    }

    /**
     * Returns true if the path exists and is within the workspace.
     */
    public boolean isAccessible(Path path) {
        Path normalized = path.normalize();
        return roots.stream().anyMatch(normalized::startsWith) && Files.exists(normalized);
    }
}
