from __future__ import annotations

from datetime import datetime, time

from app.storage.config import AppConfig


def _parse_clock(value: str) -> time:
    hour, minute = value.split(":")
    return time(int(hour), int(minute))


def is_dnd_active(cfg: AppConfig, now: datetime | None = None) -> bool:
    """判断当前是否处于免打扰时段，支持跨午夜的时间段。"""
    if not cfg.dnd_enabled:
        return False
    current = (now or datetime.now()).time()
    start = _parse_clock(cfg.dnd_start)
    end = _parse_clock(cfg.dnd_end)
    if start <= end:
        return start <= current < end
    return current >= start or current < end
