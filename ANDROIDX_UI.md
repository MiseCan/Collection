# AndroidX 官方界面替换记录

本次范围是界面与交互组件。直接使用 Maven 中固定版本的 AndroidX 库；按照官方仓库示例接线，不复制整套 AndroidX 源码进应用。上游主分支部分 API 比 1.5.0-beta02 更新，采用当前依赖可编译的正式公开重载。

| 界面 | 本次处理 | 官方参考 |
| --- | --- | --- |
| 首页标题 | 保留上次确认的 LargeFlexibleTopAppBar 与 exitUntilCollapsed，缩小后置顶 | AppBarSamples.kt |
| 编辑、详情、标签、设置标题 | MediumFlexibleTopAppBar，nestedScroll 联动，内容保持在标题栏下方 | AppBarSamples.kt |
| 附件、标签、首页列表模式 | 用 ListItem 的 content / supportingContent / leadingContent / trailingContent 替换手写布局 | ListSamples.kt |
| 卡片模式 | 使用官方 Card 和主题 shape / container tokens，移除不必要的自定义不对称卡片边角 | CardSamples.kt |
| 列表/卡片选择 | 原有 FilledTonalToggleButton 连接为官方首尾按钮组形状，补 selectableGroup 单选语义 | ButtonGroupSamples.kt |
| 代理设置 | ListItem + 整行 toggleable + Switch(onCheckedChange=null)，单一读屏操作 | SwitchSamples.kt、ListSamples.kt |
| 多文件导入选择 | selectableGroup + 整行 selectable + RadioButton(onClick=null) | RadioButtonSamples.kt |
| 图片/PDF 缩放 | rememberTransformableState + transformable，保留缩放范围与平移边界 | Foundation TransformableSample.kt |
| PDF 翻页底栏 | FlexibleBottomAppBar | AppBarSamples.kt |
| 方向性图标 | 使用 AutoMirrored 变体，随布局方向正确镜像 | AppBarSamples.kt |

已是官方组件、无需重新仿写的部分：Scaffold、OutlinedTextField（含首页组合检索输入）、FilterChip、AssistChip、DropdownMenu、AlertDialog、SnackbarHost、Button、ExtendedFloatingActionButton、LoadingIndicator、LazyColumn、LazyVerticalGrid、系统文件选择器 ActivityResultContracts、MaterialExpressiveTheme 和 MotionScheme。

保留的定制仅包括：应用配色、业务文案和内容排列、要求的虚线文件添加边框、空/错状态的内容组合、附件读取与渲染。AndroidX 不提供此应用专属的 AI、文档解析、信息收录业务组件，保留原有业务实现。

## 官方源码链接

- [Material 3 官方示例目录](https://github.com/androidx/androidx/tree/androidx-main/compose/material3/material3/samples/src/main/java/androidx/compose/material3/samples)
- [AppBarSamples.kt](https://github.com/androidx/androidx/blob/androidx-main/compose/material3/material3/samples/src/main/java/androidx/compose/material3/samples/AppBarSamples.kt)
- [ListSamples.kt](https://github.com/androidx/androidx/blob/androidx-main/compose/material3/material3/samples/src/main/java/androidx/compose/material3/samples/ListSamples.kt)
- [ButtonGroupSamples.kt](https://github.com/androidx/androidx/blob/androidx-main/compose/material3/material3/samples/src/main/java/androidx/compose/material3/samples/ButtonGroupSamples.kt)
- [RadioButtonSamples.kt](https://github.com/androidx/androidx/blob/androidx-main/compose/material3/material3/samples/src/main/java/androidx/compose/material3/samples/RadioButtonSamples.kt)
- [TransformableSample.kt](https://github.com/androidx/androidx/blob/androidx-main/compose/foundation/foundation/samples/src/main/java/androidx/compose/foundation/samples/TransformableSample.kt)


最新调整：标签管理页已删除；首页搜索与排序改为顶部动作，标签筛选与单个视图切换图标同一行。详情原件采用 Card 紧凑展示，文字/图片原生阅读，PDF/Word 等通过 ACTION_VIEW 调用第三方阅读器。

原件卡片新增本地图片缩略图及独立标题，其他类型使用官方Material Symbols。标签长按沿用FilterChip原生InteractionSource按压事件，并提供读屏长按语义；顺序菜单和删除确认使用官方DropdownMenu/AlertDialog。视图切换为单列与固定两列LazyVerticalGrid。
