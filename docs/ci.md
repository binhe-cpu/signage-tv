# 在 GitHub 上自动编译

不用本机环境也能出两个产物：**安卓 APK** 和 **网页后台 exe**。工作流在
[`.github/workflows/build.yml`](../.github/workflows/build.yml)。

**公开仓库用这个不花钱** —— GitHub 对公开仓库的 Actions 不计量额度。

---

## 一、什么时候会自动跑

| 情况 | 跑什么 |
|---|---|
| push 到 `main` | 编译 APK + exe，跑服务端自测（不发 Release） |
| 提 Pull Request | 同上 |
| push 一个 `v` 开头的 tag | 上面全跑，**并且自动建 Release**，产物挂成附件 |
| 在 Actions 页面点 `Run workflow` | 同 push（手动触发） |

同一个分支连着推两次，前一次没跑完的会自动取消，不浪费额度。

---

## 二、在哪看结果、在哪下产物

**看结果**：仓库页面上方 `Actions` 标签 → 左侧选「构建」→ 点某一次运行，能看到
四个 job 各自的绿勾或红叉。红了点开具体步骤看日志。

**下产物**（普通 push）：那次运行页面最下方 `Artifacts` 区，下载
`signage-apk-0.3.8-11` 或 `signage-admin-exe`。

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

几分钟后 `Releases` 页面就会出现 `v0.3.9`，附件里三样东西：

- `signage-0.3.9-12-debug.apk` —— 拷进电视装
- `signage-admin.exe` —— 网页后台，双击跑
- `signage-server.example.json` —— 后台的配置模板

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

## 五、四个 job 各干什么

| job | 跑在哪 | 干什么 |
|---|---|---|
| 安卓 APK | ubuntu | JDK 17 + Android SDK 34 → `./gradlew :app:assembleDebug`，顺带跑 lint（不拦构建） |
| 网页后台 exe | windows | PyInstaller 打包 + **真启动 exe 跑 19 项冒烟** |
| 服务端自测 | ubuntu | `selftest.py`（182 项）+ `check-deploy.py`（47 项） |
| 发 Release | ubuntu | 只在 tag 时跑，收齐产物挂到 Release |

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

**红在「服务端自测」**：本地跑同样的脚本能复现，比在 CI 上查快。

**一直排队不跑**：公开仓库的 macOS job 每 5 分钟、单次运行最长 6 小时会有限制，
我们这个用不到 macOS。排队通常是公共高峰，等一会儿。

---

## 七、这套 CI 管不到的事

- **电视上的界面**。触摸、二维码浮层的显隐这些只能在真机上点。
  CI 能证的是「编译得过、逻辑测试全绿」，证不了「电视上看着对」。
- **10 块屏的实际同步效果**。那是网络和现场的事。
- **群晖上真装一遍**。CI 只能验到「同样的目录布局能跑起来」这一层。
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
