package com.junited31.leftovers.network

import com.google.android.gms.tasks.Tasks
import com.google.firebase.auth.FirebaseAuth
import com.google.android.gms.tasks.Task
import java.util.concurrent.CancellationException
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

fun interface TokenProvider {
    fun idToken(): String
}

class AnonymousFirebaseTokenProvider(
    private val auth: FirebaseAuth = FirebaseAuth.getInstance(),
    private val waiter: BoundedTaskWaiter = BoundedTaskWaiter(15, TimeUnit.SECONDS),
) : TokenProvider {
    override fun idToken(): String {
        val user = auth.currentUser ?: waiter.await(auth.signInAnonymously()).user
            ?: throw TokenUnavailableException()
        return waiter.await(user.getIdToken(false)).token ?: throw TokenUnavailableException()
    }
}

class TokenUnavailableException(cause: Throwable? = null) : Exception(cause)

class BoundedTaskWaiter(
    private val timeout: Long,
    private val unit: TimeUnit,
) {
    fun <T> await(task: Task<T>): T = try {
        Tasks.await(task, timeout, unit)
    } catch (error: TimeoutException) {
        throw TokenUnavailableException(error)
    } catch (error: ExecutionException) {
        throw TokenUnavailableException(error)
    } catch (error: CancellationException) {
        throw TokenUnavailableException(error)
    } catch (error: InterruptedException) {
        Thread.currentThread().interrupt()
        throw TokenUnavailableException(error)
    }
}
