# ADR 0130 — CI en trois jobs parallèles et réglages Gradle de runner

- **Statut** : accepté (directives du 2026-10-19 — « stratégie de
  vérification et de collaboration multi-agents »)
- **Date** : 2026-10-19
- **Décideurs** : jjoblab
- **Amende** : ADR 0037 §2 (réglages mémoire séquentiels conservés tels
  quels sur CI) — l'objection « un réglage CI divergent serait un
  deuxième environnement à maintenir » est levée par les options CLI
  du workflow seul : `gradle.properties` reste INCHANGÉ.
- **Mesures fondatrices** : `docs/STRATEGIE_VERIFICATION.md` §6 —
  médiane des runs success 21,6 min ; le step « Vérification complète
  » = ~90 % du run ; 707 tâches FROM-CACHE (le cache n'est pas le
  goulot) ; runner standard `ubuntu-latest` = 4 vCPU / 16 Go
  (Ubuntu 24.04.5, confirmé par les logs « Runner Image ») alors que
  `gradle.properties` règle le build pour la sandbox locale
  4 Go / 2 cœurs (`org.gradle.parallel=false`, `workers.max=1`,
  tas démon 2536m) : **la CI tournait séquentielle sur quatre cœurs**.

## Contexte

La chaîne CI (`spotlessCheck detekt checkModuleDependencies lintDebug
testDebugUnitTest koverVerify assembleDebug`) s'exécute dans UN job
séquentiel. Sur un runner 4 vCPU, le réglage séquentiel hérité de la
sandbox (mémoire 4 Go, un seul worker, parallélisme coupé pour éviter
l'OOM killer local) laisse trois cœurs inoccupés pendant 20 à 30
minutes à chaque push. Par ailleurs :

- le filtre `cache-read-only` comporte un bug : `github.ref !=
  'refs/tags/'` est toujours VRAI pour un tag réel
  (`refs/tags/v0.145.0` n'est jamais la chaîne littérale
  `refs/tags/`) — les runs de tags tournaient donc avec un cache en
  lecture seule et ne sauvegardaient jamais leurs entrées ;
- chaque push déclenche la chaîne complète même pour des commits de
  documentation pure (constat : les lots docs déclenchent 25 min de
  runner pour zéro code).

## Décision

1. **Le job `build` est scindé en trois jobs parallèles** (mêmes
   tâches, même garantie — aucun raccourci) :
   - **statique** : `spotlessCheck detekt checkModuleDependencies` —
     échoue en minutes, aucun SDK lourd ; c'est le filet anti-push
     sale (leçon spotless v0.141-v0.143) ;
   - **tests** : `testDebugUnitTest koverVerify` ;
   - **lint-et-assemble** : `lintDebug assembleDebug` + vérification
     de signature (ADR 0067) + publication de l'APK.
   Le garde-fou composite nocturne reste inchangé.
2. **Réglages Gradle par options CLI, job par job —
   `gradle.properties` inchangé** (l'environnement local 4 Go garde
   ses garde-fous séquentiels) :
   `-Dorg.gradle.parallel=true -Dorg.gradle.workers.max=3` et un tas
   adapté (`-Dorg.gradle.jvmargs="-Xmx6g -XX:MaxMetaspaceSize=1536m
   -Dfile.encoding=UTF-8"` pour tests et lint/assemble ; 3g pour le
   job statique). La stratégie Kotlin in-process
   (`kotlin.compiler.execution.strategy=in-process`) est conservée :
   avec un tas de 6 Go elle évite l'empilement de démons Kotlin sur
   le runner comme en local.
3. **`paths-ignore` sur `**.md` et `docs/**`** (push et
   pull_request) : la documentation ne consomme plus de runner. Les
   runs de tags restent garantis : un bump de version touche
   toujours `version.properties` (non ignoré), donc tout tag `v*`
   déclenche bien la chaîne.
4. **Correction `cache-read-only`** :
   `github.ref != 'refs/heads/main' && !startsWith(github.ref,
   'refs/tags/')` — les runs de tags sauvegardent désormais leurs
   caches.

## Alternatives rejetées

- **Matrice par module** (refusée déjà en ADR 0037) : N démarrages de
  démon Gradle pour 32 modules ; la découpe en 3 jobs suffirait aux
  mesures actuelles.
- **Runner « larger » (8 vCPU / facturé)** : le gain attendu ne
  justifie pas le coût tant que le simple déblocage du parallélisme
  n'est pas mesuré (section 6.5 de la stratégie).
- **Éditer `gradle.properties`** : dérègle la sandbox locale 4 Go
  (OOM killer documenté depuis l'étape 1) et recrée le « deuxième
  environnement » que l'ADR 0037 refusait — les options CLI du
  workflow scindent proprement les deux contextes.
- **Parallélisme agressif (workers ≥ 4, tas 8g+)** : 3 JVM de test
  Robolectric + démon 8g frôlent les 16 Go ; workers.max=3 et 6g
  laissent une marge mesurée (suivi en section 6.5).

## Conséquences

- Chaque push consomme jusqu'à trois runners en parallèle — durée
  murale attendue ≈ max(jobs) au lieu de la somme (à confirmer par la
  mesure §6.5 de la stratégie) ; le cache build Gradle étant
  adressé par contenu, les trois jobs se partagent les sorties déjà
  compilées (FROM-CACHE inter-jobs).
- Le job **statique** devient le premier signal du push : une CI rouge
  sur du formatage/detekt échoue en quelques minutes au lieu de 25.
- Les commits de docs uniquement ne déclenchent plus de run (fin des
  « 25 minutes pour un .md »).
- La supervision de la CI lit désormais trois jobs — les logs d'échec
  se récupèrent par job (procédure §4 de la stratégie inchangée).
