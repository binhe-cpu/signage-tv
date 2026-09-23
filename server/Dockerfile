# 门店屏网页后台 · 容器镜像
#
# **什么时候需要它**：只有要用对象存储（COS / OSS）的时候。
# 素材放 NAS 本地（local 模式）根本用不到 boto3，直接用官方 python 镜像挂载代码
# 就能跑，不需要构建 —— 见 docker-compose.yml，默认那条路是零构建的。
#
# 构建（在工程根目录执行，上下文必须是工程根，因为要 server/ 和 tools/）：
#     docker build -f server/Dockerfile -t signage-admin:1.2.0 .
#
# 国内 pip 慢的话指定个源：
#     docker build -f server/Dockerfile \
#         --build-arg PIP_INDEX=https://pypi.tuna.tsinghua.edu.cn/simple \
#         -t signage-admin:1.2.0 .

FROM python:3.12-slim

# 时区数据。容器默认 UTC，日志里那行时间戳会差 8 小时。
# 在线判定用的是 epoch 差值（服务器收到的时刻），不受时区影响，纯粹是为了看日志顺眼。
RUN apt-get update \
    && apt-get install -y --no-install-recommends tzdata \
    && rm -rf /var/lib/apt/lists/*

# PYTHONDONTWRITEBYTECODE：代码目录是只读挂载，别去写 __pycache__
# PYTHONUNBUFFERED：日志即时输出，不然 docker logs 是空的
ENV TZ=Asia/Shanghai \
    PYTHONUNBUFFERED=1 \
    PYTHONDONTWRITEBYTECODE=1 \
    SIGNAGE_DATA_DIR=/data \
    SIGNAGE_STORAGE=local:/site

# 这个镜像存在的唯一理由就是它。boto3 全是纯 Python，
# 所以在 x86 和 ARM 的群晖上装出来是一样的（没有要编译的东西）
ARG PIP_INDEX=https://pypi.org/simple
RUN pip install --no-cache-dir -i ${PIP_INDEX} boto3

WORKDIR /app

# 只拷运行必需的四个东西。故意不用 COPY server/ 整个目录 ——
# 那会把 dist/ 里 27MB 的 exe、打包脚本、自测脚本一起塞进镜像
COPY server/signage_admin.py /app/server/
COPY server/backend.py /app/server/
COPY server/web/ /app/server/web/
COPY tools/signage_core.py /app/tools/

VOLUME ["/data", "/site"]
EXPOSE 8600

CMD ["python3", "/app/server/signage_admin.py", "--host", "0.0.0.0", "--port", "8600"]
