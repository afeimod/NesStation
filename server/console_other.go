//go:build !windows

package main

// pauseIfInteractive 非 Windows 平台不暂停：服务器/容器/终端场景下
// 日志与退出码已经完全可见（"闪退看不到原因"是 Windows 双击运行的特有现象）。
func pauseIfInteractive() {}
