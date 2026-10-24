#!/usr/bin/env python3
"""Small, bounded local development lanes; no shell interpolation or implicit device reset."""
import argparse
import fnmatch
import os
from pathlib import Path
import signal
import subprocess
import sys
import time
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]


def run(args, timeout=360, capture=False):
    started = time.monotonic()
    p = subprocess.Popen(args, cwd=ROOT, start_new_session=True, text=True,
                         stdout=subprocess.PIPE if capture else None,
                         stderr=subprocess.PIPE if capture else None)
    try:
        stdout, _ = p.communicate(timeout=timeout)
    except subprocess.TimeoutExpired:
        os.killpg(p.pid, signal.SIGTERM)
        try:
            p.communicate(timeout=5)
        except subprocess.TimeoutExpired:
            os.killpg(p.pid, signal.SIGKILL)
            p.communicate()
        raise RuntimeError("Command timed out; no validation result claimed")
    if p.returncode:
        # Captured adb output can contain device/user information; don't echo it.
        raise SystemExit(p.returncode)
    print(f"Local {Path(args[0]).name}: {time.monotonic() - started:.2f}s", flush=True)
    return stdout or ""


def gradle(tasks, args):
    java = Path(os.environ.get("JAVA_HOME", "/nonexistent")) / "bin/javac"
    sdk = Path(os.environ.get("ANDROID_HOME", os.environ.get("ANDROID_SDK_ROOT", "/nonexistent")))
    if not java.is_file() or not (sdk / "platforms/android-37.0/android.jar").is_file():
        raise RuntimeError("Set JAVA_HOME (JDK21) and ANDROID_HOME (API37.0); provision once, separately")
    version = subprocess.run([str(java), "-version"], capture_output=True, text=True, timeout=10)
    if not version.stdout.startswith("javac 21"):
        raise RuntimeError("JAVA_HOME must select JDK21")
    return [str(ROOT / "gradlew"), *tasks, "--dependency-verification=strict",
            "--no-build-cache", "--no-configuration-cache", "-Pkotlin.incremental=false",
            f"--max-workers={args.workers}", "--console=plain", *(["--offline"] if args.offline else [])]


def verify_jvm(folder, selectors):
    cases = []
    for report in folder.glob("TEST-*.xml"):
        suite = ET.parse(report).getroot()
        if any(int(suite.get(k, "0")) for k in ("failures", "errors", "skipped")):
            raise RuntimeError("JVM failure/error/skip; no pass claimed")
        cases.extend((t.get("classname", ""), t.get("name", "")) for t in suite.iter("testcase"))
    if not cases:
        raise RuntimeError("No fresh JVM test cases")
    for selector in selectors:
        if not any(fnmatch.fnmatchcase(c, selector) or fnmatch.fnmatchcase(c + "." + n, selector)
                   for c, n in cases):
            raise RuntimeError("Requested JVM selector did not execute")
    print(f"Fresh local JVM result: {len(cases)} tests, zero failures/errors/skips. Scope: {selectors or 'all JVM'}")


def jvm(args, selectors, debug=False):
    reports = ROOT / "app/build/test-results/testDebugUnitTest"
    # Delete reports only, never classes/intermediates. --rerun affects Test, not its dependencies.
    for report in reports.glob("TEST-*.xml"):
        report.unlink()
    tasks = [":app:testDebugUnitTest", "--rerun"]
    for selector in selectors:
        tasks += ["--tests", selector]
    if debug:
        tasks += [":app:assembleDebug"]
    run(gradle(tasks, args), args.timeout)
    verify_jvm(reports, selectors)


def device(args):
    # These tests mutate AppOps/providers/preferences. Never run against the owner's daily phone.
    if not args.disposable or not args.serial or not args.test:
        raise RuntimeError("Device tests require --disposable --serial SERIAL --test CLASS[#METHOD]; use a test emulator")
    if not all(c.isalnum() or c in "_.$#" for c in args.test):
        raise RuntimeError("Use one fully qualified instrumentation class or method")
    sdk = Path(os.environ.get("ANDROID_HOME", "/nonexistent"))
    adb = [str(sdk / "platform-tools/adb"), "-s", args.serial]
    hardware = run([*adb, "shell", "getprop", "ro.hardware"], 15, True).strip()
    unlocked = run([*adb, "shell", "am", "get-started-user-state", "0"], 15, True).strip()
    if hardware not in ("ranchu", "goldfish") or unlocked != "RUNNING_UNLOCKED":
        raise RuntimeError("A running, unlocked disposable emulator is required; no boot/wipe/provision attempted")
    run(gradle([":app:assembleDebug", ":app:assembleDebugAndroidTest"], args), args.timeout)
    for apk in ("app/build/outputs/apk/debug/app-debug.apk",
                "app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk"):
        run([*adb, "install", "-r", "-t", str(ROOT / apk)], 90, True)
    run([*adb, "shell", "am", "force-stop", "dev.abhik.mockgps"], 15, True)
    result = run([*adb, "shell", "am", "instrument", "-w", "-r", "-e", "class", args.test,
                  "dev.abhik.mockgps.test/androidx.test.runner.AndroidJUnitRunner"], args.timeout, True)
    # adb can return zero for an instrumentation assertion failure; inspect the runner summary.
    import re
    match = re.search(r"OK \((\d+) tests?\)", result)
    if not match or int(match[1]) < 1 or any(s in result for s in ("FAILURES!!!", "INSTRUMENTATION_FAILED", "shortMsg=", "INSTRUMENTATION_STATUS_CODE: -3")):
        raise RuntimeError("Instrumentation failed or executed zero tests; raw device output suppressed")
    print(f"Targeted device smoke: {match[1]} tests; {args.test}. No full-matrix coverage claimed.")


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("lane", nargs="?", default="fast", choices=["fast", "compile", "build", "check", "source", "tasker", "ci", "device", "full", "release"])
    p.add_argument("tests", nargs="*")
    p.add_argument("--offline", action="store_true")
    p.add_argument("--workers", type=int, default=int(os.environ.get("MOCKGPS_DEV_WORKERS", "2")))
    p.add_argument("--timeout", type=int, default=360)
    p.add_argument("--serial")
    p.add_argument("--test")
    p.add_argument("--disposable", action="store_true")
    args = p.parse_args()
    if not 1 <= args.workers <= 32 or not 1 <= args.timeout <= 1800:
        p.error("workers must be 1..32; timeout 1..1800 seconds")
    if args.tests and args.lane not in ("fast", "check"):
        p.error("JVM selectors apply only to fast/check")
    if args.lane in ("fast", "check"):
        if args.lane == "check":
            run([str(ROOT / "scripts/check-source.sh")], args.timeout)
        jvm(args, args.tests or (["*PolicyTest*"] if args.lane == "fast" else []), args.lane == "check")
    elif args.lane in ("compile", "build"):
        run(gradle([":app:compileDebugKotlin" if args.lane == "compile" else ":app:assembleDebug"], args), args.timeout)
    elif args.lane == "source":
        run([str(ROOT / "scripts/check-source.sh")], args.timeout)
    elif args.lane == "tasker":
        run(["python3", "scripts/generate-tasker-project.py"], 30)
        run(["node", "scripts/test-tasker-dispatch.cjs"], 30)
    elif args.lane == "ci":
        run(["python3", "ci/test_plan.py"], 30)
        run(["python3", "scripts/test-dev.py"], 30)
    elif args.lane == "device":
        device(args)
    elif args.lane == "full":
        run([str(ROOT / "scripts/full-validation.sh")], 1800)
    else:
        run(["python3", "ci/release_preflight.py"], 60)


if __name__ == "__main__":
    try:
        main()
    except (RuntimeError, OSError, ET.ParseError) as error:
        print(str(error), file=sys.stderr)
        sys.exit(2)
