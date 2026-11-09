# ADR 0161 — Historique et rétention des sorties de build

- Statut : proposé (2026-11-10, lot L0 — branche
  `agent/sorties-l0-conception`). Renuméroté depuis 0155 (créneau
  occupé par le chantier générateur).
- Contexte : audit § 3 (« ni structure ni mémoire »), § 9 (écarts) ;
  `docs/SORTIES_SPEC.md` § 8 ; précédents : `ArchiveSessionsJournal`
  (Logcat), ADR 0025 (relecture disque du journal applicatif), ADR 0009
  (bornes, expurgation à l'écriture).

## Contexte

La sortie du build qui a échoué hier est précisément ce qu'on veut
relire — et elle est perdue : chaque action émet `Vider` sur son canal
(`GradleService.kt:1018-1024`), la fenêtre d'affichage est bornée à
2 000 lignes (`NB_LIGNES_MAX`) et rien ne survit au redémarrage. La
sortie complète reste « dans le canal rejouable du client »
(`docs/TOOLING.md`) mais uniquement pendant la session, en mémoire.

## Décision

1. **Registre des exécutions** (`core:domain`, port pur + implémentation
   dans `app`) : les N dernières exécutions — build ET sync — avec :
   identité (id, type, tâches demandées), horodatage de début/fin,
   verdict, durée, compteurs (tâches exécutées/en cache/à jour/sans
   source/échouées, erreurs, avertissements), la **sortie textuelle
   bornée** et la référence des journaux serveur (`gradle-server`) de la
   session. N par défaut = **10**, réglable (M13).
2. **Plafond de taille par exécution** (défaut 20 000 lignes, réglable) :
   au-delà, les lignes du milieu sont tronquées en premier (tête et
   queue conservées) — la troncature est **annoncée** dans l'interface
   (M12, « sortie tronquée » avec bornes exactes).
3. **Rétention globale bornée** : un plafond d'octets total pour tout
   l'historique ; l'exécution la plus ancienne est sacrifiée d'abord ;
   l'effacement est explicite (M11/M13) et confirmé.
4. **Expurgation À L'ÉCRITURE** : chaque ligne passe par `LogRedactor`
   + relativisation des chemins absolus du projet avant persistance —
   l'affichage live n'est JAMAIS altéré (c'est le contenu de
   l'utilisateur) ; la frontière live ≠ persisté est assumée par cet
   ADR et rappelée sur la feuille d'export (M10).
5. **Consultation** : une exécution se rouvre en lecture seule dans le
   lecteur unifié (même tampon, projection brut par défaut, regroupement
   disponible si les données d'événements ont été persistées avec) ;
   « comparer avec la précédente » (durée, tâches rejouées) est une
   option post-L3.
6. **Format** : JSON Lines compressé (gzip) en stockage privé, une
   écriture par lot ingéré (jamais par ligne) ; les compteurs et le
   verdict en en-tête JSON unique par exécution. Aucune donnée
   personnelle : noms de projet et chemins relativisés/expurgés — même
   discipline que le journal applicatif (ADR 0009).

## Alternatives rejetées

- **Ne rien persister** (status quo) : le constat fondateur du chantier
  — rejeté.
- **Persister TOUTE la sortie sans borne** : mémoire flash et
  confidentialité (des sorties contiennent des chemins et des extraits
  de code) ne le permettent pas ; sans borne, aucune promesse de taille
  n'est tenable — rejeté.
- **Réutiliser le journal applicatif** (`LogRepository`) : ses entrées
  sont des `LogEntry` bornées à 4 Kio par message et son relecteur est
  pensé pour l'usage disk usage/paliers 500 ; les sorties de build ont
  une autre forme (lots, verdict, compteurs, expurgation différente) —
  port séparé, format séparé, écriture séparée.
- **Base de données** (`core:database`) : complexité et migration pour
  un format append-only borné ; JSON Lines + gzip suffit et reste
  débuggable à la main — rejeté.
- **Rattachement persistant Logcat ↔ build** : la session Logcat est
  déjà archivée séparément (`ArchiveSessionsJournal`, dernière ligne
  seulement) ; rattacher le CONTENU du Logcat à une exécution amplifierait
  la rétention (données utilisateur) — rejeté ; le fil de l'incident
  (ADR 0162) n'affiche que des liens vers des sessions vivantes ou
  archivées existantes.

## Conséquences

- L3 livre le port, l'implémentation, la persistance expurgée et le
  raccord des journaux serveur ; la console actuelle reste active
  (migration par parité).
- Un test bornes + expurgation vérifie : plafond d'entrées, plafond
  d'octets, troncature annoncée, `LogRedactor` appliqué à toute ligne
  persistée, idempotence à la relecture.
- Les compteurs du résumé (ADR 0160) et la sortie textuelle voyagent
  ensemble : une exécution re-ouverte montre le même résumé que le live.
