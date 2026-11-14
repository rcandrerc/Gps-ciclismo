package br.com.gpsciclismo.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Upsert
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

/**
 * Cada registro tem um uuid, para facilitar sincronizar com um servidor no futuro
 * (login / compartilhar com o treinador).
 */
@Entity(indices = [Index(value = ["uuid"], unique = true)])
data class RideEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val uuid: String,
    val startedAt: Long,
    val endedAt: Long? = null,
    val workoutName: String? = null,
    val distanceM: Double = 0.0,
    val movingSec: Int = 0,
    val elapsedSec: Int = 0,
    val avgSpeedKmh: Double = 0.0,
    val maxSpeedKmh: Double = 0.0,
    val avgPower: Int = 0,
    val maxPower: Int = 0,
    val avgHr: Int = 0,
    val maxHr: Int = 0,
    val avgCadence: Int = 0,
    val kcal: Int = 0,
    /** Aproveitamento do treino por blocos (% do tempo na zona), ou null. */
    val adherencePct: Int? = null,
)

@Entity(
    foreignKeys = [ForeignKey(
        entity = RideEntity::class, parentColumns = ["id"], childColumns = ["rideId"],
        onDelete = ForeignKey.CASCADE,
    )],
    indices = [Index("rideId")],
)
data class RidePointEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val rideId: Long,
    val time: Long,
    val lat: Double?,
    val lon: Double?,
    val altitude: Double?,
    val speedKmh: Double?,
    val power: Int?,
    val heartRate: Int?,
    val cadence: Int?,
)

@Entity
data class WorkoutEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    /** JSON de [br.com.gpsciclismo.core.WorkoutPlan]. */
    val planJson: String,
    val updatedAt: Long = System.currentTimeMillis(),
)

@Dao
interface RideDao {
    @Insert suspend fun insertRide(ride: RideEntity): Long
    @Upsert suspend fun updateRide(ride: RideEntity)
    @Insert suspend fun insertPoints(points: List<RidePointEntity>)

    @Query("SELECT * FROM RideEntity WHERE endedAt IS NOT NULL ORDER BY startedAt DESC")
    fun rides(): Flow<List<RideEntity>>

    @Query("SELECT * FROM RideEntity WHERE id = :id")
    suspend fun ride(id: Long): RideEntity?

    @Query("SELECT * FROM RidePointEntity WHERE rideId = :rideId ORDER BY time")
    suspend fun points(rideId: Long): List<RidePointEntity>

    @Query("DELETE FROM RideEntity WHERE id = :id")
    suspend fun delete(id: Long)
}

@Dao
interface WorkoutDao {
    @Query("SELECT * FROM WorkoutEntity ORDER BY name")
    fun workouts(): Flow<List<WorkoutEntity>>

    @Query("SELECT * FROM WorkoutEntity WHERE id = :id")
    suspend fun workout(id: Long): WorkoutEntity?

    @Upsert suspend fun save(workout: WorkoutEntity): Long

    @Query("DELETE FROM WorkoutEntity WHERE id = :id")
    suspend fun delete(id: Long)
}

@Database(
    entities = [RideEntity::class, RidePointEntity::class, WorkoutEntity::class],
    version = 2,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun rides(): RideDao
    abstract fun workouts(): WorkoutDao

    companion object {
        fun create(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, "gps-ciclismo.db")
                .addMigrations(MIGRATION_1_2)
                .build()

        /** Versão 2: nota/aproveitamento do treino. Mantém o histórico. */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE RideEntity ADD COLUMN adherencePct INTEGER")
            }
        }
    }
}
