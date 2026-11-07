# AndroidX 技术审查与实施方案（2026-11-05）

## 基线核验

现有工程 assembleDebug 成功。AGP 9.4.1、Gradle9.8.1、Kotlin Compose插件2.2.10、JDK21（源码target17）、SDK37/min26，Compose BOM2026.09.00，Material3 1.5.0-beta02。

从 Google Maven 的实际 sources.jar 核验到公开 MaterialExpressiveTheme、LargeFlexibleTopAppBar、LoadingIndicator；完整MD3E保留此固定beta版本并显式opt-in，未引用internal。Stable Material3 1.4.0不能直接替代完整Expressive界面。新增Navigation Compose2.10.2（Google Maven稳定版本），Adaptive1.3.0（稳定版本；实际源码含currentWindowAdaptiveInfoV2）。不进行无关依赖升级。

## 先读的官方源码与样例

- Foundation实现：compose/foundation/foundation/src/commonMain/kotlin/androidx/compose/foundation/lazy/grid/LazyGrid.kt；公开示例：foundation/samples/.../LazyGridSamples.kt。使用公开LazyVerticalGrid，不复制内部LazyGrid实现。
- Material3实现：compose/material3/material3/src/commonMain/kotlin/androidx/compose/material3/MaterialTheme.kt、AppBar.kt；对应samples/AppBarSamples.kt。
- Animation：compose/animation/animation/samples/src/main/java/androidx/compose/animation/samples/AnimatedContentSamples.kt；用公开动画能力，不依赖Deferred实验API。
- Adaptive实现：compose/material3/adaptive/adaptive/src/commonMain/kotlin/androidx/compose/material3/adaptive/WindowAdaptiveInfo.kt；samples/ThreePaneScaffoldSample.kt。
- Integration：compose/integration-tests/material-catalog/src/main/java/androidx/compose/material/catalog/NavGraph.kt、CatalogApp.kt。
- Navigation：navigation/navigation-compose/samples/src/main/java/androidx/navigation/compose/samples/NavigationSamples.kt、navigation-compose/src/commonMain/kotlin/androidx/navigation/compose/NavHost.kt。
- Lifecycle：lifecycle/lifecycle-runtime-compose/src/commonMain/kotlin/androidx/lifecycle/compose/FlowExt.kt。

上述路径相对于 https://github.com/androidx/androidx/tree/androidx-main ，同一源码也可在 https://android.googlesource.com/platform/frameworks/support/ 查看。本地额外检查固定版本的Maven源码，主分支API不作为已安装版本可用性的唯一依据。

## 页面与状态规划

1. ViewModel不再依赖Compose mutableStateOf；一个不可变AppUiState通过只读StateFlow发布，更新原子化。界面用collectAsStateWithLifecycle收集，业务事件仍由ViewModel处理。
2. NavHost拥有首页、详情(条目ID)、编辑(可选条目ID)、设置四个目的地与回退栈；参数仅传ID，业务数据仍来自ViewModel/本地库。编辑返回保留放弃确认，保存替换编辑目的地，返回首页恢复列表状态。
3. 首页沿用用户确认的单列/两列规则网格、稳定key与contentType；筛选用LazyRow；表单与详情使用LazyColumn，标签用FlowRow。当前是标题资料库，不强行加入瀑布流或额外导航栏。
4. 使用Adaptive窗口信息限制大屏阅读/表单宽度；手机仍优先。卡片使用最小高度而非硬性相同高度，长标题/大字号可增长。图片缩略图保留确定尺寸，不加载后跳动。
5. 保留已有MaterialExpressiveTheme/Shape/Typography/MotionScheme，提供可记忆的动态配色选项(API31+，低版本回退品牌色)。导航采用主题的spring空间动画和效果动画，支持系统动画缩放设置。
6. 本地编译、业务测试、真实NavHost交互测试、深浅色/宽屏与大字号截图后交付。遵守用户既有要求：手机仅安装，不执行手机端自动化测试。

## 已落地的实现

AppUiState/StateFlow作为统一界面快照；AppRoot按生命周期收集状态和事件，AppNavigation集中定义公开NavHost路由、参数及返回规则。保留原有持久化和AI业务逻辑，不将导航组件或Compose状态对象引入数据层。

导航采用MaterialTheme.motionScheme的空间弹簧和效果规格；没有复制AndroidX内部实现。主界面继续采用规则网格，原件缩略图有确定尺寸；当前内容不需要瀑布流。Adaptive仅约束宽屏内容，不额外增加用户未要求的导航区域。

导航验证使用真实AppRoot、ViewModel、StateFlow和TestNavHostController；存储与异步处理也实际执行。检查创建保存、编辑放弃确认、设置返回、详情返回滚动位置，以及1200dp宽屏表单；另检查200%字体与深浅色本地渲染。截图生成于app/build/reports/screenshots。

必要的实验API仅为已存在并核验版本的MD3E主题/动效。Material3固定1.5.0-beta02并显式opt-in；Navigation2.10.2、Adaptive1.3.0使用公开API。测试环境沿用现有Compose JUnit4适配器，未将测试库打入安装包。

缩略图EXIF方向读取从android.media.ExifInterface替换为androidx.exifinterface.media.ExifInterface。Google Maven元数据确认稳定版1.4.2，并从该版本sources.jar核验String路径构造器和getAttributeInt；原件仍只读。参考[AndroidX ExifInterface源码](https://github.com/androidx/androidx/blob/androidx-main/exifinterface/exifinterface/src/main/java/androidx/exifinterface/media/ExifInterface.java)。

直接来源：[Foundation网格示例](https://github.com/androidx/androidx/blob/androidx-main/compose/foundation/foundation/samples/src/main/java/androidx/compose/foundation/samples/LazyGridSamples.kt)、[官方应用导航](https://github.com/androidx/androidx/blob/androidx-main/compose/integration-tests/material-catalog/src/main/java/androidx/compose/material/catalog/NavGraph.kt)、[Material主题实现](https://github.com/androidx/androidx/blob/androidx-main/compose/material3/material3/src/commonMain/kotlin/androidx/compose/material3/MaterialTheme.kt)、[Adaptive窗口信息](https://github.com/androidx/androidx/blob/androidx-main/compose/material3/adaptive/adaptive/src/commonMain/kotlin/androidx/compose/material3/adaptive/WindowAdaptiveInfo.kt)。

## 最终验证

页面转场改为复刻 KernelSU 使用的 Miuix `NavTransitions.MiuixDefault`：上层页面从 trailing 边缘整屏滑入/退出，下层页面视差移动 25% 宽度并淡到 0.9 透明度；程序化 push/pop 使用 KernelSU 的 500ms `NavProgrammaticEasing`，预测返回则用线性规格配合 NavHost 的 `SeekableTransitionState` 1:1 跟手。没有引入 miuix-nav 导航运行时，保留 Navigation Compose 返回栈与 `MainActivity` 不拦截根返回的约束。

主题设置按 KernelSU 的 ColorPalette 页面重做：独立目的地、大标题、手机预览卡、种子色圆盘、明暗模式、配色风格/色彩标准、预测性返回、横移返回、页面切换手势和界面缩放。颜色由 MaterialKolor `4.1.1` 从种子色生成（5.0.x 使用 Kotlin 2.4 metadata，与本项目 Kotlin 2.2 不兼容）；主题模式、动态取色、缩放和转场均持久化，`LocalDensity` 与 `CollTheme` 由 MainActivity 响应式接入。导航栏角标与模块描述行数按本次要求未加入。

局部返回仅用于搜索和未保存编辑/设置：`PredictiveBackHandler` 读取 `BackEventCompat.progress`，完成才执行关闭或确认，取消时弹簧复位。编辑和详情保留退出快照，避免导航过程中读取已清空的数据。新增本地预测返回中间帧、取消/完成、搜索局部返回、未保存确认测试；最新74项测试全部通过，assembleDebug和lintDebug通过（0错误、21警告）。

2026-11-05：assembleDebug、testDebugUnitTest、lintDebug整体构建成功。74项本地测试全部通过，lint为0错误、21警告；代理示例10项测试通过。未使用真实付费Key。主要页面和宽屏、大字号截图已人工查看，保存提示避让底部操作。主题版构建完成时手机未连接，因此未执行最终 APK 安装，也未在手机启动测试或清除数据。交付APK：dist/拾页-1.0.0-debug.apk。


