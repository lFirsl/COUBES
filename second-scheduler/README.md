# Custom Kubernetes Scheduler for COUBES

This directory contains a Dockerfile and configuration for running a custom `kube-scheduler` instance alongside the COUBES adapter. The scheduler supports multiple scheduling profiles, allowing you to switch between spreading (LeastAllocated) and bin-packing (MostAllocated) strategies for benchmarking different resource allocation policies.

---

## Prerequisites

Before running the custom scheduler, ensure you have:

1. **Docker Desktop** (or equivalent container runtime) installed and running
2. **COUBES adapter** running on port 8080
   - Start the adapter with: `cd ../k8s-cloudsim-adapter && go run main.go`
   - Or use test mode: `go run main.go --test-mode`

---

## Build and Run

### Option 1: Docker Build + Run

Build the scheduler image:

```bash
docker build -t my-scheduler .
```

Run the scheduler container:

```bash
docker run --rm --network host my-scheduler
```

The `--network host` flag is required so the scheduler can connect to the adapter on `localhost:8080`.

### Option 2: Docker Compose (Recommended)

Start the scheduler with a single command:

```bash
docker compose up
```

To run in detached mode:

```bash
docker compose up -d
```

To stop the scheduler:

```bash
docker compose down
```

---

## Profile Selection

The scheduler configuration (`my-scheduler.yaml`) defines two scheduling profiles. Pods are assigned to a profile by setting the `schedulerName` field in the pod spec.

### Targeting a Profile from the Adapter

When starting the COUBES adapter, use the `--scheduler` flag to specify which profile should be used for all pods created by CloudSim:

```bash
# Use the default-scheduler profile (spreading strategy)
go run main.go --scheduler=default-scheduler

# Use the my-scheduler profile (bin-packing strategy)
go run main.go --scheduler=my-scheduler
```

The adapter will set `pod.spec.schedulerName` to the value you provide, and the kube-scheduler will route the pod to the corresponding profile.

### Available Profiles

#### `default-scheduler` (Spreading Strategy)

- **Scoring Strategy:** LeastAllocated
- **Behavior:** Distributes pods evenly across nodes, preferring nodes with more available resources
- **Use Case:** Optimizes for availability and fault tolerance by avoiding resource concentration on individual nodes
- **When to Use:** Benchmarking scenarios where you want to minimize the impact of node failures or maximize workload distribution

#### `my-scheduler` (Bin-Packing Strategy)

- **Scoring Strategy:** MostAllocated
- **Behavior:** Packs pods tightly onto fewer nodes, preferring nodes with less available resources (higher utilization)
- **Use Case:** Optimizes for energy efficiency by consolidating workloads, allowing unused nodes to remain idle or be powered down
- **When to Use:** Benchmarking scenarios focused on resource efficiency, energy consumption, or cost optimization

### LeastAllocated vs MostAllocated

| Strategy | Goal | Node Selection | Best For |
|----------|------|----------------|----------|
| **LeastAllocated** | Spread workload | Prefers nodes with **more** free resources | High availability, fault tolerance, even distribution |
| **MostAllocated** | Pack workload | Prefers nodes with **less** free resources | Energy efficiency, cost reduction, resource consolidation |

Both strategies use equal weights for CPU and memory (weight: 1) to ensure balanced consideration of both resource types.

---

## Troubleshooting

### Scheduler Fails to Connect to Adapter

**Symptom:** Scheduler logs show connection errors like `dial tcp 127.0.0.1:8080: connect: connection refused`

**Solution:**
1. Verify the adapter is running: `curl http://localhost:8080/health` (or check if the adapter process is active)
2. **Windows/WSL2 users:** Ensure `kubeconfig.yaml` uses `http://host.docker.internal:8080` instead of `http://localhost:8080`. Docker Desktop on Windows/WSL2 requires this special hostname to reach services on the Windows host.
3. **Linux users:** Use `network_mode: "host"` in `docker-compose.yml` and `http://localhost:8080` in `kubeconfig.yaml`
4. Check that no firewall is blocking port 8080
5. Confirm the adapter is listening on `0.0.0.0:8080` (not just `127.0.0.1`)

### Increasing Log Verbosity

To see more detailed scheduler logs for debugging:

**Docker Run:**
```bash
docker run --rm --network host my-scheduler kube-scheduler \
  --config=/etc/kube-scheduler/scheduler-config.yaml \
  --secure-port=10260 \
  --v=4
```

**Docker Compose:**

Edit `docker-compose.yml` and change the `--v=2` flag to a higher value:

```yaml
command:
  - kube-scheduler
  - --config=/etc/kube-scheduler/my-scheduler.yaml
  - --secure-port=10260
  - --v=4  # or --v=5 for even more detail
```

**Verbosity Levels:**
- `--v=2` (default): Basic operational messages
- `--v=4`: Detailed scheduling decisions and plugin scores
- `--v=5`: Very verbose, includes all API requests and responses

### Pods Not Being Scheduled

**Symptom:** Pods remain in Pending state indefinitely

**Possible Causes:**
1. **Wrong scheduler name:** Verify the `--scheduler` flag on the adapter matches a profile name in `my-scheduler.yaml`
2. **No nodes available:** Check that CloudSim has registered nodes via `POST /nodes` to the adapter
3. **Resource constraints:** Ensure nodes have sufficient CPU/memory for the pod requests

**Debugging:**
- Increase log verbosity to `--v=4` to see why pods are being filtered or scored low
- Check adapter logs for incoming scheduling requests
- Verify the adapter's in-memory store has nodes: check adapter startup logs or add debug endpoints