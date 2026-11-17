package com.nesstation.app.core.storage

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.preferencesDataStoreFile
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Central DataStore-backed settings.
 *
 * CRITICAL: The DataStore is created inside [init], NOT as a top-level
 * property delegate. The previous `by preferencesDataStore("settings")`
 * delegate was a top-level val that got initialised during class-loading of
 * SettingsRepositoryKt — if the DataStore library was stripped by R8 or the
 * device had a class-loading issue, this threw ExceptionInInitializerError
 * which crashed the entire app at startup. Moving creation into init() makes
 * the failure catchable and non-fatal.
 */
object SettingsRepository {
    private lateinit var appContext: Context
    private var dataStore: DataStore<Preferences>? = null

    fun init(context: Context) {
        appContext = context.applicationContext
        // Create the DataStore explicitly — if this fails it's caught by
        // NesApp.tryInit() and the app still loads.
        dataStore = PreferenceDataStoreFactory.create(
            produceFile = { appContext.preferencesDataStoreFile("settings") }
        )
    }

    private fun ds(): DataStore<Preferences> =
        dataStore ?: throw IllegalStateException("SettingsRepository not initialised")

    private val keyTvMode         = booleanPreferencesKey("tv_mode")
    private val keyRegion         = intPreferencesKey("region") // 0 = NTSC
    private val keySampleRate     = intPreferencesKey("sample_rate")
    private val keyFilter         = intPreferencesKey("video_filter")
    private val keyShowScanlines  = booleanPreferencesKey("show_scanlines")
    private val keyScreenPad      = booleanPreferencesKey("show_screen_pad")
    private val keyFastForwardTap = booleanPreferencesKey("fast_forward_on_tap")
    private val keyLastRomPath    = stringPreferencesKey("last_rom_path")
    private val keyControllerMap  = stringPreferencesKey("controller_map_json")
    private val keyTheme          = stringPreferencesKey("theme") // system/light/dark
    private val keyAudioVolume    = intPreferencesKey("audio_volume") // 0..100

    // All flows are lazy — they defer ds() access to collection time, so
    // they are safe even if init() hasn't run yet (they'll throw only when
    // collected, not when constructed).
    val tvMode: Flow<Boolean>         by lazy { ds().data.map { it[keyTvMode] ?: false } }
    val region: Flow<Int>             by lazy { ds().data.map { it[keyRegion] ?: 0 } }
    val sampleRate: Flow<Int>         by lazy { ds().data.map { it[keySampleRate] ?: 44100 } }
    val videoFilter: Flow<Int>        by lazy { ds().data.map { it[keyFilter] ?: 0 } }
    val showScanlines: Flow<Boolean>  by lazy { ds().data.map { it[keyShowScanlines] ?: false } }
    val showScreenPad: Flow<Boolean>  by lazy { ds().data.map { it[keyScreenPad] ?: true } }
    val fastForwardOnTap: Flow<Boolean> by lazy { ds().data.map { it[keyFastForwardTap] ?: false } }
    val lastRomPath: Flow<String?>    by lazy { ds().data.map { it[keyLastRomPath]?.ifBlank { null } } }
    val controllerMapJson: Flow<String?> by lazy { ds().data.map { it[keyControllerMap]?.ifBlank { null } } }
    val theme: Flow<String>           by lazy { ds().data.map { it[keyTheme] ?: "system" } }
    val audioVolume: Flow<Int>        by lazy { ds().data.map { it[keyAudioVolume] ?: 90 } }

    suspend fun setTvMode(v: Boolean) = ds().edit { it[keyTvMode] = v }
    suspend fun setRegion(v: Int) = ds().edit { it[keyRegion] = v }
    suspend fun setSampleRate(v: Int) = ds().edit { it[keySampleRate] = v }
    suspend fun setVideoFilter(v: Int) = ds().edit { it[keyFilter] = v }
    suspend fun setShowScanlines(v: Boolean) = ds().edit { it[keyShowScanlines] = v }
    suspend fun setShowScreenPad(v: Boolean) = ds().edit { it[keyScreenPad] = v }
    suspend fun setFastForwardOnTap(v: Boolean) = ds().edit { it[keyFastForwardTap] = v }
    suspend fun setLastRomPath(v: String?) = ds().edit { it[keyLastRomPath] = (v ?: "") }
    suspend fun setControllerMapJson(v: String?) = ds().edit { it[keyControllerMap] = (v ?: "") }
    suspend fun setTheme(v: String) = ds().edit { it[keyTheme] = v }
    suspend fun setAudioVolume(v: Int) = ds().edit { it[keyAudioVolume] = v }
}
