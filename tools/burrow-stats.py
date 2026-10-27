#!/usr/bin/env python3
"""Burrow's per-syscall statistics (the -s file), from the debug app over adb.

    tools/burrow-stats.py on       # count from the next start of the container
    tools/burrow-stats.py reset    # zero the counts
    tools/burrow-stats.py [N]      # the N syscalls taking the most time (20)
    tools/burrow-stats.py off

"total" is the whole handler, stack switching included; "emulated" the
emulation alone. The kernel's signal delivery and return are not in either.
"""
import os
import re
import struct
import subprocess
import sys

PKG = os.environ.get("DN_PKG", "app.droidnux.debug")
ADB = os.environ.get("ADB", "adb")
FILE = os.environ.get("DN_STATS", "files/burrow-stats")
SYSCALLS = 512
MAGIC = 0x31737473776F7262


def adb(command, **kw):
    return subprocess.run([ADB, "exec-out", "run-as %s sh -c '%s'" % (PKG, command)], capture_output=True, **kw)


def names():
    header = os.path.join(os.path.dirname(__file__), "../container/src/main/cpp/burrow/src/burrow.h")
    found = {int(n): name for name, n in re.findall(r"NR_(\w+) = (\d+)", open(header).read())}
    found.update({61: "getdents64", 46: "ftruncate", 98: "futex", 500: "(path probe)", 501: "(path walk)", 502: "(direct open)"})
    return found


def show(top):
    data = adb("cat %s" % FILE).stdout
    if len(data) < 16 + 3 * 8 * SYSCALLS:
        sys.exit("no statistics: tools/burrow-stats.py on, then restart the container")
    magic, frequency = struct.unpack_from("<QQ", data)
    if magic != MAGIC:
        sys.exit("no statistics yet")
    count, total, emulated = (struct.unpack_from("<%dQ" % SYSCALLS, data, 16 + i * 8 * SYSCALLS) for i in range(3))
    us = 1e6 / frequency
    label = names()
    rows = sorted((i for i in range(SYSCALLS) if count[i]), key=lambda i: -total[i])
    traps = sum(count[:500])  # past 500: Burrow's own steps, inside the traps
    print("%d traps, %.1f ms in the handler (%.1f ms emulating)" %
          (traps, sum(total[:500]) * us / 1000, sum(emulated[:500]) * us / 1000))
    print("%-18s %10s %10s %9s %9s" % ("syscall", "traps", "total ms", "us/trap", "emul us"))
    for i in rows[:top]:
        print("%-18s %10d %10.1f %9.2f %9.2f" % (label.get(i, str(i)), count[i], total[i] * us / 1000,
                                                 total[i] * us / count[i], emulated[i] * us / count[i]))


arg = sys.argv[1] if len(sys.argv) > 1 else "20"
if arg == "on":
    adb("touch %s" % FILE)
elif arg == "reset":
    # Zeroed in place, past the header: guests have it mapped, a shorter file would be SIGBUS for them.
    adb("dd if=/dev/zero of=%s bs=8 seek=2 count=%d conv=notrunc 2>/dev/null" % (FILE, 3 * SYSCALLS))
elif arg == "off":
    adb("rm -f %s" % FILE)
else:
    show(int(arg))
