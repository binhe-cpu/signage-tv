# 在 GitHub 上自动编译

不用本机环境也能出三个产物：**安卓 APK**、**网页后台 exe**、**最小容器镜像**。

容器镜像会推到 GitHub Packages（`ghcr.io`），NAS 上 `docker pull` 直接能用；
同时也导出成 tar.gz，留给拉不通 ghcr.io 的场合走「映像 → 从文件添加」。
工作流在 [`.github/workflows/build.yml`](../.github/workflows/build.yml)。

**公开仓库用这个不花钱** —— GitHub 对公开仓库的 Actions 不计量额度。

---

## 一、什么时候会自动跑

| 情况 | 跑什么 |
|---|---|
| push 到 `main` | 编译 APK + exe、打最小容器镜像**并推到 ghcr.io**，跑服务端自测（不发 Release） |
| 提 Pull Request | 同上，但**不推 ghcr** —— 外面 fork 过来的 PR 拿不到包写权限，推了必红 |
| push 一个 `v` 开头的 tag | 上面全跑，**并且自动建 Release**，产物挂成附件 |
| 在 Actions 页面点 `Run workflow` | 同 push（手动触发） |

同一个分支连着推两次，前一次没跑完的会自动取消，不浪费额度。

---

## 二、在哪看结果、在哪下产物

**看结果**：仓库页面上方 `Actions` 标签 → 左侧选「构建」→ 点某一次运行，能看到
五个 job 各自的绿勾或红叉。红了点开具体步骤看日志。

**下产物**（普通 push）：那次运行页面最下方 `Artifacts` 区，下载
`signage-apk-0.3.8-11`、`signage-admin-exe` 或 `signage-admin-docker-1.2.0`
（容器镜像那个 zip 里是 `signage-admin-docker-1.2.0.tar.gz` + `docker-compose.min.yml`）。
那次运行的页面上还有一块 **Summary**，写着两个镜像的体积对比，以及 ghcr 上的拉取命令。

**容器镜像其实不用下**：push 到 main 之后它已经在包仓库里了，NAS 上直接

```bash
docker pull ghcr.io/binhe-cpu/signage-tv:latest
```

包**是公开的，匿名就能拉**，不用登录（实测：不带任何凭据请求 manifest 返回 200）。
万一拉不动，按顺序排两个可能：① 网络到不了 ghcr.io（国内最常见）；② 包被设成了
private —— 去 `Package settings → Danger Zone → Change visibility` 改回 public。

三个 tag 的区别：`latest` 和版本号（`1.2.0`）都跟着 main 走、每次 push 会覆盖；
`sha-xxxxxxx` 钉死在某一次提交上，**不会被覆盖**，想冻结某一版就用它。

> 注意：**Artifacts 要登录 GitHub 才能下**，而且下来是个 zip。
> 要一个能直接甩链接的下载地址，就得走 Release（下一节）。

**下产物**（打 tag）：`Releases` 页面里，附件谁都能直接点，不用登录。

---

## 三、发一个版本

```bash
# 1. 先改版本号：app/build.gradle.kts 开头的 appVersionCode / appVersionName
#    （版本号只能涨不能降，改了才推）
# 2. 提交
git add -A && git commit -m "v0.3.9"
git push
# 3. 打 tag 推上去，Release 自动生成
git tag v0.3.9
git push origin v0.3.9
```

几分钟后 `Releases` 页面就会出现 `v0.3.9`，附件里这几样：

- `signage-0.3.9-12-debug.apk` —— 拷进电视装
- `signage-admin.exe` —— 网页后台，双击跑
- `signage-server.example.json` —— 后台的配置模板
- `signage-admin-docker-1.2.0.tar.gz` —— 最小容器镜像。**平时不用下它**，
  `docker pull ghcr.io/binhe-cpu/signage-tv:latest` 就行；这个是留给拉不通
  ghcr.io 的场合的，导进群晖「映像 → 从文件添加」
- `docker-compose.min.yml` —— 上面那个镜像的群晖部署文件（**不挂代码目录**）

> 注意 exe 旁边那个数字是镜像/服务端的版本号（`server/signage_admin.py` 里的
> `ADMIN_VERSION`），跟 APK 的版本号（`app/build.gradle.kts` 里那两行）是两回事，
> 各改各的。

> tag 名（`v0.3.9`）和工作流里读出来的版本号是两回事：tag 只是给这次发布起个名字，
> 真正记在包里的版本号来自 `build.gradle.kts`。两个别弄岔了，建议保持一致。

---

## 四、固定签名（建议做一次，5 分钟）

**不做的后果**：CI 打出来的 APK 和你在本机 `build.bat` 打的 APK，签名不一样，
装在同一个电视上会互相覆盖不了——系统只报一句「应用未安装」，看不出原因，
得先卸载再装。

原因是 AGP 默认的 debug 签名是**每台机器各自生成一个**（放在 `~/.android/debug.keystore`）。
把本机那个存成仓库 secret，两边就一致了。

```bash
# 1. 生成 base64（本机执行，Git Bash 里跑）
base64 -w0 ~/.android/debug.keystore > /tmp/ks.txt && cat /tmp/ks.txt
#    不行就用 python：
python -c "import base64,pathlib;print(base64.b64encode(pathlib.Path.home().joinpath('.android/debug.keystore').read_bytes()).decode())"
```

```bash
# 如果用 gh 命令（没装 gh 就用网页，见下面）
gh secret set DEBUG_KEYSTORE_BASE64 < /tmp/ks.txt
```

**用网页设置**：仓库 → `Settings` → 左侧 `Secrets and variables` → `Actions` →
`New repository secret` → Name 填 `DEBUG_KEYSTORE_BASE64`，
Secret 粘贴刚才那一长串 → `Add secret`。

设好后再跑一次构建，日志里会打印「已装入固定的 debug keystore」。
没设的话会有一条黄色提示，说明这次用的是临时签名。

> 这是个 debug 密钥（口令是公开的 `android`），不是发布密钥，泄露风险很低；
> 但它是**你所有 debug 构建共用的**，所以还是别直接提交进仓库。
> 真要正经对外发包，再单独生成 release keystore 配 CI —— 那是另一件事，现在用不上。

---

## 五、五个 job 各干什么

| job | 跑在哪 | 干什么 |
|---|---|---|
| 安卓 APK | ubuntu | JDK 17 + Android SDK 34 → `./gradlew :app:assembleDebug`，顺带跑 lint（不拦构建） |
| 网页后台 exe | windows | PyInstaller 打包 + **真启动 exe 跑 19 项冒烟** |
| 服务端自测 | ubuntu | `selftest.py`（182 项）+ `check-deploy.py`（77 项，没装 pyyaml 时 49 项） |
| 容器镜像 | ubuntu | 真 `docker build` 最小镜像（Alpine）+ **真起容器跑 7 项冒烟** + 体积对比 + **推 ghcr.io** + 导出 tar.gz |
| 发 Release | ubuntu | 只在 tag 时跑，收齐产物挂到 Release |

**容器镜像那个 job 为什么也要真起容器**：`check-deploy.py` 只是照着 `COPY` 清单
在临时目录里搭一份布局，它证明不了「Alpine 上 boto3 装得动」「多阶段拷贝没拷错位置」
「容器里页面真能打开」这三件事 —— 这三样的表现都是**镜像正常、服务照跑、只是页面 404
或用不了对象存储**，只能真跑一次才现形。同一次 run 里还会真的 build 一遍
`server/Dockerfile`（群晖现场构建走的那份），把两个镜像的体积打进 Summary 做对比。

**exe 那个冒烟为什么必须有**：它会真的把 exe 起来跑一遍，验「网页能打开」
（证明 `web/` 打进包了）和「数据落在 exe 旁边」（不是临时解包目录）。
这两种毛病编译期和源码自测都发现不了——漏了 `web/` 的表现是 exe 启动正常、
日志全对、只是网页 404。

**服务端自测为什么要放 CI**：runner 是 **Linux**，跟群晖 NAS 是同一个环境。
`check-deploy.py` 会按 Dockerfile 里的 `COPY` 清单搭一份一模一样的目录布局
再起真服务——漏拷文件当场暴露，不用等到 NAS 上才发现。这是本项目里唯一
能自动验证 Linux 那条路的地方。

---

## 六、出问题了怎么查

**红在「装 SDK 34」**：Android SDK 的下载源偶尔抽。重跑一次通常就好
（运行页面右上角 `Re-run jobs`）。

**红在「编译」**：日志里搜 `error:`。大多是版本号那两行被改坏了，
或者 `minSdk` 被动过——店里那台电视是 Android 5.1.1，`minSdk` **必须 ≤ 21**。

**红在「打包 + 冒烟测试」**：看冒烟哪一项没过。它打印的失败项后面会带
exe 当时的输出，通常直接能看出原因。想在本地复现就跑
`python server/build-exe.py`（跟 CI 同一条命令）。

**红在「服务端自测」**：本地跑同样的脚本能复现，比在 CI 上查快
（`python server/check-deploy.py`、`python server/selftest.py`）。

**红在「构建最小镜像」**：多半是 pip 那一步（`docker build` 的输出里有 `ERROR:`
多少行跟着）。本地有 Docker 的话，跑同一条命令就能复现：
`docker build -f server/Dockerfile.min -t signage-admin:min-1.2.0 .`

**红在「真起容器跑一遍」**：冒烟会逐条打 `ok` / `FAIL`，失败那条后面跟着它自己
的输出，而且会打成 `::error::` 注解（不用登录也能在外面看到）。最常见的两条：
`页面能打开` 挂了 = `web/` 没进镜像；`import boto3` 挂了 = 多阶段拷贝那段出问题。

**红在「推到 GitHub Packages」**：这一步在冒烟全过之后才跑，所以红了说明镜像本身
没问题、只是推不上去。两个原因占绝大多数，都写在日志里了：

1. 仓库 `Settings → Actions → General → Workflow permissions` 没选
   **Read and write**（默认是只读，此时 `GITHUB_TOKEN` 没资格推包）
2. 这个命名空间下以前推过同名包、但没关联到仓库 —— 这时 `GITHUB_TOKEN` 也会被拒。
   解法是去包设置里把包**关联到这个仓库**（Connect repository），或者改成用 PAT 推

> 顺带说：PR 上这一步是**跳过**的（fork 来的 PR 拿不到包写权限），所以 PR 绿不代表推到过 ghcr。

**一直排队不跑**：公开仓库的 macOS job 每 5 分钟、单次运行最长 6 小时会有限制，
我们这个用不到 macOS。排队通常是公共高峰，等一会儿。

---

## 七、这套 CI 管不到的事

- **电视上的界面**。触摸、二维码浮层的显隐这些只能在真机上点。
  CI 能证的是「编译得过、逻辑测试全绿」，证不了「电视上看着对」。
- **10 块屏的实际同步效果**。那是网络和现场的事。
- **群晖上真装一遍**。CI 现在能验到「镜像真构建得出来、容器里真跑得起来、包真推得上
  ghcr.io」这一层，但 DSM 的 Container Manager 怎么解析那个 yml、从 ghcr 拉镜像有没有
  网络问题（国内访问 ghcr.io 不稳），还是得在 NAS 上试。
- **exe 在别人电脑上跑**。CI 只在那台 runner 上验。

---

## 八、本机编译 vs CI 编译

两个都留着，各管各的：

| | 本机 | CI |
|---|---|---|
| 速度 | 几十秒 | 几分钟（含装环境） |
| 联网要求 | 首次拉依赖要通 | 全自动 |
| 适合 | 改代码时反复试 | 出正式产物、给别人下载 |

> **国内网络提醒**：本机 `git clone` 下来后直接跑 `./gradlew` 会卡在下载
> Gradle 发行版（`services.gradle.org` 在国内常常不通）。本机就用
> `build.bat` / `build.sh`（它们指向已经装在 `D:\gradle-8.7` 的那份），
> 或者在 `gradle/wrapper/gradle-wrapper.properties` 里把 `distributionUrl`
> 换成国内镜像。CI 上不存在这个问题。
