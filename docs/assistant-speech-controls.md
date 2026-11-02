# 逐句语音表达

聊天中的 `tts` 工具与语音电话共用同一条合成队列。模型可以分别给每句话写一个语音块：

```text
[[voice emotion=happy speed=1.1 pitch=1]]今天真开心(laughs)。[[/voice]]
[[voice emotion=calm speed=0.85 pitch=-1]]嗯，先歇一下<#0.4#>我们慢慢说。[[/voice]]
```

所有属性可省略；普通文本仍直接朗读，使用用户配置的声音、默认语速和情绪。聊天历史/电话字幕隐藏控制标记，保留原始表达供电话流式朗读和后续重播；修改或删除显示正文后不会继续使用过期表达。

当前 MiniMax HTTP 接口的已核实范围（2026-11-02）：

| 能力 | speech-2.8-hd / turbo | speech-2.6-hd / turbo | speech-01/02-hd / turbo |
| --- | --- | --- | --- |
| 语速、音高、音量 | 支持 | 支持 | 支持 |
| happy/sad/angry/fearful/disgusted/surprised/calm | 支持，亦可自动推断 | 支持 | 支持 |
| fluent / whisper | 不发送 | 支持 | 不发送 |
| 19 种英文括号语气词 | 支持 | 移除 | 移除 |
| `<#秒数#>` 停顿 | 支持 | 支持 | 支持 |

语速为 0.5–2，音高为 -12–12 的整数偏移，音量大于 0 且不超过 10。音高不是逐字控制汉字声调；整体情绪/语速/音高按单次合成生效，因此不同语音块分别合成。停顿为 0.01–99.99 秒、最多两位小数，必须位于可发音正文之间，不能连续；无效标记会移除。中文的嗯、啊、呀可以直接写进正文。

切换到其他供应商时，移除上述专属控制标记并朗读普通正文；未知 MiniMax 模型也不发送可选控制参数。2.8 不发送 whisper/fluent，2.6 和旧模型不接收 2.8 的英文语气标签。不保证第三方中转站与官方接口完全一致，服务端错误会明确上报，不会悄悄变成“播放成功”。这次没有执行付费合成或真机音质验收。

来源：[MiniMax 中文 HTTP API](https://platform.minimax.cn/docs/api-reference/speech-t2a-http)，含完整字段说明的 [OpenAPI Markdown](https://platform.minimax.cn/docs/api-reference/speech-t2a-http.md)。官方技能库旧简表将 2.8 概括为自动情绪；本实现按 HTTP OpenAPI 逐模型、逐取值限制。
