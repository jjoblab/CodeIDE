package jo.codeide.core.database

import androidx.room.Database
import androidx.room.RoomDatabase

/**
 * Base Room de CodeIDE — version 5 (rapport IA, lot 3 — épinglage des
 * discussions de l'historique : colonne `epingle` sur
 * `ai_conversations`, tri « épinglées d'abord » ; version 4, retour
 * propriétaire v0.195.0 : raisonnement du modèle sur `ai_messages` —
 * les modèles « qui réfléchissent » voient leur flux de pensée
 * persisté avec la réponse ; version 3 : compteurs d'usage par
 * conversation — `jetons_entree` + `jetons_sortie` sur
 * `ai_conversations` ; version 2 : historique des conversations IA —
 * `ai_conversations` + `ai_messages`, cascade).
 *
 * Le schéma est **exporté** dans `core/database/schemas/` (convention
 * `codeide.android.room`) : chaque fichier JSON versionné est la
 * référence pour écrire et tester les migrations des versions futures ;
 * jamais de `fallbackToDestructiveMigration` (les données utilisateur
 * ne disparaissent pas pour un simple changement de schéma). La
 * migration 1→2 ([Migration1a2]) est testée sur le schéma v1 exporté.
 *
 * Ne pas ouvrir dans le processus `:crash` (section 5.8) : la base vit
 * uniquement dans le processus principal ; l'écran dédié du plantage
 * n'accède jamais à Room.
 *
 * La classe est publique : c'est l'API du module — `core:data` et les
 * tests de repositories l'instancient (en mémoire) sans détour.
 */
@Database(
    entities = [ProjectEntity::class, ConversationIaEntity::class, MessageIaEntity::class],
    version = 5,
    exportSchema = true,
)
public abstract class CodeIdeDatabase : RoomDatabase() {
    /** Accès au registre des projets. */
    public abstract fun projectDao(): ProjectDao

    /** Accès à l'historique des conversations IA (lot IA-6). */
    public abstract fun conversationIaDao(): ConversationIaDao
}
