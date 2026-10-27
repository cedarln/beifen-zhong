#!/bin/bash
# 重启补投（catch-up）现场验证：release 包 → 冷启动一次 → 强制停止 → 重启模拟器 →
# 看 BOOT_COMPLETED 有没有把 WorkManager 唤醒、闹钟有没有重新挂上、有没有闪退。
# 只读为主，不改任何剧本或代码；产物落在 /tmp/beifen-shots/reboot_test.log
set -u
ADB="adb -s emulator-5554"
PKG=cn.linan.backingup
LOG=/tmp/beifen-shots/reboot_test.log
mkdir -p /tmp/beifen-shots
: > "$LOG"

say() { echo "[$(date +%H:%M:%S)] $*" | tee -a "$LOG"; }

say "== 1. 关掉 app，清掉旧通知，冷启动一次建立存档 =="
$ADB shell am force-stop $PKG
$ADB shell cmd notification dismiss-all
$ADB shell "monkey -p $PKG -c android.intent.category.LAUNCHER 1" >/dev/null 2>&1 ||
  $ADB shell am start -n $PKG/$PKG.MainActivity
sleep 10
say "前台包：$($ADB shell dumpsys window 2>/dev/null | grep -m1 mCurrentFocus)"

say "== 2. 记录当前闹钟表 =="
$ADB shell dumpsys alarm > /tmp/beifen-shots/alarms_before_reboot.txt 2>&1
say "本包闹钟条数：$(grep -c "$PKG" /tmp/beifen-shots/alarms_before_reboot.txt)"

say "== 3. 重启 =="
$ADB reboot
$ADB wait-for-device
say "设备回来了，等 boot_completed"
for i in $(seq 1 40); do
  bc=$($ADB shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')
  [ "$bc" = "1" ] && break
  sleep 3
done
say "sys.boot_completed=$($ADB shell getprop sys.boot_completed | tr -d '\r')"
sleep 45   # BOOT_COMPLETED 广播 + WorkManager 起来需要一点时间

say "== 4. 重启后的闹钟表 =="
$ADB shell dumpsys alarm > /tmp/beifen-shots/alarms_after_reboot.txt 2>&1
grep -A3 "$PKG" /tmp/beifen-shots/alarms_after_reboot.txt | head -20 | tee -a "$LOG"
say "重启后本包闹钟条数：$(grep -c "$PKG" /tmp/beifen-shots/alarms_after_reboot.txt)"

say "== 5. 崩溃与补投日志 =="
$ADB logcat -b crash -d -t 200 > /tmp/beifen-shots/crash_after_reboot.txt 2>&1
say "crash buffer 行数：$(wc -l < /tmp/beifen-shots/crash_after_reboot.txt)"
grep -i "backingup" /tmp/beifen-shots/crash_after_reboot.txt | head -10 | tee -a "$LOG"
$ADB logcat -d -t 4000 > /tmp/beifen-shots/logcat_after_reboot.txt 2>&1
say "CatchUp / BootReceiver / ReminderScheduler 相关行："
grep -Ei "CatchUp|BootReceiver|ReminderScheduler|WakeGate|QuietHours" /tmp/beifen-shots/logcat_after_reboot.txt | tail -20 | tee -a "$LOG"

say "== 6. 通知渠道与已投通知 =="
$ADB shell dumpsys notification --noredact > /tmp/beifen-shots/notifications.txt 2>&1
grep -A6 "$PKG" /tmp/beifen-shots/notifications.txt | head -40 | tee -a "$LOG"
say "渠道重要性（2=LOW，不该有声音）："
grep -E "channel=|importance=" /tmp/beifen-shots/notifications.txt | grep -i "$PKG\|corridor" | head -10 | tee -a "$LOG"

say "== 7. 再冷启动一次，看 D1 是否接得上 =="
$ADB shell monkey -p $PKG -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1
sleep 10
$ADB shell "uiautomator dump /sdcard/w.xml >/dev/null 2>&1; cat /sdcard/w.xml" \
  | tr '>' '\n' | grep -o 'text="[^"]*"' | sed -n '1,14p' | tee -a "$LOG"
say "完成"
