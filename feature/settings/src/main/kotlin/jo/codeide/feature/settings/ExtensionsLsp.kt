package jo.codeide.feature.settings

import androidx.fragment.app.Fragment
import jo.codeide.core.ui.NiveauEtat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Résolution au DERNIER moment des textes du présentateur (patron
 * `PresentationIndexation`) — l'UI seule touche `Context`. Les
 * ressources du présentateur portent AU PLUS DEUX arguments : la
 * résolution est bornée par taille, jamais par copie de tableau.
 */
internal fun Fragment.resoudreTexteLsp(texte: TexteLsp): String =
    when (texte) {
        is TexteLsp.Ressource -> {
            when (texte.args.size) {
                0 -> getString(texte.id)
                1 -> getString(texte.id, texte.args[0])
                else -> getString(texte.id, texte.args[0], texte.args[1])
            }
        }

        is TexteLsp.Pluriel -> {
            resources.getQuantityString(texte.id, texte.nombre, texte.nombre)
        }

        is TexteLsp.Brut -> {
            texte.texte
        }
    }

/**
 * Niveau de la pastille (IndicateurEtat, core:ui) — la FORME porte
 * l'état, jamais la couleur seule (spec LSP § 5) ; les états neutres
 * (inactif, démarrage) partagent le cercle ouvert.
 */
internal fun EtatMoteurAffiche.versNiveau(): NiveauEtat =
    when (this) {
        EtatMoteurAffiche.INACTIF -> NiveauEtat.NEUTRE
        EtatMoteurAffiche.DEMARRAGE -> NiveauEtat.NEUTRE
        EtatMoteurAffiche.EN_COURS -> NiveauEtat.AVERTISSEMENT
        EtatMoteurAffiche.PRET -> NiveauEtat.OK
        EtatMoteurAffiche.PARTIEL -> NiveauEtat.AVERTISSEMENT
        EtatMoteurAffiche.ERREUR -> NiveauEtat.ERREUR
    }

/** Libellé court de la pastille pour un état donné. */
internal val EtatMoteurAffiche.libelle: Int
    get() =
        when (this) {
            EtatMoteurAffiche.INACTIF -> R.string.lsp_etat_inactif
            EtatMoteurAffiche.DEMARRAGE -> R.string.lsp_etat_demarrage
            EtatMoteurAffiche.EN_COURS -> R.string.lsp_etat_en_cours
            EtatMoteurAffiche.PRET -> R.string.lsp_etat_pret
            EtatMoteurAffiche.PARTIEL -> R.string.lsp_etat_partiel
            EtatMoteurAffiche.ERREUR -> R.string.lsp_etat_erreur
        }

/**
 * Heure absolue du dernier passage (L5) — `HH:mm` sur l'horloge locale ;
 * jamais un « il y a X » approximatif (fiabilité non tranchée, L6).
 */
internal fun heureDePassage(horodatageMs: Long): String =
    SimpleDateFormat(FORMAT_HEURE, Locale.getDefault()).format(Date(horodatageMs))

private const val FORMAT_HEURE = "HH:mm"
