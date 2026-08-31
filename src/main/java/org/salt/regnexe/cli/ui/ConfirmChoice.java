package org.salt.regnexe.cli.ui;

public enum ConfirmChoice {
    YES,
    NO,
    PAUSE,
    /**
     * Same as YES for this call, but also tells the renderer to remember the confirmation key
     * for the rest of the process — later confirm() calls with the same key skip straight to
     * YES without prompting. Not persisted to disk; resets on restart.
     */
    ALWAYS
}
