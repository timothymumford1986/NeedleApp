package app.needler.core.network

/** A deterministic [CredentialProvider] for unit tests. Records the rejection callbacks. */
class TestCredentials(
    private var server: ServerUrl? = ServerUrl.parseOrNull("https://music.example.net"),
    private var bearer: String? = "test-bearer",
    private var appPassword: String? = "test-app-password",
    private var proxy: ProxyCredentials = ProxyCredentials.None,
) : CredentialProvider {

    var bearerRejections: Int = 0
        private set

    var appPasswordRejections: Int = 0
        private set

    override fun serverUrl(): ServerUrl? = server

    override fun bearerToken(): String? = bearer

    override fun appPassword(): String? = appPassword

    override fun onBearerRejected() {
        bearerRejections++
    }

    override fun onAppPasswordRejected() {
        appPasswordRejections++
    }

    override fun proxyCredentials(): ProxyCredentials = proxy

    fun withProxyCredentials(credentials: ProxyCredentials): TestCredentials = apply {
        proxy = credentials
    }

    fun withServer(raw: String): TestCredentials = apply {
        server = requireNotNull(ServerUrl.parseOrNull(raw)) { "bad test URL: $raw" }
    }
}
