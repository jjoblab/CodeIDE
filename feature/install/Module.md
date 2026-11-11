# feature:install

Écran d'installation du bootstrap natif (prompt compagnon Terminal-1,
section 3.4/6) : **écran de progression partagé** — l'état vit dans le
singleton du domaine (`BootstrapInstaller`), cet écran peut être ouvert
depuis l'étape « Terminal » de l'onboarding ou le bandeau de l'accueil,
y compris pendant une installation déjà lancée, et sa fermeture ne
l'interrompt pas.

Statut : étape T3 livrée (v0.22.0) — dépend uniquement de
`core:ui`/`core:domain`/`core:model` (aucune dépendance Termux, aucune
dépendance `core:bootstrap` : le port suffit).
