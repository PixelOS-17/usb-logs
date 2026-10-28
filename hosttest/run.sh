#!/bin/bash
# Host run of capture.sh under mksh, with device paths moved into a fake root and
# Android-only commands stubbed. Prints each step and fails on the first broken check.
set -u

here=$(cd "$(dirname "$0")" && pwd)
fake=$here/fake
bin=$here/bin
rm -rf "$fake" "$bin"
mkdir -p "$bin" "$fake/sys/class/udc/a600000.dwc3" "$fake/sys/class/dual_role_usb/otg_default" \
	"$fake/sys/class/power_supply/usb" "$fake/sys/class/power_supply/battery" \
	"$fake/sys/class/thermal/thermal_zone0" "$fake/sys/fs/pstore" "$fake/sys/fs/cgroup" \
	"$fake/config/usb_gadget/g1/configs/b.1" "$fake/config/usb_gadget/g1/functions/accessory.gs2" \
	"$fake/config/usb_gadget/g1/functions/ffs.adb" "$fake/data/local/tmp" "$fake/data/tombstones" \
	"$fake/data/anr" "$fake/dev"

echo addressed > "$fake/sys/class/udc/a600000.dwc3/state"
echo high-speed > "$fake/sys/class/udc/a600000.dwc3/current_speed"
echo a600000.dwc3 > "$fake/config/usb_gadget/g1/UDC"
echo 0x18d1 > "$fake/config/usb_gadget/g1/idVendor"
echo 0x2d01 > "$fake/config/usb_gadget/g1/idProduct"
echo ufp > "$fake/sys/class/dual_role_usb/otg_default/mode"
echo 1 > "$fake/sys/class/power_supply/usb/online"
mkdir "$fake/sys/class/power_supply/usb/typec_mode"   # exists but read() fails (EISDIR)
echo 87 > "$fake/sys/class/power_supply/battery/capacity"
echo cpu-0-0 > "$fake/sys/class/thermal/thermal_zone0/type"
echo 41000 > "$fake/sys/class/thermal/thermal_zone0/temp"
echo "kernel console of the previous boot" > "$fake/sys/fs/pstore/console-ramoops-0"
echo "tombstone text" > "$fake/data/tombstones/tombstone_03"
echo "tombstone proto" > "$fake/data/tombstones/tombstone_03.pb"
echo "anr trace" > "$fake/data/anr/anr_2026-10-28"
ln -s ../../functions/accessory.gs2 "$fake/config/usb_gadget/g1/configs/b.1/function0"
ln -s ../../functions/ffs.adb "$fake/config/usb_gadget/g1/configs/b.1/function1"
echo '[sys.usb.state]: [accessory,adb]' > "$fake/props"
echo boot-a > "$fake/boot_id"

ln -s "$here/mksh/mksh" "$bin/sh"
cat > "$bin/logcat" <<EOF
#!/bin/sh
case " \$* " in
*" -d "*) echo "logcat dump: \$*"; exit 0 ;;
esac
while [ \$# -gt 0 ]; do
	[ "\$1" = -f ] && { echo "logcat live: \$*" > "\$2"; exec sleep 1000; }
	shift
done
exec sleep 1000
EOF
cat > "$bin/dmesg" <<EOF
#!/bin/sh
[ "\${1:-}" = -w ] || { echo "dmesg dump"; exit 0; }
echo "dmesg live"
exec sleep 1000
EOF
printf '#!/bin/sh\ncat %s\n' "$fake/props" > "$bin/getprop"
printf '#!/bin/sh\necho "log $*" >> %s\n' "$fake/log.txt" > "$bin/log"
printf '#!/bin/sh\necho fake > %s\necho OK:%s\n' "$fake/bugreport.zip" "$fake/bugreport.zip" > "$bin/bugreportz"
printf '#!/bin/sh\necho "stub $(basename "$0") $*"\n' > "$bin/stub"
for c in dumpsys lshal service getenforce; do ln -s stub "$bin/$c"; done
chmod +x "$bin"/*

sed -e "s#/proc/sys/kernel/random/boot_id#$fake/boot_id#g" -e "s#\([ =]\)/sys/#\1$fake/sys/#g" \
	-e "s#/config/#$fake/config/#g" -e "s#/data/#$fake/data/#g" \
	-e "s#/dev/kmsg#$fake/dev/kmsg#g" -e "s#/dev/usb_accessory#$fake/dev/usb_accessory#g" \
	"$here/../src/assets/capture.sh" > "$here/capture.sh"

export PATH=$bin:$PATH
base=$fake/data/local/tmp/pixelos-logs-$(id -u)

# Same install-and-run line as MainActivity.RUN_SCRIPT, with the fake base directory.
cap() {
	echo "\$ capture.sh $*" >&2
	sh -c "d=$base && mkdir -p \$d && cat > \$d/capture.sh.new && mv \$d/capture.sh.new \$d/capture.sh && exec sh \$d/capture.sh $*" < "$here/capture.sh"
	rc=$?
	echo "exit=$rc" >&2
	return $rc
}
ok() { echo "ok: $*"; }
fail() { echo "FAIL: $*"; exit 1; }
alive() { for p in $(cat "$1/pids"); do kill -0 "$p" 2>/dev/null || return 1; done; }
session_dir() { read -r s < "$base/$1"; echo "$base/$s"; }

notes=$(printf 'Problem: USB\n\nCar shows nothing.' | base64 -w0)

cap status | grep -qx state=idle || fail "status before any capture"
ok "status is idle"
cap start bogus && fail "unknown scenario accepted"
ok "unknown scenario rejected"

cap start usb || fail "start usb"
cap start usb && fail "second start accepted"
ok "second start rejected"
dir=$(session_dir current)
sleep 1
alive "$dir" || fail "capture processes not running"
ok "logcat, dmesg and usb poller running: $(tr '\n' ' ' < "$dir/pids")"
echo configured > "$fake/sys/class/udc/a600000.dwc3/state"
sleep 1
cap mark && cap mark || fail "mark"
cap status | tee "$here/status.txt"
grep -qx state=running "$here/status.txt" && grep -qx scenario=usb "$here/status.txt" \
	&& grep -qx marks=2 "$here/status.txt" || fail "status while running"
ok "status reports the running usb capture with 2 marks"
cap collect other "$notes" && fail "collect accepted during a capture"
ok "collect rejected during a capture"

cap stop "$notes" bugreport || fail "stop"
sleep 0.5
for p in $(cat "$dir/pids"); do kill -0 "$p" 2>/dev/null && fail "pid $p still alive after stop"; done
pgrep -f "capture.sh run" > /dev/null && fail "daemon still alive after stop"
ok "stop ended every capture process"
grep -q 'Car shows nothing.' "$dir/notes.txt" || fail "notes not decoded"
grep -q 'state=configured' "$dir/usb-state.txt" || fail "usb state change not recorded"
[ -f "$dir/bugreport.zip" ] && [ -f "$dir/pstore/console-ramoops-0" ] || fail "bugreport or pstore missing"
[ "$(wc -l < "$dir/marks.txt")" = 2 ] && grep -c MARK "$fake/log.txt" | grep -qx 2 || fail "marks"
ok "notes, usb state, marks, pstore and bug report are in the session"

cap archive > "$here/usb.tar.gz" || fail "archive"
tar -tzf "$here/usb.tar.gz" | sort > "$here/usb.list"
grep -q 'start-dumpsys-usb.txt$' "$here/usb.list" && grep -q 'end.txt$' "$here/usb.list" \
	|| fail "archive contents"
ok "archive holds $(wc -l < "$here/usb.list") entries"

cap collect crash "$notes" || fail "collect crash"
dir=$(session_dir last)
[ -f "$dir/tombstones/tombstone_03" ] && [ ! -f "$dir/tombstones/tombstone_03.pb" ] \
	&& [ -f "$dir/anr/anr_2026-10-28" ] && [ -f "$dir/dropbox.txt" ] || fail "crash files"
[ -f "$dir/bugreport.zip" ] && fail "bug report taken without being asked"
ok "collect crash copied text tombstones, anr and dropbox, no bug report"

cap start battery || fail "start battery"
dir=$(session_dir current)
sleep 1
echo boot-b > "$fake/boot_id"
cap status | grep -qx state=interrupted || fail "status after a reboot"
ok "status reports a capture cut off by a reboot"
cap stop "$notes" || fail "stop after reboot"
alive "$dir" || fail "stop after a reboot killed pids from the old boot"
ok "stop after a reboot left the old boot's pids alone"
grep -q 'rebooted' "$dir/daemon.txt" || fail "reboot not noted"
kill $(cat "$dir/pids")
grep -q 'thermal cpu-0-0=41000' "$dir/battery.txt" || fail "battery poller"
ok "battery poller recorded thermal zones"

for s in reboot camera audio network display other; do
	sleep 1
	cap collect "$s" "$notes" > /dev/null || fail "collect $s"
done
n=$(ls "$base" | grep -cE '^[0-9]{8}-[0-9]{6}-')
[ "$n" = 5 ] || fail "prune kept $n sessions"
ok "prune keeps 5 sessions"
cap status | grep -qx state=idle || fail "status after stop"
ok "all checks passed"
