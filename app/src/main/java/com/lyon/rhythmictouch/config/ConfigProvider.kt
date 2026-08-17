package com.lyon.rhythmictouch.config

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.Bundle

class ConfigProvider : ContentProvider() {

    private var store: ConfigStore? = null
    private var profileStore: ProfileStore? = null

    override fun onCreate(): Boolean {
        val appCtx = context?.applicationContext
        store = appCtx?.let { ConfigStore(it) }
        profileStore = appCtx?.let { ProfileStore(it) }
        return store != null
    }

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        if (method == "get_config") {
            val config = store?.read() ?: RhythmicConfig()
            val profiles = profileStore?.readProfiles() ?: emptyList()
            val activeProfile = profileStore?.getActive()
            val params = activeProfile?.params ?: VibrationParams.defaults()
            return config.copy(
                vibrationParams = params,
                profiles = profiles,
                activeProfileId = profileStore?.readActiveId() ?: VibrationProfile.DEFAULT_ID,
            ).toBundle()
        }
        return null
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? = null

    override fun getType(uri: Uri): String? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
}
