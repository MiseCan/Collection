# 拾页图标与动态配色（2026-11-06）

参考PurposeGate的实现方式，而非复制其门形图案：

- D:/PROGRAM/BUILD/purposegate/app/src/main/res/drawable/ic_launcher_foreground.xml / ic_launcher_monochrome.xml
- mipmap-anydpi-v26/ic_launcher.xml、values-v31/launcher_colors.xml、values-night-v31/launcher_colors.xml
- app/src/main/java/com/purposegate/Theme.kt中的系统动态色、深浅色、MaterialExpressiveTheme与MotionScheme.expressive。

## 图形

新的“叠页收拢”标记以两张错位页面表达混合资料收录。前页采用圆角和折角，两道透明短线在小尺寸仍可辨认；没有字母、投影或细小装饰。108dp矢量画布，关键内容在x32–74、y30–78内，落在中心66dp安全区域。背景独立铺满，不预先裁切圆形或圆角矩形，让桌面决定形状。

前景及单色图形各为原生 VectorDrawable。API 31–32 使用自适应图标的前景和背景，API 33 及以上的启动器可使用单色层。普通图标和 roundIcon 使用相同图形。单色版本的透明短线可随桌面主题着色。

## 色彩

普通图标使用系统 `system_accent1` 调色板。浅色和深色配置分别位于 `values` 和 `values-night`，自适应图标位于 `mipmap-anydpi`。支持主题图标的 Android 13 及以上启动器可统一着色。关闭动态色彩图标开关时，应用使用固定品牌色图标。

应用继续使用MaterialExpressiveTheme、官方Dynamic Color与Material色彩角色。默认启用壁纸动态色；此次重设计让已有安装启用一次，保留原来的种子色，之后用户关闭/重新选择种子色会持久保存。纯黑模式保留黑色底，卡片容器继续使用当前调色板，删除了固定绿灰容器色。原生启动背景和系统栏明暗也与深浅色协调。

## 核验

- [官方自适应图标规范](https://developer.android.com/develop/ui/compose/system/icon_design_adaptive)
- [Compose Material3与动态配色](https://developer.android.com/develop/ui/compose/designsystems/material3)
- LauncherIconTest：SDK30品牌回退、SDK35前景/背景/单色加载与深浅色绘制、迁移后手动关闭仍持久保存。
- 本地图标对照图：app/build/reports/screenshots/launcher-redesign.png。其颜色来自测试环境系统调色板，手机上会随壁纸与启动器设置变化。


完整92项本地测试通过；最终图标资源补充测试通过，assembleDebug与lintDebug成功。最终包已覆盖安装到RMX5010，保留数据，未进行手机端测试。

