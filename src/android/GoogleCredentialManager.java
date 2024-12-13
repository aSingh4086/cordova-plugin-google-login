package com.googleSignIn.plugin

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.credentials.*
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.NoCredentialException
import androidx.lifecycle.Lifecycle
import com.google.android.gms.auth.api.Auth
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.auth.api.signin.GoogleSignInResult
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.gms.common.api.GoogleApiClient
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.android.libraries.identity.googleid.GoogleIdTokenParsingException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.apache.cordova.CallbackContext
import org.apache.cordova.CordovaInterface
import org.apache.cordova.CordovaPlugin
import org.apache.cordova.CordovaWebView
import org.json.JSONArray
import org.json.JSONObject


class GoogleCredentialManager : CordovaPlugin() {

    private var credentialManager: CredentialManager? = null
    private var credentialResponse: GetCredentialResponse? = null
    private var getCredentialRequest: GetCredentialRequest? = null

    private lateinit var mCallBackContext: CallbackContext
    private lateinit var signInActivityLauncher: ActivityResultLauncher<Intent>
    private var mGoogleApiClient: GoogleApiClient? = null

    override fun initialize(cordova: CordovaInterface?, webView: CordovaWebView?) {
        super.initialize(cordova, webView)
        cordova?.context?.let {
            credentialManager = CredentialManager.create(it)
        }
        (cordova?.activity as? AppCompatActivity)?.let { activity ->
            if (activity.lifecycle.currentState >= Lifecycle.State.CREATED) return

            signInActivityLauncher = activity.registerForActivityResult(
                ActivityResultContracts.StartActivityForResult()
            ) {
                it.data?.let { intent ->
                    handleSignInResult(Auth.GoogleSignInApi.getSignInResultFromIntent(intent))
                }
            }
        }
    }

    override fun execute(
        action: String,
        args: JSONArray,
        callbackContext: CallbackContext
    ): Boolean {
        mCallBackContext = callbackContext
        val clientId = if (args.length() > 0) args.getString(0) else ""
        var webClientId = ""
        if (clientId.isNotEmpty()) {
            webClientId = JSONObject(clientId).getString("webClientId")
        }
        return when (action) {
            "isAvailable" -> {
                callbackContext.success(isGooglePlayServicesAvailable(cordova.context).toString())
                true
            }

            "login" -> {

                initLogin(webClientId, callbackContext)
                true
            }

            "trySilentLogin" -> {
                credentialResponse?.let {
                    processSignIn({ callbackContext.success(getUserInfo(this)) }, it)
                } ?: run {
                    callbackContext.error("No credentials found")
                }
                true
            }

            "signOut" -> {
                signOut()
                callbackContext.success()
                true
            }

            else -> false
        }
    }

    private fun buildGoogleSignInClient(webClientId: String) {
        mGoogleApiClient?.disconnect()
        mGoogleApiClient = null

        val googleSignInOptions = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)

        googleSignInOptions.requestEmail().requestProfile()
        if (webClientId.isNotEmpty()) {
            googleSignInOptions.requestIdToken(webClientId)
        }

        val builder = GoogleApiClient.Builder(webView.context)
            .addOnConnectionFailedListener({
                Log.e("Google Sign in", "Connection failed")
            })
            .addApi(Auth.GOOGLE_SIGN_IN_API, googleSignInOptions.build())

        mGoogleApiClient = builder.build()
    }

    private fun initLogin(clientId: String, callbackContext: CallbackContext) {
        setCredentialRequest(clientId)
        login(clientId) {
            callbackContext.success(getUserInfo(this))
        }
    }

    private fun handleSignInResult(signInResult: GoogleSignInResult?) {
        signInResult?.takeIf { it.isSuccess }?.signInAccount?.idToken?.let { idToken ->
            mCallBackContext.success(JSONObject(mapOf("idToken" to idToken)))
            return
        } ?: run {
            mCallBackContext.error("Failed to sign in")
        }
    }

    private fun checkExistingSignInOrLaunchSignIn(webClientId: String) {
        buildGoogleSignInClient(webClientId)
        signInActivityLauncher.launch(Auth.GoogleSignInApi.getSignInIntent(mGoogleApiClient!!))
    }

    private fun isGooglePlayServicesAvailable(context: Context): Boolean {
        return GoogleApiAvailability.getInstance()
            .isGooglePlayServicesAvailable(context) == ConnectionResult.SUCCESS
    }

    private fun setCredentialRequest(webClientId: String) {
        getCredentialRequest = GetGoogleIdOption.Builder()
            .setServerClientId(webClientId)
            .setFilterByAuthorizedAccounts(false)
            .setAutoSelectEnabled(true)
            .build()
            .let { GetCredentialRequest.Builder().addCredentialOption(it).build() }
    }

    private fun signOut() {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                credentialManager?.clearCredentialState(ClearCredentialStateRequest())
            } catch (e: Exception) {
                Log.e("Google Sign in", "Sign out failed")
            }
        }
    }

    private fun login(webClientId: String, callback: GoogleIdTokenCredential.() -> Unit) {
        (cordova?.context as? AppCompatActivity)?.let {
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    credentialManager?.let { manager ->
                        getCredentialRequest?.let { request ->
                            credentialResponse =
                                manager.getCredential(it, request).also { response ->
                                    processSignIn(callback, response)
                                }
                        }
                    } ?: run { checkExistingSignInOrLaunchSignIn(webClientId) }
                } catch (e: GetCredentialCancellationException) {
                    e.printStackTrace()
                } catch (e: NoCredentialException) {
                    checkExistingSignInOrLaunchSignIn(webClientId)
                    e.printStackTrace()
                }
            }
        } ?: run {
            Log.e("Google Sign in", "Activity not found")
            mCallBackContext.error("Activity not found")
        }
    }

    private fun processSignIn(
        callback: GoogleIdTokenCredential.() -> Unit,
        result: GetCredentialResponse
    ) {
        when (val credential = result.credential) {
            is GoogleIdTokenCredential -> {
                callback(result.credential as GoogleIdTokenCredential)
            }
            // GoogleIdToken credential
            is CustomCredential -> {
                if (credential.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
                    try {
                        // Use googleIdTokenCredential and extract the ID to validate and
                        // authenticate on your server.
                        val googleIdTokenCredential = GoogleIdTokenCredential
                            .createFrom(credential.data)
                        callback(googleIdTokenCredential)
                    } catch (e: GoogleIdTokenParsingException) {
                        Log.e("credential_error", "Received an invalid google id token response", e)
                    }
                } else {
                    // Catch any unrecognized custom credential type here.
                    Log.e("credential error", "Unexpected type of credential")
                }
            }

            else -> {
                Log.e("credential error", "Unexpected type of credential")
            }
        }
    }

    private fun getUserInfo(googleIdTokenCredential: GoogleIdTokenCredential): JSONObject {
        return JSONObject().apply {
            put("idToken", googleIdTokenCredential.idToken)
        }
    }
}
