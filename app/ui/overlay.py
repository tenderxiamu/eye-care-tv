from __future__ import annotations

import ctypes
import sys
from pathlib import Path

from PySide6.QtCore import QObject, QTimer, Qt, QUrl, Signal
from PySide6.QtGui import QGuiApplication, QKeyEvent, QScreen
from PySide6.QtMultimedia import QAudioOutput, QMediaPlayer
from PySide6.QtMultimediaWidgets import QVideoWidget
from PySide6.QtWidgets import (
    QHBoxLayout,
    QLabel,
    QPushButton,
    QVBoxLayout,
    QWidget,
)

from app.storage.config import AppConfig

ES_CONTINUOUS = 0x80000000
ES_SYSTEM_REQUIRED = 0x00000001
ES_DISPLAY_REQUIRED = 0x00000002

FALLBACK_SECONDS = 300


def keep_awake() -> None:
    ctypes.windll.kernel32.SetThreadExecutionState(
        ES_CONTINUOUS | ES_DISPLAY_REQUIRED | ES_SYSTEM_REQUIRED
    )


def allow_sleep() -> None:
    ctypes.windll.kernel32.SetThreadExecutionState(ES_CONTINUOUS)


def resource_video_path() -> str:
    if hasattr(sys, "_MEIPASS"):
        base = Path(sys._MEIPASS) / "app" / "resources"
    else:
        base = Path(__file__).resolve().parents[1] / "resources"
    return str(base / "default_eye_exercise.mp4")


def format_duration(seconds: float) -> str:
    total = int(seconds)
    hours, rem = divmod(total, 3600)
    minutes, sec = divmod(rem, 60)
    if hours:
        return f"{hours} 小时 {minutes} 分钟"
    if minutes:
        return f"{minutes} 分钟"
    return f"{sec} 秒"


class OverlayWindow(QWidget):
    """单个屏幕上的全屏提醒窗口。"""

    snoozed = Signal()
    finished = Signal()

    def __init__(
        self,
        screen: QScreen,
        video_path: str,
        volume: int,
        with_video: bool,
    ) -> None:
        super().__init__(
            None,
            Qt.WindowType.Window
            | Qt.WindowType.FramelessWindowHint
            | Qt.WindowType.WindowStaysOnTopHint,
        )
        self._with_video = with_video
        self._video_path = video_path
        self._volume = volume
        self._done = False
        self._fallback_left = FALLBACK_SECONDS
        self._player: QMediaPlayer | None = None
        self._audio: QAudioOutput | None = None
        self._video: QVideoWidget | None = None

        self.setWindowTitle("护眼时间")
        self.setGeometry(screen.geometry())
        self.setStyleSheet(
            """
            QWidget { background: #0B0B0C; }
            QLabel#title { color: #FFFFFF; font-size: 28px; font-weight: 600; }
            QLabel#hint { color: #A1A1AA; font-size: 18px; }
            QPushButton {
                color: #FFFFFF; background: #1F2937; border: 1px solid #374151;
                border-radius: 8px; padding: 10px 24px; font-size: 16px;
            }
            QPushButton:hover { background: #374151; }
            """
        )

        layout = QVBoxLayout(self)
        layout.setContentsMargins(48, 32, 48, 32)
        layout.setSpacing(16)

        self._title = QLabel("")
        self._title.setObjectName("title")
        self._title.setAlignment(Qt.AlignmentFlag.AlignCenter)
        self._title.setWordWrap(True)
        layout.addWidget(self._title)

        if with_video:
            self._video = QVideoWidget(self)
            self._video.setMinimumHeight(360)
            layout.addWidget(self._video, 1)
        else:
            spacer = QLabel("请到主屏幕完成眼保健操")
            spacer.setObjectName("hint")
            spacer.setAlignment(Qt.AlignmentFlag.AlignCenter)
            layout.addWidget(spacer, 1)

        self._hint = QLabel("")
        self._hint.setObjectName("hint")
        self._hint.setAlignment(Qt.AlignmentFlag.AlignCenter)
        layout.addWidget(self._hint)

        button_row = QHBoxLayout()
        button_row.addStretch(1)
        self._snooze_button = QPushButton("5 分钟后再提醒")
        self._snooze_button.clicked.connect(self._on_snooze)
        button_row.addWidget(self._snooze_button)
        layout.addLayout(button_row)

    def start(self, seconds: float, allow_snooze: bool) -> None:
        if seconds >= 60:
            text = (
                f"护眼时间到，您的屏幕使用时长已达 {format_duration(seconds)}，"
                "来和我一起做眼保健操吧"
            )
        else:
            text = "护眼时间到，来和我一起做眼保健操吧"
        self._title.setText(text)
        self._snooze_button.setVisible(allow_snooze)
        if self._with_video:
            self._start_video()
        else:
            self._hint.setText("眼保健操进行中，请稍候")
        self.showFullScreen()

    def _start_video(self) -> None:
        self._player = QMediaPlayer(self)
        self._audio = QAudioOutput(self)
        self._audio.setVolume(self._volume / 100.0)
        self._player.setAudioOutput(self._audio)
        self._player.setVideoOutput(self._video)
        self._player.mediaStatusChanged.connect(self._on_media_status)
        self._player.errorOccurred.connect(self._on_error)
        self._player.setSource(QUrl.fromLocalFile(self._video_path))
        self._player.play()
        QTimer.singleShot(6000, self._check_started)

    def _check_started(self) -> None:
        if self._done or self._player is None:
            return
        if self._player.playbackState() != QMediaPlayer.PlaybackState.PlayingState:
            self._enter_fallback()

    def _on_media_status(self, status: QMediaPlayer.MediaStatus) -> None:
        if status == QMediaPlayer.MediaStatus.EndOfMedia:
            self._finish()

    def _on_error(self, error, message: str) -> None:
        if error != QMediaPlayer.Error.NoError:
            self._enter_fallback()

    def _enter_fallback(self) -> None:
        if self._done:
            return
        if self._player is not None:
            self._player.stop()
        if self._video is not None:
            self._video.hide()
        self._hint.setText(f"视频未能播放，请闭眼休息 {self._remaining_text()}")
        timer = QTimer(self)
        timer.setInterval(1000)
        timer.timeout.connect(lambda: self._tick_fallback(timer))
        timer.start()

    def _tick_fallback(self, timer: QTimer) -> None:
        self._fallback_left -= 1
        if self._fallback_left <= 0:
            timer.stop()
            self._finish()
            return
        self._hint.setText(f"视频未能播放，请闭眼休息 {self._remaining_text()}")

    def _remaining_text(self) -> str:
        minutes, seconds = divmod(max(self._fallback_left, 0), 60)
        return f"{minutes:02d}:{seconds:02d}"

    def _on_snooze(self) -> None:
        if self._done:
            return
        self._done = True
        if self._player is not None:
            self._player.stop()
        self.snoozed.emit()

    def _finish(self) -> None:
        if self._done:
            return
        self._done = True
        self.finished.emit()

    def force_close(self) -> None:
        self._done = True
        if self._player is not None:
            self._player.stop()
        self.close()

    def closeEvent(self, event) -> None:
        if not self._done:
            event.ignore()
            return
        event.accept()

    def keyPressEvent(self, event: QKeyEvent) -> None:
        # 屏蔽 Esc，避免用户随手退出提醒
        event.ignore()


class OverlayManager(QObject):
    finished = Signal()
    snoozed = Signal()

    def __init__(self, config: AppConfig, parent: QObject | None = None) -> None:
        super().__init__(parent)
        self._config = config
        self._windows: list[OverlayWindow] = []
        self._active = False

    def update_config(self, config: AppConfig) -> None:
        self._config = config

    def _current_video(self) -> str:
        chosen = self._config.video_path
        if chosen and Path(chosen).exists():
            return chosen
        return resource_video_path()

    def show_reminder(self, seconds: float, allow_snooze: bool) -> None:
        if self._active:
            return
        self._active = True
        keep_awake()

        primary = QGuiApplication.primaryScreen()
        for screen in QGuiApplication.screens():
            window = OverlayWindow(
                screen,
                self._current_video(),
                self._config.volume,
                with_video=screen is primary,
            )
            window.start(seconds, allow_snooze)
            if screen is primary:
                window.finished.connect(self._on_finished)
                window.snoozed.connect(self._on_snoozed)
            self._windows.append(window)

    def _on_snoozed(self) -> None:
        self._close_all()
        self.snoozed.emit()

    def _on_finished(self) -> None:
        self._close_all()
        self.finished.emit()

    def _close_all(self) -> None:
        for window in self._windows:
            window.force_close()
        self._windows.clear()
        self._active = False
        allow_sleep()
