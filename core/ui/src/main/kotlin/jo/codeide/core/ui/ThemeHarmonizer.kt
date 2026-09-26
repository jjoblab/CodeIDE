package jo.codeide.core.ui

import android.content.Context
import androidx.annotation.AttrRes
import androidx.annotation.ColorInt
import androidx.annotation.ColorRes
import androidx.core.content.ContextCompat
import com.google.android.material.color.MaterialColors

/**
 * Point d'application centralisé de l'harmonisation (ADR 0059) : les
 * tokens de couleur « de marque » (canaux tooling, accents par langage,
 * succès) ne restent pas figés quand les couleurs dynamiques Material You
 * sont actives — ils se rapprochent subtilement de la teinte primaire de
 * l'utilisateur via [MaterialColors.harmonize].
 *
 * L'harmonisation est **locale à la consommation** : les valeurs de
 * `values/colors.xml` restent la base jour/nuit (donc correctes même
 * sans appel), et chaque consommateur programmatique (adaptateurs de
 * console, badges de canal) passe par [harmoniserAvecPrimaire] au lieu
 * de lire la ressource brute. La source d'harmonisation est le
 * `colorPrimary` du **thème courant** — dynamique (fond d'écran, Android
 * 12+) ou de marque selon le réglage.
 *
 * Ne concerne QUE les couleurs de marque ; les teintes fonctionnelles
 * (journal, statuts Git, erreurs) gardent leur valeur — la sévérité doit
 * rester reconnaissable quel que soit le fond d'écran.
 */
public object ThemeHarmonizer {
    /**
     * Harmonise une couleur de base avec le `colorPrimary` du thème
     * courant.
     *
     * @param context contexte portant le thème résolu (activité).
     * @param base couleur de marque, en tant que ressource.
     * @return la couleur effective, teintée vers le primaire courant.
     */
    @ColorInt
    public fun harmoniserAvecPrimaire(
        context: Context,
        @ColorRes base: Int,
    ): Int = MaterialColors.harmonize(ContextCompat.getColor(context, base), primaire(context))

    /**
     * Résout un attr de thème étendu (ex. `colorCanalSync`) puis
     * l'harmonise avec le primaire — chemin complet pour les couleurs
     * de marque exposées comme attrs.
     *
     * @param context contexte portant le thème résolu (activité).
     * @param attr attribut de thème (déclaré dans attrs.xml de core:ui).
     * @return la couleur effective, teintée vers le primaire courant.
     */
    @ColorInt
    public fun harmoniserAttrAvecPrimaire(
        context: Context,
        @AttrRes attr: Int,
    ): Int {
        val base =
            MaterialColors.getColor(
                context,
                attr,
                "attr de couleur étendu introuvable dans le thème",
            )
        return MaterialColors.harmonize(base, primaire(context))
    }

    /** Primaire courant du thème — dynamique ou de marque selon le réglage. */
    @ColorInt
    private fun primaire(context: Context): Int =
        MaterialColors.getColor(
            context,
            androidx.appcompat.R.attr.colorPrimary,
            "colorPrimary introuvable",
        )
}
