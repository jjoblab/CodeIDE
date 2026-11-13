package jo.codeide.feature.ai

import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ClickableSpan
import android.text.style.StrikethroughSpan
import android.text.style.StyleSpan
import android.text.style.TypefaceSpan
import android.view.View
import androidx.core.net.toUri

/**
 * Spans **en ligne** du Markdown (mission IA, lot IA-3) : gras `**x**`,
 * italique `*x*` ou `_x_`, code en ligne `` `x` ``, lien `[t](url)`,
 * barré `~~x~~` (C3, retour propriétaire) — rendus dans un
 * [SpannableStringBuilder] (aucune dépendance, aucune vue Web).
 * L'analyse est une seule passe de plus court motif : le gras est
 * évalué avant l'italique (`**` gagne sur `*`), le barré avant le
 * gras en cas d'imbrication (`~~**x**~~` barré + gras).
 */
public object SpansMarkdown {
    /** Construit le texte spanné d'un paragraphe ou d'un élément de liste. */
    public fun de(texte: String): Spanned {
        val constructeur = SpannableStringBuilder()
        var index = 0
        while (index < texte.length) {
            val motif = prochainMotif(texte, index)
            if (motif == null) {
                constructeur.append(texte.substring(index))
                break
            }
            constructeur.append(texte.substring(index, motif.debut))
            val interieur = texte.substring(motif.debut + motif.marqueur.length, motif.fin)
            when (motif.type) {
                Type.GRAS -> constructeur.append(gras(interieur))
                Type.ITALIQUE -> constructeur.append(italique(interieur))
                Type.CODE -> constructeur.append(codeEnLigne(interieur))
                Type.BARRE -> constructeur.append(barre(interieur))
                Type.LIEN -> constructeur.append(lien(interieur, motif.url ?: "#"))
            }
            index = motif.suite
        }
        return constructeur
    }

    /** Un motif reconnu à partir de [depuis] — [Motif.suite] porte l'index
     * de reprise (fin du marqueur fermant, ou fin du lien complet). La
     * boucle ne fait que le MAXIMUM des candidats : chaque règle fermée
     * a son propre détecteur, sans saut dans la boucle. */
    private fun prochainMotif(texte: String, depuis: Int): Motif? {
        var meilleur: Motif? = null
        for (regle in REGLES) {
            val candidat = candidatDe(regle, texte, depuis)
            if (candidat != null && (meilleur == null || candidat.debut < meilleur.debut)) {
                meilleur = candidat
            }
        }
        return meilleur
    }

    /** Détecteur générique d'une règle à marqueur doublé (gras, italique, code). */
    private fun candidatMarqueur(regle: Regle, texte: String, depuis: Int): Motif? {
        val debut = texte.indexOf(regle.marqueur, depuis)
        if (debut < 0) return null
        val fin = texte.indexOf(regle.marqueur, debut + regle.marqueur.length)
        return if (fin < 0 || fin == debut + regle.marqueur.length) {
            null
        } else {
            Motif(regle.type, regle.marqueur, debut, fin, fin + regle.marqueur.length)
        }
    }

    /** Détecteur du lien `[texte](url)` : le marqueur fermant est « ) » —
     * une recherche de « [ » en doublé ne rendrait jamais un lien. Le
     * marqueur porte « [ » (l'extraction générique saute l'ouvrant) et
     * [Motif.suite] saute APRÈS le « ) » — l'URL n'est jamais dupliquée. */
    private fun candidatLien(texte: String, depuis: Int): Motif? {
        val correspondance = regexLien.find(texte, depuis) ?: return null
        return Motif(
            type = Type.LIEN,
            marqueur = "[",
            debut = correspondance.range.first,
            fin = correspondance.range.first + 1 + correspondance.groupValues[1].length,
            suite = correspondance.range.last + 1,
            url = correspondance.groupValues[2],
        )
    }

    private fun candidatDe(regle: Regle, texte: String, depuis: Int): Motif? = if (regle.type == Type.LIEN) {
        candidatLien(texte, depuis)
    } else {
        candidatMarqueur(regle, texte, depuis)
    }

    private fun gras(texte: String): Spanned {
        val spannable = SpannableStringBuilder(texte)
        spannable.setSpan(StyleSpan(android.graphics.Typeface.BOLD), 0, texte.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        return spannable
    }

    private fun italique(texte: String): Spanned {
        val spannable = SpannableStringBuilder(texte)
        spannable.setSpan(
            StyleSpan(android.graphics.Typeface.ITALIC),
            0,
            texte.length,
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
        )
        return spannable
    }

    private fun codeEnLigne(texte: String): Spanned {
        val spannable = SpannableStringBuilder(texte)
        spannable.setSpan(TypefaceSpan("monospace"), 0, texte.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        return spannable
    }

    /** Barré `~~x~~` (C3, retour propriétaire) — correction/retrait
     * lisible dans une réponse, même geste que l'éditeur. */
    private fun barre(texte: String): Spanned {
        val spannable = SpannableStringBuilder(texte)
        spannable.setSpan(StrikethroughSpan(), 0, texte.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        return spannable
    }

    private fun lien(texte: String, url: String): Spanned {
        val spannable = SpannableStringBuilder(texte)
        spannable.setSpan(
            object : ClickableSpan() {
                override fun onClick(vue: View) {
                    LienIa.ouvrir(url)
                }
            },
            0,
            texte.length,
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
        )
        return spannable
    }

    /** Règle de motif en ligne. */
    private data class Regle(val type: Type, val marqueur: String)

    /** Motif localisé dans le texte — [suite] : index de reprise du balayage. */
    private data class Motif(
        val type: Type,
        val marqueur: String,
        val debut: Int,
        val fin: Int,
        val suite: Int,
        val url: String? = null,
    )

    private enum class Type {
        GRAS,
        ITALIQUE,
        CODE,
        BARRE,
        LIEN,
    }

    private val REGLES =
        listOf(
            Regle(Type.BARRE, "~~"),
            Regle(Type.GRAS, "**"),
            Regle(Type.ITALIQUE, "*"),
            Regle(Type.ITALIQUE, "_"),
            Regle(Type.CODE, "`"),
            Regle(Type.LIEN, "["),
        )

    /** Lien `[texte](url)` : texte sans crochets, URL sans parenthèses. */
    private val regexLien = Regex("""\[([^\[\]]+)]\(([^()\s]+)\)""")
}

/** Ouverture de lien — séparé pour rester testable (aucune intention en test). */
public object LienIa {
    /** Callback d'ouverture posé par le fragment (aucune dépendance ici). */
    public var ouverture: ((String) -> Unit)? = null

    /** Ouvre l'URL via le callback posé, sans planter si aucun n'est posé. */
    public fun ouvrir(url: String) {
        try {
            url.toUri()
            ouverture?.invoke(url)
        } catch (
            @Suppress("SwallowedException", "TooGenericExceptionCaught") ignore: Exception,
        ) {
            // URL non analysable : ignorée silencieusement (donnée du modèle).
        }
    }
}
