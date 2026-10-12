package com.stevedaydream.autodora

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class VisionTeacherSettings(context: Context) {
    val prefs = context.getSharedPreferences("VisionTeacher", Context.MODE_PRIVATE)
    var enabled: Boolean
        get() = prefs.getBoolean("enabled", false)
        set(value) { prefs.edit().putBoolean("enabled", value).apply() }
    var endpoint: String
        get() = prefs.getString("endpoint", "") ?: ""
        set(value) { prefs.edit().putString("endpoint", value.trim().trimEnd('/')).apply() }
    var model: String
        get() = prefs.getString("model", "") ?: ""
        set(value) { prefs.edit().putString("model", value.trim()).apply() }
    var geminiModel: String
        get() = prefs.getString("geminiModel", "") ?: ""
        set(value) { prefs.edit().putString("geminiModel", value.trim().removePrefix("models/")).apply() }
    var cloudEnabled: Boolean
        get() = prefs.getBoolean("cloudEnabled", false)
        set(value) { prefs.edit().putBoolean("cloudEnabled", value).apply() }
    var game: GameProfile
        get() = runCatching { GameProfile.valueOf(prefs.getString("game", "TOS")!!) }.getOrDefault(GameProfile.TOS)
        set(value) { prefs.edit().putString("game", value.name).apply() }

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey("AutoDoraVision", null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder("AutoDoraVision", KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }

    fun setSecret(name: String, value: String) {
        if (value.isBlank()) { prefs.edit().remove(name).apply(); return }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        val encrypted = cipher.doFinal(value.trim().toByteArray(Charsets.UTF_8))
        prefs.edit().putString(name, Base64.encodeToString(cipher.iv + encrypted, Base64.NO_WRAP)).apply()
    }

    fun secret(name: String): String {
        val value = prefs.getString(name, null) ?: return ""
        return runCatching {
            val bytes = Base64.decode(value, Base64.NO_WRAP)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
                init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
            }
            String(cipher.doFinal(bytes.copyOfRange(12, bytes.size)), Charsets.UTF_8)
        }.getOrDefault("")
    }
}
