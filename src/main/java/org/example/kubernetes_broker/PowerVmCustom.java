package org.example.kubernetes_broker;

import org.cloudbus.cloudsim.Cloudlet;
import org.cloudbus.cloudsim.CloudletScheduler;
import org.cloudbus.cloudsim.core.CloudSim;
import org.cloudbus.cloudsim.core.GuestEntity;
import org.cloudbus.cloudsim.power.PowerVm;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

public class PowerVmCustom extends PowerVm {

    private final int preferredHostId;
    private final Map<String, String> labels;

    public PowerVmCustom(
            int id, int userId, double mips, int numberOfPes, int ram, long bw, long size,
            int priority, String vmm, CloudletScheduler cloudletScheduler,
            double schedulingInterval, int preferredHostId
    ) {
        this(id, userId, mips, numberOfPes, ram, bw, size, priority, vmm,
             cloudletScheduler, schedulingInterval, preferredHostId, Collections.emptyMap());
    }

    public PowerVmCustom(
            int id, int userId, double mips, int numberOfPes, int ram, long bw, long size,
            int priority, String vmm, CloudletScheduler cloudletScheduler,
            double schedulingInterval, int preferredHostId, Map<String, String> labels
    ) {
        super(id, userId, mips, numberOfPes, ram, bw, size, priority, vmm, cloudletScheduler, schedulingInterval);
        this.preferredHostId = preferredHostId;
        this.labels = labels != null ? labels : Collections.emptyMap();
    }

    public int getPreferredHostId() { return preferredHostId; }
    public Map<String, String> getLabels() { return labels; }

    @Override
    public double getCurrentRequestedTotalMips() {
        if (isBeingInstantiated()) return getMips() * getNumberOfPes();

        final double time = CloudSim.clock();
        final double perPeMips = getMips();
        double demand = 0.0;

        for (Cloudlet cl : getCloudletScheduler().getCloudletExecList()) {
            demand += cl.getUtilizationOfCpu(time) * cl.getNumberOfPes() * perPeMips;
        }

        for (GuestEntity guest : getGuestList()) {
            demand += guest.getCurrentRequestedTotalMips();
        }

        double cap = getMips() * getNumberOfPes();
        return Math.min(demand, cap);
    }

    @Override
    public List<Double> getCurrentRequestedMips() {
        if (isBeingInstantiated()) {
            List<Double> l = new ArrayList<>(getNumberOfPes());
            for (int i = 0; i < getNumberOfPes(); i++) l.add(getMips());
            return l;
        }

        final double totalDemand = getCurrentRequestedTotalMips();
        final double perPeMips = getMips();
        final double peDemand = totalDemand / perPeMips;

        final int full = (int)Math.floor(Math.min(peDemand, getNumberOfPes()));
        final double frac = Math.max(0.0, Math.min(1.0, peDemand - full));

        List<Double> req = new ArrayList<>(getNumberOfPes());
        for (int i = 0; i < full; i++) req.add(perPeMips);
        if (full < getNumberOfPes() && frac > 0) req.add(frac * perPeMips);
        while (req.size() < getNumberOfPes()) req.add(0.0);

        for (GuestEntity guest : getGuestList()) {
            req.addAll(guest.getCurrentRequestedMips());
        }
        return req;
    }
}
