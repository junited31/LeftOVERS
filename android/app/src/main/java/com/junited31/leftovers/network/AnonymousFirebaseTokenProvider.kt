package com.junited31.leftovers.network

import com.google.android.gms.tasks.Tasks
import com.google.firebase.auth.FirebaseAuth

fun interface TokenProvider {
    fun idToken(): String
}

class AnonymousFirebaseTokenProvider(
    private val auth: FirebaseAuth = FirebaseAuth.getInstance(),
) : TokenProvider {
    override fun idToken(): String {
        val user = auth.currentUser ?: Tasks.await(auth.signInAnonymously()).user
            ?: throw TokenUnavailableException()
        return Tasks.await(user.getIdToken(false)).token ?: throw TokenUnavailableException()
    }
}

class TokenUnavailableException(cause: Throwable? = null) : Exception(cause)
