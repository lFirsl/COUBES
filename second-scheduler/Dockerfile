FROM registry.k8s.io/kube-scheduler:v1.33.0

# Copy scheduler configuration defining scheduling profiles (LeastAllocated vs MostAllocated)
COPY scheduler-config.yaml /etc/kube-scheduler/scheduler-config.yaml

# Copy kubeconfig pointing to the adapter's fake API server at localhost:8080
COPY kubeconfig.yaml /etc/kube-scheduler/kubeconfig.yaml

# Start kube-scheduler with the configuration file and secure port 10260
CMD ["kube-scheduler", "--config=/etc/kube-scheduler/scheduler-config.yaml", "--secure-port=10260"]