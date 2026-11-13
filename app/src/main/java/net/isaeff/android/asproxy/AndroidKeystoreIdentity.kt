package net.isaeff.android.asproxy

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.jcraft.jsch.Identity
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec

/**
 * Permanent P-256 signing identity. Private key stays inside Android Keystore.
 * The marker is in device-protected storage to detect key loss even before first unlock.
 */
class AndroidKeystoreIdentity private constructor(
    private val privateKey: PrivateKey,
    private val publicKey: ECPublicKey
) : Identity {
    companion object {
        const val ALIAS = "aap2-ssh-ecdsa-p256-v1"
        private const val PREFS = "aap2-keystore"
        private const val WAS_CREATED = "identity_created"

        @Synchronized
        fun getOrCreate(context: Context): AndroidKeystoreIdentity = load(context, allowCreation = true)

        @Synchronized
        fun getExisting(context: Context): AndroidKeystoreIdentity = load(context, allowCreation = false)

        /**
         * Negative instrumentation only: exercise the existing-key path with a missing
         * alias without deleting or changing the device's real SSH identity.
         */
        @Synchronized
        internal fun getMissingAliasForTest(context: Context, alias: String): AndroidKeystoreIdentity {
            require(alias != ALIAS) { "Test must not substitute the production alias" }
            return load(context, allowCreation = false, alias = alias)
        }

        private fun load(
            context: Context, allowCreation: Boolean, alias: String = ALIAS
        ): AndroidKeystoreIdentity {
            val protectedContext = context.createDeviceProtectedStorageContext()
            val prefs = protectedContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            if (!store.containsAlias(alias)) {
                check(!prefs.getBoolean(WAS_CREATED, false)) {
                    "AAP2 SSH identity was lost; refusing silent regeneration"
                }
                check(allowCreation) { "AAP2 SSH identity not provisioned" }
                val generator = KeyPairGenerator.getInstance(
                    KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore"
                )
                generator.initialize(
                    KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_SIGN)
                        .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                        .setDigests(KeyProperties.DIGEST_SHA256)
                        .setUserAuthenticationRequired(false)
                        .build()
                )
                generator.generateKeyPair()
                store.load(null)
            }
            check(prefs.edit().putBoolean(WAS_CREATED, true).commit()) {
                "Could not persist AAP2 Keystore identity marker"
            }
            val key = store.getKey(alias, null) as? PrivateKey
                ?: error("AAP2 private key unavailable")
            val public = store.getCertificate(alias)?.publicKey as? ECPublicKey
                ?: error("AAP2 P-256 public key unavailable")
            require(public.params.curve.field.fieldSize == 256) { "Wrong Keystore curve" }
            return AndroidKeystoreIdentity(key, public)
        }
    }

    override fun getName(): String = ALIAS
    override fun getAlgName(): String = SshEcWire.ALGORITHM
    override fun getPublicKeyBlob(): ByteArray = SshEcWire.publicKeyBlob(publicKey)
    override fun isEncrypted(): Boolean = false
    override fun setPassphrase(passphrase: ByteArray?): Boolean = true

    override fun getSignature(data: ByteArray): ByteArray =
        getSignature(data, SshEcWire.ALGORITHM)

    override fun getSignature(data: ByteArray, alg: String): ByteArray {
        require(alg == SshEcWire.ALGORITHM) { "Unexpected SSH signature algorithm" }
        val signature = Signature.getInstance("SHA256withECDSA")
        signature.initSign(privateKey)
        signature.update(data)
        return SshEcWire.signatureBlob(signature.sign())
    }

    override fun clear() {
        // Never delete the persistent Keystore entry when JSch releases its identity.
    }

    fun authorizedKey(): String =
        SshEcWire.ALGORITHM + " " +
            Base64.encodeToString(getPublicKeyBlob(), Base64.NO_WRAP) + " aap2"

}
