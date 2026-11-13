package jo.codeide.core.domain.templates

import jo.codeide.core.model.EtapeParcours
import jo.codeide.core.model.ParcoursCreation
import jo.codeide.core.model.TemplateFormEvaluation
import jo.codeide.core.model.TemplateSection
import javax.inject.Inject

/**
 * Cas d'usage « calculer le parcours de création » (ADR 0155 §3, lot L5a) :
 * du **manifeste** — porté par l'évaluation courante, dont les étapes
 * effectives ont été filtrées par le moteur (passe 10) — et de la
 * [TemplateFormEvaluation] courante, produit la liste ordonnée des étapes
 * réellement parcourues.
 *
 * Socle imposé par l'application (ADR 0155 §2, non déclarable) :
 * `modele` → [étapes de modèle, ordre déclaré] → `identite` (nom,
 * description, emplacement + paramètres `INFORMATION`) → `fichiers` →
 * `recapitulatif` ; l'écran de création reste hors numérotation.
 *
 * Fonction **pure** : aucune entrée-sortie, recalculable à chaque frappe —
 * l'interface l'appelle après chaque réévaluation du formulaire ; les
 * évaluations de paramètres sont attachées aux étapes par référence
 * (aucune copie d'état).
 */
public class CalculeParcoursUseCase
@Inject
constructor() {
    /**
     * Assemble le parcours effectif.
     *
     * @param evaluation évaluation courante du formulaire (étapes
     * effectives déjà calculées par le moteur, paramètres évalués).
     * @return le parcours ordonné — socle d'abord, étapes de modèle au
     * milieu, socle final ensuite.
     */
    public operator fun invoke(evaluation: TemplateFormEvaluation): ParcoursCreation {
        val parIdentifiant = evaluation.parameters.associateBy { it.parameterId }

        val etapesModele =
            evaluation.etapesWizard.map { etape ->
                EtapeParcours(
                    id = etape.id,
                    socle = false,
                    titre = etape.titre,
                    icone = etape.icone,
                    parametres = etape.parametres.mapNotNull(parIdentifiant::get),
                )
            }

        val identite =
            EtapeParcours(
                id = ID_SOCLE_IDENTITE,
                socle = true,
                // Libellé applicatif : « Identité » (ressource du wizard).
                titre = "",
                parametres =
                evaluation.parameters.filter {
                    it.section == TemplateSection.INFORMATION
                },
            )

        return ParcoursCreation(
            listOf(etapeModeleSocle) + etapesModele + identite + etapeFichiersSocle + etapeRecapitulatifSocle,
        )
    }

    private companion object {
        /** Identifiants du socle (ADR 0155 §2 — réservés au chargement). */
        const val ID_SOCLE_MODELE = "modele"
        const val ID_SOCLE_IDENTITE = "identite"
        const val ID_SOCLE_FICHIERS = "fichiers"
        const val ID_SOCLE_RECAPITULATIF = "recapitulatif"

        /** Étapes socle sans paramètre (titre applicatif, vide). */
        val etapeModeleSocle = EtapeParcours(id = ID_SOCLE_MODELE, socle = true)
        val etapeFichiersSocle = EtapeParcours(id = ID_SOCLE_FICHIERS, socle = true)
        val etapeRecapitulatifSocle = EtapeParcours(id = ID_SOCLE_RECAPITULATIF, socle = true)
    }
}
