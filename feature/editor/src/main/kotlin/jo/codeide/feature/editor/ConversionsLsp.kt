package jo.codeide.feature.editor

import jo.codeide.core.model.LspSettings
import jo.codeide.lsp.host.ConfigurationLsp

/**
 * Traduction des réglages LSP applicatifs vers la configuration de
 * l'HÔTE (lot LSP-2, ADR 0128 §4) — le SEUL point où [LspSettings]
 * (modèle pur, sans dépendance LSP) rencontre `:lsp:host`
 * [ConfigurationLsp] (frontière nette : `core:*` ne connaît que ses
 * propres types, miroir de la conversion des réglages IA).
 *
 * Champ à champ, sans transformation : les bornes vivent DÉJÀ dans les
 * deux modèles (mêmes contrats — `null` = Auto, `0` = surveillance
 * coupée, défauts = comportement historique de l'hôte v1.0.x–v1.2.0).
 */
internal fun LspSettings.versConfigurationHote(): ConfigurationLsp = ConfigurationLsp(
    budgetCacheOctets = budgetCacheOctets,
    surveillanceFichiersMs = surveillanceFichiersMs,
)
