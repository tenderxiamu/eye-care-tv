from __future__ import annotations

import ctypes
import os
import sys
import traceback
from pathlib import Path

from PySide6.QtNetwork import QLocalServer, QLocalSocket
from PySide6.QtWidgets import QApplication, QSystemTrayIcon

from app.core.smart_engine import SmartEngine
from app.core.timer_engine import TimerEngine
from app.storage.config import load_config, save_config
from app.ui.overlay import OverlayManager
from app.ui.report import ReportWindow
from app.ui.settings import SettingsWindow
from app.ui.tray import TrayManager

SERVER_NAME = "EyeCareTV-single-instance"
_MUTEX_NAME = "Global\\EyeCareTVSingleInstanceMutex"
_ERROR_ALREADY_EXISTS = 183


def _boot_log(message: str) -> None:
    """仅在启动异常时落盘，方便排查打包后无控制台的问题。"""
    try:
        path = Path(os.environ.get("APPDATA", ".")) / "EyeCareTV" / "boot.log"
        path.parent.mkdir(parents=True, exist_ok=True)
        with open(path, "a", encoding="utf-8") as fh:
            fh.write(message + "\n")
    except OSError:
        pass


def _acquire_instance_mutex() -> tuple[int, bool]:
    """用 Windows 互斥量保证单实例。打包后 QLocalSocket 探测不可靠，互斥量更稳。"""
    kernel32 = ctypes.windll.kernel32
    kernel32.SetLastError(0)
    handle = kernel32.CreateMutexW(None, False, _MUTEX_NAME)
    if not handle:
        return 0, False
    return handle, kernel32.GetLastError() == _ERROR_ALREADY_EXISTS


def _notify_existing_instance() -> None:
    """尽力通知已运行的实例弹出设置页，失败也无妨。"""
    socket = QLocalSocket()
    socket.connectToServer(SERVER_NAME)
    if socket.waitForConnected(300):
        socket.write(b"show")
        socket.waitForBytesWritten(200)
        socket.disconnectFromServer()


def main() -> int:
    app = QApplication(sys.argv)
    app.setApplicationName("护眼TV")
    # 关闭设置页后应用继续驻留托盘
    app.setQuitOnLastWindowClosed(False)

    # 互斥量句柄必须活到进程结束，进程退出时系统自动释放
    _mutex, already_running = _acquire_instance_mutex()
    if already_running:
        _notify_existing_instance()
        return 0

    QLocalServer.removeServer(SERVER_NAME)
    server = QLocalServer()
    server.listen(SERVER_NAME)

    config = load_config()
    engine = TimerEngine(config)
    overlay = OverlayManager(config)
    settings = SettingsWindow(config)
    smart = SmartEngine()
    tray = TrayManager(app, engine, overlay, settings, config, smart)

    report_window: ReportWindow | None = None

    def show_report() -> None:
        nonlocal report_window
        accumulated_min = engine.accumulated / 60.0
        if report_window is None:
            report_window = ReportWindow(smart, accumulated_min)
            report_window.show()
        else:
            report_window.show_and_raise(accumulated_min)

    def on_config_saved() -> None:
        overlay.update_config(config)
        tray.refresh_autostart()

    def on_new_connection() -> None:
        while server.hasPendingConnections():
            client = server.nextPendingConnection()
            client.disconnectFromServer()
        settings.show_and_raise()

    # ---- AI 引擎信号接线 ----
    # 每秒心跳：累计活跃/空闲时间
    engine.tick_detail.connect(smart.record_tick)

    def on_reminder_triggered(accumulated: float, allow_snooze: bool) -> None:
        smart.on_reminder_triggered(accumulated / 60.0)
        # 生成个性化建议
        tip = ""
        if config.smart_enabled:
            insights = smart.insights()
            if insights:
                tip = insights[0]
        overlay.show_reminder(accumulated, allow_snooze, tip)

    def on_snoozed() -> None:
        smart.on_snooze()
        engine.snooze()

    def on_finished() -> None:
        smart.on_completed(config.remind_minutes)
        engine.reminder_finished()
        # 自适应模式：根据学习结果更新提醒间隔
        if config.smart_enabled and config.smart_mode == "adaptive":
            suggested = smart.suggested_interval(config.remind_minutes)
            if suggested != config.remind_minutes:
                config.remind_minutes = suggested
                save_config(config)

    engine.remind_requested.connect(on_reminder_triggered)
    overlay.snoozed.connect(on_snoozed)
    overlay.finished.connect(on_finished)
    settings.config_saved.connect(on_config_saved)
    settings.report_requested.connect(show_report)
    server.newConnection.connect(on_new_connection)

    if not QSystemTrayIcon.isSystemTrayAvailable():
        settings.show_and_raise()

    engine.start()
    return app.exec()


if __name__ == "__main__":
    try:
        sys.exit(main())
    except Exception:
        _boot_log("boot: exception\n" + traceback.format_exc())
        raise
