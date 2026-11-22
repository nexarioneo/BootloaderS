package dev.bootloaderstudio.app.xiaomi

import dev.bootloaderstudio.app.util.Hex
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException
import java.security.SecureRandom

data class ClearResult(
    val notice: String,
    val cleanOrNot: Int
)

data class UnlockResult(
    val encryptData: String
)

class XiaomiUnlockRepository(
    private val client: OkHttpClient
) : XiaomiUnlockService {

    override suspend fun requestNonce(account: XiaomiAccount): String = withContext(Dispatchers.IO) {
        val params = linkedMapOf(
            "r" to randomString(16),
            "sid" to XiaomiSigner.SID
        )
        val json = send(
            account = account,
            path = "/api/v2/nonce",
            paramOrder = listOf("r", "sid"),
            params = params
        )
        ensureSuccess(json)
        val nonce = json.optString("nonce")
        if (nonce.isBlank()) {
            throw XiaomiApiException(-1, "Xiaomi did not return an unlock nonce.")
        }
        nonce
    }

    override suspend fun clearDevice(
        account: XiaomiAccount,
        product: String,
        nonce: String
    ): ClearResult = withContext(Dispatchers.IO) {
        val data = JSONObject().put("product", product).toString()
        val params = linkedMapOf(
            "appId" to "1",
            "data" to data,
            "nonce" to nonce,
            "sid" to XiaomiSigner.SID
        )
        val json = send(
            account = account,
            path = "/api/v2/unlock/device/clear",
            paramOrder = listOf("appId", "data", "nonce", "sid"),
            params = params
        )
        ensureSuccess(json)
        ClearResult(
            notice = json.optString("notice").ifBlank {
                "Xiaomi approved the request. Review the notice and continue."
            },
            cleanOrNot = json.optInt("cleanOrNot", -1)
        )
    }

    override suspend fun requestUnlock(
        account: XiaomiAccount,
        product: String,
        deviceToken: String,
        deviceId: String,
        nonce: String
    ): UnlockResult = withContext(Dispatchers.IO) {
        val data = JSONObject().apply {
            put("clientId", "2")
            put("clientVersion", CLIENT_VERSION)
            put(
                "deviceInfo",
                JSONObject().apply {
                    put("boardVersion", "")
                    put("deviceName", "")
                    put("product", product)
                    put("socId", "")
                }
            )
            put("deviceToken", deviceToken)
            put("language", "en")
            put("operate", "unlock")
            put("pcId", Hex.md5(deviceId))
            put("region", "")
            put("uid", account.userId)
        }.toString()

        val params = linkedMapOf(
            "appId" to "1",
            "data" to data,
            "nonce" to nonce,
            "sid" to XiaomiSigner.SID
        )
        val json = send(
            account = account,
            path = "/api/v3/ahaUnlock",
            paramOrder = listOf("appId", "data", "nonce", "sid"),
            params = params
        )
        ensureSuccess(json)

        val encryptData = json.optString("encryptData")
        if (encryptData.isBlank()) {
            throw XiaomiApiException(-1, "Xiaomi approved the request but returned no unlock data.")
        }
        if (encryptData.length % 2 != 0 || encryptData.any { it.digitToIntOrNull(16) == null }) {
            throw XiaomiApiException(-1, "Xiaomi returned an invalid unlock token.")
        }
        UnlockResult(encryptData)
    }

    private fun send(
        account: XiaomiAccount,
        path: String,
        paramOrder: List<String>,
        params: Map<String, String>
    ): JSONObject {
        val body = XiaomiSigner.sign(
            path = path,
            paramOrder = paramOrder,
            params = params,
            ssecurity = account.ssecurity
        )
        val request = Request.Builder()
            .url("${account.host}$path")
            .post(body)
            .header("User-Agent", USER_AGENT)
            .header("Cookie", account.serviceToken.trim().removeSuffix(";"))
            .build()

        client.newCall(request).execute().use { response ->
            if (response.code == 401 || response.code == 403) {
                throw XiaomiAuthException("Xiaomi rejected the unlock session.")
            }
            if (!response.isSuccessful) {
                throw IOException("Xiaomi unlock server returned HTTP ${response.code}.")
            }
            val raw = response.body?.string()
                ?: throw IOException("Xiaomi unlock server returned an empty response.")
            val decoded = try {
                XiaomiSigner.decryptResponse(raw, account.ssecurity)
            } catch (_: Exception) {
                raw
            }
            return try {
                JSONObject(decoded)
            } catch (error: Exception) {
                throw IOException("Unable to decode the Xiaomi unlock response.", error)
            }
        }
    }

    private fun ensureSuccess(json: JSONObject) {
        // Xiaomi sometimes omits "code" on success. Only treat a present,
        // non-zero code as a rejection.
        if (!json.has("code") || json.isNull("code")) return
        val code = json.code()
        if (code != 0) {
            throw XiaomiApiException(
                code = code,
                serverMessage = json.errorMessage(),
                rawResponse = json.toString()
            )
        }
    }

    private fun JSONObject.code(): Int = when (val value = opt("code")) {
        is Number -> value.toInt()
        is String -> value.toIntOrNull() ?: -1
        else -> -1
    }

    private fun JSONObject.errorMessage(): String {
        return sequenceOf("descEN", "description", "notice", "message", "result")
            .map { optString(it).trim() }
            .firstOrNull { it.isNotEmpty() }
            ?: "Xiaomi rejected the unlock request."
    }

    private fun randomString(length: Int): String {
        val alphabet = "abcdefghijklmnopqrstuvwxyz"
        val random = SecureRandom()
        return buildString(length) {
            repeat(length) { append(alphabet[random.nextInt(alphabet.length)]) }
        }
    }

    companion object {
        // The Windows Mi Unlock client version. Update when Xiaomi requires a newer one.
        private const val CLIENT_VERSION = "7.6.727.43"
        private const val USER_AGENT = "dev.bootloaderstudio.app"
    }
}
