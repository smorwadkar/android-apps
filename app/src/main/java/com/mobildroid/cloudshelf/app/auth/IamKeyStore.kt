package com.mobildroid.cloudshelf.app.auth

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * EncryptedSharedPreferences-backed store for the user's IAM access key.
 *
 * Encryption is AES-256-GCM with the master key in Android Keystore
 * (StrongBox if available). The prefs file is excluded from cloud backup
 * and device-to-device transfer by res/xml/backup_rules.xml.
 *
 * NOTE: EncryptedSharedPreferences is marked deprecated upstream; it still
 * works and is the path of least resistance for now. Phase >1 may migrate
 * to a Keystore-backed DataStore wrapper if Google publishes one.
 */
@Singleton
class IamKeyStore @Inject constructor(@ApplicationContext context: Context) {

    private val prefs: SharedPreferences by lazy {
        val masterKey = MasterKey.Builder(context.applicationContext)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context.applicationContext,
            PREFS_FILE,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    fun save(creds: CloudShelfCredentials.IamKey) {
        prefs.edit()
            .putString(KEY_ACCESS_KEY, creds.accessKeyId)
            .putString(KEY_SECRET, creds.secretAccessKey)
            .putString(KEY_SESSION_TOKEN, creds.sessionToken)
            .putString(KEY_ACCOUNT_ID, creds.accountId)
            .putString(KEY_ARN, creds.arn)
            .putString(KEY_REGION, creds.defaultRegion)
            .apply()
    }

    fun load(): CloudShelfCredentials.IamKey? {
        val accessKey = prefs.getString(KEY_ACCESS_KEY, null) ?: return null
        val secret = prefs.getString(KEY_SECRET, null) ?: return null
        val region = prefs.getString(KEY_REGION, null) ?: return null
        return CloudShelfCredentials.IamKey(
            accessKeyId = accessKey,
            secretAccessKey = secret,
            sessionToken = prefs.getString(KEY_SESSION_TOKEN, null),
            accountId = prefs.getString(KEY_ACCOUNT_ID, null),
            arn = prefs.getString(KEY_ARN, null),
            defaultRegion = region
        )
    }

    fun clear() {
        prefs.edit().clear().apply()
    }

    private companion object {
        const val PREFS_FILE = "cloudshelf_secure_prefs"
        const val KEY_ACCESS_KEY = "access_key_id"
        const val KEY_SECRET = "secret_access_key"
        const val KEY_SESSION_TOKEN = "session_token"
        const val KEY_ACCOUNT_ID = "account_id"
        const val KEY_ARN = "arn"
        const val KEY_REGION = "default_region"
    }
}
