# 本次构建结果

验证日期：2026-10-19。

- `:app:assembleDebug`：成功。
- `:app:testDebugUnitTest`：22 项测试，0 失败、0 错误；其中槽规则/命令测试 6 项，Root 桥接管理与安装流程测试 16 项。
- `:app:lintDebug`：0 错误，12 项警告，主要涉及实现 DSU 所需的隐藏 API、固定依赖版本更新提示及 Compose/存储 API 建议。
- `apksigner verify --verbose`：通过，APK v2 签名有效，1 个签名者。
- APK manifest：`io.github.ultradsu`，版本 `0.1.0` / code `1`，最低 API 29，目标 API 35。
- 工具：JDK 17、Gradle 8.11.1、AGP 8.10.1、Kotlin 2.2.10、SDK/Build Tools 35。

交付文件：`dist/UltraDSU-0.1.0-debug.apk`，60,163,458 字节。

SHA-256：

```text
09f773c0366368cc19483184873a2de5997a5729b2676d5785406a44b91701b3
```

这是使用开发签名的可安装 Debug 构建，尚未做正式分发签名。未在连接的手机上安装应用、申请 Root、安装/删除 GSI 或执行重启。模拟 gsid 测试验证了控制流程与槽隔离，不能代替具体设备上的 DSU 启动和厂商兼容性验收。验收步骤见 [DEVICE_TESTING.md](DEVICE_TESTING.md)。

## 0.1.1 图标更新

- 沿用 DSU Sideloader / WSTxda 的矢量图形；已逐字核对 `pathData` 与上游一致。
- 图标前景/背景分别保存；Android 10/11 白底黑图，Android 12+ 使用系统调色板，Android 13+ 提供 monochrome 图层。圆形和圆角方形裁切已渲染检查。
- `assembleDebug`、`lintDebug` 成功，lint 0 错误、17 警告。未为资源外观修改增加或重复业务单元测试。
- 已覆盖安装到 moto razr 2022（XT2251-1），设备确认版本 0.1.1 / code 2，应用启动成功。读取到该设备系统色为背景 `#D5E3FF`、前景 `#004787`。未操作 DSU 安装、槽位或重启。
- 安装包：`dist/UltraDSU-0.1.1-debug.apk`，60,167,648 字节，APK v2 签名验证通过。
- SHA-256：`136af5639434d1a2b60050306edce1089bad8f472e0403b1724856d8b49c2220`。

## 0.2.0 压缩包与槽内镜像管理

- `assembleDebug`、`assembleDebugAndroidTest`、`testDebugUnitTest`、`lintDebug` 均成功。
- JVM 测试 46 项：槽规则 6 项，真实压缩格式准备与映射 14 项，模拟 gsid 的槽/分区管理 26 项；0 失败、0 错误。
- moto razr 2022 上 Android 仪器测试 2 项通过：ZIP 多分区准备与系统安装器 ZIP 打包；7z 解码与指定 system_ext 单分区映射。测试仅在应用缓存创建临时文件并清理，不调用 su、gsid、分区写入或重启。
- lint：0 错误，25 警告，含隐藏 API、固定依赖更新和样式/API 建议。Root 实际分区替换、userdata 重建和 GSI 启动仍需用户在测试设备上验收。
- Root 开关持久化，跨进程/设备重启重新检查授权；RootRequested 与实际授权状态分开记录。
- 校正了 AOSP 多槽镜像根目录 `/data/gsi/dsu/<slot>`，启动选择读取 `/metadata/gsi/dsu/active`，单次启动标记读取 `/metadata/gsi/dsu/one_shot_boot`。
- 安装包：`dist/UltraDSU-0.2.0-debug.apk`，63,636,540 字节；版本 code 3，最低 API 29，目标 API 35；APK v2 签名有效。
- SHA-256：`a9edf48444acbef65f116c99a9d40bec99b5ff92e8054afc3bcc7cc360e473f9`。
- 已覆盖安装到 moto razr 2022 并启动；未执行实际 DSU 安装/替换/删除/重建或设备重启。仪器测试 APK 已移除。

## 0.2.1 准备速度与安装目录修复

- 首次真机失败日志：`gsid: realpath failed: /data/gsi/dsu/los: No such file or directory`。补建目录后，用户重试日志变为 `cannot install DSU to /data/gsi/dsu/los/`，证明还存在路径策略限制。对比 Android 14 和 Android 15 首个发布标签，确认内部命名槽路径在 API 35 放开。已纠正“接口存在就支持内部多槽”的判断；API 30–34 在内部仅允许默认 dsu，其他命名槽安装被阻止。API 35+ 创建独立目录、0755、restorecon 后打开安装，并保护已有未登记数据。
- 7z 改用 libarchive-android 1.1.6 / liblzma 原生解码；准备和系统安装器打包均取消重复压缩，以标准不压缩 gzip / ZIP 保持交接兼容。复制与解包显示 MiB/s。
- moto razr 2022 对用户选中的同一 7z 前 64 MiB 进行只读测试：Java 7,629 ms，原生 958 ms，SHA-256 一致。另一轮分别为 6,850 / 961 ms。此比例仅代表该采样，不代表完整准备全过程。
- 同一源文件完整原生准备：1,737,281,536 字节（1,656 MiB），44,659 ms；不包含读取源文件到缓存的时间。输出仅写入独立测试缓存，完成后清理；现有槽和准备镜像未修改。
- 32 MiB 合成文件的默认 gzip 准备为 650 ms，不压缩封装为 32 ms，并核对解包后的 SHA-256；该测试仅衡量重复压缩开销。
- JVM 测试 51 项通过（含 Android 14 命名路径拒绝和默认目录选择）；Android 文件测试 5 项通过（多分区 ZIP、原生单/多镜像 7z、路径拒绝、gzip 封装和只读性能采样），另单独执行上述完整源文件准备成功。Root 真实写入与系统启动仍由用户验收。
- 安装失败保留准备文件；失败后的配置可重试，存活的系统半成品仍按真实状态标为 INCOMPLETE。报错包括安装步骤，服务异常写入 logcat。
- `assembleDebug`、`assembleDebugAndroidTest`、51 项 JVM 测试、lint 全部通过；lint 0 错误、26 警告。APK v2 签名校验通过。
- 已覆盖安装并启动 moto razr 2022：版本 0.2.1 / code 4，保留原准备文件与设置；仪器测试 APK 已移除。未执行 DSU 写入、删除、扩容或重启。
- 最终安装包：`dist/UltraDSU-0.2.1-debug.apk`，76,600,704 字节。SHA-256：`4e0777a35e187107cce1edb4e504fe89341d72231eee01968e2872529974f854`。

## 0.2.2 moto Android 14 gsid 回移测试

- 以原机 SHA `a7360f6b305d6e1f7b3a51ee08146d3e5a4d5769c7cb11c3de38c734d6e8bcf3` 为基底，通过 WSL AOSP Clang 编译两条 AArch64 指令，替换精确匹配的路径长度与 compare 长度指令；并非完整 AOSP 重编译。产物 875,632 字节，SHA `6bea182e737e238ce8c054717f48e64afc1c8a989aab53850bc9a564651bd323`；仅四字节不同，无新增导入符号。
- moto linker64 的依赖解析通过。临时文件 bind 在 PID 1 挂载命名空间生效；使用只读文件 remount 清除继承的 nosuid，恢复 init 正常域切换，不修改 SELinux 策略，getenforce 始终为 Enforcing。原文件未改写，卸载挂载即可回退；回退及再次启用均经过验证。
- Root 状态报告 Android 14 + `internalMultiSlot=true` + `tested_moto_backport`。应用按上述二进制哈希认定已验证补丁，未知文件不放行。
- 显式参数启用的设备测试使用用户原有准备文件，为 `los` 新建 16 GiB userdata 并写入 1,737,281,536 字节 system。测试 76.963 秒通过，最终完整槽为 `[dsu, los]`，active=dsu、enabled=false、running=false、installing=false。
- 原 dsu 各顶层镜像文件的设备号、inode、长度、mtime 与安装前一致，原 dsu 的 complete/install_dir SHA 一致；全局 active/install_status SHA 也与原快照一致。没有删除/重建原槽，没有重启手机，没有实际验证 los 的首次启动。
- 普通 JVM 测试新增已知/未知补丁识别、补丁恢复后在维护标记前阻止分区修改；真实写入设备测试必须指定 NEW_SLOT_ONLY、slotId 和 los 参数，默认测试不会执行该写入。
- 最终构建/lint 通过，JVM 测试 54 项通过；lint 0 错误、26 警告。0.2.2/code 5 已覆盖安装并启动，设备测试 APK 已移除。APK 72,018,672 字节，v2 签名有效；SHA `90db79177ce2953cc4a9cc34886c3664667159a9b225c6cf5d9e255b1b286ac1`。

## SukiSU Ultra 持久模块

- 确认 moto 安装的是 `com.sukisu.ultra`，用户空间版本 `4.0.0-1-ga4a9df3a`；按用户确认采用内置 Magic Mount，无需 metamodule。
- 最终模块为 `UltraDSU-GSID-Moto-API34-KSU-v1.0.1.zip`，id `ultradsu_gsid_moto34`、versionCode 2，352,592 字节，SHA `e7b74425f96e834a6bac1cbd907196984e68d9aa0139cf2e0febd964fc691eb6`。
- ZIP 使用标准 `system/bin/gsid` 目录；不含自定义 bind/remount 脚本、remount-helper 或 sepolicy.rule。安装校验 KSU、ARM64、API 34、原机/补丁哈希和 ZIP 文件清单；二进制上下文为 gsid_exec，权限 0755。
- post-fs-data 仅检查固件和 payload，未知版本写 skip_mount 交由内置挂载逻辑跳过；不调用 Binder、不写系统属性。service.sh 仅记录实际挂载哈希；Action 显示状态。
- SukiSU 模块安装成功，启用且待重启更新，完整内容位于 `modules_update/ultradsu_gsid_moto34`，无 disable/remove 标志。实机 BusyBox 执行启动前校验成功；当前临时补丁继续生效。尚未重启验证内置 Magic Mount 的冷启动加载。
- 禁用或卸载模块并重启，恢复底层原机 gsid；模块不删除 DSU 槽。最初脚本挂载原型 v1.0.0 已被待更新模块 v1.0.1 替代。

## 0.2.3 已导入槽 userdata 容量刷新

- 用户已有 dsu 的 userdata 文件为 17,179,869,184 字节（16 GiB），IImageService 映射 + blockdev 同样读出 16 GiB；容量查询后端本身正常。未知显示来自导入时 userdataGiB=0 的占位值，普通同步只更新槽状态，没有同步容量，直到用户打开分区管理才会查询。
- 修复为主系统空闲时的 Root status 返回各完整槽的 userdataSizes；AppViewModel 普通刷新/Root 启动核实自动更新已导入槽和新扫描槽的容量。单槽读取失败不会丢失全部槽列表，不猜测容量，并保留单槽错误原因。DSU 运行中或安装中不执行容量映射。
- JVM 测试 57 项通过，覆盖导入槽实际容量、运行/安装中禁止映射、容量错误时仍保留槽列表；assembleDebug、lintDebug 通过，APK v2 签名有效。
- 0.2.3/code 6 已覆盖安装并启动 moto；SlotStore 中 dsu/los 都已自动更新为 userdataGiB=16。Root status 返回两者均为 17,179,869,184 字节、userdataSizeErrors 为空。
- 检查结束后原 dsu 的 active/install_status/complete/install_dir 哈希与快照一致，原 userdata 长度仍为 16 GiB。本次仅查询及修复本地容量记录，没有执行扩容或重建。
- dsu 具有 metadata_encryption_dir 登记，裸 backing image 不能被 blkid 识别为明文文件系统；未读取密钥。保留数据扩容需要独立验证 backing image、FIEMAP/LP 元数据、解密映射和文件系统扩容的完整流程；当前应用的 resize 仍是明确清空数据的重建操作。

## 0.2.4 外观与启动提示

- 移除 Root 授权成功后额外生成的消息卡；右上角 ROOT 状态、设置权限说明及失败提示保留。冷启动实测 ROOT 状态存在，无“Root 已授权”消息卡和“知道了”按钮。
- 页首图标使用启动器同一矢量前景和分层背景颜色，支持现有莫奈资源及 Android 10/11 白底黑图回退。真机发现 Compose painterResource 不接受 XML shape 背景，已改为直接引用同一颜色资源并重新安装验证。
- 设置 → 外观新增独立持久化 glass_navigation 开关，默认关闭。开启时使用页面 GraphicsLayer 采样、Android 12+ RenderEffect 模糊、半透明渐变与高光边缘、弹性滑动选中区域，关闭恢复 miuix / MD3 原生底栏；Android 10/11 使用磨砂不透明回退，未实机验证旧版系统。
- 0.2.4/code 7 的 assembleDebug、lintDebug 通过，APK v2 签名有效。moto razr 2022 实测两种 UI 的开关、原生底栏恢复、玻璃底栏标签导航和进程停止后设置保留；最终恢复原 MD3 风格并开启玻璃底栏供用户测试。
- 本次未执行 DSU 分区写入、重建、删除或设备重启。

## 0.2.5 参考 BakaSU 重做液态玻璃

- 核对 BakaSU commit `896e8981e0ed76ec14d1dcb39ab793438b304886` 的 FloatingBottomBar：64dp 胶囊、每项至少 76dp、4dp 内边距、4dp 模糊、24dp 基础透镜、按压时的选中透镜放大与色散。移除上一版的宽底栏和重渐变描边。
- AGSL 透镜 shader 来自 Apache-2.0 的 compose-miuix-ui/miuix commit `17138ef67967e6d57d3cee03893964d85b25afbd` 示例 Lens.kt，保留上游版权和许可证，改用 API 分级的 Android 原生 RenderEffect。未引入最低 API 33 的整套 blur 库，应用最低 API 29 保持不变；API 33+ 折射、31/32 模糊、29/30 磨砂回退。
- 使用独立的强调色图标/文字记录层供选中透镜采样；隐藏副本不加入无障碍节点。底栏支持 Tab 语义、选中状态及 Enter/Space 键激活。
- 根据用户追加要求，按住时直接使用手指绝对横坐标计算连续位置，不通过弹簧插值，也不锁定某个按钮中心；松手才吸附最近按钮并提交页面选择。指针取消或多点触控会取消预览。
- 0.2.5/code 8 编译、lint 通过。moto 实测 AGSL 渲染、轻点切页、左右滑动切页、长按放大、在 x=445 与 x=635 的中间位置实时跟随及松手吸附。检查两种 UI 的深浅色显示后恢复系统原深色设置。旧版 Android 回退未实机验证。
- 已覆盖安装到 moto，保留 Root 与玻璃开关。未修改 DSU 槽、userdata 或 gsid，未重启手机。

## 0.2.6 miuix 新建弹窗不可见

- 真机复现新建按钮启用、Root 操作空闲，但点击不显示窗口；切到 MD3 后同一个待显示的新建窗口立即出现。核对 miuix v0.5.1 的 SuperDialog / MiuixPopupUtils：SuperDialog 只登记弹窗，项目自定义页面未放置 MiuixPopupHost（通常由 miuix Scaffold 提供）。
- 在页面与玻璃底栏之上补上 miuix 弹窗显示层；UDialog 关闭或离开组合时同步设置 visible=false，使宿主退出动画完成后清理登记，防止旧遮罩拦截后续点击。保留 miuix SuperDialog 和 MD3 各自组件。
- 编译、lint、APK v2 签名检查通过；0.2.6/code 9 已覆盖安装到 moto。真机连续两轮“刷新 → 新建 → 取消”通过；另验证重新打开、点击外部关闭、返回键关闭后仍停留应用，以及编辑窗口和启动窗口显示/取消。
- 最后留在新建窗口供用户继续测试；没有创建配置、提交安装或启动选择，没有修改 DSU 槽和 userdata。
- APK：71,746,827 字节；SHA-256 `e2d8ada864969923fcfcbcf4c6a8be0e28009d9b7c269dc30f3f8a1d55422376`。

## 0.2.7 槽编辑二级页面

- 槽卡片“选择启动”改为“启动”，保留现有启动模式与二次确认行为；移除独立“槽内镜像”按钮，统一从“编辑”进入完整二级页面。
- SlotEditorScreen 集中基本设置、镜像信息、实际分区/容量、单分区或压缩包替换、待写入清单和 userdata 重建扩容。返回箭头/系统返回键回到系统槽列表；保存基本设置后保留编辑页面。
- Root 分区操作仍仅在当前授权成功且为已安装/未完成槽时展示，运行 DSU 或安装中禁止修改。自动读取在空闲后只发起一次，失败可手动刷新，避免反复重试；原有分区写入/清空 userdata 的确认流程保留，确认窗口关闭后仍停留编辑页。
- 编译、lint、APK v2 签名校验通过；0.2.7/code 10 已安装到 moto。真机核对 miuix / MD3 页面、卡片按钮合并、进入后读取实际四个分区、保持原名保存、滚动到导入和扩容区域、系统返回键回到列表；恢复原 miuix 与玻璃设置。
- 本次没有替换分区、重建 userdata 或改变启动选择；基本设置保存检查沿用原显示名称。APK 72,310,746 字节，SHA-256 `e3194ca37bac9433b60d7ea991e4b5e6cd36de02ce80d109f3c50fc09e5e575e`。

## 0.2.8 页面导航动画

- 原先仅直接替换 tab/编辑页面，现在 tab 使用 320ms 按方向的横向滑动与淡入淡出，应用头部与底栏保持固定；二级编辑页使用 360ms 推入/退出，列表有 1/3 宽度视差，返回时编辑页位于上层向右退出。
- 各选项卡和主页面用 SaveableStateHolder 保存独立滚动状态。过渡中的非当前页面不接收点击和无障碍操作，离开中的编辑页不再处理返回键。编辑页在进入动画结束后才自动查询分区，避免读取状态挤动过渡中的内容。
- 编译、lint 通过，0.2.8/code 11 已覆盖安装 moto；APK v2 签名有效。MD3 与 miuix 的真机录像分帧确认有实际滑动中间画面，包含 tab 切换、二级页进入及返回，不仅是终态截图。快速连续切换选项卡、进入后立即返回均通过，返回后编辑按钮位置与进入前一致。恢复用户原 MD3、Root 与玻璃设置。
- 录像：`.tooling/v028-motion.mp4`（MD3 tab/进入）；`.tooling/v028-route.mp4`（miuix 进入/返回）。只验证导航及分区只读查询，未提交 DSU 写入、userdata 重建或启动选择。
- APK 72,348,682 字节；SHA-256 `e4980ed1c72e8b6f98a35008a79b94bf0362c0fe1238012b3aa1ce5bfb7489ad`。

## 0.2.8-alpha.1 首个 GitHub 预发布

- 版本 code 12，最低 API 29 / 目标 API 35。启用独立 RSA 3072 发布签名，密钥及真实 keystore.properties 仅保留在 Git 忽略的本地位置，发布附件不包含签名私钥。
- assembleRelease、testReleaseUnitTest、lintRelease 离线构建通过；57 项测试通过（槽规则 6、压缩包 15、RootBridge 36），lint 0 错误。未执行设备破坏性测试或安装新签名 APK，手机上的 Debug 安装与 DSU 数据保持原状。
- apksigner 验证 APK v2 签名有效；aapt 核对 versionName=0.2.8-alpha.1、versionCode=12、无 application-debuggable，包含 arm64-v8a / armeabi-v7a / x86 / x86_64。
- APK 54,620,408 字节；SHA-256 `a07534f5d9bad2687e54992ccf985f5603e753ad2f7e15a92ec5fb60ccae9fdd`。发布证书 SHA-256 `261f796ec575501160e20c3f3e951369180e38f7e4d2bc5e4a1a2350e830c749`。
- 源码清单排除工具缓存、历史 APK、设备二进制、签名文件及本机配置；Alpha Release 仅上传通用应用 APK 与独立 SHA256SUMS.txt，未包含实验性 gsid 模块。

## 0.2.8-alpha.2 维护者与页面精简

- 设置页增加 Fish_Neko、GPT-6.1 Sol 的头像和名字，README 共用相同图片资源。图片保留原像素，检查未包含 EXIF、文本或时间元数据。
- 删除设置页“操作记录”标题及卡片、概览页“开始一次系统探索”卡片，miuix / Material 3 共用更新后的内容。
- assembleRelease、lintRelease 离线构建通过，lint 0 错误；APK v2 签名校验通过，沿用 Alpha 1 发布证书。此次仅修改展示内容，未重复执行 DSU 业务测试或设备写入测试。
- aapt 核对版本 0.2.8-alpha.2 / code 13，最低 API 29、目标 API 35、未启用 debuggable，保留四种 ABI。
- APK 56,823,104 字节；SHA-256 `e17fd757c2bb945ce95da00f5071dcb6d4fcc6f6a59a82331cc21a0829b65c73`。发布附件为 APK 与 SHA256SUMS.txt。
