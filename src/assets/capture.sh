#!/system/bin/sh
# Runs as root through su. Usage: capture.sh start | stop | archive

base=/data/local/tmp/usb-logs
current=$base/current
last=$base/last

usb_state() {
	for f in /sys/class/udc/*/state /sys/class/udc/*/current_speed \
		/config/usb_gadget/g1/UDC /config/usb_gadget/g1/idVendor /config/usb_gadget/g1/idProduct \
		/sys/class/dual_role_usb/*/mode /sys/class/dual_role_usb/*/power_role \
		/sys/class/dual_role_usb/*/data_role \
		/sys/class/power_supply/usb/present /sys/class/power_supply/usb/online \
		/sys/class/power_supply/usb/real_type /sys/class/power_supply/usb/typec_mode; do
		[ -e "$f" ] || continue
		read -r v 2>/dev/null < "$f" || v="<read failed>"
		echo "$f=$v"
	done
	functions=
	for l in /config/usb_gadget/g1/configs/b.1/function*; do
		[ -L "$l" ] || continue
		t=$(realpath "$l")
		functions="$functions ${t##*/}"
	done
	echo "configs/b.1 functions:$functions"
	getprop | grep -E '^\[(sys|vendor|persist\.sys|persist\.vendor)\.usb\.'
}

watch_usb() {
	prev=
	while :; do
		cur=$(usb_state)
		if [ "$cur" != "$prev" ]; then
			read -r up _ < /proc/uptime
			echo "--- $(date '+%m-%d %H:%M:%S.%N') uptime=$up"
			echo "$cur"
			prev=$cur
		fi
		sleep 0.25
	done
}

section() {
	echo "===== $*"
	"$@" 2>&1
	echo
}

snapshot() {
	{
		section date
		section cat /proc/uptime
		section getenforce
		section uname -a
		section id
		section ls -lZ /dev/usb_accessory
		section ls -lZ /config/usb_gadget/g1/functions
		section usb_state
		echo "===== lshal | grep -i usb"
		lshal 2>&1 | grep -i usb
		echo
		echo "===== service list | grep -i usb"
		service list 2>&1 | grep -i usb
		echo
		echo "===== dumpsys package com.google.android.projection.gearhead | grep version"
		dumpsys package com.google.android.projection.gearhead 2>&1 | grep -E 'versionName|versionCode'
		echo
		section ps -A -Z
	} > "$1.txt"
	dumpsys usb > "$1-dumpsys-usb.txt" 2>&1
	getprop > "$1-getprop.txt" 2>&1
}

cmd_start() {
	if [ -f "$current" ]; then
		read -r session < "$current"
		echo "Already capturing ($session). Tap Stop first."
		return 0
	fi
	session=$(date +%Y%m%d-%H%M%S)
	dir=$base/$session
	mkdir -p "$dir" || return 1
	snapshot "$dir/start"
	echo "$session" > "$current"
	setsid sh "$0" run "$dir" < /dev/null > "$dir/daemon.txt" 2>&1 &
	echo "Capturing to $dir"
}

cmd_run() {
	dir=$1
	# su implementations that escalate in place leave this shell in the app's
	# process cgroup, which Android kills as a whole when the app dies.
	echo $$ > /sys/fs/cgroup/cgroup.procs
	logcat -b all -v threadtime > "$dir/logcat.txt" 2>&1 &
	echo $! >> "$dir/pids"
	dmesg -w > "$dir/dmesg.txt" 2>&1 &
	echo $! >> "$dir/pids"
	watch_usb > "$dir/usb-state.txt" 2>&1 &
	echo $! >> "$dir/pids"
	wait
}

cmd_stop() {
	if [ ! -f "$current" ]; then
		echo "No capture is running."
		return 1
	fi
	read -r session < "$current"
	dir=$base/$session
	# After a reboot the pids are gone; the kill errors are kept with the logs.
	kill $(cat "$dir/pids") 2>> "$dir/daemon.txt"
	snapshot "$dir/end"
	echo "$session" > "$last"
	rm "$current"
	echo "Stopped $session"
}

cmd_archive() {
	read -r session < "$last" || return 1
	tar -czf - -C "$base" "$session"
}

case $1 in
start|stop|archive)
	cmd_$1
	;;
run)
	cmd_run "$2"
	;;
*)
	echo "usage: $0 start|stop|archive" >&2
	exit 2
	;;
esac
