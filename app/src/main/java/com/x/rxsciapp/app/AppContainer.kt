package com.x.rxsciapp.app

import android.content.Context
import com.x.rxsciapp.data.local.MobileDatabase
import com.x.rxsciapp.data.preferences.ConnectionSettingsStore
import com.x.rxsciapp.data.remote.MobileRealtimeClient
import com.x.rxsciapp.data.repository.MobileRepository

class AppContainer(context: Context) {
    private val appContext = context.applicationContext
    private val database = MobileDatabase.create(appContext)
    private val settingsStore = ConnectionSettingsStore(appContext)
    private val realtimeClient = MobileRealtimeClient()

    val repository = MobileRepository(
        context = appContext,
        database = database,
        settingsStore = settingsStore,
        realtimeClient = realtimeClient,
    )
}
