# 门店屏 · 网页后台

在浏览器里管线上内容：看现在有什么、拖顺序、传新素材、点发布。
不依赖电脑上的命令行，人在哪儿都能改——**这就是第 2 步那个 `publish.py` 的网页版**，
底下用的是同一条链路（素材进对象存储 → 写清单 → 电视每 5 分钟来拉），
所以电视端 App 一行都不用改。

```
浏览器 ──① 打开网页──► 这台服务 ──② 签一张上传凭证──► 浏览器
   │                      │
   │                      └──③ 写清单（playlist.json）
   │
   └──④ 拿凭证直接把文件 PUT 到对象存储（不经过服务器）
```

**素材不经过服务器**：服务只签凭证，视频从你的手机/电脑直接进对象存储。
传 500MB 的片子也不会占服务器带宽，更不会因为服务重启传一半断掉。

---

## 一、先在本机跑起来看看

不用配对象存储，用本地目录就能跑通：

```bash
# Windows（Git Bash）
cd signage-tv
PY=python            # 换成你自己的 python（要用对象存储的话得装了 boto3）

SIGNAGE_STORAGE="local:D:/signage-online" \
SIGNAGE_ADMIN_CODE="自己设一个访问码" \
"$PY" server/signage_admin.py --port 8600 --host 0.0.0.0
```

Windows 也可以双击 `server\start-server.bat`（先把里面的两个值改成你的）。

然后浏览器打开 <http://127.0.0.1:8600/>。

> **`--host` 用 `0.0.0.0`**，不要用 `127.0.0.1`：后者只有本机能访问，
> 电视就永远拉不到内容。页面最下面那张「电视端填什么」卡片会告诉你电视该填什么地址
> ——本地目录模式时它就是那个地址的来源（详见第五节）。

跑一遍自测（会建临时目录、发真实 HTTP 请求，不动你的数据）：

```bash
"$PY" server/selftest.py
```

---

## 二、打成独立 exe（目标机器不想装 Python）

```bash
# 双击也行
server\build-exe.bat
# 或者
"$PY" server/build-exe.py
```

产物 `server/dist/signage-admin.exe`，**单文件，拷到哪台 Windows 上都能跑**：不用装
Python，也不用装 boto3（已经打进去了，对象存储模式照样能用）。约 27 MB。

脚本自己会：装 PyInstaller（没装的话）→ 打包 → 把 exe 拷到临时目录里**真跑一遍**。
冒烟 19 项覆盖「网页能开（`web/` 打进包了）、登录、发布、电视端免登录拉清单、
屏能上报、重启后访问码和台账还在、什么都没配时默认发到 exe 旁边的 `site/`」，
不过就不算打完。中间产物扔在 D 盘（`D:\signage-build`），C 盘不再吃紧。

### 换一台电脑要拷什么

```
signage-admin.exe            必须
signage-server.json          可选：想要「双击就跑」就得有它（见下）
data/                        访问码、设备台账（第一次运行自动建）
site/                        默认的发布目录（配置里没指定 storage 时用这个）
```

### 双击就跑：exe 旁边的 signage-server.json

双击 exe 时命令行是空的、环境变量也等于没有，参数只能写在 **exe 同一个目录**下的
`signage-server.json` 里。dist 里放了一份 `signage-server.example.json`，改名就能用：

```json
{
  "storage": "local:D:/web/signage",
  "port": 8600,
  "adminCode": "",
  "publicRead": true
}
```

- 优先级：**命令行 > 环境变量 > 这个文件 > 内置默认**。文件里写错了还能用命令行盖回来，
  所以「双击跑不通」时先用命令行验一遍，就知道是配置的问题还是别的。
- 一个字段都不写也能跑：存储默认发到 exe 旁边的 `site/`。
- 文件写坏了/不是合法 JSON **不会让它起不来**，只是那份配置被忽略（起不来比起得不对更糟）。

### 三个必须知道的点

1. **数据在 exe 旁边，不在临时目录里**。单文件 exe 每次启动都解包到一个新的临时目录，
   访问码要是存那儿，重启一次就得重新翻日志找码 —— 代码按 `sys.executable` 的目录取
   `data/`，冒烟测试专门钉了这条。
2. **第一次运行 Windows 会弹防火墙询问**，得选「允许专用网络」，否则电视拉不到内容。
3. **别给 exe 加 UPX 壳**（spec 里没开）：壳过的 exe 是一堆杀软的经典误报对象，
   店里那台电脑未必方便加白名单。

改完代码想重新打包，或者只想复验一遍手上这个 exe：

```bash
"$PY" server/build-exe.py --smoke-only   # 只跑冒烟，不重新打包
"$PY" server/build-exe.py --keep-work    # 留着中间产物，看 PyInstaller 的警告
```

> 打包配置在 `server/signage-admin.spec`。**别直接敲 pyinstaller**：`web/` 静态目录、
> `tools/signage_core.py`、以及 frozen 下的目录规则是靠 spec 和脚本一起兜着的，
> 少一样打出来的 exe 照样能启动、日志也正常 —— 只是打开网页 404，很难往打包上想。

---

## 三、群晖 NAS 上跑（Docker）

家里或店里本来就有群晖的话，这是最省心的跑法 —— NAS 常年不关机，比一台 Windows 电脑靠谱，
而且它本来就是存素材的地方。

先说清楚三件事：

- 上面那个 `signage-admin.exe` 在群晖上**一点用都没有**。群晖是 Linux，跑不了 Windows 程序。
  但**源码能直接用，一行都不用改** —— 服务端从第一天起就只用 Python 标准库（外带一个 boto3），
  这就是当初不肯上框架的回报。
- 本地目录模式（素材就存在 NAS 上）**连 boto3 都不用**。所以下面这套是零构建的：
  拉一个官方 python 镜像，把代码挂进去就跑，不需要构建任何镜像。
- 还有一条**更省事**的路 —— 用打好的最小镜像，NAS 上只放一个 compose 文件，
  代码和依赖全在镜像里。两条路怎么选，见下面「用最小镜像跑」。

### 需要什么

- DSM 7.2 或更高（这个版本才有 **Container Manager** 套件；7.2 以下叫「Docker」套件，步骤差不多）
- 在套件中心装上 Container Manager（免费）
- NAS 设个**固定的局域网 IP** —— 后面电视端要填它，IP 一变十块屏全瞎

### 步骤

**1. 建目录**

File Station 里在 `docker` 共享文件夹下建一个 `signage` 文件夹，里面再建一个 `app`：

```
docker/signage/
  app/      ← 放代码
  data/     ← 不用手建，第一次启动自己会有
  site/     ← 不用手建，同上
```

**2. 把代码拷进 app/**

把工程里的 `server/` 和 `tools/` 两个目录**整个**拖进 `app/`：

```
docker/signage/app/
  server/signage_admin.py
  server/backend.py
  server/web/index.html
  server/web/app.js
  server/web/app.css
  tools/signage_core.py
```

> `server/dist/` 里那个 27MB 的 exe 是给 Windows 用的，别传。
> 用 SMB 映射网络驱动器拷比在 File Station 里拖快得多。

**3. 放配置文件，改一行**

把工程里的 `server/docker-compose.yml` 拷到 `docker/signage/`（跟 `app/` 平级），
用文本编辑器打开，只改这一行：

```yaml
SIGNAGE_ADVERTISE_BASE: "http://192.168.1.100:8600"
```

IP 换成**这台 NAS 的局域网 IP**（跟电视在同一个网段）。

> 为什么不能自动取：容器有自己的一套内网地址（172.x 那种），它探不到 NAS 在局域网里的位置。
> 填错了也不影响播放 —— 这个值只是用在启动日志和提示语里。页面上那张「电视端填什么」
> 卡片是按浏览器地址栏算的，永远是对的。

**4. 在 Container Manager 里建项目**

Container Manager → 左边「**项目**」→「**新增**」：

- 名称：`signage`（随便起）
- 路径：选 `docker/signage` 这个文件夹（只能选共享文件夹下的目录）
- 来源：选「上传 docker-compose.yml」，把刚改好的文件选上
- 下一步，它会解析一遍给你确认，点完成

第一次启动要去 Docker Hub 拉 `python:3.12-slim`（约 45MB）。**国内经常拉不动**，
拉不动就跳到下面「拉不到镜像怎么办」。

> 8600 被占了的话，改 compose 里 `ports` 左边那个数字，页面地址跟着变。

**5. 看日志拿访问码**

Container Manager → 容器 → 双击 `signage-admin` → 「日志」。第一次启动会打印：

```
本次访问码：xxxxxxxx
```

记下来，打开 `http://<NAS的IP>:8600/` 登录。之后这个码存在 `data/code.json` 里，
重启不变，也能在页面上改。

### 电视端填什么

```
http://<NAS的IP>:8600/playlist.json
```

不能填 `127.0.0.1`（那只有 NAS 自己认得），也不能填容器内网 IP。
页面上那张「电视端填什么」卡片写的就是这个地址，带复制按钮，看那个就行。

### 改完代码怎么生效

代码是只读挂载进去的，改 `app/` 里的文件后**要重启容器**才生效
（Python 不会热加载）：

> Container Manager → 容器 → 选中 `signage-admin` → 「操作」→「重新启动」

### 拉不到镜像怎么办

群晖拉 Docker Hub 在国内经常超时。两条路：

1. **配镜像加速器**：Container Manager → 注册表 → 设置 → 添加一个可用的加速地址。
   这类地址换得勤，去搜「docker 镜像加速器 最新」，**别用一两年前文章里写的那个**。
2. **在别的机器上拉好再导进去**：找台有 Docker 的电脑（Windows 上装 Docker Desktop 也算），
   跑 `docker pull python:3.12-slim`，再 `docker save python:3.12-slim -o python312.tar`，
   把 tar 传到 NAS，然后 Container Manager → 映像 → 新增 → 从文件添加。

   走最小镜像那条路（下一节）的话，这一步也省了 —— 镜像本来就是一个 tar.gz。

### 用最小镜像跑（不想在 NAS 上放代码就走这条）

上面那套要往 NAS 拷 `server/` 和 `tools/` 两个目录，NAS 还得能拉官方镜像。
另一条路更省事：**用已经打好的最小镜像**，NAS 上只有一个 compose 文件，
代码和 boto3 全在镜像里，连网都不用通。

镜像从哪来，三选一：

- **推荐**：直接在 NAS 上拉（先在「终端机」里执行）
  ```bash
  docker pull ghcr.io/binhe-cpu/signage-tv:1.2.0
  ```
  包第一次发布**默认是 private**，拉不动就先登录一次：
  `docker login ghcr.io -u <你的GitHub用户名>`，密码填一个有 `read:packages`
  权限的 PAT（**不是**GitHub 登录密码）。嫌每次登录麻烦，就去包设置里把它改成
  public（Package settings → Danger Zone → Change visibility），之后匿名能拉。
  > 拉不动还有个可能：**国内网络到 ghcr.io 不稳**。那就走下面第二条，别耗在这儿。
- **拉不通 ghcr.io 就下文件**：`Releases` 页下载
  `signage-admin-docker-<版本>.tar.gz`（就是 `docker save` 出来的），
  传到 NAS → Container Manager → **映像 → 新增 → 从文件添加**。这条路 NAS 完全不用联外网
- 自己在有 Docker 的机器上构建（**在工程根目录**执行，上下文必须是根目录）：
  ```bash
  docker build -f server/Dockerfile.min -t ghcr.io/binhe-cpu/signage-tv:1.2.0 .
  docker save ghcr.io/binhe-cpu/signage-tv:1.2.0 | gzip > signage-admin-docker-1.2.0.tar.gz
  ```
  > tag 要打成上面这个 ghcr 名 —— 导入 NAS 之后镜像名才跟 compose 里 `image:`
  > 对得上。打成别的名字（比如 `signage-admin:min-1.2.0`）容器会起不来。

然后在 NAS 上建一个文件夹，放**一个文件**加两个空目录：

```
signage/
  docker-compose.min.yml    ← 工程 server/ 目录里那个（Release 里也带一份）
  data/                     ← 空目录就行，访问码和台账会写这里
  site/                     ← 空目录就行，发布的清单和素材放这里
```

改 `docker-compose.min.yml` 里标了 ★ 的两行（NAS 的局域网 IP、镜像名里的版本号），
Container Manager → 项目 → 新增 → 路径选这个文件夹 → 来源上传那个 yml → 启动。

> **跟上面那个 compose 有个关键区别：这里不挂 `./app:/app`。** 代码已经在镜像里了，
> 再挂一个宿主目录上去会把镜像里的代码盖住，目录不存在还会直接起不来。
> `data/` 和 `site/` 照旧，一个都不能少。

两种跑法怎么选：

| | 挂代码（零构建） | 最小镜像 |
|---|---|---|
| 镜像 | 官方 `python:3.12-slim`，NAS 自己拉 | `ghcr.io/.../signage-tv:<版本>`，直接拉 |
| NAS 上要不要放代码 | 要拷 `server/` 和 `tools/` | 不用，一个 yml 就够 |
| NAS 要不要能联网 | 要（拉镜像） | 拉 ghcr 要；走 tar.gz 导入那条路不要 |
| 改代码怎么生效 | 改完重启容器就行 | 得重新构建、重新导镜像 |
| 镜像体积 | 约 155MB（装完 boto3） | 约 70MB（Alpine） |
| 支持 COS 对象存储 | 要现场 build 一次 | 直接填环境变量 |

> 镜像小在哪：基础镜像从 Debian 换成 Alpine（musl + busybox，整个系统 50MB 出头），
> boto3 那几个包全是纯 Python，不需要编译，所以不用往镜像里塞 gcc 那一套。
> 体积数字每次 CI 都会打在构建日志里，也汇总在 Actions 页面那次运行的 Summary 上。

### 数据在哪，怎么备份

`docker/signage/` 这一个文件夹装完全部东西：

- `data/` —— 访问码 + 十块屏的状态台账（**丢了要重新设码，状态记录清空**）
- `site/` —— 发布出去的清单和素材（电视端就是从这里拉的）

备份就是拷这一个文件夹。换 NAS、搬家也一样：整个拷过去，改一下 compose 里的 IP，起项目。

### 想用对象存储（COS）

「挂代码」那套（`docker-compose.yml`）不装 boto3，所以只支持本地目录模式。素材存 NAS 上
其实挺好（局域网内网速比公网快，也不用花对象存储的钱）。真要用 COS，两条路：

- **最小镜像**（`docker-compose.min.yml`）：镜像里已经带了 boto3，把 `SIGNAGE_STORAGE`
  和那几个 `SIGNAGE_S3_*` / `AWS_*` 环境变量填上就完事，不用改镜像 —— 文件末尾有现成的注释
- **挂代码那套**：把 compose 里的 `image:` 换成 `build:` 那段，让 NAS 现场构建一个装了
  boto3 的镜像 —— `docker-compose.yml` 末尾有现成的配置，照抄就行

### 部署前先自检

改完 compose 之后，在电脑上跑一下（**不需要装 Docker**）：

```bash
cd signage-tv/server
python check-deploy.py
```

它会照着 Dockerfile 里的 COPY 清单搭一份一模一样的目录布局，起一次真服务，走一遍本地目录
模式的主要路径。**全过才说明这份配置是好的** —— 漏拷 `web/` 的表现是页面 404，漏拷
`tools/` 的表现是启动就报 import 错，这两种在 NAS 上都得折腾半天才看得出来。

## 四、放到云上

### 1. 买什么

一台最便宜的轻量应用服务器就够（1 核 1G 也能跑，2 核 2G 更稳）：

- 系统选 **Ubuntu 22.04**
- 带宽 3M 起步。素材是**浏览器直传对象存储**的，不走这台服务器，
  所以带宽只够打开网页就行，不用买大的
- 地域就近选（杭州选上海）

> 用 IP 访问不需要备案。等以后想用域名再说。

### 2. 服务器上装什么

```bash
apt update
apt install -y python3 python3-pip
pip3 install boto3
```

只需要这两样。服务本身只用 Python 标准库，**不装任何 Web 框架**——
依赖越少，长期无人值守越省心，将来换服务器也就是拷几个文件。

### 3. 把文件传上去

本地执行（把 IP 换成你的）：

```bash
scp -r signage-tv/server root@你的服务器IP:/opt/signage/
scp -r signage-tv/tools  root@你的服务器IP:/opt/signage/
```

目录结构必须是这样的（服务要读 `../tools/signage_core.py`）：

```
/opt/signage/
├── server/
│   ├── signage_admin.py
│   ├── backend.py
│   ├── selftest.py
│   └── web/{index.html,app.js,app.css}
└── tools/
    ├── signage_core.py
    └── publish.py
```

### 4. 配环境变量

写一个文件 `/opt/signage/server/admin.env`：

```ini
SIGNAGE_ADMIN_CODE=你自己设的访问码
# 上面这行想留空也行：不设就是"第一次启动随机生成一个，存进 data/code.json"，
# 之后重启不变，而且**可以在页面上改**。设了这行就以环境变量为准，页面上改不了。
SIGNAGE_STORAGE=s3://你的桶名-1250000000/signage
SIGNAGE_PLAYLIST=playlist.json
SIGNAGE_S3_ENDPOINT=https://cos.ap-shanghai.myqcloud.com
SIGNAGE_S3_REGION=ap-shanghai
AWS_ACCESS_KEY_ID=你的SecretId
AWS_SECRET_ACCESS_KEY=你的SecretKey
# SIGNAGE_DATA_DIR=/opt/signage/server/data     # 访问码这类状态存哪，默认是服务目录下的 data/
# SIGNAGE_ADVERTISE_BASE=http://1.2.3.4:8600    # 对外地址。**跑在 Docker 里必须填**，
                                                # 否则日志和提示里打印的是容器内网地址（172.x）
```

```bash
chmod 600 /opt/signage/server/admin.env     # 里面有密钥，别让别的用户读
```

几点说明：

- **访问码用中文没问题**，服务按字节比对。
- **访问码存在哪，决定页面上能不能改它**：来自 `SIGNAGE_ADMIN_CODE`（或 `--admin-code`）的码
  **页面上改不了**——改了下次重启又变回去，那种"改了没用"比不让改更坏，所以直接拒掉并告诉你去改哪个变量。
  不设环境变量时，第一次启动会随机生成一个**并写进 `data/code.json`**，之后重启不变，页面上也能改。
  **忘了访问码**：删掉 `data/code.json` 重启，会重新生成一个并打在启动日志里。
- `SIGNAGE_STORAGE` 的前缀要和 `publish.py` 用的**完全一样**，
  否则命令行发一套、网页发另一套，两边互相看不见。
- 密钥用 CAM 子账号，权限只给这一个桶的读写（见 `docs/cos-setup.md`）。
- 不想用对象存储、只用本地目录，就把 `SIGNAGE_STORAGE` 换成
  `local:/opt/signage/online`。但如果电视和这台服务器不在同一个局域网，
  电视就拉不到了——本地目录只适合当调试用。

### 5. 让它常驻

`/etc/systemd/system/signage-admin.service`：

```ini
[Unit]
Description=Signage Admin (门店屏内容发布后台)
After=network.target

[Service]
Type=simple
WorkingDirectory=/opt/signage/server
EnvironmentFile=/opt/signage/server/admin.env
ExecStart=/usr/bin/python3 /opt/signage/server/signage_admin.py --port 8600 --host 0.0.0.0
Restart=always
RestartSec=3

[Install]
WantedBy=multi-user.target
```

```bash
systemctl daemon-reload
systemctl enable --now signage-admin
systemctl status signage-admin          # 看有没有起来
journalctl -u signage-admin -f          # 看实时日志
```

### 6. 放开端口

云控制台的**防火墙 / 安全组**里放行 `8600`（TCP）。

然后手机浏览器打开 `http://你的服务器IP:8600/`，输入访问码就能用了。

> 想上 HTTPS 的话，在这台服务器上装个 nginx 反代到 `127.0.0.1:8600` 再配证书。
> 访问码是明文传输的，公网上走 HTTP 有被嗅探的风险——先用着，觉得需要再加。

---

## 五、电视端填什么地址

**页面上直接写着**（最下面那张「电视端填什么」卡片，带一个复制按钮），不用记。
这一节说的是它为什么长那样。

本地目录模式（`SIGNAGE_STORAGE=local:...`）下，这个后台**顺便当那台"静态服务器"用**：
它除了给你网页，还**免登录**地对外提供两样东西——

| 地址 | 给谁 |
|---|---|
| `http://<这台机器的地址>:8600/playlist.json` | 电视拉清单 |
| `http://<这台机器的地址>:8600/<素材相对路径>` | 电视下素材 |

所以电视端（电视上点一下屏幕 → 手机扫码 → 上传页最下面「服务器地址」）填的就是
第一条，刷新间隔 300 秒：

```
http://192.168.1.10:8600/playlist.json
```

几个必须知道的点：

- **用这台机器的局域网 IP**（`192.168.x.x` 这种），不能用 `127.0.0.1` ——
  那个地址只有这台机器自己认得。页面上如果是从本机打开的，会自动提醒你这一条。
- **端口要放行**：Windows 防火墙第一次会弹窗问，点「允许」。没弹过就手动加：
  控制面板 → Windows Defender 防火墙 → 高级设置 → 入站规则 → 新建规则 →
  端口 → TCP → 8600 → 允许连接。
- **这台机器不能睡**。睡眠之后电视拉不到内容，屏上会一直播上次同步下来的那份
  （不会黑屏，但新内容上不去）。电源设置里把「睡眠」改成「从不」。
- **素材和清单是公开可读的**。能访问到这个端口的人都能下载，放上去的是门店海报，
  一般无所谓；但这台机器别把 8600 直接映射到公网（要外网访问就上对象存储）。
  不想要这个出口：`SIGNAGE_PUBLIC_READ=0` 或 `--no-public-read`——注意本地目录模式下
  关掉它，屏就没地方拉内容了，只在排查问题时才关。
- **对象存储模式跟这一节无关**：那时电视端直接填桶的域名 + 清单名（桶设成公有读），
  这个后台不参与发内容。页面上也会这么提示。

> 想让后台和素材目录分开（比如后台放云上、素材放店里 NAS），就走老办法：
> 命令行 `publish.py` 发到某个目录，再用 `python -m http.server` 把它对外，
> 见根目录 README 第四节。

---

## 六、COS 桶必须配的跨域规则（不配传不上去）

浏览器直传对象存储属于跨域请求，**桶上不配 CORS 就一定会失败**，
而且报错五花八门（多半是「上传失败」，甚至看起来像网络问题）。

腾讯云 COS 控制台 → 你的桶 → **安全管理 → 跨域访问 CORS 设置** → 添加规则：

| 项 | 填什么 |
|---|---|
| 来源 Origin | `http://你的服务器IP:8600` |
| 操作 Methods | `PUT`、`GET`、`HEAD` |
| Allow-Headers | `*` |
| Expose-Headers | `ETag` |
| 超时 Max-Age | `600` |

如果同时用域名和 IP 访问，就多加几条来源。

保存后用页面上传一个文件试试。还失败的话打开浏览器开发者工具的
Console，会看到具体是哪一条被拦下来的。

---

## 七、日常怎么用

1. 手机或电脑浏览器打开后台，输入访问码。
2. 顶部一眼看到：当前清单版本、播放几项、线上几个素材、清单发在哪。
3. **添加素材**：选文件或拖进去。可以填个「子文件夹」分批归类（比如 `国庆活动`）。
   上传有进度，传失败会**逐条列出原因**，不会被「完成 X / Y」盖掉。
4. **看素材内容**：每行左边是缩略图，点缩略图或文件名就能看——图片看大图、
   视频直接播，用「上一个/下一个」连着翻，按 Esc 或点黑底关掉。
   预览顶上会写清它在播放顺序里的第几位，还是「未使用」（下次发布不上屏）。
5. **播放顺序**：刚传的排在末尾。用 ↑↓ 调位置，电脑上也能直接拖。
   每项可以单独设停留秒数（现在界面上只有全局的图片停留秒数）。
6. 点 **发布到电视**。电视最多 5 分钟后自己拉过去，不用去店里。
7. **改访问码**：右上角「**安全设置**」→ 当前访问码 + 新访问码（输两遍）→ 保存。
   改完**别的设备上的登录立刻失效**（得用新码），**当前这台不用重输**。
   如果这个码是环境变量给的，页面上会直接说改不了——去改 `SIGNAGE_ADMIN_CODE` 再重启。

几个设计上的取舍，用之前知道一下：

- **看内容不经过服务器**：对象存储模式下服务只回一个 302，浏览器拿着临时凭证
  直接去对象存储取，服务不转发字节——看多大的视频都不会把服务器带宽吃满。
- **缩略图是原图直接缩小的**（服务端不装图像库，所以不做压缩）。
  一次铺三十张几 MB 的大图，首屏会慢几秒、也会走几十 MB 流量。
  看过的会缓存一分钟，翻来翻去不会重复下载。
- **素材库里的「删除」是真的从线上删文件**。删掉的素材如果还在播放顺序里，
  会一起被摘掉（清单同时重写）——不然清单引用一个不存在的文件，
  电视会反复下载失败，看着像故障。
- **「移除」只是不播了**，文件还在素材库里。区分开是为了避免手滑删掉文件。
- **空清单可以直接发布**（= 把远端内容全部下架）。播放顺序清空之后点发布，会先弹一次确认：
  素材文件都留在素材库里，重新加回来不用重传；电视那边会退回播本地目录 `media/`，
  本地也没有就显示待机提示，**不会黑屏**。
  没有确认就发空清单会被服务端挡下——一次手滑把十块屏全清空，代价太大。
- 页面上不会出现「保存」中间态：**发布就是写清单**，写之前素材必须都已在线上，
  否则整个发布中止并点名是哪个素材没传完。

### 看十块屏现在什么情况

页面**最上面**那张「电视状态」卡片，每块屏一行：

- **在线 / 离线 + 多久没心跳**。离线判定是「上报间隔 × 3」，最少给 15 分钟宽限
  （间隔 60 秒的屏抖一下就标红的话，页面红成一片反而没人看）。
- **版本、模式、正在播第几条 / 共几条 + 文件名**。
- **清单版本号**。跟线上当前那一版**对不上会标黄「还是旧版」**——
  这是最省事的一条：以前「改了内容但屏上没变」只能一趟趟跑店里点开 `/status` 看。
- **素材数**（手机 / 本地 / 远端三处各几个），素材到底下下来了没一眼可知。
- **上次同步结果、上次播放出错**。

这块每 30 秒自己刷一次（页面切到后台时停刷），**只刷它自己**，不会打断你正在改的播放顺序。

端侧**一行都不用配**：上报地址是从清单地址同源推出来的（`http://你填的地址/api/report`）。
清单地址填的是对象存储桶的域名（那边没有接收接口），就不上报。

屏那边想看「我到底往哪报、报上去没有」：手机扫码打开上传页，拉到「电脑端清单（服务器地址）」
那一块，下面一行就写着。电视端的 `/status` 里也有一段 `report`。

想彻底关掉某块屏的回传：在屏的素材根目录下 `signage-remote.json` 里加一行
`"reportUrl": "off"`。手机上保存服务器地址时这一行会**原样保留**，不会被冲掉。

台账存在 `data/reports.json`，**服务重启不丢**（重启就变成"一台设备都没有"会让人白跑一趟店里）。
最多留 200 台，30 天没心跳的自动清掉。

---

## 八、出问题怎么查

| 现象 | 先看什么 |
|---|---|
| 打不开网页 | `systemctl status signage-admin`；云控制台防火墙放行 8600 了吗 |
| 输访问码进不去 | 访问码里有没有空格；换过 `SIGNAGE_ADMIN_CODE` 之后旧登录会失效，重新进就行 |
| 访问码彻底忘了 | 删掉 `data/code.json` 重启，启动日志里会有新生成的码（服务目录下 `data/`） |
| 「安全设置」里保存按钮是灰的 | 说明这个码来自环境变量。改 `admin.env` 里的 `SIGNAGE_ADMIN_CODE` 再重启 |
| 改完访问码，手机上的登录掉了 | 这是故意的：改码 = 把所有设备踢下线。用新码重进就行 |
| 页面上传失败 | 浏览器 Console 看具体报错。403 = CORS 或凭证过期；404 = 桶名/前缀写错 |
| 页面说存储不可用 | 页面顶部红条会写完整原因和路径。多半是密钥权限不够或桶名不对 |
| 发布后电视没变 | 电视每 5 分钟拉一次，先等；再打开手机的 `/status` 页看它拉的清单版本对不对 |
| 清单里有素材显示「缺失」 | 线上文件没了但清单还引用它。删掉那一项或重新传，然后重新发布 |
| 电视一直拉不到（本地目录模式） | 点开页面上那个清单地址试：电脑是不是睡了 / 防火墙拦了 8600 / 地址填成 127.0.0.1 了 / 路由器换过电脑的 IP |
| 「电视状态」里一台设备都没有 | 屏还没升到 v0.3.8（回传是这版才有的）；或者屏上填的清单地址不是这台服务器。屏上扫码打开上传页，「服务器地址」下面那一行写着它往哪报 |
| 某台屏标了「还是旧版」 | 它还在播上一版。先等一个刷新周期（默认 5 分钟）；一直不变就点开那台屏的 `/status` 看拉清单为什么失败 |
| 某台屏显示离线 | 卡片上有它最后见到的时间和 IP。常见是断网、拔电、或 App 被杀。**屏还亮着却显示离线，基本就是网络不通**（在播的东西没变是因为本地有缓存） |
| 手机上传页上「状态回传」写着不上报 | 要么清单地址是对象存储桶的域名（那边没有接收接口，本来就不报），要么屏上的 `signage-remote.json` 里写了 `"reportUrl":"off"` |
| 设备名单里有不认识的机器 | 上报接口免登录，同一个网段里谁都能写一条。掐掉：屏的配置里加 `"reportUrl":"off"`，或干脆给后台加上报口令（`SIGNAGE_REPORT_TOKEN`，但端侧目前没地方填，慎用） |

`/status` 是电视端的自诊断页，手机连同一个 WiFi 打开上传服务的地址加 `/status`
就能看到电视实际拉到的是哪一版、正在播什么。
