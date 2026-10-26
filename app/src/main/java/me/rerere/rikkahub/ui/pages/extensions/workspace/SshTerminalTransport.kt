package me.rerere.rikkahub.ui.pages.extensions.workspace

import com.termux.terminal.TerminalTransport
import me.rerere.workspace.ssh.SshShell
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicBoolean

/** Small stream boundary shared by SSH and the terminal lifecycle tests. */
internal interface SshTerminalHandle : Closeable {
    val inputStream: InputStream
    val outputStream: OutputStream
    val connectionLost: Boolean
    fun resize(columns: Int, rows: Int)
    fun waitFor(): Int?
}

internal fun SshShell.asTerminalHandle(): SshTerminalHandle = object : SshTerminalHandle {
    override val inputStream get() = this@asTerminalHandle.inputStream
    override val outputStream get() = this@asTerminalHandle.outputStream
    override val connectionLost get() = this@asTerminalHandle.connectionLost
    override fun resize(columns: Int, rows: Int) = this@asTerminalHandle.resize(columns, rows)
    override fun waitFor() = this@asTerminalHandle.waitFor()
    override fun close() = this@asTerminalHandle.close()
}

/** Network control never runs on the terminal view's main thread. */
internal class SshTerminalTransport(
    private val address: String,
    private val openShell: (columns: Int, rows: Int) -> SshTerminalHandle,
) : TerminalTransport {
    private val control = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "ssh-terminal-control").apply { isDaemon = true }
    }
    private val lock = Any()
    private val stopped = AtomicBoolean(false)
    private val closed = AtomicBoolean(false)
    @Volatile private var shell: SshTerminalHandle? = null
    @Volatile private var startingThread: Thread? = null
    @Volatile private var remoteExitStatus: Int? = null
    @Volatile private var connectionLost = false

    override fun start(columns: Int, rows: Int) {
        synchronized(lock) {
            if (stopped.get()) throw IOException("Terminal closed")
            startingThread = Thread.currentThread()
        }
        try {
            val opened = openShell(columns.coerceAtLeast(1), rows.coerceAtLeast(1))
            val accepted = synchronized(lock) {
                if (stopped.get()) false else { shell = opened; true }
            }
            if (!accepted) {
                opened.close()
                throw IOException("Terminal closed")
            }
        } catch (error: InterruptedException) {
            Thread.currentThread().interrupt()
            throw IOException("Terminal closed", error)
        } finally {
            startingThread = null
        }
    }

    override fun getInputStream(): InputStream = requireShell().inputStream
    override fun getOutputStream(): OutputStream = requireShell().outputStream

    override fun setWindowSize(columns: Int, rows: Int) {
        val current = shell ?: return
        runControl { if (!stopped.get()) current.resize(columns.coerceAtLeast(1), rows.coerceAtLeast(1)) }
    }

    override fun waitFor(): Int {
        val current = requireShell()
        return try {
            val status = current.waitFor()
            remoteExitStatus = status
            connectionLost = status == null && current.connectionLost
            status ?: -1
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            -1
        }
    }

    override fun kill() {
        stopped.set(true)
        startingThread?.interrupt()
        val current = shell ?: return
        runControl { current.close() }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        stopped.set(true)
        startingThread?.interrupt()
        val current = shell
        runControl { current?.close() }
        control.shutdown()
    }

    override fun describeStart(): String = "Connecting to $address..."
    override fun describeExit(exitStatus: Int): String = when {
        remoteExitStatus != null -> super.describeExit(exitStatus)
        connectionLost -> "Connection lost"
        else -> "Session closed"
    }

    private fun requireShell(): SshTerminalHandle = shell ?: error("SSH shell is not started")

    private fun runControl(block: () -> Unit) {
        try {
            control.execute { runCatching(block) }
        } catch (_: RejectedExecutionException) {
            // A completed close has already scheduled the final channel cleanup.
        }
    }
}
