package dev.blamspot.jcode.ext.sql

/** How the client reaches the server. */
internal enum class Reach(val id: String, val label: String) {
    /** Straight at the server, from inside JCode's runtime. */
    Direct("off", "Direct"),

    /** Through an SSH port-forward, for a server only its own network can see. */
    Tunnel("tunnel", "SSH tunnel"),

    /** sqlcmd runs on the far side of the connection and only its output comes back. */
    Remote("ssh", "Run over SSH"),
    ;

    companion object {
        fun of(id: String): Reach = entries.firstOrNull { it.id == id } ?: Direct
    }
}

/** Which secret gets the SSH connection open. */
internal enum class SshAuth(val id: String) {
    Key("key"),
    Password("password"),
    ;

    companion object {
        fun of(id: String): SshAuth = entries.firstOrNull { it.id == id } ?: Key
    }
}

/**
 * Everything needed to reach one server, as the app's Settings hold it.
 *
 * Read here rather than edited: the server, login and password are JCode settings, and a second
 * place to type them would be a second place for them to disagree.
 */
internal data class Conn(
    /** As SQL Server spells an address: host,port. */
    val server: String,
    val user: String,
    val password: String,
    val database: String,
    val trustCertificate: Boolean,
    val reach: Reach,
    val ssh: Ssh,
) {
    /**
     * Where sqlcmd should look for the server.
     *
     * Through a tunnel that is the near end of it rather than the server's own address — the point
     * of the forward is that the server's address means nothing on this side of it.
     */
    val address: String
        get() = if (reach == Reach.Tunnel) "127.0.0.1," + ssh.localPort else server

    /** The host half of the address, which is what a port-forward has to be pointed at. */
    val host: String get() = server.substringBefore(',').trim().ifBlank { "localhost" }

    /** The port half, defaulted the way SQL Server defaults it. */
    val port: String get() = server.substringAfter(',', "1433").trim().ifBlank { "1433" }

    /** What is missing before a connection can be attempted, or null when nothing is. */
    fun missing(): String? = if (reach == Reach.Direct) null else ssh.missing()

    companion object {
        fun from(config: Map<String, String>): Conn {
            fun value(key: String, fallback: String): String =
                config[key]?.trim().orEmpty().ifBlank { fallback }
            val trust = config["sql.trustCert"]?.trim().orEmpty()
            return Conn(
                server = value("sql.server", "localhost,1433"),
                user = value("sql.user", "sa"),
                password = config["sql.password"].orEmpty(),
                database = value("sql.database", "master"),
                // Default on: the servers this reaches are usually a VM or a container with a
                // self-signed certificate, and a refused handshake there is not a security finding.
                trustCertificate = trust.isBlank() || trust == "true",
                reach = Reach.of(value("sql.ssh.mode", "off")),
                ssh = Ssh(
                    host = value("sql.ssh.host", ""),
                    port = value("sql.ssh.port", "22"),
                    user = value("sql.ssh.user", ""),
                    auth = SshAuth.of(value("sql.ssh.auth", "key")),
                    password = config["sql.ssh.password"].orEmpty(),
                    keyPath = value("sql.ssh.key", ""),
                    passphrase = config["sql.ssh.passphrase"].orEmpty(),
                    localPort = value("sql.ssh.localPort", "11433"),
                ),
            )
        }
    }
}

/** The SSH half of a connection; unused when the reach is [Reach.Direct]. */
internal data class Ssh(
    val host: String,
    val port: String,
    val user: String,
    val auth: SshAuth,
    val password: String,
    val keyPath: String,
    val passphrase: String,
    val localPort: String,
) {
    /**
     * What is missing before this can be attempted at all.
     *
     * Said before anything is run, because ssh's own complaint about a half-filled connection is a
     * usage message, and a usage message is not an answer to "why did nothing happen".
     */
    fun missing(): String? = when {
        host.isBlank() -> "Set the SSH host in Settings."
        user.isBlank() -> "Set the SSH user in Settings."
        auth == SshAuth.Key && keyPath.isBlank() -> "Set the SSH key file in Settings."
        auth == SshAuth.Password && password.isBlank() -> "Set the SSH password in Settings."
        else -> null
    }
}
