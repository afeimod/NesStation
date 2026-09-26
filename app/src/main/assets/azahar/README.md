# 3DS 密钥目录（assets/azahar/）

把 `aes_keys.txt`（3DS AES 密钥表，用于解密加密的 .3ds 卡带镜像）放在本目录，
构建 APK 后应用首次启动会自动安装到 `<filesDir>/azahar/aes_keys.txt`
（见 `NesApp.ensureAzaharKeys()`），加密卡带即可直接运行。

- 缺失时加密 ROM 无法加载 —— AzaharEngine 现在会弹出明确错误提示
  （而非黑屏）。
- 用户手动放入 `<filesDir>/azahar/aes_keys.txt` 的文件优先，不会被覆盖。

密钥文件来源与版权由构建者自行确认；本仓库不随源码分发。
