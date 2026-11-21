package org.polyfrost.polyplus.client.launcher

import org.apache.logging.log4j.LogManager
import java.lang.reflect.InvocationTargetException
import java.nio.file.Path

/**
 * OneClient's launcher, when it started the game
 *
 * Under OneClient's token broker the game only ever holds a fake access token: the launcher swaps in the real one
 * on the way to Mojang. The account file Poly+ would otherwise read holds real tokens, so accounts come from the
 * launcher by name instead, and switching or adding one goes through it. In OneClient's sandbox the game can't run
 * programs either, so packs go to the trash and show in the file manager through the launcher too
 *
 * The API lives on the boot class path, put there by OneClient's agent, and is only reached by reflection
 */
object OneClient {
    private val LOGGER = LogManager.getLogger("PolyPlus/OneClient")
    private const val API = "org.polyfrost.oneclient.agent.api.OneClientSandbox"

    /** Never expires: the broker always holds a fresh token, so Poly+ never needs to refresh */
    private const val NEVER = "2999-12-31T00:00:00Z"

    private val api: Class<*>? by lazy {
        runCatching { Class.forName(API, true, null) }.getOrNull()
    }

    private fun call(name: String, types: Array<Class<*>> = emptyArray(), vararg args: Any?): Any? {
        val api = api ?: error("OneClient's API isn't available")
        return try {
            api.getMethod(name, *types).invoke(null, *args)
        } catch (e: InvocationTargetException) {
            throw e.targetException
        }
    }

    /** Whether OneClient's token broker serves this game */
    val brokered: Boolean by lazy { api != null && runCatching { call("isBrokered") == true }.getOrDefault(false) }

    /** Whether the game runs in OneClient's sandbox */
    val sandboxed: Boolean by lazy { api != null && runCatching { call("isSandboxed") == true }.getOrDefault(false) }

    /** The fake token the game was started with; the only one it ever has */
    private fun fakeToken(): String {
        val words = System.getProperty("sun.java.command", "").split(' ')
        val at = words.indexOf("--accessToken")
        return if (at >= 0 && at + 1 < words.size) words[at + 1] else ""
    }

    /** The user's OneClient accounts, each with the game's fake token */
    fun credentials(): LauncherAccountStore.CredentialsStore = runCatching {
        @Suppress("UNCHECKED_CAST")
        val accounts = call("accounts") as Array<Array<String>>
        val active = call("activeAccount") as String
        val token = fakeToken()
        val users = LinkedHashMap<String, LauncherAccountStore.StoredAccount>()
        for ((id, name) in accounts) {
            users[id] = LauncherAccountStore.StoredAccount(id, name, token, "", NEVER, "microsoft")
        }
        LauncherAccountStore.CredentialsStore(users, active)
    }.getOrElse {
        LOGGER.warn("Could not get the accounts from OneClient", it)
        LauncherAccountStore.CredentialsStore()
    }

    /** Asks OneClient to play as `id` from now on; false if it refused */
    fun switchAccount(id: String): Boolean = runCatching {
        call("switchAccount", arrayOf(String::class.java), id)
    }.onFailure { LOGGER.warn("OneClient did not switch to account {}", id, it) }.isSuccess

    /** Brings up OneClient so the user can sign in there */
    fun addAccount() {
        runCatching { call("addAccount") }.onFailure { LOGGER.warn("Could not reach OneClient to add an account", it) }
    }

    fun trash(path: Path): Boolean = runCatching {
        call("trash", arrayOf(String::class.java), path.toAbsolutePath().toString()) == true
    }.getOrDefault(false)

    fun reveal(path: Path): Boolean = runCatching {
        call("reveal", arrayOf(String::class.java), path.toAbsolutePath().toString()) == true
    }.getOrDefault(false)
}
