package jo.codeide.core.domain

import jo.codeide.core.model.AppResult
import jo.codeide.core.model.getOrNull

/**
 * Décorateur du port [FileSystem] qui capture l'historique local
 * (mission H1, ADR 0106) : TOUTES les écritures, suppressions,
 * renommages et créations des documents du **projet ouvert** produisent
 * des entrées — sans qu'aucun appelant ne change.
 *
 * Capture (contenu AVANT, ADR 0104) :
 * - [writeText] : contenu précédent lu via le délégué AVANT l'écriture ;
 *   fichier inconnu → CRÉATION ; **contenu identique → AUCUNE entrée**
 *   (la décorateur voit l'ancien ET le nouveau — règle anti-bruit) ;
 * - [writeBytes] : MODIFICATION SANS contenu (binaire présumé —
 *   l'entrée existe, le contenu est « indisponible ») ;
 * - [delete] : SUPPRESSION — PIERRE TOMBALE (dernier contenu lu avant
 *   la suppression, fichiers uniquement) ;
 * - [rename] : RENOMMAGE (ancien nom en libellé, blob dédupliqué du
 *   contenu courant) ;
 * - [createFile] : CRÉATION.
 *
 * Hors du projet ouvert (racine de [SourceProjetHistorique]), hors
 * exclusions (build, .gradle, .git, SECRETS — ADR 0105) : AUCUNE
 * capture. L'arbre privé n'est pas décoré (liaison séparée).
 *
 * **Silence obligatoire** (ADR 0106) : un échec d'enregistrement
 * n'échoue JAMAIS l'opération du délégué — l'historique est un filet,
 * pas une dépendance ; l'échec est avalé (journalisé par l'appelant
 * via le résultat, jamais remonté à l'utilisateur pour l'historique).
 *
 * Le chemin relatif se résout en décodant les URI de document via
 * [ArborescencesSaf] (identifiant « volume:chemin/relatif ») et en le
 * rapportant à la racine du projet ouvert.
 */
@Suppress("TooManyFunctions", "ReturnCount") // Port FileSystem complet (14 méthodes) + gardes typées de capture.
public class HistoriqueFileSystem(
    private val delegue: FileSystem,
    private val historique: HistoriqueLocal,
    private val source: SourceProjetHistorique,
    private val arborescences: ArborescencesSaf,
    private val politique: PolitiqueHistorique = PolitiqueHistorique(),
) : FileSystem {
    override suspend fun exists(documentUri: String): Boolean = delegue.exists(documentUri)

    override suspend fun stat(documentUri: String): AppResult<jo.codeide.core.domain.FileStat> =
        delegue.stat(documentUri)

    override suspend fun list(directoryUri: String): AppResult<List<jo.codeide.core.domain.FileStat>> =
        delegue.list(directoryUri)

    override suspend fun createDirectory(
        parentDirectoryUri: String,
        name: String,
    ): AppResult<String> = delegue.createDirectory(parentDirectoryUri, name)

    override suspend fun createFile(
        parentDirectoryUri: String,
        name: String,
        mimeType: String,
    ): AppResult<String> {
        val resultat = delegue.createFile(parentDirectoryUri, name, mimeType)
        val uriCreee = resultat.getOrNull()
        if (uriCreee != null) {
            val chemin = cheminRelatifSiCapture(uriCreee)
            if (chemin != null) {
                historique.enregistrer(chemin, TypeEntreeHistorique.CREATION, contenu = null)
            }
        }
        return resultat
    }

    override suspend fun writeText(
        documentUri: String,
        text: String,
    ): AppResult<Unit> {
        val chemin = cheminRelatifSiCapture(documentUri)
        val avant = chemin?.let { lireContenuAvant(documentUri) }
        val resultat = delegue.writeText(documentUri, text)
        if (resultat is AppResult.Success && chemin != null) {
            val type =
                if (avant == null) TypeEntreeHistorique.CREATION else TypeEntreeHistorique.MODIFICATION
            // Contenu identique → aucune entrée (le décorateur voit
            // l'ancien ET le nouveau — pas d'historique de bruit).
            if (avant != text) {
                historique.enregistrer(chemin, type, contenu = avant)
            }
        }
        return resultat
    }

    override suspend fun writeBytes(
        documentUri: String,
        bytes: ByteArray,
    ): AppResult<Unit> {
        val chemin = cheminRelatifSiCapture(documentUri)
        val resultat = delegue.writeBytes(documentUri, bytes)
        if (resultat is AppResult.Success && chemin != null) {
            // Binaire présumé : entrée SANS contenu (ADR 0105 —
            // « contenu indisponible », jamais inventé).
            val existait = delegue.exists(documentUri)
            val type =
                if (existait) TypeEntreeHistorique.MODIFICATION else TypeEntreeHistorique.CREATION
            historique.enregistrer(chemin, type, contenu = null)
        }
        return resultat
    }

    override suspend fun readText(documentUri: String): AppResult<String> = delegue.readText(documentUri)

    override suspend fun readBytes(documentUri: String): AppResult<ByteArray> = delegue.readBytes(documentUri)

    override suspend fun rename(
        documentUri: String,
        nouveauNom: String,
    ): AppResult<String> {
        val ancienNom =
            cheminRelatifSiCapture(documentUri)
                ?.let { delegue.stat(documentUri).getOrNull()?.name }
        val resultat = delegue.rename(documentUri, nouveauNom)
        val nouvelleUri = resultat.getOrNull()
        if (nouvelleUri != null) {
            val chemin = cheminRelatifSiCapture(nouvelleUri)
            if (chemin != null) {
                val contenu = delegue.readText(nouvelleUri).getOrNull()
                historique.enregistrer(
                    chemin,
                    TypeEntreeHistorique.RENOMMAGE,
                    contenu = contenu,
                    libelle = ancienNom,
                )
            }
        }
        return resultat
    }

    override suspend fun delete(documentUri: String): AppResult<Unit> {
        val chemin = cheminRelatifSiCapture(documentUri)
        val stat = chemin?.let { delegue.stat(documentUri).getOrNull() }
        val contenuAvant =
            if (chemin != null && stat?.isDirectory == false) {
                delegue.readText(documentUri).getOrNull()
            } else {
                null
            }
        val resultat = delegue.delete(documentUri)
        if (resultat is AppResult.Success && chemin != null && stat?.isDirectory != true) {
            // PIERRE TOMBALE : le fichier supprimé reste retrouvable et
            // restaurable (ADR 0104).
            historique.enregistrer(
                chemin,
                TypeEntreeHistorique.SUPPRESSION,
                contenu = contenuAvant,
                tailleOctets = stat?.sizeBytes ?: contenuAvant?.length?.toLong() ?: 0L,
            )
        }
        return resultat
    }

    override suspend fun takePersistablePermission(grantUri: String): AppResult<Unit> =
        delegue.takePersistablePermission(grantUri)

    override suspend fun releasePersistablePermission(grantUri: String): AppResult<Unit> =
        delegue.releasePersistablePermission(grantUri)

    override suspend fun hasPersistablePermission(grantUri: String): Boolean =
        delegue.hasPersistablePermission(grantUri)

    /**
     * Chemin relatif du document SI la capture s'applique (projet
     * ouvert + non exclu), sinon `null`.
     */
    private suspend fun cheminRelatifSiCapture(documentUri: String): String? {
        val racine = source.racineDocument ?: return null
        val idDocument = arborescences.idDocumentDeUriDocument(documentUri) ?: return null
        val idRacine = arborescences.idDocumentDeUriDocument(racine) ?: return null
        if (!idDocument.startsWith("$idRacine/")) return null
        val relatif = idDocument.substring(idRacine.length + 1)
        if (relatif.isBlank()) return null
        return relatif.takeIf { !politique.exclut(it) }
    }

    /** Contenu précédent du document (lecture AVANT l'écriture). */
    private suspend fun lireContenuAvant(documentUri: String): String? =
        if (delegue.exists(documentUri)) {
            delegue.readText(documentUri).getOrNull()
        } else {
            null
        }
}
