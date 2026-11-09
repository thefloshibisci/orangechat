# 共度大厅与房间改版：设计交接

严槿 2026-11-04 晚确认了方向，交给隔壁实现。效果图在本目录：

- `hub-mockup.png`：大厅，左浅色、右深色。
- `room-mockup.png`：「一起逛」房间内的推荐页和消息页（B 款）。
- `hub.py`、`build.py`：生成效果图的脚本，可以查颜色和尺寸。

效果图里的小动物是手画占位，要换成正式插画（见下文「插画」）。

## 目标

共度以后会有多个「房间」：一起逛（小红书，已有）、一起读、一起听，之后还会继续加。所以入口改成两层：

1. **大厅：**左右滑动选房间，点中间的卡片进入。
2. **房间：**进入后是这个功能自己的界面。现有小红书页面整体变成「一起逛」房间。

## 一、大厅

布局见 `hub-mockup.png`。

- **顶部：**一行小字 `// GONGDU INDEX · OURS`（字距放宽），下面是大标题「共度」，再下面是副标题「每一张牌，通往一件一起做的事」和一条短横线。右上角显示「当前 / 总数」，例如 `02 / 04`。
- **卡片轮播：**用 `HorizontalPager` 实现 coverflow。当前卡片正对、全尺寸；相邻卡片缩到约 0.86、倾斜约 ±5°、透明度约 0.6；再往外的卡片约 0.8、±7°、0.35。根据 `pageOffset` 连续插值，滑动过程要顺。
- **卡面（约 236×392dp，圆角 14）：**纸色底，内侧有一圈 0.8dp 的细描边。左上角是 `No.I` 这样的罗马数字编号，右上角是 `SPECIMEN`。上半部分约 56% 是插画，下面一条细分隔线，接着是房间名（22sp，粗体）、英文小字（9.5sp，字距放宽），右下角是「轻点进入 →」。
- **底部：**页码指示点，当前页是宽 22dp 的胶囊，其他是小圆点；下面一行提示「左右滑动挑一间 · 轻点进入」。
- **房间列表：**
  - I · 一起逛 · LOOK · CHAT · SHARE · 小章鱼 → 进入现有小红书页面
  - II · 一起读 · READ · NOTE · TALK · 小鱼 → 还没做，点击后提示「这间还在筹备中」
  - III · 一起听 · LISTEN · LYRICS · MOOD · 水母 → 同上
  - IV · 下一间 · COMING SOON · 无插画（虚线圆圈＋问号），卡面颜色变灰，右下角写「筹备中」
- 房间做成数据驱动的列表，例如 `GongduRoom(id, numeral, name, subtitle, illustration, enabled)`，以后加房间只需加一项。
- 记住上次停在哪间：再次打开时 pager 停在上次的房间。

### 配色

| 用途 | 浅色 | 深色 |
|---|---|---|
| 背景 | `#EFECE6` | `#1C1B19` |
| 卡面 | `#FBFAF8` | `#2A2825` |
| 正文 | `#2B2927` | `#EDE6DA` |
| 次要文字 | `#6E675F` | `#BDB4A6` |
| 弱化文字 | `#9A938A` | `#8E867B` |
| 插画线条 | `#8C847A` | `#CFC6B8` |
| 细描边 | `#D8D2C8` | `#4A463F` |

大厅是有意做的固定纸感配色，不跟随动态取色。深色模式必须用右栏。

## 二、房间（B 款，跟随 app 主题）

布局见 `room-mockup.png`，以「一起逛」为例。房间内使用 MaterialTheme 的颜色，不用大厅的纸色。

- **顶部：**一行返回箭头＋房间名（如「一起逛」），下面一行小字。右上角放连接状态小签：已连接时绿点＋「已连接」，断开时变红色。把现在每个分页都重复的「一起看，也一起聊 / 服务已连接」删掉。
- **分页：**改成胶囊分段控件（推荐｜搜索｜消息｜设置），选中项是白色浮起的胶囊。不要用下划线 Tab。
- **推荐：**标题「一起逛逛」，右侧放一个「换一批」小按钮。下面是双列瀑布流（`LazyVerticalStaggeredGrid`），封面保持原比例，标题最多两行，底下放作者头像、名字和点赞数。卡片圆角 14，带轻阴影。摘要不要直接露出原文里的 `$` 等标记符号。
- **搜索：**搜索框内放放大镜图标，按回车即搜索；框下面放几个常用关键词小标签。
- **消息：**「新消息告诉当前聊天」做成单独的开关卡片。消息列表放进一张大卡片，每条包括头像、头像角上的类型徽章（关注＝主题色「＋」，点赞＝红心，评论＝橙色气泡）、「名字 + 动作」、内容摘要、右上角时间；未读消息右侧显示小红点。删掉「已处理或首次同步」这类字样。
- **设置：**最上面放一张账号卡片（头像、昵称、登录状态、扫码登录/退出）。下面是「让当前助手使用小红书」等开关。服务地址和访问令牌折叠进「高级设置」。

## 插画

三张线稿用 app 的原生生图工具生成（严槿接的是 gpt-image-2.5）。素材要求：

- 去掉米白底，导出成**透明背景**的线稿 PNG（或转成矢量）。
- 用 `ColorFilter` 给线条着色：浅色模式 `#8C847A`，深色模式 `#CFC6B8`。
- 原图是正方形，放进卡面插画区时等比缩放、居中。

提示词（三张共用同一套风格描述，只换主体）：

> Minimal botanical-style specimen line drawing of {主体}, drawn with a single thin fine-liner pen in light warm grey ink. Pure clean contour lines only, almost no shading, no fill, no texture, very airy and delicate, lots of white space. Off-white warm paper background, completely plain. Subject centered in a tall vertical 9:16 frame, occupying about 55% of the height. Elegant, calm, scientific illustration feeling. No text, no labels, no border, no other objects.

- 一起逛：`a small octopus with tentacles curling softly`（不要斑点）
- 一起读：`a small graceful fish swimming, with flowing fins and tail`（细鳞线）
- 一起听：`a jellyfish with a rounded bell and long soft trailing tentacles flowing downward`

注意：原生生图工具目前不能指定比例，设置里改成 9:16 也会出正方形图。这是另一个待修的 bug，不在这次范围内。

## 范围与约束

- 只改共度的 UI 层（`ui/pages/gongdu/`），以及大厅需要的少量导航调整。不改 `data/gongdu/` 下的服务、工具和同步逻辑。
- 现有导航入口（聊天附件菜单里的「共度」）改为打开大厅。
- 浅色、深色都要检查，窄屏（360dp 宽）不能横向溢出。
- 基于最新 `master` 单独开分支、提 PR，不要和其他未合并的工作混在一起。
- 拿不准的地方先在 PR 里问严槿，不要自己猜。
