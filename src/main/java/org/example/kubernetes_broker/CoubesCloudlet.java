package org.example.kubernetes_broker;

import org.cloudbus.cloudsim.Cloudlet;
import org.cloudbus.cloudsim.UtilizationModel;

/**
 * Extension of CloudSim's Cloudlet with an explicit RAM request in MB.
 * When used with the COUBES adapter, this value is sent as the pod's
 * memory request for resource-aware scheduling.
 *
 * Plain Cloudlets (or CoubesCloudlets with ramRequest=0) impose no
 * memory constraint — backward compatible with existing behaviour.
 */
public class CoubesCloudlet extends Cloudlet {

    private final int ramRequest; // MB

    public CoubesCloudlet(int id, long length, int pesNumber, long fileSize, long outputSize,
                          UtilizationModel cpuModel, UtilizationModel ramModel, UtilizationModel bwModel,
                          int ramRequest) {
        super(id, length, pesNumber, fileSize, outputSize, cpuModel, ramModel, bwModel);
        this.ramRequest = ramRequest;
    }

    /** RAM requested by this cloudlet in MB. 0 means no constraint. */
    public int getRamRequest() {
        return ramRequest;
    }
}
