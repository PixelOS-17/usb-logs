#!/system/bin/sh
# Collects logs for one kind of problem. Runs as root through su, or as the shell user
# through Shizuku. The app copies this file into its working directory before each call.
# Usage: capture.sh start SCENARIO | mark | stop NOTES [bugreport]
#        | collect SCENARIO NOTES [bugreport] | status | archive
# NOTES is the tester's description, base64 encoded.

base=${0%/*}
current=$base/current
last=$base/last
tag=PixelOSLogs

# The ids must match MainActivity.SCENARIOS.
scenarios="reboot crash usb camera audio network battery display other"

show_files() {
	for f in "$@"; do
		[ -e "$f" ] || continue
		read -r v 2>/dev/null < "$f" || v="<read failed>"
		echo "$f=$v"
	done
}

usb_state() {
	show_files /sys/class/udc/*/state /sys/class/udc/*/current_speed \
		/config/usb_gadget/g1/UDC /config/usb_gadget/g1/idVendor /config/usb_gadget/g1/idProduct \
		/sys/class/dual_role_usb/*/mode /sys/class/dual_role_usb/*/power_role \
		/sys/class/dual_role_usb/*/data_role \
		/sys/class/power_supply/usb/present /sys/class/power_supply/usb/online \
		/sys/class/power_supply/usb/real_type /sys/class/power_supply/usb/typec_mode
	functions=
	for l in /config/usb_gadget/g1/configs/b.1/function*; do
		[ -L "$l" ] || continue
		t=$(realpath "$l")
		functions="$functions ${t##*/}"
	done
	echo "configs/b.1 functions:$functions"
	getprop | grep -E '^\[(sys|vendor|persist\.sys|persist\.vendor)\.usb\.'
}

battery_state() {
	show_files /sys/class/power_supply/battery/capacity /sys/class/power_supply/battery/status \
		/sys/class/power_supply/battery/health /sys/class/power_supply/battery/current_now \
		/sys/class/power_supply/battery/voltage_now /sys/class/power_supply/battery/temp \
		/sys/class/power_supply/usb/online /sys/class/power_supply/usb/real_type \
		/sys/class/power_supply/usb/current_now /sys/class/power_supply/usb/voltage_now
	for z in /sys/class/thermal/thermal_zone*; do
		[ -e "$z/temp" ] || continue
		read -r type 2>/dev/null < "$z/type" || type=${z##*/}
		read -r temp 2>/dev/null < "$z/temp" || temp="<read failed>"
		echo "thermal $type=$temp"
	done
}

cooling_state() {
	for c in /sys/class/thermal/cooling_device*; do
		[ -e "$c/cur_state" ] || continue
		read -r type 2>/dev/null < "$c/type" || type=${c##*/}
		read -r cur 2>/dev/null < "$c/cur_state" || cur="<read failed>"
		read -r max 2>/dev/null < "$c/max_state" || max="?"
		echo "cooling $type=$cur/$max"
	done
}

# Prints the output of $1 with a timestamp whenever it changes, checking every $2 seconds.
poll() {
	prev=
	while :; do
		cur=$("$1")
		if [ "$cur" != "$prev" ]; then
			read -r up _ < /proc/uptime
			echo "--- $(date '+%m-%d %H:%M:%S.%N') uptime=$up"
			echo "$cur"
			prev=$cur
		fi
		sleep "$2"
	done
}

section() {
	echo "===== $*"
	"$@" 2>&1
	echo
}

# Like section, but keeps only the lines that match the extended regex in $1.
filtered() {
	pattern=$1
	shift
	echo "===== $* | grep -iE '$pattern'"
	"$@" 2>&1 | grep -iE "$pattern"
	echo
}

dump() {
	for service in "$@"; do
		dumpsys "$service" > "$out-dumpsys-$service.txt" 2>&1
	done
}

collect_crashes() {
	mkdir -p "$dir/tombstones"
	cp /data/tombstones/tombstone_?? "$dir/tombstones/" 2>> "$dir/errors.txt"
	cp -r /data/anr "$dir/anr" 2>> "$dir/errors.txt"
	dumpsys dropbox --print > "$dir/dropbox.txt" 2>&1
}

collect_pstore() {
	pstore=/sys/fs/pstore
	if ! cp -r "$pstore" "$dir/pstore" 2>> "$dir/errors.txt"; then
		# The shell user has search (not read) on pstorefs dirs, so copy known files directly.
		mkdir -p "$dir/pstore"
		for f in console-ramoops console-ramoops-0 pmsg-ramoops-0 dmesg-ramoops-0 dmesg-ramoops-1; do
			[ -r "$pstore/$f" ] && cp "$pstore/$f" "$dir/pstore/" 2>> "$dir/errors.txt"
		done
	fi
}

# State worth comparing before and after the problem: written at start, at stop and by collect.
scenario_state() {
	case $scenario in
	reboot)
		section battery_state
		section cooling_state
		dump battery power thermalservice
		;;
	usb)
		section ls -lZ /dev/usb_accessory /config/usb_gadget/g1/functions
		section usb_state
		filtered usb lshal
		filtered 'versionName|versionCode' dumpsys package com.google.android.projection.gearhead
		dump usb
		;;
	camera)
		filtered camera lshal
		dump media.camera
		;;
	audio)
		filtered 'audio|bluetooth' lshal
		dump bluetooth_manager audio media.audio_flinger media.audio_policy
		;;
	network)
		section ip addr
		section ip route
		filtered 'radio|ims' lshal
		dump wifi connectivity telephony.registry phone telecom ims isub carrier_config
		;;
	battery)
		section battery_state
		section cooling_state
		dump battery power thermalservice
		;;
	display)
		dump SurfaceFlinger display input fingerprint face biometric
		;;
	esac
}

# Files that only exist once the problem has happened: written at stop and by collect.
scenario_files() {
	case $scenario in
	reboot|crash|camera)
		collect_crashes
		;;
	audio)
		cp -r /data/misc/bluetooth/logs "$dir/bluetooth-logs" 2>> "$dir/errors.txt"
		;;
	battery)
		dumpsys batterystats > "$dir/batterystats.txt" 2>&1
		;;
	esac
}

scenario_poll() {
	case $scenario in
	usb)
		poll usb_state 0.25 > "$dir/usb-state.txt" 2>&1 &
		echo $! >> "$dir/pids"
		;;
	reboot|battery)
		poll battery_state 10 > "$dir/battery.txt" 2>&1 &
		echo $! >> "$dir/pids"
		;;
	esac
}

snapshot() {
	out=$1
	{
		section date
		section cat /proc/uptime
		section uname -a
		section id
		section getenforce
		section df -h /data
		section ls -l /data/tombstones /data/anr /sys/fs/pstore
		section ps -A -Z
		scenario_state
	} > "$out.txt"
	getprop > "$out-getprop.txt" 2>&1
}

add_bugreport() {
	result=$(bugreportz 2>&1)
	case $result in
	OK:*)
		cp "${result#OK:}" "$dir/" 2>> "$dir/errors.txt"
		;;
	*)
		echo "bugreportz: $result" >> "$dir/errors.txt"
		;;
	esac
}

finish() {
	# pstore keeps the previous boot's kernel console and logcat across a crash or reboot.
	collect_pstore
	logcat -L -b all -d -v threadtime > "$dir/logcat-last-boot.txt" 2>&1
	scenario_files
	printf '%s' "$notes" | base64 -d > "$dir/notes.txt" 2>> "$dir/errors.txt"
	[ "$bugreport" = bugreport ] && add_bugreport
	echo "$session" > "$last"
}

# Keeps the four newest sessions before a new one is made. The app keeps its own archives.
prune() {
	ls "$base" | grep -E '^[0-9]{8}-[0-9]{6}-' | sort -r | tail -n +5 | while read -r old; do
		rm -rf "${base:?}/$old"
	done
}

new_session() {
	scenario=$1
	case " $scenarios " in
	*" $scenario "*) ;;
	*)
		echo "Unknown scenario '$scenario'."
		return 1
		;;
	esac
	prune
	session=$(date +%Y%m%d-%H%M%S)-$scenario
	dir=$base/$session
	mkdir -p "$dir" || return 1
	echo "$scenario" > "$dir/scenario"
	date +%s > "$dir/started"
	cat /proc/sys/kernel/random/boot_id > "$dir/boot_id"
}

load_current() {
	if [ ! -f "$current" ]; then
		echo "No capture is running."
		return 1
	fi
	read -r session < "$current"
	dir=$base/$session
	read -r scenario < "$dir/scenario"
}

same_boot() {
	read -r boot < /proc/sys/kernel/random/boot_id
	read -r capture_boot < "$dir/boot_id"
	[ "$boot" = "$capture_boot" ]
}

cmd_start() {
	if [ -f "$current" ]; then
		read -r session < "$current"
		echo "Already capturing ($session). Tap Stop first."
		return 1
	fi
	new_session "$1" || return 1
	snapshot "$dir/start"
	echo "$session" > "$current"
	setsid sh "$0" run "$dir" < /dev/null > "$dir/daemon.txt" 2>&1 &
	echo "Capturing to $dir"
}

cmd_run() {
	dir=$1
	read -r scenario < "$dir/scenario"
	if [ "$(id -u)" = 0 ]; then
		# su implementations that escalate in place leave this shell in the app's
		# process cgroup, which Android kills as a whole when the app dies.
		echo $$ > /sys/fs/cgroup/cgroup.procs
	fi
	# Rotates every 16 MiB and keeps 16 files, so a long capture stays under 256 MiB.
	logcat -b all -v threadtime -f "$dir/logcat.txt" -r 16384 -n 15 &
	echo $! >> "$dir/pids"
	dmesg -w > "$dir/dmesg.txt" 2>&1 &
	echo $! >> "$dir/pids"
	scenario_poll
	wait
}

cmd_mark() {
	load_current || return 1
	n=1
	[ -f "$dir/marks.txt" ] && n=$(($(wc -l < "$dir/marks.txt") + 1))
	read -r up _ < /proc/uptime
	log -t "$tag" "MARK $n"
	echo "$tag: MARK $n" 2>/dev/null > /dev/kmsg
	echo "MARK $n $(date '+%m-%d %H:%M:%S') uptime=$up" >> "$dir/marks.txt"
	echo "Marked the logs (mark $n)."
}

cmd_stop() {
	load_current || return 1
	notes=$1
	bugreport=$2
	if same_boot; then
		kill $(cat "$dir/pids") 2>> "$dir/daemon.txt"
	else
		# The saved pids belong to the previous boot and may now be other processes.
		echo "The phone rebooted during this capture." >> "$dir/daemon.txt"
	fi
	snapshot "$dir/end"
	finish
	rm "$current"
	echo "Stopped $session"
}

cmd_collect() {
	if [ -f "$current" ]; then
		echo "A capture is running. Stop it first."
		return 1
	fi
	new_session "$1" || return 1
	notes=$2
	bugreport=$3
	snapshot "$dir/now"
	logcat -b all -d -v threadtime > "$dir/logcat.txt" 2>&1
	dmesg > "$dir/dmesg.txt" 2>&1
	finish
	echo "Collected $session"
}

# Prints key=value lines for the app.
cmd_status() {
	if [ ! -f "$current" ]; then
		echo state=idle
		return 0
	fi
	load_current
	if same_boot; then
		echo state=running
	else
		echo state=interrupted
	fi
	echo "scenario=$scenario"
	echo "started=$(cat "$dir/started")"
	set -- $(du -sk "$dir")
	echo "size_kb=$1"
	marks=0
	[ -f "$dir/marks.txt" ] && marks=$(wc -l < "$dir/marks.txt")
	echo "marks=$((marks))"
}

cmd_archive() {
	if [ ! -f "$last" ]; then
		echo "No finished capture to pack." >&2
		return 1
	fi
	read -r session < "$last"
	tar -czf - -C "$base" "$session"
}

case $1 in
start)
	cmd_start "$2"
	;;
mark|status|archive)
	cmd_$1
	;;
stop)
	cmd_stop "$2" "$3"
	;;
collect)
	cmd_collect "$2" "$3" "$4"
	;;
run)
	cmd_run "$2"
	;;
*)
	echo "usage: $0 start SCENARIO | mark | stop NOTES [bugreport]" \
		"| collect SCENARIO NOTES [bugreport] | status | archive" >&2
	exit 2
	;;
esac
