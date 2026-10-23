package jo.codeide.core.ui

import kotlinx.coroutines.flow.StateFlow

/**
 * Port « complétion fantôme » (IA-8b, ADR 0129) : l'éditeur demande une
 * suggestion après la pause de saisie, annule à la frappe ; la logique
 * (réglages, fournisseur, plafond) vit côté implémentation (feature/ai) —
 * l'éditeur ne voit JAMAIS le fournisseur (contrat ponts IA, AGENTS).
 */
public interface PontCompletionFantomeIa {
    /** La fonction est-elle prête (opt-in actif, plafond non atteint) ? */
    public val disponible: StateFlow<Boolean>

    /**
     * Demande une suggestion au point de curseur. Une seule requête en
     * vol : un nouvel appel ANNULE le précédent (ADR 0129 §2). Le port
     * respecte lui-même le plafond et l'opt-in (répond `null` sinon).
     *
     * @param cheminRelatif fichier actif (journalisation sobre, sans contenu).
     * @param prefixe texte AVANT le curseur (déjà borné par l'appelant).
     * @param suffixe texte APRÈS le curseur (déjà borné).
     * @return le texte suggéré, ou `null` (indisponible/annulé/échec).
     */
    public suspend fun suggerer(cheminRelatif: String, prefixe: String, suffixe: String): String?

    /** Annule la requête en vol (frappe = annulation, ADR 0129 §2). */
    public fun annuler()

    /**
     * Note l'ACCEPTATION de [caracteres] suggérés (compteur visible,
     * plafond mensuel — ADR 0129 §3) ; désactive la fonction au plafond.
     */
    public suspend fun noterAcceptation(caracteres: Int)
}
