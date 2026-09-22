package jo.codeide.core.domain

/**
 * Détection des dossiers **refusés** par Android 11+ comme dossier de
 * travail (section 5.6 du prompt maître).
 *
 * Depuis Android 11, `ACTION_OPEN_DOCUMENT_TREE` interdit à l'utilisateur
 * de sélectionner : la racine d'un volume de stockage, `Download`, et les
 * sous-arborescences `Android/data` et `Android/obb`. Le sélecteur SAF
 * empêche déjà la sélection — mais une vérification défensive côté
 * application permet d'**expliquer clairement** le refus (onboarding,
 * étape 5) au lieu de laisser échouer la prise de permission plus tard.
 *
 * L'analyse est pure et travaille sur l'**identifiant de document** de
 * l'arborescence (le segment `…/tree/<id>` d'une URI SAF), indépendamment
 * de l'autorité du fournisseur : les identifiants des volumes externes
 * suivent la forme `<racine>:<chemin>` (`primary:` pour le stockage
 * principal, `XXXX-XXXX:` pour une carte SD, `raw:<chemin>` pour un
 * chemin brut). Tout format inconnu est réputé autorisé — la fonction ne
 * doit jamais refuser un dossier légitime par excès de zèle.
 */
public object ForbiddenFolders {
    /**
     * Raison du refus d'un dossier de travail.
     *
     * L'UI (onboarding, étape 5) traduit chaque raison en message clair
     * et actionnable ; le domaine ne porte que la classification.
     */
    public enum class Reason {
        /** Racine d'un volume de stockage. */
        STORAGE_ROOT,

        /** Dossier `Download` à la racine du volume. */
        DOWNLOADS,

        /** Sous-arborescence `Android/data` (données d'applications). */
        ANDROID_DATA,

        /** Sous-arborescence `Android/obb` (extensions de jeux). */
        ANDROID_OBB,
    }

    /**
     * Analyse un identifiant de document d'arborescence SAF.
     *
     * @param treeDocumentId segment identifiant de l'URI `…/tree/<id>`
     * (ex. `primary:CodeIDE`, `primary:`, `1A2B-3C4D:Download`).
     * @return la raison du refus, ou `null` si ce dossier est autorisé
     * (y compris si le format n'est pas reconnaissable — comportement
     * permissif assumé).
     */
    public fun reasonFor(treeDocumentId: String): Reason? {
        val chemin = cheminRelatif(treeDocumentId) ?: return null
        return raisonDuChemin(chemin)
    }

    /**
     * Classe un chemin relatif de volume.
     *
     * Chaîne vide = racine du volume ; `Download` (à la casse près) en
     * première composante = téléchargements ; `Android/data` et
     * `Android/obb` en deux premières composantes = arborescences
     * protégées.
     */
    private fun raisonDuChemin(chemin: String): Reason? {
        val segments = chemin.split('/').filter { it.isNotEmpty() }
        return when {
            segments.isEmpty() -> Reason.STORAGE_ROOT
            segments.first().equals("Download", ignoreCase = true) -> Reason.DOWNLOADS
            estSousArbreProtege(segments) -> raisonDuSousArbreProtege(segments[1])
            else -> null
        }
    }

    /** La première composante est `Android` avec une sous-composante. */
    private fun estSousArbreProtege(segments: List<String>): Boolean =
        segments.size >= 2 && segments.first().equals("Android", ignoreCase = true)

    /** Distingue `Android/data` de `Android/obb` (casse indifférente). */
    private fun raisonDuSousArbreProtege(seconde: String): Reason? =
        when {
            seconde.equals("data", ignoreCase = true) -> Reason.ANDROID_DATA
            seconde.equals("obb", ignoreCase = true) -> Reason.ANDROID_OBB
            else -> null
        }

    /**
     * Extrait le chemin **relatif au volume** d'un identifiant de
     * document, sans le préfixe de racine (`primary:`, `1A2B-3C4D:`) ni
     * les slashs parasites.
     *
     * Deux formes sont reconnues :
     * - `<racine>:<chemin relatif>` (volumes externes
     *   d'`ExternalStorageProvider`) ;
     * - `raw:<chemin absolu>` (fournisseurs de fichiers bruts), réduit
     *   au chemin relatif du volume quand il vit sous `/storage/<volume>`
     *   (`/storage/emulated/0/…` → `…`).
     *
     * @param treeDocumentId identifiant complet.
     * @return le chemin relatif (éventuellement vide = racine du
     * volume), ou `null` si l'identifiant ne suit aucune forme connue
     * (comportement permissif assumé).
     */
    private fun cheminRelatif(treeDocumentId: String): String? {
        val id = treeDocumentId.trim().trimEnd('/')
        val positionSeparateur = if (id.isEmpty()) -1 else id.indexOf(':')
        if (positionSeparateur < 0) {
            // Identifiant vide ou opaque (autre fournisseur) : par
            // principe permissif, considéré sans chemin → autorisé.
            return null
        }
        val racine = id.substring(0, positionSeparateur)
        val chemin = id.substring(positionSeparateur + 1)
        return if (racine == "raw") cheminRelatifBrut(chemin) else chemin
    }

    /**
     * Réduit un chemin brut `raw:` au suffixe relatif au point de
     * montage (`/storage/<volume>` est le préfixe technique ; le volume
     * emulé occupe deux segments : `/storage/emulated/<utilisateur>`).
     */
    private fun cheminRelatifBrut(chemin: String): String? {
        if (!chemin.startsWith("/storage/")) return null
        val segments =
            chemin
                .removePrefix("/storage/")
                .split('/')
                .filter { it.isNotEmpty() }
        val segmentsDuVolume = if (segments.firstOrNull() == "emulated") 2 else 1
        return segments.drop(segmentsDuVolume).joinToString("/")
    }
}
