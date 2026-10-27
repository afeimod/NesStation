//go:build windows

package main

// ★★ v4.7.1 Windows 控制台体验（仅标准库 syscall，零第三方依赖）：
//   1. 控制台输出切 UTF-8（默认 GBK 控制台下中文日志全是乱码）；
//   2. 启动失败暂停等回车 —— 双击运行的窗口不再"一闪而过"，
//      失败原因（缺配置 / 端口被占用 / 配置语法错）看得见。

import (
	"bufio"
	"fmt"
	"os"
	"syscall"
)

func init() {
	// SetConsoleOutputCP(65001)：控制台按 UTF-8 解释输出字节。
	// 无控制台（服务/计划任务）时调用失败也无妨（忽略错误）。
	kernel32 := syscall.NewLazyDLL("kernel32.dll")
	setConsoleOutputCP := kernel32.NewProc("SetConsoleOutputCP")
	_, _, _ = setConsoleOutputCP.Call(65001)
}

// pauseIfInteractive 交互式控制台（双击运行）时暂停等回车再退出；
// 无控制台（Windows 服务 / 重定向管道）时不阻塞直接返回。
func pauseIfInteractive() {
	st, err := os.Stdin.Stat()
	if err != nil || st.Mode()&os.ModeCharDevice == 0 {
		return // 非交互（服务/重定向）：不暂停
	}
	fmt.Print("\n按回车键退出...")
	_, _ = bufio.NewReader(os.Stdin).ReadString('\n')
}
