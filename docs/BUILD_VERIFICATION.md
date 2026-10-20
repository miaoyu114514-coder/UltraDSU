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

## 0.2.8-alpha.3 液态玻璃本地修复验证

- 在 Aura A1 / Android 17 复现按压时高亮图标、文字过度放大与裁切重影：旧版截图中图标可见边界由 36×36 像素增至约 60×61 像素。
- 将前景图标、文字移出透镜采样和缩放层，按实际透镜轮廓裁切高亮颜色；保留玻璃胶囊按压放大、连续拖动和松手切页。
- 选中透镜直接采样页面后，补齐静止、按压和拖动阶段的背景模糊，再执行折射；Android 12 的选中透镜也使用模糊回退。前景文字不参与背景模糊。
- Release 编译、lint 与 APK 签名校验通过，lint 0 错误。Aura A1 已覆盖安装 code 14，沿用发布签名并保留应用数据。
- miuix / Material 3 真机检查通过：图标在按压前后均为 36×36 像素，文字保持正常字号，胶囊背景保留模糊；拖动与松手切页正常。检查后恢复原 miuix 风格。未执行 DSU 安装、分区写入、userdata 重建或启动选择操作。
- 本地 APK 56,823,104 字节；SHA-256 `092afe05398a0c85c1b0e50e2710423dbd8e473060602e4e33dcf6439feb077a`。本次尚未推送或发布 GitHub。

## 0.2.8-alpha.4 应用内悬浮进度

- 将镜像准备、Root 安装/替换和 userdata 重建的状态集中到全局悬浮气泡，移除主页面与编辑页原有的进行中卡片。气泡支持拖动、展开/收起，完成、失败和取消结果可以手动关闭；后台仍保留前台服务通知。
- 读取使用文档提供者声明的源文件大小，解包使用 raw 条目实际声明大小，打包统计全部分区字节，Root 桥接以结构化 JSON 返回精确写入量。百分比标记为当前阶段进度；分区分配、未知长度压缩流和格式检查使用不定进度，避免伪造整体百分比。嵌套压缩条目不把压缩大小误当成解压总量。
- assembleRelease、testReleaseUnitTest、lintRelease 通过，64 项 JVM 测试通过，lint 0 错误，发布签名校验通过。版本 0.2.8-alpha.4 / code 15，最低 API 29，目标 API 35。
- Aura A1 / Android 17 的独立测试应用中，2 项设备测试通过：实际读取/准备限速的 64 MiB 合成镜像、气泡展开和拖动、编辑页返回、主页面中的进度常驻、完成/失败结果关闭，以及 MD3 取消后的草稿状态。测试保持 Root 关闭，未创建真实 DSU 槽或写入设备分区。
- 独立构建采用 tools/progress-ui-test.init.gradle；普通 Release 的 manifest / DEX 已核对不包含测试提供者和测试包名。验证后已卸载独立测试应用与测试 APK，保留截图于 Git 忽略的工具目录。
- 发布签名的本地构建已覆盖安装 Aura A1，保留原配置和 DSU 数据。APK 56,855,872 字节；SHA-256 `d549d659bae24a126b170aac94735392592b912a05378aa5f1f8768be989e8fb`。本次尚未推送或发布 GitHub。

## 0.2.8-alpha.5 调试日志与加密链路分析

- 设置加入持久化调试开关、系统文档选择器 TXT 导出和清空；记录操作阶段、进度、桥接耗时、userdata 容量读取错误及有限异常堆栈。私有目录按两个 512 KiB 文件轮转，关闭开关停止新记录，导出前再次脱敏。
- Root 已授权且调试模式开启时，提供手动 DSU 系统诊断：固定白名单加密属性及 gsid / vold / fs_mgr 的近期日志。不读取密钥、dm 表、安装清单或整机属性，不自动上传。
- AOSP 分配、元数据密钥、挂载及 FBE 的源码核对与本项目多槽密钥清理缺口见 `docs/DSU_USERDATA_ENCRYPTION.md`。本次未修改密钥清理行为，也未创建/格式化/删除真实槽。
- assembleRelease、testReleaseUnitTest、lintRelease 通过：69 项 JVM 测试、0 失败，lint 0 错误，发布签名校验通过。code 16，最低 API 29，目标 API 35；普通 Release manifest 不包含独立测试组件。
- Aura 解锁后通过 Root `pm install -r` 覆盖安装，绕过厂商安装器界面拦截并保留数据。独立应用的 2 项设备测试通过（18.27 秒）：实际日志轮转、关闭后停止采集、脱敏、miuix / MD3 开关与持久化设置、文件导出和清空。未覆盖系统文档选择器所有保存目标或 Root 系统诊断在各厂商 ROM 上的行为。
- 测试发现长无邮箱文本会触发邮箱正则的重复扫描，已增加边界和预检查，并加入长文本回归测试。界面测试等待列表反馈布局稳定、点击实际可见开关和按钮后通过；测试应用与临时 APK 已清理，恢复正式应用。
- 本地 APK 56,888,660 字节；SHA-256 `b17f560f46eae1eb0739188ec1f040f759c56066f72d24524610a323b92429f1`。本次尚未推送或发布 GitHub。

## 0.2.8-alpha.6 保留数据扩容

- 槽编辑页增加兼容性预检、完整备份、保留数据扩容、备份回退和单独清理；沿用全局悬浮进度与前台服务。原清空重建入口继续明确提示数据清除。
- 原生工具以独立进程运行：只读解封装原槽密钥、私有 dm / mount namespace、F2FS 日志正常回放，以及区分 fscrypt 文件数据与元数据的块搬移。故障状态阻止启动和覆盖，恢复记录与备份独立校验，启动选择与本应用遗留会话可恢复。
- assembleRelease、testReleaseUnitTest、lintRelease 离线构建通过，77 项 JVM 测试全部通过，lint 0 错误；8 项事务测试覆盖失败、中断、篡改、密钥变化与回退。模拟测试不等同于实际断电测试。
- code 17，最低 API 29 / 目标 API 35，四种原有 ABI 保留；新增扩容工具仅支持 Android 16+ / arm64 的已适配布局和加密组合，其他环境预检拒绝。元数据加密的 ext4 离线扩容未开放。
- APK 签名有效，沿用 Alpha 1 发布证书。APK 57,515,067 字节；SHA-256 `f7484bed820c7598928a4ab710e92669adfe696939b6afc97881eae7afb18d2a`。本地同时附带原生工具对应源码压缩包和 SHA256SUMS.txt，未推送或发布 GitHub。
- 本次新增的 4 个原生资产与源码归档共检查 388 个文件，未发现构建机用户名、主机名或绝对源码路径。设备日志、签名资料、本机配置和工具输出仍在 Git 忽略目录中。
- Aura A1 的实际迁移、回退、启动与文件校验记录见 [DEVICE_TESTING.md](DEVICE_TESTING.md)。

## 0.2.8-alpha.7：兼容性检查反馈（2026-10-20）

- Release 构建成功；版本 code 18，最低 API 29、目标 API 35。
- 全套 JVM 测试 82 项，0 失败、0 错误；包含 5 项新的兼容性/备份检查状态回归测试。最后一处刷新布局稳定性调整后再次完成 Release 构建和 lint，lint 0 错误。
- 正式 APK v2 签名验证通过。已 Root 覆盖安装到 Moto razr 2022；两种 UI 的默认槽检查拒绝原因可见。最终包上重复检查，按钮和结果位置一致，无页首重复报错。槽镜像与关键元数据前后核对一致；未执行 DSU 写入或重启。
- 原生维护工具与 Alpha 6 相同，沿用其对应完整源码包；没有放宽 Android 16+、arm64 或布局限制。
- 文件：`dist/v0.2.8-alpha.7/UltraDSU-0.2.8-alpha.7.apk`，57,531,451 字节。
- APK SHA-256：`4ec66cffc78dffb53147072eaad7581892347447cd6834e7922a43e14db097d6`。
- 原生源码包 SHA-256：`fcbfa4894f55aae7c02d054320cd5aa69a7ea225239508b9051bce2ad053bc21`。

## 0.2.8-alpha.8：Keystore 失败诊断（2026-10-20）

- Release 构建、84 项 JVM 测试和 lint 通过，0 测试失败/错误，lint 0 错误；APK v2 签名有效。code 19，最低 API 29 / 目标 API 35。
- 原生维护工具只增加固定日志中数值错误码的识别，仍不转发原始库日志；细化 `INVALID_ARGUMENT(-38)` 的提示，说明可能的跨版本密钥绑定限制。没有放宽预检或增加密钥重建/写回行为。
- 已 Root 覆盖安装到 V50 Pro，实际预检与 UI 提示通过；槽位文件统计、关键 DSU 元数据及密封密钥记录前后核对一致。未执行该机扩容、回退、备份、重建或重启。诊断详情见 [DEVICE_TESTING.md](DEVICE_TESTING.md)。
- APK：`dist/v0.2.8-alpha.8/UltraDSU-0.2.8-alpha.8.apk`，57,531,695 字节，SHA-256 `5fd370d6d62a1439fc584a875891e9e3d11074152afd34ddb0b5b90e5b1a915d`。
- 对应原生源码包 556,787 字节，SHA-256 `c3762b65243682d3561992ee00dd1b23bae55b4eeadd8c794bfc1ae202b39443`。新增原生可执行文件检查未包含构建机用户名或绝对构建路径。未推送或发布 GitHub。

## 0.2.8-alpha.9：客体协作扩容（2026-10-20）

- 主系统硬件 Keystore RSA 凭据、客体 AES-GCM / RSA-OAEP 认证交接、限定槽位与会话的匿名管道，以及协作扩容备份的严格原始回退流程已实现。原有槽位密钥封装不重建或写回。
- Release 构建、95 项 JVM 测试及 lint 通过，0 测试失败 / 错误，lint 0 错误。最后调整客体完成态提示后再次完成 Release 构建与 lint；该次仅修改展示文字。code 20，最低 API 29 / 目标 API 35，正式 APK 不含测试组件。
- V50 主系统实际生成硬件保护凭据；客体采集后，主系统真实设备解封装和只读预检测试通过，扩容完成后重复检查也通过。应用前台服务完成 16→18 GiB 事务，保留完整 16 GiB 备份。两种界面的气泡、两次客体冷启动、原 CE / DE 文件、硬链接及 5.25 GiB 新容量文件校验通过。具体耗时与未覆盖范围见 [DEVICE_TESTING.md](DEVICE_TESTING.md)。
- 本机 Alpha 9 正式工具逐块文件系统搬移耗时约 47–48 分钟，未包含后续实验性局部搬移补丁。未刻意删除真实硬件协作凭据，也未做真实断电测试。
- APK v2 签名有效，沿用原发布证书。`dist/v0.2.8-alpha.9/UltraDSU-0.2.8-alpha.9.apk` 为 57,570,663 字节，SHA-256 `ea1bcfd7c23e8e887c833157ec50d79418ec57828801ef530de6dc1eeb8bbdc8`。
- 对应原生源码包 560,368 字节，SHA-256 `cb0edf0a4fe9f8b06a30e1d7b37875f329ff01d71ee09e7829eae89ac6ebaba8`。源码归档、原生资产及 APK DEX 共扫描 404 个文件，未发现构建机用户名、主机名或绝对构建路径；签名资料、设备输出和本机配置保留在忽略目录。未推送或发布 GitHub。

## 实验性局部搬移验证（2026-10-20）

- `build.py --local-growth` 独立原生编译及 Python 语法检查通过；新增实验分支补丁化 defrag.c，普通构建默认仍禁用加密局部整理。正式 Alpha 9 的原生资产、APK 与对应源码归档均未覆盖。
- 显式选择的 V50 设备仪器测试完成独立 16→18 GiB 候选扩容，1 项通过；文件系统阶段 43.27 秒、总计 97.68 秒。候选随后完成一次真实冷启动、用户解锁、原 CE / DE 和硬链接校验，以及超出旧容量的 5.25 GiB 文件写入与两次读回。
- 恢复测试前的 18 GiB 整盘状态后，硬件凭据解封装和真实槽预检的设备测试再次通过，1 项通过。原密封密钥记录与系统镜像统计保持一致；临时镜像、额外恢复点、助手和设备测试包已清理，正常 16 GiB 回退备份及协作凭据保留。
- 本次只验证实验候选，没有重新发布应用或修改普通 APK 的扩容路径。详细测量和未覆盖范围见 [LOCAL_GROWTH_VALIDATION.md](LOCAL_GROWTH_VALIDATION.md)，Moto Android 14 的只读 ABI / 加密诊断见 [RAZR_USERDATA_GROWTH.md](RAZR_USERDATA_GROWTH.md)。

## 0.2.8-alpha.10：userdata 备份管理（2026-10-20）

- 设置页和槽编辑页新增备份管理二级页面，沿用 miuix / MD3 与进入、返回动画；从槽编辑页返回时恢复原编辑页。当前绑定 userdata 始终不可删除，可清理独立备份；失败恢复所需副本和未知状态保守保护。
- Root 列表及清理无需调用 Android 16 原生工具。限定固定备份目录/文件集合，拒绝 `current` 类型、路径越界、符号链接及在用镜像的硬链接；删除时重新检查事务阶段、维护标记和列表版本标识，清理后使扩容预检缓存失效。
- Release 构建与 lint 通过，lint 0 错误 / 23 警告。111 项 JVM 测试中 110 项通过、1 项因 Windows 无符号链接权限跳过，0 失败 / 错误；对应符号链接行为已在 Android 实机补测。最后调整嵌套返回动画后再次完成 Release 与 lint。
- V50 独立测试应用的 2 项设备测试通过：Android 符号链接保护和独立副本清理、两种 UI 的在用按钮禁用与列表失效后的删除禁用。另在 Root 私有合成目录验证真实硬链接保护和备份删除，未引用真实槽；该固定测试目录已清理。
- V50 主系统和 razr Android 14 的真实 Root 列表只读通过：V50 在用 18 GiB / 独立备份 16 GiB，razr 两个在用 userdata 均 16 GiB。清单和备份身份前后完全一致，原有备份保留；未执行真实槽备份删除、扩容、重建或重启。
- code 21，最低 API 29 / 目标 API 35，正式 APK 不包含独立测试组件。APK v2 签名有效，沿用发布证书。原生扩容资产与 Alpha 9 相同，未启用实验性局部搬移。
- APK `dist/v0.2.8-alpha.10/UltraDSU-0.2.8-alpha.10.apk` 为 57,636,191 字节，SHA-256 `f7cf2239a50f2bbe33175101934a533ec903e45425765380c562aade5de8d144`。对应原生源码包 561,028 字节，SHA-256 `17e66f23216b5430835ee86d76e951f8ebfb8802e20bb1f4ffd0f4b6588d3337`。
- 对 APK DEX、原生扩容资产及含完整上游归档的对应源码共检查 401 个条目，未发现本机构建路径、用户名或主机名。测试应用与设备临时探针已清理，未覆盖安装设备上的正式应用，也未推送或发布 GitHub。

## 0.2.8-alpha.11：局部整理提速与文件系统进度（2026-10-20）

- 按用户要求停止 V50 正在执行的 Alpha 9 全量扩容，正常进入恢复状态；真实硬件协作回退测试通过，16 GiB 原始备份和回写数据完整哈希一致，状态为 ROLLED_BACK。没有自动重新执行真实槽的 128 GiB 扩容。
- 以 `--local-growth` 编译并替换随包 F2FS 维护资产。新增固定数字进度协议与严格解析器，向已有气泡转发局部整理/全量扫描的逻辑区域计数及元数据阶段；大容量 resize 单阶段上限从一小时改为六小时，不改变备份、密钥或失败恢复检查。
- 独立 16→128 GiB 候选通过：文件系统阶段 519.19 秒，整体 641.85 秒，块设备和 F2FS 均为 128 GiB，12 份 FBE 密封记录一致。该候选没有客体冷启动验收；首个 180 秒测试限时过短的尝试已中止、清理并重新验证，详见 [局部搬移记录](LOCAL_GROWTH_VALIDATION.md)。
- Release 构建及 lint 通过，0 lint 错误 / 23 警告。114 项 JVM 测试中 113 项通过、1 项 Windows 符号链接权限限制跳过，0 失败/错误；新增 3 项进度协议测试，覆盖阶段计数、未知总量、越界/格式错误及任意原生输出拒绝。
- 初次合并构建发生一次 JDK 本机访问异常，崩溃及重放日志移入本地忽略目录；限制构建 worker 后重试通过。最终构建完成后没有残留 Java 进程，不在源码或分发包中包含这些日志。
- code 22，最低 API 29 / 目标 API 35，v2 签名有效且证书保持不变。已 Root 覆盖安装 V50，硬件凭据及真实 16 GiB 文件系统预检设备测试再次通过；在实际界面确认 Root 开关保留、在用 userdata 删除按钮禁用、独立 16 GiB 备份仍保留。
- APK `dist/v0.2.8-alpha.11/UltraDSU-0.2.8-alpha.11.apk`：57,637,279 字节，SHA-256 `2068901c5cf8f9e129ac72048ef652dd081f3070add03765e67c3641fdae37a1`。对应原生源码归档随包提供，包含新进度补丁、构建脚本及完整上游源码。
- APK DEX、原生资产及对应源码含上游归档共检查 402 个条目，未发现构建机路径、用户名或主机名。专用候选入口和设备测试代码未打入正式 APK；测试副本、助手与测试包已清理，正常回退备份及协作凭据保留。未操作 Moto / Aura，也未推送或发布 GitHub。
