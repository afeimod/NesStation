此目录用于 Switch（Eden 核心）密钥的 assets 自动识别通道。

把以下文件放进本目录并重新打包 APK，应用启动时（EdenEngine 初始化）
会自动复制到 <filesDir>/eden/keys/，玩家无需任何操作：
  - prod.keys   （必需 —— 解密游戏内容）
  - title.keys  （可选 —— title 票据密钥）

注意：
  - 已存在 <filesDir>/eden/keys/ 中的同名文件不会被覆盖
    （玩家手动导入的版本优先）；
  - 玩家也可在 设置 → 核心设置 → Switch → 密钥与固件 里点
    「选择密钥文件」手动导入（无需重新打包 APK）；
  - 密钥文件受版权保护，请勿将真实密钥提交到公共仓库。
