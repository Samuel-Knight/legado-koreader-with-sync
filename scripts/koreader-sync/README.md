# Legado KOReader 同步最新版构建

该目录保存可重复的 KOReader 同步改造补丁和自动构建脚本。脚本不会修改当前工作区，而是在系统临时目录中建立干净工作树。

默认输出目录（相对于仓库根目录）：

```text
.\dist\koreader-sync版
```

在仓库根目录运行：

```powershell
powershell -NoProfile -ExecutionPolicy Bypass `
  -File '.\scripts\koreader-sync\Build-KoReaderSyncLatest.ps1'
```

脚本执行以下流程：

1. 从 `LegadoTeam/legado` 的 `releases/latest` 解析当前最新 Release 标签，不依赖 GitHub API 配额。
2. 从该 Release 资产页下载全部 APK，验证下载文件非空。
3. 获取同一标签的源码，在临时 Git 工作树中应用 `koreader-sync.patch`。
4. 将应用版本和输出名统一为 `<Release标签>-koreader-sync`。
5. 从 Release 标签数字生成稳定、单调递增的 Android `versionCode`，确保新版本可以覆盖旧版自定义包。
6. 运行 KOReader 相关单元测试、Release 构建、包名/版本名/入口 Activity、同步资源及签名校验。
7. 只把最终 APK 写入 `dist\koreader-sync版`，结束后删除下载文件和临时构建工作树。

最终文件名格式：

```text
legado_app_<Release标签>-koreader-sync_releaseA.apk
```

可用参数：

- `-OutputDirectory <目录>`：覆盖默认输出目录。
- `-SkipTests`：跳过 KOReader 单元测试；发布时不应使用。
- `-KeepWorkspace`：保留临时源码、下载包和构建目录，便于排查失败。

构建依赖 JDK 21、Android SDK 和 Git。默认使用 `%USERPROFILE%\.android\debug.keystore` 为 APK 配置 V1/V2/V3 签名；如需正式签名，可设置 `RELEASE_STORE_FILE`、`RELEASE_STORE_PASSWORD`、`RELEASE_KEY_ALIAS`、`RELEASE_KEY_PASSWORD` 四个环境变量。安装包使用独立包名 `com.legado.app.releaseA`，可与官方普通版共存。
