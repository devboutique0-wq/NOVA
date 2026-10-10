package com.nova.assistant

import android.content.Context
import android.util.Base64
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * v16 PUBLISH A SITE: new public repo -> index.html -> GitHub Pages, with the owner's own token (SecureStore slot "github").
 * Called only after the owner tapped HAAN in a native box. The token goes in a request header only, never in a URL or a log.
 * Evidence level: written + statically checked. Not compiled here, not phone tested; GitHub's endpoints are written from the
 * public REST docs (users/repos/contents/pages), nothing was reachable from the build sandbox.
 */
object GitHubPublish {
    class Out(val ok: Boolean, val url: String = "", val msg: String = "")

    const val SLOT = "github"
    private const val API = "https://api.github.com"

    private fun call(token: String, method: String, path: String, body: String?): Pair<Int, String> {
        try {
            val c = URL(API + path).openConnection() as HttpURLConnection
            c.requestMethod = method
            c.connectTimeout = 15000
            c.readTimeout = 40000
            c.instanceFollowRedirects = false
            c.setRequestProperty("Authorization", "Bearer " + token)
            c.setRequestProperty("Accept", "application/vnd.github+json")
            c.setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
            c.setRequestProperty("User-Agent", "NOVA-assistant")
            if (body != null) {
                c.doOutput = true
                c.setRequestProperty("Content-Type", "application/json")
                c.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            val code = c.responseCode
            val stream = if (code in 200..299) c.inputStream else c.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() } ?: ""
            c.disconnect()
            return Pair(code, text)
        } catch (e: java.net.UnknownHostException) {
            return Pair(598, "")
        } catch (e: Exception) {
            return Pair(599, "")
        }
    }

    private fun failFor(code: Int): String = when (code) {
        401 -> "GitHub token galat ya expire ho gaya. Settings mein naya token daalo"
        403, 404 -> "GitHub ne mana kiya. Token mein repo ka permission (scope: repo) chahiye"
        422 -> "Repo ban nahi paya (naam pehle se ho sakta hai). Dobara try karo"
        598 -> "Internet nahi mil raha"
        else -> "Abhi publish nahi ho paya. Thodi der baad dobara try karo"
    }

    fun run(ctx: Context, subject: String, html: String): Out {
        val token = SecureStore.getSlot(ctx, SLOT)
        if (token.isEmpty()) return Out(false, "", "GitHub token nahi mila. Settings mein daalo")
        if (html.length < 200 || html.length > SiteGen.MAX_HTML) return Out(false, "", "Website file theek nahi hai")
        val who = call(token, "GET", "/user", null)
        if (who.first !in 200..299) return Out(false, "", failFor(who.first))
        val login = try { JSONObject(who.second).optString("login") } catch (e: Exception) { "" }
        if (login.isEmpty() || !login.all { it.isLetterOrDigit() || it == '-' }) return Out(false, "", failFor(599))
        val repo = "nova-" + SiteGen.slug(subject).replace('_', '-') + "-" + (System.currentTimeMillis() % 10000)
        val mk = call(token, "POST", "/user/repos", JSONObject().put("name", repo).put("description", "Made with NOVA").put("private", false).toString())
        if (mk.first !in 200..299) return Out(false, "", failFor(mk.first))
        val b64 = Base64.encodeToString(html.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
        val put = call(token, "PUT", "/repos/$login/$repo/contents/index.html",
            JSONObject().put("message", "NOVA: add website").put("content", b64).put("branch", "main").toString())
        if (put.first !in 200..299) return Out(false, "", failFor(put.first))
        val pg = call(token, "POST", "/repos/$login/$repo/pages",
            JSONObject().put("source", JSONObject().put("branch", "main").put("path", "/")).toString())
        if (pg.first !in 200..299 && pg.first != 409) return Out(false, "", failFor(pg.first))
        return Out(true, "https://$login.github.io/$repo/", "")
    }
}
