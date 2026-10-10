package jo.codeide.core.domain

import java.util.regex.Pattern

/**
 * Filtre des lignes de l'onglet Logcat (mission « Exécuter » R3) — PUR et
 * JVM-testable : l'interface ne fait que composer le filtre et l'appliquer,
 * aucune dépendance Android.
 *
 * Trois mécanismes combinés, façon Android Studio :
 *
 * 1. **Niveau minimal** : masque les niveaux INFÉRIEURS (l'ordre des
 *    valeurs de [NiveauJournal] EST l'ordre de sévérité) — choisir
 *    « Warning » ne laisse passer que W, E et F ;
 *
 * 2. **Texte** : sous-chaîne INSENSIBLE à la casse sur le message ET
 *    l'étiquette (chercher « main » retrouve les lignes de MainActivity
 *    comme celles dont le message parle de main) ;
 *
 * 3. **Regex** (option) : le texte devient une expression régulière
 *    appliquée au message seul. Un motif INVALIDE est signalé par
 *    [erreurMotif] (jamais d'exception — le filtre tombe alors en
 *    repli « aucune correspondance », l'interface affiche l'erreur en
 *    ligne : honnêteté plutôt qu'un effacement silencieux).
 *
 * @property niveauMinimal sévérité minimale affichée.
 * @property texte motif de recherche (sous-chaîne ou regex).
 * @property regex `true` : [texte] est une expression régulière.
 */
public data class FiltreLogcat(
    public val niveauMinimal: NiveauJournal = NiveauJournal.VERBEUX,
    public val texte: String = "",
    public val regex: Boolean = false,
) {
    /** Motif compilé (`null` : texte vide, ou regex invalide — repli
     *  signalé par [erreurMotif], jamais d'exception vers l'appelant). */
    private val motif: Pattern? =
        if (regex && texte.isNotEmpty()) {
            runCatching { Pattern.compile(texte) }.getOrNull()
        } else {
            null
        }

    /** Recherche pré-calculée du mode sous-chaîne (insensible à la casse). */
    private val recherche = texte.lowercase()

    /** `true` quand l'option regex est active mais le motif REJETÉ —
     *  l'interface l'affiche en ligne d'erreur sous le champ. */
    public val erreurMotif: Boolean
        get() = regex && texte.isNotEmpty() && motif == null

    /**
     * Une ligne passe-t-elle le filtre ? Pur : même liste, même verdict.
     */
    public fun accepte(ligne: LigneJournal): Boolean =
        when {
            ligne.niveau.ordinal < niveauMinimal.ordinal -> {
                false
            }

            texte.isEmpty() -> {
                true
            }

            // Motif invalide : AUCUNE ligne ne correspond (l'erreur est
            // signalée par [erreurMotif], pas par un crash).
            regex -> {
                motif?.matcher(ligne.message)?.find() == true
            }

            else -> {
                ligne.message.lowercase().contains(recherche) ||
                    ligne.etiquette.lowercase().contains(recherche)
            }
        }

    /** Filtre une liste entière — l'entrée n'est jamais copiée en amont. */
    public fun filtrer(lignes: List<LigneJournal>): List<LigneJournal> = lignes.filter(::accepte)
}
