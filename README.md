# KeyTalk

KeyTalk 是一个 Android 原生 AI 对话客户端，面向希望自己配置模型服务的用户。

你可以在 App 内配置自己的 API Key、Base URL 和模型名称，直接连接 OpenAI-Compatible、Claude、Gemini、Grok 等模型服务。KeyTalk 不提供官方账号、不做模型代理、不做云同步，聊天数据和密钥尽量保存在本机。

---

## 主要功能

- 多模型聊天客户端
- 用户自带 API Key（BYOK）
- 支持 OpenAI-Compatible 接口
- 支持 Claude Native Adapter
- 支持 Gemini Native Adapter
- 支持 Grok / xAI Adapter
- 支持 DeepSeek、Qwen、GLM、豆包等兼容 OpenAI 接口的平台或中转站
- 支持流式输出和非流式输出
- 支持图片对话
- 支持上下文占用显示
- 支持 1M 上下文开关
- 支持推理强度配置
- 支持模型列表获取
- 支持会话列表、新建会话、继续会话
- 支持回收站
- 支持本地加密聊天记录
- 支持 API Key 安全存储
- 支持加密备份导出和导入

---

## 适用场景

KeyTalk 适合这些用户：

- 想在手机上使用多个 AI 模型服务
- 想自己管理 API Key
- 想连接自建服务、第三方中转站或 OpenAI-Compatible 服务
- 不想把聊天记录交给额外的 App 后端同步
- 想要一个轻量、直接、原生的 Android AI 聊天客户端

---

## 支持的平台

当前版本：

```text
Android 8.0 及以上
API Level 26+
```

---

## 支持的接口类型

| 类型 | 说明 |
|---|---|
| OpenAI-Compatible | OpenAI、DeepSeek、OpenRouter、中转站、自建兼容服务等 |
| Claude Native | Anthropic Claude 原生接口 |
| Gemini Native | Google Gemini 原生接口 |
| Grok Native | xAI / Grok 接口 |
| Custom | 自定义兼容接口 |

如果服务商提供 OpenAI-Compatible 接口，通常选择 `OpenAI-Compatible` 即可。

---

## 数据与隐私

KeyTalk 的设计目标是本地优先、用户自控。

- 不提供官方模型账号
- 不提供官方模型代理
- 不提供云同步
- 不读取通讯录、短信、定位等隐私数据
- API Key 保存在本机安全存储中
- 聊天记录保存在本机加密数据库中
- 备份文件需要用户设置密码加密导出

注意：当你使用第三方模型服务时，你发送的对话内容会被发送到对应模型服务商。请自行确认服务商的隐私政策和使用条款。

---

## 安装 APK

可以在 GitHub Releases 下载，或直接下载仓库中的：

```text
release/KeyTalk-0.1.0.apk
```

下载后在 Android 手机上打开安装即可。

如系统提示“未知来源应用”，请按系统提示允许安装。

---

## 从源码构建

Windows：

```powershell
cd android
.\gradlew.bat :app:assembleRelease
```

macOS / Linux：

```bash
cd android
./gradlew :app:assembleRelease
```

构建产物默认位于：

```text
app/build/outputs/apk/release/
```

---

## 版本

当前版本：

```text
0.1.0
```

建议 tag：

```text
0.1.0
```

---

## 许可证

本项目为“源码可见”（source-available）项目，不是开源项目。

你可以查看、阅读、克隆和构建本源码，用于个人、学习、评估和非商业自用目的。

未经版权持有人明确书面许可，不得：

- 修改后收费
- 重新打包后收费
- 基于本项目提供收费服务
- 在官方贡献流程之外发布修改版或衍生版本
- 冒用 KeyTalk 名称、Logo、图标或品牌

完整条款见：

```text
LICENSE
```

