[![icon_android](https://github.com/gedoor/gedoor.github.io/blob/master/static/img/legado/icon_android.png)](https://play.google.com/store/apps/details?id=io.legado.play.release)
<a href="https://jb.gg/OpenSourceSupport" target="_blank">
<img width="24" height="24" src="https://resources.jetbrains.com/storage/products/company/brand/logos/jb_beam.svg?_gl=1*135yekd*_ga*OTY4Mjg4NDYzLjE2Mzk0NTE3MzQ.*_ga_9J976DJZ68*MTY2OTE2MzM5Ny4xMy4wLjE2NjkxNjMzOTcuNjAuMC4w&_ga=2.257292110.451256242.1669085120-968288463.1639451734" alt="idea"/>
</a>

<div align="center">
  
Legado 是一款免费的 Android 平台开源小说阅读器。
</div>

# KOReader 同步版本

本仓库基于 [LegadoTeam/legado](https://github.com/LegadoTeam/legado) 增加 KOReader KOSync 进度同步，并将改动合并到上游 `master` 的最新代码。应用使用独立包名 `com.legado.app.releaseA`，可以与 Legado 官方普通版同时安装。

本项目以官方原版 [legado_app_3.26101819_releaseA.apk](https://github.com/LegadoTeam/legado/releases/download/3.26101819/legado_app_3.26101819_releaseA.apk) 为基线进行修改和功能测试。

兼容性方面，已在安卓版 KOReader、Readest 和 Kindle 端 KOReader 完成 KOSync 进度同步测试。

详细使用方法见 [KOREADER_SYNC_README.md](KOREADER_SYNC_README.md)。可重复构建脚本位于 [scripts/koreader-sync](scripts/koreader-sync/README.md)；构建出的 APK 应作为 GitHub Release 资产发布，不提交到源码仓库。

## 相对原版的改动

| 方面 | 原版 Legado | 本仓库 |
| --- | --- | --- |
| 进度同步 | 使用 Legado 自身的书籍进度同步 | 增加 KOReader KOSync 协议，可与 KOReader 设备同步同一本本地书籍 |
| 文档匹配 | 以 Legado 书籍或书源记录为同步对象 | 支持 KOReader 的二进制文档 MD5 匹配，并可主动切换到文件名匹配 |
| 位置换算 | 使用 Legado 内部阅读进度 | EPUB 支持 CreNGINE XPointer、章节位置与全书百分比换算；PDF 支持页码和百分比恢复 |
| 自动同步 | 不包含 KOReader 防抖与冲突策略 | 支持按页数触发、空闲 10 秒后上传、25 秒接口防抖、离线队列和联网重试 |
| 冲突处理 | 不包含 KOReader 前后进度策略 | 可分别设置较新、较旧进度为静默、提示或不同步 |
| 手动操作 | 不包含 KOReader 上传与拉取入口 | 阅读菜单提供立即上传、立即拉取、账号与服务器配置，操作不依赖自动同步开关 |
| 安装方式 | 使用 Legado 原版包名 | 使用 `com.legado.app.releaseA` 共存包名，并同步调整 Firebase 客户端配置 |
| 可验证性 | 由上游测试覆盖 | 增加同步映射、策略和离线队列单元测试，以及补丁制可重复构建脚本 |

KOReader 与 Legado 的排版、定位模型不同，同步位置以全书百分比作为共同基准；两端需要使用内容完全一致的本地书籍文件。在线书源没有可匹配的原始文档，因此不参与 KOReader 进度同步。

本项目继续遵循原项目的 GPL-3.0 许可，原始功能、版权声明和第三方许可保持不变。KOReader 同步改动不是 Legado 官方上游功能，相关问题请提交到本仓库。

# Sponsor

> 感谢 [PiPio 中转站](https://pipio.io/offer/miaogongzi) 对项目的赞助支持。

# Grateful-感谢 [![](https://img.shields.io/badge/-Grateful-F5F5F5.svg)](#Grateful-感谢-)
> * org.jsoup:jsoup
> * cn.wanghaomiao:JsoupXpath
> * com.jayway.jsonpath:json-path
> * com.github.gedoor:rhino-android
> * com.squareup.okhttp3:okhttp
> * com.github.bumptech.glide:glide
> * org.nanohttpd:nanohttpd
> * org.nanohttpd:nanohttpd-websocket
> * cn.bingoogolapple:bga-qrcode-zxing
> * com.jaredrummler:colorpicker
> * org.apache.commons:commons-text
> * io.noties.markwon:core
> * io.noties.markwon:image-glide
> * com.hankcs:hanlp
> * com.positiondev.epublib:epublib-core
> * com.github.Moriafly:LyricViewX
> * io.github.rosemoe:editor
