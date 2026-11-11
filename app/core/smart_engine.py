"""智能自适应护眼引擎。

三个可独立测试的 AI 组件：
1. FatigueModel：基于特征的眼疲劳预测，权重随用户反馈在线更新（梯度下降式学习）。
2. AdaptiveIntervalBandit：UCB1 多臂老虎机，从"完成 / 延迟"反馈中学习最优提醒间隔。
3. InsightGenerator：聚合使用记录，生成个性化护眼洞察。

所有类均不依赖 Qt，可脱离界面做单元测试。
"""

from __future__ import annotations

import json
import math
import os
import time
from dataclasses import asdict, dataclass, field
from pathlib import Path
from typing import Iterable

APP_DIR = Path(os.environ.get("APPDATA", Path.home())) / "EyeCareTV"
USAGE_PATH = APP_DIR / "usage.jsonl"
MODEL_PATH = APP_DIR / "smart_model.json"

# 候选提醒间隔（分钟），老虎机的臂
CANDIDATE_INTERVALS = [20, 25, 30, 35, 40, 45, 50, 60]

# 疲劳模型默认权重，特征顺序与 FatigueModel.FEATURES 一致
DEFAULT_WEIGHTS = {
    "consecutive_minutes": 0.35,   # 连续屏幕时长
    "time_since_break": 0.20,      # 距上次休息时长
    "snooze_streak": 0.18,         # 连续延迟次数
    "hour_factor": 0.17,           # 时段因子（深夜更高）
    "idle_ratio": 0.10,            # 近期空闲占比（越低越累）
}


# ---------------------------------------------------------------------------
# 数据结构
# ---------------------------------------------------------------------------


@dataclass
class SessionRecord:
    """一次完整的"计时→提醒→结束"会话记录。"""

    timestamp: float               # 提醒触发的时间戳
    screen_minutes: float          # 本次累计屏幕时长
    idle_ratio: float              # 本次会话内空闲时间占比
    snoozed: bool                  # 是否被延迟
    completed: bool                # 是否完整看完（未延迟且正常结束）
    hour: int                      # 触发时的小时（0-23）
    weekday: int                   # 触发时的星期（0=周一）


@dataclass
class FatigueFeatures:
    consecutive_minutes: float = 0.0
    time_since_break: float = 0.0
    snooze_streak: int = 0
    hour_factor: float = 0.0
    idle_ratio: float = 1.0

    FEATURE_NAMES = ("consecutive_minutes", "time_since_break", "snooze_streak", "hour_factor", "idle_ratio")


# ---------------------------------------------------------------------------
# 1. 疲劳预测模型（在线学习）
# ---------------------------------------------------------------------------


class FatigueModel:
    """预测 0-100 的眼疲劳指数，权重随用户反馈在线更新。

    反馈语义：
    - 延迟(snooze)：说明提醒太早，疲劳被高估 → 下调相关特征权重
    - 完成(complete)：说明提醒时机合适 → 微调权重使其更稳定
    """

    LEARNING_RATE = 0.05

    def __init__(self, weights: dict | None = None) -> None:
        self.weights = dict(DEFAULT_WEIGHTS)
        if weights:
            for k, v in weights.items():
                if k in self.weights:
                    self.weights[k] = float(v)
        self.train_count = 0

    # ---- 预测 ----
    @staticmethod
    def hour_factor(hour: int) -> float:
        """22:00-06:00 时段因子最高，午后次之。"""
        if 22 <= hour or hour < 6:
            return 1.0
        if 13 <= hour <= 16:
            return 0.7
        return 0.4

    def extract_features(
        self,
        consecutive_minutes: float,
        time_since_break: float,
        snooze_streak: int,
        hour: int,
        idle_ratio: float,
    ) -> FatigueFeatures:
        return FatigueFeatures(
            consecutive_minutes=min(consecutive_minutes / 120.0, 1.0),
            time_since_break=min(time_since_break / 120.0, 1.0),
            snooze_streak=min(snooze_streak / 5.0, 1.0),
            hour_factor=self.hour_factor(hour),
            idle_ratio=1.0 - max(0.0, min(idle_ratio, 1.0)),
        )

    def predict(self, features: FatigueFeatures) -> float:
        raw = sum(self.weights[name] * getattr(features, name) for name in FatigueFeatures.FEATURE_NAMES)
        # 归一化到 0-100，权重和可能因在线更新轻微漂移
        weight_sum = sum(self.weights.values()) or 1.0
        normalized = raw / weight_sum
        return round(max(0.0, min(100.0, normalized * 100.0)), 1)

    # ---- 在线学习 ----
    def update(self, features: FatigueFeatures, feedback: str) -> None:
        """根据用户反馈调整权重。

        feedback:
        - "snooze"：用户延迟，说明当前特征组合下疲劳被高估，整体下调权重
        - "complete"：用户完成，权重保持并向当前值轻微收敛
        """
        self.train_count += 1
        lr = self.LEARNING_RATE / (1.0 + self.train_count * 0.01)  # 学习率衰减

        if feedback == "snooze":
            # 高估了：对每个特征乘以（1 - lr * 特征值），让贡献大的特征降得更多
            for name in FatigueFeatures.FEATURE_NAMES:
                value = getattr(features, name)
                self.weights[name] *= 1.0 - lr * value * 0.5
        elif feedback == "complete":
            # 合适：轻微向默认权重收敛，避免过拟合到某次反馈
            for name in FatigueFeatures.FEATURE_NAMES:
                self.weights[name] += (DEFAULT_WEIGHTS[name] - self.weights[name]) * lr * 0.1

        # 保证权重非负且不极端
        for name in self.weights:
            self.weights[name] = max(0.01, min(self.weights[name], 0.6))

    # ---- 持久化 ----
    def to_dict(self) -> dict:
        return {"weights": self.weights, "train_count": self.train_count}

    @classmethod
    def from_dict(cls, data: dict) -> "FatigueModel":
        model = cls(data.get("weights"))
        model.train_count = int(data.get("train_count", 0))
        return model


# ---------------------------------------------------------------------------
# 2. 自适应间隔优化器（UCB1 多臂老虎机）
# ---------------------------------------------------------------------------


class AdaptiveIntervalBandit:
    """UCB1 多臂老虎机，学习用户偏好的提醒间隔。

    奖励设计：
    - 完整看完（completed）：+1.0
    - 被延迟（snoozed）：-0.3
    - 奖励越高，说明该间隔对用户越合适。
    """

    EXPLORATION = 1.41  # UCB1 探索系数，√2

    def __init__(self, counts: dict | None = None, rewards: dict | None = None) -> None:
        self.counts = {interval: 0 for interval in CANDIDATE_INTERVALS}
        self.rewards = {interval: 0.0 for interval in CANDIDATE_INTERVALS}
        if counts:
            for k, v in counts.items():
                if int(k) in self.counts:
                    self.counts[int(k)] = int(v)
        if rewards:
            for k, v in rewards.items():
                if int(k) in self.rewards:
                    self.rewards[int(k)] = float(v)

    def select(self, current_interval: int) -> int:
        """选择下一次建议的提醒间隔。

        每个臂都至少试一次（保证探索），之后用 UCB1 公式选。
        当某个间隔被选中次数太少时，会强制尝试它。
        """
        total = sum(self.counts.values())
        if total < len(CANDIDATE_INTERVALS):
            # 数据太少，优先选还没试过的；都试过则选接近当前值的臂以减少突兀
            untried = [i for i in CANDIDATE_INTERVALS if self.counts[i] == 0]
            if untried:
                # 选最接近当前间隔的未尝试臂
                return min(untried, key=lambda x: abs(x - current_interval))

        # UCB1
        best_interval = current_interval
        best_score = -1.0
        for interval in CANDIDATE_INTERVALS:
            n = self.counts[interval]
            if n == 0:
                return interval  # 未尝试的臂优先级最高
            mean = self.rewards[interval] / n
            bonus = self.EXPLORATION * math.sqrt(math.log(total) / n)
            score = mean + bonus
            if score > best_score:
                best_score = score
                best_interval = interval
        return best_interval

    def update(self, interval: int, reward: float) -> None:
        if interval not in self.counts:
            return
        self.counts[interval] += 1
        self.rewards[interval] += reward

    def best_interval(self) -> int:
        """返回历史平均奖励最高的间隔（用于报告展示）。"""
        tried = [(i, self.rewards[i] / max(self.counts[i], 1)) for i in CANDIDATE_INTERVALS if self.counts[i] > 0]
        if not tried:
            return 30
        return max(tried, key=lambda x: x[1])[0]

    def total_trials(self) -> int:
        return sum(self.counts.values())

    def to_dict(self) -> dict:
        return {"counts": self.counts, "rewards": self.rewards}

    @classmethod
    def from_dict(cls, data: dict) -> "AdaptiveIntervalBandit":
        return cls(data.get("counts"), data.get("rewards"))


# ---------------------------------------------------------------------------
# 3. 使用记录存储
# ---------------------------------------------------------------------------


class UsageStore:
    """追加式 JSONL 存储使用记录。"""

    def __init__(self, path: Path | None = None) -> None:
        self._path = path if path is not None else USAGE_PATH

    def append(self, record: SessionRecord) -> None:
        self._path.parent.mkdir(parents=True, exist_ok=True)
        with open(self._path, "a", encoding="utf-8") as fh:
            fh.write(json.dumps(asdict(record), ensure_ascii=False) + "\n")

    def load(self, limit: int | None = None) -> list[SessionRecord]:
        if not self._path.exists():
            return []
        records: list[SessionRecord] = []
        with open(self._path, "r", encoding="utf-8") as fh:
            for line in fh:
                line = line.strip()
                if not line:
                    continue
                try:
                    records.append(SessionRecord(**json.loads(line)))
                except (json.JSONDecodeError, TypeError):
                    continue
        if limit is not None:
            return records[-limit:]
        return records

    def clear(self) -> None:
        if self._path.exists():
            self._path.unlink()


# ---------------------------------------------------------------------------
# 4. 洞察生成器
# ---------------------------------------------------------------------------


class InsightGenerator:
    """从使用记录中提炼个性化护眼洞察。"""

    def __init__(self, records: list[SessionRecord]) -> None:
        self._records = records

    def generate(self) -> list[str]:
        insights: list[str] = []
        if not self._records:
            insights.append("还没有足够的使用数据，多用几天我会更了解你的用眼习惯～")
            return insights

        records = self._records[-30:]  # 只看最近 30 条

        # 1. 延迟率
        snooze_rate = sum(1 for r in records if r.snoozed) / len(records)
        if snooze_rate > 0.5:
            insights.append(
                f"你有 {snooze_rate:.0%} 的提醒被延迟，可能当前间隔偏短，"
                "AI 已自动学习更适合你的节奏。"
            )
        elif snooze_rate < 0.1:
            insights.append("你几乎从不延迟提醒，护眼习惯很棒，继续保持！")

        # 2. 高峰时段
        hour_counts: dict[int, int] = {}
        for r in records:
            hour_counts[r.hour] = hour_counts.get(r.hour, 0) + 1
        if hour_counts:
            peak_hour = max(hour_counts, key=hour_counts.get)
            insights.append(f"你最常在 {peak_hour}:00 左右被提醒，这是你的用眼高峰时段。")

        # 3. 平均屏幕时长
        avg_screen = sum(r.screen_minutes for r in records) / len(records)
        if avg_screen > 40:
            insights.append(f"平均每次连续用屏 {avg_screen:.0f} 分钟，建议适当缩短单次时长。")
        elif avg_screen < 20:
            insights.append(f"平均每次用屏仅 {avg_screen:.0f} 分钟，节奏很健康。")

        # 4. 工作日 vs 周末
        weekday = [r for r in records if r.weekday < 5]
        weekend = [r for r in records if r.weekday >= 5]
        if weekday and weekend:
            wd_avg = sum(r.screen_minutes for r in weekday) / len(weekday)
            we_avg = sum(r.screen_minutes for r in weekend) / len(weekend)
            if wd_avg > we_avg + 10:
                insights.append(f"工作日平均用屏 {wd_avg:.0f} 分钟，比周末多不少，工作日更要注意休息。")

        # 5. 深夜使用
        late_night = [r for r in records if r.hour >= 22 or r.hour < 6]
        if late_night:
            insights.append(f"检测到 {len(late_night)} 次深夜用眼，夜间蓝光对眼睛刺激更大，建议调低屏幕亮度。")

        return insights


# ---------------------------------------------------------------------------
# 5. 智能引擎门面
# ---------------------------------------------------------------------------


class SmartEngine:
    """整合疲劳模型、老虎机、记录存储的门面。"""

    def __init__(
        self,
        store: UsageStore | None = None,
        fatigue: FatigueModel | None = None,
        bandit: AdaptiveIntervalBandit | None = None,
    ) -> None:
        self.store = store or UsageStore()
        self.fatigue = fatigue or FatigueModel()
        self.bandit = bandit or AdaptiveIntervalBandit()
        self._last_break_ts: float = time.time()
        self._snooze_streak: int = 0
        self._session_idle_seconds: float = 0.0
        self._session_active_seconds: float = 0.0
        self._load()

    # ---- 外部调用 ----
    def record_tick(self, active_seconds: float, idle_seconds: float) -> None:
        """每秒由 TimerEngine 调用，累计本次会话的活跃/空闲时间。"""
        self._session_active_seconds += active_seconds
        self._session_idle_seconds += idle_seconds

    def fatigue_score(self, accumulated_minutes: float) -> float:
        """计算当前疲劳指数。"""
        now = time.time()
        hour = time.localtime(now).tm_hour
        idle_ratio = self._session_idle_seconds / max(self._session_active_seconds + self._session_idle_seconds, 1.0)
        features = self.fatigue.extract_features(
            consecutive_minutes=accumulated_minutes,
            time_since_break=(now - self._last_break_ts) / 60.0,
            snooze_streak=self._snooze_streak,
            hour=hour,
            idle_ratio=idle_ratio,
        )
        return self.fatigue.predict(features)

    def on_reminder_triggered(self, accumulated_minutes: float) -> None:
        """提醒触发时记录会话。"""
        now = time.time()
        local = time.localtime(now)
        total = self._session_active_seconds + self._session_idle_seconds
        idle_ratio = self._session_idle_seconds / max(total, 1.0)
        self._pending_session = SessionRecord(
            timestamp=now,
            screen_minutes=accumulated_minutes,
            idle_ratio=idle_ratio,
            snoozed=False,
            completed=False,
            hour=local.tm_hour,
            weekday=local.tm_wday,
        )

    def on_snooze(self) -> None:
        """用户延迟了提醒。"""
        self._snooze_streak += 1
        if hasattr(self, "_pending_session"):
            self._pending_session.snoozed = True

    def on_completed(self, remind_minutes: int) -> None:
        """用户完整看完提醒。"""
        self._snooze_streak = 0
        self._last_break_ts = time.time()
        self._session_idle_seconds = 0.0
        self._session_active_seconds = 0.0

        if not hasattr(self, "_pending_session"):
            return
        session = self._pending_session
        session.completed = not session.snoozed

        # 记录会话
        self.store.append(session)

        # 更新疲劳模型
        features = self.fatigue.extract_features(
            consecutive_minutes=session.screen_minutes,
            time_since_break=session.screen_minutes,
            snooze_streak=self._snooze_streak,
            hour=session.hour,
            idle_ratio=session.idle_ratio,
        )
        feedback = "snooze" if session.snoozed else "complete"
        self.fatigue.update(features, feedback)

        # 更新老虎机
        reward = 1.0 if session.completed else -0.3
        self.bandit.update(remind_minutes, reward)

        self._save()

    def suggested_interval(self, current_interval: int) -> int:
        """返回 AI 建议的提醒间隔。"""
        if self.bandit.total_trials() < 3:
            return current_interval  # 数据太少，保持用户设置
        return self.bandit.select(current_interval)

    def insights(self) -> list[str]:
        return InsightGenerator(self.store.load()).generate()

    def daily_stats(self) -> dict:
        """今日统计。"""
        now = time.time()
        start_of_day = now - (now % 86400) + time.timezone  # 近似当天 0 点
        today = [r for r in self.store.load() if r.timestamp >= start_of_day]
        return {
            "breaks": len([r for r in today if r.completed]),
            "snoozes": len([r for r in today if r.snoozed]),
            "total_screen_minutes": round(sum(r.screen_minutes for r in today), 1),
            "best_interval": self.bandit.best_interval(),
            "fatigue_training_samples": self.fatigue.train_count,
        }

    # ---- 持久化 ----
    def _save(self) -> None:
        MODEL_PATH.parent.mkdir(parents=True, exist_ok=True)
        data = {
            "fatigue": self.fatigue.to_dict(),
            "bandit": self.bandit.to_dict(),
            "snooze_streak": self._snooze_streak,
        }
        try:
            MODEL_PATH.write_text(json.dumps(data, ensure_ascii=False, indent=2), encoding="utf-8")
        except OSError:
            pass

    def _load(self) -> None:
        if not MODEL_PATH.exists():
            return
        try:
            data = json.loads(MODEL_PATH.read_text(encoding="utf-8"))
            self.fatigue = FatigueModel.from_dict(data.get("fatigue", {}))
            self.bandit = AdaptiveIntervalBandit.from_dict(data.get("bandit", {}))
            self._snooze_streak = int(data.get("snooze_streak", 0))
        except (json.JSONDecodeError, OSError):
            pass
