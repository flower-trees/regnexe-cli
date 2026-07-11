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
    void bashConfirmPrompt();
    void tokenSummary(String json);
    void interruptPausing();
    void secondInterrupt();
    void paused(String sessionName);
    void warning(String message);
    void error(String message);
    void goodbye();
}
