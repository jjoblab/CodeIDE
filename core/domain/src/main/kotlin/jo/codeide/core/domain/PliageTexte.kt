package jo.codeide.core.domain

import java.text.Normalizer

/**
 * Le pliage canonique de recherche du Centre de sorties (lot L2, ADR 0160
 * § Décision 3) — NFD puis suppression des marques combinantes puis casse,
 * selon les options du filtre. UNE SEULE définition pour les TROIS
 * consommateurs : le filtre unifié ([FiltreSorties] — héritage ADR 0025),
 * le cache de pliage du tampon ([TamponSorties]) et le moteur de recherche
 * ([RechercheSorties]). Une divergence de pliage ferait se CROISER les
 * résultats du filtre et de la recherche : « theses » devrait trouver
 * « Thèses » partout ou nulle part.
 *
 * Pur JVM : [Normalizer] est `java.text.Normalizer` — aucun Android.
 */
public object PliageTexte {

    /**
     * Plie un texte selon les options de recherche.
     *
     * @param texte texte brut à plier.
     * @param sensibleCasse `true` : la casse compte (sinon minuscules).
     * @param sensibleAccents `true` : les accents comptent (sinon NFD +
     *           suppression des marques `\p{M}`).
     * @return le texte plié, prêt pour `contains` ou bornage de mot.
     */
    public fun plier(texte: String, sensibleCasse: Boolean, sensibleAccents: Boolean): String {
        var resultat = texte
        if (!sensibleAccents) {
            resultat = Normalizer.normalize(resultat, Normalizer.Form.NFD)
                .replace(REGEX_MARQUES, "")
        }
        if (!sensibleCasse) {
            resultat = resultat.lowercase()
        }
        return resultat
    }

    /** Marques combinantes (accents) supprimées au pliage NFD. */
    private val REGEX_MARQUES = Regex("\\p{M}+")
}
