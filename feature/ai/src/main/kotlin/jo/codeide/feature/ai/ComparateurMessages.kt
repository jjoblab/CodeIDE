package jo.codeide.feature.ai

import androidx.recyclerview.widget.DiffUtil

/**
 * Clés de paiement du rebind partiel (v0.195.0) : ce qui a changé
 * dans un message assistant — le texte qui coule, le raisonnement
 * qui s'écrit, la carte d'erreur, la fin de génération (curseur,
 * bouton Copier). Niveau FICHIER (B1, rapport IA lot 1) : la clé est
 * le contrat PARTAGÉ du comparateur (qui émet) et de l'adaptateur
 * (qui déplie) — chaque côté la référence SANS qualification.
 */
internal enum class ClePayload {
    TEXTE,
    RAISONNEMENT,
    ERREUR,
    FIN,
}

/**
 * Comparateur DiffUtil des messages du chat (mission IA, lot IA-3) :
 * identité = identifiant du message, contenu = égalité structurelle.
 *
 * PAIEMENTS DU REBIND PARTIEL (v0.195.0, retours propriétaire) :
 * [getChangePayload] renvoie TOUJOURS un `Set<ClePayload>` — jamais
 * une clé nue. Le contrat est consommé par le dépliage de
 * `AdapterMessages.onBindViewHolder(payloads)` :
 * `payloads.flatMap { (it as? Set<*>)?.toList() ?: listOf(it) }`.
 *
 * RÉGRESSION B1 (rapport IA, lot 1) : l'ancienne écriture renvoyait
 * un `Set` que l'adaptateur lisait par `mapNotNull { it as?
 * ClePayload }` — un `Set` n'est PAS une `ClePayload`, la liste
 * dépliée était VIDE et `lierPartiellement` ne rendait RIEN pendant
 * le flux (le curseur clignotait indéfiniment, seul un changement
 * d'onglet — recréation de vue — déclenchait le rendu complet). Le
 * contrat « TOUJOURS un `Set` » est verrouillé par
 * [ComparateurMessagesTest] et le dépliage par le test Robolectric
 * de l'adaptateur.
 */
internal object ComparateurMessages : DiffUtil.ItemCallback<MessageChatUi>() {
    public override fun areItemsTheSame(ancien: MessageChatUi, nouveau: MessageChatUi): Boolean =
        ancien.identifiant == nouveau.identifiant

    public override fun areContentsTheSame(ancien: MessageChatUi, nouveau: MessageChatUi): Boolean = ancien == nouveau

    /**
     * Clés de ce qui a changé — `Set<ClePayload>` invariablement (le
     * repli de l'ensemble vide est AUSSI un `Set` : `setOf(FIN)`).
     */
    public override fun getChangePayload(ancien: MessageChatUi, nouveau: MessageChatUi): Any? {
        if (ancien == nouveau) {
            return null
        }
        val cles = mutableSetOf<ClePayload>()
        if (ancien.texte != nouveau.texte) cles.add(ClePayload.TEXTE)
        if (ancien.raisonnement != nouveau.raisonnement) cles.add(ClePayload.RAISONNEMENT)
        if (ancien.erreur != nouveau.erreur) cles.add(ClePayload.ERREUR)
        if (ancien.generationEnCours != nouveau.generationEnCours ||
            ancien.tronque != nouveau.tronque
        ) {
            cles.add(ClePayload.FIN)
        }
        return cles.ifEmpty { setOf(ClePayload.FIN) }
    }
}
