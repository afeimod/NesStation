package main

import (
	"crypto/rand"
	"crypto/subtle"
	"crypto/sha256"
	"encoding/base64"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"os"
	"path/filepath"
	"sync"
	"time"
)

// User 虚拟账号
type User struct {
	ID        string    `json:"id"`
	Username  string    `json:"username"`
	Password  string    `json:"password"` // 加盐哈希
	Salt      string    `json:"salt"`
	Nickname  string    `json:"nickname"`
	CreatedAt time.Time `json:"createdAt"`
}

// userStore 用户数据存储（JSON 文件持久化，内存锁保护）
type userStore struct {
	mu    sync.RWMutex
	path  string
	users map[string]*User // id -> user
	byName map[string]string // username -> id
}

func newUserStore(dataDir string) (*userStore, error) {
	if err := os.MkdirAll(dataDir, 0o755); err != nil {
		return nil, fmt.Errorf("创建数据目录失败: %w", err)
	}
	s := &userStore{
		path:   filepath.Join(dataDir, "users.json"),
		users:  map[string]*User{},
		byName: map[string]string{},
	}
	if data, err := os.ReadFile(s.path); err == nil {
		var list []*User
		if err := json.Unmarshal(data, &list); err == nil {
			for _, u := range list {
				s.users[u.ID] = u
				s.byName[u.Username] = u.ID
			}
		}
	}
	return s, nil
}

func (s *userStore) save() error {
	s.mu.RLock()
	list := make([]*User, 0, len(s.users))
	for _, u := range s.users {
		list = append(list, u)
	}
	s.mu.RUnlock()
	data, err := json.MarshalIndent(list, "", "  ")
	if err != nil {
		return err
	}
	tmp := s.path + ".tmp"
	if err := os.WriteFile(tmp, data, 0o644); err != nil {
		return err
	}
	return os.Rename(tmp, s.path)
}

// register 注册新账号。用户名唯一。
func (s *userStore) register(username, password string) (*User, error) {
	if len(username) < 2 || len(username) > 20 {
		return nil, fmt.Errorf("用户名长度需在 2-20 个字符之间")
	}
	if len(password) < 4 {
		return nil, fmt.Errorf("密码至少 4 个字符")
	}

	s.mu.Lock()
	if _, exists := s.byName[username]; exists {
		s.mu.Unlock()
		return nil, fmt.Errorf("用户名已被注册")
	}

	salt := randomSalt()
	hash := hashPassword(password, salt)
	u := &User{
		ID:        randomID(),
		Username:  username,
		Password:  hash,
		Salt:      salt,
		Nickname:  username,
		CreatedAt: time.Now().UTC(),
	}
	s.users[u.ID] = u
	s.byName[u.Username] = u.ID
	s.mu.Unlock()

	if err := s.save(); err != nil {
		// 回滚内存状态
		s.mu.Lock()
		delete(s.users, u.ID)
		delete(s.byName, u.Username)
		s.mu.Unlock()
		return nil, fmt.Errorf("保存用户失败: %w", err)
	}
	return u, nil
}

// verify 校验用户名密码，成功返回用户
func (s *userStore) verify(username, password string) (*User, error) {
	s.mu.RLock()
	defer s.mu.RUnlock()
	id, ok := s.byName[username]
	if !ok {
		return nil, fmt.Errorf("用户名或密码错误")
	}
	u := s.users[id]
	expected, err := base64.StdEncoding.DecodeString(u.Password)
	if err != nil {
		return nil, fmt.Errorf("用户名或密码错误")
	}
	actual, err := base64.StdEncoding.DecodeString(hashPassword(password, u.Salt))
	if err != nil {
		return nil, fmt.Errorf("用户名或密码错误")
	}
	if subtle.ConstantTimeCompare(expected, actual) != 1 {
		return nil, fmt.Errorf("用户名或密码错误")
	}
	return u, nil
}

func (s *userStore) byID(id string) *User {
	s.mu.RLock()
	defer s.mu.RUnlock()
	return s.users[id]
}

// Close 关闭时保存
func (s *userStore) Close() error {
	return s.save()
}

// randomSalt 生成随机盐
func randomSalt() string {
	b := make([]byte, 16)
	_, _ = rand.Read(b)
	return hex.EncodeToString(b)
}

// hashPassword 加盐哈希：HMAC-SHA256 迭代 10000 次
func hashPassword(password, salt string) string {
	key := []byte(salt + ":" + password)
	h := key
	for i := 0; i < 10000; i++ {
		sum := sha256.Sum256(append(h, key...))
		h = sum[:]
	}
	return base64.StdEncoding.EncodeToString(h)
}

// randomID 生成随机 16 字节 hex ID
func randomID() string {
	b := make([]byte, 16)
	_, _ = rand.Read(b)
	return hex.EncodeToString(b)
}
