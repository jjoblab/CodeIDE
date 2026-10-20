package jo.codeide.preview.xml

import android.text.Layout
import android.widget.TextView

/**
 * Les DÉTECTIONS posées au RENDU (PE11g — moteur LayoutLib) pour les
 * règles de [ProblemesPreview] : « texte tronqué » (mesure du texte
 * posé, miroir `trunc` de `measure` de la maquette : `scrollWidth >
 * clientWidth + 1` sur le texte, `scrollHeight > clientHeight + 1` sur
 * une feuille) et « texte codé en dur » (`android:text` LITTÉRAL au
 * span de l'élément, miroir `v.lit` — la garde
 * [EditionAttributsXml.estLitteral] est celle de l'extraction).
 *
 * Extrait de [RenduLayoutLib] (la classe du pipeline reste au
 * pipeline — règle detekt LargeClass, patron `PopoverCalquesApercu`) :
 * ces fonctions lisent la vue RÉELLE après `render`, elles ne vivent
 * PAS dans [ProblemesPreview] (pur, sans Android).
 */
internal object ProblemesRendu {
    /**
     * Règle « texte tronqué » : une ligne ELLIPSÉE ou plus large que la
     * largeur utile, ou un contenu plus haut que la hauteur utile —
     * tolérance 1 px comme la maquette. Une vue non mesurée (pas encore
     * posée, pas de mise en page) ne dit RIEN — jamais de faux positif.
     */
    fun texteDepasse(vue: TextView): Boolean {
        val miseEnPage = vue.layout
        val largeurUtile = vue.width - vue.paddingLeft - vue.paddingRight
        val hauteurUtile = vue.height - vue.paddingTop - vue.paddingBottom
        return miseEnPage != null &&
            largeurUtile > 0 &&
            hauteurUtile > 0 &&
            (
                uneLigneDepasse(miseEnPage, largeurUtile) ||
                    miseEnPage.height > hauteurUtile + TOLERANCE_DEPASSEMENT_PX
            )
    }

    /**
     * Règle « texte codé en dur » au span de l'élément : `android:text`
     * existe et est un LITTÉRAL (ni `@string/…` ni `?attr/…`).
     */
    fun texteCodeDur(
        texte: String,
        debut: Int,
        fin: Int,
    ): Boolean =
        debut != InfoVueRendu.SANS_ELEMENT &&
            EditionAttributsXml.estLitteral(
                EditionAttributsXml.valeurAttribut(
                    texte,
                    InsertionXml.SpanParent(debut, fin),
                    ATTRIBUT_TEXTE,
                ),
            )

    /** Une ligne du texte déborde : ellipsée ou clippée en largeur. */
    private fun uneLigneDepasse(
        miseEnPage: Layout,
        largeurUtile: Int,
    ): Boolean =
        (0 until miseEnPage.lineCount).any { ligne ->
            miseEnPage.getEllipsisCount(ligne) > 0 ||
                miseEnPage.getLineWidth(ligne) > largeurUtile + TOLERANCE_DEPASSEMENT_PX
        }

    /** L'attribut du TEXTE d'une vue (règle « codé en dur »). */
    private const val ATTRIBUT_TEXTE: String = "android:text"

    /** Tolérance de débordement en px (miroir le `+ 1` de `measure`). */
    private const val TOLERANCE_DEPASSEMENT_PX: Int = 1
}
