#!/usr/bin/env python3
"""網路健檢（命令列版）：五項探測 + 自動附加到歷史紀錄檔"""
import socket, time, os, struct, random, statistics, sys
HIST = "/storage/emulated/0/Download/netcheck-history.txt"
TARGETS = [
    ("DNS  ", "dns",  "8.8.8.8",          53),
    ("NTP  ", "ntp",  "216.239.35.4",     123),
    ("STUN ", "stun", "162.159.207.0",    3478),
    ("TCP  ", "tcp",  "1.1.1.1",          443),
    ("GAME ", "tcp",  "43.174.218.121",   20001),
]
def dnsq():
    tid = random.randint(0, 65535)
    hdr = struct.pack('>HHHHHH', tid, 0x0100, 1, 0, 0, 0)
    qn = b''.join(bytes([len(p)]) + p.encode() for p in f"t{os.urandom(3).hex()}.example.com".split('.')) + b'\x00'
    return hdr + qn + struct.pack('>HH', 1, 1)
NTP = b'\x1b' + b'\x00' * 47
def stunp():
    return (1).to_bytes(2,'big') + (0).to_bytes(2,'big') + (0x2112A442).to_bytes(4,'big') + os.urandom(12)
def probe(kind, host, port, t=1.5):
    t0 = time.time()
    if kind == "tcp":
        s = None
        try:
            s = socket.socket(socket.AF_INET, socket.SOCK_STREAM); s.settimeout(3)
            s.connect((host, port)); return (time.time()-t0)*1000
        except Exception: return None
        finally:
            if s: s.close()
    s = None
    try:
        s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM); s.settimeout(t)
        payload = dnsq() if kind == "dns" else (NTP if kind == "ntp" else stunp())
        s.sendto(payload, (host, port)); s.recvfrom(2048)
        return (time.time()-t0)*1000
    except Exception: return None
    finally:
        if s: s.close()
def local_ip():
    try:
        s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM); s.connect(("8.8.8.8", 53))
        ip = s.getsockname()[0]; s.close(); return ip
    except Exception: return "unknown"
def path_of(ip):
    if ip.startswith("10.102."): return "Wi-Fi"
    if ip.startswith("10.") or ip.startswith("100."): return "行動網路"
    return ip
def main():
    dur = int(sys.argv[1]) if len(sys.argv) > 1 else 90
    ip = local_ip()
    res = {n: [] for n, _, _, _ in TARGETS}
    start = time.time(); end = start + dur
    while time.time() < end:
        for n, k, h, p in TARGETS:
            t0 = time.time()
            res[n].append(probe(k, h, p))
            time.sleep(max(0, 0.25 - (time.time() - t0)))
    lines = []
    for n, _, h, p in TARGETS:
        v = res[n]; ok = [x for x in v if x is not None]
        fail = len(v) - len(ok)
        rate = 100 * fail / len(v) if v else 0
        p50 = f"{statistics.median(ok):.0f}ms" if ok else "-"
        mx = f"{max(ok):.0f}ms" if ok else "-"
        lines.append(f"{n} {rate:5.1f}%  p50={p50:>7} max={mx:>7}")
    ts = time.strftime("%Y-%m-%d %H:%M:%S", time.localtime(start))
    head = f"[{ts}] {dur}秒 ｜ 線路 {path_of(ip)} ({ip}) ｜ " + " / ".join(
        f"{n.strip()} {100*sum(1 for x in res[n] if x is None)/len(res[n]):.1f}%" for n, _, _, _ in TARGETS)
    block = head + "\n  " + "\n  ".join(lines) + "\n"
    with open(HIST, "a", encoding="utf-8") as f:
        f.write(block + "\n")
    print(block)
    print(f"已附加到：{HIST}")
main()
