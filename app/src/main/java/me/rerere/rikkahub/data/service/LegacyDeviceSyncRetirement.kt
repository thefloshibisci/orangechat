package me.rerere.rikkahub.data.service

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent

/**
 * Retire only the old Supabase uploader. ScreenTriggerFamily and ContextProvider own
 * workflow screen events/conditions independently and must remain active.
 */
internal fun retireLegacyDeviceSync(context: Context) {
    val intent = Intent("me.rerere.orangechat.SUPABASE_SYNC")
        .setClassName(context.packageName, "me.rerere.rikkahub.data.service.SupabaseSyncReceiver")
    PendingIntent.getBroadcast(
        context, 10002, intent, PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE,
    )?.let {
        context.getSystemService(AlarmManager::class.java)?.cancel(it)
        it.cancel()
    }
    listOf("SupabaseSyncService", "DeviceEventTrackingService").forEach {
        context.stopService(Intent().setClassName(context.packageName, "me.rerere.rikkahub.data.service.$it"))
    }
    context.getSharedPreferences("supabase_sync_prefs", Context.MODE_PRIVATE).edit().clear().apply()
}
