# core:bootstrap

Localisation des outils du bootstrap natif (disposition Termux
`filesDir/usr`, marqueurs de validité par outil, remontée de symlink,
cache du wrapper Gradle) et source unique de l'environnement de
sous-processus — consommé par le terminal intégré **et** le futur
tooling, jamais dupliqué.

Localisation des outils (T1), lanceur de sous-processus non
interactifs et installateur complet du bootstrap (T2 : téléchargement
avec empreinte, extraction + liens symboliques, second stage, dépôt APT,
paquets d'outils), déploiement `aapt2`.

Statut : étapes T1 (v0.20.0) et T2 (v0.21.0) livrées — voir le README du
module, les ADR 0032/0033 et le prompt compagnon Terminal-1 (sections
1.4, 2.2, 3.1 à 3.5).
