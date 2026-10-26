#!/usr/bin/env python3
"""YG 消息转发中继 · 服务端。

设计约束（都是刻意的取舍，不要随手改）：

1. **零第三方依赖**。只用 Python 3.9+ 标准库：`http.server` 起线程化 HTTP 服务，
   `sqlite3` 落库，`hashlib` 做 PBKDF2 / HMAC。目标机器上只要能跑 python 就能跑，
   Docker 镜像里也不需要 pip install。
2. **两条鉴权通道彼此独立**。手机用 `Authorization: Bearer <ingest_token>` 上传，
   浏览器用 `HttpOnly` 会话 Cookie 查看。手机令牌泄露不等于网页被看，反之亦然。
3. **消息只进本地 SQLite**。不引任何上报、不外发、不打正文日志。
4. **单进程线程模型**。写库有全局锁，读用每线程连接 + WAL；SSE 每个连接占一个线程，
   `daemon_threads=True` 保证进程退出不被卡住。

用法：
    python3 serve.py --port 8787 --data-dir ./data
    python3 serve.py --port 8787 --cert cert.pem --key key.pem   # 自带 TLS
"""

from __future__ import annotations

import argparse
import base64
import datetime
import hashlib
import hmac
import json
import os
import queue
import secrets
import signal
import sqlite3
import ssl
import sys
import threading
import time
import urllib.parse
import zlib
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from typing import Any, Dict, List, Optional, Tuple

VERSION = "1.0.0"
SCHEMA_BATCH = "yg.relay.batch.v1"

# 请求体上限：手机一批最多 500 条通知，正常远小于 1MB。
MAX_BODY = 2 * 1024 * 1024
# gzip 解压上限，防压缩炸弹。
MAX_INFLATED = 8 * 1024 * 1024
MAX_EVENTS_PER_BATCH = 500

SESSION_TTL = 8 * 3600
SESSION_COOKIE = "yg_session"

# 登录爆破防护：同 IP 连续失败 5 次锁 5 分钟。
LOGIN_MAX_FAILS = 5
LOGIN_LOCK_SECONDS = 300
LOGIN_WINDOW_SECONDS = 900

TITLE_LIMIT = 2000
TEXT_LIMIT = 8000

PBKDF2_ITERATIONS = 200_000

# 网页静态资源白名单：只发这三个 + 图标，路径不参与拼接，天然免疫目录穿越。
STATIC_FILES = {
    "/": ("index.html", "text/html; charset=utf-8"),
    "/index.html": ("index.html", "text/html; charset=utf-8"),
    "/app.js": ("app.js", "application/javascript; charset=utf-8"),
    "/style.css": ("style.css", "text/css; charset=utf-8"),
    "/favicon.svg": ("favicon.svg", "image/svg+xml"),
    "/favicon.ico": ("favicon.svg", "image/svg+xml"),
}

# 可读密码字符集：去掉 0/O、1/l/I 这些容易看错的。
PASSWORD_ALPHABET = "abcdefghijkmnpqrstuvwxyzABCDEFGHJKLMNPQRSTUVWXYZ23456789"


# ---------------------------------------------------------------------------
# 小工具
# ---------------------------------------------------------------------------

def now_ms() -> int:
    return int(time.time() * 1000)


def to_int(value: Any, default: Optional[int] = None) -> Optional[int]:
    """把 JSON 里五花八门的数字（int/float/str）压成 int；失败给 default。"""
    if value is None or isinstance(value, bool):
        return default
    if isinstance(value, int):
        return value
    if isinstance(value, float):
        return int(value)
    try:
        return int(str(value).strip())
    except (TypeError, ValueError):
        return default


def to_bool(value: Any) -> Optional[bool]:
    if value is None:
        return None
    if isinstance(value, bool):
        return value
    if isinstance(value, (int, float)):
        return bool(value)
    text = str(value).strip().lower()
    if text in ("1", "true", "yes", "on"):
        return True
    if text in ("0", "false", "no", "off"):
        return False
    return None


def clip(value: Any, limit: int) -> Optional[str]:
    """截断文本字段。手机端不可信，服务端必须自己兜住长度。"""
    if value is None:
        return None
    if not isinstance(value, str):
        value = str(value)
    return value[:limit]


def b64e(raw: bytes) -> str:
    return base64.urlsafe_b64encode(raw).decode("ascii").rstrip("=")


def b64d(text: str) -> bytes:
    pad = "=" * (-len(text) % 4)
    return base64.urlsafe_b64decode(text + pad)


def sha256_hex(text: str) -> str:
    return hashlib.sha256(text.encode("utf-8")).hexdigest()


# ---------------------------------------------------------------------------
# 配置（密码 / 令牌 / 会话密钥）
# ---------------------------------------------------------------------------

class Config:
    """`<data>/config.json` 的读写。

    文件里只出现密码的 PBKDF2 哈希与盐，绝不出现明文密码；`ingest_token` 与
    `session_secret` 都是随机生成，泄露 `config.json` 等于泄露这台服务器，权限设 0600。
    """

    def __init__(self, path: str, data: Dict[str, Any]):
        self.path = path
        self.data = data

    # -- 读取 / 落盘 ------------------------------------------------------

    @staticmethod
    def load_or_create(data_dir: str) -> Tuple["Config", Optional[str]]:
        path = os.path.join(data_dir, "config.json")
        plaintext_password: Optional[str] = None

        if os.path.exists(path):
            with open(path, "r", encoding="utf-8") as fh:
                data = json.load(fh)
        else:
            plaintext_password = "".join(secrets.choice(PASSWORD_ALPHABET) for _ in range(14))
            data = {
                "version": 1,
                "created_at": now_ms(),
                "ingest_token": "yg_" + secrets.token_hex(20),
                "session_secret": secrets.token_hex(32),
            }
            data["password"] = Config._hash_password(plaintext_password)
            cfg = Config(path, data)
            cfg.save()
            return cfg, plaintext_password

        # 兼容/修复：早期文件缺字段时补齐，不让服务起不来。
        changed = False
        if not data.get("ingest_token"):
            data["ingest_token"] = "yg_" + secrets.token_hex(20)
            changed = True
        if not data.get("session_secret"):
            data["session_secret"] = secrets.token_hex(32)
            changed = True
        cfg = Config(path, data)
        if changed:
            cfg.save()
        return cfg, plaintext_password

    def save(self) -> None:
        tmp = self.path + ".tmp"
        with open(tmp, "w", encoding="utf-8") as fh:
            json.dump(self.data, fh, ensure_ascii=False, indent=2)
        try:
            os.chmod(tmp, 0o600)
        except OSError:
            pass
        os.replace(tmp, self.path)

    # -- 密码 -------------------------------------------------------------

    @staticmethod
    def _hash_password(password: str, salt: Optional[bytes] = None) -> Dict[str, Any]:
        if salt is None:
            salt = os.urandom(16)
        digest = hashlib.pbkdf2_hmac(
            "sha256", password.encode("utf-8"), salt, PBKDF2_ITERATIONS
        )
        return {
            "algo": "pbkdf2_sha256",
            "iterations": PBKDF2_ITERATIONS,
            "salt": b64e(salt),
            "hash": b64e(digest),
        }

    def verify_password(self, password: str) -> bool:
        record = self.data.get("password") or {}
        try:
            salt = b64d(record["salt"])
            iterations = int(record.get("iterations") or PBKDF2_ITERATIONS)
            expected = b64d(record["hash"])
        except (KeyError, ValueError, TypeError):
            return False
        digest = hashlib.pbkdf2_hmac("sha256", password.encode("utf-8"), salt, iterations)
        return hmac.compare_digest(digest, expected)

    def set_password(self, password: str) -> None:
        self.data["password"] = self._hash_password(password)
        self.save()

    # -- 令牌 -------------------------------------------------------------

    @property
    def ingest_token(self) -> str:
        return str(self.data["ingest_token"])

    def rotate_ingest_token(self) -> str:
        token = "yg_" + secrets.token_hex(20)
        self.data["ingest_token"] = token
        self.save()
        return token

    @property
    def session_secret(self) -> bytes:
        return str(self.data["session_secret"]).encode("utf-8")

    # -- 会话 Cookie ------------------------------------------------------

    def issue_session(self) -> str:
        payload = b64e(json.dumps({"exp": int(time.time()) + SESSION_TTL}).encode("utf-8"))
        sig = hmac.new(self.session_secret, payload.encode("ascii"), hashlib.sha256).hexdigest()[:32]
        return payload + "." + sig

    def verify_session(self, cookie_value: Optional[str]) -> bool:
        if not cookie_value or "." not in cookie_value:
            return False
        payload, _, sig = cookie_value.rpartition(".")
        expected = hmac.new(
            self.session_secret, payload.encode("ascii", "ignore"), hashlib.sha256
        ).hexdigest()[:32]
        if not hmac.compare_digest(sig, expected):
            return False
        try:
            data = json.loads(b64d(payload).decode("utf-8"))
            return int(data.get("exp", 0)) > int(time.time())
        except (ValueError, TypeError, json.JSONDecodeError):
            return False


# ---------------------------------------------------------------------------
# 登录限流
# ---------------------------------------------------------------------------

class LoginThrottle:
    """内存里的失败计数。

    单进程部署够用；重启即清零，属于可接受的取舍（真要多实例得上 Redis，
    这个项目刻意不引外部依赖）。
    """

    def __init__(self) -> None:
        self._lock = threading.Lock()
        self._state: Dict[str, Dict[str, float]] = {}

    def blocked_for(self, ip: str) -> int:
        """返回还要锁多少秒；0 表示没锁。"""
        now = time.time()
        with self._lock:
            item = self._state.get(ip)
            if not item:
                return 0
            if item.get("lock_until", 0) > now:
                return int(item["lock_until"] - now) + 1
            # 窗口过了就把计数清掉
            if now - item.get("first", 0) > LOGIN_WINDOW_SECONDS:
                self._state.pop(ip, None)
            return 0

    def record_failure(self, ip: str) -> None:
        now = time.time()
        with self._lock:
            item = self._state.setdefault(ip, {"fails": 0, "first": now, "lock_until": 0})
            if now - item.get("first", now) > LOGIN_WINDOW_SECONDS:
                item["fails"] = 0
                item["first"] = now
            item["fails"] = item.get("fails", 0) + 1
            if item["fails"] >= LOGIN_MAX_FAILS:
                item["lock_until"] = now + LOGIN_LOCK_SECONDS
                item["fails"] = 0

    def reset(self, ip: str) -> None:
        with self._lock:
            self._state.pop(ip, None)


# ---------------------------------------------------------------------------
# 数据库
# ---------------------------------------------------------------------------

EVENT_COLUMNS = (
    "id", "device_id", "device_alias", "type", "app_package", "app_label",
    "title", "text", "big_text", "sub_text", "category", "channel", "priority",
    "posted_at", "received_at", "batch_id", "raw_json",
)


class Database:
    def __init__(self, path: str):
        self.path = path
        self._local = threading.local()
        self._write_lock = threading.Lock()
        self._init_schema()

    def conn(self) -> sqlite3.Connection:
        conn = getattr(self._local, "conn", None)
        if conn is None:
            conn = sqlite3.connect(self.path, timeout=15, isolation_level=None)
            conn.row_factory = sqlite3.Row
            conn.execute("PRAGMA journal_mode=WAL")
            conn.execute("PRAGMA synchronous=NORMAL")
            conn.execute("PRAGMA busy_timeout=15000")
            self._local.conn = conn
        return conn

    def _init_schema(self) -> None:
        conn = self.conn()
        conn.executescript(
            """
            CREATE TABLE IF NOT EXISTS events (
                id            TEXT NOT NULL,
                device_id     TEXT NOT NULL,
                device_alias  TEXT,
                type          TEXT NOT NULL DEFAULT 'notification',
                app_package   TEXT,
                app_label     TEXT,
                title         TEXT,
                text          TEXT,
                big_text      TEXT,
                sub_text      TEXT,
                category      TEXT,
                channel       TEXT,
                priority      INTEGER,
                posted_at     INTEGER,
                received_at   INTEGER NOT NULL,
                batch_id      TEXT,
                raw_json      TEXT,
                PRIMARY KEY (device_id, id)
            );
            CREATE INDEX IF NOT EXISTS idx_events_received ON events(received_at DESC);
            CREATE INDEX IF NOT EXISTS idx_events_app      ON events(app_package, received_at DESC);
            CREATE INDEX IF NOT EXISTS idx_events_device   ON events(device_id, received_at DESC);

            CREATE TABLE IF NOT EXISTS devices (
                id          TEXT PRIMARY KEY,
                alias       TEXT,
                manufacturer TEXT,
                model       TEXT,
                android     TEXT,
                app_version TEXT,
                battery     INTEGER,
                charging    INTEGER,
                network     TEXT,
                first_seen  INTEGER NOT NULL,
                last_seen   INTEGER NOT NULL,
                event_count INTEGER NOT NULL DEFAULT 0
            );
            """
        )

    # -- 写入 -------------------------------------------------------------

    def ingest(self, device: Dict[str, Any], events: List[Dict[str, Any]]) -> Tuple[int, int]:
        """返回 (accepted, duplicates)。整批一个事务，失败不留半截数据。"""
        device_id = device["id"]
        alias = clip(device.get("alias"), 64) or device_id[:8]
        stamp = now_ms()

        accepted = 0
        duplicates = 0
        with self._write_lock:
            conn = self.conn()
            conn.execute("BEGIN IMMEDIATE")
            try:
                conn.execute(
                    """
                    INSERT INTO devices (id, alias, manufacturer, model, android, app_version,
                                         battery, charging, network, first_seen, last_seen, event_count)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0)
                    ON CONFLICT(id) DO UPDATE SET
                        alias        = excluded.alias,
                        manufacturer = excluded.manufacturer,
                        model        = excluded.model,
                        android      = excluded.android,
                        app_version  = excluded.app_version,
                        battery      = COALESCE(excluded.battery, devices.battery),
                        charging     = COALESCE(excluded.charging, devices.charging),
                        network      = COALESCE(excluded.network, devices.network),
                        last_seen    = excluded.last_seen
                    """,
                    (
                        device_id, alias, clip(device.get("manufacturer"), 64),
                        clip(device.get("model"), 64), clip(device.get("android"), 32),
                        clip(device.get("app_version"), 32),
                        to_int(device.get("battery")), to_bool(device.get("charging")),
                        clip(device.get("network"), 16), stamp, stamp,
                    ),
                )
                for event in events:
                    cur = conn.execute(
                        """
                        INSERT OR IGNORE INTO events
                        (id, device_id, device_alias, type, app_package, app_label, title, text,
                         big_text, sub_text, category, channel, priority, posted_at, received_at,
                         batch_id, raw_json)
                        VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                        """,
                        (
                            event["id"], device_id, alias, event["type"],
                            event["app_package"], event["app_label"],
                            event["title"], event["text"], event["big_text"], event["sub_text"],
                            event["category"], event["channel"], event["priority"],
                            event["posted_at"], event["received_at"], event["batch_id"],
                            event["raw_json"],
                        ),
                    )
                    if cur.rowcount:
                        accepted += 1
                    else:
                        duplicates += 1
                if accepted:
                    conn.execute(
                        "UPDATE devices SET event_count = event_count + ?, last_seen = ? WHERE id = ?",
                        (accepted, stamp, device_id),
                    )
                conn.execute("COMMIT")
            except Exception:
                conn.execute("ROLLBACK")
                raise
        return accepted, duplicates

    # -- 查询 -------------------------------------------------------------

    def list_events(
        self,
        limit: int,
        cursor: Optional[str],
        query: Optional[str],
        app: Optional[str],
        device: Optional[str],
    ) -> Tuple[List[Dict[str, Any]], Optional[str], int]:
        where: List[str] = []
        args: List[Any] = []

        if query:
            like = "%" + query.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%"
            where.append(
                "(title LIKE ? ESCAPE '\\' OR text LIKE ? ESCAPE '\\' "
                "OR big_text LIKE ? ESCAPE '\\' OR app_label LIKE ? ESCAPE '\\')"
            )
            args.extend([like, like, like, like])
        if app:
            where.append("app_package = ?")
            args.append(app)
        if device:
            where.append("device_id = ?")
            args.append(device)

        # total 是「当前筛选下的总条数」，不含游标条件 —— 否则前端看到的永远是剩余数。
        total = self.count_events(list(where), list(args))

        parsed = parse_cursor(cursor)
        if parsed:
            where.append("(received_at < ? OR (received_at = ? AND rowid < ?))")
            args.extend([parsed[0], parsed[0], parsed[1]])

        sql = "SELECT rowid AS _rowid, * FROM events"
        if where:
            sql += " WHERE " + " AND ".join(where)
        sql += " ORDER BY received_at DESC, rowid DESC LIMIT ?"
        args.append(limit + 1)

        rows = self.conn().execute(sql, args).fetchall()
        has_more = len(rows) > limit
        rows = rows[:limit]

        items = [row_to_event(row) for row in rows]
        next_cursor = None
        if has_more and rows:
            last = rows[-1]
            next_cursor = "%d:%d" % (int(last["received_at"]), int(last["_rowid"]))
        return items, next_cursor, total

    def count_events(self, where: Optional[List[str]] = None, args: Optional[List[Any]] = None) -> int:
        sql = "SELECT COUNT(*) AS n FROM events"
        if where:
            sql += " WHERE " + " AND ".join(where)
        row = self.conn().execute(sql, args or []).fetchone()
        return int(row["n"]) if row else 0

    def stats(self) -> Dict[str, Any]:
        conn = self.conn()
        total = self.count_events()
        # 「今天」按服务器本地时区的 0 点算，用 datetime 拼比 time.mktime 直观。
        local_now = datetime.datetime.now()
        midnight = int(
            local_now.replace(hour=0, minute=0, second=0, microsecond=0).timestamp() * 1000
        )
        today_row = conn.execute(
            "SELECT COUNT(*) AS n FROM events WHERE received_at >= ?", (midnight,)
        ).fetchone()

        apps = [
            {"package": r["app_package"] or "", "label": r["app_label"] or r["app_package"] or "", "count": int(r["n"])}
            for r in conn.execute(
                "SELECT app_package, app_label, COUNT(*) AS n FROM events "
                "GROUP BY app_package ORDER BY n DESC LIMIT 60"
            ).fetchall()
        ]
        devices = [
            {
                "id": r["id"], "alias": r["alias"] or r["id"][:8],
                "count": int(r["event_count"] or 0), "last_seen": int(r["last_seen"] or 0),
            }
            for r in conn.execute(
                "SELECT id, alias, event_count, last_seen FROM devices ORDER BY last_seen DESC"
            ).fetchall()
        ]
        return {"today": int(today_row["n"]) if today_row else 0, "total": total,
                "apps": apps, "devices": devices}

    def list_devices(self) -> List[Dict[str, Any]]:
        rows = self.conn().execute(
            "SELECT * FROM devices ORDER BY last_seen DESC"
        ).fetchall()
        result = []
        for r in rows:
            result.append({
                "id": r["id"], "alias": r["alias"] or r["id"][:8],
                "manufacturer": r["manufacturer"] or "", "model": r["model"] or "",
                "android": r["android"] or "", "app_version": r["app_version"] or "",
                "battery": r["battery"], "charging": None if r["charging"] is None else bool(r["charging"]),
                "network": r["network"] or "",
                "first_seen": int(r["first_seen"] or 0), "last_seen": int(r["last_seen"] or 0),
                "event_count": int(r["event_count"] or 0),
            })
        return result

    def clear(self, scope: str, before: Optional[int]) -> int:
        with self._write_lock:
            conn = self.conn()
            if scope == "before" and before:
                cur = conn.execute("DELETE FROM events WHERE received_at < ?", (before,))
            else:
                cur = conn.execute("DELETE FROM events")
            return int(cur.rowcount or 0)

    def iter_events(self):
        cur = self.conn().execute(
            "SELECT * FROM events ORDER BY received_at ASC, rowid ASC"
        )
        for row in cur:
            yield row_to_event(row)


def parse_cursor(cursor: Optional[str]) -> Optional[Tuple[int, int]]:
    if not cursor:
        return None
    ts_text, _, row_text = cursor.partition(":")
    try:
        return int(ts_text), int(row_text)
    except ValueError:
        return None


def row_to_event(row: sqlite3.Row) -> Dict[str, Any]:
    raw = None
    if row["raw_json"]:
        try:
            raw = json.loads(row["raw_json"])
        except (ValueError, TypeError):
            raw = None
    return {
        "id": row["id"],
        "device_id": row["device_id"],
        "device_alias": row["device_alias"],
        "type": row["type"],
        "app_package": row["app_package"] or "",
        "app_label": row["app_label"] or row["app_package"] or "",
        "title": row["title"],
        "text": row["text"],
        "big_text": row["big_text"],
        "sub_text": row["sub_text"],
        "category": row["category"],
        "channel": row["channel"],
        "priority": row["priority"],
        "posted_at": int(row["posted_at"] or 0),
        "received_at": int(row["received_at"] or 0),
        "batch_id": row["batch_id"],
        "raw_json": raw,
    }


# ---------------------------------------------------------------------------
# SSE 广播
# ---------------------------------------------------------------------------

class Broadcaster:
    """给每个 SSE 连接一个容量 200 的队列。

    满了就直接丢最旧的：网页重新连上时会走一次增量拉取，丢掉实时帧不会丢数据。
    """

    def __init__(self) -> None:
        self._lock = threading.Lock()
        self._subs: List["queue.Queue[Dict[str, Any]]"] = []

    def subscribe(self) -> "queue.Queue[Dict[str, Any]]":
        q: "queue.Queue[Dict[str, Any]]" = queue.Queue(maxsize=200)
        with self._lock:
            self._subs.append(q)
        return q

    def unsubscribe(self, q: "queue.Queue[Dict[str, Any]]") -> None:
        with self._lock:
            try:
                self._subs.remove(q)
            except ValueError:
                pass

    def publish(self, event: Dict[str, Any]) -> None:
        with self._lock:
            targets = list(self._subs)
        for q in targets:
            try:
                q.put_nowait(event)
            except queue.Full:
                pass


# ---------------------------------------------------------------------------
# 应用上下文
# ---------------------------------------------------------------------------

class App:
    def __init__(self, data_dir: str):
        self.data_dir = data_dir
        self.started_at = time.time()
        os.makedirs(data_dir, exist_ok=True)

        self.config, self.generated_password = Config.load_or_create(data_dir)

        # YG_PASSWORD 优先级最高：容器部署时想固定密码就设它。
        env_password = os.environ.get("YG_PASSWORD", "").strip()
        if env_password and not self.config.verify_password(env_password):
            self.config.set_password(env_password)
            self.generated_password = None

        self.db = Database(os.path.join(data_dir, "relay.db"))
        self.broadcaster = Broadcaster()
        self.throttle = LoginThrottle()
        self.web_dir = os.path.join(os.path.dirname(os.path.abspath(__file__)), "web")

    # -- 事件规范化 -------------------------------------------------------

    def normalize_events(self, payload: Dict[str, Any]) -> Tuple[Dict[str, Any], List[Dict[str, Any]]]:
        device_raw = payload.get("device")
        if not isinstance(device_raw, dict):
            raise ValueError("缺少 device 对象")
        device_id = clip(device_raw.get("id"), 64)
        if not device_id:
            raise ValueError("device.id 为空")

        device = {
            "id": device_id,
            "alias": device_raw.get("alias"),
            "manufacturer": device_raw.get("manufacturer"),
            "model": device_raw.get("model"),
            "android": device_raw.get("android"),
            "app_version": device_raw.get("app_version"),
            "battery": device_raw.get("battery"),
            "charging": device_raw.get("charging"),
            "network": device_raw.get("network"),
        }

        raw_events = payload.get("events")
        if not isinstance(raw_events, list):
            raise ValueError("events 必须是数组")
        if len(raw_events) > MAX_EVENTS_PER_BATCH:
            raise ValueError("单批事件超过 %d 条" % MAX_EVENTS_PER_BATCH)

        received_at = now_ms()
        batch_id = clip(payload.get("batch_id"), 64) or ""

        events: List[Dict[str, Any]] = []
        for index, item in enumerate(raw_events):
            if not isinstance(item, dict):
                continue
            app_raw = item.get("app") if isinstance(item.get("app"), dict) else {}
            package = clip(app_raw.get("package") or item.get("package"), 128) or "unknown"
            label = clip(app_raw.get("label") or item.get("label"), 128) or package
            etype = clip(item.get("type"), 16) or "notification"
            if etype not in ("notification", "sms"):
                etype = "notification"

            title = clip(item.get("title"), TITLE_LIMIT)
            text = clip(item.get("text"), TEXT_LIMIT)
            big_text = clip(item.get("big_text"), TEXT_LIMIT)
            sub_text = clip(item.get("sub_text"), 512)
            posted_at = to_int(item.get("posted_at"), received_at) or received_at

            # 手机没给 id 时由服务端算一个稳定值：同一条通知重复上报仍然合并。
            event_id = clip(item.get("id"), 64)
            if not event_id:
                event_id = sha256_hex(
                    "|".join([device_id, package, str(posted_at), title or "", text or "", str(index)])
                )[:24]

            events.append({
                "id": event_id,
                "type": etype,
                "app_package": package,
                "app_label": label,
                "title": title,
                "text": text,
                "big_text": big_text,
                "sub_text": sub_text,
                "category": clip(item.get("category"), 64),
                "channel": clip(item.get("channel"), 64),
                "priority": to_int(item.get("priority")),
                "posted_at": posted_at,
                "received_at": received_at,
                "batch_id": batch_id,
                "raw_json": json.dumps(item, ensure_ascii=False)[:20000],
            })
        return device, events


# ---------------------------------------------------------------------------
# HTTP 处理
# ---------------------------------------------------------------------------

class Handler(BaseHTTPRequestHandler):
    server_version = "YGRelay/" + VERSION
    protocol_version = "HTTP/1.1"

    # -- 基础输出 ---------------------------------------------------------

    def log_message(self, fmt: str, *args: Any) -> None:  # noqa: A003 - 基类签名如此
        """只记方法/路径/状态/耗时，绝不记请求体（里面有消息正文）。"""
        sys.stderr.write(
            "%s %s - %s\n" % (self.log_date_time_string(), self.address_string(), fmt % args)
        )

    @property
    def app(self) -> App:
        return self.server.app  # type: ignore[attr-defined]

    def _send(self, status: int, body: bytes, content_type: str,
              extra_headers: Optional[Dict[str, str]] = None) -> None:
        self.send_response(status)
        self.send_header("Content-Type", content_type)
        self.send_header("Content-Length", str(len(body)))
        self.send_header("X-Content-Type-Options", "nosniff")
        self.send_header("Referrer-Policy", "no-referrer")
        for key, value in (extra_headers or {}).items():
            self.send_header(key, value)
        self.end_headers()
        if self.command != "HEAD":
            self.wfile.write(body)

    def json_out(self, status: int, payload: Any,
                 extra_headers: Optional[Dict[str, str]] = None) -> None:
        body = json.dumps(payload, ensure_ascii=False).encode("utf-8")
        headers = {"Cache-Control": "no-store"}
        headers.update(extra_headers or {})
        self._send(status, body, "application/json; charset=utf-8", headers)

    def error_out(self, status: int, message: str,
                  extra_headers: Optional[Dict[str, str]] = None) -> None:
        self.json_out(status, {"error": message}, extra_headers)

    def read_body(self) -> bytes:
        length = to_int(self.headers.get("Content-Length"), 0) or 0
        if length <= 0:
            self._body_read = True
            return b""
        if length > MAX_BODY:
            raise ValueError("请求体过大")
        raw = self.rfile.read(length)
        self._body_read = True
        if (self.headers.get("Content-Encoding") or "").lower() == "gzip":
            # 解压时限制输出，防压缩炸弹。
            decompressor = zlib.decompressobj(16 + zlib.MAX_WBITS)
            inflated = decompressor.decompress(raw, MAX_INFLATED)
            if decompressor.unconsumed_tail:
                raise ValueError("解压后内容过大")
            raw = inflated
        return raw

    def read_json(self) -> Dict[str, Any]:
        raw = self.read_body()
        if not raw:
            return {}
        try:
            data = json.loads(raw.decode("utf-8"))
        except (UnicodeDecodeError, json.JSONDecodeError) as exc:
            raise ValueError("JSON 解析失败：%s" % exc)
        if not isinstance(data, dict):
            raise ValueError("请求体必须是 JSON 对象")
        return data

    # -- 鉴权 -------------------------------------------------------------

    def cookies(self) -> Dict[str, str]:
        header = self.headers.get("Cookie") or ""
        result: Dict[str, str] = {}
        for part in header.split(";"):
            name, _, value = part.strip().partition("=")
            if name:
                result[name] = value
        return result

    def is_logged_in(self) -> bool:
        return self.app.config.verify_session(self.cookies().get(SESSION_COOKIE))

    def require_login(self) -> bool:
        if self.is_logged_in():
            return True
        self.error_out(401, "未登录")
        return False

    def client_ip(self) -> str:
        # 反代后取 X-Forwarded-For 的第一段；仅用于登录限流，不参与授权判定。
        forwarded = self.headers.get("X-Forwarded-For")
        if forwarded:
            return forwarded.split(",")[0].strip() or self.client_address[0]
        return self.client_address[0]

    def secure_cookie(self) -> bool:
        proto = (self.headers.get("X-Forwarded-Proto") or "").lower()
        return proto == "https" or isinstance(self.server.socket, ssl.SSLSocket)

    # -- 路由 -------------------------------------------------------------

    def do_GET(self) -> None:  # noqa: N802
        self._body_read = False
        try:
            self.route_get()
        except (BrokenPipeError, ConnectionResetError):
            self.close_connection = True
        except Exception as exc:  # 兜底：任何未预期异常都不该把进程带走
            self.log_error("GET 异常: %s", exc)
            try:
                self.error_out(500, "服务器内部错误")
            except OSError:
                pass
        finally:
            self.close_if_body_unread()

    def do_POST(self) -> None:  # noqa: N802
        self._body_read = False
        try:
            self.route_post()
        except (BrokenPipeError, ConnectionResetError):
            self.close_connection = True
        except Exception as exc:
            self.log_error("POST 异常: %s", exc)
            try:
                self.error_out(500, "服务器内部错误")
            except OSError:
                pass
        finally:
            self.close_if_body_unread()

    def close_if_body_unread(self) -> None:
        """没读完请求体就回复时，keep-alive 的下一帧会被残留 body 污染，直接关连接最稳。"""
        if not getattr(self, "_body_read", False):
            self.close_connection = True

    def do_HEAD(self) -> None:  # noqa: N802
        self.do_GET()

    def route_get(self) -> None:
        path = urllib.parse.urlparse(self.path)
        route = path.path
        query = urllib.parse.parse_qs(path.query)

        if route == "/healthz":
            self.json_out(200, {
                "ok": True,
                "version": VERSION,
                "uptime": int(time.time() - self.app.started_at),
                "events": self.app.db.count_events(),
            })
            return

        if route == "/api/session":
            self.json_out(200, {"authenticated": self.is_logged_in()})
            return

        if route == "/api/events":
            self.handle_events(query)
            return

        if route == "/api/stats":
            if not self.require_login():
                return
            self.json_out(200, self.app.db.stats())
            return

        if route == "/api/devices":
            if not self.require_login():
                return
            self.json_out(200, {"devices": self.app.db.list_devices()})
            return

        if route == "/api/stream":
            if not self.require_login():
                return
            self.handle_stream()
            return

        if route == "/api/export":
            if not self.require_login():
                return
            self.handle_export(query)
            return

        if route in STATIC_FILES:
            self.serve_static(route)
            return

        self.error_out(404, "Not Found")

    def route_post(self) -> None:
        route = urllib.parse.urlparse(self.path).path

        if route == "/api/login":
            self.handle_login()
            return
        if route == "/api/logout":
            self.json_out(200, {"ok": True}, {
                "Set-Cookie": "%s=; Path=/; Max-Age=0; HttpOnly; SameSite=Strict" % SESSION_COOKIE,
            })
            return
        if route == "/api/v1/ingest":
            self.handle_ingest()
            return
        if route == "/api/clear":
            if not self.require_login():
                return
            self.handle_clear()
            return
        if route == "/api/rotate-token":
            if not self.require_login():
                return
            self.json_out(200, {"ingest_token": self.app.config.rotate_ingest_token()})
            return

        self.error_out(404, "Not Found")

    # -- 各端点实现 -------------------------------------------------------

    def handle_login(self) -> None:
        ip = self.client_ip()
        blocked = self.app.throttle.blocked_for(ip)
        if blocked:
            self.error_out(429, "尝试次数过多，请稍后再试", {"Retry-After": str(blocked)})
            return

        try:
            body = self.read_json()
        except ValueError as exc:
            self.error_out(400, str(exc))
            return

        password = body.get("password")
        if not isinstance(password, str) or not password:
            self.error_out(400, "请输入密码")
            return

        if not self.app.config.verify_password(password):
            self.app.throttle.record_failure(ip)
            self.error_out(401, "密码错误")
            return

        self.app.throttle.reset(ip)
        cookie = self.app.config.issue_session()
        attrs = "Path=/; Max-Age=%d; HttpOnly; SameSite=Strict" % SESSION_TTL
        if self.secure_cookie():
            attrs += "; Secure"
        self.json_out(200, {"ok": True}, {"Set-Cookie": "%s=%s; %s" % (SESSION_COOKIE, cookie, attrs)})

    def handle_ingest(self) -> None:
        header = self.headers.get("Authorization") or ""
        if not header.lower().startswith("bearer "):
            self.error_out(401, "缺少 Bearer 令牌")
            return
        token = header[7:].strip()
        if not hmac.compare_digest(token, self.app.config.ingest_token):
            self.error_out(401, "令牌无效")
            return

        try:
            payload = self.read_json()
            device, events = self.app.normalize_events(payload)
        except ValueError as exc:
            self.error_out(400, str(exc))
            return

        try:
            accepted, duplicates = self.app.db.ingest(device, events)
        except sqlite3.Error as exc:
            self.log_error("写库失败: %s", exc)
            self.error_out(503, "数据库繁忙，请稍后重试")
            return

        # 只把真正入库的事件推给网页，重复的不重复播。
        if accepted:
            for event in events:
                raw = None
                if event["raw_json"]:
                    try:
                        raw = json.loads(event["raw_json"])
                    except (ValueError, TypeError):
                        raw = None
                self.app.broadcaster.publish({
                    "id": event["id"],
                    "device_id": device["id"],
                    "device_alias": device.get("alias") or device["id"][:8],
                    "type": event["type"],
                    "app_package": event["app_package"],
                    "app_label": event["app_label"],
                    "title": event["title"],
                    "text": event["text"],
                    "big_text": event["big_text"],
                    "sub_text": event["sub_text"],
                    "category": event["category"],
                    "channel": event["channel"],
                    "priority": event["priority"],
                    "posted_at": event["posted_at"],
                    "received_at": event["received_at"],
                    "batch_id": event["batch_id"],
                    "raw_json": raw,
                })

        self.log_message(
            "ingest device=%s accepted=%d duplicates=%d", device["id"][:8], accepted, duplicates
        )
        self.json_out(200, {"accepted": accepted, "duplicates": duplicates, "rejected": 0})

    def handle_events(self, query: Dict[str, List[str]]) -> None:
        if not self.require_login():
            return
        try:
            limit = min(max(int(query.get("limit", ["30"])[0]), 1), 200)
        except ValueError:
            limit = 30
        events, next_cursor, total = self.app.db.list_events(
            limit=limit,
            cursor=query.get("cursor", [None])[0],
            query=query.get("q", [None])[0],
            app=query.get("app", [None])[0],
            device=query.get("device", [None])[0],
        )
        self.json_out(200, {"events": events, "next_cursor": next_cursor, "total": total})

    def handle_stream(self) -> None:
        self.send_response(200)
        self.send_header("Content-Type", "text/event-stream; charset=utf-8")
        self.send_header("Cache-Control", "no-cache, no-transform")
        self.send_header("Connection", "close")
        self.send_header("X-Accel-Buffering", "no")
        self.end_headers()
        self.close_connection = True

        subscription = self.app.broadcaster.subscribe()
        try:
            self.wfile.write(b": connected\n\n")
            self.wfile.flush()
            while True:
                try:
                    event = subscription.get(timeout=15)
                except queue.Empty:
                    self.wfile.write(b": ping\n\n")
                    self.wfile.flush()
                    continue
                data = json.dumps(event, ensure_ascii=False).encode("utf-8")
                self.wfile.write(b"data: " + data + b"\n\n")
                self.wfile.flush()
        except (BrokenPipeError, ConnectionResetError, OSError):
            pass
        finally:
            self.app.broadcaster.unsubscribe(subscription)

    def handle_export(self, query: Dict[str, List[str]]) -> None:
        fmt = (query.get("format", ["jsonl"])[0] or "jsonl").lower()
        stamp = time.strftime("%Y%m%d-%H%M%S")
        if fmt == "json":
            body = json.dumps(
                {
                    "schema": "yg.relay.export.v1",
                    "exported_at": now_ms(),
                    "events": list(self.app.db.iter_events()),
                },
                ensure_ascii=False,
            ).encode("utf-8")
            self._send(200, body, "application/json; charset=utf-8", {
                "Content-Disposition": 'attachment; filename="yg-relay-%s.json"' % stamp,
                "Cache-Control": "no-store",
            })
            return

        lines = [json.dumps(event, ensure_ascii=False) for event in self.app.db.iter_events()]
        body = ("\n".join(lines) + ("\n" if lines else "")).encode("utf-8")
        self._send(200, body, "application/x-ndjson; charset=utf-8", {
            "Content-Disposition": 'attachment; filename="yg-relay-%s.jsonl"' % stamp,
            "Cache-Control": "no-store",
        })

    def handle_clear(self) -> None:
        try:
            body = self.read_json()
        except ValueError as exc:
            self.error_out(400, str(exc))
            return
        scope = body.get("scope") or "all"
        if scope not in ("all", "before"):
            self.error_out(400, "scope 只能是 all 或 before")
            return
        deleted = self.app.db.clear(scope, to_int(body.get("before")))
        self.json_out(200, {"deleted": deleted})

    # -- 静态资源 ---------------------------------------------------------

    def serve_static(self, route: str) -> None:
        filename, content_type = STATIC_FILES[route]
        full = os.path.join(self.app.web_dir, filename)
        try:
            with open(full, "rb") as fh:
                body = fh.read()
        except OSError:
            self.error_out(404, "前端文件缺失：%s" % filename)
            return
        self._send(200, body, content_type, {
            "Cache-Control": "no-cache",
            # 单页应用，只允许自己这一份脚本；方便以后加沙箱。
            "Content-Security-Policy": (
                "default-src 'self'; img-src 'self' data:; style-src 'self' 'unsafe-inline'; "
                "script-src 'self'; connect-src 'self'; base-uri 'none'; form-action 'none'"
            ),
        })


# ---------------------------------------------------------------------------
# 启动
# ---------------------------------------------------------------------------

def build_server(host: str, port: int, app: App, cert: Optional[str],
                 key: Optional[str]) -> ThreadingHTTPServer:
    httpd = ThreadingHTTPServer((host, port), Handler)
    httpd.daemon_threads = True
    httpd.app = app  # type: ignore[attr-defined]
    if cert and key:
        context = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
        context.load_cert_chain(certfile=cert, keyfile=key)
        httpd.socket = context.wrap_socket(httpd.socket, server_side=True)
    return httpd


def main(argv: Optional[List[str]] = None) -> int:
    parser = argparse.ArgumentParser(description="YG 消息转发中继服务端")
    parser.add_argument("--host", default="0.0.0.0", help="监听地址（默认 0.0.0.0）")
    parser.add_argument("--port", type=int, default=int(os.environ.get("YG_PORT", "8787")),
                        help="监听端口（默认 8787）")
    parser.add_argument("--data-dir", default=os.environ.get("YG_DATA_DIR", "./data"),
                        help="数据目录，存 config.json 与 relay.db")
    parser.add_argument("--cert", help="TLS 证书路径（配合 --key）")
    parser.add_argument("--key", help="TLS 私钥路径")
    args = parser.parse_args(argv)

    app = App(os.path.abspath(args.data_dir))
    httpd = build_server(args.host, args.port, app, args.cert, args.key)

    print("YG Relay %s 已启动" % VERSION)
    print("  监听:      http://%s:%d" % (args.host, args.port))
    print("  数据目录:  %s" % app.data_dir)
    if app.generated_password:
        print("  ★ 初始密码: %s   （只打印这一次，已存哈希）" % app.generated_password)
    else:
        print("  网页密码:  沿用 config.json / 环境变量 YG_PASSWORD")
    print("  接入令牌:  %s" % app.config.ingest_token)
    print("  手机填写:  http://<服务器IP>:%d  + 上面这串令牌" % args.port)

    def shutdown(signum: int, frame: Any) -> None:
        print("\n收到信号 %d，正在关闭…" % signum)
        threading.Thread(target=httpd.shutdown, daemon=True).start()

    signal.signal(signal.SIGINT, shutdown)
    try:
        signal.signal(signal.SIGTERM, shutdown)
    except (AttributeError, ValueError):
        pass

    try:
        httpd.serve_forever(poll_interval=0.5)
    finally:
        httpd.server_close()
        print("已停止。")
    return 0


if __name__ == "__main__":
    sys.exit(main())
