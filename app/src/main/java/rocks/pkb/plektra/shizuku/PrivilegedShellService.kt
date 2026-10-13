package rocks.pkb.plektra.shizuku

import android.content.Context
import android.os.Bundle
import androidx.annotation.Keep
import moe.shizuku.manager.adb.CLIENT_DEADLINE_MS
import moe.shizuku.manager.adb.MAX_SHELL_OUTPUT_BYTES
import rocks.pkb.plektra.inputmethod.ShellRunResult
import java.io.IOException
import kotlin.system.exitProcess

/**
 * plk/shizuku-backend: the Shizuku UserService. Shizuku starts it in its own process, as Shizuku's
 * user (shell when Shizuku was started over ADB), from Plektra's APK, and hands its binder to
 * Plektra only. It runs one command line per call with `sh -c`, as adbd's `shell:` service does
 * for the broker, under the broker's limits: [MAX_SHELL_OUTPUT_BYTES] of output and
 * [CLIENT_DEADLINE_MS] per command, whatever the caller asks for.
 *
 * Which lines reach it is decided in the app process, by the broker's door
 * ([rocks.pkb.plektra.inputmethod.EmbeddedAdbShell.execute]); this class only runs them.
 */
class PrivilegedShellService : IPrivilegedShellService.Stub {

    /** Shizuku before API 13 calls the no-argument constructor. */
    @Keep
    constructor() : super()

    /** Shizuku API 13+ passes a Context when the class has this constructor. */
    @Keep
    @Suppress("UNUSED_PARAMETER")
    constructor(context: Context) : super()

    override fun destroy() {
        exitProcess(0)
    }

    override fun run(commandLine: String?, maxOutputBytes: Int, deadlineMs: Long): Bundle {
        val line = commandLine?.takeIf { it.isNotBlank() }
            ?: return ShellRunResult(false, null, null, "empty command line").toBundle()
        val cap = maxOutputBytes.coerceIn(1, MAX_SHELL_OUTPUT_BYTES)
        val deadline = deadlineMs.coerceIn(1L, CLIENT_DEADLINE_MS)
        val process = try {
            ProcessBuilder(SHELL, "-c", line).redirectErrorStream(true).start()
        } catch (e: IOException) {
            return ShellRunResult(false, null, null, "${e.javaClass.simpleName}: ${e.message}").toBundle()
        }
        return CappedProcessRunner.run(process, cap, deadline).toBundle()
    }

    private companion object {
        const val SHELL = "/system/bin/sh"
    }
}
