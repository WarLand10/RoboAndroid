# ============================================================
# ROBO AI - Gemini Live + Arduino + Scoped Project Assistant
# Project-only file access, user approval, backups and restore UI
# ============================================================

from __future__ import annotations

import asyncio
import ast
import concurrent.futures
import difflib
import hashlib
import json
import os
import queue
import re
import shutil
import tempfile
import threading
import time
import tkinter as tk
from dataclasses import dataclass
from datetime import datetime
from pathlib import Path
from tkinter import ttk, messagebox
from typing import Optional

import pyaudio
import serial
import serial.tools.list_ports
from google import genai
from google.genai import types

import config


# ============================================================
# PATHS / CONSTANTS
# ============================================================

PROJECT_ROOT = Path(__file__).resolve().parent
BACKUP_ROOT = PROJECT_ROOT / "_RoboBackups"
MAX_TEXT_FILE_BYTES = 500_000
MAX_TOOL_RESULT_CHARS = 30_000
MAX_CHANGE_BYTES = 500_000
MAX_MOVE_SECONDS = 5.0
OUTPUT_SAMPLE_RATE = 24000
OUTPUT_CHANNELS = 1
OUTPUT_CHUNK = 2048

TEXT_EXTENSIONS = {
    ".py", ".ino", ".txt", ".md", ".bat", ".cmd", ".json",
    ".conf", ".yaml", ".yml", ".toml", ".ini", ".cfg", ".html",
    ".css", ".js", ".xml", ".csv", ".ps1", ".sql"
}
TEXT_FILENAMES = {"README", "LICENSE", "Makefile", "Dockerfile"}
IGNORED_DIRS = {"__pycache__", ".git", ".venv", "venv", "_RoboBackups"}


def gui_event(event_type, data=None):
    gui_queue.put((event_type, data))


gui_queue: queue.Queue = queue.Queue()
project_change_lock = threading.RLock()


@dataclass
class ChangeProposal:
    rel_path: str
    action: str  # write or delete
    summary: str
    new_content: Optional[str]
    expected_digest: str
    old_content_for_display: str
    new_content_for_display: str
    future: concurrent.futures.Future


# ============================================================
# PROJECT SCOPE / FILE HELPERS
# ============================================================


def sha256_bytes(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def file_digest(path: Path) -> str:
    if not path.exists():
        return "<missing>"
    if not path.is_file():
        raise ValueError("مسیر انتخاب‌شده فایل نیست.")
    return sha256_bytes(path.read_bytes())


def normalize_relative_path(raw_path: str) -> str:
    """Accept only a relative path located inside the project workspace."""
    if not isinstance(raw_path, str) or not raw_path.strip():
        raise ValueError("مسیر فایل خالی است.")
    raw = raw_path.strip().replace("\\", "/")
    if raw.startswith("/") or re.match(r"^[A-Za-z]:", raw):
        raise ValueError("فقط مسیر نسبی داخل پوشهٔ پروژه مجاز است.")
    parts = [p for p in raw.split("/") if p not in ("", ".")]
    if not parts or any(p == ".." for p in parts):
        raise ValueError("مسیر نامعتبر است.")
    if any(p.casefold() in {x.casefold() for x in IGNORED_DIRS} for p in parts):
        raise ValueError("دسترسی مستقیم به پوشهٔ کش یا پشتیبان‌ها مجاز نیست.")
    return "/".join(parts)


def resolve_project_path(raw_path: str, must_exist: bool = False) -> tuple[str, Path]:
    rel = normalize_relative_path(raw_path)
    target = (PROJECT_ROOT / Path(*rel.split("/"))).resolve(strict=False)
    root = PROJECT_ROOT.resolve()
    if target == root or root not in target.parents:
        raise ValueError("مسیر باید فقط داخل پوشهٔ E:\\Robo باشد.")
    if target == BACKUP_ROOT or BACKUP_ROOT in target.parents:
        raise ValueError("ویرایش مستقیم فایل‌های پشتیبان مجاز نیست.")
    if must_exist and not target.is_file():
        raise FileNotFoundError(f"فایل پیدا نشد: {rel}")
    if target.exists() and not target.is_file():
        raise ValueError("مسیر انتخاب‌شده یک فایل عادی نیست.")
    return rel, target


def is_text_file(path: Path) -> bool:
    return path.name in TEXT_FILENAMES or path.suffix.lower() in TEXT_EXTENSIONS


def redact_secrets(text: str) -> str:
    """Redact literal secret assignments before project text is sent to Gemini/UI."""
    secret_assignment = re.compile(
        r"(?im)^(\s*([A-Za-z_][A-Za-z0-9_]*)\s*=\s*)(['\"])(.*?)(\3\s*)$"
    )
    api_key_pattern = re.compile(r"(?i)\bAIza[0-9A-Za-z_\-]{20,}\b")

    def assignment_replacer(match):
        variable_name = match.group(2).upper()
        if any(marker in variable_name for marker in ("API_KEY", "SECRET", "TOKEN", "PASSWORD", "PASSWD")):
            return f'{match.group(1)}{match.group(3)}[REDACTED - keep secret outside source code]{match.group(5)}'
        return match.group(0)

    text = secret_assignment.sub(assignment_replacer, text)
    text = api_key_pattern.sub("[REDACTED_API_KEY]", text)
    return text


def load_text_file(path: Path) -> str:
    if not is_text_file(path):
        raise ValueError("این فایل متنی مجاز نیست یا یک فایل باینری است.")
    size = path.stat().st_size
    if size > MAX_TEXT_FILE_BYTES:
        raise ValueError(f"فایل بزرگ است ({size:,} bytes)؛ سقف خواندن {MAX_TEXT_FILE_BYTES:,} bytes است.")
    return path.read_text(encoding="utf-8", errors="replace")


def iter_project_files():
    for current_root, dirs, files in os.walk(PROJECT_ROOT):
        dirs[:] = [d for d in dirs if d not in IGNORED_DIRS]
        current = Path(current_root)
        for name in files:
            path = current / name
            try:
                resolved = path.resolve()
                if resolved == BACKUP_ROOT or BACKUP_ROOT in resolved.parents:
                    continue
                if PROJECT_ROOT.resolve() not in resolved.parents:
                    continue
                yield path
            except OSError:
                continue


def safe_config_content_for_write(rel_path: str, proposed: str, old_text: Optional[str]) -> str:
    """Avoid reintroducing a hard-coded API key when editing config.py."""
    if Path(rel_path).name.casefold() != "config.py":
        return proposed
    # In the updated setup, the key must come from the environment, not source code.
    if re.search(r"(?im)^\s*GEMINI_API_KEY\s*=\s*(['\"]).+?\1\s*$", proposed):
        raise ValueError(
            "برای ایمنی، config.py نباید کلید API ثابت داشته باشد. از os.environ استفاده کن. "
            "تغییر پیشنهادی کلید داخل کد را شامل می‌شود و اعمال نشد."
        )
    return proposed


def create_backup_snapshot(rel_path: str, reason: str) -> Path:
    """Back up one file (or record that it did not exist) before mutation."""
    _, target = resolve_project_path(rel_path, must_exist=False)
    BACKUP_ROOT.mkdir(parents=True, exist_ok=True)
    stamp = datetime.now().strftime("%Y-%m-%d_%H-%M-%S_%f")
    snapshot = BACKUP_ROOT / stamp
    snapshot.mkdir(parents=True, exist_ok=False)
    existed = target.is_file()
    backup_rel = None
    if existed:
        backup_rel = Path("files") / Path(*rel_path.split("/"))
        destination = snapshot / backup_rel
        destination.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(target, destination)
        # Ensure the backup is readable before any modification.
        if file_digest(destination) != file_digest(target):
            shutil.rmtree(snapshot, ignore_errors=True)
            raise IOError("تأیید صحت نسخهٔ پشتیبان ناموفق بود؛ فایل تغییر نکرد.")
    manifest = {
        "created_at": datetime.now().isoformat(timespec="seconds"),
        "project_root": str(PROJECT_ROOT),
        "file": rel_path,
        "reason": reason,
        "existed_before": existed,
        "backup_copy": str(backup_rel).replace("\\", "/") if backup_rel else None,
    }
    (snapshot / "manifest.json").write_text(
        json.dumps(manifest, ensure_ascii=False, indent=2), encoding="utf-8"
    )
    return snapshot


def apply_change_after_approval(proposal: ChangeProposal) -> str:
    rel, target = resolve_project_path(proposal.rel_path, must_exist=False)
    with project_change_lock:
        if file_digest(target) != proposal.expected_digest:
            return (
                "تغییر اعمال نشد؛ فایل بعد از تهیهٔ پیشنهاد عوض شده است. "
                "لطفاً دوباره فایل را بخوان و پیشنهاد جدید بده."
            )
        backup_path = create_backup_snapshot(rel, proposal.summary)
        try:
            if proposal.action == "delete":
                if not target.is_file():
                    return "فایل از قبل وجود ندارد؛ کاری انجام نشد."
                target.unlink()
            elif proposal.action == "write":
                if proposal.new_content is None:
                    raise ValueError("محتوای پیشنهادی خالی یا نامعتبر است.")
                content = safe_config_content_for_write(
                    rel, proposal.new_content, None
                )
                if len(content.encode("utf-8")) > MAX_CHANGE_BYTES:
                    raise ValueError("محتوای پیشنهادی بیش از حد بزرگ است.")
                target.parent.mkdir(parents=True, exist_ok=True)
                fd, temp_name = tempfile.mkstemp(
                    prefix=".robo_tmp_", suffix=".tmp", dir=str(target.parent)
                )
                try:
                    with os.fdopen(fd, "w", encoding="utf-8", newline="") as stream:
                        stream.write(content)
                        stream.flush()
                        os.fsync(stream.fileno())
                    os.replace(temp_name, target)
                finally:
                    try:
                        if os.path.exists(temp_name):
                            os.remove(temp_name)
                    except OSError:
                        pass
            else:
                raise ValueError("عملیات تغییر ناشناخته است.")
        except Exception as exc:
            return (
                f"اعمال تغییر شکست خورد: {exc}\n"
                f"نسخهٔ پشتیبان قبلی باقی مانده است: {backup_path}"
            )
    gui_event("status", f"تغییر تأییدشده اعمال شد: {rel}")
    gui_event("backup_created", str(backup_path))
    return (
        f"تغییر تأیید شد و انجام شد.\nفایل: {rel}\n"
        f"نسخهٔ پشتیبان: {backup_path}\n"
        "برای بازگردانی از دکمهٔ «پشتیبان‌ها / بازگردانی» استفاده کن."
    )


# ============================================================
# ARDUINO CONTROLLER
# ============================================================

class ArduinoController:
    def __init__(self):
        self.serial = None
        self.lock = threading.Lock()

    def find_port(self):
        for port in serial.tools.list_ports.comports():
            description = (port.description or "").lower()
            manufacturer = (port.manufacturer or "").lower()
            if any(token in description or token in manufacturer for token in
                   ("arduino", "ch340", "usb serial", "usb-serial")):
                return port.device
        return None

    def connect(self):
        configured = str(config.SERIAL_PORT).strip()
        candidates = [configured] if configured else []
        detected = self.find_port()
        if detected and detected not in candidates:
            candidates.append(detected)
        last_error = "پورت سریال پیدا نشد."
        for port_name in candidates:
            try:
                connection = serial.Serial(
                    port=port_name,
                    baudrate=config.BAUD_RATE,
                    timeout=config.SERIAL_TIMEOUT,
                    write_timeout=1,
                )
                time.sleep(2)  # Arduino Mega may reset after opening serial.
                with self.lock:
                    self.serial = connection
                    self.serial.write(b"STOP\n")
                    self.serial.flush()
                return True, port_name
            except Exception as exc:
                last_error = f"{port_name}: {exc}"
                try:
                    connection.close()
                except Exception:
                    pass
        self.serial = None
        return False, last_error

    def is_connected(self):
        return self.serial is not None and self.serial.is_open

    def send(self, command):
        if command not in {"FORWARD", "BACKWARD", "LEFT", "RIGHT", "STOP"}:
            return False, "فرمان سریال غیرمجاز است."
        if not self.is_connected():
            return False, "Arduino متصل نیست."
        try:
            with self.lock:
                if not self.serial or not self.serial.is_open:
                    return False, "Arduino متصل نیست."
                self.serial.write((command + "\n").encode("ascii"))
                self.serial.flush()
            return True, command
        except Exception as exc:
            return False, str(exc)

    def stop(self):
        return self.send("STOP")

    def close(self):
        try:
            self.stop()
        except Exception:
            pass
        with self.lock:
            try:
                if self.serial and self.serial.is_open:
                    self.serial.close()
            except Exception:
                pass
            self.serial = None


# ============================================================
# ROBO CONTROLLER
# ============================================================

class RoboController:
    def __init__(self):
        self.arduino = ArduinoController()
        self.running = False
        self.thread = None
        self.audio = None
        self.microphone = None
        self.speaker = None
        self.loop = None
        self.async_stop_event = None
        self.session = None
        self.client = None
        self.motion_lock = threading.RLock()
        self.motion_generation = 0
        self.motion_timer = None
        self.tool_tasks = set()

    def check_api_key(self):
        return bool(config.GEMINI_API_KEY and config.GEMINI_API_KEY.strip())

    def check_microphone(self):
        test_audio = None
        try:
            test_audio = pyaudio.PyAudio()
            count = test_audio.get_device_count()
            index = int(config.MICROPHONE_DEVICE_INDEX)
            if index < 0 or index >= count:
                return False, f"میکروفون شمارهٔ {index} پیدا نشد. تعداد دستگاه‌ها: {count}"
            info = test_audio.get_device_info_by_index(index)
            name = str(info.get("name", "Unknown"))
            if int(info.get("maxInputChannels", 0)) <= 0:
                return False, f"دستگاه «{name}» ورودی صدا ندارد."
            return True, name
        except Exception as exc:
            return False, str(exc)
        finally:
            if test_audio is not None:
                test_audio.terminate()

    def connect_arduino(self):
        ok, result = self.arduino.connect()
        gui_event("arduino", ok)
        if ok:
            gui_event("status", f"Arduino متصل شد: {result}")
        else:
            gui_event("error", f"اتصال Arduino ناموفق بود:\n{result}")
        return ok

    def move_robot(self, direction, duration=1.0):
        direction = str(direction).lower().strip()
        commands = {
            "forward": "FORWARD", "backward": "BACKWARD",
            "left": "LEFT", "right": "RIGHT",
        }
        if direction not in commands:
            return "جهت حرکت نامعتبر است؛ حرکتی انجام نشد."
        try:
            duration = float(duration)
        except (TypeError, ValueError):
            duration = 1.0
        duration = max(0.1, min(duration, MAX_MOVE_SECONDS))
        with self.motion_lock:
            # Invalidate any previous timer before beginning a new movement.
            self.motion_generation += 1
            token = self.motion_generation
            if self.motion_timer is not None:
                self.motion_timer.cancel()
                self.motion_timer = None
            ok, result = self.arduino.send(commands[direction])
            if not ok:
                return f"نتوانستم رُوبُو را حرکت بدهم: {result}"
            timer = threading.Timer(duration, self._timed_stop, args=(token,))
            timer.daemon = True
            self.motion_timer = timer
            timer.start()
        gui_event("movement", f"{direction} | {duration:.1f} ثانیه")
        return f"حرکت {direction} شروع شد؛ توقف خودکار پس از {duration:.1f} ثانیه انجام می‌شود."

    def _timed_stop(self, token):
        with self.motion_lock:
            if token != self.motion_generation:
                return
            self.motion_generation += 1
            self.motion_timer = None
            ok, result = self.arduino.stop()
        gui_event("movement", "STOP" if ok else f"STOP ناموفق: {result}")

    def stop_robot(self):
        with self.motion_lock:
            self.motion_generation += 1
            if self.motion_timer is not None:
                self.motion_timer.cancel()
                self.motion_timer = None
            ok, result = self.arduino.stop()
        gui_event("movement", "STOP" if ok else f"STOP ناموفق: {result}")
        return "فرمان توقف به Arduino ارسال شد." if ok else f"ارسال توقف ناموفق بود: {result}"

    def start_audio(self):
        self.audio = pyaudio.PyAudio()
        try:
            self.microphone = self.audio.open(
                format=pyaudio.paInt16,
                channels=config.INPUT_CHANNELS,
                rate=config.INPUT_SAMPLE_RATE,
                input=True,
                input_device_index=config.MICROPHONE_DEVICE_INDEX,
                frames_per_buffer=config.INPUT_CHUNK,
            )
            self.speaker = self.audio.open(
                format=pyaudio.paInt16,
                channels=OUTPUT_CHANNELS,
                rate=OUTPUT_SAMPLE_RATE,
                output=True,
                frames_per_buffer=OUTPUT_CHUNK,
            )
        except Exception:
            self.stop_audio()
            raise

    def stop_audio(self):
        # Called by the session-owning worker thread after its async tasks end.
        for stream in (self.microphone, self.speaker):
            try:
                if stream is not None:
                    if stream.is_active():
                        stream.stop_stream()
                    stream.close()
            except Exception:
                pass
        self.microphone = None
        self.speaker = None
        try:
            if self.audio is not None:
                self.audio.terminate()
        except Exception:
            pass
        self.audio = None

    # --------------------------------------------------------
    # GEMINI TOOLS
    # --------------------------------------------------------

    def create_tools(self):
        declarations = [
            # Physical robot controls MUST be registered as Gemini tools.
            # Methods existed in RoboController, but without declarations Gemini
            # could only talk about moving; it could not actually request movement.
            types.FunctionDeclaration(
                name="move_robot",
                description=(
                    "حرکت واقعی رُوبُو با Arduino. فقط وقتی کاربر واضحاً درخواست حرکت کرده "
                    "استفاده کن. جهت یکی از forward/backward/left/right است. "
                    "اگر زمان مشخص نشده بود 1 ثانیه؛ حداکثر 5 ثانیه. "
                    "بعد از اجرای ابزار، نتیجه را صادقانه به کاربر بگو."
                ),
                parameters={
                    "type": "OBJECT",
                    "properties": {
                        "direction": {
                            "type": "STRING",
                            "enum": ["forward", "backward", "left", "right"],
                            "description": "جهت حرکت: جلو، عقب، چپ، راست",
                        },
                        "duration": {
                            "type": "NUMBER",
                            "description": "مدت حرکت به ثانیه، بین 0.1 و 5؛ پیش‌فرض 1",
                        },
                    },
                    "required": ["direction"],
                },
            ),
            types.FunctionDeclaration(
                name="stop_robot",
                description=(
                    "ارسال فوری فرمان STOP به Arduino و لغو تایمر حرکت فعلی. "
                    "هنگام شنیدن وایسا، بایست، توقف کن یا دستور توقف، از این ابزار استفاده کن."
                ),
                parameters={"type": "OBJECT", "properties": {}},
            ),
            types.FunctionDeclaration(
                name="list_project_files",
                description=(
                    "فهرست فایل‌ها و پوشه‌های پروژهٔ فعلی رُوبُو را بده. "
                    "فقط مسیرهای نسبی داخل پوشهٔ پروژه در دسترس‌اند؛ پوشهٔ پشتیبان‌ها جدا نگه داشته می‌شود."
                ),
                parameters={"type": "OBJECT", "properties": {}},
            ),
            types.FunctionDeclaration(
                name="read_project_file",
                description=(
                    "محتوای یک فایل متنی پروژه را بخوان. مسیر باید نسبی مانند robo_app.py یا Arduino/Robo_Mega.ino. "
                    "کلیدها و رمزهای متنی شناخته‌شده پیش از ارسال مخفی می‌شوند."
                ),
                parameters={
                    "type": "OBJECT",
                    "properties": {"path": {"type": "STRING", "description": "مسیر نسبی داخل پروژه"}},
                    "required": ["path"],
                },
            ),
            types.FunctionDeclaration(
                name="search_project_code",
                description="عبارت یا نام متغیر را در فایل‌های متنی پروژه جست‌وجو کن.",
                parameters={
                    "type": "OBJECT",
                    "properties": {"query": {"type": "STRING", "description": "عبارت جست‌وجو"}},
                    "required": ["query"],
                },
            ),
            types.FunctionDeclaration(
                name="check_python_syntax",
                description=(
                    "فایل‌های Python پروژه را فقط با AST بررسی نحوی کن؛ هیچ فایل Python را اجرا نمی‌کند. "
                    "اگر path داده شود فقط همان فایل بررسی می‌شود."
                ),
                parameters={
                    "type": "OBJECT",
                    "properties": {"path": {"type": "STRING", "description": "مسیر نسبی اختیاری؛ حذف شود تا همهٔ فایل‌های py بررسی شوند"}},
                },
            ),
            types.FunctionDeclaration(
                name="propose_file_change",
                description=(
                    "ایجاد یا جایگزینی کامل یک فایل متنی پروژه را پیشنهاد کن. هرگز ادعا نکن فایل تغییر کرده است: "
                    "برنامه ابتدا خلاصه و پیش‌نمایش تغییر را به کاربر نشان می‌دهد. فقط پس از تأیید صریح در پنجره، "
                    "برنامه قبل از نوشتن پشتیبان می‌سازد و سپس تغییر را اعمال می‌کند. مسیر فقط داخل پروژه مجاز است."
                ),
                parameters={
                    "type": "OBJECT",
                    "properties": {
                        "path": {"type": "STRING", "description": "مسیر نسبی فایل"},
                        "summary": {"type": "STRING", "description": "توضیح دقیق تغییرات، دلیل و نتیجهٔ مورد انتظار برای نمایش به کاربر"},
                        "content": {"type": "STRING", "description": "محتوای کامل پیشنهادی فایل بعد از تغییر"},
                    },
                    "required": ["path", "summary", "content"],
                },
            ),
            types.FunctionDeclaration(
                name="propose_file_delete",
                description=(
                    "حذف یک فایل متنی پروژه را پیشنهاد کن. حذف هرگز بدون نمایش دلیل و تأیید صریح کاربر انجام نمی‌شود. "
                    "پس از تأیید، نسخهٔ پشتیبان ساخته می‌شود و سپس فایل حذف می‌شود."
                ),
                parameters={
                    "type": "OBJECT",
                    "properties": {
                        "path": {"type": "STRING", "description": "مسیر نسبی فایل متنی"},
                        "summary": {"type": "STRING", "description": "دلیل و پیامدهای حذف"},
                    },
                    "required": ["path", "summary"],
                },
            ),
        ]
        return [types.Tool(function_declarations=declarations)]

    def _list_project_files(self):
        rows = []
        for path in iter_project_files():
            try:
                rel = path.relative_to(PROJECT_ROOT).as_posix()
                if path.is_file():
                    size = path.stat().st_size
                    kind = "text" if is_text_file(path) else "binary/asset"
                    rows.append({"path": rel, "type": kind, "size_bytes": size})
            except OSError:
                continue
        rows.sort(key=lambda item: item["path"].casefold())
        result = {
            "project_root": str(PROJECT_ROOT),
            "backup_folder": str(BACKUP_ROOT),
            "note": "Backup/cache files are excluded; binary assets are listed but not sent as source text.",
            "files": rows,
        }
        return json.dumps(result, ensure_ascii=False, indent=2)[:MAX_TOOL_RESULT_CHARS]

    def _read_project_file(self, raw_path):
        rel, target = resolve_project_path(raw_path, must_exist=True)
        content = redact_secrets(load_text_file(target))
        return f"FILE: {rel}\n--- BEGIN FILE ---\n{content}\n--- END FILE ---"[:MAX_TOOL_RESULT_CHARS]

    def _search_project_code(self, query):
        query = str(query or "").strip()
        if not query:
            return "عبارت جست‌وجو خالی است."
        results = []
        for path in iter_project_files():
            if not path.is_file() or not is_text_file(path):
                continue
            try:
                if path.stat().st_size > MAX_TEXT_FILE_BYTES:
                    continue
                text = redact_secrets(path.read_text(encoding="utf-8", errors="replace"))
                for number, line in enumerate(text.splitlines(), 1):
                    if query.casefold() in line.casefold():
                        rel = path.relative_to(PROJECT_ROOT).as_posix()
                        results.append(f"{rel}:{number}: {line[:300]}")
                        if len(results) >= 100:
                            return "\n".join(results) + "\n... نتیجه‌ها به 100 مورد محدود شدند."
            except (OSError, UnicodeError):
                continue
        return "\n".join(results) if results else "نتیجه‌ای پیدا نشد."

    def _check_python_syntax(self, raw_path=None):
        if raw_path:
            rel, path = resolve_project_path(raw_path, must_exist=True)
            files = [path]
            if path.suffix.lower() != ".py":
                return "فقط فایل‌های .py را می‌توان از نظر نحو بررسی کرد."
        else:
            files = [p for p in iter_project_files() if p.is_file() and p.suffix.lower() == ".py"]
        results = []
        failures = 0
        for path in files:
            rel = path.relative_to(PROJECT_ROOT).as_posix()
            try:
                if path.stat().st_size > MAX_TEXT_FILE_BYTES:
                    results.append(f"SKIP {rel}: فایل بزرگ‌تر از سقف بررسی است.")
                    continue
                source = path.read_text(encoding="utf-8-sig", errors="replace")
                ast.parse(source, filename=rel)
                results.append(f"OK   {rel}")
            except (SyntaxError, UnicodeError, OSError) as exc:
                failures += 1
                line = getattr(exc, "lineno", None)
                results.append(f"FAIL {rel}{f':{line}' if line else ''}: {exc}")
        results.append(f"\nنتیجه: {len(files)} فایل بررسی شد؛ {failures} خطای نحوی پیدا شد.")
        return "\n".join(results)[:MAX_TOOL_RESULT_CHARS]

    def _propose_change(self, raw_path, summary, content, action="write"):
        rel, target = resolve_project_path(raw_path, must_exist=False)
        if not is_text_file(target):
            raise ValueError("برای جلوگیری از خراب‌شدن مدل‌ها و فایل‌های باینری، فقط فایل‌های متنی قابل‌تغییرند.")
        if action == "delete" and not target.is_file():
            raise FileNotFoundError(f"فایل پیدا نشد: {rel}")
        if action == "write":
            if not isinstance(content, str):
                raise ValueError("محتوای پیشنهادی متنی نیست.")
            if len(content.encode("utf-8")) > MAX_CHANGE_BYTES:
                raise ValueError("فایل پیشنهادی خیلی بزرگ است؛ آن را به تغییرات کوچک‌تر تقسیم کن.")
            content = safe_config_content_for_write(rel, content, None)
        old_raw = target.read_text(encoding="utf-8", errors="replace") if target.is_file() else ""
        old_display = redact_secrets(old_raw)
        new_display = redact_secrets(content or "") if action == "write" else ""
        future = concurrent.futures.Future()
        proposal = ChangeProposal(
            rel_path=rel,
            action=action,
            summary=str(summary or "بدون توضیح")[:4000],
            new_content=content,
            expected_digest=file_digest(target),
            old_content_for_display=old_display,
            new_content_for_display=new_display,
            future=future,
        )
        gui_event("approval_request", proposal)
        return proposal

    async def execute_tool(self, function_call):
        name = function_call.name
        args = dict(function_call.args or {})
        gui_event("tool", f"{name} {args.get('path', args.get('query', ''))}")
        try:
            # Route movement Function Calls to the real Arduino controller.
            # move_robot starts a bounded timer and returns quickly so Live audio
            # reception is not blocked while the motors are moving.
            if name == "move_robot":
                direction = args.get("direction", "")
                duration = args.get("duration", 1.0)
                result = await asyncio.to_thread(
                    self.move_robot, direction, duration
                )
                return result
            if name == "stop_robot":
                return await asyncio.to_thread(self.stop_robot)
            if name == "list_project_files":
                return self._list_project_files()
            if name == "read_project_file":
                return self._read_project_file(args.get("path", ""))
            if name == "search_project_code":
                return self._search_project_code(args.get("query", ""))
            if name == "check_python_syntax":
                return self._check_python_syntax(args.get("path"))
            if name == "propose_file_change":
                proposal = self._propose_change(
                    args.get("path", ""), args.get("summary", ""), args.get("content", ""), "write"
                )
                result = await asyncio.wrap_future(proposal.future)
                return result
            if name == "propose_file_delete":
                proposal = self._propose_change(
                    args.get("path", ""), args.get("summary", ""), None, "delete"
                )
                result = await asyncio.wrap_future(proposal.future)
                return result
            return "ابزار ناشناخته است."
        except Exception as exc:
            return f"عملیات انجام نشد: {type(exc).__name__}: {exc}"

    # --------------------------------------------------------
    # MICROPHONE / GEMINI RECEIVE
    # --------------------------------------------------------

    async def send_microphone(self, session):
        loop = asyncio.get_running_loop()
        while self.running:
            try:
                data = await loop.run_in_executor(
                    None, self.microphone.read, config.INPUT_CHUNK, False
                )
                if not self.running:
                    break
                await session.send_realtime_input(
                    audio=types.Blob(data=data, mime_type=f"audio/pcm;rate={config.INPUT_SAMPLE_RATE}")
                )
            except Exception as exc:
                if self.running:
                    gui_event("error", f"خطا در میکروفون: {exc}")
                break

    async def _handle_function_call(self, session, function_call):
        try:
            result = await self.execute_tool(function_call)
        except Exception as exc:
            result = f"خطا در اجرای ابزار: {exc}"
        if not self.running:
            return
        try:
            response = types.FunctionResponse(
                name=function_call.name,
                id=function_call.id,
                response={"result": str(result)[:MAX_TOOL_RESULT_CHARS]},
            )
            await session.send_tool_response(function_responses=[response])
        except Exception as exc:
            gui_event("error", f"ارسال نتیجهٔ ابزار به Gemini ناموفق بود: {exc}")

    async def receive_gemini(self, session):
        # Gemini Live's receive() iterator yields one complete model turn and
        # then ends normally. Re-enter it so the live conversation keeps
        # listening after the initial greeting and after every later reply.
        while self.running:
            try:
                async for response in session.receive():
                    if not self.running:
                        return

                    if response.tool_call:
                        for function_call in response.tool_call.function_calls:
                            task = asyncio.create_task(
                                self._handle_function_call(session, function_call)
                            )
                            self.tool_tasks.add(task)
                            task.add_done_callback(self.tool_tasks.discard)

                    content = response.server_content
                    if not content:
                        continue

                    if content.input_transcription and content.input_transcription.text:
                        gui_event("user_text", content.input_transcription.text)

                    if content.output_transcription and content.output_transcription.text:
                        gui_event("ai_text", content.output_transcription.text)

                    if content.model_turn and self.speaker is not None:
                        for part in content.model_turn.parts:
                            if part.inline_data and part.inline_data.data:
                                try:
                                    await asyncio.to_thread(
                                        self.speaker.write, part.inline_data.data
                                    )
                                except Exception as exc:
                                    if self.running:
                                        gui_event(
                                            "error",
                                            f"پخش صدا با خطا روبه‌رو شد: {exc}",
                                        )
                # Normal end of receive() means the current turn ended, not
                # necessarily that the Live session should be shut down.
                if self.running:
                    await asyncio.sleep(0.01)
            except asyncio.CancelledError:
                raise
            except Exception as exc:
                if self.running:
                    gui_event("error", f"ارتباط Gemini قطع شد: {exc}")
                return

    async def async_main(self):
        try:
            self.loop = asyncio.get_running_loop()
            self.async_stop_event = asyncio.Event()
            self.start_audio()
            gui_event("status", "میکروفون آماده شد؛ در حال اتصال به Gemini Live...")
            self.client = genai.Client(api_key=config.GEMINI_API_KEY)
            live_config = types.LiveConnectConfig(
                response_modalities=["AUDIO"],
                system_instruction=types.Content(
                    parts=[types.Part(text=config.SYSTEM_INSTRUCTION)]
                ),
                tools=self.create_tools(),
                input_audio_transcription={},
                output_audio_transcription={},
            )
            async with self.client.aio.live.connect(
                model=config.GEMINI_MODEL, config=live_config
            ) as session:
                self.session = session
                gui_event("connected", True)
                gui_event("status", "رُوبُو آماده است؛ صحبت کن.")
                await session.send_client_content(
                    turns={"role": "user", "parts": [{"text": "سلام رُوبُو. آماده‌ای."}]},
                    turn_complete=True,
                )
                mic_task = asyncio.create_task(self.send_microphone(session))
                receive_task = asyncio.create_task(self.receive_gemini(session))
                stop_task = asyncio.create_task(self.async_stop_event.wait())
                tasks = {mic_task, receive_task, stop_task}
                done, pending = await asyncio.wait(tasks, return_when=asyncio.FIRST_COMPLETED)
                if self.running:
                    for task in done:
                        try:
                            exc = task.exception()
                            if exc:
                                gui_event("error", f"بخش ارتباطی متوقف شد: {exc}")
                        except (asyncio.CancelledError, Exception):
                            pass
                self.running = False
                for task in pending:
                    task.cancel()
                await asyncio.gather(*pending, return_exceptions=True)
                if self.tool_tasks:
                    for task in list(self.tool_tasks):
                        task.cancel()
                    await asyncio.gather(*self.tool_tasks, return_exceptions=True)
        except Exception as exc:
            gui_event("error", f"خطای رُوبُو: {exc}")
        finally:
            self.running = False
            try:
                self.stop_robot()
            except Exception:
                pass
            self.stop_audio()
            self.session = None
            self.loop = None
            self.async_stop_event = None
            gui_event("connected", False)
            gui_event("run_finished", None)

    def start(self):
        if self.running:
            return
        if not self.check_api_key():
            gui_event(
                "error",
                "کلید Gemini پیدا نشد. در متغیرهای محیطی کاربر ویندوز، GEMINI_API_KEY را با کلید جدید تنظیم کن و دوباره وارد ویندوز/ترمینال شو."
            )
            gui_event("run_finished", None)
            return
        if not self.arduino.is_connected() and not self.connect_arduino():
            gui_event("run_finished", None)
            return
        ok, result = self.check_microphone()
        if not ok:
            gui_event("error", f"مشکل میکروفون: {result}")
            gui_event("run_finished", None)
            return
        gui_event("status", f"میکروفون آماده است: {result}")
        self.running = True
        self.thread = threading.Thread(target=self.run_thread, daemon=True)
        self.thread.start()

    def run_thread(self):
        try:
            asyncio.run(self.async_main())
        except Exception as exc:
            gui_event("error", f"خطای اجرای برنامه: {exc}")
        finally:
            self.running = False
            gui_event("run_finished", None)

    def stop(self):
        # STOP is sent immediately; audio cleanup is left to the session-owning thread.
        self.running = False
        try:
            self.stop_robot()
        except Exception:
            pass
        loop = self.loop
        stop_event = self.async_stop_event
        if loop is not None and stop_event is not None:
            try:
                loop.call_soon_threadsafe(stop_event.set)
            except RuntimeError:
                pass
        gui_event("status", "درخواست توقف ثبت شد؛ در حال بستن امن ارتباط صوتی...")

    def close(self):
        self.stop()
        if self.thread and self.thread.is_alive():
            self.thread.join(timeout=2.0)
        self.arduino.close()


# ============================================================
# GUI
# ============================================================

class RoboGUI:
    def __init__(self, root):
        self.root = root
        self.root.title(config.WINDOW_TITLE)
        self.root.geometry(f"{config.WINDOW_WIDTH}x{config.WINDOW_HEIGHT}")
        self.root.minsize(760, 520)
        self.controller = RoboController()
        self.approval_dialogs = {}
        self.create_style()
        self.create_widgets()
        self.root.after(100, self.process_events)
        self.root.protocol("WM_DELETE_WINDOW", self.on_close)

    def create_style(self):
        style = ttk.Style()
        try:
            style.theme_use("clam")
        except Exception:
            pass
        style.configure("Title.TLabel", font=("Segoe UI", 24, "bold"))
        style.configure("Status.TLabel", font=("Segoe UI", 11))
        style.configure("Start.TButton", font=("Segoe UI", 12, "bold"))

    def create_widgets(self):
        main = ttk.Frame(self.root, padding=16)
        main.pack(fill="both", expand=True)
        ttk.Label(main, text="🤖 رُوبُو", style="Title.TLabel").pack(pady=(0, 3))
        ttk.Label(main, text="Gemini Live • Arduino • Project Assistant").pack(pady=(0, 10))
        self.status_var = tk.StringVar(value="آماده برای شروع")
        ttk.Label(main, textvariable=self.status_var, style="Status.TLabel").pack(pady=4)
        self.connection_var = tk.StringVar(value="● قطع")
        ttk.Label(main, textvariable=self.connection_var).pack(pady=2)

        buttons = ttk.Frame(main)
        buttons.pack(pady=10)
        self.start_button = ttk.Button(buttons, text="▶ شروع رُوبُو", style="Start.TButton", command=self.start)
        self.start_button.grid(row=0, column=0, padx=5)
        self.stop_button = ttk.Button(buttons, text="■ توقف", command=self.stop, state="disabled")
        self.stop_button.grid(row=0, column=1, padx=5)
        ttk.Button(buttons, text="📦 پشتیبان‌ها / بازگردانی", command=self.open_backups_window).grid(row=0, column=2, padx=5)
        ttk.Button(buttons, text="📂 بازکردن پوشهٔ پشتیبان", command=self.open_backup_folder).grid(row=0, column=3, padx=5)

        conversation_frame = ttk.LabelFrame(main, text="مکالمه، ابزارها و گزارش تغییرات", padding=8)
        conversation_frame.pack(fill="both", expand=True)
        self.log = tk.Text(conversation_frame, wrap="word", font=("Segoe UI", 10), state="disabled")
        self.log.pack(side="left", fill="both", expand=True)
        scrollbar = ttk.Scrollbar(conversation_frame, orient="vertical", command=self.log.yview)
        scrollbar.pack(side="right", fill="y")
        self.log.configure(yscrollcommand=scrollbar.set)
        ttk.Label(main, text=f"پشتیبان‌ها در: {BACKUP_ROOT}").pack(pady=(8, 0))

    def write_log(self, text):
        self.log.configure(state="normal")
        self.log.insert("end", str(text) + "\n")
        self.log.see("end")
        self.log.configure(state="disabled")

    def start(self):
        self.write_log("⏳ در حال بررسی سیستم...")
        self.status_var.set("در حال شروع...")
        self.start_button.configure(state="disabled")
        self.stop_button.configure(state="normal")
        threading.Thread(target=self.controller.start, daemon=True).start()

    def stop(self):
        self.controller.stop()
        # Final button state is set by run_finished after streams close.

    def show_approval_dialog(self, proposal: ChangeProposal):
        action_label = "حذف فایل" if proposal.action == "delete" else "ایجاد/جایگزینی فایل"
        if proposal.action == "delete":
            diff_text = "این فایل پس از تأیید حذف می‌شود؛ نسخهٔ قبلی قبل از حذف پشتیبان‌گیری خواهد شد.\n\n" + proposal.old_content_for_display[:30000]
        else:
            diff_lines = difflib.unified_diff(
                proposal.old_content_for_display.splitlines(),
                proposal.new_content_for_display.splitlines(),
                fromfile=f"قبل/{proposal.rel_path}" if proposal.old_content_for_display else "/dev/null",
                tofile=f"بعد/{proposal.rel_path}",
                lineterm="",
            )
            diff_text = "\n".join(diff_lines)
            if len(diff_text) > 60000:
                diff_text = diff_text[:60000] + "\n... پیش‌نمایش کوتاه شده است؛ تغییر کامل پس از تأیید اعمال می‌شود."
            if not diff_text:
                diff_text = "تغییری برای نمایش پیدا نشد."

        dialog = tk.Toplevel(self.root)
        dialog.title("تأیید تغییر در پروژهٔ رُوبُو")
        dialog.geometry("850x620")
        dialog.minsize(650, 420)
        dialog.transient(self.root)

        header = ttk.Frame(dialog, padding=12)
        header.pack(fill="x")
        ttk.Label(header, text=f"عملیات: {action_label}", font=("Segoe UI", 12, "bold")).pack(anchor="w")
        ttk.Label(header, text=f"فایل: {proposal.rel_path}", wraplength=800).pack(anchor="w", pady=(5, 0))
        ttk.Label(header, text="توضیح Gemini:", font=("Segoe UI", 10, "bold")).pack(anchor="w", pady=(8, 0))
        summary = tk.Text(header, height=4, wrap="word", font=("Segoe UI", 10))
        summary.pack(fill="x", pady=(3, 0))
        summary.insert("1.0", proposal.summary)
        summary.configure(state="disabled")

        ttk.Label(dialog, text="پیش‌نمایش تغییرات (کلیدها و اسرار شناخته‌شده مخفی شده‌اند):").pack(anchor="w", padx=12)
        diff_frame = ttk.Frame(dialog, padding=(12, 5))
        diff_frame.pack(fill="both", expand=True)
        diff_box = tk.Text(diff_frame, wrap="none", font=("Consolas", 9))
        diff_scroll_y = ttk.Scrollbar(diff_frame, orient="vertical", command=diff_box.yview)
        diff_scroll_x = ttk.Scrollbar(diff_frame, orient="horizontal", command=diff_box.xview)
        diff_box.configure(yscrollcommand=diff_scroll_y.set, xscrollcommand=diff_scroll_x.set)
        diff_box.grid(row=0, column=0, sticky="nsew")
        diff_scroll_y.grid(row=0, column=1, sticky="ns")
        diff_scroll_x.grid(row=1, column=0, sticky="ew")
        diff_frame.rowconfigure(0, weight=1)
        diff_frame.columnconfigure(0, weight=1)
        diff_box.insert("1.0", diff_text)
        diff_box.configure(state="disabled")

        footer = ttk.Frame(dialog, padding=12)
        footer.pack(fill="x")
        status = tk.StringVar(value=f"پشتیبان در {BACKUP_ROOT} ساخته می‌شود؛ فقط پس از تأیید تغییر اعمال خواهد شد.")
        ttk.Label(footer, textvariable=status, wraplength=500).pack(side="left", fill="x", expand=True)
        reject_button = ttk.Button(footer, text="رد کردن", command=lambda: reject())
        reject_button.pack(side="right", padx=(6, 0))
        approve_button = ttk.Button(footer, text="تأیید، پشتیبان‌گیری و اعمال", command=lambda: approve())
        approve_button.pack(side="right")

        def close_window():
            self.approval_dialogs.pop(id(proposal), None)
            if not proposal.future.done():
                proposal.future.set_result("کاربر تغییر را رد کرد یا پنجره را بست؛ هیچ فایلی تغییر نکرد.")
            try:
                dialog.destroy()
            except tk.TclError:
                pass

        def reject():
            if not proposal.future.done():
                proposal.future.set_result("کاربر تغییر را رد کرد؛ هیچ فایلی تغییر نکرد.")
            self.write_log(f"⛔ تغییر رد شد: {proposal.rel_path}")
            close_window()

        def approve():
            approve_button.configure(state="disabled")
            reject_button.configure(state="disabled")
            status.set("در حال بررسی نسخهٔ فایل، ساخت پشتیبان و اعمال تغییر...")

            def worker():
                result = apply_change_after_approval(proposal)
                if not proposal.future.done():
                    proposal.future.set_result(result)
                gui_event("approval_result", (proposal, result))

            threading.Thread(target=worker, daemon=True).start()

        self.approval_dialogs[id(proposal)] = (dialog, close_window)
        dialog.protocol("WM_DELETE_WINDOW", close_window)
        dialog.focus_set()

    def open_backup_folder(self):
        try:
            BACKUP_ROOT.mkdir(parents=True, exist_ok=True)
            os.startfile(str(BACKUP_ROOT))  # Windows-only app.
        except Exception as exc:
            messagebox.showerror("خطا", f"پوشهٔ پشتیبان باز نشد:\n{exc}", parent=self.root)

    def open_backups_window(self):
        window = tk.Toplevel(self.root)
        window.title("پشتیبان‌ها و بازگردانی")
        window.geometry("800x460")
        window.transient(self.root)
        frame = ttk.Frame(window, padding=12)
        frame.pack(fill="both", expand=True)
        ttk.Label(frame, text=f"محل ذخیره: {BACKUP_ROOT}").pack(anchor="w", pady=(0, 8))
        columns = ("created", "file", "reason", "state")
        tree = ttk.Treeview(frame, columns=columns, show="headings", selectmode="browse")
        tree.heading("created", text="زمان")
        tree.heading("file", text="فایل")
        tree.heading("reason", text="توضیح تغییر")
        tree.heading("state", text="وضعیت قبلی")
        tree.column("created", width=145, stretch=False)
        tree.column("file", width=210)
        tree.column("reason", width=280)
        tree.column("state", width=100, stretch=False)
        tree.pack(fill="both", expand=True)
        snapshots = {}

        def refresh():
            for iid in tree.get_children():
                tree.delete(iid)
            snapshots.clear()
            if not BACKUP_ROOT.exists():
                return
            candidates = sorted(
                [p for p in BACKUP_ROOT.iterdir() if p.is_dir() and (p / "manifest.json").is_file()],
                reverse=True,
            )
            for folder in candidates:
                try:
                    manifest = json.loads((folder / "manifest.json").read_text(encoding="utf-8"))
                    iid = tree.insert("", "end", values=(
                        manifest.get("created_at", folder.name),
                        manifest.get("file", "?"),
                        manifest.get("reason", ""),
                        "فایل موجود بود" if manifest.get("existed_before") else "فایل جدید بود",
                    ))
                    snapshots[iid] = (folder, manifest)
                except Exception:
                    continue

        def restore_selected():
            selection = tree.selection()
            if not selection:
                messagebox.showinfo("انتخاب پشتیبان", "ابتدا یک نسخهٔ پشتیبان انتخاب کن.", parent=window)
                return
            folder, manifest = snapshots[selection[0]]
            rel = manifest.get("file", "")
            try:
                rel, target = resolve_project_path(rel, must_exist=False)
                if manifest.get("existed_before"):
                    backup_rel = manifest.get("backup_copy")
                    backup_file = (folder / Path(*str(backup_rel).replace("\\", "/").split("/"))).resolve()
                    if not backup_file.is_file() or folder.resolve() not in backup_file.parents:
                        raise ValueError("فایل نسخهٔ پشتیبان پیدا نشد یا مسیر آن نامعتبر است.")
                else:
                    backup_file = None
            except Exception as exc:
                messagebox.showerror("خطا", str(exc), parent=window)
                return
            question = (
                f"فایل {rel} به وضعیت ذخیره‌شده در این پشتیبان برگردد؟\n\n"
                f"قبل از بازگردانی، وضعیت فعلی نیز در {BACKUP_ROOT} پشتیبان‌گیری می‌شود."
            )
            if not messagebox.askyesno("تأیید بازگردانی", question, parent=window):
                return

            def restore_worker():
                try:
                    with project_change_lock:
                        create_backup_snapshot(rel, f"قبل از بازگردانی نسخهٔ {folder.name}")
                        if manifest.get("existed_before"):
                            target.parent.mkdir(parents=True, exist_ok=True)
                            fd, temp_name = tempfile.mkstemp(prefix=".robo_restore_", suffix=".tmp", dir=str(target.parent))
                            try:
                                with os.fdopen(fd, "wb") as stream:
                                    with open(backup_file, "rb") as backup_stream:
                                        shutil.copyfileobj(backup_stream, stream)
                                    stream.flush()
                                    os.fsync(stream.fileno())
                                os.replace(temp_name, target)
                            finally:
                                try:
                                    if os.path.exists(temp_name):
                                        os.remove(temp_name)
                                except OSError:
                                    pass
                        else:
                            if target.is_file():
                                target.unlink()
                    gui_event("status", f"بازگردانی انجام شد: {rel}")
                    gui_event("restore_result", f"فایل بازگردانی شد: {rel}")
                except Exception as exc:
                    gui_event("error", f"بازگردانی ناموفق بود: {exc}")

            threading.Thread(target=restore_worker, daemon=True).start()
            messagebox.showinfo("بازگردانی شروع شد", "عملیات بازگردانی شروع شد. نتیجه در گزارش رُوبُو نمایش داده می‌شود.", parent=window)
            refresh()

        buttons = ttk.Frame(frame)
        buttons.pack(fill="x", pady=(10, 0))
        ttk.Button(buttons, text="تازه‌سازی", command=refresh).pack(side="left")
        ttk.Button(buttons, text="بازگرداندن نسخهٔ انتخاب‌شده", command=restore_selected).pack(side="right")
        refresh()

    def process_events(self):
        try:
            while True:
                event, data = gui_queue.get_nowait()
                if event == "status":
                    self.status_var.set(str(data))
                    self.write_log("ℹ " + str(data))
                elif event == "arduino":
                    self.connection_var.set("● Arduino متصل" if data else "● Arduino قطع")
                elif event == "connected":
                    self.connection_var.set("● Gemini + Arduino متصل" if data else "● قطع")
                elif event == "user_text":
                    self.write_log("👤 شما: " + str(data))
                elif event == "ai_text":
                    self.write_log("🤖 رُوبُو: " + str(data))
                elif event == "movement":
                    self.write_log("⚙ حرکت: " + str(data))
                elif event == "tool":
                    self.write_log("🔧 ابزار: " + str(data))
                elif event == "approval_request":
                    self.write_log(f"📝 پیشنهاد تغییر نیازمند تأیید: {data.rel_path}")
                    self.show_approval_dialog(data)
                elif event == "approval_result":
                    proposal, result = data
                    self.write_log("📦 نتیجهٔ تغییر فایل:\n" + str(result))
                    entry = self.approval_dialogs.get(id(proposal))
                    if entry:
                        _, close_callback = entry
                        close_callback()
                elif event == "backup_created":
                    self.write_log("📦 پشتیبان ساخته شد: " + str(data))
                elif event == "restore_result":
                    self.write_log("↩ " + str(data))
                elif event == "run_finished":
                    self.start_button.configure(state="normal")
                    self.stop_button.configure(state="disabled")
                elif event == "error":
                    self.write_log("❌ " + str(data))
                    self.status_var.set("خطا")
        except queue.Empty:
            pass
        self.root.after(100, self.process_events)

    def on_close(self):
        if messagebox.askyesno("خروج از رُوبُو", "رُوبُو متوقف شود و برنامه بسته شود؟", parent=self.root):
            self.controller.close()
            self.root.destroy()


def main():
    root = tk.Tk()
    RoboGUI(root)
    root.mainloop()


if __name__ == "__main__":
    main()
