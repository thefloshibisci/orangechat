# 共度大厅字体

共度大厅使用书页式排字：主标题、四个房间名称和英文标签使用常规字重的
Gongdu Book；说明、操作提示也使用同一常规宋体。大厅保留插图、边框和交互。
浅色使用暖墨色，深色使用浅米色，并分别检查文字对比度。

一起逛采用独立浅米灰纸背景、米色与灰棕阴影的复古拟物材质：搜索框和导航轨道内凹，当前标签、关键词和卡片有
左上柔和亮投影、右下暗投影及细斜边；操作按钮按下时变成内凹。保留至少48dp导航触控区域及Tab选中语义。
固定标题使用宋体；其他文字（含动态笔记）用随包的完整思源黑体SC Regular，所有
字号层级统一常规字重且不合成粗体。字体缩放继续遵循系统设置，不修改全局主题。
实机截图证实系统黑体明显粗于本地预览；具体OEM字体/系统字重设置未能在本地确认。

内页使用用户2026-11-07提供的复古背景图，离线转为低饱和浅米灰，底色匹配大厅
#F5EEE9；保留轻淡花边、建筑和植物线条。深色版以#2F2B27重映射，不在页面上叠白膜。
两张drawable-nodpi WebP按Crop显示，内容装饰无可访问文本；原图不修改。
这是小红书自己的不透明背景，主壁纸与surface透明度设置不影响它；通用SettingsBackground
保持既有行为。材质沿用用户HTML的双向柔影/内凹输入/按下状态，控件同步去黄。
搜索空状态删除“输入关键词，找一篇想聊的笔记”，无空提示时不绘制额外占位。
图像来源及处理参数见gongdu-paper-background.json；用户原图SHA256留作来源校验。
大厅操作提示共享“轻点进入 →”实测宽度，“筹备中”起点取该布局中“点”的字框位置。

## 字体来源与许可

- 上游：Adobe Source Han Serif（思源宋体），SC Regular。
- 来源：https://github.com/adobe-fonts/source-han-serif
- 下载：https://raw.githubusercontent.com/adobe-fonts/source-han-serif/release/OTF/SimplifiedChinese/SourceHanSerifSC-Regular.otf
- 许可：SIL Open Font License 1.1，完整原文随应用打包在
  `app/src/main/assets/licenses/source-han-serif-OFL.txt`。
- 本地资源：`app/src/main/res/font/gongdu_book_regular.otf`。
- 本次上游文件 SHA256：`78aa7a328fd974df2d688c8a9fd74a33d8334dfa84ab24d9d11efb2ffc464117`。
- 字体子集 SHA256：`ad861b030910fb4fd78ae9c931fe6ad65d492feda5b7668f1c1018785184cd42`。
- 子集大小 44,544 字节，133 个字符；使用 fonttools 4.68.0 生成。

这是重命名为 Gongdu Book 的修改子集，保留上游版权和许可信息。只包含当前大厅固定文案、
固定推荐标题、可打印 ASCII 和间隔点；不用于用户内容。应用不需要联网加载字体。
不能依赖 Android 的通用 `serif`，因为中文字符可能回退为系统黑体。

## 更新标题时重新生成

安装 Python `fonttools`，下载上游 SC Regular OTF，然后执行：

```shell
python scripts/subset_gongdu_font.py /path/to/SourceHanSerifSC-Regular.otf
```

脚本从 `GongduHub.kt` 提取房间名称，并验证所有需要的字符均已包含。修改标题后
必须重新生成；自定义运行时房间名不在这个有限字库的保障范围内，会使用系统回退字体。
再运行 `GongduHubUiTest` 并检查 `app/build/reports/tests/gongdu-screenshots/`。

## 内页常规黑体

- 上游原版 Source Han Sans SC Regular（未修改字库或内部名称），Adobe SIL OFL 1.1。
- 下载：https://raw.githubusercontent.com/adobe-fonts/source-han-sans/release/OTF/SimplifiedChinese/SourceHanSansSC-Regular.otf
- 资源：`app/src/main/res/font/gongdu_sans_regular.otf`；许可：`app/src/main/assets/licenses/source-han-sans-OFL.txt`。
- SHA256：`f1d8611151880c6c336aabeac4640ef434fa13cbfbf1ffe82d0a71b2a5637256`。
- 完整字库用于动态笔记及作者名称，不用固定UI子集冒充全文覆盖；上游未收录字符仍由系统回退。

## 紧凑控件与授权反馈

按钮可见面默认32dp，外部布局保留48dp槽；文字放大时允许自然长高。导航选中面32dp，
槽体40dp，整体留48dp。搜索输入框保持原高度，避免挤压输入文字。
“让当前助手使用小红书”授权开关保留，切换采用独立assistantUpdating状态防重复提交，
不触发整页busy进度条；失败仍反馈错误。搜索、连接等长操作继续有加载反馈。
