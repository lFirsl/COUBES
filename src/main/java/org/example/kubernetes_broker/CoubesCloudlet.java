package org.example.kubernetes_broker;

import org.cloudbus.cloudsim.Cloudlet;
import org.cloudbus.cloudsim.UtilizationModel;

import java.util.Collections;
import java.util.Map;

/**
 * Extension of CloudSim's Cloudlet with:
 * - Explicit RAM request (MB) for memory-aware scheduling
 * - Labels (key-value pairs) set on the K8s pod metadata
 * - Affinity/anti-affinity group strings for pod co-location/separation
 * - Per-rule hard/soft control (hard = Required, soft = Preferred)
 *
 * Plain Cloudlets (or CoubesCloudlets with defaults) behave identically
 * to before — all new fields are optional.
 */
public class CoubesCloudlet extends Cloudlet {

    private final int ramRequest;
    private final Map<String, String> labels;
    private final String affinityGroup;
    private final String antiAffinityGroup;
    private final boolean hardAffinity;      // true = Required, false = Preferred
    private final boolean hardAntiAffinity;  // true = Required, false = Preferred

    /** Minimal constructor — RAM only, no affinity. */
    public CoubesCloudlet(int id, long length, int pesNumber, long fileSize, long outputSize,
                          UtilizationModel cpuModel, UtilizationModel ramModel, UtilizationModel bwModel,
                          int ramRequest) {
        this(id, length, pesNumber, fileSize, outputSize, cpuModel, ramModel, bwModel,
             ramRequest, Collections.emptyMap(), null, null, true, true);
    }

    /** Affinity constructor — both rules default to hard. */
    public CoubesCloudlet(int id, long length, int pesNumber, long fileSize, long outputSize,
                          UtilizationModel cpuModel, UtilizationModel ramModel, UtilizationModel bwModel,
                          int ramRequest, Map<String, String> labels,
                          String affinityGroup, String antiAffinityGroup) {
        this(id, length, pesNumber, fileSize, outputSize, cpuModel, ramModel, bwModel,
             ramRequest, labels, affinityGroup, antiAffinityGroup, true, true);
    }

    /** Full constructor — independent hard/soft per rule. */
    public CoubesCloudlet(int id, long length, int pesNumber, long fileSize, long outputSize,
                          UtilizationModel cpuModel, UtilizationModel ramModel, UtilizationModel bwModel,
                          int ramRequest, Map<String, String> labels,
                          String affinityGroup, String antiAffinityGroup,
                          boolean hardAffinity, boolean hardAntiAffinity) {
        super(id, length, pesNumber, fileSize, outputSize, cpuModel, ramModel, bwModel);
        this.ramRequest = ramRequest;
        this.labels = labels != null ? labels : Collections.emptyMap();
        this.affinityGroup = affinityGroup;
        this.antiAffinityGroup = antiAffinityGroup;
        this.hardAffinity = hardAffinity;
        this.hardAntiAffinity = hardAntiAffinity;
    }

    public int getRamRequest() { return ramRequest; }
    public Map<String, String> getLabels() { return labels; }
    public String getAffinityGroup() { return affinityGroup; }
    public String getAntiAffinityGroup() { return antiAffinityGroup; }
    public boolean isHardAffinity() { return hardAffinity; }
    public boolean isHardAntiAffinity() { return hardAntiAffinity; }
}
