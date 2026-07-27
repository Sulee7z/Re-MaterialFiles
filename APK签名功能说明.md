# 新增功能：APK 解包 / 重签名（MT 管理器风格）

本次改动在 Material Files（Sora-Editor 版）基础上，新增了 **MT 管理器**里常见的
「APK 签名 / 重签名」能力，并说明了已有的「APK 解包」能力如何使用。

---

## 一、功能概览

1. **APK 重签名（新增）**
   - 在文件列表中，点击某个 `.apk` 文件右侧的「⋮」菜单，会看到新增的 **「签名 APK」** 项。
   - 弹窗提供两种签名方式：
     - **内置测试签名**（默认，一键即可）：使用随应用打包的测试密钥重新签名，
       适合自改包、去校验、调试安装等场景。等价于 MT 管理器的「测试签名」。
     - **自定义密钥库**：填入自己的密钥库路径、密钥库密码、别名、密钥密码，
       用正式签名给 APK 签名。
   - 签名采用 Google 官方 **apksig** 库，同时写入 **v1（JAR）+ v2 + v3** 三种签名方案，
     并自动做 zip 对齐（zipalign）。
   - 结果输出到源文件同目录，命名为 `原名_signed.apk`（若已存在则自动追加序号），
     不覆盖原文件，安全可回退。签名完成会有 Toast 提示。

2. **APK 解包 / 查看（已内置，说明用法）**
   - 本项目本就把 `.apk` 当作压缩包处理。点击 APK →「查看」即可像浏览文件夹一样
     进入 APK 内部，查看 `AndroidManifest.xml`、`classes.dex`、`res/`、`assets/` 等。
   - 在「⋮」菜单里选择 **「提取」** 可把 APK 内容解包到当前目录（即解包成文件夹）。
   - 因此「解包」无需额外开发，配合上面的「重签名」即可完成
     「解包 → 改内容 → 重新打包 → 重签名 → 安装」的完整链路。

---

## 二、改动的文件清单

### 新增文件

| 文件 | 作用 |
|------|------|
| `app/src/main/java/me/zhanghai/android/files/filejob/ApkSigning.kt` | 核心签名逻辑：加载测试密钥/自定义密钥库，调用 apksig 完成 v1/v2/v3 签名。含 `ApkSigningKeySpec`（签名方式，Parcelable）、`ApkSigningKey`、`ApkSigning` 对象。 |
| `app/src/main/java/me/zhanghai/android/files/filelist/SignApkDialogFragment.kt` | 「签名 APK」弹窗，选择内置测试签名 / 自定义密钥库并收集参数。 |
| `app/src/main/res/layout/sign_apk_dialog.xml` | 上述弹窗的布局（单选 + 密钥库输入框）。 |
| `app/src/main/assets/sign/testkey.pk8` | 内置测试密钥的私钥（PKCS8 DER）。 |
| `app/src/main/assets/sign/testkey.x509.pem` | 内置测试密钥的自签名证书（X.509 PEM）。 |

### 修改文件

| 文件 | 改动 |
|------|------|
| `app/build.gradle` | 新增依赖 `com.android.tools.build:apksig:8.7.3`。 |
| `app/src/main/java/.../filejob/FileJobs.kt` | 新增 `SignApkJob`（后台签名任务）与通知辅助函数；补 `newInputStream` 导入。 |
| `app/src/main/java/.../filejob/FileJobService.kt` | 新增 `signApk(file, keySpec, context)` 启动入口。 |
| `app/src/main/java/.../filelist/FileListAdapter.kt` | 列表项菜单显示/点击「签名 APK」；`Listener` 新增 `showSignApkDialog`。 |
| `app/src/main/java/.../filelist/FileListFragment.kt` | 实现 `SignApkDialogFragment.Listener`，接入弹窗与后台任务。 |
| `app/src/main/res/menu/file_item.xml` | 新增 `action_sign_apk` 菜单项。 |
| `app/src/main/res/values/strings.xml`、`values-zh-rCN`、`values-zh-rTW` | 新增全部相关文案（英文/简体/繁体）。 |

---

## 三、如何编译

本项目为标准 Android Gradle 工程，需在装有 Android SDK 的环境构建（我这边的沙箱
无法运行完整 Android 构建，故仅交付源码改动）：

```bash
# 在项目根目录
./gradlew :app:assembleGithubDebug   # 或按 build.gradle 里定义的 variant
# 产物在 app/build/outputs/apk/ 下
```

编译前请确认能访问 `google()` Maven 仓库（apksig 由 Google 官方托管）。

---

## 四、使用步骤（重签名）

1. 进入任意包含 `.apk` 的目录。
2. 点击该 APK 右侧「⋮」→ **签名 APK**。
3. 选择签名方式：
   - **内置测试签名**：直接点「签名」。
   - **自定义密钥库**：填写路径与密码等后点「签名」。
4. 稍候，任务栏通知显示「正在签名…」，完成后弹出「已签名为 xxx_signed.apk」。
5. 在同目录得到 `xxx_signed.apk`，长按可直接安装。

---

## 五、注意事项与已知限制

- **内置测试密钥仅供个人调试**：它是随包生成的一把测试证书（我已用 openssl 现场生成，
  有效期 100 年），任何拿到本 APK 的人都能用同一把 key 签名，因此**不要**用它签发对外
  正式发布的应用。正式发布请用「自定义密钥库」。
- **自定义密钥库格式**：Android 系统原生只支持 **PKCS12（.p12/.pfx）** 与 **BKS**，
  代码会自动依次尝试这两种类型。若你手上是 **JKS**（旧版 keytool 生成的 `.jks/.keystore`），
  请先转换成 PKCS12 再使用：
  ```bash
  keytool -importkeystore -srckeystore my.jks -destkeystore my.p12 -deststoretype PKCS12
  ```
- **密钥库路径**：当前弹窗需手动输入密钥库的绝对路径（例如
  `/storage/emulated/0/keys/my.p12`）。如需「浏览选择」按钮，可作为后续增强。
- **重签名原理**：apksig 会自动移除原有 `META-INF` 旧签名再重新签名，因此对已签名的
  APK 也能直接「重签名」。
- 输出文件不覆盖原 APK，保证可回退。

---

## 六、后续可扩展方向（未实现，供参考）

- 多选批量签名；
- 密钥库「浏览选择」按钮与密钥别名下拉列表；
- 在文件属性里显示 APK 的包名/版本/签名指纹（MD5/SHA-256）；
- 记住上次使用的自定义密钥库参数。
