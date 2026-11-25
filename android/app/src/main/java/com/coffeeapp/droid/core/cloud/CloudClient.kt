// Copyright (c) 2026 Christian Reichardt
// SPDX-License-Identifier: MIT
// Assisted by AI (Claude).
//
// Portions derived from pylamarzocco (MIT), Copyright (c) 2026 Josef Zweck.
// See THIRD-PARTY-NOTICES.md at the repository root.

package com.coffeeapp.droid.core.cloud

import com.coffeeapp.droid.core.log.FileLog as Log
import com.coffeeapp.droid.core.config.SecretsStore
import com.coffeeapp.droid.core.machine.CloudMachine
import com.coffeeapp.droid.core.model.MachineSnapshot
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** Raised when the cloud rejects our credentials or our request signature. */
class AuthFailure(message: String) : Exception(message)

/** Raised when a request fails for any other reason. */
class RequestFailure(
    message: String,
    /** HTTP status, when there was a response. */
    val status: Int? = null,
    /** The `code` of a 403 body, e.g. `USER_MUST_ACCEPT_POLICY` (pylamarzocco 2.5.3). */
    val code: String? = null,
) : Exception(message)

/**
 * Long-lived La Marzocco cloud client.
 *
 * Keeps ONE HTTP client and one authenticated session for the process lifetime, and serialises requests
 * behind a mutex so a background refresh cannot interleave with a stop-brew
 * halfway through a token reuse.
 *
 * IMPORTANT: use the WIRE names and types, not pylamarzocco's. Its
 * `LaMarzoccoMachine.to_dict()` re-serialises its own parsed model -
 * snake_case names, datetimes as ISO strings. The REST API sends
 * `brewingStartTime` (not `brewing_start_time`) as epoch MILLISECONDS (not
 * an ISO string). Reading the re-serialised shape would compile, run, and
 * silently never capture a machine start time.
 */
class CloudClient(
    private val serial: String,
    private val secrets: SecretsStore,
) : CloudMachine {

    private companion object {
        const val TAG = "CloudClient"
        const val BASE_URL = "https://lion.lamarzocco.io/api/customer-app"

        /**
         * The API does not return an expiry, so pylamarzocco assumes one hour
         * and refreshes ten minutes early. Same numbers here.
         */
        const val TOKEN_LIFETIME_MS = 60 * 60 * 1000L
        const val TOKEN_REFRESH_MARGIN_MS = 10 * 60 * 1000L

        const val REQUEST_TIMEOUT_MS = 10_000L
    }

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private val http = HttpClient(OkHttp) {
        expectSuccess = false
        install(HttpTimeout) {
            requestTimeoutMillis = REQUEST_TIMEOUT_MS
            connectTimeoutMillis = REQUEST_TIMEOUT_MS
        }
    }

    /**
     * Guards the token state only (installation key, sign-in, refresh). It
     * used to wrap every whole HTTP exchange, so a stop that fell back to the
     * cloud queued behind any refresh in flight - up to ~20 s on a bad
     * network. OkHttp is thread-safe; requests themselves now run side by side.
     */
    private val authLock = Mutex()

    @Volatile private var installationKey: InstallationKey? = null
    @Volatile private var accessToken: String? = null
    @Volatile private var refreshToken: String? = null
    @Volatile private var tokenExpiresAt: Long = 0L

    /** Consecutive failed refreshes; the stack trace is logged only for the first. */
    @Volatile private var refreshFailures = 0

    fun close() {
        http.close()
    }

    // --- Authentication ---------------------------------------------------

    /**
     * Load the installation key, generating and registering one on first run.
     *
     * Registration happens exactly once per install: the key is persisted, and
     * re-registering on every launch would create a fresh client against the
     * account each time.
     */
    private suspend fun ensureInstallationKey(): InstallationKey {
        installationKey?.let { return it }

        val stored = secrets.loadInstallationKey()
        if (stored != null) {
            return InstallationKey.fromStored(stored).also { installationKey = it }
        }

        val key = InstallationKey.generate()
        Log.i(TAG, "Registering a new installation key with the LM cloud")
        val response = http.post("$BASE_URL/auth/init") {
            header("X-App-Installation-Id", key.installationId)
            header("X-Request-Proof", key.registrationProof())
            contentType(ContentType.Application.Json)
            setBody(json.encodeToString(JsonObject.serializer(), buildJsonObject {
                put("pk", key.publicKeyB64)
            }))
        }
        if (response.status.value == 401) {
            throw AuthFailure("Client registration rejected (401)")
        }
        if (!response.status.isSuccess()) {
            // Status only: the body is the server's to say and the log is
            // mirrored to shared storage.
            throw RequestFailure("Client registration failed: ${response.status.value}")
        }

        secrets.saveInstallationKey(key.toStored())
        installationKey = key
        return key
    }

    /** The installation key and a valid bearer token, signing in or refreshing as needed. */
    private suspend fun credentials(): Pair<InstallationKey, String> = authLock.withLock {
        val key = ensureInstallationKey()
        val now = System.currentTimeMillis()
        val current = accessToken
        val token = when {
            current == null || tokenExpiresAt < now -> signIn()
            tokenExpiresAt < now + TOKEN_REFRESH_MARGIN_MS ->
                try {
                    refreshAccessToken()
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "Token refresh failed (${e.javaClass.simpleName}); signing in again")
                    signIn()
                }
            else -> current
        }
        key to token
    }

    /**
     * Forget a token the server just rejected, so the next call signs in
     * again instead of sending it for the rest of its assumed hour.
     */
    private suspend fun invalidateToken(rejected: String) = authLock.withLock {
        if (accessToken == rejected) {
            accessToken = null
            tokenExpiresAt = 0L
        }
    }

    private suspend fun signIn(): String {
        if (!secrets.hasCredentials) {
            throw AuthFailure("No La Marzocco credentials configured")
        }
        return fetchToken(
            "$BASE_URL/auth/signin",
            buildJsonObject {
                put("username", secrets.username)
                put("password", secrets.password)
            },
        ).also { Log.i(TAG, "Cloud signed in (token assumed valid ${TOKEN_LIFETIME_MS / 60_000} min)") }
    }

    private suspend fun refreshAccessToken(): String {
        val token = refreshToken ?: return signIn()
        return fetchToken(
            "$BASE_URL/auth/refreshtoken",
            buildJsonObject {
                put("username", secrets.username)
                put("refreshToken", token)
            },
        ).also { Log.i(TAG, "Cloud access token refreshed") }
    }

    private suspend fun fetchToken(url: String, body: JsonObject): String {
        val key = ensureInstallationKey()
        val response = http.post(url) {
            key.requestHeaders().forEach { (k, v) -> header(k, v) }
            contentType(ContentType.Application.Json)
            setBody(json.encodeToString(JsonObject.serializer(), body))
        }
        if (response.status.value == 401) {
            throw AuthFailure("Invalid username or password")
        }
        if (!response.status.isSuccess()) throw failure(response, "Token request")

        val parsed = parseObject(response.bodyAsText(), "auth")
        val access = parsed["accessToken"]?.jsonPrimitive?.content
            ?: throw RequestFailure("Token response had no accessToken")
        accessToken = access
        refreshToken = parsed["refreshToken"]?.jsonPrimitive?.content
        Log.redact(access)
        Log.redact(refreshToken)
        tokenExpiresAt = System.currentTimeMillis() + TOKEN_LIFETIME_MS
        return access
    }

    // --- Request plumbing -------------------------------------------------

    /**
     * Send an authorized request; on a 401, drop the token, sign in again and
     * retry once. Without the retry a token the server invalidated early was
     * reused until its assumed hour ran out - every call failing, the stop
     * fallback and the brew-start poll included.
     */
    private suspend fun authorized(url: String, send: suspend (InstallationKey, String) -> HttpResponse): HttpResponse {
        val (key, token) = credentials()
        val first = send(key, token)
        if (first.status.value != 401) return first
        Log.w(TAG, "Cloud rejected the access token (401) on ${pathOf(url)}; signing in again and retrying once")
        invalidateToken(token)
        val (key2, token2) = credentials()
        return send(key2, token2)
    }

    private suspend fun authorizedGet(url: String): JsonObject {
        val response = authorized(url) { key, token ->
            http.get(url) {
                key.requestHeaders().forEach { (k, v) -> header(k, v) }
                header("Authorization", "Bearer $token")
            }
        }
        return decodeObject(response, url)
    }

    private suspend fun authorizedPost(url: String, body: JsonObject?): String {
        val response = authorized(url) { key, token ->
            http.post(url) {
                key.requestHeaders().forEach { (k, v) -> header(k, v) }
                header("Authorization", "Bearer $token")
                contentType(ContentType.Application.Json)
                setBody(json.encodeToString(JsonObject.serializer(), body ?: buildJsonObject { }))
            }
        }
        if (response.status.value == 401) throw AuthFailure("Authentication failed")
        if (!response.status.isSuccess()) throw failure(response, "Request to ${pathOf(url)}")
        return response.bodyAsText()
    }

    /**
     * The exception for a failed response. A 403 says why in its body
     * (`{"success":false,"message":"User must accept latest policy",
     * "code":"USER_MUST_ACCEPT_POLICY"}`); only its code and a short message
     * are kept, so nothing else from a body reaches the mirrored log.
     */
    private suspend fun failure(response: HttpResponse, what: String): RequestFailure {
        val status = response.status.value
        if (status != 403) return RequestFailure("$what failed: $status", status)
        val body = try {
            json.parseToJsonElement(response.bodyAsText()) as? JsonObject
        } catch (_: Exception) {
            null
        }
        val code = (body?.get("code") as? JsonPrimitive)?.contentOrNull?.take(60)
        val message = (body?.get("message") as? JsonPrimitive)?.contentOrNull?.take(80)
        val why = listOfNotNull(code, message?.let { "\"$it\"" }).joinToString(" ")
        return RequestFailure("$what failed: 403${if (why.isEmpty()) "" else " $why"}", status, code)
    }

    private suspend fun decodeObject(response: HttpResponse, url: String): JsonObject {
        if (response.status.value == 401) throw AuthFailure("Authentication failed")
        if (!response.status.isSuccess()) throw failure(response, "Request to ${pathOf(url)}")
        return parseObject(response.bodyAsText(), pathOf(url))
    }

    /**
     * Parse a JSON object without letting the library's message escape: its
     * exceptions quote an excerpt of the input, and a token response or the
     * settings body (which carries the BLE token) would put that excerpt into
     * the log - which is mirrored to shared storage.
     */
    private fun parseObject(body: String, what: String): JsonObject = try {
        json.parseToJsonElement(body).jsonObject
    } catch (e: Exception) {
        throw RequestFailure("Malformed JSON from $what (${body.length} bytes, ${e.javaClass.simpleName})")
    }

    /** The URL without host and serial, for log lines. */
    private fun pathOf(url: String): String =
        url.removePrefix(BASE_URL).replace(serial, "<serial>")

    // --- Public API -------------------------------------------------------

    /**
     * Fetch dashboard and settings and fold them into a MachineSnapshot.
     *
     * Never throws: a failed refresh returns a snapshot with lastSyncOk=false
     * and the error attached, so a flaky
     * network degrades the UI rather than taking the runtime down.
     */
    override suspend fun refresh(): MachineSnapshot =
        try {
            val dashboard = authorizedGet("$BASE_URL/things/$serial/dashboard")
            // Settings is only wanted for wifi, firmware and the BLE token, so
            // it must not be able to fail the whole refresh - but say so,
            // since a failure here silently stops the BLE token sync.
            val settings = try {
                authorizedGet("$BASE_URL/things/$serial/settings")
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Cloud settings fetch failed (${e.javaClass.simpleName}: ${e.message}); BLE token not synced this time")
                null
            }
            settings?.let(::syncBleToken)
            CloudParsing.snapshotFrom(dashboard, settings).also {
                refreshFailures = 0
                Log.i(TAG, "Cloud refresh OK: status=${it.status} connected=${it.connected}")
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            // A cancelled refresh is not a failed one; the caller is gone.
            throw e
        } catch (e: Exception) {
            refreshFailures++
            val problem = CloudProblem.classify(e)
            // The trace once per outage: a night offline otherwise wrote ~50
            // identical 60-line traces over the session and probe lines.
            if (refreshFailures == 1) {
                Log.w(TAG, "Cloud refresh failed ($problem)", e)
            } else {
                Log.w(TAG, "Cloud refresh failed ($problem): ${e.javaClass.simpleName}: ${e.message} ($refreshFailures in a row)")
            }
            MachineSnapshot(lastSyncOk = false, lastError = e.message ?: e.toString(), cloudProblem = problem)
        }

    /**
     * The machine own brew start time, in epoch seconds, or null.
     *
     * Returns null unless the machine currently reports Brewing - the cloud
     * lags the event-driven scale by a beat or two, which is why ShotService
     * polls this rather than asking once.
     */
    override suspend fun getBrewStartTs(): Double? {
        val dashboard = try {
            authorizedGet("$BASE_URL/things/$serial/dashboard")
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "getBrewStartTs failed: ${e.javaClass.simpleName}: ${e.message}")
            return null
        }

        val (machineStatus, startSeconds) = CloudParsing.brewStart(dashboard)
        // DEBUG until it says Brewing: ShotService logs the outcome at INFO,
        // and the not-yet attempts were up to six INFO lines per shot.
        val line = "Machine brew-start fetch: status=$machineStatus brewingStartTime=$startSeconds"
        if (machineStatus == "Brewing") Log.i(TAG, line) else Log.d(TAG, line)

        if (machineStatus != "Brewing") return null
        if (startSeconds == null) {
            Log.w(TAG, "Machine status is Brewing but brewingStartTime is empty")
            return null
        }
        return startSeconds
    }

    override suspend fun setPower(on: Boolean) = run {
        executeCommand(
            "CoffeeMachineChangeMode",
            buildJsonObject { put("mode", if (on) "BrewingMode" else "StandBy") },
        )
        Log.i(TAG, "Power ${if (on) "on" else "off"} sent to $serial")
    }

    // There is no stopBrew here any more: the Micra has no stop-brew API, so
    // a stop is setPower(false), and MachineController decides whether that
    // goes over BLE or through here.

    override suspend fun setCoffeeTemp(temperature: Double) = run {
        executeCommand(
            "CoffeeMachineSettingCoffeeBoilerTargetTemperature",
            buildJsonObject {
                put("boilerIndex", 1)
                put("targetTemperature", Math.round(temperature * 10.0) / 10.0)
            },
        )
        Log.i(TAG, "Coffee target temp ${temperature}C sent to $serial")
    }

    /** [level] is "Level1", "Level2" or "Level3" - the API enum values. */
    override suspend fun setSteamLevel(level: String) = run {
        require(level in setOf("Level1", "Level2", "Level3")) { "Invalid steam level: $level" }
        executeCommand(
            "CoffeeMachineSettingSteamBoilerTargetLevel",
            buildJsonObject {
                put("boilerIndex", 1)
                put("targetLevel", level)
            },
        )
        Log.i(TAG, "Steam level $level sent to $serial")
    }

    override suspend fun setSteamEnabled(enabled: Boolean) = run {
        executeCommand(
            "CoffeeMachineSettingSteamBoilerEnabled",
            buildJsonObject {
                put("boilerIndex", 1)
                put("enabled", enabled)
            },
        )
        Log.i(TAG, "Steam boiler ${if (enabled) "on" else "off"} sent to $serial")
    }

    private suspend fun executeCommand(command: String, body: JsonObject) {
        // pylamarzocco optionally waits for a websocket confirmation of the
        // command. We do not hold a websocket, and pylamarzocco itself
        // returns true immediately in that case - so firing the REST call and
        // trusting the HTTP status is the same behaviour, and keeps the stop
        // command as short as it can be.
        authorizedPost("$BASE_URL/things/$serial/command/$command", body)
    }

    // --- Parsing (see CloudParsing) ----------------------------------------

    /**
     * Keep [SecretsStore.bleToken] in step with the machine. The settings
     * response carries `bleAuthToken` (pylamarzocco ThingSettings extends
     * Thing), so local BLE control costs no extra request. Never logged.
     */
    private fun syncBleToken(settings: JsonObject) {
        val token = settings["bleAuthToken"]?.jsonPrimitive?.contentOrNull
        if (token.isNullOrBlank() || token == secrets.bleToken) return
        secrets.bleToken = token
        Log.i(TAG, "BLE token synced from the cloud")
    }
}
