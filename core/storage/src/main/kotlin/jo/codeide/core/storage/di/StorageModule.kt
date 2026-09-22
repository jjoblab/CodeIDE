package jo.codeide.core.storage.di

import android.content.ContentResolver
import android.content.Context
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import jo.codeide.core.domain.ArborescencesSaf
import jo.codeide.core.domain.FileSystem
import jo.codeide.core.storage.ContentResolverPersistableUriPermissions
import jo.codeide.core.storage.PersistableUriPermissions
import jo.codeide.core.storage.SafArborescences
import jo.codeide.core.storage.SafFileSystem
import javax.inject.Singleton

/**
 * Assemblage Hilt du module `core:storage` (section 5.1) : le module
 * fournit lui-même la liaison du port [FileSystem] vers son
 * implémentation SAF — le reste de l'application n'injecte que
 * l'interface du domaine.
 */
@Module
@InstallIn(SingletonComponent::class)
internal interface StorageBindsModule {
    /** Le port FileSystem est servi par l'implémentation SAF. */
    @Binds
    @Singleton
    fun bindFileSystem(impl: SafFileSystem): FileSystem

    /** Port de décomposition des URI d'arborescence (étape 6). */
    @Binds
    fun bindArborescences(impl: SafArborescences): ArborescencesSaf
}

/** Fournitures des collaborateurs système du module. */
@Module
@InstallIn(SingletonComponent::class)
internal object StorageProvidesModule {
    /** Résolveur de contenu de l'application. */
    @Provides
    @Singleton
    fun provideContentResolver(
        @ApplicationContext context: Context,
    ): ContentResolver = context.contentResolver

    /** Port des permissions persistantes sur le résolveur réel. */
    @Provides
    @Singleton
    fun providePersistableUriPermissions(resolver: ContentResolver): PersistableUriPermissions =
        ContentResolverPersistableUriPermissions(resolver)
}
