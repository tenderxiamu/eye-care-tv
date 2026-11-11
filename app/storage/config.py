from __future__ import annotations

import json
import os
from dataclasses import asdict, dataclass
from pathlib import Path

APP_DIR = Path(os.environ.get("APPDATA", Path.home())) / "EyeCareTV"
CONFIG_PATH = APP_DIR / "config.json"
MEDIA_DIR = APP_DIR / "media"


@dataclass
class AppConfig:
    """所有可调参数。首次启动写入默认值，之后以文件为准。"""

    remind_minutes: int = 30
    idle_minutes: int = 3
    snooze_minutes: int = 5
    volume: int = 50
    video_path: str = ""
    dnd_enabled: bool = False
    dnd_start: str = "12:00"
    dnd_end: str = "13:00"
    auto_start: bool = False
    smart_enabled: bool = True
    smart_mode: str = "adaptive"  # "fixed" 或 "adaptive"

    @property
    def remind_seconds(self) -> float:
        return float(self.remind_minutes * 60)

    @property
    def idle_seconds(self) -> float:
        return float(self.idle_minutes * 60)


def load_config() -> AppConfig:
    APP_DIR.mkdir(parents=True, exist_ok=True)
    if not CONFIG_PATH.exists():
        cfg = AppConfig()
        save_config(cfg)
        return cfg
    try:
        data = json.loads(CONFIG_PATH.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError):
        return AppConfig()
    fields = set(AppConfig().__dict__)
    return AppConfig(**{k: v for k, v in data.items() if k in fields})


def save_config(cfg: AppConfig) -> None:
    APP_DIR.mkdir(parents=True, exist_ok=True)
    CONFIG_PATH.write_text(
        json.dumps(asdict(cfg), ensure_ascii=False, indent=2),
        encoding="utf-8",
    )
