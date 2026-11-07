> 历史记录：当前已直接接入 miuix-nav 0.9.4。以下旧实现决策已被 [VIDEO_MOTION_IMPLEMENTATION.md](VIDEO_MOTION_IMPLEMENTATION.md) 取代。

# 参考项目转场审查（2026-11-06）

## 实际阅读的源码

- KernelSU `019a3c0103a8f8d56675546016c4073d0fd18352`：[MainActivity](https://github.com/tiann/KernelSU/blob/019a3c0103a8f8d56675546016c4073d0fd18352/manager/app/src/main/java/me/weishu/kernelsu/ui/MainActivity.kt)，以及manager/gradle/libs.versions.toml。前端有Material/Miuix两种组件风格，导航统一使用miuix-nav 0.9.4的NavDisplay、系统圆角和按条目配置的swipeDismiss。
- 用户提供的LuckyTool仓库是发布仓库。另找到作者的源码仓库 `luckyzyx/LuckyTool`，提交 `ba6ade6e2780c702a83835ef326e9128b54156a9`：[AppNavigation](https://github.com/luckyzyx/LuckyTool/blob/ba6ade6e2780c702a83835ef326e9128b54156a9/app/src/main/java/com/luckyzyx/luckytool/ui/compose/AppNavigation.kt)、utils/PredictiveBackUtils.kt、MainActivity、Manifest、依赖表。它同样使用miuix-nav 0.9.4；禁止重复push相同页面，圆角来自rememberNavSystemCornerRadius。
- Miuix源码提交 `4873fc7392871cd6cf3ea263020f5c7032b3de2a`：miuix-nav的NavTransitions、NavDriver、NavSettleEasing、NavDisplayEffects、Android的NavSystemCornerRadius与WindowNavigationEventBridge。

## 审查结果与落地范围

Collection在本轮开始时已有Miuix风格的PageTransitions、主题设置和局部PredictiveBackMotion。保留AndroidX Navigation Compose与Material3组件，不复制参考项目的GPL应用源码，也不把近似的Compose转场描述成完全相同的miuix-nav驱动器。现有主页面几何为上层全宽、下层1/4视差与0.9透明度、500ms程序转场；Miuix还具备独立遮罩、全屏拖动仲裁和连续depth驱动，本项目未完整复刻这些机制。

| 场景 | 实现 |
| --- | --- |
| 首页→详情/编辑/设置→主题 | 既有共享PageTransitions，无单独页面转场参数 |
| 详情→文字全文 | 本轮从全屏Dialog迁入同一NavHost的text/{entryId} |
| 条目/编辑→图片及文本文件预览 | 本轮从独立窗口迁入original/{attachmentId}，同一转场/返回栈 |
| 未保存编辑/设置 | 当前目的地才消费手势；完成后确认，取消恢复，不抢占预览页面 |
| 搜索开合 | 既有AnimatedContent及局部预测进度，不导航到错误页面 |
| 图标选择、标签新建、删除/导入确认、忙碌状态 | 保持Material3标准浮层，避免将小弹层伪装为全屏页面 |
| 第三方文档阅读器/系统文件选择器、返回桌面 | 交由系统/外部应用，不覆盖系统窗口转场 |

本轮页面加入系统圆角裁剪，API31+通过公开WindowInsets.getRoundedCorner读取各角，旧版本或未上报圆角时为0，不假设所有设备都是固定圆角。单窗口预览避免原生Dialog和Activity两套返回窗口竞争。Manifest已显式开启enableOnBackInvokedCallback，首页无条件拦截不存在。

## ColorOS核查

这两个前端及已读Miuix导航源码中未发现独立的ColorOS专用页面动画分支。LuckyTool是ColorOS模块并不意味着它的前端转场调用了ColorOS专属动画：其系统Hook、私有ApplicationInfo反射开关和普通应用的页面栈是不同层级。

本轮兼容措施使用公开AndroidX/Android接口，适用于ColorOS/realme UI/原生系统：开启系统预测回调、单窗口页面栈、读取OEM实际圆角、按当前页面注册局部返回、保留系统外部窗口动画。没有增加Root/Xposed、隐藏API豁免或伪造OEM元数据；不声称已控制ColorOS桌面/SystemUI动画。手机仍按要求只安装，不做设备测试，因此ColorOS实际系统合成效果尚未验证。

验证：77项本地测试通过，assembleDebug和lintDebug成功；检查文字全文的本地截图与返回栈。最终APK已更新至dist/拾页-1.0.0-debug.apk。当前adb无设备连接，未安装手机。

