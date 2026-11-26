package br.com.gpsciclismo.data

import android.content.Context
import android.net.Uri
import androidx.room.withTransaction
import br.com.gpsciclismo.BuildConfig
import br.com.gpsciclismo.data.CloudCodec.getBool
import br.com.gpsciclismo.data.CloudCodec.getBytes
import br.com.gpsciclismo.data.CloudCodec.getInt
import br.com.gpsciclismo.data.CloudCodec.getStr
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/** O que a tela de conta mostra. */
data class CloudState(
    val email: String? = null,
    val syncing: Boolean = false,
    val lastSync: Long = 0L,
    val message: String? = null,
    val error: Boolean = false,
)

/**
 * Conta e sincronização com o Firebase (login com Google + Firestore),
 * usando só a API REST. Assim funciona igual no celular e no tablet Amazon Fire,
 * que não tem os serviços do Google Play.
 *
 * Os treinos continuam salvos no aparelho (funciona sem internet no pedal);
 * quando há conta e internet, os treinos novos sobem e os que vieram de outro aparelho descem.
 */
class CloudSync(private val context: Context, private val db: AppDatabase, private val prefs: Prefs) {
    private val apiKey = BuildConfig.FIREBASE_API_KEY
    private val projectId = BuildConfig.FIREBASE_PROJECT_ID
    /** False quando o APK foi gerado sem a configuração do Firebase. */
    val configured: Boolean = apiKey.isNotBlank() && projectId.isNotBlank()

    private val sp = context.getSharedPreferences("cloud", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private val again = AtomicBoolean(false)

    private val _state = MutableStateFlow(CloudState(email = sp.getString("email", null), lastSync = sp.getLong("lastSync", 0L)))
    val state: StateFlow<CloudState> = _state.asStateFlow()

    val signedIn: Boolean get() = configured && sp.getString("uid", null) != null

    init {
        prefs.onProfileChanged = { requestSync() }
    }

    // ------------------------------------------------------------------ conta

    /**
     * Endereço da página de login com Google (abre no navegador do aparelho).
     * Funciona também no tablet Fire, que não tem os serviços do Google Play:
     * a página faz o login pelo Firebase e devolve o app com gpsciclismo://auth.
     */
    fun googleLoginUrl(): String {
        val state = UUID.randomUUID().toString()
        sp.edit().putString("loginState", state).apply()
        val q = listOf("k" to apiKey, "p" to projectId, "s" to state)
            .joinToString("&") { (k, v) -> k + "=" + URLEncoder.encode(v, "UTF-8") }
        return "${BuildConfig.LOGIN_PAGE_URL}?$q"
    }

    /** Volta da página de login (gpsciclismo://auth#rt=...&s=...). Retorna a mensagem de erro, ou null. */
    suspend fun completeGoogleLogin(uri: Uri): String? = withContext(Dispatchers.IO) {
        if (!configured) return@withContext NOT_CONFIGURED
        val params = (uri.fragment ?: uri.query ?: "").split('&').mapNotNull {
            val i = it.indexOf('='); if (i <= 0) null else it.substring(0, i) to Uri.decode(it.substring(i + 1))
        }.toMap()
        val expected = sp.getString("loginState", null)
        if (expected == null || params["s"] != expected) return@withContext "Login não reconhecido. Toque em Entrar com Google de novo."
        val rt = params["rt"] ?: return@withContext "O login não terminou. Tenta de novo."
        sp.edit().remove("loginState").apply()
        val result = try {
            run<String?> login@{
            val form = "grant_type=refresh_token&refresh_token=" + URLEncoder.encode(rt, "UTF-8")
            val r = http("POST", "$TOKEN?key=$apiKey", form, contentType = "application/x-www-form-urlencoded")
            if (!r.ok) return@login "O Google não confirmou o login (${errorMessage(r.body)}). Tenta de novo."
            val o = json.parseToJsonElement(r.body).jsonObject
            val uid = o.s("user_id") ?: return@login "Resposta estranha do Firebase. Tenta de novo."
            val idTok = o.s("id_token") ?: return@login "Resposta estranha do Firebase. Tenta de novo."
            // E-mail da conta, só para mostrar na tela.
            val look = http("POST", "$AUTH/accounts:lookup?key=$apiKey", buildJsonObject { put("idToken", idTok) }.toString())
            val email = if (look.ok) runCatching {
                json.parseToJsonElement(look.body).jsonObject["users"]!!.jsonArray[0].jsonObject.s("email")
            }.getOrNull() else null
            // Outra conta neste aparelho: os treinos daqui sobem também para ela.
            if (sp.getString("lastUid", null) != uid) {
                db.rides().clearSynced()
                db.weights().clearSynced()
                sp.edit().putLong("lastSync", 0L).remove("pendingDeletes").remove("pendingWeightDeletes").apply()
            }
            sp.edit()
                .putString("uid", uid).putString("lastUid", uid)
                .putString("email", email ?: "conta Google")
                .putString("idToken", idTok).putString("refreshToken", o.s("refresh_token") ?: rt)
                .putLong("idExp", System.currentTimeMillis() + (o.s("expires_in")?.toLongOrNull() ?: 3600L) * 1000)
                .apply()
            null
            }
        } catch (e: IOException) {
            OFFLINE
        }
        if (result == null) {
            _state.value = CloudState(email = sp.getString("email", null), lastSync = sp.getLong("lastSync", 0L))
            requestSync()
        } else {
            _state.value = _state.value.copy(message = result, error = true)
        }
        result
    }

    fun signOut() {
        sp.edit().remove("uid").remove("email").remove("idToken").remove("idExp").remove("refreshToken")
            .remove("pendingDeletes").remove("pendingWeightDeletes").apply()
        _state.value = CloudState()
    }

    /** Token de acesso válido, renovando quando passa de 1 h. */
    private fun idToken(): String {
        val tok = sp.getString("idToken", null)
        if (tok != null && System.currentTimeMillis() < sp.getLong("idExp", 0L) - 60_000) return tok
        val refresh = sp.getString("refreshToken", null) ?: throw SyncError(SIGNED_OUT)
        val form = "grant_type=refresh_token&refresh_token=" + URLEncoder.encode(refresh, "UTF-8")
        val r = http("POST", "$TOKEN?key=$apiKey", form, contentType = "application/x-www-form-urlencoded")
        if (!r.ok) {
            if (r.code in 400..403) { signOut(); throw SyncError(SIGNED_OUT) }
            throw IOException("token ${r.code}")
        }
        val o = json.parseToJsonElement(r.body).jsonObject
        val id = o.s("id_token") ?: throw IOException("token")
        sp.edit().putString("idToken", id).putString("refreshToken", o.s("refresh_token") ?: refresh)
            .putLong("idExp", System.currentTimeMillis() + (o.s("expires_in")?.toLongOrNull() ?: 3600L) * 1000).apply()
        return id
    }

    // ------------------------------------------------------------ sincronizar

    /** Pede uma sincronização em segundo plano (se já estiver rodando, roda de novo no fim). */
    fun requestSync() {
        if (!signedIn) return
        if (mutex.isLocked) { again.set(true); return }
        scope.launch {
            do {
                again.set(false)
                syncNow()
            } while (again.get())
        }
    }

    /** Treino apagado aqui: apaga também na nuvem (agora ou na próxima vez que houver internet). */
    fun forgetRide(uuid: String) {
        if (!signedIn) return
        val set = sp.getStringSet("pendingDeletes", emptySet())!!.toMutableSet().apply { add(uuid) }
        sp.edit().putStringSet("pendingDeletes", set).apply()
        requestSync()
    }

    /** Pesagem apagada aqui: apaga também na nuvem. */
    fun forgetWeight(uuid: String) {
        if (!signedIn) return
        val set = sp.getStringSet("pendingWeightDeletes", emptySet())!!.toMutableSet().apply { add(uuid) }
        sp.edit().putStringSet("pendingWeightDeletes", set).apply()
        requestSync()
    }

    /** Sincroniza agora. Retorna a mensagem de erro, ou null se deu certo. */
    suspend fun syncNow(): String? = mutex.withLock {
        if (!signedIn) return@withLock null
        _state.value = _state.value.copy(syncing = true)
        val result = try {
            val r = withContext(Dispatchers.IO) { doSync() }
            val now = System.currentTimeMillis()
            sp.edit().putLong("lastSync", now).apply()
            _state.value = CloudState(email = sp.getString("email", null), lastSync = now, message = r)
            null
        } catch (e: SyncError) {
            e.message
        } catch (e: IOException) {
            OFFLINE
        } catch (e: Exception) {
            "Erro ao sincronizar: ${e.message ?: e.javaClass.simpleName}"
        }
        if (result != null) {
            _state.value = _state.value.copy(email = sp.getString("email", null), syncing = false, message = result, error = true)
        }
        result
    }

    private suspend fun doSync(): String {
        val uid = sp.getString("uid", null) ?: throw SyncError(SIGNED_OUT)
        val userPath = "users/$uid"

        // 1) Perfil: vale o que foi mudado por último.
        val remoteProfile = getDoc(userPath)?.let { CloudCodec.profileFrom(CloudCodec.fieldsOf(it)) }
        val localAt = prefs.profileUpdatedAt
        if (remoteProfile != null && remoteProfile.second > localAt) {
            prefs.applyRemoteProfile(remoteProfile.first, remoteProfile.second)
        } else if (localAt > 0 && localAt > (remoteProfile?.second ?: -1L)) {
            commit(listOf(update(userPath, CloudCodec.profileFields(prefs.profile.value, localAt))))
        }

        // 2) Treinos apagados neste aparelho.
        val pending = sp.getStringSet("pendingDeletes", emptySet())!!.toSet()
        for (uuid in pending) {
            val ridePath = "$userPath/rides/$uuid"
            val chunks = listDocs("$ridePath/points").map { it.name() }
            commit(chunks.map { delete(it) } + delete(fullName(ridePath)))
            val left = sp.getStringSet("pendingDeletes", emptySet())!!.toMutableSet().apply { remove(uuid) }
            sp.edit().putStringSet("pendingDeletes", left).apply()
        }

        // 3) Treinos novos daqui sobem (pontos e resumo juntos, tudo ou nada).
        var sent = 0
        for (ride in db.rides().unsynced()) {
            if (ride.uuid in pending) continue
            val points = db.rides().points(ride.id)
            val chunks = points.chunked(CloudCodec.POINTS_PER_CHUNK)
            val ridePath = "$userPath/rides/${ride.uuid}"
            val writes = chunks.mapIndexed { i, c ->
                update("$ridePath/points/${i.toString().padStart(3, '0')}", mapOf(
                    "i" to CloudCodec.int(i), "data" to CloudCodec.bytes(CloudCodec.encodePoints(c)),
                ))
            } + update(ridePath, CloudCodec.rideFields(ride, points.size, chunks.size))
            commit(writes)
            db.rides().markSynced(ride.id, System.currentTimeMillis())
            sent++
        }

        // 4) Treinos de outros aparelhos descem; os apagados lá somem daqui.
        val remote = listDocs("$userPath/rides")
        val remoteUuids = HashSet<String>()
        val localUuids = db.rides().uuids().toHashSet()
        localUuids += sp.getStringSet("pendingDeletes", emptySet())!! // apagado aqui durante a sincronização
        var received = 0
        for (doc in remote) {
            val f = CloudCodec.fieldsOf(doc)
            val ride = CloudCodec.rideFrom(f, System.currentTimeMillis()) ?: continue
            remoteUuids += ride.uuid
            if (ride.uuid in localUuids) continue
            val nChunks = f.getInt("pointChunks") ?: 0
            val points = listDocs("$userPath/rides/${ride.uuid}/points")
                .map { CloudCodec.fieldsOf(it) }
                .filter { (it.getInt("i") ?: 0) < nChunks }
                .flatMap { c -> c.getBytes("data")?.let { CloudCodec.decodePoints(it, 0) } ?: emptyList() }
                .sortedBy { it.time }
            db.withTransaction {
                val id = db.rides().insertRide(ride)
                points.map { it.copy(rideId = id) }.chunked(2000).forEach { db.rides().insertPoints(it) }
            }
            received++
        }
        var removed = 0
        for (r in db.rides().finished()) {
            if (r.syncedAt != null && r.uuid !in remoteUuids) {
                db.rides().delete(r.id)
                removed++
            }
        }

        // 5) Pesagens: a foto tirada no celular aparece no tablet (e vice-versa).
        syncWeights(userPath)

        val parts = buildList {
            if (sent > 0) add("$sent enviado${if (sent > 1) "s" else ""}")
            if (received > 0) add("$received baixado${if (received > 1) "s" else ""}")
            if (removed > 0) add("$removed apagado${if (removed > 1) "s" else ""}")
        }
        return if (parts.isEmpty()) "Tudo em dia." else "Sincronizado: ${parts.joinToString(", ")}."
    }

    private suspend fun syncWeights(userPath: String) {
        val pending = sp.getStringSet("pendingWeightDeletes", emptySet())!!.toSet()
        if (pending.isNotEmpty()) {
            commit(pending.map { delete(fullName("$userPath/weights/$it")) })
            val left = sp.getStringSet("pendingWeightDeletes", emptySet())!!.toMutableSet().apply { removeAll(pending) }
            sp.edit().putStringSet("pendingWeightDeletes", left).apply()
        }
        for (w in db.weights().unsynced()) {
            if (w.uuid in pending) continue
            val photo = w.photoPath?.let { File(it) }?.takeIf { it.exists() && it.length() < MAX_PHOTO_BYTES }?.readBytes()
            commit(listOf(update("$userPath/weights/${w.uuid}", CloudCodec.weightFields(w, photo))))
            db.weights().markSynced(w.id, System.currentTimeMillis())
        }
        // Lista só os campos leves; a foto desce apenas para as pesagens novas.
        val remote = listDocs("$userPath/weights", mask = CloudCodec.WEIGHT_LIGHT_FIELDS)
        val local = db.weights().all()
        val localUuids = local.map { it.uuid }.toHashSet()
        localUuids += sp.getStringSet("pendingWeightDeletes", emptySet())!!
        val remoteUuids = HashSet<String>()
        var newest: WeightEntity? = null
        for (doc in remote) {
            val f = CloudCodec.fieldsOf(doc)
            val w = CloudCodec.weightFrom(f, System.currentTimeMillis()) ?: continue
            remoteUuids += w.uuid
            if (w.uuid in localUuids) continue
            var photoPath: String? = null
            if (f.getBool("hasPhoto") == true) {
                val full = getDoc("$userPath/weights/${w.uuid}")?.let { CloudCodec.fieldsOf(it) }
                full?.getBytes("photo")?.let { bytes ->
                    val file = WeightPhotos.fileFor(context, w.uuid)
                    file.writeBytes(bytes)
                    photoPath = file.path
                }
            }
            val saved = w.copy(photoPath = photoPath)
            db.weights().insert(saved)
            if (newest == null || saved.measuredAt > newest.measuredAt) newest = saved
        }
        for (w in local) {
            if (w.syncedAt != null && w.uuid !in remoteUuids) {
                w.photoPath?.let { File(it).delete() }
                db.weights().delete(w.id)
            }
        }
        // Pesagem nova de outro aparelho: o peso do perfil acompanha.
        val latest = db.weights().latest()
        if (newest != null && latest?.uuid == newest.uuid && prefs.profile.value.weightKg != newest.weightKg) {
            prefs.applyRemoteProfile(prefs.profile.value.copy(weightKg = newest.weightKg), System.currentTimeMillis())
            again.set(true) // na próxima volta o perfil com o peso novo sobe para a nuvem
        }
    }

    // --------------------------------------------------------------- Firestore

    private val docsBase get() = "https://firestore.googleapis.com/v1/projects/$projectId/databases/(default)/documents"
    private fun fullName(path: String) = "projects/$projectId/databases/(default)/documents/$path"
    private fun JsonObject.name(): String = this["name"]!!.jsonPrimitive.content

    private fun update(path: String, fields: Map<String, JsonObject>) =
        buildJsonObject { put("update", buildJsonObject { put("name", fullName(path)); put("fields", JsonObject(fields)) }) }

    private fun delete(name: String) = buildJsonObject { put("delete", name) }

    private fun commit(writes: List<JsonObject>) {
        if (writes.isEmpty()) return
        writes.chunked(400).forEach { part ->
            val body = buildJsonObject { put("writes", JsonArray(part)) }
            ensureOk(fsCall("POST", "$docsBase:commit", body.toString()))
        }
    }

    private fun getDoc(path: String): JsonObject? {
        val r = fsCall("GET", "$docsBase/$path", null)
        if (r.code == 404 && !databaseMissing(r)) return null
        ensureOk(r)
        return json.parseToJsonElement(r.body).jsonObject
    }

    private fun listDocs(path: String, mask: List<String>? = null): List<JsonObject> {
        val out = ArrayList<JsonObject>()
        var pageToken: String? = null
        val maskQuery = mask?.joinToString("") { "&mask.fieldPaths=$it" } ?: ""
        do {
            val url = "$docsBase/$path?pageSize=100$maskQuery" + (pageToken?.let { "&pageToken=" + URLEncoder.encode(it, "UTF-8") } ?: "")
            val r = fsCall("GET", url, null)
            ensureOk(r)
            val o = json.parseToJsonElement(r.body).jsonObject
            o["documents"]?.jsonArray?.forEach { out += it.jsonObject }
            pageToken = o.s("nextPageToken")
        } while (!pageToken.isNullOrEmpty())
        return out
    }

    private fun fsCall(method: String, url: String, body: String?): Resp {
        var r = http(method, url, body, token = idToken())
        if (r.code == 401) { // token venceu no meio: renova e tenta uma vez
            sp.edit().remove("idToken").apply()
            r = http(method, url, body, token = idToken())
        }
        return r
    }

    private fun databaseMissing(r: Resp) = r.body.contains("does not exist", ignoreCase = true) && r.body.contains("database", ignoreCase = true)

    private fun ensureOk(r: Resp) {
        if (r.ok) return
        val msg = errorMessage(r.body)
        throw SyncError(when {
            r.code == 404 && databaseMissing(r) -> "O banco Firestore ainda não foi criado no Firebase."
            r.body.contains("SERVICE_DISABLED") || msg.contains("has not been used", true) ->
                "O Firestore não está ativado neste projeto do Firebase."
            r.code == 403 -> "O Firebase recusou o acesso. Confira as regras do Firestore."
            r.code == 401 -> SIGNED_OUT
            r.code >= 500 -> "O Firebase está com problema agora. Tenta mais tarde."
            else -> "Erro do Firebase (${r.code}): $msg"
        })
    }

    private fun errorMessage(body: String): String = runCatching {
        json.parseToJsonElement(body).jsonObject["error"]!!.jsonObject.s("message")
    }.getOrNull() ?: body.take(120)

    // -------------------------------------------------------------------- HTTP

    private class Resp(val code: Int, val body: String) { val ok get() = code in 200..299 }

    private fun http(
        method: String, url: String, body: String? = null, token: String? = null,
        contentType: String = "application/json",
    ): Resp {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 15_000
            readTimeout = 60_000
            setRequestProperty("Accept", "application/json")
            token?.let { setRequestProperty("Authorization", "Bearer $it") }
            if (body != null) {
                doOutput = true
                setRequestProperty("Content-Type", "$contentType; charset=utf-8")
            }
        }
        try {
            if (body != null) conn.outputStream.use { it.write(body.toByteArray()) }
            val code = conn.responseCode
            val text = (if (code in 200..299) conn.inputStream else conn.errorStream)?.bufferedReader()?.use { it.readText() } ?: ""
            return Resp(code, text)
        } finally {
            conn.disconnect()
        }
    }

    private fun JsonObject.s(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

    private class SyncError(message: String) : Exception(message)

    companion object {
        private const val AUTH = "https://identitytoolkit.googleapis.com/v1"
        private const val TOKEN = "https://securetoken.googleapis.com/v1/token"
        /** Foto acima disso não sobe (o documento do Firestore tem limite de 1 MB). */
        private const val MAX_PHOTO_BYTES = 700_000L
        const val NOT_CONFIGURED = "Este APK foi gerado sem a configuração do Firebase."
        const val OFFLINE = "Sem internet agora. Os treinos ficam no aparelho e sobem depois."
        const val SIGNED_OUT = "Sua sessão expirou. Entre de novo."
    }
}
