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
