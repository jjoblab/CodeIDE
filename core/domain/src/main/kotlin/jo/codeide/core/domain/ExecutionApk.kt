package jo.codeide.core.domain

import java.io.File

/**
 * Port d'installation et de lancement de l'APK produit par un build
 * (mission « Exécuter » R1, ADR 0102) — l'équivalent mobile du bouton
 * « Run » d'Android Studio : compiler, installer, lancer, SANS adb.
 *
 * L'implémentation de production (module `app`, processus principal)
 * passe par **`PackageInstaller`** d'Android : session en
 * `MODE_FULL_INSTALL`, copie + `fsync`, `commit` avec un
 * `PendingIntent` de diffusion vers un récepteur **non exporté à
 * action unique par session** (non forgeable), et TOUJOURS la
 * confirmation du système quand celui-ci l'exige (première
 * installation, signature modifiée) — l'installation n'est jamais
 * silencieuse sans accord utilisateur. Les tests JVM lient
 * [jo.codeide.core.testing.FakeApkInstaller].
 *
 * Le lancement résout l'activité de démarrage du paquet (intent
 * explicite) et **persiste avec des relances espacées** : juste après
 * une installation, le `PackageManager` du processus appelant peut ne
 * pas encore voir le paquet (leçon mesurée chez les IDE mobiles de
 * référence : ~10 essais × 200 ms).
 *
 * Un ARRIÈRE-PLAN ne peut pas démarrer une activité (Android 10+) :
 * l'appelant (l'espace d'édition, premier plan) porte la
 * responsabilité du moment — [ExecuterApplicationUseCase] documente
 * ce contrat.
 */
public interface ApkInstaller {
    /**
     * Installe l'APK [apk] (fichier réel, chemin FUSE du projet).
     *
     * @param progression récepteur optionnel des étapes franchies
     *        (autorisation « sources inconnues » requise, copie,
     *        confirmation système affichée) — intentions typées pour
     *        l'interface, jamais des chaînes brutes.
     * @return le résultat typé — succès ou échec ACTIONNABLE
     *         (signature différente → remplacer ; version plus
     *         récente installée ; autorisation refusée…).
     */
    public suspend fun installer(
        apk: File,
        progression: ((EtapeInstallationApk) -> Unit)? = null,
    ): ResultatInstallationApk

    /**
     * Lance l'application installée [nomPaquet] (relances intégrées).
     *
     * @return succès, ou introuvable après les relances.
     */
    public suspend fun lancer(nomPaquet: String): ResultatLancementApk
}

/**
 * Étape franchie pendant une installation (progression typée —
 * l'interface en déduit console et snackbar, le domaine ne formule
 * jamais de texte).
 */
public sealed interface EtapeInstallationApk {
    /**
     * L'autorisation « sources inconnues » manque : l'écran système
     * s'ouvre (l'implémentation le fait elle-même) et l'installation
     * REPRENDRA automatiquement à son retour.
     */
    public data object AutorisationSourcesInconnuesRequise : EtapeInstallationApk

    /** Copie de l'APK dans la session d'installation. */
    public data object CopieApk : EtapeInstallationApk

    /** La boîte de confirmation du système est affichée. */
    public data object ConfirmationSysteme : EtapeInstallationApk
}

/**
 * Résultat d'une installation — chaque échec est ACTIONNABLE (ADR 0102 :
 * l'interface propose l'action correctrice, jamais un échec muet).
 */
public sealed interface ResultatInstallationApk {
    /**
     * APK installé (première installation ou mise à jour).
     *
     * @property confirmationUtilisateur la confirmation du système est
     *           passée par l'utilisateur (première installation) — l'UI
     *           peut le dire.
     */
    public data class Succes(
        public val confirmationUtilisateur: Boolean,
    ) : ResultatInstallationApk

    /** Échec typé (voir chaque issue). */
    public sealed interface Echec : ResultatInstallationApk

    /** L'autorisation « sources inconnues » n'a pas été accordée. */
    public data object AutorisationRefusee : Echec

    /**
     * Une application signée DIFFÉREMMENT occupe déjà le paquet —
     * l'action correctrice est « désinstaller puis réinstaller » en
     * nommant la perte de données.
     */
    public data object SignatureDifferente : Echec

    /** L'APK est plus ANCIEN que la version installée. */
    public data object VersionPlusRecenteInstallee : Echec

    /** Espace de stockage insuffisant. */
    public data object EspaceInsuffisant : Echec

    /** Installation annulée (refus de la confirmation système, abandon). */
    public data class Annule(
        public val messageSysteme: String? = null,
    ) : Echec

    /** Autre échec du système : message brut, affiché tel quel. */
    public data class Autre(
        public val messageSysteme: String? = null,
    ) : Echec
}

/** Résultat d'un lancement d'application installée. */
public sealed interface ResultatLancementApk {
    /** Application lancée. */
    public data class Succes(
        public val nomPaquet: String,
    ) : ResultatLancementApk

    /**
     * Introuvable après toutes les relances (l'activité de démarrage
     * est peut-être absente, ou le paquet n'est pas encore visible).
     */
    public data class Introuvable(
        public val nomPaquet: String,
    ) : ResultatLancementApk
}
