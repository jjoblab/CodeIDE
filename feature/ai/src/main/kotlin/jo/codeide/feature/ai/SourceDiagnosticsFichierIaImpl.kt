package jo.codeide.feature.ai

import jo.codeide.core.ai.DiagnosticsFichierIa
import jo.codeide.core.ai.SourceDiagnosticsFichierIa
import jo.codeide.core.domain.RegistrePortEditeurActif
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Diagnostics du fichier ACTIF pour l'agent (rapport IA, chantier
 * IA-CODEIDE, lot 2, A5) : lit le [jo.codeide.core.domain.PortEditeurActif]
 * publié par l'éditeur dans [RegistrePortEditeurActif] — les diagnostics
 * ne sont exigés QUE pour le fichier ouvert (l'outil `diagnostics_lsp`
 * formule le message honnête pour tout autre fichier).
 *
 * Le port est non bloquant (lecture du champ volatile du registre —
 * miroir du chemin de l'assembleur de contexte du chat).
 */
@Singleton
public class SourceDiagnosticsFichierIaImpl
@Inject
constructor(private val registreEditeur: RegistrePortEditeurActif) :
    SourceDiagnosticsFichierIa {
    override fun diagnostics(): DiagnosticsFichierIa {
        val port = registreEditeur.actif() ?: return DiagnosticsFichierIa(cheminActif = null)
        return DiagnosticsFichierIa(
            cheminActif = port.cheminRelatifFichier,
            diagnostics = port.diagnostics,
        )
    }
}
