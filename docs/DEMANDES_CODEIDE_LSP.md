# Demandes à l'agent du dépôt `codeide-lsp` — chantier indexation (CodeIDE v0.178.0)

> **Note de transmission** (règle du propriétaire, 2026-10-24) : le
> dépôt `codeide-lsp` est travaillé PAR UN AUTRE AGENT en ce moment.
> Ce fichier est la voie OFFICIELLE pour lui transmettre les demandes
> nées du chantier indexation de CodeIDE — chaque item cite les
> preuves (fichiers, lignes, journaux). Le statut de chaque demande
> est à tenir à jour ici (⏳ demandé / 🔄 en cours / ✅ livré — avec
> la version codeide-lsp et le lien ADR).

## D1 — ANR `.kt`/`.kts` : le supplier de connexion paie le nourrissage moteur sur le fil appelant ⏳

**Preuve** : `InProcessStreamConnectionProvider.start()` (cel-lsp,
ligne 85 : `server = serverFactory.get()`) tourne sur le thread qui
appelle `LspEditor.connect()` — dans CodeIDE c'est le **thread
principal** (`CoordonnateurLsp.brancher` ← `lifecycleScope.launch`).
Le supplier `SessionLspKotlin.serveur()` fait alors, SYNCHRONÉMENT :

1. `moteur.creerSession()` ;
2. `classpathCourant?.let { sessionLangage.definirClasspath(it) }` →
   `SessionLangageKotlinParse.definirClasspath` → **`IndexAppelables-
   Kotlin.definir`** : lecture de CHAQUE segment `kotlin-appelables/
   *.kidx` du classpath (47 entrées sur le projet témoin — journaux
   du 23/10) ou **décodage à froid kotlin-metadata du jar entier**
   au premier passage (≥ 10 s mesuré sur appareil d'entrée de gamme,
   cf. correctif v0.116.0 « ANR e7dd5a7 ») + reconstruction des maps
   d'appelables ;
3. `scanProjet.definirRacines(...)` + `synthetiques.definirRacines(...)`.

**Conséquence** : première ouverture `.kt` post-sync = ANR garanti ;
et chaque changement d'onglet `.kt` re-paye la définition COMPLÈTE
(chaque connexion crée une session moteur NEUVE). Même motif côté
XML (`SessionLspXml.serveur()` → `definirClasspath` : index ressources
XE3 + catalogue AAR P3).

**Demandes** (par ordre de préférence) :
- **D1-a** : le snapshot `IndexAppelablesKotlin` doit être partagé au
  niveau MOTEUR/PROJET (reconstruit UNE fois par sync, jamais par
  connexion) ; `creerSession()` ne fait que brancher la session sur
  le snapshot immuable.
- **D1-b** : à défaut, le nourrissage à la création de session devient
  PARESSEUX/ASYNCHRONE — les requêtes sous-lisent tant que la
  définition n'est pas prête (miroir de la vue d'index fusion Java :
  « les requêtes sous-lisent tant que la construction n'est pas
  PRÊTE »).
- **D1-c** : en tout état de cause, AUCUNE E/S lourde dans le supplier
  de connexion — le fil appelant peut être le fil principal de l'hôte.
- **D1-d** (annexe)** : `SessionLangageXmlImpl.definirClasspath` suit
  la même loi (scans XE2/XE3 par empreinte/mtime — légers à chaud, mais
  re-payés par connexion).

> Côté CodeIDE (v0.178.0, ADR 0145), `CoordonnateurLsp.brancher`
> déplace déjà `connect()` hors du fil principal — l'ANR UI est
> couvert, mais la LATENCE d'ouverture `.kt` restera tant que D1-a/b
> n'est pas livré.

## D2 — Verrou unique du moteur : le filet de scan synchrone sous verrou ⏳

**Preuve** : `SessionLangageKotlinParse` sérialise TOUT
(`diagnostics`, `completion`, `survol`, `previews`, didOpen/didChange)
sous UN `synchronized(verrou)` ; or `diagnostics()` appelle
`declarationsProjet()` → `ScanProjet.declarations()` →
`scannerAuBesoin()` dont le **filet historique** peut scanner TOUT le
projet SOUS le verrou (si le WARM n'est pas en vol et le snapshot
vide — `ScanProjet.kt` l.236-250). Pendant ce temps, chaque requête
LSP Kotlin attend, y compris didOpen.

**Demandes** :
- **D2-a** : le filet synchrone ne doit JAMAIS scanner sous `verrou` :
  joindre le WARM en vol, sinon rendre le snapshot VIDE et laisser le
  WARM publier (une requête précoce répond honnêtement moins riche,
  jamais en bloquant tout le serveur).
- **D2-b** : étudier un `ReadWriteLock` — les lectures (diagnostics,
  complétion, previews) sont majoritaires et parallélisables ; seules
  `definirClasspath`/didChange écrivent.

## D3 — Contrat de progression d'indexation Kotlin + XML (écran multi-moteurs) ⏳

CodeIDE v0.178.0 livre un écran d'indexation MULTI-MOTEURS (Java +
Kotlin + XML — ADR 0145). Java expose déjà `SessionLsp.etatIndexation`
(riche : phases, faits/total, liste de travail, breakdown, bilan).
**Kotlin et XML n'exposent RIEN** : CodeIDE synthétise aujourd'hui un
état honnête mais grossier depuis ses fenêtres de nourrissage
(construction indéterminée → prêt).

**Demande** : `SessionLspKotlin.etatIndexation: StateFlow<EtatIndexa-
tion>` (le même type public que Java) alimenté par le moteur :
- phases : segments kidx chargés X/N (remplacement segments/décodage
  à froid), fichiers du scan WARM X/N, PRET/PARTIEL/ERREUR ;
- idem `SessionLspXml.etatIndexation` (catalogue XE2 par empreinte,
  index ressources XE3 par mtime).
L'agrégat CodeIDE accepte DÉJÀ un moteur « riche » — brancher le flux
remplacera la synthèse sans autre changement côté hôte.

## D4 — API « réessayer les échecs SEULEMENT » ⏳

L'écran propose « Réessayer les entrées échouées » (maquette) ;
`SessionLsp.reindexer()` fait revalidation + auto-guérison de TOUT.
Les deux boutons mappent donc aujourd'hui sur le même appel
(documenté ADR 0145). **Demande** : un `reesayerEchecs()` ciblé
(segments écartés/corrompus du dernier passage seulement) — ou une
réponse officielle « non distinguable », et l'écran retirera le
second bouton.

## D5 — Point d'annulation coopératif d'un passage ⏳

La maquette de l'écran (docs/mockups/indexation/) a un bouton
« Annuler le passage » pendant la construction ; aucun point
d'annulation n'existe côté orchestrateur/index. **Demande** : un
`annulerPassage()` coopératif (drapeau volatile testé entre les
entrées, passage marqué PARTIEL « annulé par l'utilisateur ») — sinon
l'écran retire l'offre (décision déjà prise en phase B, ADR 0117 Q3).

## Journal des demandes

| # | Demande | Statut | Livré dans |
|---|---------|--------|-----------|
| D1 | Supplier de connexion sans nourrissage lourd (Kotlin + XML) | ⏳ demandé (24/10/2026) | — |
| D2 | Pas de scan synchrone sous verrou ; lectures parallélisables | ⏳ demandé (24/10/2026) | — |
| D3 | `etatIndexation` Kotlin + XML (flux réel de progression) | ⏳ demandé (24/10/2026) | — |
| D4 | `reesayerEchecs()` ciblé | ⏳ demandé (24/10/2026) | — |
| D5 | `annulerPassage()` coopératif | ⏳ demandé (24/10/2026) | — |
