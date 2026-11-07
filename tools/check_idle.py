from __future__ import annotations

import time

from app.core.idle_detector import idle_seconds


if __name__ == "__main__":
    while True:
        print(f"\r空闲 {idle_seconds():8.1f} 秒", end="", flush=True)
        time.sleep(0.5)
