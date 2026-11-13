# core/database

Persistance Room v1 de CodeIDE : table `projects` (index unique sur
`document_uri`, tri de l'accueil dans la requête — épingles, dernier
ouvert, nom insensible à la casse), schémas exportés dans `schemas/` pour
des migrations testables, mappeurs explicites vers le modèle (aucun
convertisseur opaque). Base du processus principal uniquement.

Statut : livré à l'étape 4 (v0.5.0) — voir le README du module et
`docs/ARCHITECTURE.md` § « Couche données ».
