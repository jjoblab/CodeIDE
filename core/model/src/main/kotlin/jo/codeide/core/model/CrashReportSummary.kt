package jo.codeide.core.model

/**
 * Résumé d'un rapport de plantage destiné aux listes (section 5.8).
 *
 * La version allégée du [CrashReport] : assez pour trier, afficher et
 * décider, sans lire le fichier complet. L'écran dédié est ouvert à la
 * demande avec l'[id].
 *
 * @property id identifiant unique du rapport.
 * @property timestampMillis horodatage du plantage, en millisecondes epoch.
 * @property type nature du plantage.
 * @property exceptionClassName nom court de l'exception d'origine.
 * @property shortMessage message raccourci (une ligne), déjà expurgé.
 * @property isReviewed `true` si l'utilisateur a ouvert ou ignoré le
 * rapport (fichier témoin) — pilote la boîte de dialogue au démarrage.
 */
public data class CrashReportSummary(
    public val id: String,
    public val timestampMillis: Long,
    public val type: CrashType,
    public val exceptionClassName: String,
    public val shortMessage: String,
    public val isReviewed: Boolean,
)
