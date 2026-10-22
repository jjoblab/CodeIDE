package jo.codeide.feature.editor

/**
 * Décisions de l'action « Formater le document » (chantier « Capacités
 * LSP fermées par défaut et action Formater », ADR 0136 — P1-b/P1-c) :
 * traduction PURE des faits en ce que l'interface doit montrer, même
 * patron que [decisionNotificationTooling] — l'activité relève les
 * faits (onglet ouvert, session vivante, résultat du formatage), la
 * décision se teste sans cadre Android ([DecisionFormaterTest]).
 *
 * **Phase 1** : la visibilité suit la SESSION DE LANGUE VIVANTE de
 * l'onglet actif (Java, Kotlin ou XML — la vue ne reçoit le formateur
 * que d'un serveur connecté) ; la phase 2 la pilotera par
 * `canFormat()` (code-editor 3.74.0, ADR 0136 D4).
 */
internal object DecisionFormater {
    /**
     * L'action est-elle PROPOSÉE ? Phase 1 : un onglet ouvert ET une
     * session de langue vivante (le serveur du type du fichier actif).
     * Sans onglet : rien ne se lance, l'action n'est pas proposée.
     */
    internal fun visible(
        ongletOuvert: Boolean,
        sessionVivante: Boolean,
    ): Boolean = ongletOuvert && sessionVivante

    /**
     * Le message à montrer après un `formatDocument` :
     * - `null` (AUCUN formateur — le serveur du fichier ne propose pas
     *   la mise en forme, ou elle est désactivée dans les réglages de
     *   langage) : message d'INDISPONIBILITÉ ;
     * - `false` (formateur présent, texte déjà conforme) : « Déjà
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
