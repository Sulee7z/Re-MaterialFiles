# 会话交接文档 — MaterialFiles.Sora-Editor (Re-MaterialFiles)

## 最新交接(2026-08-31,导入新会话从这里开始)

分支:`Sora-Editor_1.7`
远端:https://github.com/Sulee7z/Re-MaterialFiles.git
HEAD:`43289f74`,**已全部推送,origin 与本地同步,工作区干净**(仅 `HANDOFF.md`/`docs/` 未跟踪,提交时不 add)
版本:`version.properties` 仍为 **1.8.3**(本会话双栏/回收站等大量改动未 bump,下次发布改三行即可)

### 本会话完成清单(3 个 commit,均已 push)

1. `2b7dc8a0` **Improve two-pane UI: FAB auto-hide, wider divider, stronger active-pane contrast**
   - FAB 滚动隐藏/多选隐藏:单一事实源 `fabHiddenByScroll` / `fabHiddenBySelection` + `updateFabVisibility()`,只在**活动 pane** 上注册滚动监听(`setupTwoPaneFabAutoHide`,idempotent);多选模式隐藏 FAB(`setFabHiddenBySelection`)
   - 分隔条视觉加宽至 44dp(提升触摸目标);非活动面板内容 alpha 0.5 对比;FAB 底部 margin 16dp
   - 相关文件:`FileListActivity.kt`、`FileListFragment.kt`、`two_pane_divider.xml`、`two_pane_divider_active.xml`、`file_list_activity_two_pane.xml`、`dimens.xml`
2. `f0094f43` **Fix two-pane divider: narrow visual, logic-based touch zone**
   - 分隔条视觉/触摸区 **44dp→12dp**(`two_pane_divider_touch_width`,让两栏占满屏宽)
   - 拖拽手势从 divider 视图上移到 `FileListActivity.dispatchTouchEvent` 内处理,以分隔条中心为基准 **±24dp 逻辑命中半径**(`setupDividerDrag` 变空壳,手势逻辑在 dispatchTouchEvent):近分隔条按下仍可调整比例,两侧的文件点击/拖拽不受影响
3. `43289f74` **Polish two-pane UI: drawer follows active pane, snackbar anchors to FAB, list jumps to top on navigation**(即本会话收尾,6 个文件)
   - **导航抽屉跟随活动栏**:`FileListActivity` 新增 `activePanePathLiveData`(MediatorLiveData)+ `rebindActivePanePath()`(pane 切换时解除旧 source、rebind 新 pane 的 currentPathLiveData);`observeCurrentPath` 改为观察该桥接 LiveData;`activePaneSecondaryListener` 追加调用 `rebindActivePanePath()`
   - **Snackbar 锚定 FAB**:`setupMoveUndo`/`setupDeleteUndo` 加 `.setAnchorView(R.id.floatingActionButton)`;必须**先 `showFab()` 再 make Snackbar**,否则 FAB 隐藏时锚定不生效
   - **列表第一行截断修复**:`FileListFragment.onFileListChanged` 中 pendingState 为空时 `scrollToPositionWithOffset(0, 0)` 回顶(旧 offeset 会把第一行顶出视口)
   - **内容聚拢**:`file_list_activity_two_pane.xml` leftPane `paddingStart=8dp`(`two_pane_screen_edge_margin`)、rightPane `paddingEnd=16dp`(`two_pane_screen_edge_margin_end`,新 dimens)
   - **清理调试日志**:FileListAdapter 每行 bind 的 `Log.i`、FileListActivity 的 TrashDrop 日志、FileListFragment 的 TwoPaneDebug/hideFile 日志、DirectoryContentSizes 的 DirSize 全套日志
   - 注:commit 带 Sisyphus 协作者署名(Ultraworked with Sisyphus),保留即可

### 更早本批功能(已推送,前几个会话)
- 回收站:`TrashFileJob` `.trash_` 前缀原地重命名 + `TrashManager` 持久化 + `TrashListActivity` + 导航抽屉 TrashItem + 删除确认对话框(含大小)+ 撤销 Snackbar(commit `c8815c2c`、`0d8e1876`)
- **FTP/WebDAV 等远端文件跳过回收站,直接永久删除**(commit `996e0ab4`)
- SMB 编辑修复:关旧 session、connect timeout、路径与密码校验(commit `0d8e1876`)
- 文件夹日期+大小显示:`DirectoryContentSizes`(4 线程池并行计算,commit `dcd32aaa`)
- 详细历史见下方旧交接节与 `git log --oneline -30`

### 环境(沿用)
- 项目路径:`C:\Users\sulee\Desktop\MaterialFiles.Sora-Editor`
- 构建:JDK 必须用 `E:\android-sdk\jdk-25.0.2`(系统默认 JDK17 不支持 VERSION_25)
  `$env:JAVA_HOME = "E:\android-sdk\jdk-25.0.2"; .\gradlew.bat :app:assembleDebug --console=plain`
- 设备(adb):当前 `QV7109BP3E`(USB);adb 用全路径 `C:\platform-tools\adb.exe`;安装前 `adb devices` 确认,旧版本签名不同需先 uninstall;安装命令 `C:\platform-tools\adb.exe -s QV7109BP3E install -r app\build\outputs\apk\debug\app-debug.apk`
- 提交模板:主题=动词开头 + 标题+冒号细节(如 `Fix two-pane divider: narrow visual, logic-based touch zone`),body 说明动机;建议带 Co-authored-by(最近提交惯例,可带可不带)
- 编译有若干 deprecation warning,与改动无关,忽略

### 已知问题/待办
1. **版本未 bump**:大量功能已在 origin 上(1.8.3 之后约 20+ commit),下次发布前改 `version.properties` 三行即可(CI 从该文件派生 versionCode/ tag)
2. **抽屉 FTP 无条目匹配问题**:本会话未触碰导航抽屉 FTP 相关代码,如有该 bug(抽屉搜索/FTP 连接无结果显示),需下会话定位(`NavigationItems.kt` 仅含 ftp server entry);未确认,勿直接改
3. **MD2/MD3 双栏回退权衡**:双栏改动主要在 MD3 下拍版/验证,MD2 主题下双栏布局差异未逐一复验(历史注意事项)
4. **滚动隐藏 FAB 残余边界**(代码已防御,未逐项复验):`fabHiddenByScroll`/selection 双标志竞争已由单一事实源 `updateFabVisibility()` 收敛;短列表无滚动通知时 FAB 保持可见;切栏后手尾 fling 不会错误隐藏(仅活动 pane 注册监听)。若再出现"切到单栏模式 FAB 不见了",检查是否走 `showFab()` 复位

### 重要注意事项
- **提交时不要 add `docs/` 和 `HANDOFF.md`**(本地笔记/交接文档,永远不入库)
- **CRLF warning 会干扰 `$?`**:`git add .` 打印 CRLF 警告后,PowerShell 的 `if ($?)` 链可能判定失败导致 commit 不执行 —— **add 与 commit 分开两条命令执行**,不要用 `; if ($?)` 连接
- 临时 `build-*.log` 推送前删除
- 源码注释不得提及"MT 管理器"(用户要求);历史遗留 "MT Manager style" 注释勿改
- edit 工具写入后需 grep/read 二次确认(历史曾出现 edit 报成功但文件未落盘)
- 若涉及用户服务进程/Stellar:先 `adb shell pkill -f "me.zhanghai.android.files:stellar"` 清理残留进程再测

---

## 旧交接(2026-08-18,以下事项本会话之前已完成)

分支:`Sora-Editor_1.7`;版本:已发布 **v1.8.2**(commit `22712cf`,正式 tag),后续 v1.8.3(`16bbe878` bump,`938e656f` 等)
(注:当时"立即要做的事"——修复 `BookmarkRecentDirectoriesDialogFragment` 编译损坏——已完成,对应 commit `938e656f`,验证通过并推送)

### 已完成功能备忘
- **最近/书签对话框改为 Material 卡片样式**(与"添加存储空间"一致):
  - 移除 `onStart` 窗口调整(dim、透明背景、全宽居中、点外部关闭)
  - 布局移除自绘背景 `@drawable/bookmark_recent_dialog_background`(该 drawable 可删除,若其后未被引用)
  - **坑**:不要用 `onCreateDialog` 里 `setView(binding.root)` + 删掉 `onCreateView` —— 那样 `onViewCreated` 不执行、UI 空白;正确做法:`onCreateView` 返回 binding.root,`onCreateDialog` 只 `return MaterialAlertDialogBuilder(...).create()`(不 setView)
  - 参照:`AddStorageDialogFragment.kt`
- **终端 extra keys 重做**(v1.8.2):键位移到底部、双排 7 列 GridLayout、`windowSoftInputMode="adjustResize"`、折叠/展开持久化(`SharedPreferences`)、方向键长按重复(400ms→80ms)、修饰键(CTRL/ALT/SHIFT)短按临时/长按锁定、扁平样式(`Widget.MaterialFiles.TerminalKeyButton` + `#CC000000` + 激活 `#80DEEA`);另有 InflateException 修复、方向键 DOWN 即发送
- **双栏共享面包栏(MT 风格)**:`sharedBreadcrumbLayout`(固定顶栏下)、`refreshSharedBreadcrumb()`、`onPaneBreadcrumbChanged()`、`dispatchTouchEvent` 的 `breadcrumbTouched` 排除
- **长文件名换行改进**:`setSingleLine(false)` + `maxLines=Int.MAX_VALUE` + `ellipsize=null`、itemLayout `WRAP_CONTENT` + `minimumHeight`(普通 72dp/dense 48dp);`onWrapLongFileNamesChanged` 重新 `recyclerView.adapter = adapter` 强制重测
- 书签点击跳转/长按编辑(`7b2cb08`)、长文件名换行设置(`6bf1073`)

## 其他已知背景
- 7z 加密解压:libarchive 3.8.1(`me.zhanghai.android.libarchive:library:1.1.6`)对 7z AES-256 **未实现解密**(`7zip.c` 1643-1644 行返回 "Crypto codec not supported yet"),用户已明确"先不改";未来可用 Apache Commons Compress 补充
- 后台 librarian 任务曾因 model not found 失败,7z 研究已由主代理手动完成,无需重跑

---

## 旧交接(2026-08-12,Stellar 支持已完成并推送,真机验证通过)

生成时间:2026-08-12(Stellar 支持已完成并推送,真机验证通过)
分支:`Sora-Editor_1.7`
远端:https://github.com/Sulee7z/Re-MaterialFiles.git

## 环境

- 项目路径:`C:\Users\sulee\Desktop\MaterialFiles.Sora-Editor`
- 构建:JDK 必须用 `E:\android-sdk\jdk-25.0.2`(系统默认 JDK17 不支持 VERSION_25)
  - `$env:JAVA_HOME = "E:\android-sdk\jdk-25.0.2"; .\gradlew.bat :app:assembleDebug`
- 设备(adb):`QV7109BP3E`(USB)、`192.168.5.214:38491`(无线);设备可能切换,先 `adb devices`
  - 设备上旧签名版本需先 `adb uninstall me.zhanghai.android.files` 再装
  - 安装:`adb -s <serial> install -r <apk绝对路径>`(注意 workdir,用绝对路径)
- 目标设备已安装 **Stellar 管理器**(roro.stellar.manager),Stellar 服务以 ADB 模式运行(stellar_server, shell uid 2000)

## 已完成并推送的功能

### ② 一键去除签名校验(`apkkiller/`)
- `KillerApplicationSmali.kt`:手写 smali 模板,PackageInfo.CREATOR 替换 + IPackageManager 动态代理双层方案(signingInfo API28+ 也处理)
- `ApkSignatureExtractor.kt`:getPackageArchiveInfo 提取签名
- `ApkSignatureKiller.kt`:smali 汇编 → DexPool 合并(>96MB 回退新 dex)→ BinaryXmlPatcher 补 manifest → ApkRebuilder 重建 → AutoSigner 重签
- `DexAccessFlagPatcher.kt`:final/非 public Application 的 dex access_flags 字节补丁 + sha1/adler32 重算
- `apkutil/`:`BinaryXmlPatcher.kt`(AXML 手术式补丁)、`ApkRebuilder.kt`(zip 重建+重签)
- UI:APK 长按菜单 "Remove signature verification",FileListFragment.killSignature
- **已验证**:MT2.26.8.apk、DataBackup.apk(final Application)均成功

### ③ dex++ smali 编辑器(`dex/`)
- `DexSmaliCompiler.kt`:单类 round trip(disassembleClass/assembleClass/mergeDex)— **避免全量重汇编 OOM**
- `DexSmaliEditorActivity/Fragment`:Rosemoe 编辑器 + undo/redo/保存;写回 .dex 或 APK 重签
- `DexAnalyzerFragment`:MT 风格两级导航(类列表 → 成员列表,导航栈 + 滚动恢复)
- `DexClass.sourceDex` 记录 APK 内所属 dex
- **已验证**:DataBackup.apk smali 编辑保存成功(单类模式,OOM 已修复)

### ④ ARSC 编辑器(`arsc/`)
- `ArscParser.kt`:完整解析(compact/sparse/complex/UTF8/UTF16 池)
- `ArscWriter.kt`:全量序列化(AOSP 兼容长度编码,headerSize 正确)
- `ArscEditorFragment`:包 → 类型 → 条目三级页面导航 + 值编辑
- **已验证**:aapt2 无警告解析,round-trip 无损

### 双栏状态栏修复(commit 03c431f)
- 顶栏 inset 消费 + pane CoordinatorLayout fitsSystemWindows=false

## Stellar 支持(已推送,真机验证通过)

需求:同时保留原版 Shizuku + 支持 Stellar(https://github.com/roro2239/Stellar,Shizuku 分支)

### 重要背景(2026-08-12 二轮修复)
- **Stellar 管理器更新后启用了 Shizuku 兼容层**:`Shizuku.pingBinder()` 变 true,导致 launchService 误走 Shizuku 链路,而兼容链路的 startUserService/attachUserService 无法交付 binder → 15s 超时
- **修复**:`isShizukuManagerInstalled()` 用 PackageManager 查 `moe.shizuku.privileged.api` 包(API 13 已移除 ShizukuProvider.isShizukuInstalled),只有真 Shizuku 管理器安装才走 Shizuku 链路,否则走验证过的 Stellar 原生链路
- **CI release 构建修复**:vendor 的 `com/stellar/api/BinderContainer.kt` 与 Stellar-API provider 依赖重复(R8 duplicate class error)→ 已删除 vendor 文件(依赖已含该类)
- **Gradle 9 deprecation 修复**:app/build.gradle 全部 `propName value` → `propName = value`;`kotlin_version` 通过 `rootProject.ext` 显式引用(build.gradle 顶层加了 ext block)
- 残留一条 "Project object as dependency notation" 警告来自 AGP 内部(createTestComponents),无法从项目侧修复,不影响构建

### 最终架构(对照官方 INTEGRATION_GUIDE.md 完善)
- 依赖:Stellar-API(aidl/shared/api/provider main-SNAPSHOT)+ `dev.rikka.hidden:compat:4.4.0` + `dev.rikka.hidden:stub:4.4.0`(compileOnly)
- Manifest:`StellarProviderCompat`(authority .stellar, exported, multiprocess=false, permission=INTERACT_ACROSS_USERS_FULL)+ meta-data `roro.stellar.permissions=stellar`;`ShizukuProvider` 保留
- `SuiFileServiceLauncher.kt`:launchService() Shizuku 优先 → Stellar 兜底;Stellar 用 Stellar.newProcess 自启用户服务进程
- `StellarProviderCompat.kt`:继承官方 StellarProvider,接管 sendUserService 直接交付 binder(管理器 attachUserService 有 bug 反序列化 BinderContainer 失败)
- `StellarUserServiceCompat.kt`:expectBinder/onUserServiceBinder 交付机制 + onBinderDead
- `StellarUserServiceMain.kt`:app_process 入口(CLASSPATH=本 APK)
- `com/stellar/api/BinderContainer.kt`:vendor(官方 provider 旧构建缺该类)
- `AppInitializers.kt`:注册 Stellar.OnBinderDeadListener → StellarUserServiceCompat.onBinderDead
- proguard:roro.stellar.** / com.stellar.** / rikka.hidden.** keep

### 关键踩坑(本会话解决,务必保留注释中的原因说明)
1. **hidden API 拦截**:`ActivityManager.getContentProviderExternal` 是 @hide,`Class.getMethod` 抛 NoSuchMethodException;`HiddenApiBypass.addHiddenApiExemptions("L")` 在 shell uid 进程**静默失败**(不抛异常但不生效,非 system 进程被 ART 忽略)
   → 改用 `rikka.hidden.compat.ActivityManagerApis.getContentProviderExternal(authority, 0, null, packageName)`(管理器 UserServiceStarter 同款,Unsafe 机制绕 hidden API,shell 进程可用)
2. **IContentProvider 不在 android.jar**:compileSdk 36 无此类 → 加 `compileOnly dev.rikka.hidden:stub`(管理器同款);call 需按 API level 分支(API31+ 用 AttributionSource 5 参,stub 重载歧义用 `null as String?` 消歧)
3. **native lib 加载失败**:app_process 进程(CLASSPATH=APK)的系统 classloader 搜索不到 APK 内 libsyscall.so/libselinux-jni.so(只搜系统库路径),访问文件时 Syscall.<clinit> 崩溃
   → 双保险:(a) `StellarUserServiceMain` 用 `context.classLoader.loadClass("...SuiFileServiceInterface")` 反射创建(与 Shizuku UserService.create 一致,LoadedApk classloader 可解析 APK 内 native lib)+ `getMethod("asBinder")` 反射拿 binder(避免跨 classloader cast);(b) `NativeLibraryLoader.kt`(provider.linux.syscall 包)兜底:从 CLASSPATH APK 提取 lib/<abi>/libsyscall.so 到 /data/local/tmp/me.zhanghai.android.files/ 再 System.load;Syscall.kt init 失败时回退
4. **交付后 continuation 不恢复的调试**:交付日志打了但超时 — 通过加日志确认 container/binder/pending 均正常、dispatch 正常,实际是早期进程残留干扰;清理旧进程后正常(若复现,先 pkill stellar 进程)
5. **用户服务进程生命周期**:ONE_TIME 语义 — StellarUserServiceMain 解析 reply 的 EXTRA_CLIENT_BINDER 并 linkToDeath,app 退出时进程自动退出("Client app died, exiting user service"),避免僵尸进程

### 验证结果(设备 QV7109BP3E, Android 12)
- 日志链路:`Calling main entry StellarUserServiceMain` → `User service created` → `StellarProviderCompat: Received user service binder (direct delivery)` → `Binder delivered to app provider` → `User service ready`
- UI 可浏览 `/storage/emulated/0/Android/data`(37 个目录)、`/data` 等受保护目录,无超时错误
- app force-stop 后用户服务进程自动退出
- 测试命令:
  - `adb -s QV7109BP3E logcat -c; adb -s QV7109BP3E shell am start -n me.zhanghai.android.files/.filelist.FileListActivity --es "me.zhanghai.android.files.extra.PATH_URI" "file:///storage/emulated/0/Android/data"`
  - `adb -s QV7109BP3E logcat -d | Select-String "StellarUserServiceMain|StellarProviderCompat|delivered|Timed out"`

## 注意事项
- 项目源码注释不得提及"MT 管理器"(用户要求);docs/ 目录是本地逆向笔记,不推送
- 临时 build-*.log 推送前删除
- 设备签名不同需先 uninstall
- killer 的 smali 模板中 `$` 需写成 `${'$'}`(Kotlin 三引号转义),类描述符成员访问用 `#CLASS#->`(#CLASS# 带分号)
- FileListFragment/Activity 有大量原有 "MT Manager style" 注释(历史遗留,勿改)
- Stellar-API 是 GPL/MPL 许可(MPL 2.0 + 原 Shizuku Apache 2.0);BinderContainer vendor 来源已注释
- 若用户服务进程反复启动(每次浏览都新进程 + 15s 超时),先 `adb -s QV7109BP3E shell pkill -f "me.zhanghai.android.files:stellar"` 清理残留再测
