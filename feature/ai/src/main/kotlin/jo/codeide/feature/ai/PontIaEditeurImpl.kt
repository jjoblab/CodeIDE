package jo.codeide.feature.ai

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import jo.codeide.core.domain.RegistrePortEditeurActif
import jo.codeide.core.ui.PontIaEditeur
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Implémentation du pont éditeur → IA (mission IA, lot IA-5, ADR 0126)
 * : publie sur [CanalSignauxIa] ce que `:feature:editor` déclenche —
 * barre de consigne, « Corriger avec l'IA » (Problèmes), « Expliquer »
 * (Console/Logcat), « Réessayer » (carte de diff). Aucun réseau ici :
 * le chat reste le seul endroit où une requête part, et toute action
 * vient d'un geste utilisateur (ADR 0121 point 3).
 *
 * Les textes transmis sont **bornés** : la sortie console/logcat est
 * tronquée à une fenêtre glissante (les N dernières lignes — les
 * premières d'un build de 10 minutes n'aident pas le modèle), les
 * chemins restent relatifs (ADR 0124 §4).
 */
@Singleton
public class PontIaEditeurImpl
@Inject
constructor(
    @param:ApplicationContext private val contexte: Context,
    private val registreEditeur: RegistrePortEditeurActif,
    private val canal: CanalSignauxIa,
) : PontIaEditeur {
    override fun envoyerConsigne(consigne: String) {
        if (consigne.isBlank()) {
            return
        }
        canal.publier(SignalIaExterne.Consigne(texte = consigne, envoiImmediat = true))
    }

    override fun corrigerDiagnostic(cheminRelatif: String, identifiant: String) {
        val port = registreEditeur.actif() ?: return
        val diagnostic =
            port.diagnostics
                .firstOrNull { it.identifiant == identifiant && it.cheminRelatif == cheminRelatif }
                ?: port.diagnostics.firstOrNull { it.identifiant == identifiant }
                ?: return
        val consigne =
            contexte.getString(R.string.ia_consigne_corriger, diagnostic.cheminRelatif) +
                diagnostic.message +
                "\n"
        canal.publier(SignalIaExterne.Consigne(texte = consigne, envoiImmediat = false))
    }

    override fun expliquer(texteBrut: String) {
        if (texteBrut.isBlank()) {
            return
        }
        val fenetre = texteBrut.lines().takeLast(LIGNES_MAX).joinToString("\n")
        val consigne =
            contexte.getString(R.string.ia_consigne_expliquer_sortie) +
                "```\n$fenetre\n```\n"
        canal.publier(SignalIaExterne.Consigne(texte = consigne, envoiImmediat = false))
    }

    override fun reessayer() {
        canal.publier(SignalIaExterne.Regenerer)
    }

    private companion object {
        /** Fenêtre glissante d'une sortie à expliquer (lignes). */
        private const val LIGNES_MAX = 200
    }
}
