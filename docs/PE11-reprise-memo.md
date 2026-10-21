# PE11 — Mémo de reprise de la deuxième vague (à lire avant tout chantier de l'annexe § 7)

> Rédigé le 2026-10-21 après PE12b (v0.166.2), la série PE12 (familles de
> parents) se poursuit (PE12c-h) **avant** la reprise de la deuxième vague.
> Ce mémo consigne ce que cette série change pour elle, les trois vigilances
> connues et la liste de contrôle de reprise. Sources : `docs/PE11-attributs-brief.md`
> (annexe § 7 — le catalogue curé), `docs/PREVIEW_PARENTS.md` § 5,
> `docs/PREVIEW_UI.md`, maquette `docs/mockups/apercu-xml.html` (v6,
> `sectionsFor`).

## 1. Ce que la deuxième vague recouvre (rappel)

- **PE11g-contrôles** : les contrôles CURSEUR et RATIO (layouts de référence
  `controle_curseur.xml`, `controle_ratio.xml`, cotes dans `PREVIEW_UI.md`).
- **PE11h** : l'action rapide par section (ActionRapide), l'ATTRIBUT LIBRE et
  les CONTRAINTES EN LECTURE (`controle_action.xml`, `controle_attribut_libre.xml`,
  `controle_contraintes_lecture.xml`).
- **L'annexe § 7 du brief** : le catalogue curé complet (~130 attributs) —
  sections communes (Apparence, Transformation, Interaction, Accessibilité,
  Aperçu uniquement…) et sections par famille (Typographie, Lignes et
  débordement, Icônes du texte, Bouton, Contraintes, État, Saisie, Champ,
  Aides, Icônes, Contour, Image, Conteneur, Carte, Progression, Liste, Racine).
- Les pièces déjà livrées mais **inertes** en attendant § 7 : la bascule
  Courants/Tous et le bouton « Afficher les N autres » (PE11f).

## 2. Ce que PE12 (une fois PE12c-h livrée) change pour cette reprise

| Sujet | Effet sur la reprise |
|---|---|
| `GenreParent` supprimé (PE12b) | le brief PE11 § 2 (rédigé à v0.134) décrit `sections(classe, genreParent)` ; la signature vivante est `sections(classe, famille: FamilleParent)` |
| `ControleAttribut.ReferenceId` (PE12c) | créé ENRICHI : `ReferenceId(nom, etiquette, portee)` à trois portées (`TOUT`, `FRERES`, `SOUS_ARBRE`), option « aucun » en tête, option fermant une boucle désactivée et suffixée « (boucle) », valeur introuvable en dernier « (introuvable) ». Le brief PE11 § 4 décrivait la version simple `ReferenceId(nom, etiquette)` : **utiliser la version enrichie** — `labelFor` (§ 7, Accessibilité) en profite tel quel |
| `TitreSection` (PE12c) | nouveaux titres « Barre d'application » et « Positions relatives » ; `DANS_PARENT` et `CONTENEUR` étendus par famille — **aucun titre de § 7 n'entre en collision** ; la CONTRAINTE en lecture § 7 (enfants ConstraintLayout) reste distincte des POSITIONS RELATIVES PE12c (bascules d'ancrage, enfants RelativeLayout) |
| Problèmes et correctifs (PE12f) | l'infrastructure `ProblemesPreview` (cinq genres) est livrée : les corrections rapides § 7 s'y raccordent au lieu d'être réinventées |
| Suppression et renommage (PE12g) | `RenommageIdentifiant` fait déjà suivre `labelFor` (attribut § 7) — non-régression testée |
| Maquette | la v6 contient les DEUX mondes (calques parents + `sectionsFor` deuxième vague) : source de vérité unifiée, rien à re-basculer |

## 3. Les trois vigilances

1. **« Conteneur » par famille fait foi.** L'annexe § 7 décrit un Conteneur
   plat « LinearLayout, MaterialCardView » (`orientation`, `gravity`…) ;
   `PREVIEW_PARENTS.md` § 5.1 rend la section Conteneur **par famille**
   (LINEAR ≠ FRAME ≠ RELATIVE ≠ COORDINATOR ≠ APPBAR). La liste LINEAR est
   identique mot pour mot dans les deux documents ; l'écart ne concerne que
   MaterialCardView, à qui § 7 prête des attributs sans objet pour un
   FrameLayout. **Règle : à la reprise, ne PAS écraser la version par
   famille** — MaterialCardView garde le Conteneur FRAME + sa section Carte.
2. **Le § 2 du brief PE11 est périmé.** Relire le code courant
   (`CatalogueAttributs`, `VoletAttributsFragment`, `AdaptateurAttributs`)
   plutôt que l'« état du code à connaître » rédigé à v0.134 ; l'annexe § 7
   elle-même (données pures : attribut → contrôle → courant) reste
   entièrement valide.
3. **Ordre des sections.** Respecter la règle `PREVIEW_PARENTS.md` § 5 : les
   sections de famille (Conteneur, Dans le parent, Positions relatives)
   viennent **après** les sections propres à la classe et **avant** les
   sections génériques — les nouvelles sections § 7 s'insèrent dans ce plan.

## 4. Liste de contrôle de reprise

- [ ] Relire l'annexe § 7 en entier et le `sectionsFor` de la maquette v6
      (les deux se répondent attribut par attribut).
- [ ] Livrer PE11g-contrôles (curseur, ratio) puis PE11h (action, attribut
      libre, contraintes en lecture) — layouts de référence dans
      `docs/mockups/ref/ressources/layout/`, cotes dans `PREVIEW_UI.md`.
- [ ] Étendre le catalogue aux sections § 7 (communes puis par famille) ;
      brancher la bascule Courants/Tous et le bouton « Afficher les N
      autres ».
- [ ] Libellés FR dans les ressources + traductions EN à écrire (précédent
      `preview_section_*`, `values-en`).
- [ ] Ne pas régresser les sections par famille de PE12c (leurs tests) ni
      l'écart « Dans le parent » ; exécuter la chaîne locale du brief § 3.
- [ ] Règle 13 (aucun nom d'IDE tiers) et fidélité ADR 0131-0134 inchangées.

## 5. Hors périmètre (inchangé)

- « Tous » dérivé du catalogue plateforme (PE11e du brief) : les moteurs
  vivent dans le dépôt frère ; à reprendre là-bas si le besoin réapparaît.
- Le correctif d'espacement des cartes de section (écart 10 dp,
  `apercu_gap_sections`) suit son propre chantier, indépendant de § 7.
