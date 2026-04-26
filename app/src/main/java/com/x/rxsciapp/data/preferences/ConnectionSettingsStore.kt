package com.x.rxsciapp.data.preferences

import android.content.Context
import android.os.Build
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.x.rxsciapp.model.ConnectionSettings
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "mobile_settings")

class ConnectionSettingsStore(private val context: Context) {
    private val baseUrlKey = stringPreferencesKey("base_url")
    private val tokenKey = stringPreferencesKey("token")
    private val clientIdKey = stringPreferencesKey("client_id")
    private val deviceNameKey = stringPreferencesKey("device_name")

    val settings: Flow<ConnectionSettings> = context.dataStore.data.map { prefs ->
        val generatedClientId = prefs[clientIdKey].orEmpty().ifBlank {
            UUID.randomUUID().toString()
        }
        val generatedDeviceName = prefs[deviceNameKey].orEmpty().ifBlank {
            "${Build.MANUFACTURER} ${Build.MODEL}"
        }
        ConnectionSettings(
            baseUrl = prefs[baseUrlKey].orEmpty(),
            token = prefs[tokenKey].orEmpty(),
            clientId = generatedClientId,
            deviceName = generatedDeviceName,
        )
    }

    suspend fun save(settings: ConnectionSettings) {
        context.dataStore.edit { prefs ->
            prefs[baseUrlKey] = settings.baseUrl.trim()
            prefs[tokenKey] = settings.token.trim()
            prefs[clientIdKey] = settings.clientId.trim()
            prefs[deviceNameKey] = settings.deviceName.trim()
        }
    }
}
