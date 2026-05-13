#!/bin/bash
# run_parallel.sh — Run a test N times with P parallel instances
# Usage: bash run_parallel.sh [--parallel=P] [--scheduler=NAME] [--volcano] <N> <TestClassName>

PARALLEL=4
SCHEDULER="least-allocated"
VOLCANO=false
BASE_PORT=9000

while [[ $# -gt 0 ]]; do
  case $1 in
    --parallel=*) PARALLEL="${1#*=}"; shift ;;
    --scheduler=*) SCHEDULER="${1#*=}"; shift ;;
    --volcano) VOLCANO=true; SCHEDULER="volcano"; shift ;;
    --help) echo "Usage: bash run_parallel.sh [--parallel=P] [--scheduler=NAME] [--volcano] <N> <TestClassName>"; exit 0 ;;
    *) break ;;
  esac
done

TOTAL_RUNS=${1:?Usage: run_parallel.sh [options] <N> <TestClassName>}
TEST_CLASS=${2:?Usage: run_parallel.sh [options] <N> <TestClassName>}

[[ "$TEST_CLASS" != org.example.* ]] && TEST_CLASS="org.example.testSuite.$TEST_CLASS"

RESULTS_DIR="results/variance_$(echo $TEST_CLASS | sed 's/.*\.//')_$(date +%Y%m%d_%H%M%S)"
mkdir -p "$RESULTS_DIR"

echo "=== Parallel Test Runner ==="
echo "  Test:      $TEST_CLASS"
echo "  Scheduler: $SCHEDULER"
echo "  Runs:      $TOTAL_RUNS"
echo "  Parallel:  $PARALLEL"
echo "  Results:   $RESULTS_DIR"
echo ""

# Build
echo "Building..."
cd k8s-cloudsim-adapter && go build -o adapter-linux . 2>/dev/null && cd ..
mvn -q compile 2>/dev/null
echo "Build complete."

# --- Start infrastructure upfront ---
echo "Starting $PARALLEL adapter+scheduler instances..."

# Clean up stale processes and containers from previous runs
pkill -9 -x adapter-linux 2>/dev/null
docker rm -f kube-scheduler volcano-scheduler 2>/dev/null
for SLOT in $(seq 0 $((PARALLEL - 1))); do
  docker rm -f "sched-par-${SLOT}" 2>/dev/null
done
sleep 2

ADAPTER_PIDS=()
for SLOT in $(seq 0 $((PARALLEL - 1))); do
  PORT=$((BASE_PORT + SLOT))
  ADAPTER_FLAGS="--port=$PORT"
  if [ "$VOLCANO" = true ]; then
    ADAPTER_FLAGS="$ADAPTER_FLAGS --scheduler=volcano"
  else
    ADAPTER_FLAGS="$ADAPTER_FLAGS --scheduler=$SCHEDULER"
  fi
  setsid ./k8s-cloudsim-adapter/adapter-linux $ADAPTER_FLAGS </dev/null >/dev/null 2>&1 &
  ADAPTER_PIDS+=($!)
done
sleep 2

# Start scheduler containers
for SLOT in $(seq 0 $((PARALLEL - 1))); do
  PORT=$((BASE_PORT + SLOT))
  CONTAINER_NAME="sched-par-${SLOT}"
  docker rm -f "$CONTAINER_NAME" 2>/dev/null || true

  if [ "$VOLCANO" = true ]; then
    docker run -d --rm --network host --name "$CONTAINER_NAME" \
      -v "$(pwd)/volcano-scheduler/volcano-scheduler.conf:/etc/volcano/volcano-scheduler.conf" \
      volcanosh/vc-scheduler:v1.10.0 \
      vc-scheduler --master="http://localhost:$PORT" \
      --scheduler-conf=/etc/volcano/volcano-scheduler.conf \
      --scheduler-name=volcano --leader-elect=false \
      --enable-metrics=false --enable-healthz=false --cache-dumper=false \
      -v 0 >/dev/null 2>&1
  else
    # Create a minimal kubeconfig pointing to this port
    KUBECONFIG_TMP="/tmp/kubeconfig-par-${SLOT}.yaml"
    cat > "$KUBECONFIG_TMP" <<EOF
apiVersion: v1
kind: Config
clusters:
- cluster:
    server: http://localhost:$PORT
    insecure-skip-tls-verify: true
  name: sim
contexts:
- context:
    cluster: sim
  name: sim
current-context: sim
EOF
    docker run -d --rm --network host --name "$CONTAINER_NAME" \
      -v "$KUBECONFIG_TMP:/etc/kube-scheduler/kubeconfig.yaml" \
      -v "$(pwd)/second-scheduler/scheduler-config.yaml:/etc/kubernetes/scheduler-config.yaml" \
      registry.k8s.io/kube-scheduler:v1.33.0 \
      kube-scheduler --config=/etc/kubernetes/scheduler-config.yaml \
      --leader-elect=false --secure-port=$((11260 + SLOT)) -v 0 >/dev/null 2>&1
  fi
done

echo "Waiting for schedulers to initialize..."
sleep 10

# Reset all adapters
for SLOT in $(seq 0 $((PARALLEL - 1))); do
  PORT=$((BASE_PORT + SLOT))
  curl -s -X DELETE "http://localhost:$PORT/reset" >/dev/null
done
echo "Infrastructure ready."
echo ""

# --- Run tests in parallel batches ---
echo "run,ttc_s,energy_wh" > "$RESULTS_DIR/raw.csv"

run_one() {
  local RUN_NUM=$1
  local SLOT=$2
  local PORT=$((BASE_PORT + SLOT))

  curl -s -X DELETE "http://localhost:$PORT/reset" >/dev/null
  sleep 1

  local OUTPUT
  OUTPUT=$(mvn -q exec:java -Dexec.mainClass="$TEST_CLASS" -Dadapter.port="$PORT" 2>&1)

  local TTC=$(echo "$OUTPUT" | grep "Simulated Time" | grep -oP '[\d.]+(?= units)')
  local ENERGY=$(echo "$OUTPUT" | grep "Energy consumption" | grep -oP '[\d.]+(?= Wh)')

  echo "$RUN_NUM,$TTC,$ENERGY" >> "$RESULTS_DIR/raw.csv"
  echo "  Run $RUN_NUM (slot $SLOT): TTC=${TTC}s, Energy=${ENERGY}Wh"
}

COMPLETED=0
while [ $COMPLETED -lt $TOTAL_RUNS ]; do
  BATCH_SIZE=$((TOTAL_RUNS - COMPLETED))
  [ $BATCH_SIZE -gt $PARALLEL ] && BATCH_SIZE=$PARALLEL

  PIDS=()
  for S in $(seq 0 $((BATCH_SIZE - 1))); do
    RUN_NUM=$((COMPLETED + S + 1))
    run_one $RUN_NUM $S &
    PIDS+=($!)
  done

  for PID in "${PIDS[@]}"; do wait $PID 2>/dev/null; done
  COMPLETED=$((COMPLETED + BATCH_SIZE))
  echo "  --- $COMPLETED/$TOTAL_RUNS complete ---"
done

# --- Cleanup ---
echo ""
echo "Cleaning up..."
for SLOT in $(seq 0 $((PARALLEL - 1))); do
  docker rm -f "sched-par-${SLOT}" 2>/dev/null || true
done
for PID in "${ADAPTER_PIDS[@]}"; do kill $PID 2>/dev/null; done
wait 2>/dev/null

# --- Statistics ---
echo ""
echo "=== Results ==="
python3 -c "
import csv, statistics
ttcs, energies = [], []
with open('$RESULTS_DIR/raw.csv') as f:
    for row in csv.DictReader(f):
        if row['ttc_s']: ttcs.append(float(row['ttc_s']))
        if row['energy_wh']: energies.append(float(row['energy_wh']))
if len(ttcs) > 1:
    print(f'TTC:    mean={statistics.mean(ttcs):.2f}s, median={statistics.median(ttcs):.2f}s, '
          f'stdev={statistics.stdev(ttcs):.2f}s, CV={statistics.stdev(ttcs)/statistics.mean(ttcs)*100:.2f}%, '
          f'min={min(ttcs):.2f}, max={max(ttcs):.2f}, n={len(ttcs)}')
    print(f'Energy: mean={statistics.mean(energies):.2f}Wh, median={statistics.median(energies):.2f}Wh, '
          f'stdev={statistics.stdev(energies):.2f}Wh, CV={statistics.stdev(energies)/statistics.mean(energies)*100:.2f}%, '
          f'min={min(energies):.2f}, max={max(energies):.2f}, n={len(energies)}')
else:
    print('Not enough data points for statistics')
"
echo "Raw data: $RESULTS_DIR/raw.csv"
