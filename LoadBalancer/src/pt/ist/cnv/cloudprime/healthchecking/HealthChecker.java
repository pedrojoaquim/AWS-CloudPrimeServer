package pt.ist.cnv.cloudprime.healthchecking;

import pt.ist.cnv.cloudprime.LoadBalancer;
import pt.ist.cnv.cloudprime.autoscaling.AutoScaler;
import pt.ist.cnv.cloudprime.aws.WorkerInstance;
import pt.ist.cnv.cloudprime.util.Config;

import java.util.List;

/**
 * Created by ASUS on 05/05/2016.
 */
public class HealthChecker {

    private Thread hcThread;

    public void start(){
        this.hcThread = new Thread() {
            public void run() {
                try {
                    while(!Thread.interrupted()){
                        Thread.sleep(Config.HEALTH_CHECKER_SLEEP_TIME);
                        checkInstancesHealth();
                    }
                } catch(InterruptedException e) {
                    //finish
                }
            }
        };

        this.hcThread.start();
    }

    private void checkInstancesHealth() {

        LoadBalancer lb = LoadBalancer.getInstance();
        List<WorkerInstance> workers = lb.getUpdatedWorkers();

        for (WorkerInstance wi: workers) {
            if(IsFailedState(wi.getState())){
                AutoScaler.getInstance().instanceFailed(wi);
                LoadBalancer.getInstance().instanceFailed(wi);
            }
        }
    }

    private boolean IsFailedState(String state) {
        return "shutting-down".equals(state) ||
                "terminated".equals(state) ||
                "stopping".equals(state) ||
                "stopped".equals(state);
    }


    public void terminate() {
        this.hcThread.interrupt();
    }
}
