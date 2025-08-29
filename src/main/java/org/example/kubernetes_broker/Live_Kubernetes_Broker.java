package org.example.kubernetes_broker;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import org.cloudbus.cloudsim.Cloudlet;
import org.cloudbus.cloudsim.DatacenterBroker;
import org.cloudbus.cloudsim.DatacenterCharacteristics;
import org.cloudbus.cloudsim.Log;
import org.cloudbus.cloudsim.core.CloudActionTags;
import org.cloudbus.cloudsim.core.CloudSim;
import org.cloudbus.cloudsim.core.GuestEntity;
import org.cloudbus.cloudsim.core.SimEvent;
import org.cloudbus.cloudsim.lists.VmList;
import org.cloudbus.cloudsim.container.core.Container;
import org.cloudbus.cloudsim.Vm;
import org.cloudbus.cloudsim.core.CloudSimTags;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.*;


/*
NOTE: This is the OLD version of the Broker, before we decided to extend DatacenterBrokerEx instead.
      Therefore, this version is now OUTDATED - it has been left here to prevent older test examples which made use of it
      from breaking.
 */
public class Live_Kubernetes_Broker extends DatacenterBroker {

    private static final String CONTROL_PLANE_URL = "http://localhost:8080";
    private final HttpClient httpClient;

    //Map of
    private final Map<Integer, Cloudlet> pendingCloudlets = new HashMap<>();

    private final Map<Integer, Cloudlet> pendingCloudletsForScheduling;

    public Live_Kubernetes_Broker(String name) throws Exception {
        super(name);
        this.httpClient = HttpClient.newHttpClient();
        ObjectMapper objectMapper = new ObjectMapper();
        this.pendingCloudletsForScheduling = new HashMap<>();
    }

    @Override
    public void startEntity() {
        super.startEntity();
        schedule(getId(), 0.0, CloudActionTags.RESOURCE_CHARACTERISTICS_REQUEST);
    }

    @Override
    public void submitCloudletList(List<? extends Cloudlet> list) {
        super.submitCloudletList(list);
        int totalInitialCloudlets = list.size();
    }

    @Override
    public void processEvent(SimEvent ev) {

        switch (ev.getTag()) {
            case CloudActionTags.RESOURCE_CHARACTERISTICS_REQUEST -> processResourceCharacteristicsRequest(ev);
            case CloudActionTags.RESOURCE_CHARACTERISTICS -> processResourceCharacteristics(ev);
            case CloudActionTags.VM_CREATE_ACK -> processVmCreateAck(ev);
            case CloudActionTags.CLOUDLET_RETURN -> {
                processCloudletReturn(ev);
            }
            case CloudActionTags.BLANK -> {
                Log.printlnConcat(CloudSim.clock(), ": Processing blank event to inject time manually.");
            }
            case CloudActionTags.END_OF_SIMULATION ->
                    Log.printlnConcat(CloudSim.clock(), ": ", getName(), ": Received END_OF_SIMULATION event. Broker will now proceed with final cleanup.");

            case null, default -> processOtherEvent(ev);
        }
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
        Log.println("Submitting all cloudlets to Control Plane in a single batch...");

        List<Cloudlet> cloudletList = getCloudletList();
        List<ObjectNode> podJsonList = new ArrayList<>();
        ObjectMapper mapper = new ObjectMapper();

        for (Cloudlet cloudlet : cloudletList) {
            ObjectNode podJson = mapper.createObjectNode();
            podJson.put("id", cloudlet.getCloudletId());
            podJson.put("name", "cloudlet-" + cloudlet.getCloudletId());
            podJson.put("length", cloudlet.getCloudletLength());
            podJson.put("pes", cloudlet.getNumberOfPes());
            podJson.put("fileSize", cloudlet.getCloudletFileSize());
            podJson.put("outputSize", cloudlet.getCloudletOutputSize());
            podJson.put("utilizationCpu", cloudlet.getUtilizationModelCpu().getUtilization(0)); // or estimate max
            podJson.put("utilizationRam", cloudlet.getUtilizationModelRam().getUtilization(0));
            podJson.put("utilizationBw", cloudlet.getUtilizationModelBw().getUtilization(0));

            podJsonList.add(podJson);
            pendingCloudletsForScheduling.put(cloudlet.getCloudletId(), cloudlet);
        }

        try {
            String requestBody = mapper.writeValueAsString(podJsonList);
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(CONTROL_PLANE_URL + "/schedule-pods"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            System.out.println("Requesting: " + CONTROL_PLANE_URL + "/schedule-pods");

            if (response.statusCode() == 200) {
                ArrayNode scheduledPods = (ArrayNode) mapper.readTree(response.body());

                for (JsonNode podNode : scheduledPods) {
                    int cloudletId = podNode.get("id").asInt();
                    String status = podNode.get("status").asText();

                    Cloudlet cloudlet = pendingCloudletsForScheduling.get(cloudletId);
                    if (cloudlet == null) continue;

                    switch (status) {
                        case "Scheduled" -> {
                            int nodeID = podNode.has("vmId") ? podNode.get("vmId").asInt() : -1;
                            String nodeName = podNode.has("nodeName") ? podNode.get("nodeName").asText() : "N/A";
                            Log.printlnConcat(CloudSim.clock(), ": ", getName(), ": Pod ", cloudletId,
                                    " scheduled on Node ", nodeName, " (VM ID ", nodeID, ")");
                            if (nodeID != -1) {
                                submitCloudletToVmInCloudSim(cloudlet, nodeID);
                            } else {
                                cloudlet.setCloudletStatus(Cloudlet.CloudletStatus.FAILED);
                                getCloudletReceivedList().add(cloudlet);
                            }
                            submitCloudletToVmInCloudSim(cloudlet, nodeID);
                        }
                        case "Unschedulable" -> {
                            Log.printlnConcat(CloudSim.clock(), ": ", getName(), ": Pod ", cloudletId, " is unschedulable.");
                            cloudlet.setCloudletStatus(Cloudlet.CloudletStatus.FAILED);
                            getCloudletReceivedList().add(cloudlet);
                        }
                        default -> {
                            Log.printlnConcat(CloudSim.clock(), ": ", getName(), ": Unknown status for Pod ", cloudletId, ": ", status);
                            cloudlet.setCloudletStatus(Cloudlet.CloudletStatus.FAILED);
                            getCloudletReceivedList().add(cloudlet);
                        }
                    }

                    pendingCloudletsForScheduling.remove(cloudletId);
                }
            } else {
                Log.printlnConcat(getName(), ": Failed to batch schedule cloudlets. HTTP ", response.statusCode());
            }

        } catch (Exception e) {
            Log.printlnConcat(getName(), ": Error submitting cloudlets batch: ", e.getMessage());
        }

        Log.printlnConcat("Finished scheduling batch. Submitting to CloudSim.");
        super.submitCloudlets();
    }

    private void deleteCloudletInControlPlane(int cloudletId) {
        String podName = "cspod-" + cloudletId;

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(CONTROL_PLANE_URL + "/pod/" + podName))
                .DELETE()
                .build();

        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 200) {
                Log.println("Deleted pod " + podName + " from control plane.");
            } else {
                Log.println("Failed to delete pod " + podName + ", status: " + response.statusCode());
            }
        } catch (IOException | InterruptedException e) {
            e.printStackTrace();
        }
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
                .build();

        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 200) {
                Log.println("Sent reset request to Control Plane.");
            } else {
                Log.println("Failed to reset Control Plane. Status: " + response.statusCode()
                        + ", Body: " + response.body());
            }
        } catch (IOException | InterruptedException e) {
            Log.println("Error sending reset request to Control Plane: " + e.getMessage());
            Thread.currentThread().interrupt();
        }
    }
}
