package {{packageName}}

import android.content.Context
import androidx.room.Room
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Fournitures Hilt de la base Room : la base est instanciée une seule fois
 * par processus, injectée ensuite où besoin (voir MainActivity).
 */
@Module
@InstallIn(SingletonComponent::class)
object ModuleDonnees {
    /** Fournit la base Room (instance unique, créée paresseusement par Hilt). */
    @Provides
    @Singleton
    fun bdd(
        @ApplicationContext contexte: Context,
    ): BddLocale =
        Room.databaseBuilder(contexte, BddLocale::class.java, "{{appName|resourceName}}.db")
            .build()
}
