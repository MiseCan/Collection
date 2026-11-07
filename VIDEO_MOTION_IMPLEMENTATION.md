# 视频转场实现（2026-11-06）

本文件取代此前 PREDICTIVE_BACK.md / REFERENCE_MOTION_REVIEW.md 中关于手写转场和 Navigation Compose 的实现决策。

## 视频观察

用户视频时长4.83秒、30fps，共145帧。本地抽帧保存在build/video-review。约0.8–2.0秒、2.6–3.9秒可见主题页覆盖设置页，保持全尺寸横向滑退；上层前缘圆角、下层视差及遮罩连续变化。右边缘系统返回也遵循LTR页面层级向右滑出。没有全屏缩小或用淡出替代横移。

## 直接实现来源

- KernelSU提交019a3c0103a8f8d56675546016c4073d0fd18352的manager/ui/MainActivity使用miuix-nav 0.9.4 NavDisplay与NavDisplayEffects系统圆角。
- LuckyTool提交ba6ade6e2780c702a83835ef326e9128b54156a9的ui/compose/AppNavigation同样使用miuix-nav 0.9.4；界面风格不同但导航共用驱动。
- https://github.com/compose-miuix-ui/miuix 中miuix-nav模块的core/NavDisplay、runtime/NavDriver、transition/NavTransitions、core/NavDisplayEffects：同一个relativeDepth浮点值驱动拖动、取消、提交；预设MiuixDefault负责全宽上层、四分之一宽下层视差，effects负责遮罩与圆角。

本轮直接依赖Maven Central已发布top.yukonga.miuix.kmp:miuix-nav-android:0.9.4，不再复刻上述库代码。删除旧PageTransitions和WindowMotionShape，移除Navigation Compose；仅迁移导航，Material3/MD3E页面组件、ViewModel、StateFlow、资料库和文件保持原实现。依赖用Kotlin2.4.20、JVM21构建，因此同步升级Kotlin/Compose编译插件和项目JVM target，JDK仍为21，SDK/minSDK不变。

## 页面与状态

CollectionNavigator只负责路由栈数据及保存恢复。CollectionRoute有唯一ID，参数仅传条目/附件ID；rememberSaveable显式保存路径与ID，原文与Key不写入路由。NavDisplay直接渲染该栈，负责手势仲裁、前后台生命周期、滚动/状态保存、共同的depth驱动、取消与提交。没有另一个隐藏NavHost或第二套动画覆盖其上。

首页、详情、编辑、设置、主题、文字和附件预览均注册到同一NavDisplay。重复点击当前路由不重复push；保存移除编辑目的地；返回桌面保留系统处理。后台页面保留绘制，但清除后台语义，避免读屏和操作误入下层。

默认使用MiuixDefault，方向跟随布局层级而非手势来自哪一侧；可选淡入淡出也通过同一depth驱动。横滑手势开关单独控制swipeDismiss。局部搜索和未保存表单仍有自己的PredictiveBackHandler，用于取消恢复/放弃确认；只在当前页面启用，不提前pop。

## 原始标题

编辑页每个附件有修改标题按钮，展开原件标题输入；文字提供原始文字标题输入。改变attachment.title/bodyTitle，随保存条目持久化。保留attachment.name/path/size及全部文件字节。清空标题时卡片回退原文件名/文字内容。手动修改也标记AI内容已编辑，重新生成前确认。

## 验证边界

本地测试覆盖路由恢复、唯一ID和参数、完整栈返回、滚动位置恢复、原文预览、手势帧的线性跟随/提交连续性/取消恢复、前台语义及原件标题编辑不改原件元数据。手机仅安装，不执行手机端测试。视频效果使用同一个库实现，不声称已经验证所有ColorOS系统版本；不安装Xposed/Root或修改系统动画。

最终验证：85项本地测试全部通过，assembleDebug/testDebugUnitTest/lintDebug整体构建成功（lint 0错误、22警告）。检查了手势叠层截图与原件标题编辑截图。最终APK已保存到dist/拾页-1.0.0-debug.apk，并覆盖安装到RMX5010，adb返回Success。手机未启动或执行测试。
