package jo.codeide.core.ui

/**
 * Navigation inter-écrans, découplée des fonctionnalités (section 5.4).
 *
 * Les modules `feature:*` ne se connaissent pas : ils demandent à
 * l'application de naviguer via cette interface, implémentée dans `app`
 * (lien Hilt `@Binds`) au-dessus du graphe Navigation Component.
 *
 * La surface grandit étape par étape, au fil du plan d'exécution :
 * ouverture de l'assistant (`onboarding`, étape 5), du wizard de
 * création (`newproject`, étapes 7 et 10), d'un rapport de plantage
 * (`diagnostics`, étape 12), de l'éditeur (`editor`, étape 13)… Les
 * écrans placeholder de l'étape 1 n'ont besoin que de l'aller-retour
 * Accueil ↔ Paramètres.
 */
public interface AppNavigator {
    /** Ouvre l'écran Paramètres depuis n'importe quelle fonctionnalité. */
    public fun openSettings(): Unit

    /**
     * Revient en arrière dans la pile de navigation.
     *
     * À distinguer d'une fermeture de dialogue ou d'un retour système :
     * c'est la demande explicite « ramène-moi là d'où je venais ».
     */
    public fun goBack(): Unit

    /**
     * Ouvre le rapport de plantage en **consultation** (section 5.8) :
     * l'écran dédié vit dans un processus séparé, l'implémentation
     * applicative (`app`) seule connaît l'activité sous-jacente.
     *
     * Servi par la boîte de dialogue du démarrage (« Voir le rapport »)
     * puis, à l'étape 12, par la visionneuse des diagnostics.
     *
     * @param id identifiant du rapport à consulter.
     */
    public fun openCrashReport(id: String)

    /**
     * Ouvre le wizard de création de projet (étape 7 : destination
     * placeholder ; assistant complet à l'étape 10).
     *
     * Servi par le bouton flottant « Nouveau projet » de l'accueil et
     * par l'état vide de la liste.
     */
    public fun openNewProjectWizard(): Unit

    /**
     * Ouvre l'assistant de premier lancement (étape 5).
     *
     * Servi par le bandeau « Configurer le dossier de travail » de
     * l'accueil quand l'utilisateur a passé l'étape du dossier à
     * l'assistant (« Plus tard »). L'assistant se referme normalement
     * par [openHome].
     */
    public fun openOnboarding(): Unit

    /**
     * Retourne à l'accueil en refermant l'assistant de premier lancement
     * (fin du parcours : `isSetupCompleted = true`).
     *
     * Retire l'assistant de la pile de retour — terminer l'installation
     * n'est pas une navigation réversible.
     */
    public fun openHome(): Unit

    /**
     * Referme le wizard de création après une **création réussie** et
     * signale à l'accueil le projet à mettre en évidence (étape 11,
     * section 12.3 : « le nouveau projet apparaît, mis en évidence »).
     *
     * L'identifiant transite par le `SavedStateHandle` de l'entrée d'accueil
     * (pile de retour) : il survit à la recréation de l'activité et ne
     * dépend d'aucun singleton.
     *
     * @param projectId identifiant du projet créé.
     */
    public fun wizardCreeProjet(projectId: String): Unit

    /**
     * Consomme l'identifiant du dernier projet créé signalé par le wizard
     * (étape 11) : le récepteur — l'accueil — l'utilise pour le défilement
     * et le surlignage, une seule fois.
     *
     * @return l'identifiant en attente, ou `null` s'il n'y en a pas.
     */
    public fun consommerProjetCree(): String?

    /**
     * Ouvre l'écran Diagnostic (étape 12) : visionneuse des journaux et des
     * rapports de plantage.
     *
     * Servi par l'entrée Paramètres › Avancé › Diagnostic.
     */
    public fun openDiagnostics(): Unit

    /**
     * Ouvre la feuille de partage système pour une **archive de
     * diagnostic** (journaux ou rapports de plantage, étape 12) produite
     * dans le répertoire d'export du cache.
     *
     * L'implémentation applicative seule connaît l'autorité du
     * FileProvider et le répertoire exposé : les fonctionnalités
     * manipulent des descriptifs opaques (`ExportedLogs` du domaine),
     * jamais d'URI de fichier interne.
     *
     * @param nomFichier nom lisible proposé au partage.
     * @param emplacementInterne emplacement opaque de l'archive produite.
     */
    public fun partagerArchive(
        nomFichier: String,
        emplacementInterne: String,
    ): Unit
}
