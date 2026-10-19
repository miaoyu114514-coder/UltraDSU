# 构建与发布

## 版本规则

Alpha tag 使用 `v0.2.8-alpha.1`，APK 的 versionName 与 tag 去掉 `v` 后一致。
每次分发都递增 versionCode；Alpha、Beta、正式版共用同一套发布密钥，便于覆盖升级。

## 发布签名

复制根目录 `keystore.properties.example` 为 `keystore.properties`，配置本地密钥路径、别名与密码。
首次发布准备生成的 PKCS12 密钥位于 `.signing/ultradsu-release.p12`，别名为 `ultradsu`；密码保存在本地 `keystore.properties`。
这两个位置已被 Git 忽略。请把密钥和密码做独立备份；GitHub 源码和 Release 附件均不包含它们。

CI 也可使用环境变量 `ULTRADSU_STORE_FILE`、`ULTRADSU_STORE_PASSWORD`、`ULTRADSU_KEY_ALIAS`、`ULTRADSU_KEY_PASSWORD`。
缺少签名配置或密钥时，assembleRelease / bundleRelease 会失败，避免误发未签名 APK。

已有 Debug 安装使用调试签名，不能被这套发布签名直接覆盖。发布流程不会自动卸载手机上的 Debug 版本。
Alpha 后续版本继续使用同一套发布签名，即可按 Android 的签名和版本检查规则更新。

## 构建与检查

需要 JDK 17、Android SDK 35 和 Build Tools 35.0.0。

```powershell
.\gradlew.bat :app:assembleRelease :app:testReleaseUnitTest :app:lintRelease
```

macOS / Linux 使用 `./gradlew`。输出：`app/build/outputs/apk/release/app-release.apk`。
使用 SDK 的 apksigner 检查签名，确认 APK 不是 debuggable，并核对版本名、versionCode、最低 API 29。
仅对这次发布的 APK 生成 SHA256SUMS.txt；不要混入历史 Debug 包和机型专用模块的校验项。

## GitHub Release

先将源码提交推送到 main，再在该提交上创建版本 tag。
创建 Release 草稿，上传签名 APK 和 SHA256SUMS.txt，正文使用 `docs/releases/<tag>.md`。
核对附件和校验值后，以 **Pre-release** 发布。仓库可见性保持原设置；私有仓库的下载者需要访问权限。

本次应用 Alpha 附件不包含实验性 gsid 二进制和模块。`native/gsid-backport` 保存机型限定的源码工具及说明，APK 不替换系统 gsid。

## 用户反馈

反馈时附应用版本、机型、Android 版本、Root 管理器和错误信息；可通过仓库 Issues 提交。
先在可恢复的测试设备验证，具体场景见 `DEVICE_TESTING.md`。构建与单元测试不代表已经验证所有 ROM 或系统启动。
