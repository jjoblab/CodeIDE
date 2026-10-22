package jo.codeide.feature.preview

import android.content.Context
import jo.codeide.preview.xml.CalquesApercu
import jo.codeide.preview.xml.ContraintesHierarchie
import jo.codeide.preview.xml.InfoVueRendu
import jo.codeide.preview.xml.ProblemesFamilles
import jo.codeide.preview.xml.ProblemesPreview
import java.util.Locale

/**
 * Les LIBELLÉS localisés des problèmes (PE6c, étendus PE11g puis PE12f) —
 * composés par l'UI à partir du [ProblemesPreview.GenreProbleme] : les
 * données pures portent le FAIT, jamais le texte. Partagés par l'onglet
 * Problèmes (rangée : titre, libellé de la vue, message) et les cartes
 * de CORRECTION RAPIDE du volet Attributs (PE11g — miroir `p.title` /
 * `p.msg` de `computeProblems` de la maquette). Les cinq genres de
 * FAMILLE (PE12f) passent par [titreFamille] / [messageFamille] : leur
 * message se compose des données du problème TEXTE (attributs, cible,
 * cycle…), pas d'une vue rendue.
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

            // Les genres de FAMILLE (PE12f) et de CONTENANCE (v0.170.0)
            // portent leurs libellés par [titreFamille] (le problème
            // TEXTE en porte les données) — jamais ici : garde de
            // programmation, pas un chemin de rendu.
            ProblemesPreview.GenreProbleme.ATTRIBUT_ORPHELIN,
            ProblemesPreview.GenreProbleme.REFERENCE_INTROUVABLE,
            ProblemesPreview.GenreProbleme.ANCRE_INVALIDE,
            ProblemesPreview.GenreProbleme.COMPORTEMENT_MANQUANT,
            ProblemesPreview.GenreProbleme.DEPENDANCE_CIRCULAIRE,
            ProblemesPreview.GenreProbleme.ENFANTS_INTERDITS,
            ProblemesPreview.GenreProbleme.ENFANTS_ADAPTATEUR,
            ProblemesPreview.GenreProbleme.ENFANT_UNIQUE_DEPASSE,
            ProblemesPreview.GenreProbleme.ENFANT_INATTENDU,
            -> {
                error("genre de famille : libellé par titreFamille")
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

            // Même garde que [titre] : les messages de famille vivent dans
            // [messageFamille], composés des données du problème TEXTE.
            ProblemesPreview.GenreProbleme.ATTRIBUT_ORPHELIN,
            ProblemesPreview.GenreProbleme.REFERENCE_INTROUVABLE,
            ProblemesPreview.GenreProbleme.ANCRE_INVALIDE,
            ProblemesPreview.GenreProbleme.COMPORTEMENT_MANQUANT,
            ProblemesPreview.GenreProbleme.DEPENDANCE_CIRCULAIRE,
            ProblemesPreview.GenreProbleme.ENFANTS_INTERDITS,
            ProblemesPreview.GenreProbleme.ENFANTS_ADAPTATEUR,
            ProblemesPreview.GenreProbleme.ENFANT_UNIQUE_DEPASSE,
            ProblemesPreview.GenreProbleme.ENFANT_INATTENDU,
            -> {
                error("genre de famille : message par messageFamille")
            }
        }

    /**
     * Le TITRE localisé d'un problème de FAMILLE (PE12f — miroir les
     * `title` de la moitié famille de `computeProblems` : ils ne nomment
     * PAS la classe de la vue, le titre suffit).
     */
    internal fun titreFamille(
        contexte: Context,
        probleme: ProblemesFamilles.Probleme,
    ): String =
        when (probleme.genre) {
            ProblemesPreview.GenreProbleme.ATTRIBUT_ORPHELIN -> {
                contexte.getString(R.string.preview_probleme_orphelin_titre)
            }

            ProblemesPreview.GenreProbleme.REFERENCE_INTROUVABLE -> {
                contexte.getString(R.string.preview_probleme_reference_titre)
            }

            ProblemesPreview.GenreProbleme.ANCRE_INVALIDE -> {
                contexte.getString(R.string.preview_probleme_ancre_titre)
            }

            ProblemesPreview.GenreProbleme.COMPORTEMENT_MANQUANT -> {
                contexte.getString(R.string.preview_probleme_comportement_titre)
            }

            ProblemesPreview.GenreProbleme.DEPENDANCE_CIRCULAIRE -> {
                contexte.getString(R.string.preview_probleme_boucle_titre)
            }

            // v0.170.0 — les genres de CONTENANCE (S3) : le TITRE suffit,
            // le message nomme le conteneur.
            ProblemesPreview.GenreProbleme.ENFANTS_INTERDITS -> {
                contexte.getString(R.string.preview_probleme_feuille_titre)
            }

            ProblemesPreview.GenreProbleme.ENFANTS_ADAPTATEUR -> {
                contexte.getString(R.string.preview_probleme_adaptateur_titre)
            }

            ProblemesPreview.GenreProbleme.ENFANT_UNIQUE_DEPASSE -> {
                contexte.getString(R.string.preview_probleme_unique_titre)
            }

            ProblemesPreview.GenreProbleme.ENFANT_INATTENDU -> {
                contexte.getString(R.string.preview_probleme_inattendu_titre)
            }

            // Garde de programmation : les genres de RENDU passent par [titre].
            else -> {
                error("genre de rendu : libellé par titre — $probleme")
            }
        }

    /**
     * Le MESSAGE localisé d'un problème de FAMILLE (PE12f) : les noms
     * d'attributs et de classes ne se traduisent pas (spec § 13) — seuls
     * les attributs SANS préfixe d'espace de noms paraissent (miroir
     * `n.replace(/^\w+:/, '')` de la maquette).
     */
    internal fun messageFamille(
        contexte: Context,
        probleme: ProblemesFamilles.Probleme,
    ): String =
        when (probleme.genre) {
            ProblemesPreview.GenreProbleme.ATTRIBUT_ORPHELIN -> {
                contexte.getString(
                    R.string.preview_probleme_orphelin_detail,
                    probleme.attributs.joinToString(", ") { it.substringAfter(':') },
                    probleme.libelleParent,
                )
            }

            ProblemesPreview.GenreProbleme.REFERENCE_INTROUVABLE -> {
                contexte.getString(
                    R.string.preview_probleme_reference_detail,
                    probleme.attributs.first().substringAfter(':'),
                    probleme.cible.orEmpty(),
                )
            }

            ProblemesPreview.GenreProbleme.ANCRE_INVALIDE -> {
                contexte.getString(R.string.preview_probleme_ancre_detail, probleme.cible.orEmpty())
            }

            ProblemesPreview.GenreProbleme.COMPORTEMENT_MANQUANT -> {
                contexte.getString(R.string.preview_probleme_comportement_detail)
            }

            ProblemesPreview.GenreProbleme.DEPENDANCE_CIRCULAIRE -> {
                contexte.getString(
                    R.string.preview_probleme_boucle_detail,
                    probleme.cycle.joinToString(" → ") { "@id/$it" },
                )
            }

            // v0.170.0 — contenance : le conteneur au NOM SIMPLE (spec
            // § 13 : les classes ne se traduisent pas, le nom local
            // suffit — « TextInputLayout », pas le FQCN).
            ProblemesPreview.GenreProbleme.ENFANTS_INTERDITS -> {
                contexte.getString(
                    R.string.preview_probleme_feuille_detail,
                    probleme.balise.substringAfterLast('.'),
                )
            }

            ProblemesPreview.GenreProbleme.ENFANTS_ADAPTATEUR -> {
                contexte.getString(
                    R.string.preview_probleme_adaptateur_detail,
                    probleme.balise.substringAfterLast('.'),
                )
            }

            ProblemesPreview.GenreProbleme.ENFANT_UNIQUE_DEPASSE -> {
                contexte.getString(
                    R.string.preview_probleme_unique_detail,
                    probleme.balise.substringAfterLast('.'),
                )
            }

            ProblemesPreview.GenreProbleme.ENFANT_INATTENDU -> {
                contexte.getString(
                    R.string.preview_probleme_inattendu_detail,
                    probleme.balise.substringAfterLast('.'),
                    ContraintesHierarchie.baliseEnfantAttendu(probleme.balise).orEmpty(),
                )
            }

            // Garde de programmation : les genres de RENDU passent par [message].
            else -> {
                error("genre de rendu : message par message — $probleme")
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
