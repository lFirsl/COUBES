# COUBES Operational Runbook
# COUBES Operational Runbook
# COUBES Operational Runbook

## run_test.sh — The Preferred Way to Run Tests

The `run_test.sh` script handles infrastructure startup, hang detection, auto-recovery, and prerequisite checks.

```bash
# Test mode (no Docker required)
./run_test.sh --test-mode org.example.testSuite.Fragmentation_Test

# Full mode (with real kube-scheduler via Docker)
./run_test.sh org.example.testSuite.Fragmentation_Test

# Volcano scheduler
./run_test.sh --volcano org.example.testSuite.Queue_Priority_Test
```

The script:
- Checks prerequisites (`mvn`, `go`, `curl`; `docker` only in full mode)
- Kills any existing adapter and stale Java processes
- Builds Go adapter + runs Go scheduler tests (unless `--no-compile`)
- Compiles Java (unless `--no-compile`)
- Starts adapter, starts scheduler (full mode only), resets state
- Runs the simulation with hang detection (45s default, 90s for Volcano)
- Auto-recovers once from a hang (restarts scheduler, retries)
- Filters output to show only results, metrics, and errors (unless `--no-filter`)
- Writes logs to `debug/<TestName>_<timestamp>_{sim,adapter,scheduler}.log`
- Symlinks `debug/{sim,adapter,scheduler}.log` always point to the latest run
- Exits non-zero with diagnostic output on failure

**Options:** `--test-mode`, `--volcano`, `--no-compile`, `--no-filter`, `--scheduler=NAME`, `--help`

**Run all tests:** `bash run_all_tests.sh [--volcano] [--no-compile] [--stop-on-fail] [--timeout=N]`

---

## Starting the Stack Manually

### Correct way to start background processes

**Never use `nohup ... &` in this environment** — the shell tool blocks waiting for the process group to exit, causing a hang.

Always use `setsid` with stdin redirected from `/dev/null`:

```bash
setsid ./adapter-linux --scheduler=default-scheduler </dev/null >/tmp/adapter.log 2>&1 &
echo $!
```

Same pattern for the Java simulation:

```bash
setsid mvn -q exec:java -Dexec.mainClass="org.example.testSuite.Fragmentation_Test" </dev/null >/tmp/sim.log 2>&1 &
echo $!
```

### Full startup sequence (full mode)

```bash
# 1. Build adapter binary (do once, or after Go changes)
cd k8s-cloudsim-adapter && go build -o adapter-linux .

# 2. Start adapter
setsid ./adapter-linux --scheduler=default-scheduler </dev/null >/tmp/adapter.log 2>&1 &
sleep 2 && curl -s -X DELETE http://localhost:8080/reset  # verify it's up

# 3. Start Docker scheduler
cd ../second-scheduler && docker compose up -d
sleep 8  # wait for "Caches populated"

# 4. Run a test
cd .. && setsid mvn -q exec:java -Dexec.mainClass="org.example.testSuite.Fragmentation_Test" </dev/null >/tmp/sim.log 2>&1 &
```

### Test mode (no Docker required)

```bash
setsid ./k8s-cloudsim-adapter/adapter-linux --test-mode </dev/null >/tmp/adapter.log 2>&1 &
sleep 2 && curl -s -X DELETE http://localhost:8080/reset
mvn -q exec:java -Dexec.mainClass="org.example.testSuite.Fragmentation_Test" 2>&1  # can run foreground if short
```

---

## Monitoring a Background Simulation

**Never use a blocking wait loop** — `while kill -0 $PID; do sleep 5; done` hangs the shell tool.

Instead, poll with separate tool calls:

```bash
# Check if still running
kill -0 $PID 2>/dev/null && echo "running" || echo "done"

# Check progress
tail -3 /tmp/sim.log
```

---

## kubeconfig.yaml — IP Address

`second-scheduler/kubeconfig.yaml` points to `http://localhost:8080`. This works because Docker is installed directly in WSL2 (not Docker Desktop) and the scheduler container uses `network_mode: host`, sharing the host's network stack.

If you switch back to Docker Desktop, `localhost` won't work from inside containers — you'd need the WSL2 eth0 IP instead.

---

## Debugging the Scheduler / Adapter

### Reading adapter logs (structured JSON)

The adapter emits structured JSON log lines. Key fields: `action`, `roundId`, `podCount`, `durationMs`, `result`, `scheduled`, `unschedulable`.

```bash
# Pretty-print adapter logs
cat /tmp/coubes-adapter.log | grep '^{' | jq .

# Filter by round ID
cat /tmp/coubes-adapter.log | grep '"roundId":"3"'

# Find timeouts
cat /tmp/coubes-adapter.log | grep '"result":"timeout"'
```

### Scheduler has gone silent (most common failure)

Symptom: adapter receives `HandleSchedule` calls but scheduling never completes; `docker logs my-scheduler --since 60s` returns nothing.

Cause: the kube-scheduler's watch connections to the adapter break when the adapter is restarted, and the scheduler enters a silent retry loop.

Fix: restart the scheduler container, then reset the adapter:

```bash
cd second-scheduler && docker compose down && docker compose up -d
sleep 10
curl -s -X DELETE http://localhost:8080/reset
```

**Note:** Always use `docker compose down && up` instead of `restart` — `restart` can fail with bind mount errors if files were recreated (inode changed).

### HTTP 409 — scheduling round already in progress

Cause: a previous simulation run crashed or was killed without calling `broker.sendResetRequestToControlPlane()`, leaving the adapter in a locked state.

Fix:
```bash
curl -s -X DELETE http://localhost:8080/reset
```

### HTTP 408 — scheduling timeout

Cause: adapter waited 60s for the kube-scheduler to bind pods but got no response. The adapter log will show exactly how many pods were scheduled vs pending before timeout.

Fix: restart scheduler, reset adapter, rerun.

### Verifying the scheduler is ready

```bash
docker logs my-scheduler --since 15s 2>&1 | grep "Caches populated" | wc -l
# Should be ~15 (one per resource type)
```

### Verifying the adapter is up

```bash
curl -s -X DELETE http://localhost:8080/reset
# Should return: Reset complete
```

---

## Killing Stale Processes

```bash
# Kill all adapter instances (use -x for exact match, NOT -f which can self-kill)
pkill -9 -x adapter-linux

# Kill a stuck Maven simulation
pkill -9 -f "exec:java"

# Check what's on port 8080
ss -tlnp | grep 8080
lsof -i :8080 | head -5
```

---

## Always Restart Scheduler and Adapter Together

If the adapter is restarted for any reason, the kube-scheduler must also be restarted. The scheduler's watch connections are tied to the adapter instance and do not recover automatically.

```bash
pkill -9 -x adapter-linux
cd second-scheduler && docker compose down && docker compose up -d
sleep 10
cd ../k8s-cloudsim-adapter && setsid ./adapter-linux --scheduler=default-scheduler </dev/null >/tmp/adapter.log 2>&1 &
sleep 2 && curl -s -X DELETE http://localhost:8080/reset
```
