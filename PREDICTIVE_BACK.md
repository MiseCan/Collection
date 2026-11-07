> 历史记录：当前已直接接入 miuix-nav 0.9.4。以下旧实现决策已被 [VIDEO_MOTION_IMPLEMENTATION.md](VIDEO_MOTION_IMPLEMENTATION.md) 取代。

# 预测式返回：KernelSU / Miuix 页面转场复刻

## 版本与配置核验（2026-11-05）

- Kotlin Compose 插件 `2.2.10`，Compose BOM `2026.09.00`。
- `androidx.activity:activity-compose:1.13.0`（`PredictiveBackHandler`、`BackEventCompat`）。
- `androidx.navigation:navigation-compose:2.10.2`。
- Material 3 `1.5.0-beta02`；`ModalBottomSheet` 自带官方预测返回与拖动进度。
- MaterialKolor `4.1.1`（兼容 Kotlin 2.2 的最后一个稳定分支），负责种子色生成 Material 3 配色。
- `compileSdk 37`、`targetSdk 37`、`minSdk 26`；`AndroidManifest.xml` 的 `<application>` 显式设置 `android:enableOnBackInvokedCallback="true"`。

## KernelSU 的做法（源码核验）

参考 [tiann/KernelSU](https://github.com/tiann/KernelSU) `28db74f` 和其依赖的 [miuix-nav](https://github.com/miuix-kotlin-multiplatform/miuix) `fa6f5b5`：

- KernelSU 的 `MainActivity.kt` 使用 `NavDisplay`，传入 `NavDisplayEffects(cornerClipRadius = rememberNavSystemCornerRadius())`，默认页面转场是 `NavTransitions.MiuixDefault`。
- `MiuixDefault` 的页面层几何是：
  - 进入页从 trailing 边缘整屏滑入（LTR 从右向左，RTL 反向）；
  - 下层被覆盖页向 leading 方向视差移动 `0.25 × 宽度`，透明度降到 `0.9`；
  - pop 时完全反向。
- 程序化 push/pop 使用 500ms 的 `NavProgrammaticEasing`（把欠阻尼弹簧阶跃响应烘焙进 tween）。
- 预测返回由单个 `animatedTop` 驱动，`NavPredictiveBackDriver` 对手势事件执行 1:1 的 `snapToFinger`，没有在手指路径上再叠加 easing；松手后使用弹簧收敛。
- `NavDisplayEffects` 另外提供前缘系统圆角裁剪和 50% dim scrim；这是 Miuix 自研导航运行时的一部分。

## 本应用的实现

本应用保留既有 Navigation Compose 返回栈，不引入第二套导航运行时。通过 Navigation Compose 公开的转场回调复刻同一组页面层动画（`PageTransitions.kt`）：

- `enterTransition`：上层页面从 trailing 边缘整屏滑入。
- `exitTransition`：下层页面向 leading 方向移动 25% 宽度，同时淡到 `0.9`。
- `popEnterTransition` / `popExitTransition`：按 Miuix 的同一映射反向播放。
- `predictivePopEnterTransition` / `predictivePopExitTransition`：使用完全相同的几何，但动画规格改为 `LinearEasing`，配合 NavHost 的 `SeekableTransitionState` 与系统 `BackEventCompat.progress`，让上层页面和下层视差在松手前 1:1 跟手。
- 左右边缘手势都交给系统。主题页的“横移返回手势”开启时，预测转场根据 `swipeEdge` 镜像退出方向；关闭时固定按布局方向从 trailing 边缘退出。传统返回按钮始终走 trailing 方向，并用 500ms 的 `MiuixNavEasing` 复刻 KernelSU 的从静止 push/pop 节奏。
- 取消手势时 NavHost 从当前进度回到起点；只有手势完成才真正 pop。
- 主题页可关闭“预测性返回手势”：关闭后系统手势仍能正常完成返回，但不播放跟手预览；“页面切换手势”可切换 Miuix 层级滑动或淡入淡出。
- 未在 `MainActivity` 或根 Activity 注册返回拦截；根首页交给系统做返回桌面预测动画。
- 搜索、未保存编辑/设置仍只在局部状态使用 `PredictiveBackHandler`，读取 `BackEventCompat.progress` 做跟手；普通页面不重复实现导航。

当前没有把 Miuix 的 `NavDisplayEffects`（系统圆角裁剪与 dim scrim）引入，因为该效果层依赖 Miuix 自研 `NavDisplay`，而 Navigation Compose 不为每个 destination 暴露整页 scrim/clip 层。页面层位移、视差、透明度与预测返回跟手已按 KernelSU 的 `MiuixDefault` 几何复刻。

## 验证

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

本地 Robolectric 测试覆盖：预测返回松手前中间帧、取消复位、完成提交、左右边缘、未保存设置/编辑页确认、搜索局部返回、多级返回和返回后状态保留。预测测试在松手前截图检查页面已经跟随进度变化，不是松手后的普通退出动画。
