package br.com.gpsciclismo.data

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/**
 * Converte treinos e perfil para o formato de documento do Firestore (API REST) e de volta.
 * Sem dependência do Android, para dar para testar no computador.
 *
 * Estrutura na nuvem (cada pessoa só enxerga a própria pasta, pelas regras do Firestore):
 *  users/{uid}                         perfil (nome, peso, altura, FTP)
 *  users/{uid}/rides/{uuid}            resumo do treino
 *  users/{uid}/rides/{uuid}/points/{n} pontos segundo a segundo, compactados, em pedaços
 *  users/{uid}/weights/{uuid}          pesagem (kg, FTP do dia e foto JPEG da balança)
 */
object CloudCodec {
    /** Pontos por pedaço: 3 h de pedal a 1 Hz cabem em 3 pedaços, bem abaixo do limite de 1 MB do Firestore. */
    const val POINTS_PER_CHUNK = 4000

    // ---- valores do Firestore ----
    fun str(v: String?): JsonObject = if (v == null) nul() else obj("stringValue", JsonPrimitive(v))
    fun int(v: Long?): JsonObject = if (v == null) nul() else obj("integerValue", JsonPrimitive(v.toString()))
    fun int(v: Int?): JsonObject = int(v?.toLong())
    fun dbl(v: Double?): JsonObject = if (v == null || v.isNaN() || v.isInfinite()) nul() else obj("doubleValue", JsonPrimitive(v))
    fun bytes(v: ByteArray): JsonObject = obj("bytesValue", JsonPrimitive(Base64.getEncoder().encodeToString(v)))
    private fun nul() = obj("nullValue", JsonNull)
    private fun obj(k: String, v: JsonElement) = buildJsonObject { put(k, v) }

    private fun JsonObject.field(name: String): JsonObject? = (this[name] as? JsonObject)
    fun JsonObject.getStr(name: String): String? = field(name)?.get("stringValue")?.jsonPrimitive?.contentOrNull
    fun JsonObject.getLong(name: String): Long? {
        val f = field(name) ?: return null
        f["integerValue"]?.jsonPrimitive?.contentOrNull?.toLongOrNull()?.let { return it }
        return f["doubleValue"]?.jsonPrimitive?.contentOrNull?.toDoubleOrNull()?.toLong()
    }
    fun JsonObject.getInt(name: String): Int? = getLong(name)?.toInt()
    fun JsonObject.getDouble(name: String): Double? {
        val f = field(name) ?: return null
        return (f["doubleValue"] ?: f["integerValue"])?.jsonPrimitive?.contentOrNull?.toDoubleOrNull()
    }
    fun JsonObject.getBytes(name: String): ByteArray? =
        field(name)?.get("bytesValue")?.jsonPrimitive?.contentOrNull?.let { Base64.getDecoder().decode(it) }

    /** Os campos ("fields") de um documento retornado pela API. */
    fun fieldsOf(doc: JsonObject): JsonObject = doc["fields"]?.jsonObject ?: JsonObject(emptyMap())

    fun document(fields: Map<String, JsonObject>): JsonObject = buildJsonObject { put("fields", JsonObject(fields)) }

    // ---- treino (resumo) ----
    fun rideFields(r: RideEntity, pointCount: Int, chunks: Int): Map<String, JsonObject> = mapOf(
        "uuid" to str(r.uuid),
        "startedAt" to int(r.startedAt),
        "endedAt" to int(r.endedAt),
        "workoutName" to str(r.workoutName),
        "distanceM" to dbl(r.distanceM),
        "movingSec" to int(r.movingSec),
        "elapsedSec" to int(r.elapsedSec),
        "avgSpeedKmh" to dbl(r.avgSpeedKmh),
        "maxSpeedKmh" to dbl(r.maxSpeedKmh),
        "avgPower" to int(r.avgPower),
        "maxPower" to int(r.maxPower),
        "avgHr" to int(r.avgHr),
        "maxHr" to int(r.maxHr),
        "avgCadence" to int(r.avgCadence),
        "kcal" to int(r.kcal),
        "adherencePct" to int(r.adherencePct),
        "planJson" to str(r.planJson),
        "ftpUsed" to int(r.ftpUsed),
        "testAvgPower" to int(r.testAvgPower),
        "testSec" to int(r.testSec),
        "planDate" to str(r.planDate),
        "pointCount" to int(pointCount),
        "pointChunks" to int(chunks),
    )

    /** Treino vindo da nuvem (sem id local). Null se o documento estiver incompleto. */
    fun rideFrom(f: JsonObject, syncedAt: Long): RideEntity? {
        val uuid = f.getStr("uuid") ?: return null
        val startedAt = f.getLong("startedAt") ?: return null
        return RideEntity(
            uuid = uuid,
            startedAt = startedAt,
            endedAt = f.getLong("endedAt") ?: startedAt,
            workoutName = f.getStr("workoutName"),
            distanceM = f.getDouble("distanceM") ?: 0.0,
            movingSec = f.getInt("movingSec") ?: 0,
            elapsedSec = f.getInt("elapsedSec") ?: 0,
            avgSpeedKmh = f.getDouble("avgSpeedKmh") ?: 0.0,
            maxSpeedKmh = f.getDouble("maxSpeedKmh") ?: 0.0,
            avgPower = f.getInt("avgPower") ?: 0,
            maxPower = f.getInt("maxPower") ?: 0,
            avgHr = f.getInt("avgHr") ?: 0,
            maxHr = f.getInt("maxHr") ?: 0,
            avgCadence = f.getInt("avgCadence") ?: 0,
            kcal = f.getInt("kcal") ?: 0,
            adherencePct = f.getInt("adherencePct"),
            planJson = f.getStr("planJson"),
            ftpUsed = f.getInt("ftpUsed"),
            testAvgPower = f.getInt("testAvgPower"),
            testSec = f.getInt("testSec"),
            syncedAt = syncedAt,
            planDate = f.getStr("planDate"),
        )
    }

    // ---- pontos ----
    /** Uma linha por ponto: tempo,lat,lon,alt,vel,pot,fc,cad,bloco (vazio = sem dado). */
    fun encodePoints(points: List<RidePointEntity>): ByteArray {
        val sb = StringBuilder(points.size * 48)
        for (p in points) {
            sb.append(p.time).append(',')
                .append(p.lat ?: "").append(',')
                .append(p.lon ?: "").append(',')
                .append(p.altitude ?: "").append(',')
                .append(p.speedKmh ?: "").append(',')
                .append(p.power ?: "").append(',')
                .append(p.heartRate ?: "").append(',')
                .append(p.cadence ?: "").append(',')
                .append(p.block ?: "").append('\n')
        }
        return gzip(sb.toString().toByteArray())
    }

    fun decodePoints(data: ByteArray, rideId: Long): List<RidePointEntity> =
        gunzip(data).decodeToString().lineSequence().filter { it.isNotBlank() }.mapNotNull { line ->
            val c = line.split(',')
            if (c.size < 9) return@mapNotNull null
            RidePointEntity(
                rideId = rideId,
                time = c[0].toLongOrNull() ?: return@mapNotNull null,
                lat = c[1].toDoubleOrNull(),
                lon = c[2].toDoubleOrNull(),
                altitude = c[3].toDoubleOrNull(),
                speedKmh = c[4].toDoubleOrNull(),
                power = c[5].toIntOrNull(),
                heartRate = c[6].toIntOrNull(),
                cadence = c[7].toIntOrNull(),
                block = c[8].toIntOrNull(),
            )
        }.toList()

    // ---- peso ----
    /** Campos leves da pesagem (sem a foto), usados para listar sem baixar todas as fotos. */
    val WEIGHT_LIGHT_FIELDS = listOf("uuid", "measuredAt", "weightKg", "ftp", "hasPhoto")

    fun weightFields(w: WeightEntity, photo: ByteArray?): Map<String, JsonObject> = buildMap {
        put("uuid", str(w.uuid))
        put("measuredAt", int(w.measuredAt))
        put("weightKg", dbl(w.weightKg))
        put("ftp", int(w.ftp))
        put("hasPhoto", obj("booleanValue", JsonPrimitive(photo != null)))
        if (photo != null) put("photo", bytes(photo))
    }

    /** Pesagem vinda da nuvem (sem id nem foto local). Null se o documento estiver incompleto. */
    fun weightFrom(f: JsonObject, syncedAt: Long): WeightEntity? {
        val uuid = f.getStr("uuid") ?: return null
        val at = f.getLong("measuredAt") ?: return null
        val kg = f.getDouble("weightKg") ?: return null
        return WeightEntity(uuid = uuid, measuredAt = at, weightKg = kg, ftp = f.getInt("ftp"), syncedAt = syncedAt)
    }

    fun JsonObject.getBool(name: String): Boolean? =
        field(name)?.get("booleanValue")?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull()

    // ---- perfil ----
    fun profileFields(p: Profile, updatedAt: Long): Map<String, JsonObject> = mapOf(
        "name" to str(p.name),
        "weightKg" to dbl(p.weightKg),
        "heightCm" to int(p.heightCm),
        "ftp" to int(p.ftp),
        "profileUpdatedAt" to int(updatedAt),
    )

    fun profileFrom(f: JsonObject): Pair<Profile, Long>? {
        val updated = f.getLong("profileUpdatedAt") ?: return null
        return Profile(
            name = f.getStr("name") ?: "",
            weightKg = f.getDouble("weightKg") ?: 0.0,
            heightCm = f.getInt("heightCm") ?: 0,
            ftp = f.getInt("ftp") ?: 200,
        ) to updated
    }

    private fun gzip(b: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        GZIPOutputStream(out).use { it.write(b) }
        return out.toByteArray()
    }

    private fun gunzip(b: ByteArray): ByteArray = GZIPInputStream(b.inputStream()).use { it.readBytes() }
}
