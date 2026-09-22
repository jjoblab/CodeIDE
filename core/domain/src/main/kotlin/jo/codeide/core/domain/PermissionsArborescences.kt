package jo.codeide.core.domain

import jo.codeide.core.model.AppError
import jo.codeide.core.model.AppResult
import jo.codeide.core.model.StorageLocation
import jo.codeide.core.model.getOrNull
import kotlinx.coroutines.flow.first

/*
 * Règles d'arborescence et de permissions partagées par les cas d'usage
 * du dossier de travail (étapes 5-6) et des projets (étape 7).
 *
 * Regroupées ici parce qu'elles incarnent **une même politique** :
 * « ne persister que le nécessaire » (section 5.6 — le système plafonne
 * les permissions persistantes) et « la frontière d'un arbre se compare
 * sur les URI de document, avec tolérance d'encodage ».
 */

/**
 * Libère la permission persistante d'une arborescence **uniquement si
 * plus personne ne l'utilise** : ni le dossier de travail courant, ni un
 * projet restant du registre.
 *
 * C'est la contrepartie de la règle de l'étape 6 (« ne libérer l'ancienne
 * permission que si aucun projet n'en dépend »), appliquée à chaque
 * retrait de projet (étape 7) : le dossier de travail la garde tant qu'il
 * la référence, un autre projet importé sur le même arbre la garde aussi.
 *
 * @param projets registre des projets.
 * @param parametres paramètres applicatifs (dossier de travail).
 * @param fichiers port d'accès au stockage.
 * @param grantUri URI d'arborescence dont la permission est candidate.
 */
internal suspend fun libererPermissionSiInutilisee(
    projets: ProjectRepository,
    parametres: SettingsRepository,
    fichiers: FileSystem,
    grantUri: String,
) {
    if (parametres
            .getSettings()
            .getOrNull()
            ?.workspace
            ?.grantUri == grantUri
    ) {
        return
    }
    if (projets.observeProjects().first().any { it.location.grantUri == grantUri }) return
    fichiers.releasePersistablePermission(grantUri)
}

/**
 * L'URI de document [documentUri] vit-elle dans l'arbre enraciné en
 * [racineDocumentUri] ?
 *
 * La comparaison est un préfixe tolérant sur l'encodage : les fournisseurs
 * SAF percent-encodent le séparateur (`%2F`) mais certains le laissent
 * brut (`/`) ; les deux formes sont acceptées (même règle que
 * `projetsSousDossier` depuis l'étape 6).
 *
 * @return `true` si le document est l'arbre lui-même ou vit dessous.
 */
internal fun estDansArbre(
    racineDocumentUri: String,
    documentUri: String,
): Boolean =
    documentUri == racineDocumentUri ||
        documentUri.startsWith("$racineDocumentUri%2F") ||
        documentUri.startsWith("$racineDocumentUri/")

/**
 * Chemin relatif d'un identifiant de document **décodé** sous une racine
 * décodée, ou `null` s'il n'est pas dans cet arbre.
 *
 * Travaille sur les identifiants décodés (chemins du volume, séparateur
 * `/` toujours brut) — contrairement aux URI, ils ne subissent pas de
 * variance d'encodage entre fournisseurs.
 *
 * @return le chemin sous la racine (ex. `MonProjet` sous
 * `primary:CodeIDE`), ou `null` hors arbre.
 */
internal fun cheminRelatifSiSousArbre(
    racineId: String,
    idDocument: String,
): String? {
    if (!idDocument.startsWith("$racineId/")) return null
    return idDocument.removePrefix("$racineId/")
}

/**
 * Libellé lisible d'un dossier pour [StorageLocation.displayPath] : nom
 * retourné par le fournisseur, repli sur le dernier segment de
 * l'identifiant de document si le stockage ne sait pas le décrire.
 *
 * @param fichiers port d'accès au stockage.
 * @param uriDocument URI du dossier.
 * @param idDocument identifiant décodé (repli).
 * @return un libellé non vide.
 */
internal suspend fun libelleLisible(
    fichiers: FileSystem,
    uriDocument: String,
    idDocument: String,
): String {
    val nom =
        (fichiers.stat(uriDocument) as? AppResult.Success<FileStat>)
            ?.value
            ?.name
            .orEmpty()
    return nom.ifBlank { idDocument.substringAfterLast('/') }
}

/**
 * Test d'écriture témoin : crée un fichier, y écrit une ligne, puis le
 * supprime — prouver que le dossier est réellement **utilisable**, pas
 * seulement sélectionnable (section 5.6).
 *
 * Extrait de `ValidateWorkspaceUseCase` (étape 5) pour être partagé avec
 * l'import et la relocalisation de projets (étape 7) : le même contrat
 * d'« arbre inscriptible » s'applique aux trois parcours.
 *
 * @param fichiers port d'accès au stockage.
 * @param uriDocument URI du dossier à éprouver.
 * @param horloge horloge injectée (nom unique du témoin).
 * @param prefixeTemoin préfixe du nom du fichier témoin.
 * @return `null` si le dossier est inscriptible, sinon l'erreur typée
 * (création, écriture ou lecture impossible).
 */
@Suppress("ReturnCount") // Clauses de garde : création, écriture, témoin orphelin (règle 16).
internal suspend fun testerEcriture(
    fichiers: FileSystem,
    uriDocument: String,
    horloge: TimeProvider,
    prefixeTemoin: String,
): AppError? {
    val nomTemoin = "$prefixeTemoin-${horloge.nowMillis()}"
    val cree =
        when (val resultat = fichiers.createFile(uriDocument, nomTemoin, "text/plain")) {
            is AppResult.Failure -> return resultat.error
            is AppResult.Success -> resultat.value
        }
    val ecriture = fichiers.writeText(cree, TEMOIN_CONTENU)
    val suppression = fichiers.delete(cree)
    // Un témoin qui ne s'efface pas n'invalide pas le dossier
    // (l'écriture a réussi) : simple avertissement.
    if (suppression is AppResult.Failure) return null
    return (ecriture as? AppResult.Failure)?.error
}

private const val TEMOIN_CONTENU = "codeide"
