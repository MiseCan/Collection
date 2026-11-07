# 网页正文参与标题生成与网站图标

本版保持只生成title，不增加summary或摘要区域。原始文字、网址与文件不改写，标题依旧可以手动编辑、重新生成；替换已有标题沿用原有确认流程。

点击AI生成时，AiService从条目文字及已解析的文本资料中识别HTTP(S)链接，获取公开网页HTML，提取article/main等正文，移除脚本、导航、表单、广告和页脚，作为新的资料片段交给现有DeepSeek标题请求。一次资料分析最多读取3个不同链接，HTML上限2 MiB，单页正文上限12000字符；较长内容继续走现有分段/综合流程并显示部分读取提示。多个原件分别生成标题时，每份资料单独使用该限制。

读取失败的网址从AI输入中移除，避免把网址当正文猜标题；原始条目中的网址保留。只有链接且全部失败时保留已有标题并报错，混合资料则继续分析成功的部分并提示失败原因。生成后的短标题综合阶段不再识别或请求新网页，也不跟随网页正文中的其他链接。重试成功后清理该域名此前的读取警告。

## 图标

原始文字卡片和首页堆叠采用正文中的第一个链接的网站图标；域名链接小卡分别使用各自网站图标。图案容器底色、层级、堆叠规则保留，图标加载后沿用现有淡入动画。

显示图标会读取原站有限大小的HTML元信息，优先选择网页声明的apple-touch-icon或rel=icon，再尝试/favicon.ico。无需第三方图标服务；成功图标按origin缓存到应用私有cache目录并提供2 MiB内存缓存，文件保存7天；失败缓存1小时。支持Android常见位图以及ICO中的PNG、24/32位DIB图像。SVG等不可解码的图标继续尝试其他候选，全部失败时回退为链接图标。图标缓存不计入原始附件和ZIP备份。

## 网络与限制

网页传输与DeepSeek请求使用不同客户端，不发送API Key、Cookie或浏览器登录信息，不运行JavaScript。仅抓取公开HTTPS地址，HTTP链接会尝试升级HTTPS；每次重定向和实际DNS结果均检查，拒绝本机、内网及降级跳转。限制跳转次数、读取大小、超时和并发，并支持取消。需要登录、反爬验证、仅动态渲染、正文不可读或不支持的文件下载链接可能失败，会保留人工保存流程。

解析使用jsoup 1.24.1，按其Android要求启用Google desugar_jdk_libs_nio 2.1.5。使用现有OkHttp 4.12.0，无新增第三方UI框架。

来源：[jsoup官方安装与Android要求](https://jsoup.org/download)、[jsoup选择器说明](https://jsoup.org/cookbook/extracting-data/selector-syntax)、[OkHttp Dns源码](https://github.com/square/okhttp/blob/parent-4.12.0/okhttp/src/main/kotlin/okhttp3/Dns.kt)、[Google Java API desugaring说明](https://developer.android.com/studio/write/java8-support#library-desugaring)、[HTML图标链接规范](https://html.spec.whatwg.org/multipage/links.html#rel-icon)。

## 验证与交付

130项本地测试全部通过，包含本地HTTPS正文提取、重定向、登录/动态页失败、正文与图标大小限制、取消、凭据隔离、内网目标拦截、只保留可读事实、生成标题不再次抓取、图标内存/磁盘/失败缓存、ICO解码、界面替换与既有功能回归。assembleDebug、lintDebug通过（0错误、24警告）。界面截图使用测试图标，不进入正式资料库或安装包。

2026-11-06已覆盖安装到RMX5010的com.collection，未启动应用或运行手机端测试，未调用真实付费AI接口。交付dist/Collection-1.0.0-debug.apk；此前版本另存dist/Collection-before-web-20261106.apk，数据库结构不变。
