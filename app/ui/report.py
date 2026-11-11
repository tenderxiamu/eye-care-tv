"""AI 护眼报告对话框。

展示今日用眼数据、疲劳指数、AI 洞察与学习到的最优提醒间隔。
"""

from __future__ import annotations

from PySide6.QtCore import Qt
from PySide6.QtGui import QColor, QFont, QPainter, QPen
from PySide6.QtWidgets import (
    QFrame,
    QHBoxLayout,
    QLabel,
    QPushButton,
    QScrollArea,
    QVBoxLayout,
    QWidget,
)

from app.core.smart_engine import SmartEngine


def _fatigue_color(score: float) -> str:
    if score < 30:
        return "#16A34A"  # 绿
    if score < 60:
        return "#F59E0B"  # 黄
    return "#EF4444"  # 红


def _fatigue_label(score: float) -> str:
    if score < 30:
        return "状态良好"
    if score < 60:
        return "轻度疲劳"
    return "需要休息"


class FatigueGauge(QWidget):
    """简单的疲劳指数半圆仪表盘。"""

    def __init__(self, score: float = 0.0) -> None:
        super().__init__()
        self._score = score
        self.setMinimumHeight(120)

    def set_score(self, score: float) -> None:
        self._score = score
        self.update()

    def paintEvent(self, event) -> None:
        painter = QPainter(self)
        painter.setRenderHint(QPainter.RenderHint.Antialiasing)
        w, h = self.width(), self.height()
        cx, cy = w / 2, h - 20
        radius = min(w / 2 - 20, h - 30)

        # 背景弧
        painter.setPen(QPen(QColor("#E4E4E7"), 14))
        painter.drawArc(int(cx - radius), int(cy - radius), int(radius * 2), int(radius * 2), 180 * 16, 180 * 16)

        # 数值弧
        ratio = max(0.0, min(1.0, self._score / 100.0))
        color = QColor(_fatigue_color(self._score))
        painter.setPen(QPen(color, 14, Qt.PenStyle.SolidLine, Qt.PenCapStyle.RoundCap))
        span = int(180 * ratio * 16)
        painter.drawArc(int(cx - radius), int(cy - radius), int(radius * 2), int(radius * 2), 180 * 16, span)

        # 数值文字
        painter.setPen(QColor("#18181B"))
        font = QFont()
        font.setPointSize(28)
        font.setBold(True)
        painter.setFont(font)
        painter.drawText(self.rect(), Qt.AlignmentFlag.AlignCenter, f"{self._score:.0f}")


class ReportWindow(QWidget):
    def __init__(self, smart: SmartEngine, accumulated_minutes: float = 0.0) -> None:
        super().__init__(None)
        self._smart = smart
        self.setWindowTitle("AI 护眼报告")
        self.setFixedWidth(520)
        self._layout = QVBoxLayout(self)
        self._layout.setContentsMargins(24, 24, 24, 24)
        self._layout.setSpacing(16)
        self._build(accumulated_minutes)

    @staticmethod
    def _clear_layout(layout) -> None:
        """清空布局并销毁其中的控件与子布局，用于刷新前重置。"""
        while layout.count():
            item = layout.takeAt(0)
            widget = item.widget()
            if widget is not None:
                widget.deleteLater()
                continue
            child = item.layout()
            if child is not None:
                ReportWindow._clear_layout(child)
                child.deleteLater()

    def _build(self, accumulated_minutes: float) -> None:
        layout = self._layout

        # 标题
        title = QLabel("🧠 AI 护眼报告")
        title.setStyleSheet("font-size: 22px; font-weight: 700; color: #18181B;")
        layout.addWidget(title)

        # 疲劳指数
        fatigue = self._smart.fatigue_score(accumulated_minutes)
        gauge_row = QHBoxLayout()
        gauge_row.addWidget(self._build_fatigue_card(fatigue), 1)
        layout.addLayout(gauge_row)

        # 今日数据卡片
        stats = self._smart.daily_stats()
        stats_row = QHBoxLayout()
        stats_row.addWidget(self._stat_card("今日休息", f"{stats['breaks']} 次", "#16A34A"))
        stats_row.addWidget(self._stat_card("今日延迟", f"{stats['snoozes']} 次", "#F59E0B"))
        stats_row.addWidget(self._stat_card("累计用屏", f"{stats['total_screen_minutes']:.0f} 分", "#3B82F6"))
        layout.addLayout(stats_row)

        # AI 学习到的最优间隔
        interval_card = QFrame()
        interval_card.setStyleSheet(
            "QFrame { background: #F0F9FF; border: 1px solid #BAE6FD; border-radius: 10px; }"
        )
        iv_layout = QVBoxLayout(interval_card)
        iv_layout.setContentsMargins(16, 12, 16, 12)
        iv_title = QLabel("🎯 AI 学习到的节奏")
        iv_title.setStyleSheet("font-size: 13px; color: #075985; font-weight: 600;")
        iv_text = QLabel(
            f"根据你的反馈，最适合你的提醒间隔约为 <b>{stats['best_interval']} 分钟</b>"
            f"（已学习 {stats['fatigue_training_samples']} 次）"
        )
        iv_text.setStyleSheet("font-size: 13px; color: #0C4A6E;")
        iv_text.setWordWrap(True)
        iv_layout.addWidget(iv_title)
        iv_layout.addWidget(iv_text)
        layout.addWidget(interval_card)

        # AI 洞察
        insights_title = QLabel("💡 AI 洞察")
        insights_title.setStyleSheet("font-size: 15px; font-weight: 600; color: #18181B; margin-top: 4px;")
        layout.addWidget(insights_title)

        scroll = QScrollArea()
        scroll.setWidgetResizable(True)
        scroll.setStyleSheet("QScrollArea { border: none; }")
        insights_widget = QWidget()
        insights_layout = QVBoxLayout(insights_widget)
        insights_layout.setSpacing(8)

        for insight in self._smart.insights():
            label = QLabel(f"• {insight}")
            label.setStyleSheet("font-size: 13px; color: #3F3F46; padding: 8px 12px; background: #F4F4F5; border-radius: 8px;")
            label.setWordWrap(True)
            insights_layout.addWidget(label)

        insights_layout.addStretch(1)
        scroll.setWidget(insights_widget)
        scroll.setMinimumHeight(140)
        layout.addWidget(scroll, 1)

        # 关闭按钮
        btn_row = QHBoxLayout()
        btn_row.addStretch(1)
        close_btn = QPushButton("关闭")
        close_btn.setFixedWidth(90)
        close_btn.setStyleSheet(
            "QPushButton { background: #18181B; color: white; border: none; "
            "border-radius: 8px; padding: 8px 16px; font-size: 13px; }"
            "QPushButton:hover { background: #3F3F46; }"
        )
        close_btn.clicked.connect(self.close)
        btn_row.addWidget(close_btn)
        layout.addLayout(btn_row)

    def _build_fatigue_card(self, score: float) -> QWidget:
        card = QFrame()
        card.setStyleSheet(
            "QFrame { background: #FAFAFA; border: 1px solid #E4E4E7; border-radius: 10px; }"
        )
        v = QVBoxLayout(card)
        v.setContentsMargins(12, 12, 12, 12)
        label = QLabel("当前眼疲劳指数")
        label.setStyleSheet("font-size: 12px; color: #71717A;")
        label.setAlignment(Qt.AlignmentFlag.AlignCenter)
        gauge = FatigueGauge(score)
        status = QLabel(_fatigue_label(score))
        status.setStyleSheet(f"font-size: 14px; font-weight: 600; color: {_fatigue_color(score)};")
        status.setAlignment(Qt.AlignmentFlag.AlignCenter)
        v.addWidget(label)
        v.addWidget(gauge)
        v.addWidget(status)
        return card

    def _stat_card(self, title: str, value: str, color: str) -> QWidget:
        card = QFrame()
        card.setStyleSheet(
            "QFrame { background: #FAFAFA; border: 1px solid #E4E4E7; border-radius: 10px; }"
        )
        v = QVBoxLayout(card)
        v.setContentsMargins(12, 12, 12, 12)
        t = QLabel(title)
        t.setStyleSheet("font-size: 12px; color: #71717A;")
        val = QLabel(value)
        val.setStyleSheet(f"font-size: 18px; font-weight: 700; color: {color};")
        v.addWidget(t)
        v.addWidget(val)
        return card

    def show_and_raise(self, accumulated_minutes: float) -> None:
        # 清空旧内容后重新构建，刷新今日数据与疲劳指数
        self._clear_layout(self._layout)
        self._build(accumulated_minutes)
        self.show()
        self.raise_()
        self.activateWindow()
