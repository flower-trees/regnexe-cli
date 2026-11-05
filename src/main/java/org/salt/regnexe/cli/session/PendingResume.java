package org.salt.regnexe.cli.session;

/** The exact task shown to the user; never reselect the latest task on execution. */
public record PendingResume(String sessionUuid, String taskId) {
    public void checkSession(String actualUuid) {
        if (!sessionUuid.equals(actualUuid)) throw new IllegalStateException("Resume session changed; run /resume again");
    }
}
