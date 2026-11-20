package app.kuroka.host.secure

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/*
 * The tokens of a sign-in, kept where no other app and no backup can read them: a key that never
 * leaves the phone's keystore, and one file per secret holding a random nonce and the ciphertext.
 * The library and the settings stay in plain JSON, since nothing in them is a secret.
 *
 * This file is part of Kuroka (GPL-3.0, see ../../../LICENSE).
 */

class SecureStore(context: Context) {

    private val dir = File(context.filesDir, "secure").apply { if (!exists()) mkdirs() }

    private val key: SecretKey by lazy { load() }

    private fun load(): SecretKey {
        val store = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (store.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setRandomizedEncryptionRequired(true)
                .build()
        )
        return generator.generateKey()
    }

    /** Saves [value] under [name], replacing what was there. */
    fun put(name: String, value: String) {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key)
        val iv = cipher.iv
        val body = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        val out = ByteArray(iv.size + body.size)
        System.arraycopy(iv, 0, out, 0, iv.size)
        System.arraycopy(body, 0, out, iv.size, body.size)
        file(name).writeBytes(out)
    }

    /** What was saved under [name], or null when there is nothing (or it cannot be read). */
    fun get(name: String): String? {
        val f = file(name)
        if (!f.exists()) return null
        return runCatching {
            val bytes = f.readBytes()
            if (bytes.size <= GCM_IV_LENGTH) return@runCatching null
            val iv = bytes.copyOfRange(0, GCM_IV_LENGTH)
            val body = bytes.copyOfRange(GCM_IV_LENGTH, bytes.size)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
            String(cipher.doFinal(body), Charsets.UTF_8)
        }.getOrNull()
    }

    fun remove(name: String) {
        runCatching { file(name).delete() }
    }

    fun clear() {
        dir.listFiles()?.forEach { runCatching { it.delete() } }
    }

    /** A secret as an Int (a user id kept next to its token). */
    fun putInt(name: String, value: Int) = put(name, value.toString())

    fun getInt(name: String): Int? = get(name)?.toIntOrNull()

    private fun file(name: String) = File(dir, name.filter { it.isLetterOrDigit() || it == '-' || it == '_' })

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val ALIAS = "kuroka.account"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val GCM_IV_LENGTH = 12
        const val GCM_TAG_BITS = 128
    }
}
