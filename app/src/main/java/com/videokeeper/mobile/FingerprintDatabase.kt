package com.videokeeper.mobile

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase

@Entity(tableName = "video_fingerprints", primaryKeys = ["cacheKey"])
data class FingerprintEntity(
    val cacheKey: String,
    val uri: String,
    val bytes: Long,
    val modified: Long,
    val durationMs: Long,
    val width: Int,
    val height: Int,
    val algorithmVersion: Int,
    val samples: String,
    val createdAt: Long
)

@Dao
interface FingerprintDao {
    @Query("SELECT * FROM video_fingerprints WHERE cacheKey = :key LIMIT 1")
    fun find(key: String): FingerprintEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun put(entity: FingerprintEntity)

    @Query("DELETE FROM video_fingerprints")
    fun clear()

    @Query("DELETE FROM video_fingerprints WHERE uri = :uri")
    fun deleteForUri(uri: String)

    @Query("SELECT COUNT(*) FROM video_fingerprints")
    fun count(): Int
}

@Database(entities = [FingerprintEntity::class], version = 1, exportSchema = false)
abstract class FingerprintDatabase : RoomDatabase() {
    abstract fun fingerprintDao(): FingerprintDao

    companion object {
        @Volatile private var instance: FingerprintDatabase? = null

        fun get(context: Context): FingerprintDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext,
                FingerprintDatabase::class.java,
                "videokeeper-fingerprints.db"
            ).fallbackToDestructiveMigration().build().also { instance = it }
        }
    }
}

object FingerprintCodec {
    fun encode(frames: List<FrameSignature>): String = frames.joinToString(";") { frame ->
        listOf(
            frame.sampleIndex,
            frame.phash.toULong().toString(16),
            frame.dhash.toULong().toString(16),
            frame.luma,
            frame.color,
            if (frame.usable) 1 else 0
        ).joinToString(":")
    }

    fun decode(value: String): List<FrameSignature> = value.split(';').mapNotNull { part ->
        val fields = part.split(':')
        if (fields.size < 3) return@mapNotNull null
        runCatching {
            FrameSignature(
                phash = fields[1].toULong(16).toLong(),
                dhash = fields[2].toULong(16).toLong(),
                sampleIndex = fields[0].toInt(),
                luma = fields.getOrNull(3)?.toIntOrNull() ?: 0,
                color = fields.getOrNull(4)?.toIntOrNull() ?: 0,
                usable = fields.getOrNull(5)?.toIntOrNull() != 0
            )
        }.getOrNull()
    }
}
