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

    /**
     * Ouvre le tiroir sur l'onglet Chat de l'assistant (mission IA, lot
     * IA-5) — point d'entrée générique des panneaux ; le pré-remplissage
     * passe par les deux méthodes dédiées ci-dessous.
     */
    fun ouvrirTiroirIa()

    /**
     * « Corriger avec l'IA » (mission IA, lot IA-5, spec E3) : ouvre le
     * chat pré-rempli avec le diagnostic — identifiant reconstruit par
     * l'hôte (convention `source:ligne:colonne` du port d'éditeur).
     */
    fun corrigerProblemeAvecIa(
        fichier: String,
        ligne: Int,
        colonne: Int,
        source: String,
    )

    /**
     * « Expliquer » (mission IA, lot IA-5, spec E3) : ouvre le chat
     * pré-rempli avec la sortie visible (Console ou Logcat) — l'envoi
     * reste un geste utilisateur dans le chat.
     */
    fun expliquerAvecIa(texte: String)
}
