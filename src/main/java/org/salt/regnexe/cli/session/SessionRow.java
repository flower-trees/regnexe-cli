package org.salt.regnexe.cli.session;

import lombok.Data;

/**
 * One row in the sessions table.
 */
@Data
public class SessionRow {
    /** UUID — primary key. */
    private String sessionId;
    /** User-visible name (unique). Also used as the key for conversation history. */
    private String name;
    /** Working directory when this session was first created. */
    private String workingDir;
    /** Model string at creation time (for display). */
    private String model;
    private long createdAt;
    private long updatedAt;
}
