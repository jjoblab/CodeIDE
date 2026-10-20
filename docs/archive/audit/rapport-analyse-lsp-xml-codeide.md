# Analyse du LSP XML de CodeIDE — gaps, incohérences et plan de stabilisation

> **Référence** : `docs/LSP_XML.md` (cadrage de mission v0.109.0, étapes XE1–XE5
> livrées v0.117.0 → v0.121.0). Miroir des rapports LSP Java (Task 10) et LSP
> Kotlin (Task 11) — même méthode, mêmes critères, même grille de priorisation.
>
> **Objet analysé** : module `:lsp:xml` (moteur maison in-process, décision X1)
> + couture hôte `:lsp:host` (`XmlLanguageServer`, `SessionLspXml`,
> `AdaptateurVueNomsXml`) + câblage éditeur (`EditorViewModel`,
> `CoordonnateurLsp`).
>
> **Date** : 2026-10-14. **État du dépôt** : main v0.124.7 (bd6f458), arbre
> propre, **CodeIDE strictement en lecture seule** (0 fichier suivi modifié).
>
> **Méthode** : lecture intégrale du cadrage et du code (28 fichiers main /
> 5 284 l., 15 fichiers de test / 2 796 l., hôte XML 750 l.) ; vérification
> d'état fraîche (tests, kover, jar) ; **sonde empirique hors dépôt** pilotant
> le SPI public du jar compilé contre la vraie plateforme android-37.2
> installée (10 cas A→J, §6) ; comparaison avec LemMinX/vscode-xml (Red Hat),
> Android Studio et le projet de référence `lang-xml` (§8).

---

## 1. Synthèse exécutive

**Verdict : socle artisanal de très haute qualité, juridiquement propre, mais
déclaré « fini » au milieu du gué — trois familles de faux positifs PROUVÉS
par sonde dégradent la confiance utilisateur, et le catalogue de balises repose
sur une donnée de plateforme obsolète qui prive la complétion des vues
fondamentales d'Android (LinearLayout compris).**

Le positif d'abord, et il est massif : le moteur XML est la brique LSP la plus
**disciplinée** des trois (Java, Kotlin, XML) au plan architectural. Parseur
tolérant écrit main qui digère le corpus LayoutLib **entier sans un seul
recouvrement** (test de parité branché, 2 tests verts) ; DOM neutre immuable à
identité ; SPI/pack découplable derrière ServiceLoader ; types neutres de
protocole (le moteur ignore LSP4J) ; règles pures + coutures conservatrices X4
rigoureusement respectées partout où elles sont écrites ; **zéro dépendance**
(jar 195 903 octets / 87 classes, contre un uber-jar LemMinX de plusieurs Mo) ;
188 tests module verts, kover ≥ 80 % vérifié, 17 tests hôte XML verts ; aucune
mention tierce dans THIRD_PARTY_NOTICES (projet de référence = enseignements
d'architecture uniquement, **zéro code réutilisé** — contrairement au lot G du
LSP Java, aucun risque GPL).

Le négatif, prouvé empiriquement (§6) :

1. **`xml.referenceNonResolue` produit des erreurs fantômes sur les ressources
   de DÉPENDANCES** (Material, AppCompat, AndroidX) : l'index ne connaît que
   `res/` du projet et `public-final.xml` du SDK — toute application réelle
   avec dépendances verra des squiggles rouges sur des références valides
   (sonde cas B : `@style/Theme.Material3.DayNight` → erreur).
2. **L'index ressources vieillit entre deux syncs Gradle** : `didSave` et
   `didChangeWatchedFiles` sont muets côté serveur, le re-scan incrémental
   (pourtant implémenté, mtime) ne se déclenche QUE lors d'un re-push de
   classpath — une ressource fraîchement créée au disque reste « introuvable »
   jusqu'à la sync suivante (sonde cas D).
3. **Les attributs `tools:` sont sondés comme des références réelles** alors
   qu'aapt les ignore totalement (sonde cas E : `tools:text="@string/absente"`
   → erreur).
4. **`data/widgets.txt` ne porte que 37 vues — une relique de l'ère Android
   1.x/2.x** (Gallery, ZoomButton, AnalogClock y figurent ; LinearLayout,
   FrameLayout, RelativeLayout, ListView n'y figurent PAS). Conséquence en
   cascade mesurée : complétion `<Linear|` = **0 proposition** (cas H),
   `android:orientation` **absent** des attributs proposés d'un LinearLayout
   (repli sur View — cas I), survol de LinearLayout = **null** (cas J),
   « did you mean » limité aux 37 noms.
5. **Les vues du projet ne sont jamais proposées en complétion de balise**
   alors que le cadrage §4.3 le promettait explicitement (« classes View du
   projet — réutilise l'index de classes du LSP Java ! ») : la couture
   `VueNomsXml` n'est câblée que sur la garde « balise inconnue », pas sur la
   complétion (sonde cas C : vue de noms qui connaît `MaVuePerso` → 0
   proposition).

Le module est **figé depuis v0.121.0** (5 commits XE1→XE5, rien depuis — 4
versions mineures de l'app) : la mission a été déclarée terminée avec ces gaps
ouverts. La bonne nouvelle du bilan : contrairement au LSP Kotlin (descripteur
pack périmé 0.113.0 pour du code 0.124.x), le `version.properties` XML est
**honnête** (0.121.0 = dernier commit du module) — le pack reflète exactement
l'état du binaire.

Le plan de stabilisation proposé (§10) suit la même grille que Java et Kotlin :
**S0** éteindre les faux positifs (filtre `tools:`, gravité des références
tant que les AAR ne sont pas indexés, re-scan au didSave/watchedFiles, survol
dumb mode) — ~2 jours ; **S1** un catalogue de balises réel (widgets.txt
complété depuis attrs.xml/android.jar + contributeur vues du projet) et
`documentSymbol` — ~4 jours ; **S2** confort (folding, valeurs littérales,
cache de sauts de ligne, genres étendus) ; **S3** index des dépendances AAR et
renommage de ressources.

---

## 2. Rappel du cadrage et de ses promesses

`docs/LSP_XML.md` (v0.109.0) fixe la mission : un LSP XML **in-process**
« propre, robuste et professionnel » avec l'expérience attendue d'Android
Studio — complétion contextuelle, diagnostics temps réel, résolution
`@string/…`/`?attr/…`, hover sur les ressources. Cinq décisions :

| # | Décision | Statut |
|---|----------|--------|
| X1 | Moteur maison in-process, parseur tolérant écrit main, pas de Lemminx/XSD | ✅ Livré et éprouvé (corpus LayoutLib : zéro recouvrement) |
| X2 | DOM neutre partagé, même forme de contrat de session que Java/Kotlin | ✅ Livré, miroir exact |
| X3 | Complétion par contributeurs ; catalogue dérivé des **données de plateforme installées** (widgets.txt, attrs.xml, public-final.xml) | ⚠️ Architecture livrée, **mais la donnée widgets.txt est une relique de 37 vues** (XP4) |
| X4 | Règles pures + coutures conservatrices (« indéterminé = rien signalé ») | ✅ Respecté dans les règles — **contourné dans le survol** (XP6) et par les faux positifs de résolution (XP1–XP3) |
| X5 | Résolution `@type/name` et `?attr/` dans hover/definition/complétion ; `definition` saute au déclarant | ✅ Livré, **sans les ressources de dépendances** (XP1) |

Les étapes XE1→XE5 sont toutes marquées ✅ dans le cadrage, avec des comptes
de tests (67→108→130→152→188) et un budget §6 (re-parse < 10 ms, complétion
< 50 ms, catalogue < 300 ms) mesuré en test (`CorpusLayoutLibTest` : corpus
intégral sous la seconde).

Deux promesses de cadrage non tenues à ce jour, aucune des deux mentionnée
comme écart dans le document (§9) : la complétion des **valeurs littérales**
(« dimension/couleur → extraits de valeurs », §4.3) et les **classes View du
projet** dans la complétion de balise (§4.3, cité ci-dessus).

---

## 3. Cartographie du module (état réel du code)

```
:lsp:xml (28 fichiers main / 5 284 l. — 15 fichiers de test / 2 796 l.)
  api/         SessionLangageXml (contrat complet, impl. par défaut vides),
               MoteurLangageXml + ServiceMoteurLangageXml.unique (SPI),
               DiagnosticsXml (17 codes xml.*), SymbolesXml (propositions,
               survol, emplacements, éditions/correctifs), ClasspathXml +
               VueNomsXml (couture « nom simple de classe existe ? »)
  parse/       ParseurXml (598 l., pile de noms ouverts, reprise fermante
               incohérente/orpheline, guillemet en frappe SANS marqueur),
               DomXml (XmlElement à identité, attributs préfixe/locale,
               plages), EntitesXml (validation de forme + décodage)
  context/     ContexteCurseur (scanner lexical depuis le dernier « < »,
               respect des quotes, zones NomDeBalise/NomAttribut/Valeur/Muet)
  catalog/     CataloguePlateforme (widgets.txt + attrs.xml + public-final.xml,
               le tout PARSÉ PAR LE PARSEUR MAISON), CataloguesPartages (cache
               par chemin de plateforme, partagé par le moteur)
  resources/   IndexRessources (type → nom → déclarants ; scan res/ des
               racines sources ; incrémental par mtime ; purge des disparus)
  rules/       GenreDocument (LAYOUT/VALEURS/MANIFEST/DRAWABLE/MENU/AUTRE par
               URI), ReglesStructure (orchestrateur), ReglesGeneriques
               (attribut dupliqué, esperluettes), ReglesLayout (xmlns, tailles,
               ids, include, merge), ReglesValues (name), ReglesManifeste
               (package, composants), ReglesReferences (couture index+catalogue,
               dumb mode), ReglesTagInconnu (couture catalogue+vueNoms)
  completion/  CompletionXml (balises/attributs + xmlns + tools:),
               CompletionValeursXml (@, @android:, ?attr, énumérations),
               CompletionEntreesXml (values), CompletionDrawablesXml (390 l.),
               CompletionMenusXml, ClassementXml (scoreur, clé de tri)
  resolution/  ReferencesXml (extraction @type/@+/@android:/@*android:/?
               attr…, estResolue, survol, definitions)
  session/     MoteurXmlMaison (SPI + descripteur de pack version.properties),
               SessionLangageXmlImpl (449 l., orchestration par genre),
               CorrectifsXml (insertion xmlns, did-you-mean Levenshtein bornée)

:lsp:host (partie XML — 750 l. main, 722 l. test)
  XmlLanguageServer (501 l.)  adaptateur LSP4J : Full sync, diagnostics push,
    completion (< = :), hover, definition, codeAction ; didSave sans effet ;
    didChangeWatchedFiles vide ; AUCUNE négociation de capacités
  SessionLspXml (208 l.)     façade : une session moteur par connexion,
    classpath poussé post-sync + modèle persisté, vue de noms fournie par la
    session Java (EditorViewModel L850/L1551)
  AdaptateurVueNomsXml (41 l.) VueIndex (extension java.classNames) → VueNomsXml
```

**Câblage éditeur** : `EditorViewModel` ouvre `SessionLspXml` par projet
(L1399), pousse le classpath brut après chaque sync utile (L1551 :
`sessionLspXmlInterne.value?.appliquerClasspath(brut, session.vueNomsXml())`)
et le modèle persisté à la réouverture (L850) — **aucun push ailleurs** :
ni didSave, ni événement fichier. Le verrou/registre FermablesEspace est le
même que Java/Kotlin (ADR 0114).

Faits de version : `version.properties` = `0.121.0` (id `jo.codeide.lsp.xml`,
version minimale app 0.110.0) — **cohérent** avec le dernier commit du module
(`2509f1f`, XE5) ; jar mesuré ce jour : 195 903 octets, 87 classes, zéro
dépendance (le cadrage XE1 annonçait 80 Ko ; la croissance XE2–XE5 reste
maigre : 0,19 Mo).

---

## 4. État de santé vérifié (mesures fraîches, lecture seule)

| Mesure | Résultat |
|---|---|
| `:lsp:xml:test` | **188 tests, 0 échec, 0 ignoré** — dont corpus LayoutLib : « tout le corpus — zéro recouvrement de parseur » et « budget de re-parse — corpus intégral sous la seconde », tous deux **exécutés** (frère présent localement) |
| `:lsp:xml:koverVerify` | **RÉUSSI** (règle couverture-minimale-lsp-xml ≥ 80 % ligne) — à la différence du LSP Kotlin dont la chaîne kover est bloquée par sa baseline rouge |
| `:lsp:host` tests XML | `XmlLanguageServerTest` 9 + `SessionLspXmlTest` 8 = **17 tests verts** |
| `:lsp:xml:rapportTaille` | jar 195 903 octets (0,19 Mo), 87 classes, zéro dépendance |
| Arbre git | propre, 0 fichier suivi modifié — CodeIDE intact |
| Juridique | THIRD_PARTY_NOTICES : **aucune entrée pour le LSP XML** (projet de référence = enseignements uniquement) — pas d'équivalent du lot G Java, pas de vendoring tiers |
| Historique module | 5 commits (XE1→XE5, v0.117.0→v0.121.0), **rien depuis** alors que l'app est à v0.124.7 |

L'incident de process qui discréditait le LSP Kotlin (baseline de parité
rouge sur main, 5 commits passés sans voir le gate) **n'a pas d'équivalent
XML** : tout est vert, la suite qualité fonctionne. Le XML est la brique la
plus saine des trois au plan « usine ».

---

## 5. La sonde empirique — protocole

Même dispositif que la sonde Kotlin (Task 11) : un programme Java **hors
dépôt** (`/home/z/my-project/scripts/sonde-lsp-xml/Sonde.java`), compilé
contre le jar du module + kotlin-stdlib 2.2.20 épinglée, qui pilote le SPI
public (`ServiceMoteurLangageXml` → `MoteurXmlMaison` 0.121.0) avec la
**vraie plateforme android-37.2 installée** et un projet factice (res/values +
res/layout). Dix cas :

| Cas | Éprouve | Attendu si sain | Observé |
|---|---|---|---|
| A | témoin sain `@string/existe` | aucun diagnostic | ✅ aucun diagnostic |
| B | `style="@style/Theme.Material3.DayNight"` (ressource de **bibliothèque**) | aucun diagnostic (le build Gradle réel réussit) | ❌ `xml.referenceNonResolue` **ERREUR** |
| C | complétion `<Ma|`, vue de noms qui **connaît** `com.exemple.MaVuePerso` | `MaVuePerso` proposée (cadrage §4.3) | ❌ **0 proposition** (la couture n'est pas branchée sur la complétion) |
| D | `@string/fraiche` créée au disque **après** le push de classpath, sans re-push | résolue après sauvegarde | ❌ signalée « introuvable » ; ne se résout qu'au re-push (= sync Gradle) |
| E | `tools:text="@string/inexistante"` | silence (aapt ignore `tools:`) | ❌ `xml.referenceNonResolue` **ERREUR** |
| F | `GenreDocument.depuisUri("jar:file:/x.aar!/AndroidManifest.xml")` | MANIFEST | ❌ **AUTRE** (le `substringAfterLast("!/")` retire le slash de tête, `endsWith("/AndroidManifest.xml")` échoue) |
| G | DOM de `<a><b></a>` | `b` fermé (implicitement) | ❌ `estFermee=false`, `plageFermante=null` |
| H | complétion `<Linear|` | `LinearLayout` en tête | ❌ **0 proposition** (widgets.txt = 37 vues) |
| I | attributs sur `<LinearLayout …|` | `android:orientation` proposé | ❌ 143 propositions, **orientation absent** (repli View ; le styleable `LinearLayout` existe pourtant dans attrs.xml) |
| J | survol du nom de balise | classe pour TextView **et** LinearLayout | TextView ✅ `class android.widget.TextView` ; LinearLayout ❌ **null** |

Sortie brute de la sonde (extraits, reproduite en annexe §12) :

```
=== A/B/E : diagnostics du layout (catalogue android-37.2 chargé) ===
  [xml.referenceNonResolue] référence « style/Theme.Material3.DayNight » introuvable (projet et framework)
  [xml.referenceNonResolue] référence « string/inexistante » introuvable (projet et framework)
=== H : completion « <Linear| » ===
  propositions=0  -> LinearLayout ABSENT (widgets.txt = 37 vues legacy)
=== I : attributs proposés sur <LinearLayout …| ===
  propositions=143  android:orientation proposé=false
=== D : après ajout au disque SANS re-push de classpath ===
  [xml.referenceNonResolue] référence « string/fraiche » introuvable (projet et framework)
=== D : après re-push (équivalent sync Gradle) ===
  (aucun diagnostic)
```

La mesure clé derrière les cas H/I/J : `data/widgets.txt` de la plateforme
android-37.2 contient **37 lignes `W`** — AnalogClock, DigitalClock, Gallery,
ZoomButton, ZoomControls, TwoLineListItem… la liste des vues « legacy » que
Google n'a jamais mise à jour dans ce fichier. Le cadrage X3 supposait une
donnée riche (« vues + chaîne de super-classes ») ; la réalité : 37 entrées,
dont l'absence de **LinearLayout, FrameLayout, RelativeLayout, GridLayout,
TableLayout, ListView, GridView, ViewFlipper, TextSwitcher, Toolbar…** (les
`LayoutParams` `P` référencent LinearLayout.LayoutParams — preuve interne au
fichier même que LinearLayout existe mais n'est pas listé en `W`). Pendant ce
temps, `attrs.xml` porte **598 `declare-styleable`** (dont `LinearLayout`) :
la donnée riche existe dans la plateforme, le catalogue ne la consomme pas.

---

## 6. Constats priorisés (XP1 → XP17)

Grille commune aux trois rapports : sévérité d'impact utilisateur (faux
positif > valeur perdue > protocole > robustesse > gouvernance), chaque
constat ancré dans le code, les preuves par sonde citées.

### Rang 1 — Fiabilité (faux positifs prouvés)

**XP1 — `xml.referenceNonResolue` signale les ressources de dépendances
(critique, sonde cas B).** `IndexRessources` ne scanne que les `res/` des
racines sources du projet ; le volet framework consulte `public-final.xml`
(3 515 ressources publiques — vérifié : `Theme.Material` y figure,
`Theme.Material3` non, c'est une bibliothèque). Or un projet Android réel
référence massivement des ressources de **dépendances Gradle** (Material
Components, AppCompat, AndroidX, et tout AAR maison) : `style="@style/
Theme.Material3.DayNight"`, `@string/material_*`, `@drawable/ic_*` de libs…
chacune produira une **ERREUR** rouge persistante pour une référence valide.
`ClasspathXml` ne porte d'ailleurs aucune notion de ressource de dépendance —
le modèle v8 (ADR 0113) a les AAR côté classes mais rien n'est exposé pour
les `res/` des AAR. Impact : la règle la plus « utile en apparence » est la
plus destructive en confiance ; un utilisateur qui voit 5 erreurs fantômes
dans son premier layout ferait confiance à ZÉRO diagnostic.

**XP2 — les attributs `tools:` sont sondés comme des références réelles
(critique, sonde cas E).** `ReglesReferences.sonde` itère `element.attributs`
**sans filtrer le préfixe** : `tools:text="@string/…"` (ignoré par aapt et
par le runtime — les outils ne résolvent JAMAIS ces références) est marqué
`xml.referenceNonResolue`. Idem pour tout espace de noms inconnu. La garde
correcte : ignorer `tools:` (et `xmlns:`), ne sonder que `android:`, `app:`/
sans préfixe. Fix trivial, gain immédiat de crédibilité.

**XP3 — l'index ressources périme entre deux syncs (critique, sonde cas D).**
Le re-scan incrémental par mtime est **implémenté et correct**
(`IndexRessources.definir` : fichiers inchangés épargnés, disparus purgés) —
mais il ne se déclenche que via `SessionLangageXmlImpl.definirClasspath`,
poussé par la façade **uniquement** post-sync Gradle et à la réouverture du
projet. En session d'édition normale : l'utilisateur ajoute `<string
name="titre2">` dans `strings.xml`, sauvegarde, revient dans son layout,
tape `@string/titre2` → **« introuvable (projet et framework) »** en erreur,
jusqu'à la prochaine sync. `didSave` est explicitement « sans effet »
(XmlLanguageServer L287-289) et `didChangeWatchedFiles` vide (L486-489).
La parade est déjà dans le moteur : re-pousser le classpath courant au
didSave (idempotent, delta mtime seulement) coûte un listage de dossiers.

### Rang 2 — Valeur perdue (complétion dégradée)

**XP4 — le catalogue de balises est une relique de 37 vues (majeur, sonde
cas H/I/J).** Toute la chaîne « connaissance Android » balise-side dépend de
`widgets.txt`, que Google n'a jamais étendu depuis les débuts d'Android (la
liste contient Gallery/ZoomButton/AnalogClock — morts depuis l'API 16 — et
PAS LinearLayout). En cascade : complétion de balise quasi vide hors des 37
(`<Linear|` → 0), attributs repliés sur `View` pour toute classe hors
catalogue (`chaineDe` → null → `listOf("View")` — `android:orientation`,
`android:baselineAligned`, `android:layout_gravity` mode.LinearLayout jamais
proposés alors que le styleable existe dans attrs.xml), survol limité aux 37,
« did you mean » borné aux 37. **La complétion XML est aujourd'hui en-dessous
du plancher du projet de référence** (qui complète sur toutes les vues du classpath) et
sans commune mesure avec Android Studio. Parades plausibles, par coût
croissant : (a) dériver les familles de balises des 598 `declare-styleable`
d'attrs.xml (données déjà parsées !) ; (b) enrichir par la vue de noms
(android.jar est dans le classpath indexé — la couture existe) ; (c) reconstruire
la hiérarchie de vues depuis les super-classes du classpath.

**XP5 — les vues du projet ne sont jamais proposées (majeur, sonde cas C).**
Le cadrage §4.3 promettait la complétion des « classes View du projet
(classpath v8 : types héritant de View — réutilise l'index de classes du LSP
Java !) ». `CompletionXml.balises` ne consulte que `catalogue.balises` (SDK) :
la couture `VueNomsXml` est injectée dans `ClasspathXml` mais **aucun
contributeur de complétion ne la lit** (seule `ReglesTagInconnu` l'interroge).
Un projet avec des custom views — le cas nominal de CodeIDE — ne les verra
jamais en complétion, alors même que la garde « balise inconnue » les
connaît. Incohérence de conception interne : la même donnée prouve
l'existence pour un diagnostic et se tait pour la complétion.

### Rang 3 — Garde conservatrice contournée

**XP6 — le survol MENT en dumb mode (majeur, analyse de code).**
`ReferencesXml.survol` (L186-187) : si la référence n'est pas résolue dans
l'index, la branche `reference.framework || !index.estChaud` rend
`« … — framework android »` — donc **avant tout classpath** (index froid),
un survol sur `@string/mon_texte_projet` affiche « framework android » alors
que la ressource est du projet. C'est l'exact opposé de la philosophie X4
(« ce qu'on ne prouve pas, on ne l'affirme pas ») : le dumb mode devrait
taire le survol (null), pas inventer une origine. Fix trivial mais
symboliquement important : la même garde que `estResolue` (index froid →
silence).

### Rang 4 — Protocole et fonctionnalités LSP

**XP7 — didSave sans effet (miroir Java I-x / Kotlin KP-x).** Ni
republication des diagnostics, ni re-scan de l'index (cf. XP3). Le XML est le
cas d'usage le plus nuisible : la vérité disque diverge du texte édité.

**XP8 — didChangeWatchedFiles muet.** Pour le XML, c'est LE mécanisme naturel
de rafraîchissement (création/suppression de fichiers de ressources hors
documents ouverts — nouveaux drawables, valeurs) ; combiné à XP3, l'index ne
suit jamais le système de fichiers.

**XP9 — aucune négociation de capacités.** `initialize` ne lit jamais
`params.capabilities` (client sans hover ? sans codeAction ? déclarations
unilatérales). Miroir du constat Java (ServerCapabilitiesProvider) — moins
brûlant (le client est cel-lsp maison), mais le jour d'un client externe le
serveur déclarera des capacités non consommables.

**XP10 — `documentSymbol` absent (vs LemMinX).** Le DOM neutre détient déjà
l'arbre parfait des balises ; l'éditeur CodeIDE a une barre de symboles pour
Java/Kotlin. Fort rapport valeur/effort (même patron que le KP « documentSymbol
= plancher » du Kotlin) : traduire racine → enfants en DocumentSymbol
hiérarchiques (kind Field/Class selon balise spéciale ou vue).

**XP11 — `foldingRange` absent (vs LemMinX).** Le XML est le langage le plus
naturellement pliable (plages ouvrante/fermante déjà portées par le DOM).
LemMinX fournit en outre documentLink, documentHighlight, rename,
formatting/rangeFormatting — tous dérivables du DOM à des degrés divers, à
calibrer en S2/S3 (le formatage XML maison est un projet en soi).

### Rang 5 — Robustesse et périmètre

**XP12 — genres de document non couverts.** `GenreDocument` ne connaît que
layout/values/manifest/drawable/menu : `res/color` (sélecteurs de couleur
— XML très proche des drawables XE5), `res/anim`/`res/animator`, `res/xml`
(AppWidgetProviderInfo, adaptive icons via `mipmap-anydpi-v26`), `res/
navigation`, `res/transition`, `res/font` passent en AUTRE — règles
génériques seules, aucune complétion. Conservatoire, mais périmètre notable
de la « majorité silencieuse » que le cadrage §1 voulait couvrir.

**XP13 — manifest d'AAR non reconnu (sonde cas F).** `depuisUri` retire la
portion après `!/` puis exige `endsWith("/AndroidManifest.xml")` — une entrée
à la racine du conteneur (`!/AndroidManifest.xml`) perd son slash de tête et
retombe AUTRE. Les racines sources du projet mènent toujours à un chemin
complet en pratique (peu d'impact production), mais la couture « conteneurs
composites » que le commentaire revendique ne fonctionne pas.

**XP14 — incohérence DOM sur les fermées implicites (sonde cas G).** Dans
`consommerFermante`, les éléments intermédiaires refermés implicitement ne
reçoivent ni `plageFermante` ni marque d'implicite : `estFermee` reste false.
`ContexteCurseur.conteneurDe` traite alors ces éléments comme « ouverts »
après leur fermeture réelle → parent de complétion potentiellement trop
profond. Aucun crash (la reprise est justement tolérante), mais un état DOM
qui ment sur lui-même finit par mordre ; poser la plage de la fermante
« qui referme » (ou un flag `fermeeImplicitement`) assainit le contrat.

**XP15 — performance de traduction offsets↔positions.** `positionDe` /
`decalageDe` (XmlLanguageServer) sont O(longueur) par appel et la publication
traduit chaque diagnostic (2 appels par diagnostic, à chaque frappe) : sans
cache de sauts de ligne (le LSP Java en a un), la publication d'un document
1 500 lignes × 20 diagnostics = ~60 000 scans de caractères par frappe.
Supportable au budget visé, mais le correctif est standard et le corpus
montre des layouts LayoutLib bien plus longs que 1 500 lignes. Idem :
`locationLspDe` relit le fichier CIBLE au disque à CHAQUE définition (FUSE,
sans cache) — une lecture complète par clic.

**XP16 — valeurs littérales et plafonds non livrés.** Le cadrage §4.3
promettait « dimension/couleur → extraits de valeurs » : aucune complétion de
`16dp`/`#FF0000` littérales extraites de l'index. Le plafond de 60
propositions par contributeur (`CompletionValeursXml.PLAFOND_PROPOSITIONS`)
n'est documenté nulle part et interagit avec `isIncomplete=false` (le client
croit la liste exhaustive). Complétion de valeurs `@` : les types proposés ne
sont que ceux PRÉSENTS dans le projet (sans catalogue vide de types usuels —
cohérent X4 mais surprenant sur un projet neuf vide).

### Rang 6 — Gouvernance

**XP17 — module figé depuis v0.121.0.** Quatre versions mineures de l'app
ont passé (v0.122→v0.124.7, gros chantiers éditeur/Phase B) sans une ligne
dans `:lsp:xml` ni dans ses 3 fichiers hôte. Ce n'est pas une dette de code
(les tests/kover sont verts), c'est une **fin de mission déclarée** avec XP1–
XP5 ouverts : le cadrage ne comporte pas de colonne « connus/non livrés » à
la différence des ADR Java qui assument leurs limites. Point positif à
créditer au passage : `version.properties` (0.121.0) est exactement honnête —
le pack reflète le binaire, là où le pack Kotlin prétend 0.113.0 pour du code
0.124.x.

---

## 7. Ce qui fonctionne remarquablement bien

L'équilibre du rapport exige de nommer les réussites, car elles fondent le
plan (on répare un moteur sain, on ne le réécrit pas) :

- **Le parseur tolérant est éprouvé** : corpus LayoutLib intégral (65
  fichiers) re-parsé en test avec **zéro recouvrement**, budget intégral
  sous la seconde — le pari X1 (parseur strict inutilisable pour l'édition)
  est tenu, avec la reprise par pile, le guillemet en frappe sans marqueur à
  chaud, la fermante incohérente qui referme implicitement.
- **La discipline d'architecture est la meilleure des trois briques** :
  types neutres intégraux (le moteur ne connaît pas LSP4J), SPI + descripteur
  de pack, implémentations par défaut vides (dégradation honnête pack
  ancien/hôte neuf), session par connexion, catalogue chargé UNE fois par
  plateforme et partagé, parseur sans état partagé en sécurité.
- **Les quick-fixes XE4 dépassent le plancher LemMinX côté Android** :
  insertion `xmlns:android` au bon endroit, « did you mean » préfixe commun
  puis Levenshtein bornée avec renommage ouvrante+fermante — LemMinX n'a pas
  d'équivalent pour la sémantique Android.
- **Le re-scan incrémental mtime et la purge des disparus sont corrects**
  (l'index « ne ment jamais ») — le problème est uniquement le déclencheur
  (XP3), pas la mécanique.
- **Les diagnostics de structure sont fidèles et fins** : 17 codes `xml.*`
  documentés, gravités réfléchies (tailles = AVERTISSEMENT conformément au
  cadrage « hint, pas error »), dumb mode respecté dans ReglesReferences,
  garde X4 triple-unanime (catalogue + scan + vue de noms) pour tagInconnu.
- **Juridiquement propre** : aucune entrée tierce, le projet de référence n'a servi que
  de modèle d'architecture (conforme à la section §2 du cadrage qui annonçait
  « aucune réutilisation de code »).

---

## 8. Comparaison avec les LSP XML de référence

### 8.1 LemMinX (eclipse-lemminx, EPL-2.0) — le plancher générique

README officiel vérifié (fetch du dépôt main) — capacités annoncées :
`codeAction`, `completion`, `documentHighlight`, `documentLink`,
`documentSymbol`, `foldingRanges`, `formatting`, `hover`, `rangeFormatting`,
`rename`, plus la validation XSD/DTD via Xerces et un système d'extensions
SPI (le patron « contributeurs » de CodeIDE est un écho direct de ce modèle,
via le projet de référence).

| Capacité | LemMinX | CodeIDE XML |
|---|---|---|
| completion | ✅ XSD/DTD générique | ✅ sémantique Android (balises/attributs/valeurs) |
| hover | ✅ (documentation de schéma) | ✅ (références, classe, format) — ment en dumb mode (XP6) |
| definition | ✅ (via schéma/extension) | ✅ (déclarant res/values, projet seul) |
| codeAction | ✅ | ✅ (2 correctifs Android ciblés) |
| diagnostics | ✅ validation XSD/DTD | ✅ règles Android maison |
| documentSymbol | ✅ | ❌ |
| foldingRanges | ✅ | ❌ |
| rename | ✅ | ❌ |
| formatting / rangeFormatting | ✅ | ❌ |
| documentLink / documentHighlight | ✅ | ❌ |
| validation sémantique tierce | générique (schéma) | ❌ / faux positifs (XP1–XP3) |

La décision X1 d'écarter LemMinx reste **défendable** pour CodeIDE : un
uber-jar Xerces+Eclipse sur ART, une sémantique Android non-schématisable,
et le besoin de brancher le modèle de ressources AGP v2 — le cadrage §2
l'argumentait déjà via l'audit du projet de référence. Mais le miroir des constats
Kotlin vaut ici : LemMinX marque le **plancher fonctionnel** d'un LSP XML
sérieux, et documentSymbol + foldingRanges y sont les absents les plus
inexplicables côté CodeIDE puisque le DOM neutre détient déjà exactement
l'information nécessaire.

### 8.2 Android Studio — la référence UX du cadrage

Le cadrage §1 invoque explicitement « l'expérience attendue d'Android
Studio ». Au rang des écarts mesurés : AS complète sur **toutes** les vues du
SDK **et** des dépendances (CodeIDE : 37 vues SDK + 0 vue de projet — XP4/
XP5) ; AS résout les références `@` sur projet **+ dépendances + framework**
(CodeIDE : projet + framework seul — XP1) ; AS rafraîchit l'index de
ressources en continu (VFS — CodeIDE : sync Gradle — XP3) ; AS offre le
renommage de ressources, l'extraction de chaînes, la preview intégrée
(CodeIDE : preview LayoutLib existe par ailleurs — traçabilité §8 du
cadrage, l'index LSP est promis partagé avec elle).

### 8.3 Projet de référence `lang-xml` — le modèle d'origine

Le cadrage §2 en tire quatre enseignements (parseur tolérant, DOM neutre,
règles/coutures séparées, complétion par contributeurs) — tous livrés. En
revanche, le point où le projet de référence reste devant : sa complétion de balise
s'appuie sur **les classes du classpath** (toutes les vues disponibles),
quand CodeIDE en propose 37 (XP4) et aucune du projet (XP5). L'écart
sémantique « connaissance Android = données, pas code » (X3) est juste ;
la donnée choisie (widgets.txt) est la mauvaise.

---

## 9. Écarts cadrage ↔ livré

| Promesse (LSP_XML.md) | Livré | Écart |
|---|---|---|
| §4.3 « classes View du **projet** (classpath v8) dans la complétion de balise » | ❌ | XP5 (couture existante non branchée) |
| §4.3 « dimension/couleur → extraits de valeurs » | ❌ | XP16 |
| §4.3 complétion balise = « tous les View/ViewGroup du SDK » | partiel | XP4 (37/≈200+ ; la donnée de plateforme choisie ne les porte pas) |
| §4.4 « référence non résolue (projet + framework) » | livré tel quel | la formulation masque le tiers « dépendances » — XP1 |
| §6 « complétion à chaud < 50 ms » | non mesuré en continu | bench banc dédié absent (mesure ponctuelle corpus OK) |
| §7 « Qualité des données public.txt incomplet → repli attrs.xml » | ✅ | — |
| §8 « l'index LSP partagé avec le panneau preview » | index XML séparé de LayoutLib | traçabilité suspendue (PREVIEW.md consomme LayoutLib, pas :lsp:xml) — décision à documenter |

Aucun de ces écarts n'est répertorié comme limite connue dans le cadrage :
XE1–XE5 sont tous ✅ sans colonne « restes ». C'est la différence de
gouvernance la plus notable avec les ADR Java/Kotlin qui assument leurs
non-livraisons.

---

## 10. Plan de stabilisation proposé

Même grille que Java (S0→S3) et Kotlin : chaque étape est bornée, testable,
et protégée par des garde-fous (§11). Les efforts sont exprimés en
jours-homme sur la base des chantiers précédents du dépôt.

### S0 — Éteindre les faux positifs et les mensonges (~2 j)

1. **Filtre d'espace de noms dans `ReglesReferences`** : ne pas sonder
   `tools:*` (ni `xmlns:*`) — XP2. Test TDD : layout avec `tools:text="@string/x"`
   absent → AUCUN diagnostic.
2. **Gravité de `xml.referenceNonResolue` rétrogradée en AVERTISSEMENT**
   tant que les dépendances ne sont pas indexées (XP1) — un faux avertissement
   coûte moins de confiance qu'un faux ROUNGE ; le retour à ERREUR vient avec
   S3. Alternative acceptée : ne plus signaler que les types dont le projet
   prouve l'usage (string/drawable/layout déjà définis dans le projet) — mais
   la rétrogradation est la plus honnête.
3. **Re-scan au didSave** : le serveur re-pousse le classpath courant à la
   sauvegarde (`session.definirClasspath` idempotent, delta mtime) — XP3/XP7.
   Test : scénario sonde cas D en test module + test serveur.
4. **Survol dumb mode** : index froid → `null` au lieu de « framework
   android » — XP6 (ReferencesXml.survol L186-187).
5. **Fixes de robustesse légère** : GenreDocument AAR (accepter l'entrée
   racine de conteneur) — XP13 ; `plageFermante` posée sur les refermés
   implicites — XP14.

Sortie S0 : la sonde (cas B mis à gravité, D, E, F, G) devient **test de
non-régression verte** ; plus AUCUN faux positif connu.

### S1 — Un catalogue de balises réel + le plancher LSP (~4 j)

1. **Enrichissement du catalogue** (XP4) : les familles de balises dérivées
   des 598 `declare-styleable` d'attrs.xml (données déjà parsées par le
   catalogue — le styleable `LinearLayout` y est, avec `orientation`) ;
   complétées par la vue de noms du classpath pour la chaîne de
   super-classes réelle (android.jar est indexé). Test contractuel :
   `LinearLayout`, `FrameLayout`, `RelativeLayout`, `ListView` **doivent**
   être proposées à `<Linear|`/`<F|`/`<R|`/`<L|` et `android:orientation`
   doit apparaître sur un LinearLayout — le test casse si une future
   plateforme retire la donnée.
2. **Contributeur « vues du projet »** (XP5) : `CompletionXml.balises`
   consulte `VueNomsXml` (noms simples filtrés par chaîne atteignant
   android.view.View — conservateur : sans preuve de vue, silence).
3. **documentSymbol** (XP10) : racine→enfants en symboles hiérarchiques,
   kind Class pour vues, Field pour entrées values ; déclaré dans les
   capacités.
4. **didChangeWatchedFiles** (XP8) : re-push du classpath sur événement
   fichiers res/ (le client cel-lsp doit exposer les watchers — voir même
   constat côté Java/Kotlin ; à défaut, le didSave de S0 couvre le principal).

### S2 — Confort et périmètre (~5 j, par ordre de valeur)

foldingRanges (plages ouvrante/fermante déjà portées par le DOM) ; cache de
sauts de ligne partagé dans XmlLanguageServer (publication O(n)) + cache LRU
des lectures de cibles de définition — XP15 ; complétion des valeurs
littérales dimension/couleur depuis l'index (XP16) ; genres étendus
`res/color` (réutiliser le contributeur drawables) puis `res/xml`,
`res/navigation`, `res/anim*` — XP12 ; negotiation de capacités minimale
(XP9).

### S3 — La fidélité Android Studio (~2-3 semaines, chantier)

**Index des ressources de dépendances** (racine du problème XP1) : le
modèle v8 (ADR 0113) connaît les AAR du classpath côté classes ; exposer
leurs `res/` (archives AAR : listage + parse à la volée, budget mtime) dans
`ClasspathXml` (nouveau champ `racinesAar`) et fusionner dans
IndexRessources (types/noms préfixés du projet vs lib pour le détail). Au
terme : retour de `xml.referenceNonResolue` en ERREUR légitime, complétion
des styles/couleurs Material, fidélité de la résolution au build réel.
Suites possibles : renommage borné d'entrées values, formatage XML maison.

### Décisions à documenter (ADR courtes)

- D-X1 : gravité de `xml.referenceNonResolue` (ERREUR vs AVERTISSEMENT) avant/
  après l'index AAR — assumée dans le diagnostic et le CHANGELOG.
- D-X2 : source de vérité du catalogue balises (widgets.txt complété vs
  attrs.xml + classpath) — consigner le rejet du « widgets.txt seul » avec
  la mesure des 37 vues.
- D-X3 : partage (ou non) de l'index ressources avec le pipeline preview
  LayoutLib (traçabilité §8 du cadrage, suspendue).

---

## 11. Garde-fous et suivi

1. **Sonde → test de non-régression** : les 10 cas A→J (scripts/
   sonde-lsp-xml/) deviennent un banc de caractérisation exécuté contre le
   jar compilé (patron du test de sonde Kotlin proposé en KP-garde) — tout
   futur changement de comportement des complétions/diagnostics XML doit se
   lire dans ce banc d'abord.
2. **Test contractuel catalogue** : « les balises fondamentales sont
   proposées » (LinearLayout & co) — casse si une donnée de plateforme
   régresse (le risque §7 du cadrage était exactement celui-là, il s'est
   réalisé côté widgets.txt).
3. **CI** : `:lsp:xml:test` + `koverVerify` déjà verts à maintenir ; ajouter
   le corpus LayoutLib avec frère monté en CI (il tourne localement —
   l'auto-skip honnête ne doit pas devenir le régime nominal).
4. **Soak multi-connexions** : le partage catalogue/index par le moteur est
   peu couvert côté concurrence (tests actuels séquentiels) — un banc de
   2 sessions parallèles sur le même moteur (didChange croisés) avant S3.
5. **Réflexe descripteur** : tout commit touchant `:lsp:xml` bump
   `version.properties` (le réflexe absent côté Kotlin, présent de fait ici
   car le module est figé — l'écrire dans CONVENTIONS.md pour le dégel).

---

## 12. Annexes

### 12.1 Reproduire la sonde

```bash
source /home/z/my-project/scripts/env-layoutlib.sh
cd /home/z/my-project/scripts/sonde-lsp-xml
STDLIB=/home/z/.gradle/caches/modules-2/files-2.1/org.jetbrains.kotlin/\
kotlin-stdlib/2.2.20/5380b19fa1924399b62ce3a1faffebb2b4f82272/\
kotlin-stdlib-2.2.20.jar
JAR=/home/z/my-project/repos/CodeIDE/lsp/xml/build/libs/xml.jar
javac -cp "$JAR:$STDLIB" Sonde.java && java -cp ".:$JAR:$STDLIB" Sonde
```

Sortie intégrale (2026-10-14, jar 0.121.0, plateforme android-37.2) :

```
moteur jo.codeide.lsp.xml version 0.121.0

=== A/B/E : diagnostics du layout (catalogue android-37.2 chargé) ===
  [xml.referenceNonResolue] référence « style/Theme.Material3.DayNight » introuvable (projet et framework)
  [xml.referenceNonResolue] référence « string/inexistante » introuvable (projet et framework)

=== C : completion de balise « <Ma| » (vueNoms connaît MaVuePerso) ===
  propositions=0 dont contenant 'Ma'=0  -> MaVuePerso JAMAIS proposée (écart cadrage 4.3)

=== H : completion « <Linear| » (balise la plus courante d'Android) ===
  propositions=0  -> LinearLayout ABSENT (widgets.txt = 37 vues legacy)

=== I : attributs proposés sur <LinearLayout …| ===
  propositions=143  android:orientation proposé=false

=== J : survol du nom de balise ===
  TextView -> ContenuSurvoleXml(valeur=class android.widget.TextView)
  LinearLayout -> null  -> aucun survol (hors des 37 vues)

=== D : référence @string/fraiche avant ajout au disque ===
  [xml.referenceNonResolue] référence « string/fraiche » introuvable (projet et framework)
=== D : après ajout au disque SANS re-push de classpath ===
  [xml.referenceNonResolue] référence « string/fraiche » introuvable (projet et framework)
=== D : après re-push (équivalent sync Gradle) ===
  (aucun diagnostic)

=== F : GenreDocument d'un manifest dans un AAR ===
  genre=AUTRE  -> manifest AAR non reconnu (endsWith exige un slash)

=== G : DOM « <a><b></a> » (fermeture implicite de b) ===
  b: estFermee=false plageFermante=null  -> incohérence : b refermé implicitement mais déclaré ouvert
```

### 12.2 Mesure de la relique widgets.txt

`rg -c "^W" platforms/android-37.2/data/widgets.txt` → **37**. La liste
complète (AnalogClock, CalendarView, CheckBox…ZoomControls) est celle des
vues publiques de l'ère Android 1-2 ; `LinearLayout`, `FrameLayout`,
`RelativeLayout`, `ListView`, `GridView`, `Toolbar`, `ViewFlipper` en sont
absents (leurs `LayoutParams` figurent en lignes `P` — le fichier se
contredit lui-même). `attrs.xml` porte 598 `declare-styleable` et
`public-final.xml` 3 515 ressources publiques (743 styles framework).

### 12.3 Métriques du module

| Métrique | Valeur |
|---|---|
| Main | 28 fichiers, 5 284 l. (dont ParseurXml 598, SessionLangageXmlImpl 449, CompletionDrawablesXml 390) |
| Tests | 15 fichiers, 2 796 l., 188 tests verts (0 échec, 0 ignoré) |
| Hôte XML | XmlLanguageServer 501 l., SessionLspXml 208 l., AdaptateurVueNomsXml 41 l. ; tests 722 l., 17 verts |
| Jar | 195 903 octets, 87 classes, zéro dépendance |
| Codes diagnostics | 17 codes `xml.*` documentés |
| Kover | ≥ 80 % ligne, règle couverture-minimale-lsp-xml, vérifiée RÉUSSIE |

### 12.4 Fichiers de référence de l'analyse

- Cadrage : `docs/LSP_XML.md` (155 l.).
- Code : `lsp/xml/src/main/kotlin/jo/lspxml/**` (intégralement lu),
  `lsp/host/src/main/kotlin/jo/codeide/lsp/host/{XmlLanguageServer,
  SessionLspXml, AdaptateurVueNomsXml}.kt`, `feature/editor/src/main/
  kotlin/jo/codeide/feature/editor/EditorViewModel.kt` (câblage L817-850,
  L1399-1551).
- Juridique : `docs/THIRD_PARTY_NOTICES.md` (aucune entrée XML).
- Historique : `git log lsp/xml` → 5 commits (55c5af1 XE1 → 2509f1f XE5).
- Sonde : `/home/z/my-project/scripts/sonde-lsp-xml/Sonde.java` + résultats
  JSON des recherches web (LemMinX README officiel fetché et vérifié).
