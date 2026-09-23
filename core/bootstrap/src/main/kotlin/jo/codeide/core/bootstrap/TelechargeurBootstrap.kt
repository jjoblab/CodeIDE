package jo.codeide.core.bootstrap

import jo.codeide.core.domain.DispatcherProvider
import jo.codeide.core.model.AppError.BootstrapReason
import jo.codeide.core.model.EtapeInstallation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Téléchargement de l'archive du bootstrap (prompt compagnon Terminal-1,
 * section 3.4, étape 1 : progression octets reçus sur total, échec réseau
 * typé, **empreinte SHA-256 vérifiée** avant de rendre la main).
 *
 * Client `HttpURLConnection` : aucune dépendance réseau additionnelle —
 * l'autorisation `INTERNET` de l'application couvre cet unique usage
 * (ADR de l'étape T3).
 *
 * Écrit directement dans le répertoire de préparation ; l'annulation ou
 * l'échec peut y laisser une archive partielle — c'est l'installateur
 * qui nettoie le staging (il est le seul à connaître le cycle complet).
 */
internal class TelechargeurBootstrap(
    private val configuration: ConfigurationBootstrap,
    private val dispatchers: DispatcherProvider,
) {
    /**
     * Télécharge l'archive vers [destination] et vérifie son empreinte.
     *
     * @return flux froid de progression (octets reçus, total annoncé ou
     * `null` si le serveur ne le fournit pas) ; le flux se termine
     * normalement seulement si l'archive complète et intègre est en
     * place.
     * @throws EchecBootstrap réseau injoignable, réponse invalide ou
     * empreinte non conforme.
     */
    fun telecharger(destination: File): Flow<EtapeInstallation.Telechargement> =
        flow {
            var connexion: HttpURLConnection? = null
            try {
                connexion = ouvrirConnexion(configuration.urlArchive)
                val code = connexion.responseCode
                if (code !in BORNE_SUCCES) {
                    throw EchecBootstrap(BootstrapReason.ReseauIndisponible, "HTTP $code pour l'archive du bootstrap")
                }
                val tailleAnnoncee = connexion.contentLengthLong

                val empreinte = MessageDigest.getInstance("SHA-256")
                val sortie = FileOutputStream(destination).buffered()
                sortie.use {
                    connexion.inputStream.buffered().use { entree ->
                        val tampon = ByteArray(TAILLE_TAMPON)
                        var recus = 0L
                        var derniereEmission = 0L
                        while (true) {
                            val lus = entree.read(tampon)
                            if (lus < 0) break
                            empreinte.update(tampon, 0, lus)
                            sortie.write(tampon, 0, lus)
                            recus += lus
                            if (recus - derniereEmission >= SEUIL_EMISSION || lus == 0) {
                                emit(EtapeInstallation.Telechargement(recus, tailleAnnoncee.takeIf { it >= 0 }))
                                derniereEmission = recus
                            }
                        }
                        emit(EtapeInstallation.Telechargement(recus, tailleAnnoncee.takeIf { it >= 0 }))
                    }
                }

                // Représentation hexadécimale **indépendante de la locale**
                // (String.format utiliserait la locale par défaut — des
                // chiffres régionaux casseraient la comparaison).
                val calculee =
                    empreinte.digest().joinToString("") { octet ->
                        ((octet.toInt() and MASQUE_OCTET) + BASE_HEXA).toString(BASE_16).substring(1)
                    }
                if (calculee != configuration.empreinteAttendue) {
                    throw EchecBootstrap(BootstrapReason.EmpreinteInvalide, "SHA-256 obtenue $calculee")
                }
            } catch (e: java.util.concurrent.CancellationException) {
                throw e
            } catch (e: EchecBootstrap) {
                throw e
            } catch (e: IOException) {
                throw EchecBootstrap(BootstrapReason.ReseauIndisponible, "échec d'E/S réseau : ${e.message}", cause = e)
            } finally {
                connexion?.disconnect()
            }
        }.flowOn(dispatchers.io)

    private fun ouvrirConnexion(url: String): HttpURLConnection {
        val connexion = URL(url).openConnection() as HttpURLConnection
        // Les releases GitHub répondent par des redirections (302) vers
        // les objets réels — suivies automatiquement.
        connexion.instanceFollowRedirects = true
        connexion.connectTimeout = DELAI_CONNEXION
        connexion.readTimeout = DELAI_LECTURE
        return connexion
    }

    private companion object {
        /** Codes HTTP de succès (2xx) acceptés pour l'archive. */
        private val BORNE_SUCCES = 200..299

        /** Base de la représentation hexadécimale (indépendante de la locale). */
        private const val BASE_16 = 16

        /** Masque d'un octet non signé. */
        private const val MASQUE_OCTET = 0xff

        /** Décalage garantissant deux chiffres hexadécimaux par octet. */
        private const val BASE_HEXA = 0x100

        /** Taille du tampon de copie (octets). */
        private const val TAILLE_TAMPON = 8 * 1024

        /** Émission de progression au plus à chaque 512 Kio (StateFlow conflate en aval). */
        private const val SEUIL_EMISSION = 512L * 1024

        /** Délais réseau (millisecondes). */
        private const val DELAI_CONNEXION = 30_000
        private const val DELAI_LECTURE = 60_000
    }
}
