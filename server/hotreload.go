package main

import (
	"bytes"
	"crypto/sha256"
	"log"
	"os"
	"sync/atomic"
	"time"
)

// ---------------------------------------------------------------------------
// configStore —— 并发安全的"当前配置"持有器
// ---------------------------------------------------------------------------
//
// 热重载的落点：apiServer / relayHub 一律通过 get() 取最新配置；
// 热替换时整指针原子交换（copy-on-write），旧 *Config 永不原地改字段，
// 正在处理的请求继续引用旧配置读完为止 —— 无锁、无数据竞争。
type configStore struct {
	ptr atomic.Pointer[Config]
}

func newConfigStore(cfg *Config) *configStore {
	s := &configStore{}
	s.ptr.Store(cfg)
	return s
}

// get 返回当前配置快照。快照视为不可变（解析后不再改字段），
// 调用方可放心长期持有（如 ROM 下载中途换配置也不影响本次下载）。
func (s *configStore) get() *Config { return s.ptr.Load() }

// store 原子替换当前配置
func (s *configStore) store(cfg *Config) { s.ptr.Store(cfg) }

// ---------------------------------------------------------------------------
// configWatcher —— config.json 变动监视器（轮询，仅标准库）
// ---------------------------------------------------------------------------
//
// 为什么轮询而不是 inotify/fsnotify：
//  1. 项目坚持零第三方依赖；inotify 需要 syscall 封装，且对"编辑器写
//     临时文件再 rename 原子替换"这类保存方式有一堆坑（rename 后监听丢失）。
//  2. config.json 只有几 KB，每秒读一次 + SHA-256 对比的 CPU 开销可忽略。
//  3. 轮询对 NFS / 容器挂载卷 / WSL 等场景一律有效。
type configWatcher struct {
	path     string
	interval time.Duration
	events   chan<- *Config // 解析成功的新配置（发给主循环决策）
	kick     chan struct{}  // SIGHUP：立即检查一次（不等下一个轮询周期）
	done     chan struct{}  // 关闭 watcher（进程自动重启前）
	last     [32]byte       // 上次读到的文件内容哈希
	lastErr  string         // 上次读取错误（日志去重用）
}

func newConfigWatcher(path string, events chan<- *Config) *configWatcher {
	w := &configWatcher{
		path:     path,
		interval: time.Second,
		events:   events,
		kick:     make(chan struct{}, 1),
		done:     make(chan struct{}),
	}
	// 用启动时的文件内容播种哈希：内容没变就不触发（避免启动即空事件）
	if data, err := os.ReadFile(path); err == nil {
		w.last = sha256.Sum256(data)
	}
	return w
}

// check 单次检查：读文件 → 内容哈希对比 → 解析校验 → 发事件。
// 新配置无效（写了一半 / 语法错误 / 校验不过）时只告警、不动现网配置，
// 等文件再次变化时重试。
func (w *configWatcher) check() {
	data, err := os.ReadFile(w.path)
	if err != nil {
		if err.Error() != w.lastErr {
			log.Printf("[热重载] 读取 %s 失败：%v（继续用当前配置运行）", w.path, err)
			w.lastErr = err.Error()
		}
		return
	}
	w.lastErr = ""
	sum := sha256.Sum256(data)
	if bytes.Equal(sum[:], w.last[:]) {
		return // 内容没变
	}
	// 记住新哈希（即使解析失败，也不对同一份坏内容重复告警）
	w.last = sum

	cfg, err := parseConfig(data, w.path)
	if err != nil {
		log.Printf("[热重载] 新配置无效，保持旧配置继续运行：%v", err)
		return
	}
	select {
	case w.events <- cfg:
	case <-w.done:
	}
}

func (w *configWatcher) run() {
	ticker := time.NewTicker(w.interval)
	defer ticker.Stop()
	for {
		select {
		case <-w.done:
			return
		case <-ticker.C:
			w.check()
		case <-w.kick:
			w.check()
		}
	}
}

func (w *configWatcher) stop() { close(w.done) }

// reloadNow 立即触发一次检查（不等轮询周期）
func (w *configWatcher) reloadNow() {
	select {
	case w.kick <- struct{}{}:
	default:
	}
}
