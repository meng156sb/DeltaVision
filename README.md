# DeltaVision

DeltaVision 是一个 Android + PC 的完整采集闭环工程：
- root 手机抓取《三角洲行动》横屏画面
- 只处理中心 ROI
- 手机端实时检测、叠框和伪标签采集
- 通过同一 Wi‑Fi 上传到本地 Collector
- 电脑端复核并导出 YOLO 数据集

仓库地址：
- https://github.com/meng156sb/DeltaVision

## 主要能力

### Android 端
- root `screencap -p` 抓帧
- 中心 ROI 裁剪
- Kotlin + ONNX Runtime 端上推理
- 运行时叠框显示
- 冷启动空框采集
- 采集队列、断网重试、局域网同步

### Collector 端
- 接收 ROI 图片、metadata、detections、review status
- SQLite 入库与去重
- 浏览器可视化复核框
- YOLO 数据集导出

## 本地构建

### Debug APK
```powershell
cd D:\梦梦鸭\工作区\DeltaVision
$env:ANDROID_SDK_ROOT='C:\Users\meng\AppData\Local\Android\Sdk'
$env:GRADLE_USER_HOME='D:\梦梦鸭\工作区\_gradle_user_home'
.\gradlew.bat assembleDebug --console=plain
```

### 签名 Release APK
当前 release 构建需要以下环境变量或同名 Gradle properties：
- `ANDROID_KEYSTORE_BASE64`
- `ANDROID_KEYSTORE_PASSWORD`
- `ANDROID_KEY_ALIAS`
- `ANDROID_KEY_PASSWORD`

也支持在本地额外传入：
- `ANDROID_KEYSTORE_FILE`

示例：
```powershell
cd D:\梦梦鸭\工作区\DeltaVision
$env:ANDROID_SDK_ROOT='C:\Users\meng\AppData\Local\Android\Sdk'
$env:GRADLE_USER_HOME='D:\梦梦鸭\工作区\_gradle_user_home'
$env:ANDROID_KEYSTORE_FILE='C:\path\to\release.keystore'
$env:ANDROID_KEYSTORE_PASSWORD='***'
$env:ANDROID_KEY_ALIAS='***'
$env:ANDROID_KEY_PASSWORD='***'
.\gradlew.bat assembleRelease --console=plain
```

输出路径：
- `D:\梦梦鸭\工作区\DeltaVision\app\build\outputs\apk\debug\app-debug.apk`
- `D:\梦梦鸭\工作区\DeltaVision\app\build\outputs\apk\release\app-release.apk`

## GitHub Actions 用法

工作流文件：
- `D:\梦梦鸭\工作区\DeltaVision\.github\workflows\android-release.yml`

### 1. 配置 GitHub Secrets
在 GitHub 仓库 `Settings -> Secrets and variables -> Actions` 中添加：
- `ANDROID_KEYSTORE_BASE64`
- `ANDROID_KEYSTORE_PASSWORD`
- `ANDROID_KEY_ALIAS`
- `ANDROID_KEY_PASSWORD`

其中 `ANDROID_KEYSTORE_BASE64` 需要是你的 release keystore 文件的 Base64 文本。

PowerShell 生成 Base64 示例：
```powershell
[Convert]::ToBase64String([IO.File]::ReadAllBytes('C:\path\to\release.keystore')) | Set-Clipboard
```

### 2. 触发工作流
- 推送到 `main` 会自动触发
- 也可以在 GitHub 仓库的 `Actions -> Android Release -> Run workflow` 手动触发

### 3. 下载 APK
- 打开对应的 Actions 运行记录
- 在页面底部下载 artifact：`DeltaVision-release-apk`
- 解压后得到 `app-release.apk`

如果任一 Secret 缺失，工作流会在前置检查阶段直接失败，并明确提示缺少哪一项。

## Collector 运行

```powershell
cd D:\梦梦鸭\工作区\DeltaVision\collector
python -m venv .venv
.venv\Scripts\activate
pip install -r requirements.txt
$env:DELTA_VISION_COLLECTOR_TOKEN='delta-token'
python app.py
```

打开：
- http://127.0.0.1:8000

## 手机模型放置

当前 Android 端加载的是 ONNX 模型：
```text
/sdcard/Android/data/com.deltavision.app/files/models/model.onnx
```

建议：
- 先手工标注 `300-800` 张中心 ROI 图训练首版
- 导出 `448x448` 的单类 `person_body` ONNX
- 重命名为 `model.onnx` 后拷到手机目录

## 冷启动采集与一键训练

### 没有模型时
1. 电脑启动 Collector
2. 手机安装 APK，并配置 Collector 地址和 token
3. 勾选 App 里的 `Upload empty ROI frames when model is missing`
4. 不放 `model.onnx`，直接进入游戏点击 `Start`
5. 电脑端打开 Collector，手工画框后点“人工通过”
6. 点“导出 YOLO 数据集”
7. 运行下面的一键脚本：

```powershell
cd D:\梦梦鸭\工作区\DeltaVision
powershell -ExecutionPolicy Bypass -File .\tools\retrain_model.ps1 -Epochs 80 -PushToPhone
```

### 脚本会自动完成
- 创建 `D:\梦梦鸭\工作区\DeltaVision\.venv-train`
- 安装训练依赖
- 训练 YOLO 模型
- 导出 `D:\梦梦鸭\工作区\DeltaVision\models\trained\person_body\model.onnx`
- 自动推送到手机模型目录

## 实际使用步骤

### 有初始模型时
1. 电脑启动 Collector
2. 手机安装 APK
3. 手机上授予悬浮窗、使用情况访问和 root 权限
4. 在 App 中填写 Collector 地址和 token
5. 把 `model.onnx` 放入手机指定目录
6. 进入游戏，启动识别服务
7. 在电脑端复核样本并导出训练集
8. 运行 `tools\retrain_model.ps1` 训练并回推新模型

## 已验证
- `assembleDebug` 可通过
- `assembleRelease` 可通过
- `tools\retrain_model.ps1` 已通过 PowerShell 语法检查
- Collector Python 代码可通过语法检查
