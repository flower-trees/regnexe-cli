package org.salt.regnexe.cli.ui;

import org.salt.regnexe.cli.config.RexConfig;
import org.salt.regnexe.cli.session.SessionContext;

public interface CliRenderer {
    void startup(String version, SessionContext ctx, RexConfig config, String missingApiKeyEnv);
    String prompt(SessionContext ctx);
    void thinking();
    void ready();
    void executing();
    void toolCalled(String text);
    void toolResult(String text);
    void bashCommand(String command);
    /** Preview shown before a write_file confirmation: the target path and content to write. */
    void filePreview(String path, String content, boolean isNew);
    /** Preview shown before an edit_file confirmation: the target path and the replaced text. */
    void editPreview(String path, String oldString, String newString);
    /** Preview shown before an MCP tool-call confirmation: which server/tool and the arguments. */
    void mcpToolPreview(String server, String tool, Object args);
    /**
     * Asks the user to confirm an action described by {@code verb} (e.g. "run", "apply").
     * {@code rememberKey} identifies what an ALWAYS answer applies to (e.g. a tool name) — once
     * the user picks ALWAYS for a given key, later calls with the same key skip the prompt and
     * return YES directly, for the rest of this process (not persisted to disk).
     */
    ConfirmChoice confirm(String verb, String rememberKey);
    void tokenSummary(String json);
    void interruptPausing();
    void secondInterrupt();
    void paused(String sessionName);
    void warning(String message);
    void error(String message);
    void goodbye();
}
