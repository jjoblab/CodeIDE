package jo.codeide.feature.editor

/**
 * Décisions de l'action « Formater le document » (chantier « Capacités
 * LSP fermées par défaut et action Formater », ADR 0136 — P1-b/P1-c) :
 * traduction PURE des faits en ce que l'interface doit montrer, même
 * patron que [decisionNotificationTooling] — l'activité relève les
 * faits (onglet ouvert, disponibilité du formateur, résultat du
 * formatage), la décision se teste sans cadre Android
 * ([DecisionFormaterTest]).
 *
 * **Phase 2** (code-editor 3.74.0, ADR 0136 D4) : la visibilité suit
 * `canFormat()` — VRAI uniquement si le langage courant expose un
 * formateur (le serveur du type du fichier annonce la mise en forme et
 * la capacité n'est pas éteinte dans les réglages de langage). La
 * session de langue (garde du BRANCHEMENT, [serveurPretDe]) n'est plus
 * un proxy : la vue répond d'elle-même, y compris pendant la connexion
 * (le rappel de branchement re-évalue l'action) et sur un onglet sans
 * serveur (la déconnexion restaure `EmptyLanguage`, la réponse repasse
 * fausse).
 */
internal object DecisionFormater {
    /**
     * L'action est-elle PROPOSÉE ? Phase 2 : un onglet ouvert ET un
     * formateur disponible dessus (`canFormat()` — le formateur suit le
     * langage courant, pas la session). Sans onglet ou sans formateur :
     * rien ne se lance, l'action n'est pas proposée.
     */
    internal fun visible(
        ongletOuvert: Boolean,
        formateurDisponible: Boolean,
    ): Boolean = ongletOuvert && formateurDisponible

    /**
     * Le message à montrer après un `formatDocument` ou un
     * `formatSelection` (mêmes messages — le refus de plage et le
     * « déjà conforme » se disent pareil) :
     * - `null` (AUCUN formateur — le serveur du fichier ne propose pas
     *   la mise en forme, ou elle est désactivée dans les réglages de
     *   langage) : message d'INDISPONIBILITÉ ;
     * - `false` (formateur présent, texte déjà conforme — pour la
     *   sélection : refus propre de plage ou sélection vide) : « Déjà
     *   formaté » ;
     * - `true` (texte modifié) : AUCUN message — l'utilisateur VOIT le
     *   texte changer.
     */
    internal fun messagePour(resultat: Boolean?): Int? =
        when (resultat) {
            null -> R.string.editor_formater_indisponible
            false -> R.string.editor_formater_deja
            true -> null
        }
}
