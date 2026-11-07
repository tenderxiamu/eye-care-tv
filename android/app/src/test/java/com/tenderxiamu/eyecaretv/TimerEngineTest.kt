package com.tenderxiamu.eyecaretv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

class TimerEngineTest {

    private var now = 0L
    private val engine = TimerEngine { now }
    private val config = ReminderConfig(intervalMinutes = 30)

    /** 按 30 秒真实采样节奏推进，避免触发时间钳制。 */
    private fun step(
        seconds: Long,
        active: Boolean = true,
        cfg: ReminderConfig = config,
    ): Boolean {
        var remind = false
        var left = seconds
        while (left > 0) {
            val chunk = minOf(left, 30L)
            now += chunk * 1000
            if (engine.tick(active, cfg, now)) remind = true
            left -= chunk
        }
        return remind
    }

    @Test
    fun `accumulates only while screen is active`() {
        step(60)
        assertEquals(TimerEngine.State.TRACKING, engine.state)
        assertEquals(60_000L, engine.accumulatedMs)

        step(60, active = false)
        assertEquals(TimerEngine.State.PAUSED, engine.state)
        assertEquals(60_000L, engine.accumulatedMs)

        step(30)
        assertEquals(TimerEngine.State.TRACKING, engine.state)
        assertEquals(60_000L, engine.accumulatedMs)
    }

    @Test
    fun `triggers once interval is reached`() {
        assertFalse(step(29 * 60))
        assertEquals(TimerEngine.State.TRACKING, engine.state)
        assertTrue(step(60))
        assertEquals(TimerEngine.State.REMINDING, engine.state)
        assertEquals(1_800_000L, engine.accumulatedMs)
    }

    @Test
    fun `snooze works only once`() {
        engine.restore(30 * 60 * 1000L, snoozeUsed = false, snoozeDeadline = 0L)
        assertTrue(step(30))
        assertTrue(engine.snooze(config))
        assertEquals(TimerEngine.State.SNOOZED, engine.state)

        assertFalse(step(4 * 60))
        assertEquals(TimerEngine.State.SNOOZED, engine.state)
        assertTrue(step(60))
        assertEquals(TimerEngine.State.REMINDING, engine.state)
        assertFalse(engine.snooze(config))
    }

    @Test
    fun `finishing reminder clears accumulated time`() {
        engine.restore(30 * 60 * 1000L, snoozeUsed = false, snoozeDeadline = 0L)
        assertTrue(step(30))
        engine.reminderFinished()
        assertEquals(0L, engine.accumulatedMs)
        assertEquals(TimerEngine.State.TRACKING, engine.state)
        assertFalse(engine.snoozeUsed)
    }

    @Test
    fun `dnd holds reminder until window ends`() {
        val calendar = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 12)
            set(Calendar.MINUTE, 29)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        now = calendar.timeInMillis
        val dndConfig = ReminderConfig(
            intervalMinutes = 30,
            dndEnabled = true,
            dndStart = "12:00",
            dndEnd = "13:00",
        )
        engine.restore(29 * 60 * 1000L, false, 0L)
        // 12:29 已累计 29 分钟，一分钟后到点，但仍在免打扰时段内
        assertFalse(step(60, cfg = dndConfig))
        assertEquals(TimerEngine.State.PENDING, engine.state)

        // 一直到 12:59 仍在免打扰时段
        assertFalse(step(29 * 60, cfg = dndConfig))
        // 13:00 免打扰结束，补弹提醒
        assertTrue(step(60, cfg = dndConfig))
        assertEquals(TimerEngine.State.REMINDING, engine.state)
    }

    @Test
    fun `dnd window can cross midnight`() {
        val dndConfig = ReminderConfig(dndEnabled = true, dndStart = "22:30", dndEnd = "07:00")
        fun at(hour: Int, minute: Int): Long = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis

        assertTrue(dndConfig.isDndActive(at(23, 0)))
        assertTrue(dndConfig.isDndActive(at(2, 0)))
        assertFalse(dndConfig.isDndActive(at(7, 0)))
        assertFalse(dndConfig.isDndActive(at(15, 0)))
    }
}
