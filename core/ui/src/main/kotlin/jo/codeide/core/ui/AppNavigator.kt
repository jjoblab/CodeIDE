package jo.codeide.core.ui

/**
 * Navigation inter-écrans, découplée des fonctionnalités (section 5.4).
 *
 * Les modules `feature:*` ne se connaissent pas : ils demandent à
 * l'application de naviguer via cette interface, implémentée dans `app`
 * (lien Hilt `@Binds`) au-dessus du graphe Navigation Component.
 *
 * La surface grandit étape par étape, au fil du plan d'exécution :
 * ouverture de l'assistant (`onboarding`, étape 5), du wizard de création
 * (`newproject`, étape 10), d'un rapport de plantage (`diagnostics`,
 * étape 12), de l'éditeur (`editor`, étape 13)… Les écrans placeholder de
 * l'étape 1 n'ont besoin que de l'aller-retour Accueil ↔ Paramètres.
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
}
