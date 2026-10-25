# 鲸鱼娘余额桌宠 · Whale Balance Pet for Android

把一只鲸鱼娘放在**手机屏幕角落**，常显你的 DeepSeek 账户余额。

> 独立 Android 应用，不需要装 DSH，不需要 root，不依赖 Termux。

## 功能

- 🐋 **角落常驻**：透明悬浮窗浮在所有应用之上，鲸鱼娘 + 余额气泡
- 🫳 **摸头互动**（v1.2）：按住鲸鱼 1 秒，她会被摸头 —— 按下有压扁形变 + 按压音，摸头时播放 10 帧动画、冒台词、轻震一下；松手回弹 + 松开音
- 💰 **余额常显**：直接调 DeepSeek 官方文档化接口 `GET /user/balance`，显示总余额（支持 CNY / USD 多币种）
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
