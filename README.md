# 鲸鱼娘余额桌宠 · Whale Balance Pet for Android

把一只鲸鱼娘放在**手机屏幕角落**，常显你的 DeepSeek 账户余额。

> 独立 Android 应用，不需要装 DSH，不需要 root，不依赖 Termux。

## 功能

- 🐋 **角落常驻**：透明悬浮窗浮在所有应用之上，鲸鱼娘 + 余额气泡
- 💬 **气泡动画**：点一下鲸鱼或气泡才出现，弹入 + 淡出；可设常显；停留 5 秒自动收起
- 🫳 **摸头互动**：按住鲸鱼 1 秒，她会被摸头 —— 按下有压扁形变 + 按压音，摸头时**放大 1.18 倍并上浮**（冒出来给你摸）、播放 10 帧动画、冒台词、轻震一下；松手回弹 + 松开音
- 💰 **余额常显**：直接调 DeepSeek 官方文档化接口 `GET /user/balance`，显示总余额（支持 CNY / USD 多币种）
- 📒 **记账**（v1.3）：气泡第二行常显「今日 -¥0.42 · 3 笔」，设置页有今日已用 / 今日充值 / 累计已用和最近 12 条明细
- 🔄 **自动刷新**：间隔可调（默认 60 秒，最小 15 秒），点一下鲸鱼立刻刷新
- 🖐 **拖拽 + 吸附**：随便拖，松手自动吸到最近的左右边缘，位置记在本机
- ⚙️ **按住 3 秒**打开设置：换 Key、改间隔、关气泡、开关摸头音效与震动
- 📴 **零第三方**：只有一个 HTTPS 请求打到 `api.deepseek.com`，不上传、不埋点、不读其他应用数据

## 安装

到 [Releases](../../releases) 或 Actions 的 `whale-balance-pet-apk` artifact 下载 APK，直接侧载安装（需允许「安装未知来源应用」）。

首次打开：

1. 把 `sk-...` 的 DeepSeek API Key 填进去（[platform.deepseek.com](https://platform.deepseek.com) → API Keys）
2. 点**授予悬浮窗权限**（必需，否则鲸鱼浮不出来）
3. 点**保存并启动桌宠**
4. 鲸鱼就出现在屏幕角落，气泡里是余额

> 想换位置：拖到任意角落，松手会自动贴边。想立刻刷新：点一下鲸鱼。想摸头：按住她别放，1 秒后就开始摸。

## 界面：按原版 frame_classic 还原

泡泡不是重画的，是把 `dsh-whale-widget` 的 `frame_classic` 逐项搬过来：

| 项 | 原版实现 | 本应用 |
|---|---|---|
| 形状 | SVG `viewBox 0 0 1026 700` 的贝塞尔气泡 + 两条尾巴椭圆 | 同一份 `path` 数据做成 VectorDrawable（`bubble_frame.xml`），路径一个字没改 |
| 配色 | 填充 `#FFFFFF`、描边 `#203170`、`stroke-width 18`、圆角连接 | 完全一致 |
| 三行文案 | label `DeepSeek 余额` / amount / hint `今日已用 ¥ 0.42` | 一致（含 CNY 的 `¥ 12.34` 空格格式） |
| 字号 | `u = 泡泡宽/1026`，label 66u / amount 128u / hint 56u，hint 上边距 9u | 同样按 u 等比换算成 px |
| 字重字距 | label 600 + `.06em`，amount 800，hint `.02em` | `sans-serif-medium` / bold，letterSpacing 0.06 / 0.02 |
| 字色 | 正文 `#536BA9`，提示 `#9FB0D9` | 一致 |
| 文本区位置 | 中心 (44.25%, 36%)，尺寸 66% × 64% | `BubbleBox` 按同样比例排版 |
| 尺寸比例 | 鲸鱼 = 根方块 59.45%，泡泡 = 根方块宽 × 1026:700 | 同样由鲸鱼尺寸反推泡泡尺寸 |
| 弹出 | 形状先弹（尾巴 0.13s / 0.26s 延迟），文字 0.36s 后淡入 | 整体 220ms Overshoot 弹出 + 文字延迟 120ms 淡入 |
| 数字滚动 | 余额变化时 700ms 三次缓出滚动 | `ValueAnimator` 700ms，`1-(1-t)³` 缓动 |

状态文案也照搬：加载中显示 `…` + `加载中…`，失败显示上次金额 + `获取失败 · 点击重试`。

## 记账怎么算的

不额外请求任何接口，纯靠余额差额推算：

| 观测 | 记账 |
|---|---|
| 余额下降 | 记为消费，累加到「今日已用」 |
| 余额上升 | 记为充值 / 赠金，单独统计，不会冲掉已有消费 |
| 余额不变 | 不落账 |

所以账目颗粒度 = 你的刷新间隔（默认 60 秒）。每笔都存本地（SharedPreferences，最多 200 条），设置页可以看明细、一键清空。跨天会自动只统计当天。

## 摸头是怎么还原的

原版 `dsh-whale-widget` 的交互是这么写的：

| 环节 | 原版实现 | 本应用 |
|---|---|---|
| 按下形变 | `SQUISH = 'scaleY(0.88) scaleX(1.05)'` | 同样 0.88 / 1.05，90ms 过渡，以顶部为轴压扁 |
| 按压音 | `Ya1.mp3`（按压槽） | 直接打包原文件 `res/raw/ya1.mp3` |
| 松开音 | `Ya2.mp3`（松开槽） | `res/raw/ya2.mp3` |
| 摸头动画 | `rua.gif`（128×128，10 帧，每帧 2cs=20ms） | 逐帧解码成 10 张 PNG，`AnimationDrawable` 循环播放，帧时长原样 20ms |

也就是说动画素材不是重画的，是把原版 GIF 的每一帧解出来直接用的，帧时序也按原 GIF 的 20ms/帧。

## 保活提示

锁屏久了悬浮窗被系统回收，是国产 ROM 的常规操作，按你的机型处理：

- **小米 / 红米**：省电策略 →「无限制」+ 打开「自启动」
- **荣耀 / MagicOS**：电池 → 允许后台活动，并加入「应用启动管理」允许列表
- **OPPO / vivo**：允许自启动 + 后台高耗电允许

## 自建 / 编译

仓库自带 GitHub Actions（`.github/workflows/build.yml`），push 到 `main` 或手动 `Run workflow` 就会产出 APK；打 `v*` tag 会自动发 Release 并附上 APK。

本地编译需要 JDK 17 + Android SDK：

```bash
gradle assembleRelease
# 产物：app/build/outputs/apk/release/app-release.apk
```

release 用 debug 签名，方便直接侧载；要正式分发请换成自己的 keystore。

## 隐私

- API Key 只存本机 `SharedPreferences`（应用私有目录），只用于请求 `https://api.deepseek.com/user/balance`
- 应用**没有**任何分析、统计、崩溃上报 SDK
- 除上述余额接口外不发起任何网络请求

## 授权

- 代码：MIT，见 [LICENSE](./LICENSE)
- 鲸鱼娘立绘 `app/src/main/res/drawable-nodpi/whale.png`：社区二创作品，**CC BY-NC-SA 4.0**，来源 `dsh-whale-widget`（MeteorNOX/DeepSeek-Balance-Whale-Widget）→ 上游 `fornarwhal/deepseek-whale-girl-icon`。**请勿商用**；替换成自己的图请改这个文件。

## 相关

- 想在 DSH 界面里看余额：[dsh-whale-widget](https://github.com/MeteorNOX/DeepSeek-Balance-Whale-Widget)
- 想要会动会说话的大肥鱼桌宠：[dsh-pet-android](https://github.com/Tony-Chen-Stark/dsh-pet-android)
