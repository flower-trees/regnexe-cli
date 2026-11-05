package org.salt.regnexe.cli.session;

import lombok.Getter;
import org.salt.regnexe.cli.tools.WorkspaceContext;
import java.io.IOException;
import java.nio.file.Path;

/** The current open session. Ownership survives idle prompts and task completion. */
public class SessionContext implements AutoCloseable {
    @Getter private String sessionId;
    @Getter private String sessionName;
    @Getter private WorkspaceContext workspace;
    private SessionExecutionLock ownership;

    public SessionContext(String sessionId, String sessionName, WorkspaceContext workspace) {
        this.sessionId = sessionId;
        this.sessionName = sessionName;
        this.workspace = workspace;
    }

    public synchronized void open(Path lockDirectory) throws IOException {
        if (ownership == null) ownership = SessionExecutionLock.acquire(lockDirectory, sessionId);
    }

    /** Acquire the destination first. A busy destination leaves the current session intact. */
    public synchronized void switchTo(SessionContext destination, Path lockDirectory) throws IOException {
        if (sessionId.equals(destination.sessionId)) {
            open(lockDirectory);
            return;
        }
        destination.open(lockDirectory);
        try {
            close();
        } catch (IOException e) {
            destination.close();
            throw e;
        }
        sessionId = destination.sessionId;
        sessionName = destination.sessionName;
        workspace = destination.workspace;
        ownership = destination.ownership;
        destination.ownership = null;
    }

    public synchronized AutoCloseable retainExecution() {
        if (ownership == null) throw new IllegalStateException("Session is not open");
        return ownership.retain();
    }

    @Override
    public synchronized void close() throws IOException {
        if (ownership != null) {
            ownership.close();
            ownership = null;
        }
    }
}
