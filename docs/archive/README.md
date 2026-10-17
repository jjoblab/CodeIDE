# Archive des documents

Documents **achevés ou supplantés**, conservés pour l'historique. Leur
contenu décrit un état passé du dépôt — ne pas s'y fier pour l'état
actuel : `README.md`, `docs/ARCHITECTURE.md`, `docs/ROADMAP.md` et le
`CHANGELOG.md` font foi.

Archivage du 2026-10-18 (état du dépôt : v0.134.44).

| Document | Statut à l'archivage |
|---|---|
| `PLAN_PROCHAINES_ETAPES.md` | Planification post-v0.125.1 (14/10/2026) — supplantée par `docs/ROADMAP.md` et `docs/IA_PLAN_EXECUTION.md` |
| `PLAN_SEPARATION_DEPOTS.md` | Séparation des dépôts frères **terminée** : 18 paquets publics (tooling 1.0.0, lsp 1.0.2, LayoutLib 3.4.7), consommation binaire par défaut (v0.134.44, `scripts/bump-frere.sh`), CI sans clone + garde-fou composite nocturne |
| `PLAN_STABILISATION_LSP.md` | Stabilisation LSP **terminée** : moteurs Java/Kotlin/XML stabilisés puis déplacés dans `jjoblab/codeide-lsp` (v1.0.2, CI verte) |
| `audit/` | Rapports d'analyse et audits de phase (prompts du 2026-10-13) — constats traités ou reportés dans les ADR correspondantes |
| `tooling-scenarios/` | Rapport Phase 0 des scénarios de sync + fixtures + sorties — catalogue d'étapes livré (ADR 0065/0096) ; le script générateur `scripts/scenarios-tooling.sh` écrit désormais ici |

Les ADR (`docs/adr/`) ne sont **pas** archivables : elles constituent le
journal des décisions, y compris celles abandonnées (voir ADR 0119 pour
un exemple de renonciation).
