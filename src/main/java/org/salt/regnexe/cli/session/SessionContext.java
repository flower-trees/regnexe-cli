package org.salt.regnexe.cli.session;

import lombok.Data;
import org.salt.regnexe.cli.tools.WorkspaceContext;

/**
 * Mutable session state held by the main REPL loop.
 * Mutated in-place on /switch so the main loop rebuilds the agent without needing a new reference.
 */
@Data
public class SessionContext {
    /** UUID — primary key in the sessions table. */
    private String sessionId;
    /**
     * User-visible name passed to {@code req.setSessionId()}.
     * Conversation and task history retain this name for compatibility; execution locks use UUID.
     */
    private String sessionName;
    /** Workspace root(s) for this session. */
    private WorkspaceContext workspace;

    public SessionContext(String sessionId, String sessionName, WorkspaceContext workspace) {
        this.sessionId = sessionId;
        this.sessionName = sessionName;
        this.workspace = workspace;
    }
}
