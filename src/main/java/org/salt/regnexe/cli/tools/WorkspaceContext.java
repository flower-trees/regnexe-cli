package org.salt.regnexe.cli.tools;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Holds the set of allowed workspace root directories.
 * All file tool operations are restricted to paths within these roots.
 *
 * Mutable: roots can be added at runtime via addRoot() — tools hold a reference
 * to this object and will see new roots immediately without agent rebuild.
 */
public class WorkspaceContext {

    private final List<Path> roots;

    public WorkspaceContext(List<Path> roots) {
        if (roots == null || roots.isEmpty()) {
            throw new IllegalArgumentException("WorkspaceContext requires at least one root directory");
        }
        this.roots = new ArrayList<>(
                roots.stream().map(p -> p.toAbsolutePath().normalize()).toList()
        );
    }

    public List<Path> getRoots() {
        return List.copyOf(roots);
    }

    public Path primaryRoot() {
        return roots.get(0);
    }

    /**
     * Adds a new root directory. Silently ignores duplicates.
     * Throws if the path is not an existing directory.
     */
    public void addRoot(Path p) {
        Path normalized = p.toAbsolutePath().normalize();
        if (!Files.isDirectory(normalized)) {
            throw new IllegalArgumentException("Not a directory: " + normalized);
        }
        if (!roots.contains(normalized)) {
            roots.add(normalized);
        }
    }

    /**
     * Resolves a path for READ operations: tries each root in order and returns
     * the first existing match for relative paths.
     * Absolute paths must still be within a known root.
     * Falls back to primary-root resolution if nothing exists (caller handles "not found").
     */
    public Path resolveForRead(String pathStr) {
        if (pathStr == null || pathStr.isBlank()) {
            return primaryRoot();
        }
        Path candidate = Path.of(pathStr);
        if (candidate.isAbsolute()) {
            // Absolute path: just enforce the root whitelist
            return resolve(pathStr);
        }
        // Relative path: try each root, return first existing match
        for (Path root : roots) {
            Path resolved = root.resolve(candidate).normalize();
            if (resolved.startsWith(root) && Files.exists(resolved)) {
                return resolved;
            }
        }
        // Nothing found: fall back to primary root (tools will surface "not found")
        return primaryRoot().resolve(candidate).normalize();
    }

    /**
     * Resolves a path for WRITE operations: relative paths always go under the primary root.
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
                String relStr = rel.toString();
                if (relStr.isEmpty()) return ".";
                // Prefix with root index when there are multiple roots, to avoid ambiguity
                if (roots.size() > 1) {
                    int idx = roots.indexOf(root);
                    return "[" + (idx == 0 ? "primary" : root.getFileName()) + "] " + relStr;
                }
                return relStr;
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

    /**
     * Human-readable description of all roots, one per line.
     * Format: "  1. /path/to/root (primary)"
     */
    public String describeRoots() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < roots.size(); i++) {
            sb.append("  ").append(i + 1).append(". ").append(roots.get(i));
            if (i == 0) sb.append("  (primary)");
            sb.append("\n");
        }
        return sb.toString();
    }
}
