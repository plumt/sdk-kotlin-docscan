package net.plumt.docscan.internal

import com.google.android.gms.tasks.CancellationTokenSource
import com.google.android.gms.tasks.TaskCompletionSource
import com.google.android.gms.tasks.Tasks
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class TasksTest {

    @Test
    fun `완료된 Task 는 바로 값`() = runTest {
        assertEquals(3, Tasks.forResult(3).await())
    }

    @Test
    fun `실패한 Task 는 원래 예외`() = runTest {
        val e = IllegalStateException("x")
        val thrown = runCatching { Tasks.forException<Int>(e).await() }.exceptionOrNull()
        assertSame(e, thrown)
    }

    @Test
    fun `취소된 Task 는 TaskCancelledException`() = runTest {
        val thrown = runCatching { Tasks.forCanceled<Int>().await() }.exceptionOrNull()
        assertTrue(thrown is TaskCancelledException)
    }

    @Test
    fun `나중에 끝나는 Task 를 기다린다 — 성공·실패·취소`() = runTest {
        val ok = TaskCompletionSource<String>()
        val a = async(start = CoroutineStart.UNDISPATCHED) { ok.task.await() }
        ok.setResult("v")
        assertEquals("v", a.await())

        val bad = TaskCompletionSource<String>()
        val b = async(start = CoroutineStart.UNDISPATCHED) { runCatching { bad.task.await() }.exceptionOrNull() }
        val err = RuntimeException("e")
        bad.setException(err)
        assertSame(err, b.await())

        val tokens = CancellationTokenSource()
        val c = TaskCompletionSource<String>(tokens.token)
        val d = async(start = CoroutineStart.UNDISPATCHED) { runCatching { c.task.await() }.exceptionOrNull() }
        tokens.cancel()
        org.robolectric.shadows.ShadowLooper.idleMainLooper()
        assertTrue(d.await() is TaskCancelledException)
    }

    @Test
    fun `코루틴 취소는 기다림을 끝낸다`() = runTest {
        val never = TaskCompletionSource<String>()
        val job = async(start = CoroutineStart.UNDISPATCHED) { never.task.await() }
        job.cancel()
        assertTrue(runCatching { job.await() }.exceptionOrNull() is kotlinx.coroutines.CancellationException)
    }
}
