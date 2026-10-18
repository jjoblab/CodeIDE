package jo.codeide.core.ui

import androidx.fragment.app.Fragment

/**
 * Fabrique du fragment **IA du tiroir** (mission IA, ADR 0122 — miroir
 * exact de [FabriqueFragmentTerminalTiroir], ADR 0053).
 *
 * Le fragment IA (Chat / Agent / Historique) vit dans `:feature:ai` —
 * seul feature autorisé à dépendre du domaine IA `:core:ai` (exception
 * explicite du ModuleRulesPlugin). L'activité d'édition, elle, reste
 * dans `:feature:editor` et ne peut référencer la classe : elle demande
 * le fragment à cette fabrique, liée par Hilt (`@Binds`) côté
 * `:feature:ai` (module `di/TiroirIaBindsModule`, lot IA-3).
 *
 * Le fragment résout son [ControleurIaTiroir] auprès de l'activité
 * hôte (cast en `onAttach`) : aucune référence croisée feature ↔ feature
 * au moment de la compilation — les deux interfaces vivent dans ce
 * module, le socle partagé.
 */
public interface FabriqueFragmentIaTiroir {
    /** Crée le fragment IA du tiroir, prêt à ajouter au gestionnaire. */
    public fun creer(): Fragment
}

/**
 * Commandes du fragment IA que seule l'activité d'édition peut exécuter
 * (mission IA, ADR 0122) : état de l'espace de travail que `:feature:ai`
 * ne connaît pas — fichier actif, dossier réel du projet (pont FUSE),
 * ouverture d'un document, affichage de la carte de diff (lot IA-5).
 *
 * L'activité d'édition implémente l'interface et le fragment la résout
 * par cast en `onAttach` ; un hôte qui ne l'implémente pas voit
 * simplement les actions concernées se désactiver (même convention que
 * [ControleurTerminalTiroir]).
 */
public interface ControleurIaTiroir {
    /**
     * Chemin RELATIF au projet du fichier actif dans l'éditeur, ou
     * `null` (aucun onglet ouvert) — alimente la puce de contexte
     * « fichier actif » (ADR 0124 §4 : ce que l'utilisateur voit est ce
     * qui part, jamais de chemin absolu de l'appareil).
     */
    public fun cheminRelatifFichierActif(): String?

    /**
     * Dossier RÉEL du projet courant côté appareil (résolu par le pont
     * SAF → FUSE, [ResoudreRepertoireProjet]) ou `null` (aucun projet
     * ouvert) — l'assembleur de contexte (IA-4) en a besoin pour lister
     * les fichiers `@mentionnables` ; les chemins transmis au modèle
     * restent RELATIFS.
     */
    public fun dossierProjetReel(): String?

    /**
     * Ouvre un document du projet dans l'éditeur (chemin relatif) —
     * utilisé par l'agent (IA-7) pour « appliquer puis montrer » et par
     * l'historique (IA-6) pour rouvrir un fichier cité.
     */
    public fun ouvrirFichier(cheminRelatif: String)

    /**
     * Affiche la **carte de diff** de l'éditeur (lot IA-5, ADR 0126) :
     * revue Accepter / Rejeter / Réessayer d'une proposition de bloc de
     * code du chat — l'application est en un lot (`beginBatch/endBatch`)
     * et refusée si la révision du document a changé (critère IA-5).
     * Hôte absent de la carte (aucun éditeur) : no-op honnête.
     */
    public fun afficherPropositionDiff(proposition: PropositionDiffIa)
}

/**
 * Proposition d'édition IA destinée à la carte de diff de l'éditeur
 * (mission IA, lot IA-5, ADR 0126) : construite par le chat au moment
 * de « Appliquer » sur un bloc de code — la plage est la sélection
 * courante du port d'éditeur, la révision sert de garde d'application
 * (texte changé ⇒ « Accepter » refusé, relancer).
 *
 * @property texteActuel contenu actuel de la plage (texte de la
 * sélection — ce que [jo.codeide.core.domain.DiffUnifie] compare).
 * @property textePropose contenu proposé par le modèle (bloc de code).
 * @property debut début de la plage de remplacement (offset document).
 * @property fin fin (exclusive) de la plage de remplacement.
 * @property revision révision du document au moment de la proposition
 * (port [jo.codeide.core.domain.PortEditeurActif.revision]).
 * @property cheminRelatif chemin relatif du fichier concerné (`null` si
 * aucun fichier actif — la carte reste affichable mais « Accepter »
 * sera refusé par le port).
 */
public data class PropositionDiffIa(
    public val texteActuel: String,
    public val textePropose: String,
    public val debut: Int,
    public val fin: Int,
    public val revision: Long,
    public val cheminRelatif: String?,
)

/**
 * Ce que l'éditeur appelle sur l'IA **sans** dépendre de `:feature:ai`
 * (mission IA, ADR 0122) : le pont est implémenté par `:feature:ai`
 * (liaison Hilt `di/TiroirIaBindsModule`, lot IA-3) et consommé par
 * `:feature:editor` au lot IA-5 (crochets minces ≲ 80 lignes — ADR 0126).
 *
 * Toute action déclenchée ici est une **initiative utilisateur** (ADR
 * 0121 point 3) : aucun appel en arrière-plan, aucun pré-chargement.
 */
public interface PontIaEditeur {
    /**
     * Transmet la consigne tapée dans la **barre de consigne en ligne**
     * (lot IA-5, ADR 0126) : le chat s'ouvre pré-rempli et l'envoi part
     * immédiatement — le bouton « Envoyer » de la barre EST une action
     * utilisateur explicite (ADR 0121 point 3), le contexte (sélection,
     * fichier actif) suit les règles IA-4 côté chat.
     *
     * @param consigne texte tapé par l'utilisateur (jamais vide — le
     * bouton appelant est désactivé sinon).
     */
    public fun envoyerConsigne(consigne: String)

    /**
     * Ouvre le chat IA pré-rempli pour un diagnostic (« Corriger avec
     * l'IA » — onglet Problèmes ou console, lot IA-5).
     *
     * @param cheminRelatif fichier concerné (relatif au projet).
     * @param identifiant identifiant du diagnostic chez son producteur
     * (javac/kotlinc/LSP) — le pont ré-assemble le message.
     */
    public fun corrigerDiagnostic(
        cheminRelatif: String,
        identifiant: String,
    )

    /**
     * Ouvre le chat IA pré-rempli avec une sortie à expliquer
     * (« Expliquer » — Console / Logcat, lot IA-5) : le texte est borné
     * par le pont (fenêtre glissante) et ne part qu'à l'envoi
     * utilisateur (pré-rempli, jamais auto-envoyé).
     *
     * @param texteBrut sortie telle qu'affichée à l'écran.
     */
    public fun expliquer(texteBrut: String)

    /**
     * Relance la dernière génération du chat (« Réessayer » de la carte
     * de diff — lot IA-5) : la consigne d'origine est conservée ; si
     * aucune génération n'existe encore, aucun effet.
     */
    public fun reessayer()
}
