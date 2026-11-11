# core:terminal-runtime

Gestion des **sessions shell réelles** du terminal intégré (prompt
compagnon Terminal-1, section 4) : registre global des sessions
(`TerminalSessionRepository` du domaine + `TerminalRuntime` réservé au
rendu), service *foreground* qui les garde en vie hors écran.

Les objets réels sont des `TerminalSession` Termux
(`com.termux:terminal-emulator`, Apache-2.0 — pseudo-terminal dédié,
distinct de `NativeProcessLauncher`, section 1.5 du prompt).

Statut : étape T4 (v0.23.0) livrée — voir le README du module, l'ADR
0035 et le prompt compagnon Terminal-1 (sections 1.5, 2.2, 4).
