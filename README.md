# tawc-dsh

在 Android 上，于 Linux 容器里运行 DSH 智能体。

一个应用一个容器：它把一份原版 Linux 发行版下载到应用私有存储，在容器里拉起
`dsh web` harness，再用 WebView 显示 DSH 自己的 web 客户端。容器能拿到 GPU 做
Vulkan 计算。

本项目是 [tawc](https://github.com/wmww/tawc)（Tess's Android Wayland Compositor）
的 fork —— 基准修订 [`b3fff97`](https://github.com/wmww/tawc/commit/b3fff97eb7f1db26918acabe5871ff085fef8eaa)
（2026-09-09）。容器那一层是 tawc 的 —— `tawcroot`、`ando` 桥、终端集成、libhybris 供给。
本 fork 改的是这一层**指向什么**：一个智能体 harness，而不是 Wayland 桌面；并且整个
显示栈都删掉了。

> **非官方社区版本。** 与 DeepSeek 无隶属关系，未经其审核、认可或支持。
>
> **早期软件。** 当前是 0.1，可靠性尚未验证 —— 见下面「限制」。

[English](README.en.md)

## 它能做什么

- **是容器，不是模拟器。** 把 Arch Linux ARM 或 Debian sid 解包到应用私有存储，用
  `tawcroot` 在其中运行程序 —— 一个单进程的 PRoot 替代方案。不需要 root，不用
  proot，不是虚拟机。
- **DSH 的真界面。** WebView 从 `127.0.0.1` 上的 harness 加载 DSH 自己的 web 客户端，
  所以会话、工具调用、模型选择都是真的，不是重写的仿制品。
- **容器真能用上的 GPU。** 两条驱动：`libhybris`（设备厂商的 Vulkan 驱动，经我们自己
  写的 null platform plugin）和 Mesa 的 `turnip`。两条都是**仅计算** —— 面向 llama.cpp
  一类负载，从不用于绘制。
- **终端**，用 Termux 的终端控件；不需要装 Termux 应用本身。
- **`ando` 与存储绑定**，容器里的程序因此能在你要求时访问 Android 侧数据 —— 且仅在
  你要求时。
- **悬浮小键**，覆盖在智能体界面之上：终端、任务管理器、容器管理、界面缩放、设置。

## 要求

- Android 10（API 29）或更高，`arm64-v8a`。
- **建议在安卓平板上运行。** 当前版本的 DSH 界面是桌面形态，在手机上偏挤 —— 尤其是
  16:9 的直板机，宽和高两个方向都不够用。「界面缩放」能缓解，但缓解不了全部。手机装得上、
  也跑得起来，只是体验明显不如平板。
- 不需要 root。（需要 root 的 `chroot` 方式只存在于 debug 版。）
- 几 GB 可用空间：Arch Linux ARM 的引导包本身就要下载约 800 MB，解包后的容器约
  1.6 GB —— 安装期间两者同时在盘上。
- 网络要能搬动这些数据。应用会先测速再选源，也可以手动指定某个源。
- 没别的了。DSH 自己的凭据放在容器的 `~/.dsh` 里，应用既不读、也不随包分发。

## 安装

从 [Releases 页](https://github.com/Kaeno-Tori/tawc-dsh-for-android/releases)下载
`tawc-dsh-v<版本>.apk`（`arm64-v8a`，已签名）。0.1 是首个版本。

没有上架任何应用商店。想自己构建见下文。

## 使用

首次启动会准备容器：选一个发行版，它会下载并解包。完成后进入智能体界面 —— 由容器
提供的 DSH 客户端。

角落的悬浮键打开：

| 动作 | 作用 |
|---|---|
| 终端 | 容器内的 shell |
| 任务管理器 | 容器进程，可以结束它们 |
| 容器管理 | 发行版信息、存储绑定、卸载 |
| 缩小 / 放大界面 | DSH 界面本身的页面缩放，5% 一档 |
| 设置 | 外观、界面缩放、GPU 后端、关于、开源许可 |

两个值得知道的设置：

- **界面缩放**（50–150%）。DSH 的客户端是桌面形态的 web 应用，而 WebView 会给它
  "屏幕 dp 数"那么多 CSS 像素 —— 16:9 手机在宽和高两个方向都不够用。这项缩放的是
  **页面本身**，不只是文字。
- **GPU 后端。** 三档：`libhybris`（用你设备的厂商驱动）、`turnip`（Mesa freedreno）、
  以及**关闭**。关闭就是不给容器任何驱动 —— 容器里的程序改用它们自己的 CPU 路径
  （llama.cpp 一类负载的 CPU 后端不需要 Vulkan）。驱动在设备上有问题时可以切换。

界面有英文和简体中文。

## 限制

动手报 bug 之前先看这一段 —— 其中大部分是刻意的。

- **可靠性尚未验证。** 这是 0.1，代码绝大部分由智能体编写，真机验证过的设备和场景都不多。
  请把它当作"值得一试"的东西，而不是可以依赖的工具 —— 尤其别用于你无法重来的工作。
- **DSH 以 `DSH_PERMISSION_MODE=danger-full-access` 运行。** 这是强制的，不是偏好。
  DSH 自己的约束链是 `bwrap` → Landlock，而这两者在 Android 上都不可用（非特权
  user namespace 被禁用，内核未开启 Landlock），所以 DSH 的沙箱 fail-closed，其默认
  模式会让每条命令都拒绝执行。**隔离边界是容器，不是 DSH 的沙箱。**
- **容器也不是安全沙箱。** 隔离依靠 Android 自身的应用沙箱，加上 `tawcroot` 的系统
  调用翻译 —— 后者是单进程设计，有心的程序可以逃逸。容器里跑的东西，就当作你自己
  亲手运行的程序来看待。
- **没有图形 Linux 程序。** 没有合成器、没有显示栈，所以没有 Wayland/X11 桌面、没有
  桌面 GL、没有 GTK/Qt GUI。这个 build 是给智能体和它的工具用的。
- **一次安装一个容器。** 它不是多发行版管理器；换发行版意味着卸载。
- **性能优于同类方案，但不是原生** —— 系统调用翻译每次都有开销。

## 报告问题

用 [GitHub Issues](https://github.com/Kaeno-Tori/tawc-dsh-for-android/issues)（有 issue
模板，会问设备、版本、容器与图形后端 —— 这些是能动手排查的最低信息）。**安全漏洞不要开
公开 issue**，走 [SECURITY.md](SECURITY.md) 里的私密渠道。

仓库内的 `issues/` 目录是**这个项目自己的开发记录**（agent 之间传递的工作笔记），不是
反馈入口。

## 构建

需要 Android SDK/NDK、Rust 工具链和 Linux 主机。完整工具链、vendored 依赖与交叉编译
（`libhybris`、`turnip`、`tawcroot`）见 [notes/building.md](notes/building.md)。

```bash
scripts/build-app.sh                # debug APK
scripts/app-build-install.sh        # 构建、安装并在设备上启动
scripts/build-release-apk.sh        # release APK：构建、zipalign、签名、校验
```

`scripts/build-release-apk.sh` 从环境变量 `KEYSTORE_PASS` 读密钥库口令；
见 [notes/release.md](notes/release.md)。

## 测试

```bash
./gradlew :app:testDebugUnitTest       # 宿主机侧单元测试
scripts/run-integration-tests.sh       # 设备上的集成测试
tawcroot/test.sh                       # tawcroot 自己的测试
```

设计与实现笔记在 [notes/](notes/README.md) —— **为什么**在那儿，包括镜像源探测、
GPU 策略这类决定背后的实测数据。[AGENTS.md](AGENTS.md) 是本项目的操作手册。

与上游 tawc 一样，本 fork 是智能体构建的：既有文字可能已过期，以源码为准。

## 许可

`deps/` 之外的所有代码是 MIT（[LICENSE.MIT](LICENSE.MIT)）。另有部分 vendored 代码是
GPLv3（termux-shared 的 extra-keys 控件），所以整个项目按 GPLv3
（[LICENSE](LICENSE)）发布 —— 构建出的 APK 也按此传递。

应用内打包的每个组件的归属信息，在应用里「设置 → 关于 → 开源许可」下。

## 致谢

- [tawc](https://github.com/wmww/tawc)，作者 Tess —— 本应用建立在它的容器运行时
  （`tawcroot`）、`ando` broker、终端集成与 libhybris 供给之上。
- [libhybris fork](https://github.com/wmww/libhybris)，版本固定在 `deps/deps.list`。
- [Termux](https://github.com/termux/termux-app)，终端控件来自它。
- DSH 本身是 DeepSeek 的；本应用只是承载它。
