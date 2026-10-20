package jo.codeide.feature.preview

import android.content.Context
import jo.codeide.preview.xml.CalquesApercu
import jo.codeide.preview.xml.InfoVueRendu
import jo.codeide.preview.xml.ProblemesPreview
import java.util.Locale

/**
 * Les LIBELLÉS localisés des problèmes (PE6c, étendus PE11g) — composés
 * par l'UI à partir du [ProblemesPreview.GenreProbleme] : les données
 * pures portent le FAIT, jamais le texte. Partagés par l'onglet
 * Problèmes (rangée : titre, libellé de la vue, message) et les cartes
 * de CORRECTION RAPIDE du volet Attributs (PE11g — miroir `p.title` /
 * `p.msg` de `computeProblems` de la maquette).
 */
internal object LibellesProblemes {
    /** Format d'une échelle non entière (une décimale, celle du locale). */
    private const val FORMAT_DECIMALE: String = "%.1f"

    /**
     * Le TITRE localisé d'un problème (miroir `p.title`) — la classe de
     * la vue fautive habille les genres qui la nomment.
     */
    internal fun titre(
        contexte: Context,
        genre: ProblemesPreview.GenreProbleme,
        vueFautive: InfoVueRendu?,
    ): String =
        when (genre) {
            ProblemesPreview.GenreProbleme.CLASSE_ABSENTE -> {
                contexte.getString(
                    R.string.preview_probleme_classe_absente_titre,
                    vueFautive?.classe ?: "",
                )
            }

            ProblemesPreview.GenreProbleme.SANS_CONTENT_DESCRIPTION -> {
                contexte.getString(
                    R.string.preview_probleme_sans_description_titre,
                    vueFautive?.classe ?: "",
                )
            }

            ProblemesPreview.GenreProbleme.TEXTE_CODE_DUR -> {
                contexte.getString(R.string.preview_probleme_texte_code_titre)
            }

            ProblemesPreview.GenreProbleme.CIBLE_TACTILE -> {
                contexte.getString(R.string.preview_probleme_cible_tactile_titre)
            }

            ProblemesPreview.GenreProbleme.TEXTE_TRONQUE -> {
                contexte.getString(R.string.preview_probleme_tronque_titre)
            }
        }

    /**
     * Le MESSAGE localisé d'un problème (miroir `p.msg`) — la cible
     * parle en dp du PROFIL, le texte tronqué nomme l'échelle de police
     * (miroir `String(S.fs).replace('.', ',')` : « ×1,3 » en français).
     */
    internal fun message(
        contexte: Context,
        genre: ProblemesPreview.GenreProbleme,
        vueFautive: InfoVueRendu?,
        densiteDpi: Int,
        echellePolice: Float,
    ): String =
        when (genre) {
            ProblemesPreview.GenreProbleme.CLASSE_ABSENTE -> {
                contexte.getString(R.string.preview_probleme_classe_absente_message)
            }

            ProblemesPreview.GenreProbleme.SANS_CONTENT_DESCRIPTION -> {
                contexte.getString(R.string.preview_probleme_sans_description_message)
            }

            ProblemesPreview.GenreProbleme.TEXTE_CODE_DUR -> {
                contexte.getString(R.string.preview_probleme_texte_code_message)
            }

            ProblemesPreview.GenreProbleme.CIBLE_TACTILE -> {
                if (vueFautive != null && densiteDpi > 0) {
                    contexte.getString(
                        R.string.preview_probleme_cible_tactile_message,
                        CalquesApercu.pxVersDp(vueFautive.largeurPx, densiteDpi),
                        CalquesApercu.pxVersDp(vueFautive.hauteurPx, densiteDpi),
                    )
                } else {
                    contexte.getString(R.string.preview_probleme_cible_tactile_message_sans_mesure)
                }
            }

            ProblemesPreview.GenreProbleme.TEXTE_TRONQUE -> {
                contexte.getString(R.string.preview_probleme_tronque_message, echelleAffichee(contexte, echellePolice))
            }
        }

    /**
     * L'échelle AFFICHÉE (miroir `String(S.fs).replace('.', ',')`) :
     * entière sans décimale (« ×2 »), sinon UNE décimale du locale —
     * « ×1,3 » en français, « ×1.3 » en anglais.
     */
    private fun echelleAffichee(
        contexte: Context,
        echelle: Float,
    ): String =
        if (echelle % 1f == 0f) {
            echelle.toInt().toString()
        } else {
            String.format(contexte.resources.configuration.locales[0], FORMAT_DECIMALE, echelle)
        }
}
