from __future__ import annotations

import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from PySide6.QtCore import QCoreApplication

from app.core.timer_engine import State, TimerEngine
from app.storage.config import AppConfig


class FakeClock:
    def __init__(self) -> None:
        self.now = 0.0

    def __call__(self) -> float:
        return self.now

    def advance(self, seconds: float) -> None:
        self.now += seconds


class FakeIdle:
    def __init__(self) -> None:
        self.value = 0.0

    def __call__(self) -> float:
        return self.value


class TimerEngineTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls._app = QCoreApplication([])

    def make_engine(self):
        config = AppConfig(remind_minutes=1, idle_minutes=3, snooze_minutes=5)
        clock = FakeClock()
        idle = FakeIdle()
        engine = TimerEngine(config, clock=clock, idle_provider=idle)
        return engine, config, clock, idle

    def test_triggers_after_limit(self) -> None:
        engine, _, clock, _ = self.make_engine()
        events = []
        engine.remind_requested.connect(lambda s, a: events.append((s, a)))
        for _ in range(61):
            clock.advance(1.0)
            engine.tick()
        self.assertEqual(len(events), 1)
        # 累计到 60 秒即触发，触发后不再累加
        self.assertAlmostEqual(events[0][0], 60.0, places=1)
        self.assertTrue(events[0][1])
        self.assertEqual(engine.state, State.REMINDING)

    def test_idle_pauses_and_resumes(self) -> None:
        engine, _, clock, idle = self.make_engine()
        clock.advance(10.0)
        idle.value = 200.0
        engine.tick()
        self.assertEqual(engine.state, State.PAUSED)
        # 空闲已超阈值，这一跳的时间属于离开时段，不计入累计
        self.assertAlmostEqual(engine.accumulated, 0.0, places=1)

        clock.advance(10.0)
        engine.tick()
        self.assertAlmostEqual(engine.accumulated, 0.0, places=1)

        idle.value = 0.0
        clock.advance(1.0)
        engine.tick()
        self.assertEqual(engine.state, State.TRACKING)
        self.assertAlmostEqual(engine.accumulated, 0.0, places=1)

        # 恢复后的下一跳才开始重新累计
        clock.advance(1.0)
        engine.tick()
        self.assertAlmostEqual(engine.accumulated, 1.0, places=1)

    def test_snooze_once_then_force(self) -> None:
        engine, _, clock, _ = self.make_engine()
        events = []
        engine.remind_requested.connect(lambda s, a: events.append((s, a)))
        for _ in range(61):
            clock.advance(1.0)
            engine.tick()
        self.assertEqual(engine.state, State.REMINDING)

        self.assertTrue(engine.snooze())
        self.assertEqual(engine.state, State.SNOOZED)
        self.assertFalse(engine.snooze())

        clock.advance(300.0)
        engine.tick()
        self.assertEqual(engine.state, State.REMINDING)
        self.assertEqual(len(events), 2)
        self.assertFalse(events[-1][1])

    def test_finish_resets_accumulated(self) -> None:
        engine, _, clock, _ = self.make_engine()
        for _ in range(31):
            clock.advance(1.0)
            engine.tick()
        engine.force_reminder()
        engine.reminder_finished()
        self.assertEqual(engine.state, State.TRACKING)
        self.assertAlmostEqual(engine.accumulated, 0.0, places=1)

    def test_dnd_holds_reminder(self) -> None:
        engine, _, clock, _ = self.make_engine()
        events = []
        engine.remind_requested.connect(lambda s, a: events.append((s, a)))
        engine._is_dnd = lambda: True
        for _ in range(61):
            clock.advance(1.0)
            engine.tick()
        self.assertEqual(engine.state, State.PENDING)
        self.assertEqual(len(events), 0)

        engine._is_dnd = lambda: False
        clock.advance(1.0)
        engine.tick()
        self.assertEqual(engine.state, State.REMINDING)
        self.assertEqual(len(events), 1)


if __name__ == "__main__":
    unittest.main()
