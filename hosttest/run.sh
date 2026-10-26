#!/bin/bash
# Host run of capture.sh under mksh, with sysfs/configfs/base paths moved into a fake root
# and Android-only commands stubbed.
set -u

here=$(cd "$(dirname "$0")" && pwd)
fake=$here/fake
bin=$here/bin
rm -rf "$fake" "$bin"
mkdir -p "$bin" "$fake/sys/class/udc/4e00000.dwc3" "$fake/sys/class/dual_role_usb/otg_default" \
	"$fake/sys/class/power_supply/usb" "$fake/sys/fs/cgroup" \
	"$fake/config/usb_gadget/g1/configs/b.1" "$fake/config/usb_gadget/g1/functions/accessory.gs2" \
	"$fake/config/usb_gadget/g1/functions/ffs.adb" "$fake/data/local/tmp"

echo addressed > "$fake/sys/class/udc/4e00000.dwc3/state"
echo high-speed > "$fake/sys/class/udc/4e00000.dwc3/current_speed"
echo 4e00000.dwc3 > "$fake/config/usb_gadget/g1/UDC"
echo 0x18d1 > "$fake/config/usb_gadget/g1/idVendor"
echo 0x2d01 > "$fake/config/usb_gadget/g1/idProduct"
echo ufp > "$fake/sys/class/dual_role_usb/otg_default/mode"
echo sink > "$fake/sys/class/dual_role_usb/otg_default/power_role"
echo device > "$fake/sys/class/dual_role_usb/otg_default/data_role"
echo 1 > "$fake/sys/class/power_supply/usb/online"
mkdir "$fake/sys/class/power_supply/usb/typec_mode"   # exists but read() fails (EISDIR)
ln -s ../../functions/accessory.gs2 "$fake/config/usb_gadget/g1/configs/b.1/function0"
ln -s ../../functions/ffs.adb "$fake/config/usb_gadget/g1/configs/b.1/function1"
echo '[sys.usb.state]: [accessory,adb]' > "$fake/props"

ln -s "$here/mksh/mksh" "$bin/sh"
for c in logcat dmesg; do
	printf '#!/bin/sh\necho "%s started"\nexec sleep 1000\n' "$c" > "$bin/$c"
done
printf '#!/bin/sh\ncat %s\n' "$fake/props" > "$bin/getprop"
printf '#!/bin/sh\necho "stub $(basename "$0") $*"\n' > "$bin/stub"
for c in dumpsys lshal service getenforce; do ln -s stub "$bin/$c"; done
chmod +x "$bin"/*

sed -e "s#/sys/#$fake/sys/#g" -e "s#/config/#$fake/config/#g" -e "s#/data/local/tmp#$fake/data/local/tmp#" \
	"$here/../src/assets/capture.sh" > "$here/capture.sh"

export PATH=$bin:$PATH
cap() { echo "\$ capture.sh $*"; sh "$here/capture.sh" "$@"; echo "exit=$?"; }
base=$fake/data/local/tmp/usb-logs

cap start
cap start
sleep 1
read -r session < "$base/current"
dir=$base/$session
echo "pids: $(tr '\n' ' ' < "$dir/pids")"
for p in $(cat "$dir/pids"); do kill -0 "$p" && echo "pid $p alive"; done
echo "cgroup.procs: $(cat "$fake/sys/fs/cgroup/cgroup.procs")"

echo configured > "$fake/sys/class/udc/4e00000.dwc3/state"
echo '[sys.usb.state]: [mtp,adb]' > "$fake/props"
sleep 1
cap stop
sleep 0.5
for p in $(cat "$dir/pids"); do kill -0 "$p" 2>/dev/null && echo "pid $p STILL ALIVE"; done
pgrep -f "capture.sh run" && echo "daemon STILL ALIVE"
cap stop

sh "$here/capture.sh" archive > "$here/out.tar.gz"
echo "archive exit=$?"
tar -tzf "$here/out.tar.gz"
echo "--- usb-state.txt"
cat "$dir/usb-state.txt"
echo "--- daemon.txt"
cat "$dir/daemon.txt"
echo "--- start.txt (head)"
head -40 "$dir/start.txt"
