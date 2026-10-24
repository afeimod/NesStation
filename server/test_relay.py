#!/usr/bin/env python3
"""快速测试 TCP 中继握手/同步启动协议（本地验证用）

验证点（★ 同步启动门协议）：
  1. 房主（HTTP 创建房间的人）即使后连 TCP 也拿到 host 角色（按 OwnerID 分配，
     不再按连接顺序）；
  2. 双方各自发 ready（带建议 inputDelay）后，服务器【同时】向双方广播 start；
  3. start 携带的最终 inputDelay = max(双方建议, 服务器配置)，两端一致；
  4. ping/pong 时间戳回显（RTT 测量链路）；
  5. input 转发（A 的输入原样到 B，帧号/按键一致）。
"""
import json, socket, threading, time

TCP_HOST, TCP_PORT = "127.0.0.1", 9090
HTTP_HOST, HTTP_PORT = "127.0.0.1", 8080


def client(name, token, room_id, results, suggest_delay, delay_connect=0.0):
    try:
        if delay_connect:
            time.sleep(delay_connect)
        s = socket.create_connection((TCP_HOST, TCP_PORT), timeout=5)
        hello = json.dumps({"type": "hello", "token": token, "roomId": room_id}) + "\n"
        s.sendall(hello.encode())

        state = {"hello": None, "peer_joined": False, "start": None,
                 "pong": None, "peer_ready": False, "remote_input": None}

        def send(obj):
            s.sendall((json.dumps(obj) + "\n").encode())

        # ping RTT 探测（hello 之后任意时刻可发）
        send({"type": "ping", "ts": 123456})

        buf = b""
        deadline = time.time() + 8
        hello_seen = False
        ping_sent = False
        while time.time() < deadline:
            s.settimeout(0.3)
            try:
                d = s.recv(4096)
            except socket.timeout:
                d = None
            if d == b"":
                break
            if d:
                buf += d
            while b"\n" in buf:
                line, buf = buf.split(b"\n", 1)
                if not line.strip():
                    continue
                msg = json.loads(line)
                print(f"[{name}] recv: {msg}")
                t = msg.get("type")
                if t == "hello":
                    state["hello"] = msg.get("msg")
                    if not hello_seen:
                        hello_seen = True
                        # hello 后立即发 ready（模拟引擎加载完）
                        send({"type": "ready", "pad": suggest_delay})
                        # 发一帧输入（对方连接后应收到）
                        send({"type": "input", "frame": 0, "pad": 0x0F0F})
                elif t == "peer_joined":
                    state["peer_joined"] = True
                elif t == "peer_ready":
                    state["peer_ready"] = True
                elif t == "start":
                    state["start"] = msg.get("pad")
                elif t == "pong":
                    state["pong"] = msg.get("ts")
                elif t == "input":
                    state["remote_input"] = (msg.get("frame"), msg.get("pad"))
                if state["start"] is not None and state["pong"] is not None and \
                   state["remote_input"] is not None:
                    results[name] = state
                    return
        results[name] = state
    except Exception as e:
        results[name] = {"error": str(e)}


def main():
    import urllib.request

    def reg(u):
        req = urllib.request.Request(
            f"http://{HTTP_HOST}:{HTTP_PORT}/api/auth/register",
            data=json.dumps({"username": u, "password": "123456"}).encode(),
            headers={"Content-Type": "application/json"})
        return json.load(urllib.request.urlopen(req))["token"]

    ts = str(int(time.time()))
    tok_a = reg("p1_" + ts)
    tok_b = reg("p2_" + ts)
    # A 创建房间 → A 是房主（OwnerID）→ 即使 B 先连 TCP，A 也必须是 host
    req = urllib.request.Request(
        f"http://{HTTP_HOST}:{HTTP_PORT}/api/rooms",
        data=json.dumps({"gameId": "kof97"}).encode(),
        headers={"Content-Type": "application/json", "Authorization": "Bearer " + tok_a})
    room = json.load(urllib.request.urlopen(req))["room"]
    room_id = room["id"]
    print(f"room={room_id}")

    results = {}
    # B（加入者）先连，A（房主）后连 —— 旧版会把先连的 B 当 host（bug）
    t1 = threading.Thread(target=client, args=("B", tok_b, room_id, results, 6, 0.0))
    t2 = threading.Thread(target=client, args=("A", tok_a, room_id, results, 5, 0.4))
    t1.start(); t2.start(); t1.join(); t2.join()
    print("RESULT:", json.dumps(results, ensure_ascii=False, default=str))

    a, b = results.get("A", {}), results.get("B", {})
    # 1. 角色按房主身份分配（A 创建房间 = host），不按连接顺序（B 先连）
    assert a.get("hello") == "host", a
    assert b.get("hello") == "guest", b
    # 2. 双方都收到 start（同步启动门）
    assert a.get("start") is not None and b.get("start") is not None, results
    # 3. 最终延迟 = max(A 建议 5, B 建议 6, 配置) = 6，且两端一致
    assert a["start"] == b["start"], results
    assert a["start"] >= 6, results
    # 4. pong 回显了 ping 的时间戳
    assert a.get("pong") == 123456 and b.get("pong") == 123456, results
    # 5. 对方输入被转发（帧号与按键原样）
    assert b.get("remote_input") == (0, 0x0F0F), b
    assert a.get("remote_input") == (0, 0x0F0F), a
    # 6. 双方都看到了 peer_ready 通知
    assert a.get("peer_ready") and b.get("peer_ready"), results
    print("TCP RELAY TEST PASSED (sync-start protocol)")


if __name__ == "__main__":
    main()
