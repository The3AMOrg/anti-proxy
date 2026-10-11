package org.ap.protocol.account

import android.app.Application
import android.content.Context
import androidx.core.content.edit
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.NoCredentialException
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.auth.GoogleAuthProvider
import com.google.firebase.auth.userProfileChangeRequest
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import org.ap.protocol.R
import org.ap.protocol.classroom.ClassroomRepository

enum class AppRole { Instructor, Student }

enum class ThemeMode { System, Light, Dark }

data class AccountState(
    val user: FirebaseUser? = null,
    val displayName: String? = null,
    val role: AppRole? = null,
    val themeMode: ThemeMode = ThemeMode.System,
    /** The one-time "allow nearby access" step after choosing a role has been answered (or skipped). */
    val permissionsAsked: Boolean = false,
    val signingIn: Boolean = false,
    val error: String? = null
)

/** Google sign-in (required for classrooms) and the user's chosen role. */
class AccountViewModel(app: Application) : AndroidViewModel(app) {

    private val auth = FirebaseAuth.getInstance()
    private val prefs = app.getSharedPreferences("account", Context.MODE_PRIVATE)
    private val classrooms = ClassroomRepository()

    private val _state = MutableStateFlow(
        AccountState(
            user = auth.currentUser,
            displayName = auth.currentUser?.displayName,
            role = savedRole(),
            themeMode = prefs.getString(KEY_THEME, null)?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() } ?: ThemeMode.System,
            permissionsAsked = prefs.getBoolean(KEY_PERMISSIONS_ASKED, false)
        )
    )
    val state: StateFlow<AccountState> = _state.asStateFlow()

    private val authListener = FirebaseAuth.AuthStateListener { a ->
        _state.update { it.copy(user = a.currentUser, displayName = a.currentUser?.displayName) }
    }

    init {
        auth.addAuthStateListener(authListener)
    }

    override fun onCleared() {
        auth.removeAuthStateListener(authListener)
    }

    /** [activityContext] must be an Activity: Credential Manager shows its account picker over it. */
    fun signIn(activityContext: Context) {
        if (_state.value.signingIn) return
        _state.update { it.copy(signingIn = true, error = null) }
        viewModelScope.launch {
            val error = try {
                val idToken = googleIdToken(activityContext)
                val user = auth.signInWithCredential(GoogleAuthProvider.getCredential(idToken, null)).await().user
                user?.let(::saveProfile)
                null
            } catch (_: GetCredentialCancellationException) {
                null // user closed the picker
            } catch (_: NoCredentialException) {
                "No Google account on this phone. Add one in Settings, then try again."
            } catch (e: Exception) {
                "Couldn't sign in: ${e.message ?: "unknown error"}"
            }
            _state.update { it.copy(signingIn = false, error = error) }
        }
    }

    fun signOut() {
        auth.signOut()
    }

    /**
     * Rules compare stored names with the ID token's name, so: update the profile, refresh the
     * token, then rewrite the name on the profile, the teacher's classrooms and memberships.
     */
    fun updateName(name: String, onDone: (String) -> Unit) {
        val user = auth.currentUser ?: return
        viewModelScope.launch {
            val message = try {
                user.updateProfile(userProfileChangeRequest { displayName = name }).await()
                user.getIdToken(true).await()
                saveProfile(user)
                classrooms.propagateName(user.uid, name)
                _state.update { it.copy(displayName = name) }
                "Your name is now $name."
            } catch (e: Exception) {
                "Couldn't change your name. Make sure you're online and try again."
            }
            onDone(message)
        }
    }

    fun setRole(role: AppRole) {
        prefs.edit { putString(KEY_ROLE, role.name) }
        _state.update { it.copy(role = role) }
    }

    fun markPermissionsAsked() {
        prefs.edit { putBoolean(KEY_PERMISSIONS_ASKED, true) }
        _state.update { it.copy(permissionsAsked = true) }
    }

    fun setThemeMode(mode: ThemeMode) {
        prefs.edit { putString(KEY_THEME, mode.name) }
        _state.update { it.copy(themeMode = mode) }
    }

    private suspend fun googleIdToken(context: Context): String {
        val option = GetGoogleIdOption.Builder()
            .setFilterByAuthorizedAccounts(false) // first sign-in: let the user pick any account
            .setServerClientId(context.getString(R.string.default_web_client_id))
            .setAutoSelectEnabled(false)
            .build()
        val credential = CredentialManager.create(context)
            .getCredential(context, GetCredentialRequest.Builder().addCredentialOption(option).build())
            .credential
        check(credential is CustomCredential && credential.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
            "Unexpected credential type"
        }
        return GoogleIdTokenCredential.createFrom(credential.data).idToken
    }

    // Not awaited: Firestore queues the write offline and sends it when it can.
    private fun saveProfile(user: FirebaseUser) {
        val name = user.displayName?.takeIf { it.isNotBlank() } ?: return
        FirebaseFirestore.getInstance().collection("users").document(user.uid).set(mapOf("name" to name))
    }

    private fun savedRole(): AppRole? =
        prefs.getString(KEY_ROLE, null)?.let { runCatching { AppRole.valueOf(it) }.getOrNull() }

    private companion object {
        const val KEY_ROLE = "role"
        const val KEY_THEME = "theme"
        const val KEY_PERMISSIONS_ASKED = "permissions_asked"
    }
}
