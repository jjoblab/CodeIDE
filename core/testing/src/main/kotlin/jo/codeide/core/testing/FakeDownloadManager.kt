package jo.codeide.core.testing

import jo.codeide.core.domain.DownloadManager
import jo.codeide.core.domain.DownloadRequest
import jo.codeide.core.domain.Progress
import jo.codeide.core.model.AppError
import jo.codeide.core.model.AppError.EnvironmentSetupReason
import jo.codeide.core.model.AppResult
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/**
 * Doublure de [DownloadManager] (refonte E2, ADR 0087) : aucun réseau —
 * chaque demande est **comptée** ([demandes], invariant « un seul
 * téléchargement par artefact » des tests du cadre commun) et le test
 * fournit soit un fichier en cache ([fichierEnCache]), soit un échec
 * typé ([echec]).
 *
 * Le fichier semé est retourné tel quel : c'est au test de garantir la
 * cohérence entre la somme demandée et le contenu (le vrai gestionnaire
 * vérifie, la doublure épargne le hachage au testeur).
 */
public class FakeDownloadManager : DownloadManager {
    /** Demandes traitées, dans l'ordre — la taille certifie le nombre de téléchargements. */
    public val demandes: MutableList<DownloadRequest> = CopyOnWriteArrayList()

    /** Nombre total de demandes reçues (compteur atomique, pratique pour les assertions). */
    public val nombreDemandes: AtomicInteger = AtomicInteger(0)

    /** Fabrique du fichier à retourner — défaut : `null` traité comme cache absent. */
    public var fichierEnCache: File? = null

    /** Quand non `null`, le téléchargement échoue par cette erreur typée. */
    public var echec: AppError? = null

    /** Progressions publiées par appel (pour vérifier la conflation côté orchestrateur). */
    public val progressions: MutableList<Progress> = CopyOnWriteArrayList()

    override suspend fun download(
        request: DownloadRequest,
        onProgress: (Progress) -> Unit,
    ): AppResult<File> {
        demandes += request
        nombreDemandes.incrementAndGet()
        return when {
            echec != null -> {
                AppResult.Failure(echec!!)
            }

            fichierEnCache != null -> {
                restituer(fichierEnCache!!, onProgress)
            }

            else -> {
                AppResult.Failure(
                    AppError.EnvironmentSetup(
                        reason = EnvironmentSetupReason.Reseau,
                        details = "aucun fichier semé pour ${request.sha256}",
                    ),
                )
            }
        }
    }

    private fun restituer(
        fichier: File,
        onProgress: (Progress) -> Unit,
    ): AppResult<File> {
        val progression = Progress.Bytes(received = fichier.length(), total = fichier.length())
        onProgress(progression)
        progressions += progression
        return AppResult.Success(fichier)
    }
}
