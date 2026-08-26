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
    /** Asks the user to confirm an action described by {@code verb} (e.g. "run", "apply"). */
    ConfirmChoice confirm(String verb);
    void tokenSummary(String json);
    void interruptPausing();
    void secondInterrupt();
    void paused(String sessionName);
    void warning(String message);
    void error(String message);
    void goodbye();
}
