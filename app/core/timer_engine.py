from __future__ import annotations

import time
from enum import Enum

from PySide6.QtCore import QObject, QTimer, Signal

from app.core.idle_detector import idle_seconds
from app.storage.config import AppConfig


class State(str, Enum):
    TRACKING = "tracking"      # 计时中
    PAUSED = "paused"          # 空闲或手动暂停
    PENDING = "pending"        # 到点但处于免打扰，挂起
    REMINDING = "reminding"    # 全屏播放中
    SNOOZED = "snoozed"        # 延后等待


class TimerEngine(QObject):
    usage_changed = Signal(float)
    remind_requested = Signal(float, bool)
    state_changed = Signal(str)
    tick_detail = Signal(float, float)  # (active_seconds, idle_seconds)

    def __init__(
        self,
        config: AppConfig,
        parent: QObject | None = None,
        idle_provider=idle_seconds,
        clock=time.monotonic,
    ) -> None:
        super().__init__(parent)
        self._config = config
        self._idle_provider = idle_provider
        self._clock = clock
        self._state = State.TRACKING
        self._accumulated = 0.0
        self._last_tick = clock()
        self._snooze_used = 0
        self._snooze_deadline = 0.0
        self._paused_until = 0.0
        self._timer = QTimer(self)
        self._timer.setInterval(1000)
        self._timer.timeout.connect(self.tick)

    # ---- 生命周期 ----
    def start(self) -> None:
        self._last_tick = self._clock()
        self._timer.start()

    def stop(self) -> None:
        self._timer.stop()

    # ---- 状态查询 ----
    @property
    def state(self) -> State:
        return self._state

    @property
    def accumulated(self) -> float:
        return self._accumulated

    @property
    def remaining(self) -> float:
        return max(self._config.remind_seconds - self._accumulated, 0.0)

    # ---- 外部操作 ----
    def pause_for(self, minutes: int) -> None:
        self._paused_until = self._clock() + minutes * 60
        self._set_state(State.PAUSED)

    def resume(self) -> None:
        self._paused_until = 0.0
        self._set_state(State.TRACKING)

    def snooze(self) -> bool:
        if self._state != State.REMINDING or self._snooze_used >= 1:
            return False
        self._snooze_used += 1
        self._snooze_deadline = self._clock() + self._config.snooze_minutes * 60
        self._set_state(State.SNOOZED)
        return True

    def reminder_finished(self) -> None:
        self._accumulated = 0.0
        self._snooze_used = 0
        self._last_tick = self._clock()
        self._set_state(State.TRACKING)
        self.usage_changed.emit(0.0)

    def force_reminder(self) -> None:
        self._set_state(State.REMINDING)
        self.remind_requested.emit(self._accumulated, self._snooze_used < 1)

    # ---- 心跳 ----
    def tick(self) -> None:
        now = self._clock()
        delta = now - self._last_tick
        self._last_tick = now
        if delta <= 0:
            return
        # 休眠唤醒后可能出现巨大的时间差，钳制避免一次性跳过
        delta = min(delta, 2.0)

        active = 0.0
        idle = 0.0

        if self._state == State.TRACKING:
            if self._idle_provider() >= self._config.idle_seconds:
                idle = delta
                self._set_state(State.PAUSED)
                self.tick_detail.emit(active, idle)
                return
            active = delta
            self._accumulated += delta
            self.usage_changed.emit(self._accumulated)
            if self._accumulated >= self._config.remind_seconds:
                self._trigger()
        elif self._state == State.PAUSED:
            idle = delta
            if self._paused_until:
                if now >= self._paused_until:
                    self._paused_until = 0.0
                    self._set_state(State.TRACKING)
                self.tick_detail.emit(active, idle)
                return
            if self._idle_provider() < self._config.idle_seconds:
                self._set_state(State.TRACKING)
        elif self._state == State.PENDING:
            idle = delta
            if not self._is_dnd():
                self._trigger()
        elif self._state == State.SNOOZED:
            idle = delta
            if now >= self._snooze_deadline:
                self._trigger()
        elif self._state == State.REMINDING:
            idle = delta

        self.tick_detail.emit(active, idle)

    # ---- 内部 ----
    def _is_dnd(self) -> bool:
        from app.core.dnd import is_dnd_active

        return is_dnd_active(self._config)

    def _trigger(self) -> None:
        if self._state == State.TRACKING and self._is_dnd():
            self._set_state(State.PENDING)
            return
        self._set_state(State.REMINDING)
        self.remind_requested.emit(self._accumulated, self._snooze_used < 1)

    def _set_state(self, new_state: State) -> None:
        if new_state == self._state:
            return
        self._state = new_state
        self.state_changed.emit(new_state.value)
