# playlist.json 字段规范（schema v1）

这是整条链路的合同：**电脑端 publish.py 生成它，电视端 App 消费它**。
字段只增不改，改动一律升 `schemaVersion`。

## 整体结构

```json
{
  "schemaVersion": 1,
  "revision": "20260921-1008-a1b2c3d4",
  "assetBaseUrl": "https://cdn.example.com/signage/",
  "defaults": {
    "imageDurationSec": 8,
    "muted": true,
    "volume": 0.0
  },
  "items": [
    { "file": "01_秋季促销.jpg", "durationSec": 10 },
    { "file": "02_新品视频.mp4", "type": "video" },
    { "file": "03_会员日.jpg", "startTime": "09:00", "endTime": "21:00", "days": [1,2,3,4,5] }
  ]
}
```

## 顶层字段

| 字段 | 必填 | 说明 |
|---|---|---|
| `schemaVersion` | 否 | 规范版本，默认 `1`。端侧遇到**比自己新**的版本会整份忽略（安全起见），旧的兼容读 |
| `revision` | 建议 | 内容版本号。端侧只用来打日志/判断"清单变了没"，**不参与下载决策**。推荐 `日期-时间-内容指纹` |
| `assetBaseUrl` | 否 | 素材基址。省略时取 `playlist.json` 所在目录 |
| `defaults` | 否 | 每个 item 没写的字段就取这里 |
| `items` | 是 | 播放项数组，**数组顺序就是播放顺序**（不再靠文件名排序） |

## defaults 字段

| 字段 | 默认 | 说明 |
|---|---|---|
| `imageDurationSec` | 8 | 图片停留秒数 |
| `muted` | true | 是否静音。门店屏建议 true |
| `volume` | 0.0 | 音量 0~1，`muted=true` 时无效 |

## items[] 字段

| 字段 | 必填 | 说明 |
|---|---|---|
| `file` | **是** | 素材文件名，相对素材目录。支持子目录 `A店/01.jpg`。**不允许 `..`** |
| `type` | 否 | `video` / `image`。省略则按扩展名推断 |
| `durationSec` | 否 | 图片停留秒数。视频忽略此字段（播完即切） |
| `muted` | 否 | 覆盖 defaults |
| `volume` | 否 | 覆盖 defaults，0~1 |
| `startTime` | 否 | 每日生效起点 `HH:mm` |
| `endTime` | 否 | 每日生效终点 `HH:mm`（不含终点，即 21:00 表示 21:00 就停） |
| `days` | 否 | 生效星期，`1`=周一 … `7`=周日。省略 = 每天 |
| `size` | 建议 | 文件字节数。**端侧增量下载靠它**，对不上就重下 |
| `sha256` | 可选 | 文件哈希。填了端侧就强校验，更稳但发布时算得慢 |

### 时段规则

- `startTime` / `endTime` 同时给才生效；只给一个视为不限制。
- `startTime <= endTime`：正常区间，如 `09:00~21:00`。
- `startTime > endTime`：**跨午夜**，如 `22:00~02:00` 表示当天 22 点到次日凌晨 2 点。
- 被时段过滤掉的 item **不占播放位**，直接跳过。

## 每块屏播什么

同一份素材目录，每块屏可以有各自的清单。命名约定：

```
media/
├── 01_秋季促销.jpg
├── 02_新品视频.mp4
├── 03_会员日.jpg
├── 04_通用门店形象.jpg
└── playlist.json            ← 默认清单（没指定就用它）
└── playlist-A店.json        ← A 店的清单
└── playlist-B店.json        ← B 店的清单
```

电视端在自己的配置文件里指定用哪一份：

```json
{
  "playlistUrl": "https://cdn.example.com/signage/playlist-{device}.json",
  "device": "A店",
  "refreshIntervalSec": 300
}
```

`{device}` 会被替换成 URL 编码后的设备名。这样 10 台盒子可以烧同一个 APK，
只改这一个文件里的 `device` 字段。

## 版本演进约定

- 加字段 → 不升 `schemaVersion`，老端侧忽略未知字段即可。
- 删字段 / 改语义 → 必须升 `schemaVersion`，并保证 `publish.py` 通过 `--schema` 参数能吐旧版本。
