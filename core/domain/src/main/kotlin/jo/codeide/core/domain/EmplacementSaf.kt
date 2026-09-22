package jo.codeide.core.domain

import jo.codeide.core.model.AppError
import jo.codeide.core.model.getOrNull

/**
 * Emplacement d'un dossier choisi via le sélecteur SAF, selon la
 * politique d'héritage de l'étape 7 (ADR 0015) : un choix **dans**
 * l'arbre du dossier de travail hérite de sa permission ; un choix
 * ailleurs doit prendre sa propre permission persistante.
 */
internal sealed interface EmplacementSaf {
    /** Le dossier vit dans l'arbre du dossier de travail : permission héritée. */
    data class Herite(
        val grantUri: String,
        val uriDocument: String,
    ) : EmplacementSaf

    /** Le dossier vit hors du dossier de travail : permission propre à prendre. */
    data object Propre : EmplacementSaf

    /** L'URI n'a pas pu être réadressée dans l'arbre du dossier de travail. */
    data class Illisible(
        val erreur: AppError,
    ) : EmplacementSaf
}

/**
 * Résout la politique d'emplacement d'un dossier choisi ([idChoisi],
 * [uriChoisi]) par rapport au dossier de travail courant : héritage si
 * le choix vit dans son arbre (racine comprise), permission propre
 * sinon.
 */
@Suppress("ReturnCount") // Clauses de garde : sans dossier, URI racine illisible, imbriqué, hors arbre (règle 16).
internal suspend fun resoudreEmplacement(
    arborescences: ArborescencesSaf,
    parametres: SettingsRepository,
    uriChoisi: String,
    idChoisi: String,
): EmplacementSaf {
    val dossierTravail = parametres.getSettings().getOrNull()?.workspace ?: return EmplacementSaf.Propre
    val idRacine = arborescences.idDocument(dossierTravail.grantUri) ?: return EmplacementSaf.Propre

    // Le dossier de travail lui-même, choisi comme projet.
    if (idChoisi == idRacine) {
        return EmplacementSaf.Herite(dossierTravail.grantUri, uriChoisi)
    }

    // Un sous-dossier de l'arbre : réadressé dans l'arbre du dossier de
    // travail, pour que la règle de libération conditionnelle sache le
    // comparer (le sélecteur rend une URI enracinée au dossier choisi).
    if (cheminRelatifSiSousArbre(idRacine, idChoisi) != null) {
        val uriDansArbre =
            arborescences.uriDocumentDansArbre(dossierTravail.grantUri, idChoisi)
                ?: return EmplacementSaf.Illisible(
                    AppError.Storage(AppError.StorageReason.Io, "URI de document illisible"),
                )
        return EmplacementSaf.Herite(dossierTravail.grantUri, uriDansArbre)
    }

    return EmplacementSaf.Propre
}
