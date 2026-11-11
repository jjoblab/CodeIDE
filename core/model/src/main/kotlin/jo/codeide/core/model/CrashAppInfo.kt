package jo.codeide.core.model

/**
 * Identité du build au moment d'un plantage (section 5.8).
 *
 * Champs **non identifiants** : ils qualifient la compilation, jamais
 * l'utilisateur. Le type de build distingue un APK de recette d'une version
 * diffusée — indispensable pour interpréter une trace reçue en retour.
 *
 * @property versionName nom lisible de la version (ex. « 0.4.0 »).
 * @property versionCode code numérique strictement croissant.
 * @property buildType variante (« debug » ou « release »).
 * @property applicationId identifiant du paquet Android.
 */
public data class CrashAppInfo(
    public val versionName: String,
    public val versionCode: Long,
    public val buildType: String,
    public val applicationId: String,
)
