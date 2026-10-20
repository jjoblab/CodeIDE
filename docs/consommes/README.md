# Documents de planification consommés

> Règle propriétaire du 2026-10-20 — AGENTS.md §8 : dès qu'un document
> de planification (brief, plan, revue, spécification d'écrans…) a
> **toutes ses étapes livrées et vérifiées** (lots poussés, CI verte,
> recette consignée), il quitte la racine de `docs/` et rejoint CE
> répertoire par `git mv` — **jamais supprimé** : l'historique Git et
> la traçabilité des décisions sont conservés.

- La racine de `docs/` ne garde que les documents **vivants** : ceux qui
  portent encore des étapes ouvertes, les références normatives
  (maquettes, jetons, spécifications d'usage) et la documentation
  opérationnelle.
- Au déplacement, les références croisées pointant vers l'ancien chemin
  sont mises à jour (`ROADMAP.md`, `README.md`, ADR concernées) et le
  déplacement est inscrit au `CHANGELOG.md`.
- Un document consommé **ne se modifie plus** ; toute re-référence cite
  son nouveau chemin (`docs/consommes/…`).

## Contenu

| Document | Consommé par | Version |
|---|---|---|
| `LSP_REVUE_REGLAGES.md` | Lots LSP-0 → LSP-4 (ADR 0128) | v0.162.0 (2026-10-21) |
| `LSP_SPEC_ECRANS.md` | Lots LSP-3 → LSP-4 (écrans L1–L5, ADR 0128 §4) | v0.162.0 (2026-10-21) |
