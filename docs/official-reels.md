# 红果官方刷剧首页：接口与界面适配

本分支在原版 Kaixinguo 上独立添加刷剧首页，所有原有播放器和业务模块保持不变。

## 官方数据来源核查

- `kadaliao/openhongguo` 中 `internal/protocol/channel.go` 映射短剧 `bottom_tab_type=7`：
  - 推荐：请求 `tab_type=-1`，读取 `video_feed=16`；
  - 真人：请求 `real_person_feed=39`；
  - 漫剧：请求 `motion_comic_feed=36`。
- `sdk/catalog.go` 中 `Discover()` 处理 `bookmall/tab` 的首页与 `cell/change` 的续页，并为后续请求生成、校验游标。
- Android 绑定 `DiscoverOptions(channel=..., cursor=...)` 能直接调用这些接口；不应把 `shortRank(content="human")` 等榜单冒充官方刷剧推荐。
- 非官方 SDK 使用匿名设备，因此推荐内容不能保证与已登录官方 App 的个性化推荐完全相同。

参考资料：
- https://github.com/kadaliao/openhongguo/blob/main/internal/protocol/channel.go
- https://github.com/kadaliao/openhongguo/blob/main/sdk/catalog.go
- https://github.com/tsukiyo-zero/hongguoTV/blob/main/kotlin-tv/APP_RECOMMENDATION_REFERENCE.md

## 实现范围

- `ui/OfficialReelsScreen.kt`：顶部推荐/真人/漫剧，垂直滑切作品、播放、暂停、刷新、加载/失败反馈、收藏、进入详情/选集。
- 官方频道取自既有 `Catalog.discover()` 与 `Feed`；`model.home` 继续服务推荐和剧场的原推荐网格。
- `ui/TheatreScreen.kt`：保留原来的推荐列表、排行榜、新剧和搜索。
- `ui/App.kt` / `ui/Router.kt`：仅调整首页、导航和复用播放器 UI，不修改 `model/Player.kt`、`model/AppModel.kt`、`model/Catalog.kt`、`playback`、`cenc` 或 `MainActivity`。
- `PlayerScreen.VideoSurface` 和 `PlayerWindow` 改为 `internal`，仅用于 UI 复用。

## 需要真机验证

1. 推荐、真人、漫剧分类切换及按服务端游标连续刷剧；
2. 各频道选中位置、刷新、失败/重复返回处理；
3. 新短剧与剧集之间的音视频连续性、返回与画中画；
4. 进入详情/选集后返回首页，保留 Kaixinguo 的原有历史/收藏功能。

当前实现是“官方风格 UI + 官方匿名频道数据”，不宣称是官方登录账号同款个性化算法，也未实现官方曝光/点赞/评论上报。
