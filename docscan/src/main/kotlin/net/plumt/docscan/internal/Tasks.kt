package net.plumt.docscan.internal

import com.google.android.gms.tasks.Task
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.CancellationException
import java.util.concurrent.Executor
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** 리스너를 등록한 스레드와 무관하게 완료 스레드에서 바로 이어 간다. */
private val Direct = Executor { it.run() }

/**
 * Play 서비스 [Task] 를 기다린다. 실패는 원래 예외 그대로 던진다(분류는 호출부가 한다).
 * Task 가 취소로 끝나면 [CancellationException] 이 아니라 [TaskCancelledException] 을 던져 코루틴 취소와 구별한다.
 */
internal suspend fun <T> Task<T>.await(): T {
    if (isComplete) return completedValue()
    return suspendCancellableCoroutine { cont ->
        addOnCompleteListener(Direct) { task ->
            try {
                cont.resume(task.completedValue())
            } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
                cont.resumeWithException(e)
            }
        }
    }
}

private fun <T> Task<T>.completedValue(): T {
    if (isCanceled) throw TaskCancelledException()
    exception?.let { throw it }
    @Suppress("UNCHECKED_CAST")
    return result as T
}

/** Play 서비스 Task 가 취소로 끝났다. */
internal class TaskCancelledException : Exception("Play 서비스 작업이 취소됐다")
