# feature/ai

Feature Android (mission IA, ADR 0122) : tiroir IA (Chat / Agent /
Historique), écrans Paramètres → IA, consentement de premier usage,
rendu Markdown. Vues XML + ViewBinding, Material 3 (ADR 0002) ;
ressources FR (values/) et EN (values-en/).

EXCEPTION FEATURE EXPLICITE : `:feature:ai` → `:core:ai` (la logique
métier IA vit dans le domaine) — enregistrée dans le ModuleRulesPlugin.
`:feature:editor` n'entre JAMAIS ici : contrats `PontIaEditeur`
(core:ui) et `PortEditeurActif` (core:domain).

Statut : IA-0 (v0.135.0) — échafaudage ; fragment du tiroir au lot IA-3.
