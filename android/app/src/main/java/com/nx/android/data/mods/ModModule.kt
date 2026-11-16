package com.nx.android.data.mods

import com.nx.android.domain.mods.ModRepository
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class ModModule {
    @Binds @Singleton
    abstract fun bindModRepository(impl: ModRepositoryImpl): ModRepository
}
