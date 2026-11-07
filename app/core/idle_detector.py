from __future__ import annotations

import ctypes
from ctypes import wintypes


class _LastInputInfo(ctypes.Structure):
    _fields_ = [("cbSize", wintypes.UINT), ("dwTime", wintypes.DWORD)]


def idle_seconds() -> float:
    """返回距最后一次键盘或鼠标输入的秒数。

    Windows 的 GetLastInputInfo 返回最后一次输入的系统 tick，
    锁屏、挂机、离开电脑时该值会持续增长，天然可用于判断空闲。
    """
    info = _LastInputInfo()
    info.cbSize = ctypes.sizeof(_LastInputInfo)
    if not ctypes.windll.user32.GetLastInputInfo(ctypes.byref(info)):
        return 0.0
    ticks = ctypes.windll.kernel32.GetTickCount()
    # GetTickCount 是 32 位，约 49.7 天回绕，用掩码保证差值正确
    delta = (ticks - info.dwTime) & 0xFFFFFFFF
    return delta / 1000.0
