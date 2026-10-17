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
 * ouverture d'un document.
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
}

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
     * Ouvre le chat IA pré-rempli avec la sélection courante de
     * l'éditeur (« Demander à l'IA » — action de sélection enregistrée
     * via `EditorView.addSelectionAction`, lot IA-5). Sélection vide :
     * l'action appelante doit être grisée (critère d'acceptation IA-5).
     */
    public fun demanderSurSelection()

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
}
