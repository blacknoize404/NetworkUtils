package io.github.blacknoize404.pingUtils;

import io.github.blacknoize404.pingUtils.methods.PingMethodFactory;
import io.github.blacknoize404.pingUtils.responses.*;
import org.jetbrains.annotations.NotNull;

import java.util.concurrent.*;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;

import static io.github.blacknoize404.pingUtils.Ping.State.RUNNING;
import static io.github.blacknoize404.pingUtils.Ping.State.STOPPED;

/**
 * Represents a utility for monitoring the response of a host using various methodologies.
 * The Ping class aims to provide flexibility in conducting ping operations by
 * allowing different configurations such as setting request quantities, update rates, and timeouts.
 * The results of the ping operations are processed using a consumer callback.
 */
public final class Ping {

    /**
     * Represents the operational states of a Ping instance.
     * <p>
     * The State enum is used to indicate whether the Ping instance is actively
     * running or stopped. It provides two states:
     * <ul>
     * <li>{@code RUNNING} Indicates that the Ping process is currently active.</li>
     * <li>{@code STOPPED} Indicates that the Ping process has been stopped.</li>
     * </ul>
     * <p>
     * This enumeration is integral to managing the lifecycle and operational
     * behavior of the Ping class.
     */
    public enum State {
        RUNNING,
        STOPPED
    }

    /**
     * Represents the current operational state of the {@code Ping} instance.
     * <p>
     * The {@code state} can have values defined by the {@link State} enum, such
     * as {@code RUNNING} or {@code STOPPED}, to track whether the pinging
     * operation is actively executing or has been halted.
     * <p>
     * Changes to the {@code state} occur based on the lifecycle of the
     * {@code Ping} instance, including starting, stopping, or exhausting a fixed
     * number of ping requests if applicable.
     */
    private State state;

    /**
     * The hostname or IP address of the target system that is being monitored or pinged.
     * <p>
     * This variable stores the address of the host to which ping requests are sent.
     * It is used to identify and communicate with the target system.
     * The value of this field is expected to be a valid hostname or IP address.
     */
    private String host;
    /**
     * Defines the maximum duration, in milliseconds, to wait for a response when performing a ping operation.
     * <p>
     * This variable specifies the timeout threshold for detecting a response, after which the operation
     * will be considered failed if no reply is received. It is used to prevent indefinitely waiting
     * for a response from a host during a ping process managed by the Ping class.
     */
    private int timeoutMillis;
    /**
     * Specifies the frequency, in milliseconds, at which the ping operation is executed.
     * <p>
     * This field determines the interval between consecutive ping requests when the
     * Ping instance is running. The value must be a positive non-zero integer to ensure
     * periodic execution. A shorter interval results in more frequent pings, while a longer
     * interval reduces the frequency of requests.
     * <p>
     * It is primarily used within the internal scheduling logic of the Ping class to control
     * the timing of successive ping operations.
     */
    private int updateRateMillis;

    /**
     * Indicates whether the Ping instance is configured to use a fixed number of requests.
     * <p>
     * If set to true, the Ping instance will perform a limited number of requests as defined
     * by the `requestQuantity` field. Once the predefined number of requests is completed,
     * the instance transitions to the stopped state. If set to false, the Ping instance
     * will operate indefinitely until explicitly stopped.
     */
    private boolean isUsingFixedRequestQuantity;

    /**
     * Specifies the fixed number of ping requests to be sent to a target host.
     * <p>
     * This variable is used to configure and determine the total number of
     * ping operations executed by the Ping instance when operating in fixed
     * request quantity mode. The value is set during the Ping instance
     * initialization and remains constant throughout the object's lifecycle.
     * <p>
     * If the Ping instance operates in fixed request quantity mode, the value
     * of `requestQuantity` is decremented after each executed ping request,
     * ensuring the total number of requests matches the configured quantity.
     */
    private int requestQuantity;
    /**
     * The number of remaining requests that can be made within a specified limit or quota.
     * This variable is typically used to track and enforce rate-limiting or usage restrictions.
     */
    private int requestsLeft;

    /**
     * Represents the method used to perform the ping operation.
     * <p>
     * This is an instance of {@link PingMethodFactory} and determines
     * the specific implementation or strategy to execute the ping request.
     * The method is responsible for handling the logic to interact with
     * the given host based on the configuration parameters, such as the
     * timeout and response callback.
     */
    private PingMethodFactory method;

    /**
     * A callback function used to handle responses from the ping operation.
     * <p>
     * The {@code callback} variable holds a {@link Consumer} instance that consumes
     * a {@link PingResponse} object. This callback is invoked upon completion of
     * the ping request, providing the resulting response.
     * <p>
     * The specific type of {@link PingResponse} received (e.g., {@link PingSuccessResponse},
     * {@link PingMessageResponse}, or {@link PingErrorResponse}) will depend on the
     * outcome of the ping operation. This allows the user to process different types
     * of responses, including success, informational messages, and errors, accordingly.
     */
    private Consumer<PingResponse> callback;

    /**
     * Manages and schedules the periodic execution of tasks related to the Ping instance.
     * <p>
     * This ScheduledExecutorService is utilized to handle the timing and interval-based
     * task execution for the Ping instance, enabling operations like sending periodic ping
     * requests, managing timeouts, and updating the processing state.
     * <p>
     * The scheduler operates with a thread pool initialized to support concurrent task scheduling
     * and processing. It ensures that ping operations or related tasks occur at the specified
     * intervals and respects cancellation or shutdown signals as necessary.
     * <p>
     * The lifecycle of this scheduler is tied to the lifecycle of the Ping instance, and it
     * is properly initialized and terminated as part of the instance's start and stop process.
     */
    private final ScheduledExecutorService scheduler;
    /**
     * Represents a scheduled task that is repeatedly executed at a fixed rate
     * or with a fixed delay, as determined by the associated scheduler.
     * <p>
     * This variable is used to manage and control the execution schedule of
     * repetitive operations within the Ping instance. It is associated with the
     * scheduler to execute the `run` method of this class.
     * <p>
     * The `task` may be canceled or modified based on the state of the Ping instance
     * or upon stopping the associated Ping process to ensure proper resource management.
     */
    private ScheduledFuture<?> task;

    /**
     * A reentrant mutual exclusion lock used to manage concurrent access to shared resources in the Ping class.
     * <p>
     * This lock ensures thread safety while performing operations that modify or rely on mutable shared data.
     * The use of ReentrantLock allows for more fine-grained control over lock management, including the ability
     * to interrupt waiting threads and detect potential deadlocks.
     * <p>
     * It is primarily utilized to synchronize critical sections in methods where concurrent access might lead
     * to data inconsistency or operational errors.
     * <p>
     * The lock is final, meaning it cannot be reassigned, and its usage should follow proper acquisition
     * and release patterns to avoid runtime issues.
     */
    private final ReentrantLock lock = new ReentrantLock();

    private Consumer<State> onChangeStateEvent;

    /**
     * Creates a new Ping object configured to execute a fixed quantity of requests.
     *
     * @param location        the target location (e.g., a URL or host) where the requests will be sent.
     * @param requestQuantity the number of requests to be sent. Must be greater than 0.
     * @param method          the PingMethodFactory defining the method to handle the request operation.
     * @return a new Ping instance configured with the specified request quantity and method.
     * @throws IllegalArgumentException if the requestQuantity is less than or equal to 0.
     */
    public static Ping ofRequestQuantity(String location,
                                         int requestQuantity,
                                         PingMethodFactory method) {

        if (requestQuantity <= 0)
            throw new IllegalArgumentException("Debe definir una cantidad de peticiones mayor que 0");

        return new Ping(location, -1, -1, requestQuantity, method, null);

    }
//    TODO DETECTOR DE CAMBIO DE CONEXIÓN
    /**
     * Creates a new Ping object that repeatedly sends ping requests to the specified host
     * at a defined update rate in milliseconds.
     *
     * @param host             the target host to which the ping requests will be sent.
     * @param updateRateMillis the interval in milliseconds between consecutive ping requests.
     *                         Must be greater than 0.
     * @param method           the PingMethodFactory defining the ping implementation to use.
     * @return a Ping instance configured with the specified update rate and parameters.
     * @throws IllegalArgumentException if updateRateMillis is less than or equal to 0.
     */
    public static Ping withUpdateRate(String host,
                                      int updateRateMillis,
                                      PingMethodFactory method) {

        if (updateRateMillis < 0)
            throw new IllegalArgumentException("Debe definir una duración mayor que 0 milisegundos");

        return new Ping(host, -1, updateRateMillis, 0, method, null);

    }

    /**
     * Creates a new Ping instance that sends ping requests to the specified location
     * with a defined timeout duration.
     *
     * @param location      the target location (e.g., a URL or host) where the ping request will be sent.
     * @param timeoutMillis the maximum duration in milliseconds to wait for a response. Must be greater than 0.
     * @param method        the PingMethodFactory defining the implementation for handling the ping request.
     * @return a new Ping instance configured with the specified timeout and parameters.
     * @throws IllegalArgumentException if the timeoutMillis is less than or equal to 0.
     */
    public static Ping withTimeout(String location,
                                   int timeoutMillis,
                                   PingMethodFactory method) {

        if (timeoutMillis < 0) throw new IllegalArgumentException("Debe definir una duración mayor que 0 milisegundos");

        return new Ping(location, timeoutMillis, -1, 0, method, null);

    }

    /**
     * Creates a new Ping instance targeting the specified location with default parameters.
     * <p>
     * This method allows creating a Ping instance that will continuously send ping requests
     * without a fixed timeout or update rate. The ping requests are handled using the provided
     * PingMethodFactory, and responses are processed by the provided Consumer.
     *
     * @param method   the PingMethodFactory defining the implementation for handling the ping requests.
     * @return a new Ping instance configured with the specified parameters.
     */
    public static Ping of(String host,
                          int timeoutMillis,
                          int updateRateMillis,
                          int requestQuantity,
                          PingMethodFactory method,
                          Consumer<PingResponse> callback) {

        if (method == null) throw new IllegalArgumentException("Debe definir un método no nulo");

        return new Ping(host, timeoutMillis, updateRateMillis, requestQuantity, method, callback);

    }

    /**
     * Constructs a new instance of the Ping class.
     *
     * @param host             the target host to which the ping requests will be sent. Must not be null or blank.
     * @param timeoutMillis    the maximum duration in milliseconds to wait for a response. Defaults to 10000 if less than 0.
     * @param updateRateMillis the interval in milliseconds between consecutive ping requests. Defaults to 1000 if less than 0.
     * @param requestQuantity  the number of requests to be sent. If greater than 0, enables fixed request quantity mode.
     * @param method           the PingMethodFactory defining the implementation for sending ping requests.
     * @param callback         the Consumer to process the PingResponse after each ping request.
     * @throws IllegalArgumentException if the host is null or blank.
     */
    private Ping(String host,
                 int timeoutMillis,
                 int updateRateMillis,
                 int requestQuantity,
                 PingMethodFactory method,
                 Consumer<PingResponse> callback) {

        if (host == null || host.isBlank()) {
            throw new IllegalArgumentException("El host no puede ser nulo o vacío");
        }

        this.host = host;

        this.timeoutMillis = (timeoutMillis < 0) ? 10000 : timeoutMillis;

        this.updateRateMillis = (updateRateMillis < 0) ? 1000 : updateRateMillis;

        this.isUsingFixedRequestQuantity = requestQuantity > 0;
        this.requestQuantity = requestQuantity;
        this.requestsLeft = requestQuantity;

        this.method = method;
        this.callback = callback;
        this.scheduler = Executors.newSingleThreadScheduledExecutor(new DaemonThreadFactory());

        this.state = STOPPED;
    }

    /**
     * Updates the current host of the Ping instance.
     * <p>
     * This method allows changing the target host to which ping requests will be sent.
     * The new host must not be null or blank; otherwise, an {@link IllegalArgumentException}
     * will be thrown.
     *
     * @param host the new host to set. Must not be null or blank.
     * @throws IllegalArgumentException if the provided host is null, empty, or consists only of whitespace.
     */
    public void setHost(String host) {

        if (host == null || host.isBlank()) {
            throw new IllegalArgumentException("El host no puede ser nulo o vacío");
        }

        this.host = host;

    }

    public void setTimeoutMillis(int timeoutMillis) {
        this.timeoutMillis = timeoutMillis;
    }

    public void setUpdateRateMillis(int updateRateMillis) {
        this.updateRateMillis = updateRateMillis;
    }

    public void setRequestQuantity(int requestQuantity) {

        this.isUsingFixedRequestQuantity = requestQuantity > 0;
        this.requestQuantity = requestQuantity;

    }

    public void setMethod(PingMethodFactory method) {
        this.method = method;
    }

    /**
     * Asigna un método de escucha para la respuesta del ping.
     *
     * @param callback the Consumer to process the PingResponse after the request completes.
     */
    public void setOnPingResponse(Consumer<PingResponse> callback) {
        this.callback = callback;
    }

    /**
     * Starts the Ping task, scheduling it to execute at fixed intervals defined by the update rate.
     * <p>
     * This method locks the execution to ensure thread-safety and verifies the current state
     * of the ping process. If the task is already running, an {@code IllegalStateException}
     * is thrown to prevent reinitialization. Once started, the task will run periodically at
     * the specified update rate in milliseconds until stopped.
     *
     * @throws IllegalStateException if the Ping task is already running.
     */
    public void start() {

        lock.lock();
        if (isRunning()) stop();

        try {
            if (state == RUNNING || (task != null && task.state() == Future.State.RUNNING) ) throw new IllegalStateException("El scheduler ya ha sido iniciado");
            state = RUNNING;

            task = scheduler.scheduleWithFixedDelay(this::run, 0, updateRateMillis, TimeUnit.MILLISECONDS);

            if (callback != null)
                callback.accept(new PingMessageResponse(host, "Iniciando petición"));

            if (onChangeStateEvent != null) onChangeStateEvent.accept(state);

        } finally {
            lock.unlock();
        }
    }

    /**
     * Polls for the completion status of the currently scheduled task.
     * <p>
     * This method checks if the Ping scheduler has been started and if the
     * specified task is initialized. If the scheduler is not running or the
     * task is uninitialized, it throws an {@link IllegalStateException}.
     * <p>
     * Once these conditions are verified, the method enters a loop to continuously
     * check for the task's completion status. It pauses the current thread in
     * intervals of 100 milliseconds until the task reports that it is done.
     *
     * @throws InterruptedException  if the thread executing this method is
     *                               interrupted while sleeping.
     * @throws IllegalStateException if the scheduler is not currently RUNNING
     *                               or if the scheduled task is null.
     */
    public void poll() throws InterruptedException {

        if (state != RUNNING) throw new IllegalStateException("No se ha iniciado el scheduler");

        if (task == null) {
            throw new IllegalStateException("Tarea programada no inicializada");
        }

        while (!task.isDone()) {
            TimeUnit.MILLISECONDS.sleep(100);
        }

    }

    /**
     * Checks whether the Ping instance is currently running.
     * <p>
     * This method determines if the Ping process is active by verifying if the current
     * state is set to RUNNING.
     *
     * @return true if the Ping instance is in a RUNNING state, otherwise false.
     */
    public boolean isRunning() {

        return state == RUNNING;

    }

    /**
     * Stops the current operation or process and updates
     * the state to indicate it is no longer active.
     */
    public void stop() {

        task.cancel(false); // Cancela la tarea sin interrumpir si está en progreso
//        scheduler.shutdown(); // Cambiar .close() por .shutdown()
        reset();

        if (callback != null)
            callback.accept(
                    new PingMessageResponse(host, "El servicio está detenido")
            );

        state = STOPPED;

        if (onChangeStateEvent != null) onChangeStateEvent.accept(state);

    }

    private void reset() {

        if (isUsingFixedRequestQuantity) requestsLeft = requestQuantity;

    }

    /**
     * Executes the core logic of the Ping task, determining its behavior based on the current state
     * and configuration of the Ping instance.
     * <p>
     * This method performs the following:
     * - If the instance is configured with a fixed quantity of ping requests, it decrements the number
     * of remaining requests and transitions to the STOPPED state when no requests are left.
     * - If the Ping instance is not in the RUNNING state, it cancels the current task without interrupting
     * it, shuts down the scheduler, and resets internal state.
     * - When in the RUNNING state, it attempts to send a ping to the host using the provided method,
     * waits for a defined interval, and handles interruptions or errors by invoking the callback
     * with an error response.
     * <p>
     * This method is intended to be executed at a fixed interval as part of a scheduled task. It ensures
     * proper transition of state and resource management in response to changes in task state and
     * execution interruptions.
     */
    private void run() {

        if (isUsingFixedRequestQuantity) {

            if (requestsLeft == 0) state = STOPPED;
            else requestsLeft -= 1;

        }

        method.ping(host, timeoutMillis, pingResponse -> {

            if (callback != null) callback.accept(pingResponse);

        });

    }

    /**
     * Releases resources and stops any ongoing scheduled tasks related to this Ping instance.
     * <p>
     * This method attempts to shut down the internal scheduler associated with the Ping instance.
     * If the scheduler is not already shut down, it will be forcibly stopped using the
     * `shutdownNow` method. Any exceptions encountered during this process are caught and
     * logged to the standard error stream to prevent resource leaks.
     * <p>
     * It is recommended to call this method when the Ping instance is no longer needed to
     * ensure proper cleanup of resources.
     */
    public void close() {
        try {
            if (!scheduler.isShutdown()) {
                scheduler.shutdownNow(); // fuerza la detención
            }
        } catch (Exception e) {
            System.err.println("Error liberando recursos: " + e.getMessage());
        }
    }

    /**
     * A factory for creating daemon threads. Threads generated by this factory
     * are automatically set to run as daemon threads, meaning they will not
     * prevent the JVM from exiting once all non-daemon threads have finished.
     * <p>
     * This factory is typically used in scenarios where threads need to run
     * in the background without affecting the application's lifecycle.
     */
    private static class DaemonThreadFactory implements ThreadFactory {

        @Override
        public Thread newThread(@NotNull Runnable r) {
            Thread thread = new Thread(r);
            thread.setDaemon(true);
            return thread;
        }

    }

    public void setOnChangeStateEvent(Consumer<State> onChangeStateEvent) {
        this.onChangeStateEvent = onChangeStateEvent;
    }
}
