# core/datastore

Source des paramètres applicatifs : Preferences DataStore projeté vers
`AppSettings` (lecture tolérante champ par champ, corruption remplacée
par les défauts, transformations atomiques lire-transformer-réécrire,
dossier de travail en trio de clés, défauts par type de build pour la
verbosité de journalisation).

Statut : livré à l'étape 4 (v0.5.0) — voir le README du module et
`docs/ARCHITECTURE.md` § « Couche données ».
