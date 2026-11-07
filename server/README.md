# 拾页个人代理示例

此服务使 DeepSeek API Key 只留在服务器。Android App 配置代理地址和独立访问令牌，通过服务器转发文字及图片并获取标题。它是可运行的单令牌个人代理示例，未部署。

## 运行

需要 Node.js 22 或更高版本，无第三方运行依赖，无需执行 `npm install`。

1. 将 `.env.example` 复制为 `.env`。
2. 填写 `DEEPSEEK_API_KEY`。这个 Key 不应放进 Android 安装包、Git 或客户端设置。
3. 将 `CLIENT_TOKEN` 改为随机令牌，建议至少 32 字节随机数据。生成示例：

   ```text
   node -e "console.log(require('node:crypto').randomBytes(32).toString('hex'))"
   ```

4. 启动服务：

   ```text
   node --env-file=.env proxy.mjs
   ```

默认监听 `127.0.0.1:8787`。启动时缺少 Key、令牌或仍使用占位值会直接退出。允许的模型由 `ALLOWED_MODELS` 配置，多个名称以英文逗号分隔。

## 连接 Android App

服务器本身提供本机 HTTP。对外使用必须在前面部署提供有效 HTTPS 证书的反向代理（例如 Nginx 或 Caddy），将 `/chat/completions` 转发到 `http://127.0.0.1:8787/chat/completions`。反向代理应同步限制请求体不超过 12 MiB、设置连接/读取超时；不要记录 Authorization、请求正文和响应正文。不要将默认 HTTP 监听口直接暴露公网。

在 App「设置与备份」中：

- 开启「使用服务端代理」。
- 地址填写完整 HTTPS 地址，例如 `https://your-domain.example/chat/completions`。
- 「代理访问令牌」填写 `CLIENT_TOKEN`，不要填写服务器的 DeepSeek Key。
- 模型填写 `deepseek-flash`，或服务端明确允许的模型。

此示例仅适用于个人或受信任的小范围使用。面向公众发布时，应接入用户登录，为每位用户设置独立身份、额度和可撤销令牌；不要把这个共享访问令牌硬编码进安装包。

## 已有约束

- 仅接受经过 Bearer 令牌鉴权的 `POST /chat/completions`，令牌摘要使用固定长度恒定时间比较。
- 仅允许配置的模型；服务端固定官方 DeepSeek 上游地址，禁止上游重定向。
- 支持文字和 JPEG/PNG/GIF/WebP 的 Base64 视觉输入，不接收外部图片 URL 或任意文件路径。
- 请求正文最多 12 MiB，上游输出最多 2 MiB；最多 8 张图；生成上限 128 tokens。
- 同一客户端默认每分钟 12 次；全局仅一个上游请求同时执行，最多等待 4 个请求，排队最长 15 秒，上游最长 90 秒。
- 不信任调用者的 `X-Forwarded-For`；在反向代理后所有请求共用反向代理地址的额度，适用于此单令牌示例。
- 固定 JSON Output，只返回并校验非空 `title` 和完整结束标志。上游错误返回通用提示，不转发原始错误正文或推理内容。
- 不保存资料或密钥到日志；`.env` 已加入忽略列表。应由部署平台保护环境变量和运行账户。

## 测试

```text
node --test proxy.test.mjs
```

测试只请求本机服务，上游通过注入替身模拟，不调用真实 DeepSeek，不需要真实 API Key。
