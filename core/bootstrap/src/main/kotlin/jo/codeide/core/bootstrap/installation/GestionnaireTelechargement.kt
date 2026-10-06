package jo.codeide.core.bootstrap.installation

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import jo.codeide.core.domain.AppLogger
import jo.codeide.core.domain.DispatcherProvider
import jo.codeide.core.domain.DownloadManager
import jo.codeide.core.domain.DownloadRequest
import jo.codeide.core.domain.Progress
import jo.codeide.core.model.AppError
import jo.codeide.core.model.AppError.EnvironmentSetupReason
import jo.codeide.core.model.AppResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

// Une responsabilité (télécharger et vérifier), décomposée en étapes nommées :
// ouverture, reprise, réception, finalisation — seuil assumé (règle 8).

/**
 * Téléchargement d'artefacts du parcours (port [DownloadManager],
 * ADR 0085 § 4 / ADR 0087 § 3) — `HttpURLConnection` éprouvé par
 * `TelechargeurBootstrap`, prolongé par les trois exigences du cadre :
 *
 * - **cache adressé par SHA-256** (`filesDir/cache/downloads/<sha256>`) :
 *   fichier présent et somme correcte → restitution immédiate, zéro
 *   requête réseau — l'invariant « un composant = une version résolue =
 *   un téléchargement » (§ 3.1 du cahier) ;
 * - **sources ordonnées** : la primaire puis les miroirs, chaque essai
 *   journalisé — jamais de repli silencieux (§ 3.3) ;
 * - **reprise `Range`** : un téléchargement interrompu reprend à
 *   l'octet où il s'était arrêté (`Range: bytes=N-`, réponse 206) ; un
 *   serveur sans support `Range` (réponse 200) fait repartir proprement
 *   du début.
 *
 * La somme SHA-256 est vérifiée **à chaque restitution** (cache compris)
 * — un fichier présent ne prouve rien (§ 3.2). L'hexadécimal est
 * construit indépendant de la locale.
*/
@Suppress("TooManyFunctions")
@Singleton
internal class GestionnaireTelechargement
    @Inject
    constructor(
        @ApplicationContext contexte: Context,
        private val dispatchers: DispatcherProvider,
        private val journal: AppLogger,
    ) : DownloadManager {
        private val repertoireCache = File(contexte.filesDir, REPERTOIRE_CACHE)

        /**
         * Fichier final d'un artefact dans le cache (clé = SHA-256) —
         * exposé aux étapes pour leur contrôle de reprise passif : « déjà
         * téléchargé » sans réémettre la moindre requête.
         */
        internal fun fichierEnCache(sha256: String): File = File(repertoireCache, sha256)

        override suspend fun download(
            request: DownloadRequest,
            onProgress: (Progress) -> Unit,
        ): AppResult<File> =
            withContext(dispatchers.io) {
                val final = fichierEnCache(request.sha256)
                if (final.isFile && sommeDuFichier(final) == request.sha256) {
                    journal.i(TAG) { "cache : ${request.sha256.take(COURTE)} restitué sans réseau" }
                    onProgress(Progress.Bytes(received = final.length(), total = final.length()))
                    return@withContext AppResult.Success(final)
                }
                telechargerDepuisLesSources(request, onProgress)
            }

        /** Essaie chaque source dans l'ordre ; le dernier échec typé l'emporte. */
        private fun telechargerDepuisLesSources(
            request: DownloadRequest,
            onProgress: (Progress) -> Unit,
        ): AppResult<File> {
            var dernierEchec: AppError.EnvironmentSetup? = null
            for ((index, source) in request.sources.withIndex()) {
                when (val essai = essayerSource(source, request, onProgress)) {
                    is AppResult.Success -> {
                        return essai
                    }

                    is AppResult.Failure -> {
                        dernierEchec = essai.error as AppError.EnvironmentSetup
                        journal.w(TAG) {
                            "source ${index + 1}/${request.sources.size} échouée : ${dernierEchec?.details}"
                        }
                    }
                }
            }
            return AppResult.Failure(
                dernierEchec
                    ?: AppError.EnvironmentSetup(
                        reason = EnvironmentSetupReason.Reseau,
                        details = "aucune source disponible (${request.sources.size} essayée·s)",
                    ),
            )
        }

        /** Une source : ouverture, reprise `Range` si possible, réception, somme. */
        private fun essayerSource(
            source: String,
            request: DownloadRequest,
            onProgress: (Progress) -> Unit,
        ): AppResult<File> {
            repertoireCache.mkdirs()
            val part = File(repertoireCache, "${request.sha256}.part")
            var connexion: HttpURLConnection? = null
            return try {
                connexion = ouvrirConnexion(source, part)
                val code = connexion.responseCode
                if (code !in HTTP_OK_MIN..HTTP_OK_MAX) {
                    return AppResult.Failure(refusHttp(code, source, part))
                }
                val reponse = preparerReprise(connexion, code, part)
                val somme = recevoir(connexion, part, reponse, request, onProgress)
                finaliser(part, request.sha256, source, somme)
            } catch (e: CancellationException) {
                throw e
            } catch (e: IOException) {
                part.delete()
                AppResult.Failure(
                    AppError.EnvironmentSetup(
                        reason = EnvironmentSetupReason.Reseau,
                        details = "échec réseau sur $source : ${e.message}",
                    ),
                )
            } finally {
                connexion?.disconnect()
            }
        }

        /** Ouvre la connexion, en demandant la suite du `.part` s'il existe. */
        private fun ouvrirConnexion(
            source: String,
            part: File,
        ): HttpURLConnection {
            val connexion = URL(source).openConnection() as HttpURLConnection
            connexion.instanceFollowRedirects = true
            connexion.connectTimeout = DELAI_CONNEXION
            connexion.readTimeout = DELAI_LECTURE
            if (part.isFile && part.length() > 0) {
                connexion.setRequestProperty(EN_TETE_RANGE, "bytes=${part.length()}-")
            }
            return connexion
        }

        /** Échec HTTP typé réseau, la reprise éventuelle est nommée dans les détails. */
        private fun refusHttp(
            code: Int,
            source: String,
            part: File,
        ): AppError.EnvironmentSetup =
            AppError.EnvironmentSetup(
                reason = EnvironmentSetupReason.Reseau,
                details =
                    "HTTP $code pour $source" +
                        if (part.isFile && part.length() > 0) " (reprise refusée à l'octet ${part.length()})" else "",
            )

        /**
         * Réponse à une demande de reprise : 206 → le `.part` est repris
         * dans l'empreinte et la réception continue en append ; 200 → le
         * serveur ignore `Range`, tout repart de zéro.
         */
        private fun preparerReprise(
            connexion: HttpURLConnection,
            code: Int,
            part: File,
        ): ReponseReprise {
            val repriseDemandee = part.isFile && part.length() > 0
            val acceptee = code == HTTP_CONTENU_PARTIEL && repriseDemandee
            if (!acceptee && part.isFile) part.delete()
            return ReponseReprise(
                octetsDejaRecus = if (acceptee) part.length() else 0L,
                totalAnnonce = totalAnnonce(connexion, if (acceptee) part.length() else 0L),
            )
        }

        /** Taille totale annoncée : la demande prime, sinon `Content-Length` (plus la reprise). */
        private fun totalAnnonce(
            connexion: HttpURLConnection,
            octetsDejaRecus: Long,
        ): Long =
            when {
                connexion.contentLengthLong >= 0 -> octetsDejaRecus + connexion.contentLengthLong
                else -> -1L
            }

        /** Boucle de réception : hachage au fil de l'eau, progression espacée — retourne la somme calculée. */
        private fun recevoir(
            connexion: HttpURLConnection,
            part: File,
            reponse: ReponseReprise,
            request: DownloadRequest,
            onProgress: (Progress) -> Unit,
        ): String {
            val empreinte = MessageDigest.getInstance("SHA-256")
            if (reponse.octetsDejaRecus > 0) {
                // Le hachage couvre le fichier COMPLET : le `.part`
                // existant est repris dans l'empreinte avant la suite.
                hacherFichier(part, empreinte)
            }
            val total = if (request.sizeBytes > 0) request.sizeBytes else reponse.totalAnnonce
            val compteur = CompteurReception(reponse.octetsDejaRecus, total, onProgress)
            FileOutputStream(part, reponse.octetsDejaRecus > 0).buffered().use { sortie ->
                connexion.inputStream.buffered().use { entree -> copier(entree, sortie, empreinte, compteur) }
                sortie.flush()
            }
            onProgress(progression(compteur.recus, maxOf(compteur.recus, total)))
            return hexadecimal(empreinte.digest())
        }

        /** Copie un flux dans l'autre en hachant et en publiant la progression espacée. */
        private fun copier(
            entree: java.io.InputStream,
            sortie: java.io.OutputStream,
            empreinte: MessageDigest,
            compteur: CompteurReception,
        ) {
            val tampon = ByteArray(TAILLE_TAMPON)
            while (true) {
                val lus = entree.read(tampon)
                if (lus < 0) break
                empreinte.update(tampon, 0, lus)
                sortie.write(tampon, 0, lus)
                compteur.ajouter(lus)
            }
        }

        /** Compteur de réception : publie la progression au plus toutes les 512 Kio (conflation côté UI). */
        private inner class CompteurReception(
            var recus: Long,
            private val total: Long,
            private val surProgression: (Progress) -> Unit,
        ) {
            private var derniereEmission = 0L

            fun ajouter(octets: Int) {
                recus += octets
                if (recus - derniereEmission >= SEUIL_EMISSION) {
                    surProgression(progression(recus, total))
                    derniereEmission = recus
                }
            }
        }

        /** Bascule du `.part` vers le cache final, après contrôle de la somme. */
        private fun finaliser(
            part: File,
            sha256: String,
            source: String,
            sommeCalculee: String,
        ): AppResult<File> {
            if (sommeCalculee != sha256) {
                part.delete()
                return AppResult.Failure(
                    AppError.EnvironmentSetup(
                        reason = EnvironmentSetupReason.SommeControle,
                        details = "SHA-256 obtenue $sommeCalculee, $sha256 attendue ($source)",
                    ),
                )
            }
            val final = fichierEnCache(sha256)
            if (final.isFile) final.delete()
            return if (part.renameTo(final)) {
                AppResult.Success(final)
            } else {
                AppResult.Failure(
                    AppError.EnvironmentSetup(
                        reason = EnvironmentSetupReason.Permissions,
                        details = "bascule du téléchargement vers le cache impossible",
                    ),
                )
            }
        }

        private fun progression(
            recus: Long,
            total: Long,
        ): Progress = Progress.Bytes(received = recus, total = if (total > 0) total else null)

        /** Somme SHA-256 d'un fichier, hexadécimal minuscule indépendant de la locale. */
        private fun sommeDuFichier(fichier: File): String {
            val empreinte = MessageDigest.getInstance("SHA-256")
            hacherFichier(fichier, empreinte)
            return hexadecimal(empreinte.digest())
        }

        private fun hacherFichier(
            fichier: File,
            empreinte: MessageDigest,
        ) {
            fichier.inputStream().buffered().use { flux ->
                val tampon = ByteArray(TAILLE_TAMPON)
                while (true) {
                    val lus = flux.read(tampon)
                    if (lus < 0) break
                    empreinte.update(tampon, 0, lus)
                }
            }
        }

        private fun hexadecimal(digest: ByteArray): String =
            digest.joinToString("") { octet ->
                ((octet.toInt() and MASQUE_OCTET) + BASE_HEXA).toString(BASE_HEXADECIMALE).substring(DEBUT_SOUS_CHAINE)
            }

        /** Réponse d'une source après préparation de la reprise. */
        private data class ReponseReprise(
            val octetsDejaRecus: Long,
            val totalAnnonce: Long,
        )

        internal companion object {
            private const val TAG = "install"

            private const val EN_TETE_RANGE = "Range"

            private const val REPERTOIRE_CACHE = "cache/downloads"

            /** Codes HTTP de succès (2xx). */
            internal const val HTTP_OK_MIN: Int = 200

            internal const val HTTP_OK_MAX: Int = 299

            /** Réponse « Partial Content » : le serveur honore la reprise `Range`. */
            internal const val HTTP_CONTENU_PARTIEL: Int = 206

            /** Tampon de copie (8 Kio — éprouvé par `TelechargeurBootstrap`). */
            internal const val TAILLE_TAMPON: Int = 8 * 1024

            /** Émission de progression au plus toutes les 512 Kio (conflation côté UI). */
            internal const val SEUIL_EMISSION: Long = 512L * 1024

            internal const val DELAI_CONNEXION: Int = 30_000

            internal const val DELAI_LECTURE: Int = 60_000

            /** Troncature des sommes dans les journaux (lisibilité). */
            private const val COURTE: Int = 12

            /** Masque d'un octet (le `and` produit un entier positif). */
            private const val MASQUE_OCTET: Int = 0xff

            /** Décalage garantissant deux chiffres hexadécimaux par octet. */
            private const val BASE_HEXA: Int = 0x100

            /** Base de la représentation hexadécimale. */
            private const val BASE_HEXADECIMALE: Int = 16

            /** Premier chiffre de la sous-chaîne (le second est le chiffre utile). */
            private const val DEBUT_SOUS_CHAINE: Int = 1
        }
    }
