package app.droidnux.desktop

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * The desktop's Linux account: its name is in [DesktopSettings.user], its
 * password here, encrypted with a key Android's keystore keeps. The session
 * gets the password at every start: it is the user's (and sudo's) password,
 * and it opens KDE's wallet as logging in to Plasma would.
 */
object Account {
    private const val KEY_ALIAS = "droidnux-account"
    private const val FILE = "account-password.bin"
    /** The password before [changePassword], until the session moves the wallet to the new one. */
    private const val BEFORE_FILE = "account-password-before.bin"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"

    private val NAME = Regex("[a-z_][a-z0-9_-]{0,31}")
    /** Users Debian has already, or that would mean something else. */
    private val RESERVED = setOf(
        "root", "daemon", "bin", "sys", "sync", "games", "man", "lp", "mail", "news", "uucp", "proxy",
        "www-data", "backup", "list", "irc", "_apt", "nobody", "messagebus", "polkitd", "sddm", "pipewire",
    )

    fun isValidName(name: String) = NAME.matches(name) && name !in RESERVED && !name.startsWith("systemd-")

    fun isValidPassword(password: String) = password.isNotEmpty() && password.none { it == '\n' || it == '\r' }

    fun hasPassword(context: Context) = File(context.filesDir, FILE).isFile

    fun savePassword(context: Context, password: String) = seal(context, FILE, password)

    /**
     * A new password for an account that has one: the session makes it the
     * account's, and moves KDE's wallet to it with [passwordBefore].
     */
    fun changePassword(context: Context, password: String) {
        // Changed again before a session: the wallet still has the first one.
        if (passwordBefore(context) == null) password(context)?.let { seal(context, BEFORE_FILE, it) }
        seal(context, FILE, password)
    }

    /** Null when none was set, or the keystore lost its key (the app's data was restored elsewhere). */
    fun password(context: Context): String? = unseal(context, FILE)

    /** The password KDE's wallet opens with, when [changePassword] made a new one since. */
    fun passwordBefore(context: Context): String? = unseal(context, BEFORE_FILE)

    /** The session moved the wallet to [password]: the current one, unless it changed again meanwhile. */
    fun walletMoved(context: Context, password: String) {
        if (password(context) == password) File(context.filesDir, BEFORE_FILE).delete() else seal(context, BEFORE_FILE, password)
    }

    private fun seal(context: Context, name: String, password: String) {
        val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, key()) }
        val sealed = cipher.doFinal(password.toByteArray())
        val file = File(context.filesDir, name)
        val temp = File(context.filesDir, "$name.new")
        temp.outputStream().use {
            it.write(cipher.iv.size)
            it.write(cipher.iv)
            it.write(sealed)
        }
        temp.renameTo(file)
    }

    private fun unseal(context: Context, name: String): String? {
        val bytes = File(context.filesDir, name).takeIf { it.isFile }?.readBytes() ?: return null
        return runCatching {
            val ivSize = bytes[0].toInt()
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes, 1, ivSize))
            String(cipher.doFinal(bytes, 1 + ivSize, bytes.size - 1 - ivSize))
        }.getOrNull()
    }

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(
                KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .build(),
            )
        }.generateKey()
    }
}
