package com.notifyforward.app

import android.app.Application
import com.notifyforward.app.data.DbHelper
import com.notifyforward.app.data.Settings
import com.notifyforward.app.engine.DndController
import com.notifyforward.app.service.WatchdogService
import com.notifyforward.app.util.Notices
import com.tencent.mmkv.MMKV
import java.util.concurrent.Executors

class NotifyApp : Application() {

    override fun onCreate() {
        super.onCreate()
        MMKV.initialize(this)
        Notices.ensureChannels(this)
        DndController.restoreIfExpired(this)

        val settings = Settings.get(this)
        com.notifyforward.app.service.ListenerDiagnostics.record(this, "process_started", "name=${if (android.os.Build.VERSION.SDK_INT >= 28) getProcessName() else packageName} build=${android.os.Build.DISPLAY}")
        if (settings.keepAlive || com.notifyforward.app.service.ListenerHealth.authorized(this)) WatchdogService.schedule(this)
        val db = DbHelper.get(this)
        Executors.newSingleThreadExecutor().execute {
            runCatching {
                // Preserve legacy labels before retention cleanup removes their source notifications.
                com.notifyforward.app.engine.LearningStore(this)
                db.deleteOlderThan(settings.retentionDays)
            }
        }
    }
}
