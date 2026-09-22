# tools/generateur

Outil Kotlin JVM en ligne de commande : harnais de génération sur disque
(étape 9, ADR 0019). Produit de vrais projets depuis les assets embarqués du
dépôt (`app/src/main/assets`) en réutilisant le moteur de templates de
`core:domain` — plan figé de `PlanProjectCreationUseCase` déversé sur disque.
Piloté par `scripts/verify-templates.sh`.

Étape 9 : création du module avec la validation réelle des modèles `kotlin-jvm`
et `java` (builds Gradle/Maven, exécution, publication).

Contenu détaillé : voir `README.md` du module.

Statut : étape 9 (v0.10.0).
