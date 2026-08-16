package com.miyakz.worklog

import android.app.Application
import com.miyakz.worklog.data.local.AppDatabase
import com.miyakz.worklog.data.remote.ServerDiscovery
import com.miyakz.worklog.data.repository.RecordRepository
import com.miyakz.worklog.data.repository.SyncRepository

/**
 * Wires the app's small dependency graph by hand. The graph is tiny
 * (one DB, two repositories) so a DI framework would add more ceremony
 * than it saves.
 */
class WorkLogApp : Application() {

    lateinit var recordRepository: RecordRepository
        private set
    lateinit var syncRepository: SyncRepository
        private set

    override fun onCreate() {
        super.onCreate()
        val database = AppDatabase.getInstance(this)
        recordRepository = RecordRepository(database.recordDao())
        syncRepository = SyncRepository(
            recordDao = database.recordDao(),
            syncStateDao = database.syncStateDao(),
            discovery = ServerDiscovery(this),
        )
    }
}
