# 聆境 · ABS Native

连接你自己部署的 Audiobookshelf 服务器的 Android 有声书客户端。

## 它是什么

一个轻量原生 Android 应用：Jetpack Compose 界面 + Media3 播放内核。登录服务器后浏览书库与书架、搜索、收听有声书，播放由前台服务承担，因此通知栏、锁屏和蓝牙耳机按键都能直接控制；进度与服务器双向同步，换设备接着听。

支持 Android 8.0（API 26）及以上。

## 它解决了什么

### 公网 / 局域网自动切换地址

- 家里走局域网、出门走公网。Wi-Fi 下自动探测并优先局域网，离开后自动回退，网络切换时按需重建播放流而不打断正在播放的音频。

## 使用方式

**环境**：JDK 17、Android SDK（compileSdk 36），一台 Android 8.0+ 设备或模拟器。

**构建**

```bash
cd android-native
./gradlew :app:assembleDebug # debug
./gradlew :app:assembleRelease # release
```
