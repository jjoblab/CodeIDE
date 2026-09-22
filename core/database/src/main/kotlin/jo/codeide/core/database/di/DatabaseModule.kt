package jo.codeide.core.database.di

import android.content.Context
import androidx.room.Room
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import jo.codeide.core.database.CodeIdeDatabase
import jo.codeide.core.database.ProjectDao
import javax.inject.Singleton

/**
 * Assemblage Hilt de la base Room (étape 4) — le module `core:database`
 * fournit lui-même ses dépendances (section 5.1) ; `core:data` et `app`
 * les agrègent.
 *
 * La base est un singleton du **processus principal** : ouverte
 * paresseusement à la première requête, jamais dans le processus
 * `:crash` (section 5.8 — l'écran dédié du plantage n'y accède pas).
 */
@Module
@InstallIn(SingletonComponent::class)
internal object DatabaseModule {
    /** Nom du fichier de base. */
    private const val NOM_BASE = "codeide.db"

    @Provides
    @Singleton
    internal fun provideDatabase(
        @ApplicationContext context: Context,
    ): CodeIdeDatabase =
        Room
            .databaseBuilder(context, CodeIdeDatabase::class.java, NOM_BASE)
            // Pas de fallback destructif : les migrations seront écrites
            // et testées à partir des schémas exportés (section 6).
            .build()

    @Provides
    @Singleton
    internal fun provideProjectDao(database: CodeIdeDatabase): ProjectDao = database.projectDao()
}
