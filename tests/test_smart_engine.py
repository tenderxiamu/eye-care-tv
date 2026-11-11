from __future__ import annotations

import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from app.core.smart_engine import (
    AdaptiveIntervalBandit,
    CANDIDATE_INTERVALS,
    FatigueFeatures,
    FatigueModel,
    InsightGenerator,
    SessionRecord,
    SmartEngine,
    UsageStore,
)


class FatigueModelTest(unittest.TestCase):
    def test_predict_range(self) -> None:
        model = FatigueModel()
        features = model.extract_features(
            consecutive_minutes=60.0,
            time_since_break=60.0,
            snooze_streak=2,
            hour=14,
            idle_ratio=0.1,
        )
        score = model.predict(features)
        self.assertGreaterEqual(score, 0.0)
        self.assertLessEqual(score, 100.0)

    def test_high_fatigue_score_higher_than_low(self) -> None:
        model = FatigueModel()
        low = model.extract_features(10.0, 10.0, 0, 10, 0.8)
        high = model.extract_features(120.0, 120.0, 5, 23, 0.0)
        self.assertGreater(model.predict(high), model.predict(low))

    def test_hour_factor(self) -> None:
        self.assertAlmostEqual(FatigueModel.hour_factor(23), 1.0)
        self.assertAlmostEqual(FatigueModel.hour_factor(3), 1.0)
        self.assertAlmostEqual(FatigueModel.hour_factor(14), 0.7)
        self.assertAlmostEqual(FatigueModel.hour_factor(10), 0.4)

    def test_snooze_feedback_reduces_weights(self) -> None:
        model = FatigueModel()
        features = model.extract_features(60.0, 60.0, 1, 14, 0.1)
        before = dict(model.weights)
        model.update(features, "snooze")
        # 延迟反馈后，至少有一个权重下降
        self.assertTrue(any(model.weights[k] < before[k] for k in before))

    def test_train_count_increments(self) -> None:
        model = FatigueModel()
        features = model.extract_features(30.0, 30.0, 0, 10, 0.5)
        model.update(features, "complete")
        model.update(features, "snooze")
        self.assertEqual(model.train_count, 2)

    def test_serialization_roundtrip(self) -> None:
        model = FatigueModel()
        features = model.extract_features(60.0, 60.0, 2, 20, 0.2)
        model.update(features, "complete")
        data = model.to_dict()
        restored = FatigueModel.from_dict(data)
        self.assertEqual(restored.train_count, model.train_count)
        for k in model.weights:
            self.assertAlmostEqual(restored.weights[k], model.weights[k])


class AdaptiveIntervalBanditTest(unittest.TestCase):
    def test_selects_untried_arm_first(self) -> None:
        bandit = AdaptiveIntervalBandit()
        selected = bandit.select(30)
        # 首次应选择未尝试过的臂中最接近 30 的
        self.assertEqual(selected, 30)
        self.assertEqual(bandit.counts[30], 0)  # select 不增加计数

    def test_update_records_counts_and_rewards(self) -> None:
        bandit = AdaptiveIntervalBandit()
        bandit.update(30, 1.0)
        bandit.update(30, 1.0)
        bandit.update(45, -0.3)
        self.assertEqual(bandit.counts[30], 2)
        self.assertEqual(bandit.rewards[30], 2.0)
        self.assertEqual(bandit.counts[45], 1)
        self.assertAlmostEqual(bandit.rewards[45], -0.3)

    def test_best_interval_prefers_high_reward(self) -> None:
        bandit = AdaptiveIntervalBandit()
        bandit.update(20, -0.3)
        bandit.update(20, -0.3)
        bandit.update(45, 1.0)
        bandit.update(45, 1.0)
        self.assertEqual(bandit.best_interval(), 45)

    def test_total_trials(self) -> None:
        bandit = AdaptiveIntervalBandit()
        bandit.update(30, 1.0)
        bandit.update(45, 1.0)
        bandit.update(60, -0.3)
        self.assertEqual(bandit.total_trials(), 3)

    def test_serialization_roundtrip(self) -> None:
        bandit = AdaptiveIntervalBandit()
        bandit.update(30, 1.0)
        bandit.update(45, -0.3)
        data = bandit.to_dict()
        restored = AdaptiveIntervalBandit.from_dict(data)
        self.assertEqual(restored.counts, bandit.counts)
        for k in bandit.rewards:
            self.assertAlmostEqual(restored.rewards[k], bandit.rewards[k])

    def test_ucb_explores_less_tried_arms(self) -> None:
        """经过多次尝试后，UCB 应能选出高回报臂，而非永远只选一个。"""
        bandit = AdaptiveIntervalBandit()
        current = 30
        for _ in range(50):
            chosen = bandit.select(current)
            # 30 分钟回报最高，40 次之
            reward = 1.0 if chosen == 30 else (0.5 if chosen == 40 else 0.0)
            bandit.update(chosen, reward)
        # 30 的平均回报最高，应是最佳
        self.assertEqual(bandit.best_interval(), 30)


class UsageStoreTest(unittest.TestCase):
    def test_append_and_load(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "usage.jsonl"
            store = UsageStore(path)
            record = SessionRecord(
                timestamp=1000.0,
                screen_minutes=30.0,
                idle_ratio=0.1,
                snoozed=False,
                completed=True,
                hour=14,
                weekday=2,
            )
            store.append(record)
            loaded = store.load()
            self.assertEqual(len(loaded), 1)
            self.assertEqual(loaded[0].screen_minutes, 30.0)
            self.assertTrue(loaded[0].completed)

    def test_load_limit(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "usage.jsonl"
            store = UsageStore(path)
            for i in range(5):
                store.append(SessionRecord(
                    timestamp=float(i), screen_minutes=10.0, idle_ratio=0.0,
                    snoozed=False, completed=True, hour=10, weekday=0,
                ))
            self.assertEqual(len(store.load(limit=3)), 3)
            self.assertEqual(len(store.load()), 5)

    def test_clear(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "usage.jsonl"
            store = UsageStore(path)
            store.append(SessionRecord(
                timestamp=0.0, screen_minutes=10.0, idle_ratio=0.0,
                snoozed=False, completed=True, hour=10, weekday=0,
            ))
            store.clear()
            self.assertEqual(len(store.load()), 0)


class InsightGeneratorTest(unittest.TestCase):
    def test_no_records(self) -> None:
        gen = InsightGenerator([])
        insights = gen.generate()
        self.assertTrue(len(insights) >= 1)
        self.assertIn("数据", insights[0])

    def test_high_snooze_rate(self) -> None:
        records = [
            SessionRecord(1000.0, 30.0, 0.0, True, False, 10, 0) for _ in range(6)
        ]
        gen = InsightGenerator(records)
        insights = gen.generate()
        self.assertTrue(any("延迟" in i for i in insights))

    def test_peak_hour(self) -> None:
        records = [
            SessionRecord(1000.0, 30.0, 0.0, False, True, 15, 0) for _ in range(5)
        ] + [
            SessionRecord(1000.0, 30.0, 0.0, False, True, 10, 0) for _ in range(1)
        ]
        gen = InsightGenerator(records)
        insights = gen.generate()
        self.assertTrue(any("15:00" in i for i in insights))

    def test_late_night(self) -> None:
        records = [
            SessionRecord(1000.0, 30.0, 0.0, False, True, 23, 0) for _ in range(3)
        ]
        gen = InsightGenerator(records)
        insights = gen.generate()
        self.assertTrue(any("深夜" in i for i in insights))


class SmartEngineTest(unittest.TestCase):
    def setUp(self) -> None:
        self._tmpdir = tempfile.TemporaryDirectory()
        self._tmp = Path(self._tmpdir.name)
        # 重定向存储路径
        import app.core.smart_engine as se
        self._orig_usage = se.USAGE_PATH
        self._orig_model = se.MODEL_PATH
        se.USAGE_PATH = self._tmp / "usage.jsonl"
        se.MODEL_PATH = self._tmp / "smart_model.json"

    def tearDown(self) -> None:
        import app.core.smart_engine as se
        se.USAGE_PATH = self._orig_usage
        se.MODEL_PATH = self._orig_model
        self._tmpdir.cleanup()

    def test_full_completion_flow(self) -> None:
        smart = SmartEngine()
        smart.record_tick(60.0, 5.0)
        smart.on_reminder_triggered(30.0)
        smart.on_completed(30)

        stats = smart.daily_stats()
        self.assertEqual(stats["breaks"], 1)
        self.assertEqual(stats["snoozes"], 0)
        self.assertGreater(stats["fatigue_training_samples"], 0)

    def test_snooze_flow(self) -> None:
        smart = SmartEngine()
        smart.record_tick(60.0, 0.0)
        smart.on_reminder_triggered(30.0)
        smart.on_snooze()
        smart.on_completed(30)

        stats = smart.daily_stats()
        self.assertEqual(stats["snoozes"], 1)
        self.assertEqual(stats["breaks"], 0)  # 延迟的不算完成休息

    def test_fatigue_score_changes_with_input(self) -> None:
        smart = SmartEngine()
        low = smart.fatigue_score(5.0)
        high = smart.fatigue_score(120.0)
        self.assertGreater(high, low)

    def test_suggested_interval_keeps_current_with_little_data(self) -> None:
        smart = SmartEngine()
        # 数据不足时保持当前间隔
        self.assertEqual(smart.suggested_interval(30), 30)

    def test_persistence_across_instances(self) -> None:
        smart1 = SmartEngine()
        smart1.record_tick(60.0, 0.0)
        smart1.on_reminder_triggered(30.0)
        smart1.on_completed(30)

        # 新实例应加载到已学习的数据
        smart2 = SmartEngine()
        self.assertEqual(smart2.fatigue.train_count, 1)
        self.assertEqual(smart2.bandit.total_trials(), 1)

    def test_insights_returns_list(self) -> None:
        smart = SmartEngine()
        insights = smart.insights()
        self.assertIsInstance(insights, list)


if __name__ == "__main__":
    unittest.main()
