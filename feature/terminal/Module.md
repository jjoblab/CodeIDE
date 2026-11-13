# feature:terminal

Écran **plein écran** du terminal intégré (prompt compagnon Terminal-1,
section 5) : toolbar, onglets de sessions défilants, un seul
`TerminalView` rebranché sur la session active, clavier étendu
**interne** (termux-shared refusé pour licence, ADR 0035/0036), thèmes
clair/sombre et réglage dédié de taille de police.

Seule fonctionnalité autorisée à dépendre de `core:terminal-runtime`
(API de rendu de la section 4.4 du prompt) et des bibliothèques
`com.termux:terminal-view`/`terminal-emulator` (Apache-2.0).

Statut : étape T5 (v0.24.0) livrée — voir le README du module, l'ADR
0036 et le prompt compagnon Terminal-1 (sections 2.3 et 5).
