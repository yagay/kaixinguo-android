# 刷剧首页：数据依据与实现范围

## 接口依据

项目当前使用 `kadaliao/openhongguo` 0.2.1-kx.1 的 Android SDK。
其官方短剧书城频道（`bottom_tab_type=7`）为：

| UI | DiscoverOptions(channel=) | 请求/返回频道 |
| --- | --- | --- |
| 推荐 | `推荐` | 请求 -1，读取 video_feed=16 |
| 真人 | `真人剧` | real_person_feed=39 |
| 漫剧 | `漫剧` | motion_comic_feed=36 |

SDK 的 `Discover` 在内部请求书城首屏，并使用 `cell/change` 和返回的游标续页。此处不使用热门排行榜模拟信息流。

源码：
- https://github.com/kadaliao/openhongguo/blob/v0.2.1-kx.1/internal/protocol/channel.go
- https://github.com/kadaliao/openhongguo/blob/v0.2.1-kx.1/sdk/catalog.go

## 实现方式

新增独立「刷剧」导航入口；原有推荐、排行榜、新剧、我的、搜索五个导航及其页面、默认启动入口均保持原样。

刷剧页使用 Compose `VerticalPager`，固定单一 `VideoSurface`，滑动页面只操作封面和文案。
新剧开始使用 `Player.play(id,1)`；媒体解析、自动连播、历史、CENC、后台服务都沿用原项目。
详情、选集直接打开已有剧集页面；收藏用原 Favorites。
真人与漫剧分别维护独立分页及位置；推荐复用原有首页 Feed 和缓存。

## 边界

- 频道内容由第三方 SDK 匿名设备请求，无法保证与登录官方 App 的个人兴趣排序相同；
- 暂不展示缺少独立支持的官方点赞数量、评论数和分享数，不伪造这些数据；
- 仅做 UI 与 SDK 已有发现频道的适配，不修改 Core Player、CENC、PlaybackService、数据模型；
- 真机需要检查滑动切剧、首帧、竖屏比例、画中画与切换分类。

## App 已登录后的独立账号推荐（2026-11-12）

- 推荐频道在确认 App 业务账号后，优先请求账号会话的官方书城 `bookmall/tab/v1`（`tab_type=16`）并继续 `bookmall/cell/change/v1`，保留官方的会话游标。
- 账号推荐请求使用 App Passport 保存的账号 Cookie + 设备身份和 GPL 来源的完整请求签名。若平台返回特定签名拒绝码 110，可按红果 Android 上游策略尝试同一账号 Cookie 的未签名请求；不会使用匿名账号 Cookie 冒充登录态。
- 必须重新验证业务账号身份且获得有效账号推荐卡片才切换刷剧推荐源；失败时仍使用原来的匿名 SDK 推荐，刷剧顶部小字显示当前来源。
- 刷剧视频加载、CENC、播放器、Media3、预加载和后台播放仍沿用开心果原实现。账号请求与匿名缓存隔离。
- **服务器实际个性化排序仍需真机登录后对照官方 App 才能验收**；请求带账号会话不等于一定获得完全相同的排序，也不会模拟账号观看行为。
