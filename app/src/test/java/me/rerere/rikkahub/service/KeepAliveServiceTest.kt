package me.rerere.rikkahub.service

import android.app.Application
import android.app.Notification
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class KeepAliveServiceTest {
    @Test fun timeoutStopsAnAlreadyForegroundLegacyInstanceImmediately() {
        val controller = Robolectric.buildService(KeepAliveService::class.java).create()
        val service = controller.get()
        try {
            // Simulate an already-running legacy instance receiving the platform callback.
            service.startForeground(30001, Notification.Builder(service, "keep_alive_channel")
                .setSmallIcon(android.R.drawable.ic_dialog_info).build(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            assertFalse(shadowOf(service).isForegroundStopped)
            service.onTimeout(1, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            assertTrue(shadowOf(service).isStoppedBySelf)
            assertTrue(shadowOf(service).isForegroundStopped)
            assertEquals(Service.START_NOT_STICKY, service.onStartCommand(null, 0, 3))
            // Task removal after the timeout must not enqueue another restart.
            service.onTaskRemoved(null)
            val app = RuntimeEnvironment.getApplication()
            assertFalse(shadowOf(app).broadcastIntents.any {
                it.action == KeepAliveService.ACTION_RESTART_KEEP_ALIVE
            })
        } finally { controller.destroy() }
    }

    @Test fun timeoutGuardSurvivesServiceRecreationAndBlocksTheRestartReceiver() {
        val app = RuntimeEnvironment.getApplication()
        val first = Robolectric.buildService(KeepAliveService::class.java).create()
        first.get().onTimeout(1, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        first.destroy()
        val recreated = Robolectric.buildService(KeepAliveService::class.java).create()
        try {
            assertEquals(Service.START_NOT_STICKY, recreated.get().onStartCommand(null, 0, 2))
            assertTrue(shadowOf(recreated.get()).isStoppedBySelf)
            KeepAliveRestartReceiver().onReceive(app, Intent(KeepAliveService.ACTION_RESTART_KEEP_ALIVE))
            assertNull(shadowOf(app).nextStartedService)
        } finally { recreated.destroy() }
    }

    @Test fun android15DoesNotStartIdleDataSyncEvenFromTheForeground() {
        val app = RuntimeEnvironment.getApplication()
        assertFalse(KeepAliveService.isSupported())
        KeepAliveService.start(app, fromUserForeground = true)
        assertNull(shadowOf(app).nextStartedService)
        val legacyRestart = Robolectric.buildService(KeepAliveService::class.java).create()
        try {
            assertEquals(Service.START_NOT_STICKY, legacyRestart.get().onStartCommand(null, 0, 2))
            assertTrue(shadowOf(legacyRestart.get()).isStoppedBySelf)
            assertNull(shadowOf(legacyRestart.get()).lastForegroundNotification)
        } finally { legacyRestart.destroy() }
    }

    @Test fun disablingKeepAliveRejectsAnAlreadyQueuedRestartBroadcast() {
        val app = RuntimeEnvironment.getApplication()
        KeepAliveService.stop(app)
        KeepAliveRestartReceiver().onReceive(app, Intent(KeepAliveService.ACTION_RESTART_KEEP_ALIVE))
        assertNull(shadowOf(app).nextStartedService)
    }

    @Test
    @Config(sdk = [28, 34])
    fun olderAndroidStillStartsTheForegroundNotification() {
        val controller = Robolectric.buildService(KeepAliveService::class.java).create()
        try {
            val service = controller.get()
            assertTrue(KeepAliveService.isSupported())
            assertEquals(Service.START_STICKY, service.onStartCommand(null, 0, 1))
            assertNotNull(shadowOf(service).lastForegroundNotification)
        } finally { controller.destroy() }
    }

    @Test
    @Config(sdk = [28])
    fun olderAndroidAllowsUserReenableButNotQueuedBackgroundRestarts() {
        val app = RuntimeEnvironment.getApplication()
        KeepAliveService.stop(app)
        KeepAliveRestartReceiver().onReceive(app, Intent(KeepAliveService.ACTION_RESTART_KEEP_ALIVE))
        assertNull(shadowOf(app).nextStartedService)
        KeepAliveService.start(app, fromUserForeground = true)
        assertEquals(KeepAliveService::class.java.name, shadowOf(app).nextStartedService?.component?.className)
    }
}
