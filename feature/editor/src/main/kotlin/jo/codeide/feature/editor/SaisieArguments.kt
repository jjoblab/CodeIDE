package jo.codeide.feature.editor

/**
 * État de saisie du champ d'arguments du tooling (correctif n°10 du prompt
 * « tooling Gradle professionnel ») : décide quand le rendu peut réécrire
 * le champ et quand la saisie doit être persistée.
 *
 * **Bug corrigé** : l'ancien `DialogueConfigToolingFragment` armait son
 * drapeau `saisieArgumentsEnCours` à CHAQUE `doAfterTextChanged` — y compris
 * ceux déclenchés par le `setText` programmatique du rendu. Le champ n'était
 * alors plus jamais resynchronisé sur le DataStore, et une valeur périmée
 * était ré-écrite à la prochaine perte de focus. La source du changement
 * (utilisateur ou rendu) distingue désormais les deux cas.
 *
 * Pur par construction (précédent [DecisionNotificationTooling]) : le
 * fragment n'est que de la colle, la décision est testée unitairement.
 */
internal class SaisieArguments {
    private var enCours = false

    /**
     * Un changement de texte vient d'arriver : arme la validation SEULEMENT
     * si l'utilisateur en est l'auteur. Le rendu signale ses propres
     * écritures par [pendantRendu] (même patron que les interrupteurs :
     * « TextWatcher désactivé pendant le rendu »).
     */
    fun surChangementTexte(pendantRendu: Boolean) {
        if (!pendantRendu) {
            enCours = true
        }
    }

    /** Le rendu peut-il réécrire le champ ? (jamais pendant une saisie :
     *  effacer le texte d'un utilisateur en train de taper serait le pire
     *  des rendus). */
    fun renduPeutReecrire(): Boolean = !enCours

    /**
     * La saisie doit-elle être persistée (perte de focus / « Terminé ») ?
     * Consomme le drapeau : une validation n'arme pas la suivante.
     */
    fun consommerPourValidation(): Boolean {
        if (!enCours) {
            return false
        }
        enCours = false
        return true
    }
}
