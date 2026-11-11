package jo.codeide.feature.newproject

import android.content.Context

/**
 * Libellé affichable d'une étape du parcours : le socle et l'étape par
 * défaut « Configuration » portent des titres **applicatifs** (ressources
 * de la fonctionnalité), les étapes de modèle déclarées portent leur titre
 * résolu du dictionnaire (ADR 0155 — l'interface n'analyse aucun manifeste).
 */
internal fun EtatWizard.titreEtape(context: Context, etape: jo.codeide.core.model.EtapeParcours): String =
    if (etape.socle) {
        TITRES_SOCLE[etape.id]?.let(context::getString) ?: etape.id
    } else {
        etape.titre.ifBlank { context.getString(R.string.wizard_etape_configuration) }
    }
