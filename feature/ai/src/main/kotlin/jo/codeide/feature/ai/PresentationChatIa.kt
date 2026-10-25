package jo.codeide.feature.ai

/**
 * Modèle d'écran PUR du chat IA (v0.188.0, rapport IA §5 Lot 3 —
 * B1a/B1b/B1c : la zone de saisie masquée par le compositeur d'états).
 *
 * Le chat a QUATRE visages exclusifs, par ordre de priorité :
 *
 * - [EcranChat.INACTIF] : l'assistant n'est pas actif (ou le
 *   consentement manque) → calque + bouton « Activer l'assistant »,
 *   compositeur MASQUÉ (aucune saisie ne peut partir) ;
 * - [EcranChat.CLE_ABSENTE] : actif mais AUCUNE clé n'est enregistrée
 *   pour le fournisseur actif → calque + bouton « Ajouter une clé »
 *   (S3 : plus d'erreur d'authentification au premier envoi),
 *   compositeur MASQUÉ ;
 * - [EcranChat.VIDE] : prêt à parler, aucun message → calque d'accueil
 *   dans la ZONE DES MESSAGES, compositeur VISIBLE (B1a/B1b : le
 *   premier message doit pouvoir être tapé — le calque ne couvre PLUS
 *   la saisie) ;
 * - [EcranChat.CONVERSATION] : liste + compositeur.
 *
 * Fonction PURE (aucune dépendance Android) — couverte par
 * [PresentationChatIaTest] ; le fragment n'a plus qu'à poser les
 * visibilités selon la branche, plus de booléen dispersé.
 */
internal enum class EcranChat {
    /** Assistant inactif : consentement à donner, saisie fermée. */
    INACTIF,

    /** Actif sans clé pour le fournisseur choisi : saisie fermée, ajout de clé proposé. */
    CLE_ABSENTE,

    /** Prêt et vide : accueil + saisie OUVERTE (le premier message peut partir). */
    VIDE,

    /** Conversation en cours : liste + saisie. */
    CONVERSATION,
}

/** Présentateur pur de l'écran du chat (rapport IA, Lot 3). */
internal object PresentationChatIa {
    /**
     * Décrit l'écran du chat à partir de l'état.
     *
     * @param active assistant actif ET consentement donné.
     * @param cleAbsente AUCUNE clé enregistrée pour le fournisseur actif
     *        ([jo.codeide.core.domain.CoffreClesIa.contient] = `false`).
     * @param nbMessages nombre de messages de la conversation courante.
     * @return le visage de l'écran — priorité : inactif > clé absente >
     *         vide > conversation (on ne propose pas la saisie sans
     *         clé ; on ne propose pas l'accueil avec des messages).
     */
    fun decrireEcranChat(active: Boolean, cleAbsente: Boolean, nbMessages: Int): EcranChat = when {
        !active -> EcranChat.INACTIF
        cleAbsente -> EcranChat.CLE_ABSENTE
        nbMessages <= 0 -> EcranChat.VIDE
        else -> EcranChat.CONVERSATION
    }
}
