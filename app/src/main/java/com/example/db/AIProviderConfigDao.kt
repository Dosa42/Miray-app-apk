package com.example.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.example.model.AIProviderConfig
import kotlinx.coroutines.flow.Flow

@Dao
interface AIProviderConfigDao {

    @Query("SELECT * FROM provider_configs")
    fun getAllConfigs(): Flow<List<AIProviderConfig>>

    @Query("SELECT * FROM provider_configs")
    suspend fun getAllConfigsList(): List<AIProviderConfig>

    @Query("SELECT * FROM provider_configs WHERE providerId = :providerId LIMIT 1")
    suspend fun getConfigById(providerId: String): AIProviderConfig?

    @Query("SELECT * FROM provider_configs WHERE providerId = :providerId LIMIT 1")
    fun getConfigByIdFlow(providerId: String): Flow<AIProviderConfig?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrUpdate(config: AIProviderConfig)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(configs: List<AIProviderConfig>)

    @Query("DELETE FROM provider_configs WHERE providerId = :providerId")
    suspend fun deleteConfig(providerId: String)
}
