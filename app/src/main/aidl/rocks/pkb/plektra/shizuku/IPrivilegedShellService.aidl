package rocks.pkb.plektra.shizuku;

import android.os.Bundle;

/**
 * plk/shizuku-backend: the Shizuku UserService that runs the broker's command lines as Shizuku's
 * user (shell over ADB, root on a rooted phone). Only Plektra holds this binder: Shizuku hands it
 * to the app that bound the service, after the user allowed Plektra in Shizuku.
 */
interface IPrivilegedShellService {
    /** Shizuku's reserved transaction code for destroying a user service. */
    void destroy() = 16777114;

    /**
     * Runs one command line with `sh -c`, stdout and stderr together. The service caps the output
     * at maxOutputBytes and kills the command after deadlineMs (both clamped to the broker's own
     * limits). Returns a ShellRunResult bundle.
     */
    Bundle run(String commandLine, int maxOutputBytes, long deadlineMs) = 1;
}
