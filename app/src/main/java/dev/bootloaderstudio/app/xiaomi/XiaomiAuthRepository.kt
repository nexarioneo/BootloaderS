package dev.bootloaderstudio.app.xiaomi

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONObject
import java.io.IOException
import java.net.URLDecoder

class XiaomiAuthRepository(
    private val baseClient: OkHttpClient,
    private val baseUrl: String = DEFAULT_BASE_URL
) : XiaomiAuthService {

    override suspend fun resolveAccount(session: XiaomiSession): XiaomiAccount = withContext(Dispatchers.IO) {
        val client = baseClient.newBuilder()
            .cookieJar(MemoryCookieJar(session))
            .build()

        val region = getRegion(client)
        val regionConfig = getRegionConfig(client, region)
        val host = hostFor(regionConfig, region)
        val (ssecurity, serviceToken) = getSsecurityAndServiceToken(client)

        XiaomiAccount(
            userId = session.userId,
            region = region,
            host = host,
            ssecurity = ssecurity,
            serviceToken = serviceToken
        )
    }

    private fun getRegion(client: OkHttpClient): String {
        val body = getBody(
            client,
            url("/pass/user/login/region")
        )
        return try {
            val json = JSONObject(body)
            json.getJSONObject("data").getString("region")
        } catch (error: Exception) {
            throw XiaomiAuthException(
                "Xiaomi did not recognize the saved account session.",
                error
            )
        }
    }

    private fun getRegionConfig(client: OkHttpClient, region: String): String {
        val body = getBody(
            client,
            url("/pass2/config?key=regionConfig")
        )
        val configs = try {
            JSONObject(body).getJSONObject("regionConfig")
        } catch (error: Exception) {
            throw IOException(
                "Xiaomi returned an invalid account region configuration.",
                error
            )
        }
        val keys = configs.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            val config = configs.optJSONObject(key) ?: continue
            val codes = config.optJSONArray("region.codes") ?: continue
            for (index in 0 until codes.length()) {
                if (region.equals(codes.optString(index), ignoreCase = true)) {
                    return key
                }
            }
            if (region.equals(config.optString("region"), ignoreCase = true)) {
                return key
            }
        }

        return fallbackRegionConfig(region)
    }

    private fun hostFor(regionConfig: String, region: String): String {
        val subdomain = when (regionConfig) {
            "China" -> "unlock.update"
            "Singapore", "Global" -> "unlock.update.intl"
            "India" -> "in-unlock.update.intl"
            "Russia" -> "ru-unlock.update.intl"
            "Europe" -> "eu-unlock.update.intl"
            else -> when {
                region.equals("CN", true) || region.contains("china", true) -> "unlock.update"
                region.equals("IN", true) || region.contains("india", true) -> "in-unlock.update.intl"
                region.equals("RU", true) || region.contains("russia", true) -> "ru-unlock.update.intl"
                region in EUROPE_REGION_CODES -> "eu-unlock.update.intl"
                else -> "unlock.update.intl"
            }
        }
        return "https://$subdomain.miui.com"
    }

    private fun fallbackRegionConfig(region: String): String = when {
        region.equals("CN", true) || region.contains("china", true) -> "China"
        region.equals("IN", true) || region.contains("india", true) -> "India"
        region.equals("RU", true) || region.contains("russia", true) -> "Russia"
        region in EUROPE_REGION_CODES -> "Europe"
        else -> "Singapore"
    }

    private fun getSsecurityAndServiceToken(client: OkHttpClient): Pair<String, String> {
        val request = Request.Builder()
            .url(url("/pass/serviceLogin?sid=unlockApi"))
            .header("User-Agent", USER_AGENT)
            .build()

        client.newCall(request).execute().use { response ->
            if (response.code == 401 || response.code == 403) {
                throw XiaomiAuthException("Xiaomi rejected the saved account session.")
            }
            if (!response.isSuccessful) {
                throw IOException("Xiaomi account returned HTTP ${response.code}")
            }

            val pragma = (response.priorResponse ?: response).header("extension-pragma")
                ?: response.firstHeader("extension-pragma")
                ?: throw XiaomiAuthException("Xiaomi did not return an account session token.")
            val pragmaJson = parseJsonLoose(pragma)
            val ssecurity = pragmaJson.optString("ssecurity").ifBlank {
                throw XiaomiAuthException("Xiaomi session token is missing ssecurity.")
            }

            val serviceToken = collectCookies(response)
            if (serviceToken.isBlank()) {
                throw XiaomiAuthException("Xiaomi did not return a service token.")
            }

            return ssecurity to serviceToken
        }
    }

    /**
     * Collects cookies from the whole redirect chain, oldest response first.
     * Later responses override earlier values, and an explicit `=null` removes
     * a cookie set by an earlier hop.
     */
    private fun collectCookies(response: Response): String {
        val chain = generateSequence(response) { it.priorResponse }
            .toList()
            .asReversed()
        val cookies = linkedMapOf<String, String>()

        for (item in chain) {
            for (header in item.headers("Set-Cookie")) {
                val pair = header.substringBefore(";").trim()
                if (pair.isEmpty() || !pair.contains("=")) continue

                val name = pair.substringBefore("=").trim()
                val value = pair.substringAfter("=", "").trim()
                if (name.isEmpty()) continue

                if (value.isBlank() || value.equals("null", ignoreCase = true)) {
                    cookies.remove(name)
                } else {
                    cookies[name] = "$name=$value"
                }
            }
        }

        return cookies.values.joinToString(";")
    }

    private fun url(path: String): String = baseUrl.trimEnd('/') + path

    private fun getBody(client: OkHttpClient, url: String): String {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .build()

        client.newCall(request).execute().use { response ->
            if (response.code == 401 || response.code == 403) {
                throw XiaomiAuthException("Xiaomi rejected the saved account session.")
            }
            if (!response.isSuccessful) {
                throw IOException("Xiaomi account returned HTTP ${response.code}")
            }
            val body = response.body?.string()
                ?: throw IOException("Xiaomi account returned an empty response.")
            return stripPrefix(body)
        }
    }

    private fun stripPrefix(body: String): String {
        val marker = "&&&START&&&"
        return if (body.startsWith(marker)) body.substring(marker.length) else body
    }

    private fun parseJsonLoose(value: String): JSONObject {
        return try {
            JSONObject(value)
        } catch (_: Exception) {
            JSONObject(URLDecoder.decode(value, "UTF-8"))
        }
    }

    private fun Response.firstHeader(name: String): String? {
        var current: Response? = this
        while (current != null) {
            current.header(name)?.let { return it }
            current = current.priorResponse
        }
        return null
    }

    private class MemoryCookieJar(session: XiaomiSession) : CookieJar {

        private val initialCookies = mapOf(
            "passToken" to session.passToken,
            "deviceId" to session.deviceId,
            "userId" to session.userId
        ).filterValues { it.isNotBlank() }

        private val cookieStore = mutableMapOf<String, List<Cookie>>()

        override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
            val merged = cookieStore[url.host]
                .orEmpty()
                .associateBy { it.name }
                .toMutableMap()
            cookies.forEach { cookie -> merged[cookie.name] = cookie }
            cookieStore[url.host] = merged.values.toList()
        }

        override fun loadForRequest(url: HttpUrl): List<Cookie> {
            val merged = linkedMapOf<String, Cookie>()
            initialCookies.forEach { (name, value) ->
                merged[name] = Cookie.Builder()
                    .name(name)
                    .value(value)
                    .domain(url.host)
                    .build()
            }
            cookieStore.values
                .flatten()
                .filter { cookie -> cookie.matches(url) }
                .forEach { cookie -> merged[cookie.name] = cookie }
            return merged.values.toList()
        }
    }

    companion object {
        private const val USER_AGENT = "dev.bootloaderstudio.app"
        private const val DEFAULT_BASE_URL = "https://account.xiaomi.com"
        private val EUROPE_REGION_CODES = setOf(
            "AT", "BE", "BG", "HR", "CY", "CZ", "DK", "EE", "FI", "FR",
            "DE", "GR", "HU", "IE", "IT", "LV", "LT", "LU", "MT", "NL",
            "PL", "PT", "RO", "SK", "SI", "ES", "SE", "GB", "UK", "EU"
        )
    }
}
