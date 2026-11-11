from __future__ import annotations

import shutil
from pathlib import Path

from PySide6.QtCore import QTime, Qt, Signal
from PySide6.QtWidgets import (
    QCheckBox,
    QFileDialog,
    QFormLayout,
    QHBoxLayout,
    QLabel,
    QPushButton,
    QSlider,
    QSpinBox,
    QTimeEdit,
    QVBoxLayout,
    QWidget,
)

from app.core.autostart import set_auto_start
from app.storage.config import AppConfig, MEDIA_DIR, save_config


class SettingsWindow(QWidget):
    config_saved = Signal()
    report_requested = Signal()

    def __init__(self, config: AppConfig) -> None:
        super().__init__(None)
        self._config = config
        self.setWindowTitle("护眼TV 设置")
        self.setFixedWidth(480)
        self._build()
        self._load_from_config()

    def _build(self) -> None:
        layout = QVBoxLayout(self)
        layout.setContentsMargins(24, 24, 24, 24)
        layout.setSpacing(16)

        form = QFormLayout()
        form.setSpacing(12)

        self._remind_spin = QSpinBox()
        self._remind_spin.setRange(5, 180)
        self._remind_spin.setSuffix(" 分钟")
        form.addRow("提醒间隔", self._remind_spin)

        self._idle_spin = QSpinBox()
        self._idle_spin.setRange(1, 15)
        self._idle_spin.setSuffix(" 分钟")
        form.addRow("空闲暂停阈值", self._idle_spin)

        self._volume_slider = QSlider(Qt.Orientation.Horizontal)
        self._volume_slider.setRange(0, 100)
        form.addRow("提醒音量", self._volume_slider)

        self._video_row = QWidget()
        video_layout = QHBoxLayout(self._video_row)
        video_layout.setContentsMargins(0, 0, 0, 0)
        self._video_name = QLabel("")
        self._video_name.setStyleSheet("color: #52525B;")
        choose_button = QPushButton("选择视频")
        choose_button.clicked.connect(self._choose_video)
        reset_button = QPushButton("恢复默认")
        reset_button.clicked.connect(self._reset_video)
        video_layout.addWidget(self._video_name, 1)
        video_layout.addWidget(choose_button)
        video_layout.addWidget(reset_button)
        form.addRow("眼保健操视频", self._video_row)

        self._dnd_check = QCheckBox("启用免打扰时段")
        form.addRow("", self._dnd_check)

        self._dnd_start = QTimeEdit()
        self._dnd_start.setDisplayFormat("HH:mm")
        self._dnd_end = QTimeEdit()
        self._dnd_end.setDisplayFormat("HH:mm")
        dnd_row = QWidget()
        dnd_layout = QHBoxLayout(dnd_row)
        dnd_layout.setContentsMargins(0, 0, 0, 0)
        dnd_layout.addWidget(self._dnd_start)
        dnd_layout.addWidget(QLabel("至"))
        dnd_layout.addWidget(self._dnd_end)
        dnd_layout.addStretch(1)
        form.addRow("免打扰时间", dnd_row)

        self._autostart_check = QCheckBox("开机自启")
        form.addRow("", self._autostart_check)

        self._smart_check = QCheckBox("启用 AI 智能护眼（自适应间隔 + 疲劳预测）")
        self._smart_check.toggled.connect(self._on_smart_toggled)
        form.addRow("", self._smart_check)

        self._smart_mode_row = QWidget()
        smart_mode_layout = QHBoxLayout(self._smart_mode_row)
        smart_mode_layout.setContentsMargins(0, 0, 0, 0)
        self._smart_fixed = QPushButton("固定间隔")
        self._smart_fixed.setCheckable(True)
        self._smart_adaptive = QPushButton("AI 自适应")
        self._smart_adaptive.setCheckable(True)
        self._smart_fixed.clicked.connect(lambda: self._set_smart_mode("fixed"))
        self._smart_adaptive.clicked.connect(lambda: self._set_smart_mode("adaptive"))
        smart_mode_layout.addWidget(self._smart_fixed)
        smart_mode_layout.addWidget(self._smart_adaptive)
        smart_mode_layout.addStretch(1)
        form.addRow("提醒策略", self._smart_mode_row)

        layout.addLayout(form)

        # AI 报告入口
        report_row = QHBoxLayout()
        report_row.addStretch(1)
        self._report_button = QPushButton("📊 查看 AI 护眼报告")
        self._report_button.setStyleSheet(
            "QPushButton { background: #EFF6FF; color: #1D4ED8; border: 1px solid #BFDBFE; "
            "border-radius: 8px; padding: 8px 16px; font-size: 13px; }"
            "QPushButton:hover { background: #DBEAFE; }"
        )
        report_row.addWidget(self._report_button)
        layout.addLayout(report_row)
        self._report_button.clicked.connect(self.report_requested.emit)

        hint = QLabel("到达提醒时间后会全屏播放视频，播完后自动恢复计时。")
        hint.setWordWrap(True)
        hint.setStyleSheet("color: #52525B;")
        layout.addWidget(hint)

        buttons = QHBoxLayout()
        buttons.addStretch(1)
        close_button = QPushButton("关闭")
        close_button.clicked.connect(self.close)
        save_button = QPushButton("保存")
        save_button.clicked.connect(self._save)
        buttons.addWidget(close_button)
        buttons.addWidget(save_button)
        layout.addLayout(buttons)

    def _load_from_config(self) -> None:
        cfg = self._config
        self._remind_spin.setValue(cfg.remind_minutes)
        self._idle_spin.setValue(cfg.idle_minutes)
        self._volume_slider.setValue(cfg.volume)
        self._dnd_check.setChecked(cfg.dnd_enabled)
        self._dnd_start.setTime(QTime.fromString(cfg.dnd_start, "HH:mm"))
        self._dnd_end.setTime(QTime.fromString(cfg.dnd_end, "HH:mm"))
        self._autostart_check.setChecked(cfg.auto_start)
        self._smart_check.setChecked(cfg.smart_enabled)
        self._set_smart_mode(cfg.smart_mode)
        self._refresh_video_name()

    def _set_smart_mode(self, mode: str) -> None:
        self._smart_fixed.setChecked(mode == "fixed")
        self._smart_adaptive.setChecked(mode == "adaptive")
        self._smart_mode_row.setEnabled(self._smart_check.isChecked())

    def _on_smart_toggled(self, checked: bool) -> None:
        self._smart_mode_row.setEnabled(checked)
        if checked and not self._smart_fixed.isChecked() and not self._smart_adaptive.isChecked():
            self._smart_adaptive.setChecked(True)

    def _refresh_video_name(self) -> None:
        if self._config.video_path:
            self._video_name.setText(Path(self._config.video_path).name)
        else:
            self._video_name.setText("内置默认视频")

    def _choose_video(self) -> None:
        path, _ = QFileDialog.getOpenFileName(
            self,
            "选择眼保健操视频",
            "",
            "视频文件 (*.mp4 *.mov *.avi *.mkv)",
        )
        if not path:
            return
        MEDIA_DIR.mkdir(parents=True, exist_ok=True)
        source = Path(path)
        target = MEDIA_DIR / f"user_video{source.suffix.lower()}"
        shutil.copy2(source, target)
        self._config.video_path = str(target)
        self._refresh_video_name()

    def _reset_video(self) -> None:
        self._config.video_path = ""
        self._refresh_video_name()

    def _save(self) -> None:
        cfg = self._config
        cfg.remind_minutes = self._remind_spin.value()
        cfg.idle_minutes = self._idle_spin.value()
        cfg.volume = self._volume_slider.value()
        cfg.dnd_enabled = self._dnd_check.isChecked()
        cfg.dnd_start = self._dnd_start.time().toString("HH:mm")
        cfg.dnd_end = self._dnd_end.time().toString("HH:mm")
        cfg.auto_start = self._autostart_check.isChecked()
        cfg.smart_enabled = self._smart_check.isChecked()
        cfg.smart_mode = "adaptive" if self._smart_adaptive.isChecked() else "fixed"
        save_config(cfg)
        set_auto_start(cfg.auto_start)
        self.config_saved.emit()

    def show_and_raise(self) -> None:
        self._load_from_config()
        self.show()
        self.raise_()
        self.activateWindow()
