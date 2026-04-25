package org.example.kubernetes_broker;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.cloudbus.cloudsim.*;
import org.cloudbus.cloudsim.EX.DatacenterBrokerEX;
import org.cloudbus.cloudsim.container.core.Container;
import org.cloudbus.cloudsim.core.*;
import org.cloudbus.cloudsim.lists.VmList;
import org.example.metrics.PerformanceMetrics;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

public class Live_Kubernetes_Broker_Ex extends DatacenterBrokerEX {

    private static final String CONTROL_PLANE_URL = "http://localhost:8080";
    private final HttpClient httpClient;
    private int guestIndex = 0;
    private int roundCounter = 0;

    //Maps
    HashMap<Integer,Cloudlet> cloudletsSubmittedToMiddle;
    HashMap<Integer,Cloudlet> cloudletsReadyForCloudsim;
    
    // Completed cloudlet IDs to send in next snapshot
    private final List<Integer> completedSinceLastRound = new ArrayList<>();
    
    // Guard: true when a RESCHEDULE_PENDING event is already queued, prevents duplicate events
    private boolean reschedulePending = false;
    // Optional PerformanceMetrics integration
    private PerformanceMetrics performanceMetrics;

    //Variables for throughput rolling average metrics (Prototype, not yet validated)
    // --- Throughput metrics (pods/sec) ---
    private long tpTotalPods = 0L;
    private long tpTotalNanos = 0L;
    private long tpBatches = 0L;

    // EWMA (exponentially-weighted moving average) of instantaneous batch throughput
    private double tpEwma = 0.0;
    private final double TP_ALPHA = 0.3;     // Can be tuned: 0.1 (smoother) .. 0.5 (more reactive)

    // Sliding window over the last N batches
    private final int TP_WINDOW = 10;
    private final java.util.ArrayDeque<long[]> tpWindow = new java.util.ArrayDeque<>();
    private long tpWindowPods = 0L;
    private long tpWindowNanos = 0L;

    public Live_Kubernetes_Broker_Ex(String name) throws Exception {
        super(name, -1.0F);
        this.httpClient = HttpClient.newHttpClient();
        this.cloudletsSubmittedToMiddle = new HashMap<Integer,Cloudlet>();
        this.cloudletsReadyForCloudsim = new HashMap<Integer,Cloudlet>();
        this.performanceMetrics = null;
    }

    public Live_Kubernetes_Broker_Ex(String name, double lifeLength) throws Exception {
        super(name,lifeLength);
        this.httpClient = HttpClient.newHttpClient();
        this.cloudletsSubmittedToMiddle = new HashMap<Integer,Cloudlet>();
        this.cloudletsReadyForCloudsim = new HashMap<Integer,Cloudlet>();
        this.performanceMetrics = null;
    }
    
    /**
     * Sets the optional PerformanceMetrics instance for tracking scheduling latency and throughput.
     * @param perf the PerformanceMetrics instance, or null to disable performance tracking
     */
    public void setPerformanceMetrics(PerformanceMetrics perf) {
        this.performanceMetrics = perf;
    }

    @Override
    protected void processResourceCharacteristics(SimEvent ev) {
        DatacenterCharacteristics characteristics = (DatacenterCharacteristics) ev.getData();
        getDatacenterCharacteristicsList().put(characteristics.getId(), characteristics);

        if (getDatacenterCharacteristicsList().size() == getDatacenterIdsList().size()) {
            createVmsInDatacenter(getDatacenterIdsList().getFirst());
        }
    }

    @Override
    protected void processVmCreateAck(SimEvent ev) {
        int[] data = (int[]) ev.getData();
        int datacenterId = data[0];
        int vmId = data[1];
        int result = data[2];

        GuestEntity guest = VmList.getById(getGuestList(), vmId);

        if (result == CloudSimTags.TRUE) {
            getVmsToDatacentersMap().put(vmId, datacenterId);
            getGuestsCreatedList().add(guest);
            Log.printlnConcat(CloudSim.clock(), ": ", getName(), ": ", guest.getClassName(), " #", vmId,
                    " has been created in Datacenter #", datacenterId, ", ", guest.getHost().getClassName(), " #",
                    guest.getHost().getId());
        } else {
            Log.printlnConcat(CloudSim.clock(), ": ", getName(), ": Creation of ", guest.getClassName(), " #", vmId,
                    " failed in Datacenter #", datacenterId);
        }

        incrementVmsAcks();

        if (getVmsRequested() == getVmsAcks()) {
            sendAllActiveNodesToControlPlane();
            if (getGuestsCreatedList().size() == getGuestList().size()) {
                submitCloudlets();
            } else {
                boolean triedAllDatacenters = true;
                for (int nextDatacenterId : getDatacenterIdsList()) {
                    if (!getDatacenterRequestedIdsList().contains(nextDatacenterId)) {
                        createVmsInDatacenter(nextDatacenterId);
                        triedAllDatacenters = false;
                        break;
                    }
                }

                if (triedAllDatacenters) {
                    if (!getGuestsCreatedList().isEmpty()) {
                        submitCloudlets();
                    } else {
                        Log.printlnConcat(CloudSim.clock(), ": ", getName(),
                                ": none of the required VMs/Containers could be created. CloudSim will terminate naturally when no more events remain.");
                        // No explicit terminateSimulation() here.
                    }
                }
            }
        }
    }

    private void sendAllActiveNodesToControlPlane() {
        List<ObjectNode> nodeJsons = new ArrayList<>();
        ObjectMapper mapper = new ObjectMapper();

        for (GuestEntity guest : getGuestsCreatedList()) {
            ObjectNode nodeJson = mapper.createObjectNode();
            nodeJson.put("id", guest.getId());
            nodeJson.put("mipsAvailable", (int) guest.getMips());
            nodeJson.put("ramAvailable", guest.getRam());
            nodeJson.put("pes", guest.getNumberOfPes());
            nodeJson.put("bw", guest.getBw());
            nodeJson.put("size", guest.getSize());
            nodeJson.put("type", guest instanceof Vm ? "vm" : "container");

            String name = guest instanceof Vm ? "vm-" + guest.getId()
                    : guest instanceof Container ? "container-" + guest.getId()
                    : "guest-" + guest.getId();
            nodeJson.put("name", name);

            nodeJsons.add(nodeJson);
        }

        if (nodeJsons.isEmpty()) return;

        try {
            String payload = mapper.writeValueAsString(nodeJsons);
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(CONTROL_PLANE_URL + "/nodes"))
                    .header("Content-Type", "application/json")
                    .header("X-Round-Id", String.valueOf(roundCounter))
                    .POST(HttpRequest.BodyPublishers.ofString(payload))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 200) {
                Log.println(CloudSim.clock() + ": Synced active nodes: " + payload);
            } else {
                Log.println(CloudSim.clock() + ": Failed to sync nodes: " + response.body());
            }
        } catch (IOException | InterruptedException e) {
            e.printStackTrace();
        }
    }

    @Override
    protected void submitCloudlets() {
        Log.println("Submitting all cloudlets to Control Plane [round=" + roundCounter + "]...");

        // 1. Record submission timestamps for performance metrics
        for (Cloudlet cloudlet : getCloudletList()) {
            if (performanceMetrics != null) {
                performanceMetrics.recordSubmission(cloudlet.getCloudletId());
            }
        }

        // 2. Build SimulationSnapshot
        ObjectMapper mapper = new ObjectMapper();
        ObjectNode snapshot = mapper.createObjectNode();
        
        // Add nodes
        ArrayNode nodesArray = mapper.createArrayNode();
        for (GuestEntity guest : getGuestsCreatedList()) {
            ObjectNode nodeJson = mapper.createObjectNode();
            nodeJson.put("id", guest.getId());
            nodeJson.put("mipsAvailable", (int) guest.getMips());
            nodeJson.put("ramAvailable", guest.getRam());
            nodeJson.put("pes", guest.getNumberOfPes());
            nodeJson.put("bw", guest.getBw());
            nodeJson.put("size", guest.getSize());
            nodeJson.put("type", guest instanceof Vm ? "vm" : "container");
            String name = guest instanceof Vm ? "vm-" + guest.getId()
                    : guest instanceof Container ? "container-" + guest.getId()
                    : "guest-" + guest.getId();
            nodeJson.put("name", name);
            nodesArray.add(nodeJson);
        }
        snapshot.set("nodes", nodesArray);
        
        // Add pending pods (new from getCloudletList + previously unschedulable from cloudletsSubmittedToMiddle)
        ArrayNode podsArray = mapper.createArrayNode();
        for (Cloudlet cloudlet : getCloudletList()) {
            podsArray.add(buildPodJson(mapper, cloudlet));
            cloudletsSubmittedToMiddle.put(cloudlet.getCloudletId(), cloudlet);
            if (performanceMetrics != null) {
                performanceMetrics.recordSubmission(cloudlet.getCloudletId());
            }
        }
        // Only re-send previously unschedulable pods when capacity has changed
        // (i.e., completions happened). Otherwise the scheduler already has them
        // in its backoff queue and will re-evaluate them when capacity frees up.
        if (!completedSinceLastRound.isEmpty()) {
            for (Cloudlet cloudlet : cloudletsSubmittedToMiddle.values()) {
                if (getCloudletList().contains(cloudlet)) continue;
                podsArray.add(buildPodJson(mapper, cloudlet));
            }
        }
        snapshot.set("pods", podsArray);
        
        // Add completed pod IDs
        ArrayNode completedArray = mapper.createArrayNode();
        for (Integer completedId : completedSinceLastRound) {
            completedArray.add(completedId);
        }
        snapshot.set("completedPodIds", completedArray);
        completedSinceLastRound.clear();
        
        getCloudletList().clear();

        // 3. Submit to control plane via POST /schedule
        BatchDecisionResponse batchDecision = submitSimulationSnapshot(snapshot);
        if (batchDecision == null) {
            Log.printlnConcat(CloudSim.clock(), ": No batch decision received. Skipping pod response process");
            return;
        }

        // 4. Process scheduling result
        processBatchDecision(batchDecision);
    }

    private BatchDecisionResponse submitSimulationSnapshot(ObjectNode snapshot) {
            roundCounter++;
        try {
            ObjectMapper mapper = new ObjectMapper();
            mapper.findAndRegisterModules(); // Register JSR310 module for Instant deserialization
            String requestBody = mapper.writeValueAsString(snapshot);
            
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(CONTROL_PLANE_URL + "/schedule"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                    .header("X-Round-Id", String.valueOf(roundCounter))
                    .build();

            // Determine how many pods we're sending in this batch
            final int podsInBatch = snapshot.get("pods").size();

            final long t0 = System.nanoTime();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            final long t1 = System.nanoTime();

            // Record throughput metrics (even on non-200)
            tpRecord(podsInBatch, t1 - t0);

            if (response.statusCode() == 200) {
                Log.printlnConcat(getName(), ": [round=" + roundCounter + "] Batch scheduled. "
                        , "inst=", String.format("%.2f", (podsInBatch / ((t1 - t0) / 1e9)))
                        , " pods/s; ewma=", String.format("%.2f", tpEwma())
                        , "; window(", TP_WINDOW, ")=", String.format("%.2f", tpWindowAvg())
                        , "; overall=", String.format("%.2f", tpOverall())
                        , " [batches=", tpBatchCount(), "]"
                );
                
                BatchDecisionResponse batchDecision = mapper.readValue(response.body(), BatchDecisionResponse.class);
                
                // Record batch decision for performance metrics
                if (performanceMetrics != null) {
                    performanceMetrics.recordBatchDecision(batchDecision);
                }
                
                return batchDecision;
            } else if (response.statusCode() == 408) {
                Log.printlnConcat(getName(), ": Scheduling timeout (HTTP 408). Marking all pending cloudlets as failed.");
                // Mark all pending cloudlets as failed
                for (Cloudlet cloudlet : cloudletsSubmittedToMiddle.values()) {
                    cloudlet.setCloudletStatus(Cloudlet.CloudletStatus.FAILED);
                    getCloudletReceivedList().add(cloudlet);
                }
                cloudletsSubmittedToMiddle.clear();
                return null;
            } else {
                Log.printlnConcat(getName(), ": Failed to schedule batch. HTTP ", response.statusCode(), ": ", response.body());
                throw new RuntimeException("Adapter returned HTTP " + response.statusCode() + ": " + response.body());
            }
        } catch (Exception e) {
            Log.printlnConcat(getName(), ": Error scheduling cloudlets: ", e.getMessage());
            throw new RuntimeException("Error scheduling cloudlets", e);
        }
    }

    private void processBatchDecision(BatchDecisionResponse batchDecision) {
        Log.printlnConcat(getName(), ": [round=" + roundCounter + "] Processing batch decision");
        
        // Process scheduled pods
        if (batchDecision.getScheduled() != null) {
            for (BatchDecisionResponse.PodAssignment assignment : batchDecision.getScheduled()) {
                int cloudletId = assignment.getPodId();
                int nodeId = assignment.getNodeId();
                
                Cloudlet cloudlet = cloudletsSubmittedToMiddle.getOrDefault(cloudletId, null);
                if (cloudlet == null) {
                    Log.printlnConcat(getName(), ": Pod ", cloudletId, " not found in pending cloudlets for scheduling. It was supposed to be on node ", nodeId);
                    continue;
                }
                
                Log.printlnConcat(CloudSim.clock(), ": ", getName(), ": Pod ", cloudletId, " scheduled on node ", nodeId);
                submitCloudletToVmInCloudSim(cloudlet, nodeId);
                cloudletsSubmittedToMiddle.remove(cloudletId);
                cloudletsReadyForCloudsim.put(cloudletId, cloudlet);
            }
        }
        
        // Process unschedulable pods — keep them in cloudletsSubmittedToMiddle so they
        // are resubmitted when nodes free up (via RESCHEDULE_PENDING event).
        if (batchDecision.getUnschedulable() != null && !batchDecision.getUnschedulable().isEmpty()) {
            Log.printlnConcat(CloudSim.clock(), ": ", getName(), ": ",
                    batchDecision.getUnschedulable().size(), " pods pending (no free nodes yet), will retry when nodes free up");
            // Pods remain in cloudletsSubmittedToMiddle — rescheduling triggered by processCloudletReturn
        }

        Log.println("Finished scheduling batch. Submitting to CloudSim.");
        cloudSimAllocation();

        // Detect permanently unschedulable pods: if nothing was scheduled this round
        // and nothing is running, no capacity will ever free up — give up on remaining pods.
        if (cloudletsSubmitted == 0 && !cloudletsSubmittedToMiddle.isEmpty()
                && (batchDecision.getScheduled() == null || batchDecision.getScheduled().isEmpty())) {
            Log.printlnConcat(CloudSim.clock(), ": ", getName(),
                    ": WARNING: ", cloudletsSubmittedToMiddle.size(),
                    " pods are permanently unschedulable (no running pods to free capacity). Marking as FAILED.");
            cloudletsSubmittedToMiddle.clear();
            if (getCloudletList().isEmpty() && cloudletsReadyForCloudsim.isEmpty()) {
                clearDatacenters();
                finishExecution();
            }
        }
    }

    private String serializeCloudletsForSubmission(List<Cloudlet> cloudletList){
        return serializeCloudletsForSubmission(cloudletList,false);
    }

    private String serializeCloudletsForSubmission(List<Cloudlet> cloudletList,boolean deletion) {
        ObjectMapper mapper = new ObjectMapper();
        List<ObjectNode> podJsonList = new ArrayList<>();

        for (Cloudlet cloudlet : cloudletList) {
            ObjectNode podJson = mapper.createObjectNode();
            podJson.put("id", cloudlet.getCloudletId());
            podJson.put("name", "cloudlet-" + cloudlet.getCloudletId());
            podJson.put("length", cloudlet.getCloudletLength());
            podJson.put("pes", cloudlet.getNumberOfPes());
            podJson.put("fileSize", cloudlet.getCloudletFileSize());
            podJson.put("outputSize", cloudlet.getCloudletOutputSize());
            podJson.put("utilizationCpu", cloudlet.getUtilizationModelCpu().getUtilization(0));
            podJson.put("utilizationRam", cloudlet.getUtilizationModelRam().getUtilization(0));
            podJson.put("utilizationBw", cloudlet.getUtilizationModelBw().getUtilization(0));
            podJsonList.add(podJson);
            if(!deletion) {
                cloudletsSubmittedToMiddle.put(cloudlet.getCloudletId(), cloudlet);
            }
        }

        try {
            return mapper.writeValueAsString(podJsonList);
        } catch (Exception e) {
            Log.printlnConcat(getName(), ": Error serializing cloudlets: ", e.getMessage());
            return null;
        }
    }

    private String serializeSingleCloudletForSubmission(Cloudlet cloudlet){
        return serializeSingleCloudletForSubmission(cloudlet,false);
    }
    private String serializeSingleCloudletForSubmission(Cloudlet cloudlet,boolean deletion) {
        return serializeCloudletsForSubmission(List.of(cloudlet),deletion);
    }

    protected void cloudSimAllocation() {
        List<Cloudlet> successfullySubmitted = new ArrayList<>();
        for (Integer key : cloudletsReadyForCloudsim.keySet()) {
            Cloudlet cloudlet = cloudletsReadyForCloudsim.get(key);
            GuestEntity vm;
            // if user didn't bind this cloudlet and it has not been executed yet
            if (cloudlet.getGuestId() == -1) {
                Log.printlnConcat("ERROR: We SOMEHOW got a guestID of -1");
                vm = getGuestsCreatedList().get(guestIndex);
            } else { // submit to the specific vm
                vm = VmList.getById(getGuestsCreatedList(), cloudlet.getGuestId());
                if (vm == null) { // vm was not created
                    vm = VmList.getById(getGuestList(), cloudlet.getGuestId()); // check if exists in the submitted list

                    if(!Log.isDisabled()) {
                        if (vm != null) {
                            Log.printlnConcat(CloudSim.clock(), ": ", getName(), ": Postponing execution of cloudlet ",
                                    cloudlet.getCloudletId(), ": bount ", vm.getClassName(), " #", vm.getId(), " not available");
                        } else {
                            Log.printlnConcat(CloudSim.clock(), ": ", getName(), ": Postponing execution of cloudlet ",
                                    cloudlet.getCloudletId(), ": bount guest entity of id ", cloudlet.getGuestId(), " doesn't exist");
                        }
                    }
                    Log.printlnConcat("We're continuing here, for some reason");
                    continue;
                }
            }

            if (!Log.isDisabled()) {
                Log.printlnConcat(CloudSim.clock(), ": ", getName(), ": Sending ", cloudlet.getClass().getSimpleName(),
                        " #", cloudlet.getCloudletId(), " to " + vm.getClassName() + " #", vm.getId());
            }

            cloudlet.setGuestId(vm.getId());
            sendNow(getVmsToDatacentersMap().get(vm.getId()), CloudActionTags.CLOUDLET_SUBMIT, cloudlet);
            cloudletsSubmitted++;
            guestIndex = (guestIndex + 1) % getGuestsCreatedList().size();
            getCloudletSubmittedList().add(cloudlet);
            successfullySubmitted.add(cloudlet);
        }

        // remove submitted cloudlets from waiting list
        for(Cloudlet cloudlet : successfullySubmitted) {
            cloudletsReadyForCloudsim.remove(cloudlet.getCloudletId());
        }
    }

    @Override
    protected void processCloudletReturn(SimEvent ev) {
        Cloudlet cloudlet = (Cloudlet) ev.getData();
        Log.printlnConcat(CloudSim.clock(), ": ", getName(), ": ", cloudlet.getClass().getSimpleName()," #", cloudlet.getCloudletId(), " return received");

        // Add completed cloudlet ID to list for next snapshot
        completedSinceLastRound.add(cloudlet.getCloudletId());

        getCloudletReceivedList().add(cloudlet);
        cloudletsSubmitted--;

        // If pods are still waiting to be scheduled, trigger a rescheduling round
        // after 1 simulated second to batch any other completions in the same window.
        if (!cloudletsSubmittedToMiddle.isEmpty() && !reschedulePending) {
            reschedulePending = true;
            send(getId(), 1.0, CloudActionTagsEx.RESCHEDULE_PENDING, null);
        }

        // Note: we do NOT call finishExecution() here. The simulation terminates
        // naturally when no more future events exist. Calling finishExecution() early
        // would kill the datacenter before delayed cloudlet waves arrive.
    }

    @Override
    protected void processOtherEvent(SimEvent ev) {
        if (ev.getTag() == CloudActionTagsEx.RESCHEDULE_PENDING) {
            reschedulePending = false;
            if (!cloudletsSubmittedToMiddle.isEmpty()) {
                Log.printlnConcat(CloudSim.clock(), ": ", getName(), ": Rescheduling ",
                        cloudletsSubmittedToMiddle.size(), " pending cloudlets after completions: ", completedSinceLastRound);
                submitCloudlets();
            }
        } else {
            super.processOtherEvent(ev);
        }
    }

    private ObjectNode buildPodJson(ObjectMapper mapper, Cloudlet cloudlet) {
        ObjectNode podJson = mapper.createObjectNode();
        podJson.put("id", cloudlet.getCloudletId());
        podJson.put("name", "cloudlet-" + cloudlet.getCloudletId());
        podJson.put("length", cloudlet.getCloudletLength());
        podJson.put("pes", cloudlet.getNumberOfPes());
        podJson.put("fileSize", cloudlet.getCloudletFileSize());
        podJson.put("outputSize", cloudlet.getCloudletOutputSize());
        podJson.put("utilizationCpu", cloudlet.getUtilizationModelCpu().getUtilization(0));
        podJson.put("utilizationRam", cloudlet.getUtilizationModelRam().getUtilization(0));
        podJson.put("utilizationBw", cloudlet.getUtilizationModelBw().getUtilization(0));
        return podJson;
    }

    private void submitCloudletToVmInCloudSim(Cloudlet cloudlet, int vmId) {
        GuestEntity targetVm = VmList.getById(getGuestsCreatedList(), vmId);

        if (targetVm == null) {
            Log.printlnConcat(CloudSim.clock(), ": ", getName(), ": CRITICAL ERROR: Target VM/Container #", vmId, " not found for Cloudlet #", cloudlet.getCloudletId(), " in CloudSim's list. Marking as failed.");
            cloudlet.setCloudletStatus(Cloudlet.CloudletStatus.FAILED);
            getCloudletReceivedList().add(cloudlet);
            return;
        }

        cloudlet.setGuestId(targetVm.getId());
    }


    public void sendResetRequestToControlPlane() {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(CONTROL_PLANE_URL + "/reset"))
                .DELETE()
                .header("X-Round-Id", String.valueOf(roundCounter))
                .build();

        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 200) {
                Log.println("Sent reset request to Control Plane.");
            } else {
                Log.println("WARNING: Failed to reset Control Plane. Status: " + response.statusCode()
                        + ", Body: " + response.body());
            }
        } catch (IOException | InterruptedException e) {
            Log.println("WARNING: Error sending reset request to Control Plane: " + e.getMessage());
            Thread.currentThread().interrupt();
        }
    }

    //Throughput specific helper functions
    private synchronized void tpRecord(int pods, long durationNanos) {
        if (pods <= 0 || durationNanos <= 0) return;

        tpTotalPods  += pods;
        tpTotalNanos += durationNanos;
        tpBatches++;

        final double instRate = pods / (durationNanos / 1_000_000_000.0);
        tpEwma = (tpBatches == 1) ? instRate : (TP_ALPHA * instRate + (1 - TP_ALPHA) * tpEwma);

        tpWindow.addLast(new long[]{pods, durationNanos});
        tpWindowPods  += pods;
        tpWindowNanos += durationNanos;

        while (tpWindow.size() > TP_WINDOW) {
            long[] old = tpWindow.removeFirst();
            tpWindowPods  -= old[0];
            tpWindowNanos -= old[1];
        }
    }

    public synchronized double tpOverall() {
        return tpTotalNanos == 0 ? 0.0 : tpTotalPods / (tpTotalNanos / 1e9);
    }

    public synchronized double tpWindowAvg() {
        return tpWindowNanos == 0 ? 0.0 : tpWindowPods / (tpWindowNanos / 1e9);
    }

    public synchronized double tpEwma() {
        return tpEwma;
    }

    public synchronized long tpBatchCount() {
        return tpBatches;
    }

}
