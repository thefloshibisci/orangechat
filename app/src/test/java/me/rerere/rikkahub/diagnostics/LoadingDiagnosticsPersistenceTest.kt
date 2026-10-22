package me.rerere.rikkahub.diagnostics

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28])
class LoadingDiagnosticsPersistenceTest {
    @Test fun stalledLocalTaskPersistsStagesProbesAndStacksForPostRestartInspection() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Application>()
        LoadingDiagnostics.clear(context)
        val trace = LoadingDiagnostics.begin(context, "storage.count", timeoutMillis = 25)
        trace.stage("queued on IO")
        try {
            val file = File(context.filesDir, "diagnostics/last-loading-stall.txt")
            withTimeout(5_000) { while (!file.isFile) delay(25) }
            val report = file.readText()
            assertTrue(report.contains("storage.count: queued on IO"))
            assertTrue(report.contains("Dispatcher probes:"))
            assertTrue(report.contains("Thread stacks:"))
            assertTrue(report.contains("coroutine-worker"))
            assertTrue(report.length <= 128 * 1024)
            assertEquals(report, LoadingDiagnostics.read(context))
        } finally { trace.close(); LoadingDiagnostics.clear(context) }
    }
}
