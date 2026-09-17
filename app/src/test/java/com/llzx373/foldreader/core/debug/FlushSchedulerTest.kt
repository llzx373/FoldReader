package com.llzx373.foldreader.core.debug

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 刷新调度：只有真的有数据时才排任务，空闲期不能持续空转。
 * 原实现用固定周期定时器，没数据也每 500ms 唤醒一次。
 */
class FlushSchedulerTest {

    private class FakeScheduler {
        val tasks = mutableListOf<Runnable>()
        var acceptTasks = true

        fun schedule(task: Runnable): Boolean {
            if (!acceptTasks) return false
            tasks += task
            return true
        }
    }

    @Test
    fun `有新数据才排任务且同一时刻最多一个`() {
        val fake = FakeScheduler()
        var flushes = 0
        val scheduler = FlushScheduler(schedule = fake::schedule, flush = { flushes++ })

        assertEquals("没有数据时不排任务", 0, fake.tasks.size)

        scheduler.onData()
        scheduler.onData()
        scheduler.onData()

        assertEquals("连续写入只应排一个待执行任务", 1, fake.tasks.size)
        assertEquals(0, flushes)
    }

    @Test
    fun `任务执行后可以再次排程`() {
        val fake = FakeScheduler()
        var flushes = 0
        val scheduler = FlushScheduler(schedule = fake::schedule, flush = { flushes++ })

        scheduler.onData()
        fake.tasks.removeAt(0).run()
        assertEquals(1, flushes)

        scheduler.onData()
        assertEquals("刷新后应能重新排程", 1, fake.tasks.size)
        fake.tasks.removeAt(0).run()
        assertEquals(2, flushes)

        assertEquals("空闲：不再有后续任务", 0, fake.tasks.size)
    }

    @Test
    fun `排程失败时不吞掉后续排程机会`() {
        val fake = FakeScheduler().apply { acceptTasks = false }
        var flushes = 0
        val scheduler = FlushScheduler(schedule = fake::schedule, flush = { flushes++ })

        scheduler.onData()
        assertTrue("执行器不可用时不该留下任何任务", fake.tasks.isEmpty())

        fake.acceptTasks = true
        scheduler.onData()
        assertEquals("恢复后应能正常排程", 1, fake.tasks.size)
    }

    @Test
    fun `立即刷新绕过节流`() {
        val fake = FakeScheduler()
        var flushes = 0
        val scheduler = FlushScheduler(schedule = fake::schedule, flush = { flushes++ })

        scheduler.flushNow()
        scheduler.flushNow()

        assertEquals(2, flushes)
        assertTrue(fake.tasks.isEmpty())
    }
}
