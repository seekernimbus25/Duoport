package app.filmmaking.studio.data

import android.content.Context
import android.util.Log
import androidx.credentials.ClearCredentialStateRequest
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.ClearCredentialException
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import androidx.credentials.exceptions.GetCredentialInterruptedException
import androidx.credentials.exceptions.GetCredentialProviderConfigurationException
import androidx.credentials.exceptions.GetCredentialUnsupportedException
import androidx.credentials.exceptions.NoCredentialException
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.android.libraries.identity.googleid.GoogleIdTokenParsingException

/** A Google ID token and the raw nonce whose SHA-256 is inside it. Never logged or stored. */
class GoogleIdToken(val idToken: String, val rawNonce: String, val email: String?)

/** A user-facing outcome; `cancelled` means the person closed the sheet (not an error). */
class GoogleSignInException(message: String, val cancelled: Boolean = false) : Exception(message)

/**
 * Android Credential Manager → Google ID token (no Firebase). Uses the "Sign in with Google" button flow
 * (`GetSignInWithGoogleOption`), which lists every Google account on the phone and lets the person add one.
 * The server client ID is the OAuth **Web** client ID, so the token's audience is what Supabase verifies;
 * Google matches this APK by its package name and signing-certificate SHA-1 (the Android OAuth client).
 */
object GoogleCredentials {
    private const val TAG = "StudioGoogleSignIn"

    /** [activityContext] must be the foreground Activity: Credential Manager shows its UI on top of it. */
    suspend fun request(activityContext: Context, webClientId: String): GoogleIdToken {
        val nonce = GoogleNonce.create()
        val option = GetSignInWithGoogleOption.Builder(serverClientId = webClientId).setNonce(nonce.hashed).build()
        val request = GetCredentialRequest.Builder().addCredentialOption(option).build()
        val result = try {
            CredentialManager.create(activityContext).getCredential(activityContext, request)
        } catch (e: GetCredentialCancellationException) {
            throw GoogleSignInException("Google sign-in cancelled. Nothing changed.", cancelled = true)
        } catch (e: GetCredentialInterruptedException) {
            throw GoogleSignInException("Google sign-in was interrupted. Please try again.")
        } catch (e: NoCredentialException) {
            throw GoogleSignInException("No Google account is available. Add one in the phone's Settings > Accounts, then try again.")
        } catch (e: GetCredentialProviderConfigurationException) {
            throw GoogleSignInException("Google Play services is missing or out of date on this phone, so Google sign-in can't run.")
        } catch (e: GetCredentialUnsupportedException) {
            throw GoogleSignInException("This phone doesn't support Google sign-in through Credential Manager.")
        } catch (e: GetCredentialException) {
            Log.w(TAG, "getCredential failed: " + e.type)
            // "[16] Account reauth failed" / developer errors usually mean the Android OAuth client
            // (package + SHA-1) or the Web client ID doesn't match this build.
            throw GoogleSignInException("Google sign-in failed (" + e.type.substringAfterLast('.') + "). " +
                "If this keeps happening, this build's Google client setup does not match.")
        }
        val credential = result.credential
        if (credential !is CustomCredential || credential.type != GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL)
            throw GoogleSignInException("Google returned an unexpected credential type.")
        val google = try { GoogleIdTokenCredential.createFrom(credential.data) }
            catch (e: GoogleIdTokenParsingException) { throw GoogleSignInException("Google returned an unreadable sign-in token.") }
        if (google.idToken.split('.').size != 3) throw GoogleSignInException("Google returned an invalid sign-in token.")
        return GoogleIdToken(google.idToken, nonce.raw, google.id.takeIf { '@' in it })
    }

    /** Clears Credential Manager's remembered choice so the next sign-in shows the account picker again. */
    suspend fun clear(context: Context) {
        try { CredentialManager.create(context).clearCredentialState(ClearCredentialStateRequest()) }
        catch (e: ClearCredentialException) { Log.w(TAG, "clearCredentialState: " + e.type) }
    }
}
