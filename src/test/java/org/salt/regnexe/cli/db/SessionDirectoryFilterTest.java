package org.salt.regnexe.cli.db;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.salt.regnexe.cli.session.SessionRow;
import java.nio.file.*;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import static org.junit.Assert.*;

public class SessionDirectoryFilterTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();
    private void save(RexDatabase db, String name, String directory) throws Exception {
        var row = new SessionRow(); row.setSessionId(UUID.randomUUID().toString()); row.setName(name);
        row.setWorkingDir(directory); row.setCreatedAt(1); row.setUpdatedAt(1); db.findOrCreateSession(row);
    }
    @Test public void includesCanonicalAliasesButNotOtherProjectsOrChildDirectories() throws Exception {
        Path root = temporary.getRoot().toPath();
        Path project = Files.createDirectory(root.resolve("project"));
        Path child = Files.createDirectory(project.resolve("child"));
        Path other = Files.createDirectory(root.resolve("other"));
        Path alias = Files.createSymbolicLink(root.resolve("alias"), project);
        try (var db = new RexDatabase(root.resolve("rex.db"))) {
            save(db, "project", project.toString()); save(db, "alias", alias.toString());
            save(db, "normalized", project.resolve(".").toString());
            save(db, "child", child.toString()); save(db, "other", other.toString());
            var names = db.listSessions(alias).stream().map(SessionRow::getName).collect(Collectors.toSet());
            assertEquals(Set.of("project", "alias", "normalized"), names);
            assertEquals(5, db.listSessions().size());
            assertTrue(db.findSessionByName("other").isPresent());
        }
    }
    @Test public void emptyAndInvalidStoredDirectoriesAreNotMistakenForCurrentProject() throws Exception {
        Path root = temporary.getRoot().toPath();
        try (var db = new RexDatabase(root.resolve("rex.db"))) {
            save(db, "current", root.toString()); save(db, "empty", ""); save(db, "invalid", "bad\u0000path");
            assertEquals(java.util.List.of("current"), db.listSessions(root).stream().map(SessionRow::getName).toList());
        }
    }
}
