# 条目图标

素材来自 [Google 官方 Material Symbols 仓库](https://github.com/google/material-design-icons)，使用 Rounded 24px 静态 SVG，以原始路径转换成 Android VectorDrawable。共38项，包含自动识别选项。

固定源代码提交：`10ecc14790612ca8fd164e3f4ffe9c16fc50d5b7`。逐文件链接和 SHA-256 记录在 `material-symbols-sources.json`。Apache 2.0 许可证随安装包收录于 `assets/licenses/material_symbols_LICENSE.txt`。

在编辑页的「条目图标」按钮中选择，保存条目后生效；选择「自动识别」可恢复按照文件类型、链接、邮箱、电话等内容推断。不会为选图标调用 AI。

数据库从v1无损升级到v2；备份以可选 `iconKey` 字段保存。旧备份缺少该字段时自动识别，未知图标ID也安全回退。

可选类型：自动识别、文字、链接、图片、邮箱、PDF、文档、表格、演示、音频、视频、压缩包、代码、网页、电话、联系人、位置、日程、待办、书籍、聊天、灵感、研究、票据、支付、文件夹、附件、多种资料、私密、收藏、二维码、下载、云端、数据库、工作、人物、安装包、字体。

首页卡片高度108dp，列表模式88dp。按用户更正只移除日期与摘要，保留图标、标题、标签、文字/附件信息及导航箭头。
