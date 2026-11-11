package jo.codeide.core.model

/**
 * Emplacement de stockage d'un dossier (dossier de travail ou projet),
 * conforme au contrat SAF de la section 5.6 du prompt maître.
 *
 * SAF fournit des **URI**, jamais des chemins `File` : l'emplacement porte
 * donc les deux URI nécessaires et un libellé lisible, sans rien présupposer
 * du système de fichiers sous-jacent (ADR 0003).
 *
 * @property grantUri URI de l'arborescence (tree URI) **qui détient la
 * permission persistante**. C'est elle qui est présentée à
 * `takePersistableUriPermission` / `persistedUriPermissions` ; les projets
 * créés dans le dossier de travail héritent de cet accès sans prendre
 * leur propre permission.
 * @property documentUri URI du document (dossier) lui-même, utilisée pour
 * toute opération SAF le concernant (création, listing, existence).
 * @property displayPath libellé lisible par l'humain (ex. « Téléchargements »),
 * construit au moment de la sélection ; utilisé dans l'UI, jamais comme clé.
 */
public data class StorageLocation(
    public val grantUri: String,
    public val documentUri: String,
    public val displayPath: String,
) {
    init {
        require(grantUri.isNotBlank()) { "L'URI de permission (grantUri) ne peut pas être vide." }
        require(documentUri.isNotBlank()) { "L'URI du dossier (documentUri) ne peut pas être vide." }
        require(displayPath.isNotBlank()) { "Le libellé lisible (displayPath) ne peut pas être vide." }
    }

    /**
     * Représentation sûre pour les journaux et le débogage.
     *
     * N'expose volontairement **ni** les URI ni le chemin complet
     * (règle 15 : aucune donnée personnelle ni localisable dans les
     * journaux) — seul le libellé lisible apparaît.
     *
     * @return le libellé lisible de l'emplacement.
     */
    public override fun toString(): String = displayPath
}
