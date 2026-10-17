package app.pagedrop.discover

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
interface DiscoverModule {
    @Binds
    @Singleton
    fun bindDiscoverHttp(impl: OkHttpDiscoverHttp): DiscoverHttp
}
