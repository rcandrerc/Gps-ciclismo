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
    /** JSON do treino seguido ([br.com.gpsciclismo.core.WorkoutPlan]), para exportar e analisar. */
    val planJson: String? = null,
    /** FTP do perfil quando o treino começou. */
    val ftpUsed: Int? = null,
    /** Teste de FTP: média de potência do esforço e segundos com potência. */
    val testAvgPower: Int? = null,
    val testSec: Int? = null,
    /** Quando foi enviado para a nuvem (null = ainda não subiu). */
    val syncedAt: Long? = null,
    /**
     * Dia do plano (AAAA-MM-DD) para o qual este treino conta, escolhido pelo atleta
     * (ex.: treino de 25 feito em 26). Null = automático.
     */
    val planDate: String? = null,
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
    /** Índice do bloco do treino (0 = primeiro), ou null sem treino estruturado. */
    val block: Int? = null,
)

@Entity
data class WorkoutEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    /** JSON de [br.com.gpsciclismo.core.WorkoutPlan]. */
    val planJson: String,
    val updatedAt: Long = System.currentTimeMillis(),
)

/** Pesagem (de preferência antes do treino), com foto opcional do visor da balança. */
@Entity(indices = [Index(value = ["uuid"], unique = true)])
data class WeightEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val uuid: String,
    val measuredAt: Long,
    val weightKg: Double,
    /** FTP do perfil no dia, para mostrar o W/kg da época. */
    val ftp: Int? = null,
    /** Caminho da foto no aparelho (filesDir/weights/uuid.jpg), ou null sem foto. */
    val photoPath: String? = null,
    /** Quando foi enviado para a nuvem (null = ainda não subiu). */
    val syncedAt: Long? = null,
)

@Dao
interface WeightDao {
    @Query("SELECT * FROM WeightEntity ORDER BY measuredAt DESC")
    fun weights(): Flow<List<WeightEntity>>

    @Query("SELECT * FROM WeightEntity ORDER BY measuredAt DESC LIMIT 1")
    fun latestFlow(): Flow<WeightEntity?>

    @Query("SELECT * FROM WeightEntity ORDER BY measuredAt DESC LIMIT 1")
    suspend fun latest(): WeightEntity?

    @Insert suspend fun insert(w: WeightEntity): Long

    @Query("DELETE FROM WeightEntity WHERE id = :id")
    suspend fun delete(id: Long)

    // ---- nuvem ----
    @Query("SELECT * FROM WeightEntity WHERE syncedAt IS NULL ORDER BY measuredAt")
    suspend fun unsynced(): List<WeightEntity>

    @Query("SELECT * FROM WeightEntity")
    suspend fun all(): List<WeightEntity>

    @Query("UPDATE WeightEntity SET syncedAt = :at WHERE id = :id")
    suspend fun markSynced(id: Long, at: Long)

    @Query("UPDATE WeightEntity SET syncedAt = NULL")
    suspend fun clearSynced()
}

data class RidePlanDate(val uuid: String, val planDate: String?)

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

    // ---- nuvem ----
    @Query("SELECT * FROM RideEntity WHERE endedAt IS NOT NULL AND syncedAt IS NULL ORDER BY startedAt")
    suspend fun unsynced(): List<RideEntity>

    @Query("SELECT * FROM RideEntity WHERE endedAt IS NOT NULL")
    suspend fun finished(): List<RideEntity>

    @Query("UPDATE RideEntity SET syncedAt = :at WHERE id = :id")
    suspend fun markSynced(id: Long, at: Long)

    /** Muda o dia do plano do treino; sobe de novo para a nuvem. */
    @Query("UPDATE RideEntity SET planDate = :planDate, syncedAt = NULL WHERE id = :id")
    suspend fun setPlanDate(id: Long, planDate: String?)

    /** Mudança de dia que veio de outro aparelho (já está na nuvem). */
    @Query("UPDATE RideEntity SET planDate = :planDate WHERE uuid = :uuid AND syncedAt IS NOT NULL")
    suspend fun applyRemotePlanDate(uuid: String, planDate: String?)

    @Query("SELECT uuid, planDate FROM RideEntity")
    suspend fun planDates(): List<RidePlanDate>

    /** Outra conta entrou neste aparelho: os treinos daqui sobem de novo para ela. */
    @Query("UPDATE RideEntity SET syncedAt = NULL")
    suspend fun clearSynced()

    @Query("SELECT uuid FROM RideEntity")
    suspend fun uuids(): List<String>
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
    entities = [RideEntity::class, RidePointEntity::class, WorkoutEntity::class, WeightEntity::class],
    version = 6,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun rides(): RideDao
    abstract fun workouts(): WorkoutDao
    abstract fun weights(): WeightDao

    companion object {
        fun create(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, "gps-ciclismo.db")
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6)
                .build()

        /** Versão 2: nota/aproveitamento do treino. Mantém o histórico. */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE RideEntity ADD COLUMN adherencePct INTEGER")
            }
        }

        /** Versão 3: teste de FTP e dados para exportar (plano, FTP usado, bloco de cada ponto). */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE RideEntity ADD COLUMN planJson TEXT")
                db.execSQL("ALTER TABLE RideEntity ADD COLUMN ftpUsed INTEGER")
                db.execSQL("ALTER TABLE RideEntity ADD COLUMN testAvgPower INTEGER")
                db.execSQL("ALTER TABLE RideEntity ADD COLUMN testSec INTEGER")
                db.execSQL("ALTER TABLE RidePointEntity ADD COLUMN block INTEGER")
            }
        }

        /** Versão 4: marca de quando o treino subiu para a nuvem. */
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE RideEntity ADD COLUMN syncedAt INTEGER")
            }
        }

        /** Versão 6: dia do plano escolhido pelo atleta para o treino. */
        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE RideEntity ADD COLUMN planDate TEXT")
            }
        }

        /** Versão 5: histórico de peso (pesagem antes do treino, com foto da balança). */
        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `WeightEntity` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`uuid` TEXT NOT NULL, `measuredAt` INTEGER NOT NULL, `weightKg` REAL NOT NULL, " +
                        "`ftp` INTEGER, `photoPath` TEXT, `syncedAt` INTEGER)"
                )
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_WeightEntity_uuid` ON `WeightEntity` (`uuid`)")
            }
        }
    }
}
