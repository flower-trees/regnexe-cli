package org.salt.regnexe.cli.tools;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * Holds the set of allowed workspace root directories.
 * File tools use real-path validation against these roots, with explicit absolute read
 * exceptions for system and temporary locations. This is not an OS-level sandbox.
 *
 * Mutable: roots can be added at runtime via addRoot() — tools hold a reference
 * to this object and will see new roots immediately without agent rebuild.
 */
public class WorkspaceContext {

    // Absolute paths under these prefixes are readable even though they're outside every
    // workspace root — standard system/tool locations and scratch temp dirs. Shared with
    // BashTool's own workspace-escape heuristic (same list, same reasoning: these are locations a
    // command legitimately touches — 2>/dev/null, /usr/bin/env — or where ToolOutputOverflow
    // saves overflowed tool output for later retrieval via read_file). Read-only: write_file/
    // edit_file still go through resolve(), which does NOT consult this list — there's no reason
    // to let the model write into system tmp via those tools.
    public static final List<String> SAFE_READ_PREFIXES = List.of(
        "/dev/", "/bin/", "/sbin/", "/usr/", "/opt/", "/etc/", "/System/", "/Library/",
        "/private/etc/", "/private/tmp/", "/private/var/folders/", "/tmp/", "/var/folders/"
    );

    private final List<Path> roots;
    // Pin real roots when granted, rather than silently following a retargeted root symlink.
    private final List<Path> realRoots = new ArrayList<>();

    public WorkspaceContext(List<Path> roots) {
        if (roots == null || roots.isEmpty()) {
            throw new IllegalArgumentException("WorkspaceContext requires at least one root directory");
        }
        this.roots = new ArrayList<>();
        roots.forEach(this::addRoot);
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
        Path real;
        try {
            real = normalized.toRealPath();
        } catch (IOException e) {
            throw new IllegalArgumentException("Cannot resolve workspace directory: " + normalized, e);
        }
        if (!roots.contains(normalized)) {
            roots.add(normalized);
            realRoots.add(real);
        }
    }

    /**
     * Finds the first existing, permitted relative path across roots. Missing paths still
     * pass the same boundary check before being returned for the caller's not-found error.
     * Only explicit absolute paths may use the system/temp read exceptions.
     */
    public Path resolveForRead(String pathStr) {
        Path candidate = Path.of(pathStr == null || pathStr.isBlank() ? "." : pathStr);
        if (candidate.isAbsolute()) return validate(candidate, true);
        for (Path root : roots) {
            try {
                Path resolved = validate(root.resolve(candidate), false);
                if (Files.exists(resolved)) return resolved;
            } catch (SecurityException ignored) {
                // Another configured root may contain a valid match. The primary-root
                // fallback below must validate again, never return an unchecked path.
            }
        }
        return validate(primaryRoot().resolve(candidate), false);
    }

    /** Resolves writable paths against workspace roots only, including real symlink targets. */
    public Path resolve(String pathStr) {
        Path candidate = Path.of(pathStr == null || pathStr.isBlank() ? "." : pathStr);
        return validate(candidate.isAbsolute() ? candidate : primaryRoot().resolve(candidate), false);
    }

    private boolean withinWorkspace(Path path) {
        return roots.stream().anyMatch(path::startsWith)
                || realRoots.stream().anyMatch(path::startsWith);
    }

    private static boolean withinReadException(Path path) {
        return SAFE_READ_PREFIXES.stream().map(Path::of).anyMatch(path::startsWith);
    }

    private Path validate(Path candidate, boolean allowReadExceptions) {
        Path normalized = candidate.toAbsolutePath().normalize();
        boolean workspacePath = withinWorkspace(normalized);
        if (!workspacePath && !(allowReadExceptions && withinReadException(normalized))) {
            throw new SecurityException("Path outside workspace: " + normalized);
        }
        // Do not normalize before resolving symlinks: link/.. is evaluated after following
        // the link by the filesystem, and may differ from lexical normalization.
        Path real = realPathForPotentialFile(candidate.toAbsolutePath());
        boolean realInWorkspace = realRoots.stream().anyMatch(real::startsWith);
        if (!realInWorkspace && (workspacePath || !allowReadExceptions || !withinReadException(real))) {
            throw new SecurityException("Path resolves outside workspace: " + normalized + " -> " + real);
        }
        return real;
    }

    /** Resolve the nearest existing ancestor; dangling/inaccessible/cyclic links fail closed. */
    private static Path realPathForPotentialFile(Path candidate) {
        Path ancestor = candidate;
        Deque<Path> missing = new ArrayDeque<>();
        try {
            while (true) {
                try {
                    Files.readAttributes(ancestor, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                    break;
                } catch (NoSuchFileException e) {
                    if (ancestor.getParent() == null) throw e;
                    missing.addFirst(ancestor.getFileName());
                    ancestor = ancestor.getParent();
                }
            }
            Path real = ancestor.toRealPath();
            if (!missing.isEmpty() && !Files.isDirectory(real)) {
                throw new IOException("Ancestor is not a directory: " + ancestor);
            }
            for (Path component : missing) real = real.resolve(component);
            return real.normalize();
        } catch (IOException e) {
            throw new SecurityException("Cannot safely resolve path: " + candidate, e);
        }
    }

    /**
     * Returns the path relative to its containing workspace root, for display.
     * Falls back to the absolute path if no root matches.
     */
    public String displayPath(Path absolute) {
        Path normalized = absolute.toAbsolutePath().normalize();
        for (Path root : roots) {
            Path realRoot = realRoots.get(roots.indexOf(root));
            Path containingRoot = normalized.startsWith(root) ? root : realRoot;
            if (normalized.startsWith(containingRoot)) {
                Path rel = containingRoot.relativize(normalized);
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
        try {
            Path candidate = path.isAbsolute() ? path : primaryRoot().resolve(path);
            return Files.exists(validate(candidate, false));
        } catch (SecurityException e) {
            return false;
        }
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
