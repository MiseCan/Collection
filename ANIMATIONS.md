# 交互动效

本轮基于实际依赖Compose BOM 2026.09.00、Material3 1.5.0-beta02、Miuix导航0.9.4。主题继续采用MaterialExpressiveTheme和MotionScheme.expressive；不增加第三方动画库，不修改配色目标值、图案底色或导航模型。

位置、尺寸、旋转和按压反馈取主题spatial规格；颜色和透明度取effects规格。自定义交互没有自行写死弹簧阻尼、刚度或时长。系统返回手势和图片缩放直接跟随手指，回弹才使用动画。

| 界面/交互 | 实现 |
| --- | --- |
| 全部页面进出、上下级返回 | 保留已经验证的Miuix NavDisplay及预测返回；不在页面外再套一层转场 |
| 各页标题栏滚动 | 继续使用共用AndroidX TopAppBar滚动行为 |
| 首页搜索开关 | 原有AnimatedContent和SizeTransform，统一使用主题规格 |
| 首页单列/两列 | 视图按钮和卡片内容淡变、尺寸衔接；同一个LazyVerticalGrid保留状态和稳定key |
| 首页搜索/排序/筛选/删除 | LazyItemScope.animateItem负责淡入淡出和列表位置变化；空内容也参与过渡 |
| 首页长按标签拖拽 | 被拖动项直接跟手，其他标签弹簧移位；保留跨行、边缘滚动、取消恢复和松手持久化 |
| 首页/详情批量管理 | 操作栏淡入淡出；首页卡片右边缘固定，左边缘向右收缩，外侧选择框水平展开。新增按钮使用Scaffold的FabPosition.EndOverlay固定退出位置，避免被进入的批量栏顶起。所选数量和全选文字淡变 |
| 新建/详情标签 | FlowRow整体高度变化和选中前置平滑衔接；加号旋转、行内输入展开收起；删除菜单仍由原生DropdownMenu处理 |
| 新建原件与标题输入 | 附件插入、移除、邻项让位和原件标题输入展开收起；静态区域均有稳定key |
| 详情标题编辑 | 标题与输入框淡变、尺寸过渡；保存先清除焦点，避免退出中的输入框保留键盘 |
| 详情链接、原始资料、AI状态 | 插入/移除和邻项移动使用animateItem；AI生成后的原件标题淡变、卡片高度衔接 |
| 首页/原始内容卡片 | 复用combinedClickable的InteractionSource做轻微按压回弹，保留原生涟漪与长按语义，无第二个点击识别器 |
| 缩略图和首页堆叠 | 缩略图加载后淡入；堆叠内容变化时居中区域尺寸和原件位置平滑衔接，保持左上顶层到右下底层 |
| 原文编辑 | 打开即为可编辑状态，保存/输入继续使用原生组件反馈，不增加多余编辑模式动画 |
| 图片/文字附件预览 | 加载、错误、重试、原件之间淡变；分段/页码文字过渡，加载时保留分页栏，手指缩放和平移不添加延迟 |
| AI设置 | 官方/代理输入区淡变和高度过渡，校验信息展开收起；密钥显示/隐藏立即切换，避免敏感文字在退出画面中残留 |
| 主题设置 | 全部48个公开ColorScheme角色一起插值，保留最终色板；模式选中色、展开箭头、设置值和缩放百分比过渡 |
| 状态与分享导入 | StateScreen文案淡变、加载/重试控件展开；分享说明随合并/独立选择展开 |
| 按钮、开关、复选框、输入框、菜单、确认框、Snackbar、系统文件选择 | 保留Material3和系统自身反馈，不叠加第二层缩放/转场 |

## 生命周期、快速操作与无障碍

MotionReveal和MotionSwap只为退出画面保留绘制，不保留点击或无障碍动作，避免旧按钮在淡出时再次触发。行内输入、代理切换和标题保存主动清除不再使用的焦点。输入、图片手势、用户拖拽本身不缓动。

MotionPlacement不让初次出现的标签从屏幕原点飞入；只对后续位置变化做弹簧移动，拖拽项禁用位置缓动。列表删除交给官方Lazy动画维护退出生命周期，不保留可操作的业务副本。

动画均使用Compose的动画时钟及MotionDurationScale，跟随系统动画设置；没有用delay拼接装饰性动画或阻塞用户操作。提供零时长测试，验证展开和配色在下一组帧回调中直接完成。自动滚动属于用户拖动行为，不因关闭装饰动画而失去功能。

## 官方来源与验证

- [AndroidX MotionScheme源码](https://github.com/androidx/androidx/blob/androidx-main/compose/material3/material3/src/commonMain/kotlin/androidx/compose/material3/MotionScheme.kt)
- [AndroidX AnimatedVisibility Samples](https://github.com/androidx/androidx/blob/androidx-main/compose/animation/animation/samples/src/main/java/androidx/compose/animation/samples/AnimatedVisibilitySamples.kt)
- [AndroidX AnimatedContent Samples](https://github.com/androidx/androidx/blob/androidx-main/compose/animation/animation/samples/src/main/java/androidx/compose/animation/samples/AnimatedContentSamples.kt)
- [AndroidX MotionDurationScale源码与测试接入说明](https://github.com/androidx/androidx/blob/androidx-main/compose/ui/ui/src/commonMain/kotlin/androidx/compose/ui/MotionDurationScale.kt)

使用公开API。现有106项本地回归检查通过；新增6项逐帧/中断/预览重试/关闭动画测试通过，进一步覆盖动画过程和退出控件的交互隔离。测试外部状态写入在暂停时钟下显式调用Snapshot.sendApplyNotifications，避免读取更新前的旧画面。未使用真实付费AI请求，未在手机端运行测试。

最终输入焦点修正后21项相关检查再次通过；assembleDebug和lintDebug通过（0错误、24警告）。主要页面的最终布局截图已在电脑上复查。交付 `dist/拾页-新版-1.0.0-debug.apk`，包名保持 `com.collfresh.next`；本轮完成时ADB未检测到手机，尚未安装这份动效版。旧版及手机中已有新版数据未被修改。

后续改名为Collection，代码包和安装包身份统一为com.collection，当前安装包为 `dist/Collection-1.0.0-debug.apk`；本页动效保持。改名后的完整112项检查通过，详见README的Collection名称与包名记录。
