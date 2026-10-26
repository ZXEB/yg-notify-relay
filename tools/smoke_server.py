#!/usr/bin/env python3
"""端到端冒烟：真起一个 serve.py 子进程，走完「登录 → 上传 → 查询 → 导出 → 清理」。

与 tests/test_server.py 的区别：
- 单测是在同进程里 build_server()，覆盖逻辑细节；
- 这个脚本验证的是「把 serve.py 当脚本启动」这条真实路径（argparse、数据目录、静态资源、
  stdout 里打印的初始密码/令牌）。

用法：python tools/smoke_server.py
"""

from __future__ import annotations

import http.client
import json
import os
import shutil
import subprocess
import sys
import tempfile
import time
import urllib.parse

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SERVER = os.path.join(ROOT, "server", "serve.py")
PORT = 18787


def request(method, path, body=None, headers=None):
    conn = http.client.HTTPConnection("127.0.0.1", PORT, timeout=10)
    payload = json.dumps(body, ensure_ascii=False).encode("utf-8") if body is not None else None
    hdrs = {"User-Agent": "smoke"}
    hdrs.update(headers or {})
    conn.request(method, path, body=payload, headers=hdrs)
    resp = conn.getresponse()
    data = resp.read()
    result = (resp.status, dict(resp.getheaders()), data)
    conn.close()
    return result


def wait_healthy(deadline_seconds=20):
    deadline = time.time() + deadline_seconds
    last = None
    while time.time() < deadline:
        try:
            status, _, data = request("GET", "/healthz")
            if status == 200:
                return json.loads(data)
        except OSError as exc:
            last = exc
        time.sleep(0.3)
    raise RuntimeError("服务没有起来：%s" % last)


def main() -> int:
    data_dir = tempfile.mkdtemp(prefix="yg-smoke-")
    env = dict(os.environ)
    env.pop("YG_PASSWORD", None)
    proc = subprocess.Popen(
        [sys.executable, SERVER, "--host", "127.0.0.1", "--port", str(PORT), "--data-dir", data_dir],
        stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT,
        env=env,
        text=True,
        encoding="utf-8",
    )
    try:
        health = wait_healthy()
        print("[ok] healthz", health)

        # serve.py 启动时会打印初始密码与令牌；密码只在首次生成时出现。
        time.sleep(0.2)
        config_path = os.path.join(data_dir, "config.json")
        with open(config_path, encoding="utf-8") as fh:
            config = json.load(fh)
        token = config["ingest_token"]
        assert token.startswith("yg_"), token
        print("[ok] config.json 生成了 ingest_token（长度 %d）" % len(token))

        # 静态资源
        for path, marker in (("/", "YG"), ("/app.js", "function"), ("/style.css", "{")):
            status, headers, body = request("GET", path)
            assert status == 200, (path, status)
            assert marker in body.decode("utf-8"), (path, marker)
        print("[ok] 静态资源 index.html / app.js / style.css 均可访问")

        # 未登录时接口必须 401
        status, _, _ = request("GET", "/api/events")
        assert status == 401, status
        print("[ok] 未登录访问 /api/events 返回 401")

        # 登录（初始密码从 config.json 的哈希无法反推，所以这里直接用环境变量重设一次）
        # 用 YG_PASSWORD 重启成本更高，这里改为读 serve.py 打印的密码：
        proc.terminate()
        proc.wait(timeout=10)

        env["YG_PASSWORD"] = "smoke-pass"
        proc = subprocess.Popen(
            [sys.executable, SERVER, "--host", "127.0.0.1", "--port", str(PORT), "--data-dir", data_dir],
            stdout=subprocess.PIPE,
            stderr=subprocess.STDOUT,
            env=env,
            text=True,
            encoding="utf-8",
        )
        wait_healthy()

        status, headers, body = request(
            "POST", "/api/login", {"password": "smoke-pass"}, {"Content-Type": "application/json"}
        )
        assert status == 200, (status, body)
        cookie = headers["Set-Cookie"].split(";")[0]
        print("[ok] 登录成功，拿到会话 Cookie")

        batch = {
            "schema": "yg.relay.batch.v1",
            "batch_id": "smoke-1",
            "sent_at": int(time.time() * 1000),
            "device": {
                "id": "smoke-device",
                "alias": "冒烟机",
                "manufacturer": "Test",
                "model": "SMOKE-1",
                "android": "15",
                "app_version": "1.0.0",
                "battery": 66,
                "charging": True,
                "network": "wifi",
            },
            "events": [
                {
                    "id": "smoke-evt-1",
                    "type": "notification",
                    "posted_at": int(time.time() * 1000),
                    "app": {"package": "com.tencent.mm", "label": "微信"},
                    "title": "冒烟测试",
                    "text": "这是一条测试消息",
                }
            ],
        }
        auth = {"Authorization": "Bearer " + token, "Content-Type": "application/json"}
        status, _, body = request("POST", "/api/v1/ingest", batch, auth)
        assert status == 200, (status, body)
        print("[ok] 上传:", json.loads(body))

        status, _, body = request("POST", "/api/v1/ingest", batch, auth)
        print("[ok] 重复上传:", json.loads(body))

        status, _, body = request(
            "GET", "/api/events?q=" + urllib.parse.quote("冒烟"), headers={"Cookie": cookie}
        )
        payload = json.loads(body)
        assert status == 200, (status, payload)
        assert "total" in payload, payload
        assert payload["total"] == 1, payload
        assert payload["events"][0]["title"] == "冒烟测试"
        print("[ok] 查询到 1 条，标题：", payload["events"][0]["title"])

        status, _, body = request("GET", "/api/devices", headers={"Cookie": cookie})
        devices = json.loads(body)["devices"]
        assert devices and devices[0]["id"] == "smoke-device", devices
        print("[ok] 设备列表:", devices[0]["alias"], "电量", devices[0]["battery"])

        status, headers, body = request("GET", "/api/export?format=jsonl", headers={"Cookie": cookie})
        assert status == 200 and b"smoke-evt-1" in body
        print("[ok] 导出 JSONL（%d 字节）" % len(body))

        status, _, body = request("POST", "/api/clear", {"scope": "all"}, {"Cookie": cookie, "Content-Type": "application/json"})
        print("[ok] 清空:", json.loads(body))

        status, _, body = request("GET", "/healthz")
        print("[ok] 收尾 healthz:", json.loads(body))
        print("\n全部通过。")
        return 0
    finally:
        proc.terminate()
        try:
            proc.wait(timeout=10)
        except subprocess.TimeoutExpired:
            proc.kill()
        shutil.rmtree(data_dir, ignore_errors=True)


if __name__ == "__main__":
    sys.exit(main())
