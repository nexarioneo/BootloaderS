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
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.Base64

class XiaomiAuthRepository(
    private val baseClient: OkHttpClient,
    private val baseUrl: String = DEFAULT_BASE_URL
) : XiaomiAuthService {

    override suspend fun resolveAccount(session: XiaomiSession): XiaomiAccount = withContext(Dispatchers.IO) {
        val cookieJar = MemoryCookieJar(session)
        val client = baseClient.newBuilder()
            .cookieJar(cookieJar)
            .build()

        val region = getRegion(client)
        val regionConfig = getRegionConfig(client, region)
        val configuredHost = hostFor(regionConfig, region)
        val credentials = getSsecurityAndServiceToken(
            client = client,
            session = session,
            cookieJar = cookieJar
        )

        XiaomiAccount(
            userId = session.userId,
            region = region,
            // The service token is scoped to the host that issued it. The
            // region-config guess is only a fallback; prefer the STS host so
            // an intl/CN mismatch cannot send a valid token to the wrong
            // unlock server.
            host = credentials.host.ifBlank { configuredHost },
            ssecurity = credentials.ssecurity,
            serviceToken = credentials.serviceToken,
            cookies = cookieJar.snapshot()
        )
    }

    private fun getRegion(client: OkHttpClient): String {
        val body = getBody(
            client,
            url("/pass/user/login/region")
        )
        if (body.trimStart().startsWith("<")) {
            // OkHttp followed a redirect to Xiaomi's sign-in page, which
            // answers with HTTP 200 and HTML. That means the account session
            // was rejected, not that the response was malformed. Other HTML
            // (captcha, gateway error) is recoverable and must not clear the
            // saved login.
            if (looksLikeSignInPage(body)) {
                throw XiaomiAuthException(
                    "Xiaomi did not recognize the saved account session.",
                    rejected = true
                )
            }
            throw IOException("Xiaomi returned an HTML page instead of the account region.")
        }

        val json = try {
            JSONObject(body)
        } catch (error: Exception) {
            throw IOException("Xiaomi returned an invalid account region response.", error)
        }

        val code = json.optInt("code", 0)
        if (code != 0) {
            val description = json.optString("description")
                .ifBlank { json.optString("desc") }
                .ifBlank { "Xiaomi did not recognize the saved account session." }
            throw XiaomiAuthException("$description (code $code).")
        }

        val region = json.optJSONObject("data")?.optString("region").orEmpty()
        if (region.isBlank()) {
            throw IOException("Xiaomi returned an account region response without a region.")
        }
        return region
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

    /**
     * Resolves `ssecurity` and the unlock-domain `serviceToken`.
     *
     * Modern Xiaomi accounts no longer accept the implicit browser redirect
     * used by older clients. The supported exchange is:
     *
     * 1. `GET /pass/serviceLogin?sid=unlockApi&_json=true` with the account
     *    cookies. The JSON answers `ssecurity`, `nonce`, `cUserId`, and a
     *    `location` pointing at the STS endpoint.
     * 2. `GET <location>&clientSign=...`, where `clientSign` is the Base64
     *    SHA-1 of `nonce=<nonce>&<ssecurity>`. The STS response sets the
     *    unlock-domain `serviceToken` cookie (plus `unlockApi_slh`/`_ph`).
     *
     * Without the `_json=true` + `clientSign` handshake the STS endpoint can
     * answer HTTP 200 without ever setting `serviceToken`, which the app used
     * to report as an expired session.
     *
     * Xiaomi may also answer with `securityStatus != 0`, an empty `ssecurity`
     * and a `notificationUrl`. That means the account is logged in but the
     * unlock service still needs an identity check (SMS or e-mail). The
     * notification URL must be opened in the same cookie jar before the
     * exchange can succeed; reporting it as an expired session is wrong.
     */
    private fun getSsecurityAndServiceToken(
        client: OkHttpClient,
        session: XiaomiSession,
        cookieJar: MemoryCookieJar
    ): StsCredentials {
        val userId = session.userId
        val loginJson = fetchServiceLoginJson(client)

        val jsonSsecurity = jsonString(loginJson, "ssecurity")
        val psecurity = jsonString(loginJson, "psecurity")
        val securityStatus = loginJson.optInt("securityStatus", 0)
        val notificationUrl = jsonString(loginJson, "notificationUrl")

        // securityStatus is non-zero while unlockApi still needs identity
        // verification. A notification URL without ssecurity is the same
        // gate, even if the status field is absent. Check this before any
        // error code: Xiaomi can answer code 70016 while still exposing an
        // identity URL that resolves the session.
        if (notificationUrl.isNotBlank() &&
            (securityStatus != 0 || jsonSsecurity.isBlank())
        ) {
            throw XiaomiIdentityRequiredException(
                notificationUrl = resolveNotificationUrl(notificationUrl),
                securityStatus = securityStatus
            )
        }

        val code = loginJson.optInt("code", 0)
        if (loginJson.has("code") && !loginJson.isNull("code") && code != 0) {
            val description = jsonString(loginJson, "description")
                .ifBlank { jsonString(loginJson, "desc") }
                .ifBlank { "Xiaomi rejected the saved account session." }
            // A code on this endpoint can be a resumable identity/login
            // state. Keep the session; only HTTP 401/403 or a sign-in HTML
            // page is treated as a terminal rejection.
            throw XiaomiAuthException("$description (code $code).")
        }

        var ssecurity = jsonSsecurity
        if (ssecurity.isBlank()) {
            // Xiaomi answers a web-flavoured session (pass_ua=web) with
            // `psecurity` and an already signed STS location instead of the
            // native `ssecurity` key. The native redirect endpoint still
            // returns ssecurity in its extension-pragma header, so retry
            // that proven flow before reporting a protocol failure.
            fetchLegacyCredentials(client, userId, cookieJar)?.let { return it }
            throw XiaomiAuthException(
                missingSsecurityMessage(loginJson, securityStatus, psecurity)
            )
        }

        val nonce = jsonString(loginJson, "nonce")
        val cUserId = jsonString(loginJson, "cUserId")
            .ifBlank { session.cookies["cUserId"].orEmpty() }

        var location = jsonString(loginJson, "location")
        if (location.isBlank()) {
            throw XiaomiAuthException("Xiaomi session token is missing the STS location.")
        }
        if (!location.startsWith("https://") && !location.startsWith("http://")) {
            location = url(location)
        }

        var stsUrl = location
        val alreadySigned =
            "_ssign=" in location || "clientSign=" in location
        if (nonce.isNotBlank() && !alreadySigned) {
            // The STS endpoint only trusts a location whose clientSign was
            // derived from the nonce and ssecurity issued by serviceLogin.
            // Keep clientSign percent-encoded (`+`, `/`, `=` included) as the
            // server compares the encoded signature. A location that already
            // carries `_ssign` was signed by Xiaomi for the browser/native
            // redirect flow and must be used as-is.
            val clientSign = clientSign(nonce, ssecurity)
            val separator = if ("?" in location) "&" else "?"
            val encryption = if ("_userIdNeedEncrypt=" in location) {
                ""
            } else {
                "&_userIdNeedEncrypt=true"
            }
            stsUrl = "$location${separator}clientSign=$clientSign$encryption"
        }

        val stsRequest = Request.Builder()
            .url(stsUrl)
            // The STS endpoint is a browser endpoint. The reference flow
            // reports that application-only user agents (for example
            // "XiaomiPCSuite") are sometimes answered with HTTP 401 even
            // though the signed URL and cookies are correct.
            .header("User-Agent", BROWSER_USER_AGENT)
            .header("Referer", "https://account.xiaomi.com/")
            .build()

        // The STS exchange is signed by `clientSign`. The native reference
        // client still sends the passport cookie triplet here, so use the
        // normal client. MemoryCookieJar drops the web-only `pass_ua` and
        // `passInfo` markers plus stale unlock tokens, which otherwise make
        // Xiaomi treat the request as a browser and skip the fresh token.
        client.newCall(stsRequest).execute().use { response ->
            return stsCredentialsFromResponse(
                response = response,
                userId = userId,
                cUserId = cUserId,
                ssecurity = ssecurity,
                cookieJar = cookieJar
            )
        }
    }

    /**
     * Performs the legacy `serviceLogin` redirect flow used by the native Mi
     * Unlock client. It follows the pre-signed STS location and returns the
     * `ssecurity` key from the `extension-pragma` response header.
     *
     * Returns null when the response does not contain both a signing key and
     * an unlock service token, so the caller can report a protocol failure
     * without discarding the saved session.
     */
    private fun fetchLegacyCredentials(
        client: OkHttpClient,
        userId: String,
        cookieJar: MemoryCookieJar
    ): StsCredentials? {
        val request = Request.Builder()
            .url(url("/pass/serviceLogin?sid=unlockApi"))
            .header("User-Agent", USER_AGENT)
            .build()

        client.newCall(request).execute().use { response ->
            if (response.code == 401 || response.code == 403) {
                throw XiaomiAuthException(
                    "Xiaomi rejected the saved account session (HTTP ${response.code}).",
                    rejected = true
                )
            }
            if (!response.isSuccessful) return null

            val ssecurity = extensionPragmaSsecurity(response)
            val cookies = collectServiceCookies(response)
            val headerToken = collectResponseHeader(
                response,
                "unlockApi_serviceToken",
                "serviceToken"
            )
            if (ssecurity.isBlank() || ("serviceToken" !in cookies && headerToken.isBlank())) {
                return null
            }

            val cUserId = collectResponseHeader(response, "cUserId")
                .ifBlank { cookieJar.snapshot()["cUserId"].orEmpty() }
            return stsCredentialsFromResponse(
                response = response,
                userId = userId,
                cUserId = cUserId,
                ssecurity = ssecurity,
                cookieJar = cookieJar
            )
        }
    }

    private fun stsCredentialsFromResponse(
        response: Response,
        userId: String,
        cUserId: String,
        ssecurity: String,
        cookieJar: MemoryCookieJar
    ): StsCredentials {
        if (response.code == 401 || response.code == 403) {
            throw XiaomiAuthException("Xiaomi rejected the STS request (HTTP ${response.code}).")
        }
        if (!response.isSuccessful) {
            throw IOException("Xiaomi STS returned HTTP ${response.code}.")
        }
        response.body?.close()

        val cookies = collectServiceCookies(response)
        // Some STS deployments return the service ticket as a response
        // header instead of a Set-Cookie entry. The reference client reads
        // these headers first, so a header value wins over a stale
        // Set-Cookie entry for the same name.
        val headerCookies = linkedMapOf(
            "serviceToken" to collectResponseHeader(
                response,
                "unlockApi_serviceToken",
                "serviceToken"
            ),
            "unlockApi_slh" to collectResponseHeader(response, "unlockApi_slh"),
            "unlockApi_ph" to collectResponseHeader(response, "unlockApi_ph"),
            "cUserId" to collectResponseHeader(response, "cUserId")
        )
        headerCookies.forEach { (name, value) ->
            if (value.isNotBlank()) {
                cookies[name] = "$name=$value"
            }
        }
        if (cUserId.isNotBlank() && "cUserId" !in cookies) {
            cookies["cUserId"] = "cUserId=$cUserId"
        }
        if (userId.isNotBlank() && "userId" !in cookies) {
            cookies["userId"] = "userId=$userId"
        }
        if ("serviceToken" !in cookies) {
            throw XiaomiAuthException(
                "Xiaomi STS did not return an unlock service token."
            )
        }

        // Header tokens never pass through CookieJar.saveFromResponse, so
        // persist them explicitly. Use the final redirect URL as their
        // domain; that is where the STS server issued them.
        val finalUrl = response.request.url
        val extraCookies = buildList {
            headerCookies.forEach { (name, value) ->
                cookieFor(name, value, finalUrl)?.let(::add)
            }
            if (cUserId.isNotBlank() && "cUserId" !in cookies) {
                cookieFor("cUserId", cUserId, finalUrl)?.let(::add)
            }
            if (userId.isNotBlank() && "userId" !in cookies) {
                cookieFor("userId", userId, finalUrl)?.let(::add)
            }
        }
        if (extraCookies.isNotEmpty()) {
            cookieJar.saveFromResponse(finalUrl, extraCookies)
        }

        // The final STS hop is the token's home host. Use it for the
        // unlock API instead of the region-config guess when it is a
        // Xiaomi host.
        val finalHost = response.request.url.host
        val apiHost = finalHost
            .takeIf {
                !it.equals("account.xiaomi.com", ignoreCase = true) &&
                    (it.endsWith(".miui.com", ignoreCase = true) ||
                        it.endsWith(".xiaomi.com", ignoreCase = true))
            }
            ?.let { "https://$it" }
            .orEmpty()

        return StsCredentials(
            ssecurity = ssecurity.ifBlank { extensionPragmaSsecurity(response) },
            serviceToken = cookies.values.joinToString("; "),
            host = apiHost
        )
    }

    private fun extensionPragmaSsecurity(response: Response): String {
        val raw = collectResponseHeader(response, "extension-pragma")
        if (raw.isBlank()) return ""
        val json = runCatching { JSONObject(raw) }.getOrNull()
            ?: runCatching { JSONObject(URLDecoder.decode(raw, "UTF-8")) }.getOrNull()
            ?: return ""
        return jsonString(json, "ssecurity")
    }

    private data class StsCredentials(
        val ssecurity: String,
        val serviceToken: String,
        val host: String
    )

    private fun cookieFor(name: String, value: String, url: HttpUrl): Cookie? {
        if (value.isBlank()) return null
        return runCatching {
            Cookie.Builder()
                .name(name)
                .value(value)
                .domain(url.host)
                .path("/")
                .build()
        }.getOrNull()
    }

    private fun fetchServiceLoginJson(client: OkHttpClient): JSONObject {
        val request = Request.Builder()
            .url(
                url(
                    "/pass/serviceLogin?sid=unlockApi&_json=true&passive=true" +
                        "&hidden=false&_locale=en"
                )
            )
            .header("User-Agent", USER_AGENT)
            .header("Accept", "application/json;charset=UTF-8")
            .build()

        client.newCall(request).execute().use { response ->
            if (response.code == 401 || response.code == 403) {
                throw XiaomiAuthException(
                    "Xiaomi rejected the saved account session (HTTP ${response.code}).",
                    rejected = true
                )
            }
            if (!response.isSuccessful) {
                throw IOException("Xiaomi account returned HTTP ${response.code}.")
            }

            val raw = response.body?.string()
                ?: throw IOException("Xiaomi account returned an empty response.")
            val body = stripPrefix(raw)
            if (body.trimStart().startsWith("<")) {
                if (looksLikeSignInPage(body)) {
                    throw XiaomiAuthException(
                        "Xiaomi did not recognize the saved account session.",
                        rejected = true
                    )
                }
                throw IOException("Xiaomi returned an HTML page instead of a login response.")
            }
            return try {
                val json = JSONObject(body)
                // Some passport deployments still return ssecurity/nonce in
                // the legacy extension-pragma header instead of the JSON
                // body. Merge only fields the JSON did not provide.
                mergeExtensionPragma(response, json)
                // Older serviceLogin responses put cUserId in a response
                // header instead of the JSON body. Preserve it so the unlock
                // cookie string is complete.
                if (jsonString(json, "cUserId").isBlank()) {
                    val headerUserId = collectResponseHeader(response, "cUserId")
                    if (headerUserId.isNotBlank()) {
                        json.put("cUserId", headerUserId)
                    }
                }
                json
            } catch (error: Exception) {
                throw IOException("Xiaomi returned an invalid login response.", error)
            }
        }
    }

    private fun mergeExtensionPragma(response: Response, json: JSONObject) {
        val raw = collectResponseHeader(response, "extension-pragma")
        if (raw.isBlank()) return
        val parsed = runCatching { JSONObject(raw) }.getOrNull()
            ?: runCatching { JSONObject(URLDecoder.decode(raw, "UTF-8")) }.getOrNull()
            ?: return
        val keys = parsed.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            val current = json.optString(key).trim()
            if (current.isBlank() || current.equals("null", ignoreCase = true)) {
                json.put(key, parsed.get(key))
            }
        }
    }

    /**
     * Reads a JSON string field, treating JSON `null` and the literal string
     * `"null"` as absent. Xiaomi answers with either depending on the
     * endpoint, and neither should be used as a token.
     */
    private fun jsonString(json: JSONObject, key: String): String {
        return json.optString(key).trim()
            .takeUnless { it.equals("null", ignoreCase = true) }
            .orEmpty()
    }

    /**
     * `notificationUrl` is usually absolute, but Xiaomi occasionally sends a
     * path relative to the account host. Make it safe to load in the WebView.
     */
    private fun resolveNotificationUrl(raw: String): String {
        return when {
            raw.startsWith("https://") || raw.startsWith("http://") -> raw
            raw.startsWith("/") -> url(raw)
            else -> baseUrl.trimEnd('/') + "/" + raw
        }
    }

    private fun missingSsecurityMessage(
        json: JSONObject,
        securityStatus: Int,
        psecurity: String = ""
    ): String {
        val result = jsonString(json, "result")
        val description = jsonString(json, "description")
            .ifBlank { jsonString(json, "desc") }
        val notification = if (jsonString(json, "notificationUrl").isNotBlank()) {
            "present"
        } else {
            "absent"
        }
        val detail = buildList {
            if (securityStatus != 0) add("securityStatus=$securityStatus")
            if (result.isNotBlank()) add("result=$result")
            if (description.isNotBlank()) add("description=$description")
            if (psecurity.isNotBlank()) add("psecurity=present")
            add("notificationUrl=$notification")
        }.joinToString(", ")
        return "Xiaomi session token is missing ssecurity ($detail)."
    }

    private fun clientSign(nonce: String, ssecurity: String): String {
        val digest = MessageDigest.getInstance("SHA-1")
            .digest("nonce=$nonce&$ssecurity".toByteArray(Charsets.UTF_8))
        return URLEncoder.encode(
            Base64.getEncoder().encodeToString(digest),
            "UTF-8"
        )
    }

    /**
     * Collects cookies from every hop of the STS redirect chain, oldest first.
     * Later values win and deletion sentinels (`=null`, `=EXPIRED`, or
     * `Max-Age=0`) remove only the cookie identity they refer to. Deletions
     * for another domain (for example the web `serviceToken` on
     * `account.xiaomi.com`) must not erase the unlock-domain token.
     */
    private fun collectServiceCookies(response: Response): MutableMap<String, String> {
        val chain = generateSequence(response) { it.priorResponse }
            .toList()
            .asReversed()
        val cookies = linkedMapOf<String, String>()

        for (hop in chain) {
            val requestHost = hop.request.url.host.lowercase()
            for (header in hop.headers("Set-Cookie")) {
                val pair = header.substringBefore(";").trim()
                if (pair.isEmpty() || !pair.contains("=")) continue

                val name = pair.substringBefore("=").trim()
                val value = pair.substringAfter("=", "").trim()
                if (name.isEmpty()) continue

                val domain = header.split(";")
                    .map { it.trim() }
                    .firstOrNull { it.startsWith("Domain=", ignoreCase = true) }
                    ?.substringAfter("=")
                    ?.trim()
                    ?.removePrefix(".")
                    ?.lowercase()
                    ?: requestHost
                val key = "$name|$domain"

                if (value.isEmpty() ||
                    value.equals("null", ignoreCase = true) ||
                    value.equals("EXPIRED", ignoreCase = true) ||
                    maxAgeIsZero(header)
                ) {
                    cookies.remove(key)
                } else {
                    cookies[key] = "$name=$value"
                }
            }
        }

        // The unlock request only needs one value per cookie name. The chain
        // is ordered, so the last surviving value is the freshest one.
        val byName = linkedMapOf<String, String>()
        cookies.forEach { (key, pair) -> byName[key.substringBefore("|")] = pair }
        return byName
    }

    private fun maxAgeIsZero(header: String): Boolean {
        return header.split(";").any { part ->
            val trimmed = part.trim()
            trimmed.startsWith("Max-Age=", ignoreCase = true) &&
                trimmed.substringAfter("=").trim() == "0"
        }
    }

    /**
     * Reads a response header from the final hop and, if absent, from earlier
     * redirect hops. The newest non-empty value wins.
     */
    private fun collectResponseHeader(response: Response, vararg names: String): String {
        val chain = generateSequence(response) { it.priorResponse }
            .toList()
            .asReversed()
        var value = ""
        for (hop in chain) {
            // Prefer the first name in `names` on each hop (for example
            // `unlockApi_serviceToken` over the generic `serviceToken`).
            val candidate = names.firstNotNullOfOrNull { name ->
                hop.header(name)?.trim()
                    ?.takeIf { it.isNotEmpty() && !it.equals("null", ignoreCase = true) }
            }
            if (candidate != null) {
                value = candidate
            }
        }
        return value
    }

    private fun url(path: String): String = baseUrl.trimEnd('/') + path

    private fun getBody(client: OkHttpClient, url: String): String {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .build()

        client.newCall(request).execute().use { response ->
            if (response.code == 401 || response.code == 403) {
                throw XiaomiAuthException(
                    "Xiaomi rejected the saved account session.",
                    rejected = true
                )
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

    /**
     * Distinguishes Xiaomi's sign-in HTML from unrelated HTML such as a
     * captcha or a gateway error. Only the former means the account session
     * was actually rejected.
     */
    private fun looksLikeSignInPage(body: String): Boolean {
        val lower = body.lowercase()
        return lower.contains("servicelogin") ||
            lower.contains("passport") ||
            lower.contains("login") ||
            lower.contains("sign in") ||
            lower.contains("登录")
    }

    private class MemoryCookieJar(session: XiaomiSession) : CookieJar {

        private val initialCookies = buildMap {
            session.cookies.forEach { (name, value) ->
                if (name.isNotBlank() &&
                    value.isNotBlank() &&
                    !value.equals("null", ignoreCase = true) &&
                    !value.equals("EXPIRED", ignoreCase = true)
                ) {
                    put(name, value)
                }
            }
            // The explicit triplet is authoritative over a stale copy in the
            // generic cookie map.
            put("passToken", session.passToken)
            put("deviceId", session.deviceId)
            put("userId", session.userId)
        }.filterValues { it.isNotBlank() }

        private val cookieStore = mutableMapOf<String, List<Cookie>>()

        /**
         * Returns every cookie currently held by this jar. The repository
         * persists this snapshot with the session so rotated passTokens,
         * cUserId, and unlockApi_* cookies survive account refreshes.
         */
        fun snapshot(): Map<String, String> {
            val now = System.currentTimeMillis()
            val result = linkedMapOf<String, String>()
            initialCookies.forEach { (name, value) -> result[name] = value }
            cookieStore.values
                .flatten()
                .filter { it.expiresAt > now }
                .forEach { cookie -> result[cookie.name] = cookie.value }
            return result
        }

        override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
            val merged = cookieStore[url.host]
                .orEmpty()
                .associateBy { it.name }
                .toMutableMap()
            val now = System.currentTimeMillis()
            cookies.forEach { cookie ->
                // `Max-Age=0` is a deletion. OkHttp keeps such a cookie with
                // expiresAt = Long.MIN_VALUE, so storing it verbatim would
                // replay a sentinel value on the next request.
                if (cookie.expiresAt <= now) {
                    merged.remove(cookie.name)
                } else {
                    merged[cookie.name] = cookie
                }
            }
            cookieStore[url.host] = merged.values.toList()
        }

        override fun loadForRequest(url: HttpUrl): List<Cookie> {
            val now = System.currentTimeMillis()
            // The passport API must look like the native client, not like the
            // WebView that produced the login. In particular `pass_ua=web`
            // makes serviceLogin answer with a browser-shaped `psecurity` +
            // pre-signed location and no `ssecurity`. Sending only the
            // passport identity cookies keeps the native JSON/redirect shape.
            // The path check keeps this behavior testable on a local
            // MockWebServer, whose host is not xiaomi.com.
            val nativeAuthRequest = isNativeAuthRequest(url)
            fun isAllowed(name: String): Boolean =
                !nativeAuthRequest ||
                    name.lowercase() in NATIVE_AUTH_COOKIE_NAMES

            val merged = linkedMapOf<String, Cookie>()
            initialCookies.forEach { (name, value) ->
                if (!isAllowed(name)) return@forEach
                merged[name] = Cookie.Builder()
                    .name(name)
                    .value(value)
                    .domain(url.host)
                    .build()
            }
            cookieStore.values
                .flatten()
                .filter { cookie ->
                    cookie.expiresAt > now &&
                        cookie.matches(url) &&
                        isAllowed(cookie.name)
                }
                .forEach { cookie -> merged[cookie.name] = cookie }
            return merged.values.toList()
        }

        private fun isNativeAuthRequest(url: HttpUrl): Boolean {
            val host = url.host.lowercase()
            if (host == "account.xiaomi.com" ||
                host.endsWith(".xiaomi.com") ||
                host.endsWith(".miui.com")
            ) {
                return true
            }
            val path = url.encodedPath
            return path.startsWith("/pass/") ||
                path.startsWith("/pass2/") ||
                path.startsWith("/sts") ||
                path.startsWith("/fe/")
        }
    }

    companion object {
        private const val USER_AGENT = "dev.bootloaderstudio.app"
        private const val BROWSER_USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36"
        private const val DEFAULT_BASE_URL = "https://account.xiaomi.com"

        /**
         * Cookies the native passport exchange actually consumes. `pass_ua`,
         * `passInfo`, analytics, theme, and the web serviceToken are
         * deliberately absent: replaying them makes Xiaomi treat the app as
         * a browser session.
         */
        private val NATIVE_AUTH_COOKIE_NAMES = setOf(
            "passtoken",
            "deviceid",
            "userid",
            "cuserid",
            "passport_slh",
            "passport_ph"
        )

        private val EUROPE_REGION_CODES = setOf(
            "AT", "BE", "BG", "HR", "CY", "CZ", "DK", "EE", "FI", "FR",
            "DE", "GR", "HU", "IE", "IT", "LV", "LT", "LU", "MT", "NL",
            "PL", "PT", "RO", "SK", "SI", "ES", "SE", "GB", "UK", "EU"
        )
    }
}
