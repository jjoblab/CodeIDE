# core/storage

Implémente `FileSystem` du domaine au-dessus du Storage Access Framework : URI (jamais de `File`), permissions persistantes, requêtes groupées `DocumentsContract` (jamais de boucle sur `DocumentFile`), gestion des dossiers refusés par Android 11+. Voir la section 5.6 du prompt et l'ADR 0003.

Contenu fonctionnel prévu : voir `README.md` du module.
