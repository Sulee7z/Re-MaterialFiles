# MT 管理器 2.26.8 去签名校验逆向笔记

来源:`MT2.26.8.apk`(bin.mt.plus / v2.26.8)反汇编 + `L-JINBIN/ApkSignatureKillerEx`
参考实现(revanced-patches 仓库 `202c45d` commit 导出的 killer 源码)。

## 架构总览

MT 的去签产物由三部分组成,注入目标 APK:

```
目标 APK (修改后)
├── AndroidManifest.xml        # application android:name → KillerApplication
├── classesN.dex               # KillerApplication (继承原 Application 或独立)
├── lib/<abi>/libSignatureKiller.so   # xhook: hook open/open64/openat/openat64
└── assets/SignatureKiller/origin.apk # 原始 APK 的完整副本
```

运行时两层拦截:

### 1. Java 层 — 替换 `PackageInfo.CREATOR`(killPM)

系统通过 Binder 跨进程传递 `PackageInfo` 时一律走 Parcel 序列化/反序列化,
反序列化由静态字段 `PackageInfo.CREATOR` 完成。把该字段替换为自定义
Creator 后,**所有**进入本进程的 PackageInfo 都会被拦截:

- `signatures[0]` → 替换为伪造 Signature(原始证书 DER)
- `signingInfo.getApkContentsSigners()[0]` → 同样替换(API 28+)
- 清空缓存:`PackageManager.sPackageInfoCache`、`Parcel.mCreators`、
  `Parcel.sPairedCreators`,避免旧对象残留
- API 28+ 用 HiddenApiBypass 做隐藏 API 豁免

关键点:替换 CREATOR 用的是反射改静态字段
(`PackageInfo.class.getDeclaredField("CREATOR").set(null, creator)`),
CREATOR 是 public API,反射不受隐藏 API 策略限制。

### 2. Native 层 — xhook 重定向 APK 文件(killOpen)

应用可能直接读取 APK 文件校验(哈希、ZipFile 遍历、dex 比对等),
Java 层拦不住。native 层用 xhook(PLT hook)hook 所有 so 的
`open / open64 / openat / openat64`:

- 从 `/proc/self/maps` 定位安装后 APK 的真实路径
- 把 `assets/SignatureKiller/origin.apk`(原版 APK)释放到 data 目录
- 任何代码打开安装后 APK 路径时,透明重定向到原版 APK 文件

### 与老版(Proxy 方案)的对比

| | 老版 PmsHookApplication | MT 新版 |
|---|---|---|
| 拦截点 | `IPackageManager` 动态代理(替换 sPackageManager/mPM) | `PackageInfo.CREATOR`(Parcel 反序列化层) |
| signingInfo (API 28+) | 不支持 | 支持 |
| 应用内直读 APK 文件 | 不支持 | xhook 文件重定向 |
| 依赖隐藏 API 反射 | ActivityThread.sPackageManager 等(greylist) | CREATOR(public)+ 缓存字段(尽力而为) |

## 文件

- `KillerApplication.java` — 原始 Java 源码(参考实现)
- `mt_jni.c` — xhook 的 JNI 封装(hookApkPath)
- `build.gradle.kts` — killer 模块构建配置

## MT 2.26.8 自身 APK 的防护(与本功能无关,逆向副产品)

- `libhook.so` = **LSPosed lsplant + Dobby**(ArtMethod inline hook 框架)的封装,
  `org.lsposed.lsplant.Hooker` 提供 `check/doHook/doUnhook`,MT 用它 hook
  自身关键方法做防篡改(NAK4 类:字符串全部异或加密,含 JavaHook probe
  反调试检测)。
