package jo.codeide.core.domain

/**
 * Catalogue de la chaîne d'outils : les **exigences** de l'application,
 * déclarées à un seul endroit (refonte E1, ADR 0086) — le manifeste v2 de
 * `codeide-tools` fournit les **artefacts** qui les satisfont, jamais
 * l'inverse.
 *
 * Le catalogue est consommé par les phases d'installation ET aligné avec
 * les templates Android (`buildToolsVersion` explicite, E4) : un test
 * vérifie que le plan résolu contient chaque version exigée. Toute
 * montée de version est un geste délibéré — le manifeste publie d'abord,
 * le catalogue et les templates suivent ensemble.
 *
 * Injecté (doublable en test) : aucune de ces valeurs n'est codée en dur
 * ailleurs — en particulier l'URL du manifeste est la **constante unique**
 * du § 12.7 (jamais d'URL de composant dans le code).
 *
 * @property manifestUrl URL du manifeste v2 — **non fixée à ce jour**
 * (prompt 2, R5) : surchargeable ; l'application rejette tout manifeste
 * dont `schemaVersion` n'est pas 2, un manifeste v1 ne peut donc pas être
 * lu en silence. Les tests n'utilisent jamais cette URL (client factice).
 * @property sdkProfile nom du profil consommé dans `profiles.<nom>` du
 * manifeste.
 * @property jdkMajorVersion version majeure du JDK exigée (phase 3).
 * @property jdkPackage paquet APT du JDK (phase 3 : `pkg install`).
 * @property packageTools paquets de la phase 2, installés **un par un**
 * avec état par paquet (§ 5 du cahier : outils nécessaires pour
 * télécharger, vérifier et extraire la suite).
 * @property requiredComponents composants exigés à la version exacte —
 * le plan résolu doit les contenir tous (ADR 0086 § 2 : build-tools
 * 35.0.2, seule version publiée pour Android aarch64 ; plateforme des
 * templates, `compileSdk` 37.2).
 * @property spaceThresholdBytes seuil d'espace disque plancher avant la
 * phase 1 (octets) ; la phase 4 contrôle ensuite la taille **totale
 * résolue** du plan.
 */
public data class ToolchainCatalog(
    public val manifestUrl: String = DEFAULT_MANIFEST_URL,
    public val sdkProfile: String = DEFAULT_SDK_PROFILE,
    public val jdkMajorVersion: Int = DEFAULT_JDK_MAJOR,
    public val jdkPackage: String = DEFAULT_JDK_PACKAGE,
    public val packageTools: List<String> = DEFAULT_PACKAGE_TOOLS,
    public val requiredComponents: List<ComponentRequirement> = DEFAULT_REQUIRED_COMPONENTS,
    public val spaceThresholdBytes: Long = DEFAULT_SPACE_THRESHOLD_BYTES,
) {
    public companion object {
        /**
         * URL officielle du manifeste v2 servie par GitHub Pages sur le
         * dépôt `codeide-tools` (ADR 0006 du dépôt `codeide-tools`,
         * confirmée par R5 : immuable par horodatage + pointeur `latest`,
         * reprise Range vérifiée). Pages est activé : l'URL répond 200 et
         * sert un manifeste `schemaVersion=2` à jour.
         *
         * L'ancienne valeur `raw.githubusercontent.com/.../manifest.v2.json`
         * pointait vers un chemin inexistant à la racine du dépôt (le
         * manifeste v2 est committé sous `dist/manifest.v2.json`, et la CI
         * le recopie vers `gh-pages/manifests/v2/latest.json`) — l'étape
         * `resolution-plan` de la phase `ANDROID_SDK` échouait donc en
         * HTTP 404 dès la première exécution.
         *
         * Surchargeable par configuration (le contrôle de `schemaVersion`
         * garantit qu'aucun manifeste d'une autre génération n'est
         * consommé en silence).
         */
        public const val DEFAULT_MANIFEST_URL: String =
            "https://jjoblab.github.io/codeide-tools/manifests/v2/latest.json"

        /** Profil SDK consommé (§ 12.2 : `profiles.<nom>`). */
        public const val DEFAULT_SDK_PROFILE: String = "default"

        /**
         * JDK exigé : 17 — seul JDK publié par le dépôt APT
         * `codeide-packages` (vérifié contre son fichier `Packages`) ;
         * le manifeste n'exprime que `jdk>=17` (§ 12.1).
         */
        public const val DEFAULT_JDK_MAJOR: Int = 17

        /** Paquet APT du JDK (phase 3). */
        public const val DEFAULT_JDK_PACKAGE: String = "openjdk-17"

        /**
         * Outils de la phase 2 (§ 5) : téléchargement (`curl`),
         * certificats (`ca-certificates`), extraction `.tar.xz` par les
         * outils du bootstrap — bits d'exécution et liens préservés
         * (ADR 0084 R4), archives zip (`unzip`).
         */
        public val DEFAULT_PACKAGE_TOOLS: List<String> =
            listOf("curl", "ca-certificates", "tar", "xz-utils", "unzip")

        /**
         * Composants exigés (ADR 0086 § 2) :
         * - `build-tools@35.0.2` : seule version reconditionnée publiée
         *   pour Android aarch64 (manifeste v1 vérifié, source
         *   `lzhiyong/android-sdk-tools`) — la machine de build x86_64
         *   utilise 36.0.0, ce n'est pas la même contrainte ;
         * - `platform@android-37.2` : plateforme des templates
         *   (`compileSdk` 37 + `compileSdkMinor` 2, vérifié dans
         *   `templates/android-app`).
         */
        public val DEFAULT_REQUIRED_COMPONENTS: List<ComponentRequirement> =
            listOf(
                ComponentRequirement("build-tools", "35.0.2"),
                ComponentRequirement("platform", "android-37.2"),
            )

        /** Seuil plancher : 1 Gio (marge pour le JDK seul, 200+ Mio installé). */
        public const val DEFAULT_SPACE_THRESHOLD_BYTES: Long = 1024L * 1024 * 1024
    }
}
