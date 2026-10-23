package jo.codeide.core.aistorage

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import dagger.hilt.android.qualifiers.ApplicationContext
import jo.codeide.core.domain.CoffreClesIa
import jo.codeide.core.domain.DispatcherProvider
import jo.codeide.core.model.AppError
import jo.codeide.core.model.AppResult
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.security.InvalidKeyException
import java.security.KeyStore
import java.security.NoSuchAlgorithmException
import java.security.ProviderException
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.IllegalBlockSizeException
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject

/**
 * Source de la **clé maître** du coffre (mission IA, lot IA-2, ADR 0124
 * §1) — abstraite pour rendre [CoffreClesKeystore] entièrement testable
 * en JVM (l'implémentation de test fournit une clé logicielle ; la perte
 * de clé se simule en changeant la clé retournée).
 */
public fun interface CleMaitreFournisseur {
    /** Renvoie la clé maître (créée au premier appel côté Keystore). */
    public fun obtenirOuCreer(): SecretKey
}

/**
 * Implémentation **Keystore** de [CleMaitreFournisseur] : clé AES-256-GCM
 * non exportable, alias versionné `codeide.ai.vault.v1` (ADR 0124 §1).
 * Le Keystore Android est global au processus : aucun `Context` requis.
 * Toute erreur plateforme remonte en exception — [CoffreClesKeystore] la
 * traduit en `AppError.Storage(KeyUnavailable)`.
 */
internal class CleMaitreKeystore : CleMaitreFournisseur {
    override fun obtenirOuCreer(): SecretKey {
        val keystore = KeyStore.getInstance(FORNISSEUR_ANDROID).apply { load(null) }
        (keystore.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val generateur = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, FORNISSEUR_ANDROID)
        generateur.init(
            KeyGenParameterSpec
                .Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(TAILLE_CLE_BITS)
                .setRandomizedEncryptionRequired(true)
                .build(),
        )
        return generateur.generateKey()
    }

    private companion object {
        const val FORNISSEUR_ANDROID = "AndroidKeyStore"

        /** Alias versionné — ADR 0124 §1 : la rotation future crée `v2`, jamais ceci. */
        const val ALIAS = "codeide.ai.vault.v1"

        /** Taille de la clé maître — AES-256 (ADR 0124 §1). */
        const val TAILLE_CLE_BITS = 256
    }
}

/**
 * Coffre de clés IA (mission IA, lot IA-2, ADR 0124 §1) : chaque clé
 * d'API est chiffrée AES-256-GCM avec un IV aléatoire de 12 octets ;
 * blob = `version(1) ‖ IV(12) ‖ chiffré+étiquette` — écrit
 * atomiquement (fichier temporaire + renommage) dans
 * `Context.noBackupFilesDir` (**exclu de la sauvegarde** par construction).
 *
 * L'aperçu public `••••1234` (4 derniers caractères, non sensible —
 * ADR 0124 §1) est stocké à côté du blob (fichier `.apercu`), lu sans
 * déchiffrement : il survit à une rotation et disparaît avec la clé.
 *
 * Aucune perte n'est un plantage : blob absent → `Storage(NotFound)` ;
 * clé maître perdue ou blob corrompu → `Storage(KeyUnavailable)` —
 * l'écran affiche « Clé à ressaisir » (critère d'acceptation IA-2).
 */
public class CoffreClesKeystore
@Inject
constructor(
    @param:ApplicationContext private val contexte: Context,
    private val dispatchers: DispatcherProvider,
    private val cleMaitre: CleMaitreFournisseur,
) : CoffreClesIa {
    private fun fichier(identifiant: String, extension: String): File {
        require(REGEX_IDENTIFIANT.matches(identifiant)) { "identifiant de clé invalide" }
        return File(contexte.noBackupFilesDir, "$PREFIXE_FICHIER$identifiant$extension")
    }

    override suspend fun enregistrer(identifiant: String, cle: CharArray): AppResult<Unit> =
        withContext(dispatchers.io) {
            try {
                val chiffre = chiffre().apply { init(Cipher.ENCRYPT_MODE, cleMaitre.obtenirOuCreer()) }
                val iv = chiffre.iv
                require(iv.size == TAILLE_IV_ATTENDUE) { "IV inattendu (${iv.size} octets)" }
                val blob =
                    ByteArray(1 + iv.size) { index -> if (index == 0) VERSION_BLOB else iv[index - 1] } +
                        chiffre.doFinal(cle.concatToString().toByteArray(Charsets.UTF_8))
                ecrireAtomiquement(fichier(identifiant, EXTENSION_BLOB), blob)
                ecrireAtomiquement(
                    fichier(identifiant, EXTENSION_APERCU),
                    apercuDe(cle).toByteArray(Charsets.UTF_8),
                )
                cle.fill('\u0000')
                AppResult.Success(Unit)
            } catch (erreur: Exception) {
                if (erreur.estAnnulation()) throw erreur
                val cause = erreur.javaClass.simpleName
                AppResult.Failure(
                    AppError.Storage(AppError.StorageReason.NotWritable, "enregistrement impossible : $cause"),
                )
            }
        }

    override suspend fun lire(identifiant: String): AppResult<CharArray> = withContext(dispatchers.io) {
        val blob = fichier(identifiant, EXTENSION_BLOB)
        if (!blob.exists()) {
            val absente = AppError.Storage(AppError.StorageReason.NotFound, "aucune clé")
            return@withContext AppResult.Failure(absente)
        }
        try {
            val contenu = blob.readBytes()
            if (contenu.size < 1 + TAILLE_IV_ATTENDUE + ETIQUETTE_GCM_MINIMUM || contenu[0] != VERSION_BLOB) {
                return@withContext AppResult.Failure(erreurClePerdue("blob illisible"))
            }
            val cle = cleMaitre.obtenirOuCreer()
            val iv = contenu.copyOfRange(1, 1 + TAILLE_IV_ATTENDUE)
            val chiffre = chiffre()
            chiffre.init(Cipher.DECRYPT_MODE, cle, GCMParameterSpec(ETIQUETTE_GCM_BITS, iv))
            val clair =
                chiffre.doFinal(contenu, 1 + TAILLE_IV_ATTENDUE, contenu.size - 1 - TAILLE_IV_ATTENDUE)
            AppResult.Success(String(clair, Charsets.UTF_8).toCharArray())
        } catch (erreur: Exception) {
            val traduite = cleIllisible(erreur)
            if (traduite != null) {
                return@withContext traduite
            }
            val cause = erreur.javaClass.simpleName
            return@withContext AppResult.Failure(AppError.Unknown("lecture coffre : $cause"))
        }
    }

    override suspend fun supprimer(identifiant: String): AppResult<Unit> = withContext(dispatchers.io) {
        fichier(identifiant, EXTENSION_BLOB).delete()
        fichier(identifiant, EXTENSION_APERCU).delete()
        AppResult.Success(Unit)
    }

    override suspend fun contient(identifiant: String): Boolean =
        withContext(dispatchers.io) { fichier(identifiant, EXTENSION_BLOB).exists() }

    override suspend fun apercu(identifiant: String): String? = withContext(dispatchers.io) {
        fichier(identifiant, EXTENSION_APERCU)
            .takeIf { it.exists() }
            ?.readText(Charsets.UTF_8)
            ?.ifBlank { null }
    }

    private fun chiffre(): Cipher = Cipher.getInstance("AES/GCM/NoPadding")

    private fun ecrireAtomiquement(cible: File, contenu: ByteArray) {
        val temporaire = File(cible.parentFile, "${cible.name}.tmp")
        temporaire.writeBytes(contenu)
        if (!temporaire.renameTo(cible)) {
            temporaire.delete()
            throw IOException("renommage refusé : ${cible.name}")
        }
    }

    private companion object {
        const val PREFIXE_FICHIER = "codeide_ia_cle_"
        const val EXTENSION_BLOB = ".blob"
        const val EXTENSION_APERCU = ".apercu"
        const val VERSION_BLOB: Byte = 1
        const val TAILLE_IV_ATTENDUE = 12
        const val ETIQUETTE_GCM_MINIMUM = 16
        const val ETIQUETTE_GCM_BITS = 128

        /** Identifiants autorisés (identifiants de [jo.codeide.core.domain.FournisseursIa]). */
        val REGEX_IDENTIFIANT = Regex("""^[a-z0-9_]{1,64}$""")
    }
}

/** Caractères visibles de l'aperçu (ADR 0124 §1 : les 4 derniers). */
private const val APERCU_VISIBLES = 4

/** Aperçu `••••` + 4 derniers caractères — clé courte intégralement masquée. */
private fun apercuDe(cle: CharArray): String = if (cle.size <= APERCU_VISIBLES) {
    "•".repeat(cle.size)
} else {
    "•".repeat(APERCU_VISIBLES) + cle.concatToString().takeLast(APERCU_VISIBLES)
}

/** Traduction des incidents de déchiffrement en « clé à ressaisir ». */
private fun cleIllisible(erreur: Exception): AppResult<CharArray>? = when (erreur) {
    is AEADBadTagException,
    is InvalidKeyException,
    is IllegalBlockSizeException,
    is NoSuchAlgorithmException,
    is ProviderException,
    -> AppResult.Failure(erreurClePerdue(erreur.javaClass.simpleName))

    else -> null
}

private fun erreurClePerdue(details: String): AppError =
    AppError.Storage(AppError.StorageReason.KeyUnavailable, "clé à ressaisir : $details")

/** `CancellationException` n'est jamais avalée : elle reste relancée (règle 4 du plan). */
private fun Exception.estAnnulation(): Boolean = this is kotlin.coroutines.cancellation.CancellationException
