package jo.codeide.core.logging

/**
 * Informations de build portées par `app` et consommées par le pipeline
 * (en-tête de session, export) — le module d'implémentation ne dépend
 * d'aucun `BuildConfig`.
 *
 * @property versionName nom de version lisible (ex. « 0.3.0 »).
 * @property versionCode code numérique strictement croissant.
 * @property buildType type de build (`debug` ou `release`).
 */
data class BuildInfo(
    val versionName: String,
    val versionCode: Long,
    val buildType: String,
)

/**
 * Résumé **non identifiant** de l'appareil, fourni par `app` (identique en
 * principe à celui des rapports de plantage de l'étape 3) : fabricant,
 * modèle, version d'Android, ABI et locale — jamais d'identifiants
 * propriétaires.
 */
fun interface DeviceSummary {
    /**
     * Compose le résumé de l'appareil.
     *
     * @return une ligne descriptive, sans donnée personnelle.
     */
    fun summary(): String
}
