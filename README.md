# Collection

交互动效覆盖首页、详情、新建、设置、主题和原件预览：主题弹簧统一位置/尺寸反馈，颜色/透明度使用effects规格，保留原有Miuix页面转场与系统返回。列表增删、视图切换、批量选择、标签移位和输入展开等详见[ANIMATIONS.md](ANIMATIONS.md)。原有图案底色和配色目标值保留。

文字中的网页链接会在点击AI生成时尝试读取正文，仍只生成标题；原始文字卡片、首页堆叠与域名链接小卡支持网站图标，失败回退链接图标。读取限制、缓存与验证详见[WEB_CONTENT.md](WEB_CONTENT.md)。本版已更新安装到手机，未做手机端测试。

Kotlin + Jetpack Compose + Material 3 Expressive 本地信息收录应用，最低支持 Android 12（API 31）。应用名为“Collection”，applicationId 和 namespace 均为 `com.collection`。可与 `com.collfresh`、`com.collfresh.next` 并存，各自保存数据。

## 当前界面

- 首页大标题随滚动收缩并置顶。右上角为搜索、视图切换、排序和设置；搜索在标题栏内输入。
- 首页标签筛选占满整行，数量较多自动换行。视图切换使用标题栏普通图标按钮。多个标签按同时包含筛选；与标题/标签搜索、六种升降序组合使用。视图、排序和筛选持久保存。
- 已移除标签管理页面。新建页与详情可直接多选标签，末尾加号展开行内输入；长按显示“删除”菜单，点击后删除全局标签，不额外弹确认框。首页移除长按菜单，改为长按拖拽排序。
- 创建页从上到下为标题与AI按钮、虚线多文件添加区、已添加文件、较小的多行文字框、原始文字标题、标签及保存按钮。条目图标选择功能已移除，原始文件类型图标保留。
- AI 只生成标题，可手动修改；替换手动标题前确认。没有总结输入或展示。旧数据库/备份中的总结字段仅兼容保留，不再搜索、展示或生成。
- 详情页原始内容以紧凑卡片显示，左侧是图片缩略图或类型图标，右侧是各自的 AI 标题；未生成时使用原名/文字内容。点击文字直接进入完整原文编辑，勾选保存；点击图片使用应用内缩放查看；PDF、Word 等交给其他应用打开。
- 文本文件原生阅读按段加载，可翻页看完整内容，避免截断及一次性加载大文件。

## 原件、本地数据与备份

条目包含标题、原始笔记、多个附件、多个标签。附件导入时复制全部字节到私有 `filesDir/originals`，保留名称、类型、大小；单文件上限 100 MiB。支持 Android 单个/多个分享导入，多文件可合并为一个条目或独立创建。

SQLite 本地存储，可在设置中启用[坚果云 WebDAV 双向同步](CLOUD_SYNC.md)。ZIP 备份仍可独立使用，包含条目、标签和原件，不含应用密码或 API Key；恢复合并并创建新条目，不覆盖已有条目。备份校验大小、SHA-256、引用和路径，失败回滚。备份自身没有加密。卸载/清除数据会删除本机资料，请定期备份。

## AI 标题

设置中填写自己的 DeepSeek API Key，默认 `deepseek-flash`（模型可配置）。仅在点击生成时上传可读取资料。POST `https://api.deepseek.com/chat/completions`，Bearer 鉴权，非思考模式，JSON Output 只返回 `title`；提示词约一小段，单次输出上限 128 tokens。短资料合并请求；长资料分段取主题后综合标题，避免生成长总结。实际费用仍取决于资料输入量和图片数量。

文字直接读取。图片使用视觉输入。DOCX 提取正文、表格、附属文字及内嵌图片。旧版 DOC 使用 Apache POI HWPF 提取正文，单份解析上限为 32 MiB。PDF 提取文字并对扫描或图形页面转图。解析失败时保留原件并提示，仍可手动保存。

安全上限：文字文件读取 4 MiB、单份材料 200 万字符；PDF 500 页；DOCX 100 张图片/100 MiB 解压内容。超限明确提示未读取部分，建议拆分。GIF 只分析首帧，图像分析副本最长边约 1600px。原件只读不覆盖。

Key 通过 Android Keystore AES-GCM 加密保存在本机，不硬编码、不写入日志或备份。网络请求处理空结果、字段校验、超时、限流和有限重试，支持取消。代理模式兼容服务端 Chat Completions，`server/` 为可自行部署的单令牌个人代理示例，未部署公网服务。公开发布还需用户认证、独立额度和 HTTPS。

## 构建与本地检查

JDK 21、Android SDK 37、Gradle 9.8.1、AGP 9.4.1、Compose 插件 2.4.20，Material3 固定 `1.5.0-beta02`，主题取色使用兼容该 Kotlin 版本的 MaterialKolor `4.1.1`。换机器后修改 `local.properties` 的 SDK 路径。

```powershell
.\gradlew.bat :app:assembleDebug
.\gradlew.bat :app:testDebugUnitTest :app:lintDebug
node --test server/proxy.test.mjs
```

当前安装包：`dist/Collection-1.1.0-debug.apk`（个人测试用调试签名）。本地界面截图位于 `app/build/reports/screenshots`。测试中的示例内容不进入安装包和实际资料库。

手机按用户要求只安装更新，不执行手机端测试。未使用真实 Key 调用官方付费 API。设备自动化测试源码保留，API 37 的 Espresso 兼容性尚未作为通过验证。

官方组件替换映射见 [ANDROIDX_UI.md](ANDROIDX_UI.md)。

条目图标：38个Google官方Material Symbols选项，编辑页可更换，支持自动识别、数据库升级与备份恢复。首页卡片仅移除日期和摘要，保留标签及附件信息。详见 [MATERIAL_SYMBOLS.md](MATERIAL_SYMBOLS.md)。


## 最新调整：原件卡片与标签操作

- 每份附件有独立 `title`，文字有 `bodyTitle`。编辑页标题旁的生成按钮会逐份分析原件，生成各自标题；多份材料的条目标题只综合短主题，单份材料直接复用标题以减少请求。仍只生成标题，单次输出128 tokens。未生成或失败时暂用原名/文字内容，不伪造结果；成功的部分保留并提示失败原因。
- 原始内容卡片左侧：图片用本地缩略图（限采样尺寸，读取EXIF方向），其他文件及文字用官方类型图标；右侧展示该原件标题。点击文字/图片原生查看，其他文档交第三方应用。
- 编辑页移除宣传行和AI说明大块，保存按钮为16dp圆角矩形。
- 首页标签长按可置顶、前移、后移、确认删除。删除保留条目和原件，并清理标签关联/筛选；顺序保存在数据库，备份按当前顺序导出。
- 首页仅单列/两列视图，不再有旧详细列表模式；偏好继续记忆。
- 数据库从v1/v2无损升级至v3，新增原件标题及标签顺序；备份字段均可选，旧备份仍兼容。

## AndroidX 架构与适配

- ViewModel通过只读StateFlow发布不可变AppUiState；界面使用collectAsStateWithLifecycle，导航和提示事件按生命周期收集。
- Miuix NavDisplay 0.9.4管理首页、详情、编辑和设置的真实返回栈。仅传条目ID；保存后移除编辑页，返回恢复首页滚动位置；未保存编辑仍需确认放弃。
- Adaptive 1.3.0提供窗口信息。大屏限制表单阅读宽度，手机保留紧凑布局；卡片最小高度随大字号增长。
- MaterialExpressiveTheme保持固定Material3 1.5.0-beta02，导航使用Miuix共享驱动；动态配色开关会持久保存，Android 12以下回退品牌色。保存提示避让底部操作。

版本核验、官方实现与Samples来源、页面规划见 [ANDROIDX_REVIEW.md](ANDROIDX_REVIEW.md)。

2026-11-05最终检查：assembleDebug成功，74项本地测试通过，lint通过（0错误、21项警告）；代理示例10项测试通过。预测返回专项测试覆盖左右边缘中间帧、取消复位、完成提交、未保存确认及多级返回，主题测试覆盖明暗模式、种子色、转场切换和设置持久化。已检查主要页面、深浅色、宽屏及大字号本地截图。最终主题版 APK 已生成；安装时手机未连接，未在手机执行测试。

## 设置、主题与预测返回

设置页新增“主题设置”入口，打开独立主题页；页面按 KernelSU / Miuix 的视觉层级重做：大标题、手机预览卡、种子色圆盘、明暗模式、配色风格、色彩标准、预测性返回、横移返回、页面切换手势和界面缩放。配色使用 MaterialKolor 从种子色生成完整 Material 3 方案；主题模式支持跟随系统、浅色、深色和纯黑；动态配色在 Android 12+ 可切换为壁纸取色。所有选择立即生效并持久保存。

页面转场按 KernelSU 的 Miuix `MiuixDefault` 复刻：上层页面整屏滑入/退出，下层页面做 25% 视差并降到 0.9 透明度；预测返回使用同一几何和线性规格，由系统手势进度实时 seek，传统返回按钮使用 Miuix 程序化缓动。主题页可切换“淡入淡出”；关闭“预测性返回手势”时不再播放跟手预览但保留正常返回；关闭“横移返回手势”时固定按布局方向退出。未保存编辑/设置和搜索只在这些局部状态使用 `PredictiveBackHandler`。根首页不拦截返回，保留系统返回桌面动画。Material 3 `ModalBottomSheet` 保留官方预测返回实现。完整依据、版本核验与验证范围见 [PREDICTIVE_BACK.md](PREDICTIVE_BACK.md)。



## 参考项目转场复核

已核查KernelSU、LuckyTool及Miuix导航源码。文字全文、图片和文本附件预览接入现有统一NavHost，复用Miuix风格转场；页面按公开WindowInsets圆角裁剪，API26–30保留平角回退。完整来源、与原库的差异和ColorOS兼容边界见[REFERENCE_MOTION_REVIEW.md](REFERENCE_MOTION_REVIEW.md)。本轮77项本地测试通过；当前未连接手机，未执行手机端测试。

## 紧凑卡片与链接

首页单列卡片最小高度80dp、两列104dp；原件卡片最小高度80dp，减少上下内边距，保留大字号增长。编辑页将图标选择按钮放在标题输入框左侧，AI标题按钮在右侧。

详情从原始笔记正文在本机提取HTTP/HTTPS、www及域名链接，去重后显示域名和完整地址摘要，点击调用浏览器；打不开时提示。中文标点/Markdown尾括号会剥离，平衡的路径括号和查询编码保留，不将邮箱或含凭据地址识别为打开卡片。不会自动访问网站或改写原文；附件中尚未解析出的正文不参与此处链接提取。


## 视频转场与原件标题

导航已直接迁移至参考项目的miuix-nav 0.9.4，Kotlin/Compose插件2.4.20、JDK/JVM21。编辑页可修改附件与文字的独立显示标题，保留原文件名和内容。当前架构、源码来源及视频逐帧分析见[VIDEO_MOTION_IMPLEMENTATION.md](VIDEO_MOTION_IMPLEMENTATION.md)，其内容取代旧转场试验记录。


本轮视频转场/原件标题修改：85项本地测试通过，编译和静态检查通过（0错误、22警告）。新版已覆盖安装到RMX5010，未进行手机端测试。


## 条目详情内直接编辑

已移除已有条目的编辑页入口与底部编辑按钮；创建页仅用于新建/分享导入。点击详情标题进入输入，勾选或键盘完成即独立保存；图标、标签、新建标签、添加文件与文字编辑均在详情操作，不跳转编辑页。链接卡片置于原始内容标题和卡片之前。

长按文字或附件卡片可修改标题、编辑文字/移除资料，或选择“AI 生成标题”。此操作仅提交选中原件（文字只提交文字，附件只提交该附件），仅更新该原件标题，条目标题及其他原件标题不变。替换已有标题先确认，失败保留原内容，支持重试/取消。条目级AI按钮只更新条目标题。所有修改经本地库持久化；不更改原文件名、路径或字节。

详情就地编辑验证：89项本地测试通过，assembleDebug和lintDebug通过（0错误、22警告）；已检查链接位置与直接保存截图。新版覆盖安装到RMX5010，保留数据，未进行手机端测试。


## 新图标与动态配色

桌面图标已重设计为叠页收拢标记，参考PurposeGate采用独立前景/背景、深浅色系统调色板及Android13主题单色层。界面默认跟随壁纸动态色，本次升级启用一次，之后手动选择会记忆；原来的自定义种子色保留。详见[ICON_DESIGN.md](ICON_DESIGN.md)。

## 标签、原件与批量操作

详情的已有标签使用FlowRow自动换行，点击即选中/取消，选中项前置；末尾加号展开行内标签输入，重复名称就地提示，长按标签先显示“删除”菜单，点击后删除。标签读写按顺序执行，正常操作不弹出进度框，失败用底部提示。删除作用于全局标签及其关联，保留条目与原件。条目图标选择功能已移除；原始内容图案的底色和形状保留。添加内容仅为“原始内容”同一行右侧加号，点击直接打开系统多文件选择器；文字卡片打开后直接可编辑并支持键盘避让，勾选保存，不再切换编辑模式。没有正文的条目提供“添加文字”卡片。

首页只通过长按条目进入多选，标题栏批量图标已删除；原始内容标题旁进入批量模式。均支持全选、取消全选、确认删除与退出；首页删除条目在一次数据库事务中执行，原件批量移除只影响选中资料。批量模式返回先退出选择。

首页最多3张原件缩略图从左上向右下叠放，偏移间距为4dp，左上第一张位于最上层，依次向右下递减；1/2/3张均按整个图案范围居中，与详情共用OriginalVisual（图片采样缩略图、其余类型图标）。原始文字包含网页链接时使用链接图标。字号对齐PurposeGate的Material3默认Typography与字重，仍尊重系统字号和原有界面缩放设置。

动态色彩图标新增独立偏好，采用DynamicLauncher/StaticLauncher双alias，先启用目标再停用旧入口，DONT_KILL_APP，重启时恢复选择；固定模式使用固定品牌色，动态模式读取系统壁纸色。桌面自身的主题图标开关仍由系统/启动器管理。

Miuix进一步可借鉴的部分：OverlayDialog/WindowDialog的弹层关闭体验，以及ScrollEndHaptic的滚动边界反馈。当前不额外引入miuix-ui整套控件，避免与MD3E表单和配色角色混杂。PurposeGate自身设计说明列出的参考是AndroidX Material3 Catalog、Tomato、Breezy Weather、Pause/Nudge；不是这些项目源码的直接合并。

## 历史：新旧版本并存

此前独立新版应用ID为 `com.collfresh.next`，桌面名称“拾页·新版”；原版 `com.collfresh` 保留。两者的数据、设置、API Key和文件目录独立，不自动复制旧版内容。FileProvider权限使用应用ID生成，桌面图标切换操作限定在当前应用包内。安装新版不覆盖旧版，可直接打开旧版继续使用。

依据：[Android官方应用ID与namespace说明](https://developer.android.com/build/configure-app-module#set-application-id)。源代码namespace保持 `com.collfresh`，安装身份以applicationId为准。

2026-11-06验证：98项本地测试全部通过，assembleDebug与lintDebug通过。安装包 `dist/拾页-新版-1.0.0-debug.apk` 已通过ADB安装到RMX5010；包列表确认 `com.collfresh` 与 `com.collfresh.next` 同时存在，未启动应用或在手机端测试。Windows上的Robolectric不兼容FileProvider的原生路径分隔检查，因此该身份验证检查合并后的provider权限、元数据和独立authority；不将其声称为手机文件打开测试。


## 2026-11-06 行内编辑与布局来源

依赖保持Compose BOM 2026.09.00、Material3 1.5.0-beta02、Miuix导航0.9.4，不新增UI库。调整使用公开API，参照AndroidX官方源码与Sample：

- [Box布局和contentAlignment](https://github.com/androidx/androidx/blob/androidx-main/compose/foundation/foundation-layout/src/commonMain/kotlin/androidx/compose/foundation/layout/Box.kt)：堆叠总范围居中，避免1/2张图案偏上。
- [Modifier.zIndex](https://github.com/androidx/androidx/blob/androidx-main/compose/ui/ui/src/commonMain/kotlin/androidx/compose/ui/ZIndexModifier.kt)：第一张更高zIndex，保持内容顺序并明确绘制层级。
- [FlowRow官方Sample](https://github.com/androidx/androidx/blob/androidx-main/compose/foundation/foundation-layout/samples/src/main/java/androidx/compose/foundation/layout/samples/FlowRowSample.kt)：首页、新建页、详情标签按可用宽度换行，无横向嵌套滚动。
- [Material3 TextField官方Sample](https://github.com/androidx/androidx/blob/androidx-main/compose/material3/material3/samples/src/main/java/androidx/compose/material3/samples/TextFieldSamples.kt)：行内输入、IME完成、重复校验和原文字段编辑。

本次验证：103项本地测试通过，涵盖1/2/3张图案居中和像素层级、首页/详情标签换行、连续标签读写、行内新增/重复校验/长按删除、加号直接请求文件选择和文字页直接编辑。补上详情页IME避让后15项相关界面测试再次通过；assembleDebug、assembleDebugAndroidTest、lintDebug成功（lint 0错误、25警告）。已人工检查电脑生成的新建、首页、详情及原文编辑截图。最终APK更新到 `dist/拾页-新版-1.0.0-debug.apk` 并安装到RMX5010；安装后包列表仍同时包含 `com.collfresh` 与 `com.collfresh.next`。未启动应用、未运行手机端测试。


## 统一主题标题栏与标签拖拽

主题设置移除滚动内容内的ThemeHeader，改用与设置/详情/新建共用的CollectionTopBar（MediumFlexibleTopAppBar）和Scaffold嵌套滚动。标题随滚动收缩，顶部返回按钮固定，按钮和系统返回均交给现有Miuix页面栈返回设置父页，不新增手动页面返回动画。主题内容与配色保留。

首页标签保留FlowRow换行，删除置顶/前移/后移长按菜单。HomeTagFilters通过Foundation公开的detectDragGesturesAfterLongPress处理长按拖动：跨行命中、跟手平移、按下抬起反馈、边缘自动滚动，松手一次保存完整顺序；取消手势恢复原顺序。按住不移动不会改变筛选。辅助功能用户可通过自定义前移/后移动作完成排序。排序使用原有数据库position字段，后台串行保存，筛选条件不变。

条目内（详情和新建共用EntryTagsRow）长按标签改为Material3 DropdownMenu，菜单仅“删除”，点击前不执行删除。普通点击仍为选择/取消，新增仍为行内输入。

官方来源：[Foundation拖拽手势实现](https://github.com/androidx/androidx/blob/androidx-main/compose/foundation/foundation/src/commonMain/kotlin/androidx/compose/foundation/gestures/DragGestureDetector.kt)、[Material3标题栏Samples](https://github.com/androidx/androidx/blob/androidx-main/compose/material3/material3/samples/src/main/java/androidx/compose/material3/samples/AppBarSamples.kt)。依赖版本不变；使用公开API，不复制内部实现。

验证：56项相关本地测试全部通过，包含跨行实际触摸拖拽、长按不移动、排序持久化、删除菜单点击前后状态、主题标题栏置顶和预测返回取消/返回父页。assembleDebug和lintDebug成功（0错误、24警告），新主题顶部/滚动后和拖拽后截图已人工检查。最终APK安装到RMX5010的com.collfresh.next；保留com.collfresh，未启动或测试手机上的应用。


## Collection名称与包名

当前应用名为Collection，applicationId、namespace、Kotlin主代码及测试包统一为com.collection，工程名为Collection，源目录位于app/src/*/kotlin/com/collection。桌面名称、首页标题、设置说明、主题预览与备份文件名前缀均同步改名，app_name集中存于res/values/strings.xml。动态/固定图标的两个入口同步为com.collection.DynamicLauncher与com.collection.StaticLauncher，文件共享authority使用com.collection.files，Activity为com.collection.MainActivity。

这是新的安装身份，可与此前两个包并存，旧应用及其数据保留。当前包数据独立；可用原有ZIP备份导入条目和原始文件，API Key不包含在备份内。原有备份格式、文件保留方式和界面功能不变。

改名验证：112项本地测试通过；备份名称改用Compose stringResource后，22项启动入口/导航检查再次通过。assembleDebug、assembleDebugAndroidTest、lintDebug成功（0错误、24警告）。APK实际应用名为Collection、应用ID为com.collection；DEX包含com.collection.MainActivity且不再包含com.collfresh代码包。已核对首页展开及收缩标题的截图。交付 `dist/Collection-1.0.0-debug.apk`，SHA-256为 `AC25CAED6C77DD903F3972438111C70188FDD34041276CC395991A29A09B1986`。打包完成后手机未连接，尚未安装此包，未做手机端测试。
