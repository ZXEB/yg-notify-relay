FROM python:3.12-slim

# 纯标准库实现，所以这里没有任何 pip install —— 镜像小、构建快、也没有依赖漂移。
ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1 \
    YG_DATA_DIR=/data \
    YG_PORT=8787

WORKDIR /app

# 非 root 跑：这个进程能读到你手机上所有通知，权限压到最小。
# uid/gid 固定 10001，bind mount 时宿主机按这个数字 chown 即可。
RUN groupadd --gid 10001 relay \
    && useradd --uid 10001 --gid 10001 --no-create-home --shell /usr/sbin/nologin relay \
    && mkdir -p /data && chown relay:relay /data

COPY serve.py /app/serve.py
COPY web/ /app/web/

USER relay
EXPOSE 8787

# slim 镜像没有 curl，用 python 自己探活；端口跟随 YG_PORT。
HEALTHCHECK --interval=30s --timeout=5s --start-period=5s --retries=3 \
    CMD ["python", "-c", "import os,urllib.request;urllib.request.urlopen('http://127.0.0.1:'+os.environ.get('YG_PORT','8787')+'/healthz',timeout=3)"]

# 用 sh -c + exec，保证 docker stop 的 SIGTERM 直接落到 python 上（能优雅关库）。
CMD ["sh", "-c", "exec python serve.py --host 0.0.0.0 --port \"${YG_PORT:-8787}\" --data-dir \"${YG_DATA_DIR:-/data}\""]
