#!/bin/sh
# gate.sh <max minutes> [pressure file]: waits until the box is quiet enough to time anything (1-minute load under
# $MCGUI_CI_GATE_LOAD, default 5, and memory pressure 'full avg60' under 5 %), checking every 30 s. Prints the load
# it started at and exits 0, or prints why it gave up after the limit and exits 1.
END=$(( $(date +%s) + ${1:-30} * 60 ))
PRESSURE=${2:-/sys/fs/cgroup/atlas.slice/memory.pressure}
MAX_LOAD=${MCGUI_CI_GATE_LOAD:-5}
while :; do
  L=$(cut -d' ' -f1 /proc/loadavg)
  P=$(grep full "$PRESSURE" 2>/dev/null | sed 's/.*avg60=\([0-9.]*\).*/\1/')
  P=${P:-0}
  if awk -v l="$L" -v p="$P" -v m="$MAX_LOAD" 'BEGIN{exit !(l < m && p < 5)}'; then
    echo "quiet at $(date +%T): load $L, memory full avg60 $P"
    exit 0
  fi
  if [ "$(date +%s)" -ge "$END" ]; then
    echo "gave up at $(date +%T): load $L, memory full avg60 $P"
    exit 1
  fi
  sleep 30
done
