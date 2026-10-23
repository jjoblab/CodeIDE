package jo.codeide.core.database.di

import android.content.Context
import androidx.room.Room
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import jo.codeide.core.database.CodeIdeDatabase
import jo.codeide.core.database.ConversationStoreRoom
import jo.codeide.core.database.Migration1a2
import jo.codeide.core.database.ProjectDao
import jo.codeide.core.domain.ConversationStore
import javax.inject.Singleton

/**
 * Assemblage Hilt de la base Room (étape 4) — le module `core:database`
 * fournit lui-même ses dépendances (section 5.1) ; `core:data` et `app`
 * les agrègent.
 *
 * La base est un singleton du **processus principal** : ouverte
 * paresseusement à la première requête, jamais dans le processus
 * `:crash` (section 5.8 — l'écran dédié du plantage n'y accède pas).
 * Les migrations déclarées ([Migration1a2] — lot IA-6) sont testées sur
 * les schémas exportés ; jamais de fallback destructif.
 */
@Module
@InstallIn(SingletonComponent::class)
internal object DatabaseModule {
    /** Nom du fichier de base. */
    private const val NOM_BASE = "codeide.db"

    @Provides
    @Singleton
    internal fun provideDatabase(@ApplicationContext context: Context): CodeIdeDatabase = Room
        .databaseBuilder(context, CodeIdeDatabase::class.java, NOM_BASE)
        // Pas de fallback destructif : les migrations sont écrites et
        // testées à partir des schémas exportés (section 6, lot IA-6).
        .addMigrations(Migration1a2)
        .build()

    @Provides
    @Singleton
    internal fun provideProjectDao(database: CodeIdeDatabase): ProjectDao = database.projectDao()
}

/** Liaison du port de l'historique IA (mission IA, lot IA-6). */
@Module
@InstallIn(SingletonComponent::class)
internal abstract class ConversationStoreModule {
    @Binds
    @Singleton
    internal abstract fun lierConversationStore(impl: ConversationStoreRoom): ConversationStore
}
