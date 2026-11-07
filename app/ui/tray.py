from __future__ import annotations

from PySide6.QtCore import QObject, QTimer, Qt
from PySide6.QtGui import QColor, QIcon, QPainter, QPixmap
from PySide6.QtWidgets import QApplication, QMenu, QSystemTrayIcon

from app.core.autostart import set_auto_start
from app.core.timer_engine import State, TimerEngine
from app.storage.config import AppConfig, save_config
from app.ui.overlay import OverlayManager, format_duration
from app.ui.settings import SettingsWindow


def _build_icon() -> QIcon:
    pixmap = QPixmap(64, 64)
    pixmap.fill(Qt.GlobalColor.transparent)
    painter = QPainter(pixmap)
    painter.setRenderHint(QPainter.RenderHint.Antialiasing)
    painter.setPen(Qt.PenStyle.NoPen)
    painter.setBrush(QColor("#0FDC78"))
    painter.drawEllipse(6, 16, 52, 32)
    painter.setBrush(QColor("#004D26"))
    painter.drawEllipse(22, 24, 20, 16)
    painter.end()
    return QIcon(pixmap)


class TrayManager(QObject):
    def __init__(
        self,
        app: QApplication,
        engine: TimerEngine,
        overlay: OverlayManager,
        settings: SettingsWindow,
        config: AppConfig,
    ) -> None:
        super().__init__(app)
        self._app = app
        self._engine = engine
        self._overlay = overlay
        self._settings = settings
        self._config = config

        self._tray = QSystemTrayIcon(_build_icon(), app)
        self._tray.setToolTip("护眼TV")

        menu = QMenu()
        self._status_action = menu.addAction("剩余 --")
        self._status_action.setEnabled(False)
        menu.addSeparator()

        settings_action = menu.addAction("打开设置")
        settings_action.triggered.connect(self._settings.show_and_raise)

        rest_action = menu.addAction("立即休息")
        rest_action.triggered.connect(self._engine.force_reminder)

        self._pause_action = menu.addAction("暂停 30 分钟")
        self._pause_action.triggered.connect(self._toggle_pause)

        self._autostart_action = menu.addAction("开机自启")
        self._autostart_action.setCheckable(True)
        self._autostart_action.setChecked(config.auto_start)
        self._autostart_action.triggered.connect(self._toggle_autostart)

        menu.addSeparator()
        quit_action = menu.addAction("退出")
        quit_action.triggered.connect(app.quit)

        self._tray.setContextMenu(menu)
        self._tray.show()

        self._refresh_timer = QTimer(self)
        self._refresh_timer.setInterval(1000)
        self._refresh_timer.timeout.connect(self._refresh_status)
        self._refresh_timer.start()

    def refresh_autostart(self) -> None:
        self._autostart_action.setChecked(self._config.auto_start)

    def _refresh_status(self) -> None:
        state = self._engine.state
        if state == State.TRACKING:
            self._status_action.setText(f"剩余 {format_duration(self._engine.remaining)}")
            self._pause_action.setText("暂停 30 分钟")
        elif state == State.PAUSED:
            self._status_action.setText("已暂停计时")
            self._pause_action.setText("恢复计时")
        elif state == State.PENDING:
            self._status_action.setText("免打扰中，提醒已挂起")
            self._pause_action.setText("暂停 30 分钟")
        elif state == State.REMINDING:
            self._status_action.setText("提醒播放中")
            self._pause_action.setText("暂停 30 分钟")
        else:
            self._status_action.setText("已延后，5 分钟后再次提醒")
            self._pause_action.setText("暂停 30 分钟")

    def _toggle_pause(self) -> None:
        if self._engine.state == State.PAUSED:
            self._engine.resume()
        else:
            self._engine.pause_for(30)
        self._refresh_status()

    def _toggle_autostart(self, checked: bool) -> None:
        self._config.auto_start = checked
        save_config(self._config)
        set_auto_start(checked)
