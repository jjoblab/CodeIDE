package jo.codeide.core.domain

import jo.codeide.core.model.AppResult

/**
 * Sélection courante dans l'éditeur (mission IA, ADR 0122) — intervalle
 * fermé-ouvert sur le texte du document actif.
 *
 * @property debut décalage du premier caractère (0, inclusif).
 * @property fin décalage après le dernier caractère (exclusif).
 * @property texte contenu sélectionné — ce que l'utilisateur voit est ce
 * qui part vers le fournisseur (ADR 0124 §4) ; rien d'implicite.
 */
public data class SelectionEditeur(
    public val debut: Int,
    public val fin: Int,
    public val texte: String,
)

/** Gravité d'un diagnostic exposé au domaine IA (mission IA, ADR 0122). */
public enum class GraviteDiagnostic {
    /** Information, suggestion. */
    INFO,

    /** Avertissement (code douteux, dépréciation…). */
    AVERTISSEMENT,

    /** Erreur de compilation ou d'analyse. */
    ERREUR,
}

/**
 * Diagnostic normalisé exposé par l'éditeur au domaine IA (mission IA,
 * ADR 0122) : le modèle de diagnostic des producteurs (parseurs
 * javac/kotlinc, LSP) reste confiné côté éditeur — l'IA ne voit que ce
 * contrat mince (producteur = « javac », « kotlinc », « lsp-java »…).
 */
public data class DiagnosticEditeur(
    public val identifiant: String,
    public val gravite: GraviteDiagnostic,
    public val cheminRelatif: String,
    public val ligne: Int,
    public val colonne: Int,
    public val message: String,
)

/**
 * Port de l'éditeur **actif** pour l'IA (mission IA, ADR 0122) : lire
 * l'état courant (fichier actif, sélection, diagnostics) et appliquer un
 * lot d'édition **atomique** — un seul pas d'annulation (l'éditeur reste
 * l'annulable ; « Accepter » d'une carte de diff IA-5 se défaire d'un
 * seul Ctrl+Z, critère d'acceptation IA-5).
 *
 * Implémenté par le `EditorViewModel` de `:feature:editor`, publié dans
 * [RegistrePortEditeurActif] (même esprit que le port `SourceApercu` des
 * previews, ADR 0118) — aucune dépendance feature ↔ feature : le domaine
 * IA (`:core:ai`) lit ce port, l'éditeur n'entre jamais dans `:feature:ai`.
 *
 * Thread : appelé depuis le fil UI ou les dispatchers injectés du
 * domaine IA ; les implémentations ne bloquent jamais.
 */
public interface PortEditeurActif {
    /** Chemin RELATIF au projet du fichier actif, ou `null` (aucun onglet). */
    public val cheminRelatifFichier: String?

    /** Sélection courante, ou `null` (curseur sans sélection). */
    public val selection: SelectionEditeur?

    /** Diagnostics visibles pour le fichier actif (producteurs normalisés). */
    public val diagnostics: List<DiagnosticEditeur>

    /**
     * Révision du document actif (compteur de mutations, strictement
     * croissant) — détection des conflits IA-5 : un texte généré contre
     * une révision différente est refusé (« Accepter » propose de
     * relancer) au lieu d'écraser silencieusement.
     */
    public fun revision(): Long

    /**
     * Applique un remplacement ATOMIQUE dans le document actif (un seul
     * pas d'annulation, `beginBatch/endBatch` côté éditeur — ADR 0126).
     *
     * @param debut décalage du premier caractère remplacé (inclusif).
     * @param fin décalage après le dernier caractère remplacé (exclusif).
     * @param texte texte de substitution.
     * @return `AppResult.Erreur` si la révision a changé depuis la
     * génération (conflit — jamais d'écrasement silencieux) ou si la
     * plage est invalide ; `AppResult.Succes` sinon.
     */
    public fun appliquerRemplacement(
        debut: Int,
        fin: Int,
        texte: String,
    ): AppResult<Unit>
}
