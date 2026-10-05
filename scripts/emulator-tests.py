"""Run Android tests through a dedicated local emulator when native ADB's home is read-only.

Install the development-only dependency: pip install adb-shell==0.4.4.
The --reset flag clears test data on the emulator, never on a physical device.
"""
import argparse
import pathlib
import re
from adb_shell.adb_device import AdbDeviceTcp

parser = argparse.ArgumentParser()
parser.add_argument("--port", type=int, default=5555)
parser.add_argument("--reset", action="store_true", required=True)
parser.add_argument("--output", default="/tmp/cunqi-instrumentation.txt")
args = parser.parse_args()
repo = pathlib.Path(__file__).resolve().parent.parent
device = AdbDeviceTcp("127.0.0.1", args.port, default_transport_timeout_s=180)
device.connect()
assert device.shell("getprop ro.kernel.qemu").strip() == "1", "Only run on an emulator"
assert device.shell("getprop sys.boot_completed").strip() == "1", "Emulator has not finished booting"
for apk, remote in [(repo / "app/build/outputs/apk/debug/app-debug.apk", "/data/local/tmp/cunqi.apk"),
    (repo / "app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk", "/data/local/tmp/cunqi-test.apk")]:
    print("Installing", apk.name, flush=True)
    device.push(str(apk), remote, read_timeout_s=180)
    result = device.shell("pm install -r " + remote, transport_timeout_s=600, read_timeout_s=600, timeout_s=600)
    assert "Success" in result, result
assert "Success" in device.shell("pm clear com.local.deposittracker"), "Could not reset test data"
chunks = []
for chunk in device.streaming_shell("am instrument -w -r com.local.deposittracker.test/androidx.test.runner.AndroidJUnitRunner",
    transport_timeout_s=1800, read_timeout_s=1800):
    chunks.append(chunk)
    print(chunk, end="", flush=True)
result = "".join(chunks)
pathlib.Path(args.output).write_text(result)
assert re.search(r"OK \(3 tests\)", result), "Instrumentation did not pass all 3 expected tests"
assert "INSTRUMENTATION_CODE: -1" in result, "Instrumentation did not complete normally"
