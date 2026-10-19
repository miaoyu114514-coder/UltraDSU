# DSU 版本和多槽机制核实

结论：**Android 10 / API 29 引入 DSU；Android 11 / API 30 引入命名槽、枚举和选择接口；AOSP Android 15 / API 35 才允许内部存储使用默认目录下的独立槽目录。** Android 11 的命名接口不代表能在内部存储安装多个系统。应用此前只核实了接口，遗漏 gsid 路径策略；moto Android 14 的真实安装日志证实这会返回错误码 1。

## 一手证据

| 层次 | Android 10 | Android 11 |
| --- | --- | --- |
| DynamicSystemManager | `startInstallation(long systemSize, long userdataSize)` | `startInstallation(String dsuSlot)`，另有逐分区创建和结束安装接口 |
| gsid / IGsiService | `setGsiBootable(boolean oneShot)`；无槽枚举/命名选择 | `getInstalledDsuSlots()`、`getActiveDsuSlot()`、`enableGsi(boolean oneShot, String dsuSlot)` |
| 系统安装器 | system 大小和 userdata 大小 | 增加 `KEY_DSU_SLOT`，默认标识 `dsu` |
| 存储选择 | 一个默认安装 | DynamicSystemService 按 `dsuSlot` 构造 `/data/gsi/<slot>` 或可移动介质上的槽目录 |

固定 Android 发布标签的 AOSP 源码：

- [Android 10 DynamicSystemManager](https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-10.0.0_r1/core/java/android/os/image/DynamicSystemManager.java)
- [Android 11 DynamicSystemManager](https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-11.0.0_r1/core/java/android/os/image/DynamicSystemManager.java)
- [Android 10 IGsiService](https://android.googlesource.com/platform/system/gsid/+/refs/tags/android-10.0.0_r1/aidl/android/gsi/IGsiService.aidl)
- [Android 11 IGsiService](https://android.googlesource.com/platform/system/gsid/+/refs/tags/android-11.0.0_r1/aidl/android/gsi/IGsiService.aidl)
- [Android 11 DynamicSystemInstallationService](https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-11.0.0_r1/packages/DynamicSystemInstallationService/src/com/android/dynsystem/DynamicSystemInstallationService.java)
- [Android 11 DynamicSystemService](https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-11.0.0_r1/services/core/java/com/android/server/DynamicSystemService.java)
- [Android 11 gsi_tool.cpp](https://android.googlesource.com/platform/system/gsid/+/refs/tags/android-11.0.0_r1/gsi_tool.cpp)：`enable --dsuslot <name>` 的实现。

命名多槽接口的起始版本是 Android 11，内部存储多槽的原生路径支持从 Android 15 开始。Android 11–14 的 `GsiService::ValidateInstallParams` 对内部路径要求 `install_dir == kDefaultDsuImageFolder`，只有符合条件的外置介质可使用其他路径；Android 15 改为 `StartsWith(install_dir, kDefaultDsuImageFolder)`。对比 [Android 14 gsi_service.cpp](https://android.googlesource.com/platform/system/gsid/+/refs/heads/android14-release/gsi_service.cpp) 和 [Android 15 gsi_service.cpp](https://android.googlesource.com/platform/system/gsid/+/refs/tags/android-15.0.0_r1/gsi_service.cpp)。

应用不提供外置介质安装目标、也不替换系统 gsid。在 Android 11–14 上管理已有槽和准备镜像仍可用；新建内部安装仅允许没有已占用槽时使用默认 `dsu`，并使用精确的 `/data/gsi/dsu`。独立内部目录安装按 API 35+ 放行，仍可能受厂商实现限制。自定义 ROM 回移功能目前不自动启用；仅检测接口不能证明回移了路径策略。

0.2.2 增加一个经真机验证的例外：moto 原机 API 34 gsid 的两条路径比较指令由本地 Clang 编译后替换，保留全部原有依赖。SHA-256 为 `6bea182e737e238ce8c054717f48e64afc1c8a989aab53850bc9a564651bd323` 时，Root 引擎按实际文件哈希放行，不要求 API 35。其他未知二进制仍不会绕过版本限制。补丁以独立脚本临时 bind 挂载，APK 不包含自动替换逻辑。恢复原 gsid 后，已有独立内部槽的分区修改会在写维护标记前被拒绝，避免因路径不支持而把完整槽误标为维护中。

## 应用检测和降级

1. `minSdk=29`。Android 10 将标识锁定为 `dsu`，只允许一个配置，显示单槽提示；显示名称和 userdata 大小仍可设置。
2. 检查 `com.android.dynsystem.VerificationActivity` 是否存在。缺少时提示不支持系统安装流程；已核实的 Root 多槽接口可使用独立引擎。
3. 分开记录命名接口和内部多槽路径能力；Android 11–14 明确显示内部仅默认槽。对不支持的内部命名槽禁止提交 Root / ADB 安装，防止系统安装器先删除现有安装才失败。本地配置始终与实际安装状态分开。
4. 开启并通过 Root 授权后，反射系统的 `IGsiService`，检测命名枚举和选择接口，枚举真实槽。
5. AOSP 的槽枚举可能包含未完成安装：只有标准元数据中的 `complete=OK` 才记为已安装；名称占用检查则使用完整枚举，防止覆盖半成品。
6. 镜像准备完成、安装请求提交、系统已安装是不同状态。非 Root 无法验证系统安装结果；明确提示查看系统通知。
7. `su` 拒绝、缺失 gsid、Binder/SELinux 错误都会显示具体失败，不改安全策略。

Android 10 的 gsid 方法名仍是 `removeGsiInstall` / `disableGsiInstall`，Android 11 改为 `removeGsi` / `disableGsi`。桥接层按接口存在情况适配，未使用固定 Binder 编号。

## 为什么不能简单调用 gsi_tool wipe 删除任意槽

[AOSP Android 11 GsiService](https://android.googlesource.com/platform/system/gsid/+/refs/tags/android-11.0.0_r1/gsi_service.cpp) 中，`removeGsi()` 作用于当前选中槽，`RemoveGsiFiles()` 同时删除共享的安装状态/单次启动标记。简单“切换到目标 → wipe → 切回”可能导致其他槽无法重新启用。

UltraDSU 在主系统、无安装进行中时才允许删除：

- 最后一个槽：调用系统原生删除接口。
- 多个槽：通过 `openImageService("dsu/<target>")` 获取槽专属的 [IImageService](https://android.googlesource.com/platform/system/gsid/+/refs/tags/android-11.0.0_r1/aidl/android/gsi/IImageService.aidl)。预先验证 backing image 名称、未映射状态，以及 `/metadata/gsi/dsu/<target>` 下 `install_dir`、`complete` 的标准布局和真实路径。
- 删除当前选中槽时，先选中另一个完整槽，保留原来的启用/停用与单次/持续启动模式。删除其他槽不切换当前启动选择。
- 使用 `deleteBackingImage` 删除目标槽 backing images；确认列表为空，再仅删除该槽的 `complete` 和 `install_dir` 注册标记。不执行 `rm -rf`，不直接写镜像或分区，也不删除共享启动状态。
- 不支持的元数据布局、仍映射的镜像或部分失败会明确报错，保留本地记录用于诊断。删除当前槽后选中的槽会显示在刷新结果中。

厂商实现仍需真机验证。Runtime API 检测和模拟单元测试不能保证 GSI 的 vendor、AVB、文件系统兼容性。

## 系统安装器为何不能直接用来保留多系统

[Android 11 InstallationAsyncTask](https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-11.0.0_r1/packages/DynamicSystemInstallationService/src/com/android/dynsystem/InstallationAsyncTask.java) 的 `doInBackground()` 首先调用 `mDynSystem.remove()`，然后才执行 `startInstallation(mDsuSlot)`。因此，多槽接口存在，不代表系统提供的安装 UI 会保留前一个安装；只传 `KEY_DSU_SLOT` 不足以实现保留多系统。

UltraDSU 在 Root 引擎中绕过这个清理步骤。Android 15+ 验证目标名不存在，创建 `/data/gsi/dsu/<slot>`、设置 0755 权限并 restorecon，然后调用 `openInstall`；Android 11–14 仅允许默认槽并使用精确的 `/data/gsi/dsu`。已有未登记且非空的镜像目录会阻止覆盖。随后创建独立 userdata 与清单中的只读分区，通过 gzip 解包管道调用 `commitGsiChunkFromStream`，按运行时接口情况关闭分区并完成安装。早期 Android 11 需要通过 `enableGsi` 结束残留安装会话；新版系统在 `closeInstall` 中直接结束并置为停用。之后恢复旧槽选择和单次/持续模式，不自动重启。

写入失败时取消本次会话，使用槽专属镜像服务清理新槽；不调用共享 `removeGsi()` 清理其他槽。部分失败会留下可见的未完成状态，不宣称安装成功。无 Root 时仍可通过 ADB 使用系统安装器，但应用明确提示其清理当前 DSU 的行为，保留多系统需要 Root 内置安装。

## 槽内替换与 userdata 重建（0.2.0）

依据 [AOSP PartitionInstaller](https://android.googlesource.com/platform/system/gsid/+/refs/heads/main/partition_installer.cpp) 的 `Preallocate()`：`createPartition(name, size, readOnly)` 会在目标槽重建同名 backing image。因此替换使用目标槽 `install_dir` 记录打开现有安装，仅创建指定分区并流式写入；重建 userdata 仅创建可写的 userdata，不遍历删除其他分区。

编辑期间先写入该槽的 `ultradsu_maintenance.json` 待修复记录，停用该槽，并保留 `complete=OK` 注册以免 gsid 启动清理回收其他分区。失败后保留记录并禁止从应用启动此槽；成功后按完成目标清除记录。修改不是原子回滚；中断或损坏的目标镜像需要重新写入，其他镜像保留。

启动选择读取 `/metadata/gsi/dsu/active`，避免 `getActiveDsuSlot()` 返回最后安装上下文造成误判；单次启动标记使用 `/metadata/gsi/dsu/one_shot_boot`。新槽根目录依据 AOSP `file_paths.h` 固定在 `/data/gsi/dsu/` 下。查看实际容量时临时映射 backing image，使用 `blockdev --getsize64` 读取字节数后解除本次映射；仍映射的分区禁止修改。
