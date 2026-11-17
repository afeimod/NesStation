package main

import (
	"crypto/hmac"
	"crypto/sha256"
	"encoding/base64"
	"encoding/json"
	"fmt"
	"strings"
	"time"
)

// jwt 轻量 JWT 实现（HS256），零第三方依赖
type jwt struct {
	secret []byte
	ttl    time.Duration
}

type jwtClaims struct {
	Sub      string `json:"sub"`      // user id
	Username string `json:"username"` // 用户名
	Exp      int64  `json:"exp"`      // 过期时间（unix 秒）
}

func newJWT(secret string, ttl time.Duration) *jwt {
	return &jwt{secret: []byte(secret), ttl: ttl}
}

func (j *jwt) sign(claims jwtClaims) string {
	claims.Exp = time.Now().Add(j.ttl).Unix()
	header := base64.RawURLEncoding.EncodeToString([]byte(`{"alg":"HS256","typ":"JWT"}`))
	payload, _ := json.Marshal(claims)
	p := base64.RawURLEncoding.EncodeToString(payload)
	sig := j.signature(header + "." + p)
	return header + "." + p + "." + sig
}

func (j *jwt) signature(data string) string {
	mac := hmac.New(sha256.New, j.secret)
	mac.Write([]byte(data))
	return base64.RawURLEncoding.EncodeToString(mac.Sum(nil))
}

// verify 校验 token，返回 claims
func (j *jwt) verify(token string) (*jwtClaims, error) {
	parts := strings.Split(token, ".")
	if len(parts) != 3 {
		return nil, fmt.Errorf("token 格式错误")
	}
	header, payload, sig := parts[0], parts[1], parts[2]
	expected := j.signature(header + "." + payload)
	if !hmac.Equal([]byte(expected), []byte(sig)) {
		return nil, fmt.Errorf("token 签名无效")
	}
	payloadBytes, err := base64.RawURLEncoding.DecodeString(payload)
	if err != nil {
		return nil, fmt.Errorf("token payload 解析失败")
	}
	var claims jwtClaims
	if err := json.Unmarshal(payloadBytes, &claims); err != nil {
		return nil, fmt.Errorf("token payload 解析失败")
	}
	if claims.Exp < time.Now().Unix() {
		return nil, fmt.Errorf("token 已过期")
	}
	return &claims, nil
}
