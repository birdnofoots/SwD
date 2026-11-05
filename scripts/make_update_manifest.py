#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""生成 SWD 的更新清单 swd-update.json。

App 检查更新时读它, 结构与 App 侧的 NewVersion 一致:
    {versionCode, versionName, downloadUrl, fileSize, versionLogs:[{name,code,desc}]}

版本号来源优先级:
  1) --version-code / --version-name 显式传入
  2) 从 APK 里读(aapt / apkanalyzer)
  3) 从 build.gradle.kts 的 gkdVersionCode + git 短 sha 推(与 Gradle 内部 GitInfo 一致)
"""
import argparse
import json
import os
import re
import shutil
import subprocess
import sys


def run(cmd):
    try:
        r = subprocess.run(cmd, capture_output=True, text=True, timeout=60)
        return r.stdout if r.returncode == 0 else ""
    except Exception:
        return ""


def from_apk(apk):
    """从 APK 里读 versionName / versionCode; 读不到返回 (None, None)。"""
    if not apk or not os.path.isfile(apk):
        return None, None
    sdk = os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT") or ""
    aapts = []
    if sdk:
        bt = os.path.join(sdk, "build-tools")
        if os.path.isdir(bt):
            for d in sorted(os.listdir(bt), reverse=True):
                aapts.append(os.path.join(bt, d, "aapt"))
    aapts += [shutil.which("aapt"), shutil.which("aapt2")]
    for aapt in aapts:
        if not aapt or not os.path.isfile(aapt):
            continue
        out = run([aapt, "dump", "badging", apk])
        m = re.search(r"versionCode='(\d+)'", out)
        v = re.search(r"versionName='([^']+)'", out)
        if m:
            return (v.group(1) if v else None), int(m.group(1))
    # apkanalyzer
    analyzer = None
    if sdk:
        analyzer = os.path.join(sdk, "cmdline-tools", "latest", "bin", "apkanalyzer")
    analyzer = analyzer if analyzer and os.path.isfile(analyzer) else shutil.which("apkanalyzer")
    if analyzer:
        name = run([analyzer, "manifest", "version-name", apk]).strip()
        code = run([analyzer, "manifest", "version-code", apk]).strip()
        if code.isdigit():
            return (name or None), int(code)
    return None, None


def from_gradle(root):
    """按 Gradle 的算法复现: gkdVersionCode + ("-" + commit sha 前 7 位)。"""
    build_file = os.path.join(root, "build.gradle.kts")
    if not os.path.isfile(build_file):
        return None, None
    text = open(build_file, encoding="utf-8").read()
    m = re.search(r'gkdVersionCode"\]\s*=\s*(\d+)', text)
    if not m:
        return None, None
    code = int(m.group(1))
    base = re.search(r'gkdVersionName"\]\s*=\s*"([^"]+)"', text)
    name = base.group(1) if base else "swd"
    sha = run(["git", "-C", root, "rev-parse", "HEAD"]).strip()
    if sha and not re.search(r"-\d{7}$", name):
        name = "%s-%s" % (name, sha[:7])
    return name, code


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--apk")
    ap.add_argument("--output", required=True)
    ap.add_argument("--download-url", required=True)
    ap.add_argument("--desc", default="SWD 自有构建")
    ap.add_argument("--gradle-root", default=".")
    ap.add_argument("--version-code", type=int)
    ap.add_argument("--version-name")
    args = ap.parse_args()

    name, code = from_apk(args.apk)
    if code is None:
        name, code = from_gradle(args.gradle_root)
    if args.version_code is not None:
        code = args.version_code
    if args.version_name:
        name = args.version_name
    if code is None or not name:
        sys.exit("无法确定版本号: 请显式传 --version-code / --version-name")

    size = os.path.getsize(args.apk) if args.apk and os.path.isfile(args.apk) else 0
    manifest = {
        "versionCode": code,
        "versionName": name,
        "downloadUrl": args.download_url,
        "fileSize": size,
        "versionLogs": [{"name": name, "code": code, "desc": args.desc}],
    }
    with open(args.output, "w", encoding="utf-8") as f:
        json.dump(manifest, f, ensure_ascii=False, indent=2)
        f.write("\n")
    print(json.dumps(manifest, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
