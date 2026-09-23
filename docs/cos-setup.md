# 腾讯云 COS 配置：从零到能发布

这份文档只解决一件事：**10 块屏分散在不同店时，素材放哪儿、怎么让它能被盒子拉到。**

---

## 0. 先判断你需不需要它

| 你的情况 | 用什么 | 成本 |
|---|---|---|
| 10 块屏**都在同一家店** | 别用 COS。店里找台常开的电脑/NAS，跑个静态 HTTP 服务 | 0 |
| 10 块屏**分散在不同店** | 用 COS | 每月几块钱 |

**同一家店别碰云存储**——多一层外部依赖，网断了就是故障。局域网见 README 的 4.1。

---

## 1. 注册与开通

1. 注册腾讯云账号并完成**实名认证**（个人认证就行）
2. 进入 [对象存储 COS 控制台](https://console.cloud.tencent.com/cos)，首次进入会提示开通服务，点开通
3. 开通不需要付费，按用量计费

---

## 2. 建存储桶

控制台 → **存储桶列表** → **创建存储桶**。三个关键项：

| 项 | 填什么 | 为什么 |
|---|---|---|
| **名称** | `signage`（只允许小写字母、数字、短横线） | 建完系统会自动加一个 APPID 后缀，变成 `signage-1250000000`。**这个完整名字才是桶名**，后面处处要用 |
| **地域** | **上海（ap-shanghai）** | COS 华东只有上海可选。选错地域只能删桶重建，地域不可改 |
| **访问权限** | **公有读私有写** | 盒子要靠公开 URL 直接拉文件，必须能匿名读。写操作仍然要密钥 |

> 别选「私有读写」。那样每个请求都得带签名，端侧逻辑会复杂一大截，而你要防的其实是流量盗刷——那个用第 10 节的办法解决更划算。

创建完成后，控制台会给一个请求域名，形如：

```
https://signage-1250000000.cos.ap-shanghai.myqcloud.com
```

**记住这个地址，它就是素材的对外根地址。**

---

## 3. 建子账号和密钥（这步别省）

**不要用主账号的 API 密钥。** 主账号密钥能操作你整个腾讯云账号——包括付费资源。
一旦写进脚本、传到第二台电脑，等于把家门钥匙复制了一把扔在外面。

正确做法：建一个只能读写这一个桶的子账号。

### 3.1 建子用户

1. 进入 [访问管理 CAM](https://console.cloud.tencent.com/cam) → **用户** → **用户列表** → **新建用户**
2. 选**自定义创建**
3. 访问方式**只勾「编程访问」**（不要勾控制台登录——他不需要登录网页）
4. 用户名填 `signage-publisher`
5. 到「设置用户权限」这一步**先不给他任何权限**，直接下一步完成
6. 完成后会弹出 **SecretId** 和 **SecretKey**，**只显示这一次，立刻复制保存**

### 3.2 建一条最小权限策略

访问管理 → **策略** → **新建自定义策略** → 选 **按策略语法创建** → 选「空白模板」，粘这段：

```json
{
  "version": "2.0",
  "statement": [
    {
      "effect": "allow",
      "action": [
        "cos:HeadObject",
        "cos:GetObject",
        "cos:PutObject",
        "cos:DeleteObject",
        "cos:GetBucket"
      ],
      "resource": [
        "qcs::cos:ap-shanghai:uid/1250000000:signage-1250000000/*",
        "qcs::cos:ap-shanghai:uid/1250000000:signage-1250000000/"
      ]
    }
  ]
}
```

**把两处数字换成你自己的：**

| 占位 | 换成 |
|---|---|
| `uid/1250000000` 里的 `1250000000` | 你的 **APPID**（在账号信息里，或者直接看桶名的后缀） |
| `signage-1250000000` | 你的**完整桶名** |

策略名叫 `signage-publisher-policy`，保存。

### 3.3 把策略授权给子用户

访问管理 → 用户列表 → 找到 `signage-publisher` → **关联策略** → 勾上刚建的 `signage-publisher-policy`。

**这条策略的含义**：这个人只能读、写、删这一个桶里的文件，别的桶、别的云产品一概碰不了。密钥真泄露了，损失上限也就是这一个桶。

---

## 4. 访问地址（不用开静态网站）

很多人教程会让你开「静态网站」。**你不需要。**

静态网站解决的是：访问根路径自动返回 index.html、自定义错误页、重定向。我们是直接按文件名 GET 对象，用**默认域名**就行：

```
https://signage-1250000000.cos.ap-shanghai.myqcloud.com/signage/playlist.json
```

开静态网站会多给你一个 `cos-website` 域名，但对拿文件这件事没有区别。**跳过这步。**

> 唯一例外：如果你以后想用自定义域名（比如 `cdn.你的店.com`），那需要开静态网站 + 绑域名。10 屏规模没必要。

---

## 5. 在电脑上配环境变量

`publish.py` 从环境变量读密钥，不写死在代码里。

```bat
set SIGNAGE_S3_ENDPOINT=https://cos.ap-shanghai.myqcloud.com
set SIGNAGE_S3_REGION=ap-shanghai
set AWS_ACCESS_KEY_ID=AKIDxxxxxxxxxxxxxxxx
set AWS_SECRET_ACCESS_KEY=yyyyyyyyyyyyyyyy
```

注意三点：

- `SIGNAGE_S3_ENDPOINT` 是**不带桶名**的地域地址（`cos.ap-shanghai.myqcloud.com`），桶名单独写在 `--target` 里
- `AWS_ACCESS_KEY_ID` 填的是**子账号的 SecretId**，`AWS_SECRET_ACCESS_KEY` 是子账号的 SecretKey
- 这几行只在当前命令行窗口有效

### 嫌每次敲 set 麻烦？

`signage-tv\tools\publish.bat` 已经存在，它只负责用对的 Python 跑脚本。
你可以**再复制一份**成 `my-publish.bat`，把密钥和参数都写死，以后双击就跑：

```bat
@echo off
chcp 65001 >nul
set SIGNAGE_S3_ENDPOINT=https://cos.ap-shanghai.myqcloud.com
set SIGNAGE_S3_REGION=ap-shanghai
set AWS_ACCESS_KEY_ID=AKIDxxxxxxxxxxxxxxxx
set AWS_SECRET_ACCESS_KEY=yyyyyyyyyyyyyyyy
python "%~dp0publish.py" --src "D:\signage\media" --target s3://signage-1250000000/signage %*
pause
```

> ⚠️ 这个文件里有明文密钥。**别放进 Git、别发到群里、别传网盘。** 只留在自己电脑上。

---

## 6. 用现成的启动脚本（不用管 boto3）

这台机器上已经装好了 Python 环境和 boto3，用现成的脚本就行：

```bat
cd signage-tv\tools
publish.bat --src "D:\signage\media" --target "s3://signage-1250000000/signage"
```

`publish.bat` 会自己找到装好 boto3 的那个 Python —— **不用记路径，不用 pip install**。

> **要在 cmd 或 PowerShell 里跑，Git Bash 里跑不了 `.bat`**（这台机器的安全策略拦了 `cmd.exe`）。
> 非要用自己的 Python 跑 `python publish.py`，那就先 `pip install boto3`。

---

## 7. 发布

```bat
cd signage-tv\tools
publish.bat --src "D:\signage\media" --target "s3://signage-1250000000/signage"
```

**桶名一定要带 `-1250000000` 这个 APPID 后缀**，少了会报 `InvalidBucketName` 或找不到桶。

第一次把所有素材传上去，之后只传变过的。心里没底就先试跑，只打印计划不动文件：

```bat
publish.bat --src "D:\signage\media" --target "s3://signage-1250000000/signage" --dry-run
```

---

## 8. 验证

浏览器直接打开这个地址：

```
https://signage-1250000000.cos.ap-shanghai.myqcloud.com/signage/playlist.json
```

**能看到 JSON 内容 = 成功。** 这一步过了，盒子才可能拉到。

打不开就按第 11 节查。

---

## 9. 配到盒子上

每台盒子的 `/sdcard/Android/data/com.laodao.signage/files/signage-remote.json`：

```json
{
  "playlistUrl": "https://signage-1250000000.cos.ap-shanghai.myqcloud.com/signage/playlist.json",
  "device": "",
  "refreshIntervalSec": 300,
  "prune": true,
  "assetBaseUrl": ""
}
```

### 如果每家店播的内容不一样

发布时按店生成各自的清单：

```bat
publish.bat --src "D:\signage\media" --target "s3://signage-1250000000/signage" --playlist "playlist-{device}.json" --device "A店"
publish.bat --src "D:\signage\media" --target "s3://signage-1250000000/signage" --playlist "playlist-{device}.json" --device "B店"
publish.bat --src "D:\signage\media" --target "s3://signage-1250000000/signage" --playlist "playlist-{device}.json" --device "C店"
```

盒子上用同一份配置，只改 `device`：

```json
{
  "playlistUrl": "https://signage-1250000000.cos.ap-shanghai.myqcloud.com/signage/playlist-{device}.json",
  "device": "A店",
  "refreshIntervalSec": 300,
  "prune": true,
  "assetBaseUrl": ""
}
```

**10 台盒子烧同一个 APK**，装机时只改这一个字段。素材是共用的——A 店加了 `04_新活动.jpg`，
B 店的清单里没列它，B 店就不会播，也不会下载它。

---

## 10. 费用与防盗刷

### 花钱的地方

| 项目 | 单价量级 | 你的实际情况 |
|---|---|---|
| 存储 | 约 0.1 元/GB/月 | 500MB 素材 ≈ **6 分钱/月** |
| 外网下行流量 | 约 0.5 元/GB | 首次 10 台 × 500MB = 5GB ≈ **2.5 元**（一次性） |
| 请求次数 | 按万次计 | 10 台 × 每 5 分钟一次 ≈ 8.6 万次/月 ≈ **几分钱** |

**结论：第一个月 3 块钱以内，之后每月几毛钱。** 具体单价以腾讯云官网价格页为准。

注意：**日常换一张图几乎不产生流量**——端侧靠 sha256 判断，只有真变了的文件才下载。

### 防流量盗刷

「公有读」意味着任何知道地址的人都能下载。地址要是被扫到，流量就记在你头上。三个实用手段：

1. **给路径前缀加一段随机串**。别用 `signage/`，用 `signage-7k3x9/`。别人猜不到就扫不到，端侧配置里同步改一下就行。这是性价比最高的一招。
2. **地址不要贴到公开场合**（群、论坛、截图）。它跟密码一样。
3. **偶尔看一眼用量监控**。控制台 → 用量监控，流量突然异常就换前缀。

### 更严的做法（暂时不必）

真要上强度，可以用「私有读 + 临时签名 URL」，或者绑 CDN 做鉴权。代价是端侧要处理签名刷新，
复杂度上一个台阶。10 屏自用不值当，等真被盗刷了再说。

---

## 11. 常见坑

| 现象 | 原因 | 解决 |
|---|---|---|
| `InvalidBucketName` / 找不到桶 | 桶名少了 `-APPID` 后缀 | 用 `signage-1250000000` 这种完整名 |
| 上传 403 | 子账号策略缺 `PutObject`，或 resource 里的 APPID／地域写错了 | 对照第 3.2 节逐字核对 |
| 浏览器打不开清单（403） | 桶权限不是「公有读私有写」 | 权限管理 → 存储桶访问权限 |
| `SignatureDoesNotMatch` | 少数环境 v4 签名不通 | 加 `--sig-version s3` 重试 |
| 盒子一直显示「正在同步素材」 | 清单地址写错，或桶不可公开读 | **先在电脑浏览器**把地址打开验证 |
| 盒子报 403，但浏览器能打开 | 盒子的系统时间不对（HTTPS 校验依赖时间） | 盒子设置里打开「自动校时」 |
| 改了素材但屏没变 | 没跑 publish；或盒子的刷新间隔还没到 | 跑一次 publish，等 5 分钟 |
| 屏上还是老内容 | 端侧在时段过滤里被跳过了 | 检查清单里的 `startTime`/`days` |

---

## 12. 替代方案

- **阿里云 OSS**：流程完全一样。endpoint 换成 `https://oss-cn-hangzhou.aliyuncs.com`，**杭州有节点，比你到上海更近一点**。`publish.py` 不用改，只换环境变量和桶名。
- **同一个店**：真的别用云存储，局域网方案省事十倍，见 README 4.1。
