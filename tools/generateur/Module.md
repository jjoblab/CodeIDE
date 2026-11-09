# tools/generateur

Outil Kotlin JVM en ligne de commande des modèles de projet (ADR 0019 ;
multi-commandes au lot L2, ADR 0157) : `lister` (état réel des manifestes),
`generer` (génération sur disque en lot ou unitaire), `matrice` (dérivation
des combinaisons des manifestes — stratégies exhaustif/paires/bornes),
`valider-manifestes` (lint i18n/expressions/sources/choix atteignables) et
`determinisme` (deux générations comparées octet pour octet). Protocole de
sortie `PROTOCOLE=2` ; piloté par `scripts/verify-templates.sh`, qui ajoute
les cas nommés de régression puis compile, teste et exécute chaque projet
généré avec les vrais outils.

Les sept modèles embarqués (`kotlin-jvm`, `java`, `spring-boot`,
`kotlin-multiplatform`, `android-app`, `android-library`, `gradle-plugin`)
sont couverts par sa matrice dérivée et ses tests d'invariants d'effet.

Contenu détaillé : voir `README.md` du module.

Statut : lot L2 du chantier générateur/Nouveau projet (v0.210.0, ADR 0157).
