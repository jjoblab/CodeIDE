package jo.codeide.feature.editor

/**
 * Contrat entre les fragments du panneau inférieur (ADR 0055) et leur
 * hôte [EditorActivity] — même pattern que [jo.codeide.core.ui.ControleurTerminalTiroir]
 * pour le tiroir (ADR 0053), mais intra-feature : le contrat et ses
 * fragments vivent dans `feature:editor`, l'activité l'implémente et le
 * fragment le récupère par un cast contrôlé dans `onAttach`.
 */
interface ControleurPanneauEditeur {
    /**
     * Saut à un diagnostic de l'onglet Problèmes : sélectionne l'onglet
     * du fichier diagnostiqué (son chemin relatif est le suffixe du
     * fichier diagnostiqué), défile l'éditeur et pose le curseur à la
     * ligne — la vue est rebbranchée sur la bonne session avant le saut.
     */
    fun sauterAuProbleme(
        fichier: String,
        ligne: Int,
    )
}
