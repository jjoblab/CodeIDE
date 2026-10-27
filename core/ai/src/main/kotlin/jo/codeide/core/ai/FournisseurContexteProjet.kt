package jo.codeide.core.ai

import jo.codeide.core.model.AppResult

/**
 * Port de lecture du PROJET pour l'assembleur de contexte (mission IA,
 * lot IA-4, ADR 0122) : `:core:ai` ne touche jamais le disque — le
 * consommateur (tiroir IA) fournit une implémentation liée par Hilt,
 * adossée au dossier réel du projet publié par l'éditeur.
 *
 * Les chemins échangés ici sont TOUJOURS RELATIFS au projet (ADR 0124
 * §4 : aucun chemin absolu de l'appareil ne part vers le fournisseur) ;
 * l'implémentation refait la garde d'évasion (`..`, hors racine).
 *
 * Thread : suspend, implémentations non bloquantes (dispatchers IO).
 */
public interface FournisseurContexteProjet {
    /**
     * Lit le contenu TEXTE d'un fichier du projet (chemin relatif).
     * Contenu borné (troncature documentée côté implémentation — la
     * puce reflète le contenu tronqué effectivement envoyé).
     */
    public suspend fun lireFichier(cheminRelatif: String): AppResult<String>

    /**
     * Liste les chemins RELATIFS du projet (bornée : nombre et
     * profondeur plafonnés côté implémentation ; les dossiers lourds
     * `.git`/`build`/`.gradle` sont élagués au balayage — l'exclusion
     * AUTHORITATIVE reste celle de [AssemblerContexte]).
     */
    public suspend fun listerChemins(): AppResult<List<String>>

    /**
     * Lignes du fichier optionnel `.codeide/ia-ignore` (vides si
     * absent) — ÉTEND la liste d'exclusion par défaut (ADR 0124 §4).
     */
    public suspend fun lireIaIgnore(): List<String>

    /**
     * Recherche TEXTE bornée dans le projet (rapport IA, lot 2, A5) :
     * motif LITTÉRAL (sensible à la casse), chemins RELATIFS, une
     * occurrence par ligne porteuse — le nombre de résultats est borné
     * par [limite], la parcourue par l'implémentation (dossiers lourds
     * et fichiers binaires écartés).
     */
    public suspend fun rechercherTexte(motif: String, limite: Int): AppResult<List<OccurrenceRechercheIa>>

    /**
     * Lecture d'un INTERVALLE de lignes (rapport IA, lot 2, A5) :
     * bornes 1-based INCLUSIVES, texte numéroté (`12| …`), bornes
     * repliées par l'implémentation (plafond de lignes par appel) —
     * [debut] au-delà de la fin du fichier = échec honnête.
     */
    public suspend fun lireLignes(cheminRelatif: String, debut: Int, fin: Int): AppResult<String>
}

/** Une occurrence de recherche texte (A5, rapport IA lot 2) — chemin, numéro de ligne, extrait. */
public data class OccurrenceRechercheIa(
    public val cheminRelatif: String,
    public val ligne: Int,
    public val extrait: String,
)
