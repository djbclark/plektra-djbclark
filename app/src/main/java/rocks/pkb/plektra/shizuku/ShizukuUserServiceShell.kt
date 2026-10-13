package rocks.pkb.plektra.shizuku

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import moe.shizuku.manager.adb.CLIENT_DEADLINE_MS
import moe.shizuku.manager.adb.MAX_SHELL_OUTPUT_BYTES
import rikka.shizuku.Shizuku
import rocks.pkb.plektra.BuildConfig
import rocks.pkb.plektra.inputmethod.ShellRunResult
import rocks.pkb.plektra.inputmethod.ShizukuShell
import rocks.pkb.plektra.inputmethod.ShizukuState
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * plk/shizuku-backend: the real [ShizukuShell]. Binds [PrivilegedShellService] through
 * `Shizuku.bindUserService` (the supported API; `Shizuku.newProcess` is hidden in API 13) and
 * calls it once per command line. The service enforces the output limit and the deadline; this
 * side waits at most the deadline plus [CALL_SLACK_MS] for an answer and then kills the service,
 * so a stuck call cannot hold the broker's lock.
 *
 * Works with any Shizuku server that speaks the Shizuku API (Shizuku, ShizukuTendCF, Sui).
 */
internal object ShizukuUserServiceShell : ShizukuShell {

    private const val TAG = "ShizukuShell"

    /** Shizuku's manager app; ShizukuTendCF keeps the same package name. */
    const val SHIZUKU_PACKAGE = "moe.shizuku.privileged.api"

    /** How long a cold start of the service (a new process) may take. */
    private const val BIND_TIMEOUT_MS = 10_000L

    /** Extra wait beyond the service's own deadline, for the binder round trip. */
    private const val CALL_SLACK_MS = 5_000L

    @Volatile
    private var service: IPrivilegedShellService? = null

    @Volatile
    private var connecting: CountDownLatch? = null

    @Volatile
    private var boundArgs: Shizuku.UserServiceArgs? = null

    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }

    /** Calls run here so the caller can stop waiting; a stuck call ends when the service is killed. */
    private val calls = Executors.newCachedThreadPool { r ->
        Thread(r, "shizuku-shell-call").apply { isDaemon = true }
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            if (binder != null && binder.pingBinder()) {
                service = IPrivilegedShellService.Stub.asInterface(binder)
            }
            connecting?.countDown()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
        }
    }

    override fun state(context: Context): ShizukuState {
        val alive = runCatching { Shizuku.pingBinder() }.getOrDefault(false)
        if (alive) {
            val allowed = runCatching {
                !Shizuku.isPreV11() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
            }.getOrDefault(false)
            return if (allowed) ShizukuState.READY else ShizukuState.NOT_ALLOWED
        }
        return if (isInstalled(context)) ShizukuState.NOT_RUNNING else ShizukuState.NOT_INSTALLED
    }

    private fun isInstalled(context: Context): Boolean = runCatching {
        context.packageManager.getPackageInfo(SHIZUKU_PACKAGE, 0)
        true
    }.getOrDefault(false)

    /**
     * Shows Shizuku's "allow Plektra" prompt. Call it from a tap only. False when Shizuku is not
     * running (or too old to ask), so there is nothing to ask.
     */
    fun requestPermission(requestCode: Int): Boolean = runCatching {
        if (!Shizuku.pingBinder() || Shizuku.isPreV11()) return false
        Shizuku.requestPermission(requestCode)
        true
    }.getOrDefault(false)

    override fun runLine(context: Context, commandLine: String): ShellRunResult {
        if (state(context) != ShizukuState.READY) {
            return ShellRunResult.unavailable("Shizuku is not running or Plektra is not allowed.")
        }
        val shell = connect(context.applicationContext)
            ?: return ShellRunResult.unavailable("Shizuku did not start Plektra's shell service.")
        val call = calls.submit<Bundle> { shell.run(commandLine, MAX_SHELL_OUTPUT_BYTES, CLIENT_DEADLINE_MS) }
        return try {
            ShellRunResult.fromBundle(call.get(CLIENT_DEADLINE_MS + CALL_SLACK_MS, TimeUnit.MILLISECONDS), MAX_SHELL_OUTPUT_BYTES)
        } catch (_: TimeoutException) {
            call.cancel(true)
            disconnect()
            ShellRunResult(false, null, null, CappedProcessRunner.deadlineFailure(CLIENT_DEADLINE_MS))
        } catch (e: ExecutionException) {
            // The service died (DeadObjectException) or threw: drop it, the next call binds again.
            val cause = e.cause ?: e
            Log.w(TAG, "Shizuku shell call failed", cause)
            service = null
            ShellRunResult(false, null, null, "${cause.javaClass.simpleName}: ${cause.message}")
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            ShellRunResult(false, null, null, "interrupted")
        }
    }

    private fun args(context: Context) = Shizuku.UserServiceArgs(
        ComponentName(context.packageName, PrivilegedShellService::class.java.name)
    )
        .daemon(false)
        .tag("privileged_shell")
        .processNameSuffix("privileged_shell")
        .version(BuildConfig.VERSION_CODE)

    /** The bound service, binding it first if needed. BLOCKING, up to [BIND_TIMEOUT_MS]. */
    private fun connect(context: Context): IPrivilegedShellService? {
        service?.takeIf { it.asBinder().pingBinder() }?.let { return it }
        service = null
        val latch = CountDownLatch(1)
        connecting = latch
        val args = args(context)
        boundArgs = args
        // Shizuku delivers the connection on the main thread; bind from there too.
        mainHandler.post {
            runCatching { Shizuku.bindUserService(args, connection) }
                .onFailure {
                    Log.w(TAG, "bindUserService failed", it)
                    latch.countDown()
                }
        }
        try {
            latch.await(BIND_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        return service?.takeIf { it.asBinder().pingBinder() }
    }

    /** Unbinds and kills the service process (a stuck command dies with it). */
    private fun disconnect() {
        val args = boundArgs ?: return
        service = null
        mainHandler.post { runCatching { Shizuku.unbindUserService(args, connection, true) } }
    }
}
