# Lot « familles de parents » (PE12) — à copier à la racine du dépôt CodeIDE

Ce lot ne contient que du **neuf** : il complète les lots précédents (PE11, ADR 0131-0134 déjà dans le dépôt). Rien n'écrase du code ; seul `docs/` reçoit des fichiers (les ressources de `docs/mockups/ref/ressources/` sont à copier ensuite dans `feature/preview/src/main/res/` par le chantier PE12c/d).

| Chemin | Contenu |
|---|---|
| `PE12-parents-brief.md` | Brief à coller dans la session de l'agent (ne va pas dans le dépôt). Chantiers PE12a à PE12h. |
| `docs/PREVIEW_PARENTS.md` | Spécification normative, partie III : audit, modèle `FamilleParent`, règles Android par famille, moteur, inspecteur, dépôt, popup, calque Relations, problèmes, suppression, palette, recette. |
| `docs/adr/0135-apercu-familles-de-parents.md` | ADR (proposé ; D6 marge 16 dp et D9 cibles tactiles à confirmer). Renuméroter si 0135 est pris. |
| `docs/mockups/apercu-xml.html` | Maquette = v6 du dépôt (redimensionnement par poignée, **avec correctif du relâchement**) + section « Parents et enfants » (quatre documents). Remplace celle du dépôt. |
| `docs/mockups/ref/parents/` | `exemples/` (3 layouts + valeurs fr/en, fixtures de test), `insertions_or.json` (12 insertions en or), `meas_popups_parents.json`, captures clair. |
| `docs/mockups/ref/ressources/` | Nouvelles ressources de référence : `values/{dimens,styles,strings}_parents.xml`, `values-en/strings_parents.xml`, `layout/popover_placement_parents.xml` (remplace `popover_placement.xml`), `item_position_bouton.xml`, `item_cellule_gravite.xml`, `controle_reference_id.xml`. |

**Ordre** : PE12a → PE12b → PE12c → PE12d → PE12e → PE12f → PE12g → PE12h. PE12 est indépendant de PE11e-h, sauf le contrôle `ReferenceId` (voir PE12c).
Les 39 jetons de la maquette sont inchangés (parité vérifiée) : aucun jeton à ajouter.
