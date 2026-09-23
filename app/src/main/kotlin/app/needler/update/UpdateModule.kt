package app.needler.update

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * The one binding this package needs from Hilt.
 *
 * Everything else — [GitHubHttp], [GitHubReleaseClient], [ApkDownloader], [ApkInstaller],
 * [InstalledVersion], [UpdateRepository] — is an `@Inject constructor` class that Hilt can build
 * without being told how, so there are no `@Provides` methods here and nothing to keep in step with
 * a constructor signature. Only [UpdatePreferences] needs a module, because it is an interface, and
 * it is an interface so that the scheduling decisions above it can be tested with an in-memory fake
 * instead of a device.
 *
 * `SingletonComponent`, because the update state has to outlive any one screen: the banner's view
 * model is created and destroyed by the composition, and a download must not restart because the
 * listener rotated the phone.
 *
 * Nothing in this module — or anywhere else in this package — injects `CredentialProvider`,
 * `SecureCredentialStore`, `NeedlerHttpClient` or any other holder of the listener's server
 * credentials. That is not an accident of what happens to be needed; it is the guarantee that this
 * package cannot send a credential to GitHub even by mistake. See [GitHubHttp].
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class UpdateModule {

    @Binds
    @Singleton
    abstract fun bindUpdatePreferences(preferences: AndroidUpdatePreferences): UpdatePreferences
}
