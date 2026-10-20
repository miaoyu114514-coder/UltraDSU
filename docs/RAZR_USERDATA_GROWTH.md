# razr 2022 保留数据扩容的缺口

检查日期：2026-10-20；本次只读诊断，不执行该机扩容、重建、备份回退或重启。

## 实机信息与确定的阻碍

- 系统 Android 14 / API 34，内核 5.10.198，安全补丁 2024-06-01，F2FS、FBE 与元数据加密启用。
- 当前应用的维护工具要求 API 36+，这不仅是界面开关。将正式工具和配套 AIDL 客户端库放在独立临时目录直接运行只读探针时，动态链接器先报告缺少 `std::__libcpp_verbose_abort`。
- 仅在该临时目录补充匹配的 C++ 运行库后，继续报告缺少 `AIBinder_Class_setTransactionCodeToFunctionNameMap`。该函数在 AOSP 头文件中明确标为 API 36 引入；设备的 Android 14 `libbinder_ndk` 不提供它。因此移除 SDK 判断或仅补一个 `.so` 并不能使当前工具直接运行。没有替换任何系统库。
- 主系统 `/data` 的 fstab 使用 `metadata_encryption=aes-256-xts:wrappedkey_v0`，文件加密选项也包含 `wrappedkey_v0`。当前维护工具明确只接受已适配的普通元数据密钥与普通 64 字节系统 DE 密钥；没有实现硬件封装存储密钥导出、对应内核映射标志和 FBE 封装密钥校验。当前代码会拒绝这份配置。
- 默认 `dsu` 的安装目录是 `/data/gsi/dsu/`，还会触发标准命名槽路径检查；`los` 使用 `/data/gsi/dsu/los/`。先前已经适配的命名多槽 gsid 与保留数据扩容是两条独立路径。

Binder API 来源：[AOSP binder_ibinder.h](https://android.googlesource.com/platform/frameworks/native/+/refs/heads/main/libs/binder/ndk/include_ndk/android/binder_ibinder.h)。硬件封装映射选项的参考实现见 [AOSP MetadataCrypt.cpp](https://android.googlesource.com/platform/system/vold/+/refs/tags/android-security-12.0.0_r42/MetadataCrypt.cpp)。

## 若要适配

1. 为 API 34 单独构建维护工具及匹配的 C++ / Binder / Keystore 客户端，避免强依赖新系统符号；逐项只读验证已有密钥检索与升级的兼容性，仍禁止写回封装记录。
2. 核实目标客体实际沿用的加密选项与密钥格式，再适配硬件封装元数据及系统 DE 密钥的维护流程、内核映射和验证。不能把封装 blob 截成普通 AES 密钥，也不能用重建密钥代替适配。
3. 默认槽另需适配旧式目录与布局；已有内部命名槽可以继续使用各自的路径检查。
4. 在独立副本上完成备份、加密记录与文件校验，再进入明确授权的测试槽做启动验收。

本次没有解封装该机的槽位密钥，也没有启动客体，因此不能仅凭主系统 fstab 或封装记录长度断言某个现有槽的实际密钥形式。确定的是：当前原生二进制存在实机可复现的 ABI 缺口，现有加密选项也未被当前维护路径接受；尚无证据表明需要更换内核或整个 gsid。

临时探针和运行库已删除；默认槽与 `los` 的注册记录、active 摘要、`los` 四份密封密钥记录摘要、两槽 userdata 文件大小 / inode / mtime 与本次基线一致。
