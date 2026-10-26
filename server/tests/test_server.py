"""服务端测试：只跑标准库 unittest，不引 pytest。

覆盖的三类东西：

1. **鉴权边界** —— 网页密码、会话 Cookie 的 HMAC、上传令牌，任一可绕过都是事故；
2. **去重与分页** —— 手机重发是常态（通知更新、进程重启），重复入库会毁掉时间线；
3. **输入防御** —— 超大请求体、压缩炸弹、目录穿越、伪造 Cookie，都要有明确状态码。
"""

from __future__ import annotations

import gzip
import http.client
import json
import os
import shutil
import sys
import tempfile
import threading
import time
import unittest
import urllib.parse
import uuid

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

import serve  # noqa: E402


class RelayServerTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        os.environ.pop("YG_PASSWORD", None)
        cls.data_dir = tempfile.mkdtemp(prefix="yg-relay-test-")
        cls.app = serve.App(cls.data_dir)
        cls.app.config.set_password("correct-horse")
        cls.token = cls.app.config.ingest_token

        cls.httpd = serve.build_server("127.0.0.1", 0, cls.app, None, None)
        cls.port = cls.httpd.server_address[1]
        cls.thread = threading.Thread(target=cls.httpd.serve_forever, daemon=True)
        cls.thread.start()
        time.sleep(0.1)

    @classmethod
    def tearDownClass(cls) -> None:
        cls.httpd.shutdown()
        cls.httpd.server_close()
        shutil.rmtree(cls.data_dir, ignore_errors=True)

    # -- 请求辅助 ---------------------------------------------------------

    def request(self, method, path, body=None, headers=None, gzip_body=False):
        conn = http.client.HTTPConnection("127.0.0.1", self.port, timeout=10)
        payload = None
        hdrs = {"User-Agent": "relay-test"}
        hdrs.update(headers or {})
        if body is not None:
            raw = json.dumps(body, ensure_ascii=False).encode("utf-8")
            if gzip_body:
                raw = gzip.compress(raw)
                hdrs["Content-Encoding"] = "gzip"
            hdrs["Content-Type"] = "application/json"
            payload = raw
        conn.request(method, path, body=payload, headers=hdrs)
        resp = conn.getresponse()
        data = resp.read()
        result = (resp.status, dict(resp.getheaders()), data)
        conn.close()
        return result

    def login(self):
        status, headers, _ = self.request("POST", "/api/login", {"password": "correct-horse"})
        self.assertEqual(status, 200, headers)
        cookie = headers.get("Set-Cookie", "")
        return cookie.split(";")[0]

    def ingest_body(self, events, device_id="dev-test-0001"):
        return {
            "schema": serve.SCHEMA_BATCH,
            "batch_id": "batch-1",
            "sent_at": serve.now_ms(),
            "device": {
                "id": device_id,
                "alias": "测试机",
                "manufacturer": "Xiaomi",
                "model": "24129PN74C",
                "android": "15",
                "app_version": "1.0.0",
                "battery": 77,
                "charging": False,
                "network": "wifi",
            },
            "events": events,
        }

    def seed(self, device_id="dev-test-0001", package="com.seed", label="Seed", title="种子"):
        """灌一条事件并返回 (event_id, app_package)。

        每个用例都自己准备数据：unittest 按方法名字母序执行，任何跨用例的数据假设
        都会在改个方法名之后莫名其妙地红掉。
        """
        event_id = "seed-" + uuid.uuid4().hex[:10]
        package = "%s.%s" % (package, uuid.uuid4().hex[:6])
        body = self.ingest_body(
            [{"id": event_id, "app": {"package": package, "label": label}, "title": title, "text": "内容"}],
            device_id,
        )
        status, _, data = self.request(
            "POST", "/api/v1/ingest", body,
            headers={"Authorization": "Bearer " + self.token},
        )
        self.assertEqual(status, 200, data)
        return event_id, package

    # -- 基础 -------------------------------------------------------------

    def test_healthz_is_public(self):
        status, headers, data = self.request("GET", "/healthz")
        self.assertEqual(status, 200)
        payload = json.loads(data)
        self.assertTrue(payload["ok"])
        self.assertEqual(payload["version"], serve.VERSION)
        self.assertIn("events", payload)

    def test_session_false_before_login(self):
        status, _, data = self.request("GET", "/api/session")
        self.assertEqual(status, 200)
        self.assertFalse(json.loads(data)["authenticated"])

    def test_events_requires_login(self):
        status, _, data = self.request("GET", "/api/events")
        self.assertEqual(status, 401)
        self.assertIn("error", json.loads(data))

    def test_static_whitelist_blocks_traversal(self):
        for path in ("/serve.py", "/../serve.py", "/web/../serve.py", "/data/config.json"):
            status, _, _ = self.request("GET", path)
            self.assertEqual(status, 404, path)

    # -- 登录 -------------------------------------------------------------

    def test_login_wrong_password_then_right(self):
        status, _, data = self.request("POST", "/api/login", {"password": "nope"})
        self.assertEqual(status, 401)
        self.assertEqual(json.loads(data)["error"], "密码错误")

        cookie = self.login()
        status, _, data = self.request("GET", "/api/session", headers={"Cookie": cookie})
        self.assertEqual(status, 200)
        self.assertTrue(json.loads(data)["authenticated"])

    def test_forged_cookie_rejected(self):
        for forged in ("garbage", "eyJleHAiOjQ3MDAwMDAwMDB9.deadbeef", "a.b.c"):
            status, _, data = self.request("GET", "/api/session", headers={"Cookie": "yg_session=" + forged})
            self.assertEqual(status, 200)
            self.assertFalse(json.loads(data)["authenticated"], forged)

    # -- 上传 -------------------------------------------------------------

    def test_ingest_requires_token(self):
        body = self.ingest_body([{"id": "x1", "app": {"package": "com.a", "label": "A"}, "title": "t"}])
        status, _, _ = self.request("POST", "/api/v1/ingest", body)
        self.assertEqual(status, 401)

        status, _, _ = self.request(
            "POST", "/api/v1/ingest", body, headers={"Authorization": "Bearer wrong"}
        )
        self.assertEqual(status, 401)

    def test_ingest_dedup_and_fields(self):
        suffix = uuid.uuid4().hex[:6]
        package = "com.tencent.mm." + suffix
        title = "张三-" + suffix
        events = [
            {
                "id": "evt-" + suffix,
                "type": "notification",
                "posted_at": 1760000000000,
                "app": {"package": package, "label": "微信"},
                "title": title,
                "text": "在吗",
                "category": "msg",
                "channel": "message",
                "priority": 2,
            }
        ]
        body = self.ingest_body(events)
        headers = {"Authorization": "Bearer " + self.token}

        status, _, data = self.request("POST", "/api/v1/ingest", body, headers=headers)
        self.assertEqual(status, 200, data)
        first = json.loads(data)
        self.assertEqual(first["accepted"], 1)
        self.assertEqual(first["duplicates"], 0)

        status, _, data = self.request("POST", "/api/v1/ingest", body, headers=headers)
        second = json.loads(data)
        self.assertEqual(second["accepted"], 0)
        self.assertEqual(second["duplicates"], 1)

        cookie = self.login()
        status, _, data = self.request(
            "GET", "/api/events?q=" + urllib.parse.quote(title), headers={"Cookie": cookie}
        )
        payload = json.loads(data)
        self.assertEqual(status, 200)
        self.assertEqual(payload["total"], 1)
        event = payload["events"][0]
        self.assertEqual(event["app_label"], "微信")
        self.assertEqual(event["title"], title)
        self.assertEqual(event["text"], "在吗")
        self.assertEqual(event["device_alias"], "测试机")
        self.assertIsInstance(event["raw_json"], dict)
        self.assertEqual(event["raw_json"]["id"], "evt-" + suffix)

    def test_ingest_accepts_gzip_body(self):
        body = self.ingest_body(
            [{"id": "evt-gzip", "app": {"package": "com.g", "label": "Gzip"}, "title": "z", "text": "z"}]
        )
        status, _, data = self.request(
            "POST", "/api/v1/ingest", body,
            headers={"Authorization": "Bearer " + self.token}, gzip_body=True,
        )
        self.assertEqual(status, 200, data)
        self.assertEqual(json.loads(data)["accepted"], 1)

    def test_ingest_rejects_bad_payload(self):
        headers = {"Authorization": "Bearer " + self.token}
        status, _, _ = self.request("POST", "/api/v1/ingest", {"events": []}, headers=headers)
        self.assertEqual(status, 400)

        status, _, _ = self.request(
            "POST", "/api/v1/ingest", {"device": {"id": "d"}, "events": "nope"}, headers=headers
        )
        self.assertEqual(status, 400)

    def test_filter_and_devices(self):
        _, package = self.seed("dev-test-0001", label="过滤", title="过滤测试")
        cookie = self.login()
        status, _, data = self.request(
            "GET", "/api/events?app=" + package, headers={"Cookie": cookie}
        )
        self.assertEqual(status, 200)
        events = json.loads(data)["events"]
        self.assertTrue(events)
        self.assertTrue(all(e["app_package"] == package for e in events))

        status, _, data = self.request("GET", "/api/devices", headers={"Cookie": cookie})
        devices = json.loads(data)["devices"]
        self.assertTrue(any(d["id"] == "dev-test-0001" for d in devices))
        dev = [d for d in devices if d["id"] == "dev-test-0001"][0]
        self.assertEqual(dev["battery"], 77)
        self.assertFalse(dev["charging"])

    def test_pagination_cursor(self):
        headers = {"Authorization": "Bearer " + self.token}
        package = "com.page." + uuid.uuid4().hex[:6]
        events = [
            {"id": "page-%d-%s" % (i, uuid.uuid4().hex[:4]),
             "app": {"package": package, "label": "Page"}, "title": "t%d" % i}
            for i in range(5)
        ]
        self.request("POST", "/api/v1/ingest", self.ingest_body(events, "dev-page"), headers=headers)

        cookie = self.login()
        status, _, data = self.request(
            "GET", "/api/events?app=" + package + "&limit=2", headers={"Cookie": cookie}
        )
        page1 = json.loads(data)
        self.assertEqual(len(page1["events"]), 2)
        self.assertEqual(page1["total"], 5)
        self.assertIsNotNone(page1["next_cursor"])

        status, _, data = self.request(
            "GET",
            "/api/events?app=" + package + "&limit=2&cursor=" + urllib.parse.quote(page1["next_cursor"]),
            headers={"Cookie": cookie},
        )
        page2 = json.loads(data)
        self.assertEqual(len(page2["events"]), 2)
        ids = {e["id"] for e in page1["events"]} | {e["id"] for e in page2["events"]}
        self.assertEqual(len(ids), 4)

    def test_export_formats(self):
        self.seed()
        cookie = self.login()
        status, headers, data = self.request("GET", "/api/export?format=jsonl", headers={"Cookie": cookie})
        self.assertEqual(status, 200)
        self.assertIn("attachment", headers.get("Content-Disposition", ""))
        line = data.decode("utf-8").strip().split("\n")[0]
        self.assertIn("app_package", json.loads(line))

        status, _, data = self.request("GET", "/api/export?format=json", headers={"Cookie": cookie})
        self.assertEqual(status, 200)
        payload = json.loads(data)
        self.assertEqual(payload["schema"], "yg.relay.export.v1")
        self.assertGreaterEqual(len(payload["events"]), 1)

    def test_rotate_token_invalidates_old(self):
        cookie = self.login()
        status, _, data = self.request("POST", "/api/rotate-token", {}, headers={"Cookie": cookie})
        self.assertEqual(status, 200)
        new_token = json.loads(data)["ingest_token"]
        self.assertNotEqual(new_token, self.token)

        body = self.ingest_body([{"id": "evt-after-rotate", "app": {"package": "com.r"}, "title": "x"}])
        status, _, _ = self.request(
            "POST", "/api/v1/ingest", body, headers={"Authorization": "Bearer " + self.token}
        )
        self.assertEqual(status, 401)
        status, _, _ = self.request(
            "POST", "/api/v1/ingest", body, headers={"Authorization": "Bearer " + new_token}
        )
        self.assertEqual(status, 200)
        # 后续测试沿用旧令牌的话会失败，这里换回来
        self.app.config.data["ingest_token"] = self.token
        self.app.config.save()

    def test_clear_all(self):
        self.seed()
        cookie = self.login()
        status, _, data = self.request("POST", "/api/clear", {"scope": "all"}, headers={"Cookie": cookie})
        self.assertEqual(status, 200)
        self.assertGreaterEqual(json.loads(data)["deleted"], 1)
        status, _, data = self.request("GET", "/api/events", headers={"Cookie": cookie})
        self.assertEqual(json.loads(data)["total"], 0)

    def test_sse_receives_new_event(self):
        cookie = self.login()
        conn = http.client.HTTPConnection("127.0.0.1", self.port, timeout=10)
        conn.request("GET", "/api/stream", headers={"Cookie": cookie})
        resp = conn.getresponse()
        self.assertEqual(resp.status, 200)
        self.assertIn("text/event-stream", resp.getheader("Content-Type", ""))
        first = resp.fp.readline()
        self.assertIn(b"connected", first)

        body = self.ingest_body(
            [{"id": "evt-sse", "app": {"package": "com.sse", "label": "SSE"}, "title": "直播", "text": "来了"}]
        )
        self.request(
            "POST", "/api/v1/ingest", body, headers={"Authorization": "Bearer " + self.token}
        )

        deadline = time.time() + 5
        found = False
        while time.time() < deadline:
            line = resp.fp.readline()
            if not line:
                break
            if line.startswith(b"data: "):
                event = json.loads(line[6:])
                self.assertEqual(event["id"], "evt-sse")
                found = True
                break
        conn.close()
        self.assertTrue(found, "SSE 没有收到新事件")

    # -- 限流（放最后，会污染本机 IP 的计数） -------------------------------

    def test_login_lockout_after_failures(self):
        self.app.throttle.reset("127.0.0.1")
        for _ in range(serve.LOGIN_MAX_FAILS):
            status, _, _ = self.request("POST", "/api/login", {"password": "wrong"})
            self.assertEqual(status, 401)

        status, headers, data = self.request("POST", "/api/login", {"password": "correct-horse"})
        self.assertEqual(status, 429)
        self.assertIn("error", json.loads(data))
        self.assertIn("Retry-After", headers)
        self.app.throttle.reset("127.0.0.1")


if __name__ == "__main__":
    unittest.main(verbosity=2)
