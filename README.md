# UltraDSU

基于 Android DSU（Dynamic System Updates）的原生 Android 应用。最低 Android 10 / API 29，Kotlin + Jetpack Compose。

当前预发布版本：**0.2.8-alpha.2**。[下载 Release](https://github.com/miaoyu114514-coder/UltraDSU/releases) · [Alpha 说明](docs/releases/v0.2.8-alpha.2.md)。私有仓库的下载需要仓库访问权限。

## 已实现

- 设置中即时切换 **miuix**（真实 miuix 组件库）与 **Material Design 3**，跟随系统深浅色。
- 设置 → 外观可独立开启并保存液态玻璃底栏。参考 BakaSU 的紧凑居中胶囊：64dp 高、每项 76dp，轻模糊与透镜折射，按住放大、拖动预览、松手切页及弹性回弹。Android 13+ 使用 AGSL 折射/色散，Android 12 仅模糊，Android 10/11 使用磨砂回退；两种 UI 均可使用。页首复用启动器图标的前景、背景与莫奈配色。
- 沿用 DSU Sideloader 的手机/下载箭头图标，分离自适应图标前景和背景。Android 12+ 使用系统莫奈色，Android 13+ 支持启动器主题图标；Android 10/11 回退白底黑图。
- 分别配置每个槽的 DSU 标识、本地显示名称、userdata 大小（GiB）；持久保存多个配置。
- 导入 SAF 文件选择器中的 raw ext4 / EROFS 镜像，以及 ZIP、7z、TAR、GZ、XZ、BZ2 压缩格式（包括 tar.gz / tar.xz / tar.bz2）。流式检查、解包和重新打包，准确统计每个分区大小。
- 单镜像包默认映射到 `system`；多镜像包按 `system.img`、`product.img`、`system_ext.img` 等文件名识别分区，写入前展示清单。不把多分区包误拆成多个 DSU 槽。
- Root 槽内镜像管理：查看真实分区/容量，为指定分区选择替换文件，或导入多分区包统一写入。替换只重建选定分区，保留该槽其余分区和 userdata。
- 槽卡片统一使用“启动 / 编辑 / 删除”；“编辑”进入独立二级页面，集中提供基本设置、实际分区、镜像替换和 userdata 重建扩容。无 Root 仅展示基本配置，草稿可调整 userdata 预设大小。
- 底栏切换时页面按方向横向过渡；编辑页从右侧推入，返回时向右退出并带背景视差。各选项卡保留独立滚动位置，返回编辑前的列表位置；两种 UI 和普通/玻璃底栏共用过渡。
- Root 重建 userdata 扩容：读取现有实际容量，校验新容量更大，要求输入槽标识确认清除数据；只重建 userdata，保留其他分区。这不是无损扩容。
- 在前台服务中准备镜像，提供通知、处理量、取消、唤醒锁、剩余空间检查及进程终止后的临时文件清理。
- Android 15+ Root 模式使用独立安装引擎：通过 gsid 的命名槽、分区创建与流式写入接口安装，保留已有槽，完成后恢复原来的启动选择和模式。Android 11–14 仅允许内部默认槽安装，已有槽仍可管理。不硬编码 Binder 编号。
- 对已验证的 moto Android 14 回移补丁按 gsid SHA-256 放行内部多槽。独立的编译、临时挂载和回退工具见 [native/gsid-backport](native/gsid-backport/README.md)；APK 不自动替换系统组件。
- Android 10 / 无 Root 使用系统 `com.android.dynsystem.VerificationActivity` 安装，传递大小参数，并在 Android 11+ 传递 `KEY_DSU_SLOT`。
- 无 Root：生成可复制的 ADB 安装命令，显式授予系统安装器读取准备镜像的 URI 权限。**系统安装器会清理当前 DSU，因此无 Root 的系统安装流程不保证保留其他槽；保留多系统请使用 Root 内置安装。**
- Root 开关持久保存，应用或设备重启后保持开启并重新检查 `su` 授权。只有当前授权成功才显示特权操作；授权失效保留开关选择并提示重试，不把历史授权当作当前权限。
- Root 扫描实际槽；只有完整槽的 `complete=OK` 才认定安装成功，未完成但占用名称的槽也会阻止覆盖。无 Root 的安装请求只显示“已提交”，不假装已安装。
- 主系统空闲时 Root 刷新会查询已有槽的实际 userdata 容量，更新导入配置的未知占位值；DSU 运行中或安装中不为此映射镜像。当前容量字段使用整数 GiB，非整数容量仍可在分区清单查看精确大小。
- Android 10 使用唯一默认标识 `dsu`，提示不支持命名多槽；Android 11+ 结合运行时接口检测。缺少系统 DSU 安装器时提示；已核实的 Root 多槽接口仍可使用内置安装。

## DSU 多槽从哪个版本开始？

**命名接口从 AOSP Android 11 / API 30 开始；内部存储的独立槽路径从 Android 15 / API 35 开始。** Android 10 首次引入 DSU。Android 11–14 不能仅凭命名接口和 Root 权限在内部存储新增独立槽；外置介质路径另有要求，本应用尚未提供外置安装目标。这描述的是 DSU 槽，与 Bootloader 的 A/B 槽不同。

逐版本源码对比与厂商适配限制见 [docs/DSU_COMPATIBILITY.md](docs/DSU_COMPATIBILITY.md)。设备仍需支持动态分区和 DSU；第三方 GSI 的启动通常需要解锁 Bootloader 并满足 AVB、架构和 vendor 兼容要求。

## 使用

1. 安装 APK，在“系统槽”中新建配置，选择兼容的 GSI。
2. 填写槽标识、显示名称和 userdata GiB，点击“准备镜像”。安装时还需 system + userdata 空间，系统有自己的额外预留要求。
3. 无 Root：复制两行命令到配置好 ADB 的电脑，执行后在手机上确认。首次连接电脑需开启 USB 调试并授权。命令以 POSIX shell 的引号规则生成，建议在 Linux / macOS / WSL / Git Bash 中执行。
4. 有 Root：设置中开启 Root，Android 15+ 点击“安装到此槽”并确认，应用将直接安装独立槽，进度在本应用和前台通知中显示。Android 11–14 仅允许无已有占用槽时安装默认 dsu；Android 10 仍使用系统安装器。
5. 系统安装器的进度查看系统 DSU 通知；Root 内置安装的进度查看 UltraDSU。完成后刷新，再选择单次或持续启动。无 Root 使用系统通知启动。
6. 点击槽卡片“编辑”进入二级页面，保存名称等基本设置；Root 模式的已安装槽还可查看分区、导入替换镜像，核对待写入清单后确认替换。
7. 扩容时在编辑页输入新的 userdata GiB 容量和槽标识，再确认重建。此操作清除该槽 userdata 的所有应用、账号、文件与设置，其他分区保留。请自行备份需要的数据。

无 Root 无法核实其他工具创建的槽，系统安装器可能清理当前 DSU，执行 ADB 命令前请备份已有 DSU 数据。准备文件应保留到安装完成。“删除”会回收对应准备文件和 URI 授权；Root 模式还会删除实际系统槽与其数据。失败留下的未完成槽会单独标记，可在 Root 模式清理。

## 构建与验证

需要 JDK 17、Android SDK 35、Build Tools 35.0.0。Gradle Wrapper 固定 8.11.1，AGP 8.10.1、Kotlin 2.2.10、miuix 0.5.1。依赖固定版本以稳定实验性 miuix API，并匹配 miuix 工件的 Kotlin 版本。

在 `local.properties` 设置 `sdk.dir`，或设置 `ANDROID_HOME`，然后执行：

```powershell
.\gradlew.bat :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
```

macOS / Linux：

```sh
chmod +x gradlew
./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
```

调试输出：`app/build/outputs/apk/debug/app-debug.apk`。签名 Release 的配置、构建和发布步骤见 [docs/RELEASING.md](docs/RELEASING.md)；签名密钥与密码保留在本地，不提交仓库。

测试覆盖 SDK/槽规则、命令参数、ZIP / 7z / TAR / XZ / BZ2 真实解包、单镜像/多分区映射、路径与大小限制，以及模拟 gsid 的多分区安装、单分区隔离替换、userdata 扩容、失败修复和启动模式保留。另有仅处理 app-cache 文件的 Android 设备 ZIP / 7z 测试，不调用 Root 或修改 DSU。模拟测试不能证明具体设备能启动 GSI。真机验收步骤见 [docs/DEVICE_TESTING.md](docs/DEVICE_TESTING.md)。

## 实现边界

- 系统安装流程依赖 ROM 自带 DSU 安装器。Android 11+ Root 内置流程依赖系统 gsid，不包含定制 gsid、Shizuku 或安全策略补丁。
- Root 内置安装的分区分配阶段显示阶段文字，写入阶段显示已写入 MiB。系统安装流程的进度由系统通知负责。
- 压缩包仅处理 `.img`、`.img.gz`、`.img.xz`、`.img.bz2` 分区镜像。支持 system、system_ext、product、vendor、odm 及 system/vendor/odm_dlkm。未知或重复分区名会拒绝；不刷 boot、vbmeta、super 或整机固件。Android sparse 请先用 `simg2img` 转 raw。
- 不支持加密包、分卷包或 RAR。Android 上的 7z 使用 libarchive / liblzma 原生解码；XZ 流与 JVM 7z 解码的字典内存上限为 128 MiB，原生 7z 的内存消耗由压缩包字典决定。源文件先复制到应用缓存以便 ZIP / 7z 随机读取，需为复制、准备及安装保留空间。
- 准备阶段使用 gzip 不压缩封装，保留系统安装器兼容性并免去重复压缩；多分区安装包也不再次压缩。复制与解包显示 MiB/s。准备文件占用接近解压后的原始镜像大小。
- 命名槽接口从 Android 11 开始；原生内部存储多槽路径从 Android 15 开始。Android 11–14 的 stock gsid 仅接受内部默认目录，本应用会阻止新增内部命名槽，保留已有槽管理；Root 权限本身不能绕过该限制，应用不替换系统 gsid。
- 无 Root 的本地配置不等于系统真实状态。Root 分区容量通过 gsid 临时映射并用 blockdev 读取，之后解除本次映射；无法读取时显示未知，不猜测。无法确认实际 userdata 容量时拒绝扩容。
- 替换失败会保留其余分区，并记录待修复目标、阻止该槽启动；重新写入对应目标成功后解除。被选分区的替换不提供自动回滚，准备镜像与最终确认发生在重建前。
- Root 通过 `app_process` 加载当前 APK，在 UID 0 中反射设备自带 AIDL Proxy，不硬编码 Binder transaction 编号。SELinux/厂商接口限制会返回错误，不会自动改 SELinux 或关闭 AVB。
- Android 11 的 `removeGsi()` 会删除共享启动状态。多槽删除改用槽专属 `IImageService` 删除 backing images，并清除该槽两个注册标记；仅支持标准 AOSP 元数据布局。最后一个槽使用系统原生删除接口。细节见兼容性文档。
- 本地记录保留完整读写错误；操作日志只保留当前进程最近 100 条，不含 su 凭证。

## 项目维护者

<table>
  <tr>
    <td align="center"><img src="app/src/main/res/drawable-nodpi/maintainer_fish_neko.png" width="120" height="120" alt="Fish_Neko 的头像"><br><b>Fish_Neko</b></td>
    <td align="center"><img src="app/src/main/res/drawable-nodpi/maintainer_gpt_sol.png" width="120" height="120" alt="GPT-6.1 Sol 的头像"><br><b>GPT-6.1 Sol</b></td>
  </tr>
</table>

## 参考与许可

功能流程参考 [VegaBobo/DSU-Sideloader](https://github.com/VegaBobo/DSU-Sideloader)（Apache-2.0），尤其是系统安装器交接与 userdata 自定义。应用实现独立编写，未集成该项目的定制 gsid 或 Magisk 模块。

参见 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)。本项目使用 Apache-2.0 许可证。
