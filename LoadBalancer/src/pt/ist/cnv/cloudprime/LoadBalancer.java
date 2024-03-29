package pt.ist.cnv.cloudprime;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import pt.ist.cnv.cloudprime.autoscaling.AutoScaler;
import pt.ist.cnv.cloudprime.aws.AWSManager;
import pt.ist.cnv.cloudprime.aws.WorkerInstance;
import pt.ist.cnv.cloudprime.aws.metrics.RequestMetrics;
import pt.ist.cnv.cloudprime.aws.metrics.WorkInfo;
import pt.ist.cnv.cloudprime.healthchecking.HealthChecker;
import pt.ist.cnv.cloudprime.httpserver.ReadRequestHandler;
import pt.ist.cnv.cloudprime.httpserver.ResponseRequesthandler;
import pt.ist.cnv.cloudprime.mss.MSSRequester;
import pt.ist.cnv.cloudprime.util.Config;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.math.BigInteger;
import java.net.InetSocketAddress;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Scanner;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class LoadBalancer {


    /**
     * Singleton instance for Load Balancer
     */
    private static LoadBalancer instance = null;

    /**
     * Map that associates a requestID to the worker that is handling that request
     */
    private Map<Integer, WorkerInstance> pendingRequests = new ConcurrentHashMap<Integer, WorkerInstance>();

    /**
     * Map that contains the info about already processed requests and their metrics
     */
    private Map<String, RequestMetrics> knownRequests = new ConcurrentHashMap<String, RequestMetrics>();

    /**
     * List with all worker instances
     */
    private List<WorkerInstance> workers = new ArrayList<>();

    /**
     * object responsible for aws communications related issues
     */
    private AWSManager awsManager = null;

    /**
     * current requestID
     */
    private int requestID = 1;

    /**
     * LoadBalancer public ip
     */
    private String publicIP;

    /**
     * Http Server receiving the requests
     */
    private HttpServer server;

    /**
     * Http executor service threads receiving and responding to the requests
     */

    private ExecutorService threadPool;

    /**
     * AutoScaler responsible to increase and decrease workers
     */
    private AutoScaler autoScaler;

    /**
     * Entity responsible for Metrics Storage System queries
     */
    private MSSRequester mssRequest;

    /**
     * Entity that checks instance health
     */
    private HealthChecker healthChecker;


    public static void main(String[] args) throws Exception {
        LoadBalancer lb = new LoadBalancer();
        lb.start();
        waitToFinish();
    }

    /**
     * Method to get the singleton instance of the Load Balancer
     */

    public static synchronized LoadBalancer getInstance(){
        if(instance == null){
            instance = new LoadBalancer();
        }
        return instance;
    }


    /**
     * method reading user input waiting to read 'exit'
     * to stop the load balancer and all started instances gracefully
     */
    private static void waitToFinish() {
        Scanner scanner = new Scanner(System.in);

        while(true){
            String cmd = scanner.nextLine();
            if("exit".equals(cmd.toLowerCase())){
                LoadBalancer.getInstance().terminate();
                return;
            }
        }

    }

    /**
     * Method to gracefully terminate the load balancer execution
     * terminating all the worker instances and threads
     */
    private synchronized void terminate() {
        for (WorkerInstance wi: workers) {
            this.awsManager.terminateWorker(wi);
            wi.terminate();
        }

        this.server.stop(1);
        this.threadPool.shutdownNow();
        this.autoScaler.terminate();
        this.mssRequest.terminate();
        this.healthChecker.terminate();
    }




    /**
     * Starts the web server to receive the requests
     * And gets the machine public ip
     */
    private void start() throws Exception {

        LoadBalancer.instance = this;
        this.publicIP = getPublicIP();
        this.awsManager = AWSManager.getInstance();
        this.awsManager.setLbPublicIP(this.publicIP);
        this.autoScaler = new AutoScaler(this);
        this.mssRequest = new MSSRequester(publicIP, this);
        this.healthChecker = new HealthChecker();

        startServer(Config.LB_PORT);

        this.autoScaler.start();
        this.mssRequest.start();
        this.healthChecker.start();
    }



    /**
     * Threadsafe method to get a new unique requestID
     */
    private synchronized int getRequestID(){
        return this.requestID++;
    }

    /**
     * Function to start the load balancer web server with a
     * thread pool to handle the requests
     */
    private void startServer(int port) throws IOException {

        threadPool = Executors.newCachedThreadPool();
        server = HttpServer.create(new InetSocketAddress(port), 0);
        server.createContext("/f.html", new ReadRequestHandler());          //receive user requests
        server.createContext("/r.html", new ResponseRequesthandler());      //receive workers responses
        server.setExecutor(threadPool);
        server.start();
        System.out.println("LOAD BALANCER STARTED AND RUNNING ON: " + port);
    }

    /**
     * Method called when a new user request arrives
     *  This method calls the load balancing algorithm method (chooseWorker)
     *  and then assigns the request to that worker
     */

    public void handleNewRequest(HttpExchange httpExchange, String numberToFactor) {

        WorkerInstance worker;

        synchronized (AutoScaler.getInstance()){
            synchronized (this){

                worker = chooseWorker(numberToFactor);

                if(worker == null){
                    this.autoScaler.startNewInstance();
                    handleNewRequest(httpExchange, numberToFactor);
                    return;
                }
            }
        }

        System.out.println("[WI ASSIGNED] " + worker.getInstanceID());

        int requestID = getRequestID();
        this.pendingRequests.put(requestID, worker);

        worker.doRequest(new WorkInfo(httpExchange, numberToFactor, requestID));
    }


    /**
     * Method called when the response from the workers is received and
     *  marks que request as finished
     *
     * @param requestID the id associated to the request
     * @return the HttpExchange with the connection to the user
     */

    public HttpExchange endPendingRequest(int requestID){
        WorkerInstance instance = this.pendingRequests.get(requestID);

        if(instance == null) { return null; }

        HttpExchange he = instance.getWorkInfo(requestID).getHttpExchange();

        instance.endJob(requestID);

        return he;
    }


    /**
     * Function that implements the load balancing algorithm to chosse a worker
     *
     * @param numberToFactor the request number to be factored
     * @return the assigned instance to process the request
     */
    private synchronized WorkerInstance chooseWorker(String numberToFactor) {

        int minLoadFactor = -1;
        WorkerInstance minLoadInstance = null;

        for (WorkerInstance wi:  getAvailableWorkers()) {
            int loadFactor = calcLoadFactor(wi);

            if(minLoadFactor == -1 || loadFactor <= minLoadFactor){
                minLoadFactor = loadFactor;
                minLoadInstance = wi;
            }
        }

        /*
         * if the request + the min load factor >= COMPLEXITY THRESHOLD
         * new instance is created and assigned
         */

        if(this.knownRequests.containsKey(numberToFactor)){
            RequestMetrics pastRequest = this.knownRequests.get(numberToFactor);

            if(minLoadFactor != 0 && pastRequest.calcRequestComplexity() + minLoadFactor >= Config.REQUEST_COMPLEXITY_THRESHOLD){
                return this.autoScaler.startNewInstance();
            }
        }

        return minLoadInstance;
    }

    /**
     * Function that calcs the load factor of an instance
     * this factor takes into account measures as CPU Utilization
     * current requests being handled and if the requests are already known the
     * current calc stage
     *
     * @param wi the worker instance being analysed
     * @return the resulting load factor
     */

    private int calcLoadFactor(WorkerInstance wi) {

        int cpuUtilization = Double.valueOf(wi.getCpuMetric().getValue()).intValue();
        int requestFactor = 0;

        for (WorkInfo request: wi.getAllJobs()) {
            if(!this.knownRequests.containsKey(request.getNumberToFactor())){
                //for unknown requests
                requestFactor += 100;
                requestFactor += request.getRequestMetrics().calcRequestComplexity();
            }
            else{
                requestFactor += (knownRequests.get(request.getNumberToFactor()).calcRequestComplexity() - request.getRequestMetrics().calcRequestComplexity());
            }

        }

        return ((cpuUtilization * 10) + requestFactor);
    }

    /**
     *
     * @return The list of available workers
     */

    public List<WorkerInstance> getAvailableWorkers() {
        List<WorkerInstance> result = new ArrayList<WorkerInstance>();

        for(WorkerInstance wi : this.workers){
            if(wi.isActive()){
                result.add(wi);
            }
        }
        return result;
    }

    /**
     * Function that finds the machine public ip using aws checkip service
     */
    public String getPublicIP() throws IOException {

        URL whatismyip = new URL("http://checkip.amazonaws.com");
        BufferedReader in = new BufferedReader(new InputStreamReader(whatismyip.openStream()));

        return in.readLine();
    }

    /**
     * Adds new workers to the list of available workers
     */
    public synchronized void addNewWorker(WorkerInstance wi) {
        this.workers.add(wi);
    }


    public synchronized boolean canDecrease(WorkerInstance wi) {

        if(wi.getAllJobs().size() == 0){
            this.workers.remove(wi);
            wi.terminate();
            return true;
        }

        wi.markInstanceToFinish();

        return false;
    }

    /**
     * Function called by the MSSREquester thread when a new request metrics is available
     *  this is always from a terminated request
     *
     * @param requestMetrics the metrics to be added
     */
    public synchronized void addNewRequestMetrics(RequestMetrics requestMetrics) {
        this.knownRequests.put(requestMetrics.getRequestNumber(), requestMetrics);
    }

    /**
     * Function called by the MSSRequester thread when we have updated metrics from a running request
     *
     * @param requestID the request id
     * @param metricName the metric name that we are updating (instructions, comparisons, ...)
     * @param metricValue the new value
     */
    public synchronized void updateMetric(int requestID, String metricName, BigInteger metricValue) {

        if(this.pendingRequests.containsKey(requestID)){
            this.pendingRequests.get(requestID).updateMetricValue(requestID, metricName, metricValue);
        }

    }

    /**
     * Function that updates the Instance (AWS SDK) object with refreshed values for important
     *  fields as IP and instance state
     */
    public synchronized List<WorkerInstance> getUpdatedWorkers() {

        for (WorkerInstance wi: this.workers) {
            this.awsManager.updateInstance(wi);
        }

        return new ArrayList<>(this.workers);
    }

    /**
     * Method called by the health checker to inform that an instance has failed
     * and starts the fault tolerance protocol
     */

    public synchronized void instanceFailed(WorkerInstance wi) {

        this.workers.remove(wi);
        wi.terminate();

        List<WorkInfo> currentJobs = wi.getAllJobs();

        for (WorkInfo work : currentJobs) {
            this.pendingRequests.remove(work.getRequestID());
            handleNewRequest(work.getHttpExchange(), work.getNumberToFactor());
        }
    }
}
