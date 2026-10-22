# 2026-10-20 集成验收记录

基线：`0d5e74c1`（上一批修复），另合入 `test/remove-xinchao-bridge` 的 `4c21a58a`。工作分支：`codex/audio-workspace-search-20261019`，草稿 PR #6；不直接合并 master。

## 这轮修复

- 普通文件删除从全量附件修复改为按被删除文件名筛选相关消息行，再清除已删除附件的引用。保留所有文字及未选中附件；超大消息仍通过完整分片读取，数据库更新保持比较后写入。内存会话清理移到后台，在原会话锁内完成，删除页面增加进度提示。
- 文件清理图标改为长柄、束口、散开刷毛的矢量扫帚。
- 合入心潮跨客户端桥接的移除，阻止旧工具入口再调用桥接。独立外置记忆库和普通 MCP 服务保留。
- 合入逐消息额外信息快照，并将快照读取、持久化和历史比对放到 IO 线程。历史回复间隔提示不再在下一轮消失，消息模板的日期时间使用消息原始时间，避免每次重写历史前缀。撤销删除会话期间保留快照，真正释放会话时清理。
- Gemini 语音输入支持 Google 原生和 OpenAI Chat Completions。新录音为磁盘流式 WAV，最长五分钟；自动模式排除 embedding/image/tts/live 等不同用途模型。模型设置可选自动、直传、转写；Responses/其他不支持的传输走原 ASR。兼容服务仍可能自行禁用音频，此时可切换为转写。
- 工作区文件使用 `[文件](sandbox:/workspace/file.zip)` 保存到用户选择的位置，预检查文件存在性及路径边界，复制使用流而非聊天内 base64。旧回复的内联代码路径提供保存按钮。下载绑定点击时的工作区，切换会话不改变来源。
- 参考 RikkaHub 3.0.0（AGPL-3.0，commit `55a108a`）适配 Doubao、Serper、DeepSeek 搜索，保留本项目无需反射的配置工厂。其完整 UI/存储重构未搬入此次修复。
- 移除旧 Supabase 设备同步配置、上传和定时服务。**工作流的 ScreenTriggerFamily、TriggerRegistry、ContextProvider 屏幕条件保留**，并加入停用旧同步之后仍能收到亮屏/息屏广播的回归测试。独立 Supabase 外置记忆功能保留。

## 验证范围

新增或更新：屏幕广播和屏幕条件；指定附件删除与超大消息；额外信息跨轮/重启快照；回复间隔和模板时间前缀；原生 Google 与 OpenAI 音频载荷的 WAV 字节；WAV 头；工作区链接路径越界/编码；搜索配置序列化与豆包返回解析。

CI 运行 app、ai、search、workspace 单元测试，编译 Android 设备测试，运行 Image Atelier 回归，构建签名 arm64 APK 并检查内置 OCR。最终结果见 PR #6 和对应 Actions 记录。

未代替真机验证：手机录音权限与实际音质、第三方兼容商的音频支持、系统文件选择器交互、真实长对话删除耗时、Codex 服务端缓存命中率。服务端缓存有自身策略；请求前缀稳定并不保证每次命中。

## 2026-10-20 evening regression fixes

- Recorded voice now uses the same Audio attachment path as uploaded files; recording completion waits for Idle and does not reuse the previous path. Routing uses the conversation's assistant/model. Tool-only messages remain visible, including their saved results after a later generation failure.
- Every request adapts both legacy VoiceMessage and Audio parts to the current model and transport. Unsupported audio uses an existing transcript or an explicit missing-audio notice. This transformation does not mutate stored history; switching back to a capable model restores access to the original attachment. OpenAI-compatible transport accepts WAV/MP3; native Google retains supported M4A/OGG audio.
- Workspace downloads from file management, edited-file cards and chat links share a folder picker and streaming export. Existing names receive numbered suffixes. Creation, write, close and byte-count errors are reported; incomplete newly created documents are removed, and existing documents are never truncated.
- Backup navigation uses four fixed, compact tabs. A 24-hour picker below the interval defaults to 03:00. Periodic WorkManager requests use a next-schedule override, refreshed after completion; daily/multi-day schedules preserve the local clock through delayed execution and daylight saving changes. First activation uses the next clock occurrence. Unchanged schedules are preserved across process restarts so overdue work is not postponed; completing workers explicitly advance the next clock. The 6-hour option uses the chosen time as its anchor.
- Integrated icon PR #7 as a second parent of the repair branch, including launcher, monochrome, thinking and exported-chat icons. Master remains unchanged.

Validation: 18 changed Kotlin files parsed without syntax errors; 9 isolated backup-clock JVM tests passed. New Android regressions cover historical audio after model switching, transport-specific formats, duplicate document names, partial writes, output errors and a provider incorrectly returning an existing document. Full Android CI is required for the final delivery. No live provider keys or physical-phone recording/background scheduling checks are available in this environment.
