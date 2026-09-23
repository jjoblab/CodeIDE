# core:bootstrap

Localisation des outils du bootstrap natif (disposition Termux
`filesDir/usr`, marqueurs de validité par outil, remontée de symlink,
cache du wrapper Gradle) et source unique de l'environnement de
sous-processus — consommé par le terminal intégré **et** le futur
tooling, jamais dupliqué.

Statut : étape T1 livrée (v0.20.0) — voir le README du module, l'ADR
0032 et le prompt compagnon Terminal-1 (sections 1.4, 2.2, 3.1, 3.2).
