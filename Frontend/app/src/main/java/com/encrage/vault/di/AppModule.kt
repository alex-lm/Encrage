package com.encrage.vault.di

import android.content.Context
import android.content.ContentResolver
import androidx.room.Room
import com.encrage.vault.BuildConfig
import com.encrage.vault.crypto.LibsodiumXChaChaEngine
import com.encrage.vault.crypto.XChaChaEngine
import com.encrage.vault.data.local.EncrageDatabase
import com.encrage.vault.data.local.MediaDao
import com.encrage.vault.data.local.TransferDao
import com.encrage.vault.data.remote.AuthApi
import com.encrage.vault.data.remote.ApiEndpointInterceptor
import com.encrage.vault.data.remote.BearerInterceptor
import com.encrage.vault.data.remote.EncrageApi
import com.encrage.vault.data.remote.RefreshAuthenticator
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import javax.inject.Qualifier
import javax.inject.Singleton

@Qualifier annotation class RawHttp
@Qualifier annotation class AuthenticatedHttp

@Module
@InstallIn(SingletonComponent::class)
object AppModule {
    @Provides fun contentResolver(@ApplicationContext context: Context): ContentResolver = context.contentResolver
    @Provides @Singleton
    fun database(@ApplicationContext context: Context): EncrageDatabase =
        Room.databaseBuilder(context, EncrageDatabase::class.java, "encrage.db")
            .setJournalMode(androidx.room.RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
            .build()

    @Provides fun mediaDao(db: EncrageDatabase): MediaDao = db.mediaDao()
    @Provides fun transferDao(db: EncrageDatabase): TransferDao = db.transferDao()

    @Provides @Singleton @RawHttp
    fun rawHttp(endpointInterceptor: ApiEndpointInterceptor): OkHttpClient = OkHttpClient.Builder()
        .addInterceptor(endpointInterceptor)
        .addInterceptor(HttpLoggingInterceptor().apply {
            redactHeader("Authorization")
            level = if (BuildConfig.DEBUG) HttpLoggingInterceptor.Level.BASIC else HttpLoggingInterceptor.Level.NONE
        })
        .build()

    @Provides @Singleton @AuthenticatedHttp
    fun authenticatedHttp(
        @RawHttp raw: OkHttpClient,
        bearer: BearerInterceptor,
        authenticator: RefreshAuthenticator,
    ): OkHttpClient = raw.newBuilder().addInterceptor(bearer).authenticator(authenticator).build()

    @Provides @Singleton
    fun authApi(@RawHttp client: OkHttpClient): AuthApi = retrofit(client).create(AuthApi::class.java)

    @Provides @Singleton
    fun api(@AuthenticatedHttp client: OkHttpClient): EncrageApi = retrofit(client).create(EncrageApi::class.java)

    private fun retrofit(client: OkHttpClient) = Retrofit.Builder()
        .baseUrl(BuildConfig.API_BASE_URL)
        .client(client)
        .addConverterFactory(GsonConverterFactory.create())
        .build()
}

@Module
@InstallIn(SingletonComponent::class)
abstract class CryptoModule {
    @Binds abstract fun xChaChaEngine(implementation: LibsodiumXChaChaEngine): XChaChaEngine
}
