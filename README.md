# NetworkUtils

> A small Java library for monitoring the activity of a remote host: it pings a server on a schedule and
> reports every response — success with latency, error, or status message — through a callback.

It is the library [Pingmaster](https://github.com/blacknoize404/Pingmaster) uses to keep an eye on hosts
and APIs in real time.

## What it does

- **Periodic pings on a background thread.** `start()` launches a scheduled task; `stop()` and `close()`
  shut it down cleanly. The service is `AutoCloseable` and its worker threads are daemons.
- **Several ping methods**, interchangeable at runtime: ICMP through the system's `ping` process,
  native ICMP (`IcmpSendEcho` on Windows, an ICMP datagram socket on Linux and macOS — it falls back to
  the `ping` process when the native path can't reach: IPv6, permissions, a missing library), HTTP and
  HTTPS (a TCP connect), a TCP connect to any port (`host:port`), DNS resolution time and a real HTTP
  request that checks the status code and the body (see below). On Windows the native echo goes through
  the foreign function API, so it needs `--enable-native-access=NetworkUtils` like `TrafficMonitor`.
- **Outage diagnosis**: when a host stops answering, `NetworkDiagnosis` tells whether the router, the
  Internet connection, the DNS server or the host itself is at fault.
- **A callback per response.** Every probe produces a `PingResponse`:
  - `PingSuccessResponse` — the host answered, with the round-trip time in milliseconds
    (`getResponseMsecs()`).
  - `PingErrorResponse` — the probe failed, with the reason (`getResponse()`).
  - `PingMessageResponse` — a status message from the service itself (started, stopped…).
- **Mutable while running.** The host, timeout, update rate, request quantity, method and even the
  response callback can be changed on the fly; the next cycles pick the new values up.
- **State events.** `setOnChangeStateEvent` is notified when the service changes `Status`
  (`RUNNING` / `STOPPED`).
- **Download and upload speed** of the whole system through `TrafficMonitor` (see below).
- **Java module** (`module NetworkUtils`): `javafx.base` (headless) and `org.jetbrains.annotations`.

> NetworkUtils is the former PingUtils (up to 1.6). The latency detector keeps its API; its packages moved
> from `io.github.blacknoize404.pingUtils` to `io.github.blacknoize404.networkUtils.latency`.

## Using it

```java
import io.github.blacknoize404.networkUtils.latency.HostActivityService;
import io.github.blacknoize404.networkUtils.latency.methods.PingMethod;
import io.github.blacknoize404.networkUtils.latency.responses.*;

HostActivityService service = HostActivityService.withTimeout(
        "api.example.com",
        500,                       // ms to wait for each response
        PingMethod.HTTP_SECURE.method(),
        response -> {
            if (response instanceof PingSuccessResponse ok) {
                System.out.println(ok.getHost() + " answered in " + ok.getResponseMsecs() + " ms");
            } else if (response instanceof PingErrorResponse error) {
                System.out.println(error.getHost() + " failed: " + error.getResponse());
            }
        });

service.start();
```

More factory methods for common configurations:

| Factory method | What it sets |
|---|---|
| `withTimeout(host, timeout, method, callback)` | per-probe timeout |
| `withUpdateRate(host, rate, method, callback)` | delay between probes |
| `ofRequestQuantity(host, quantity, method, callback)` | how many probes per cycle |
| `of(host, timeout, rate, quantity, method, callback)` | everything at once |

While it runs you can adjust it without restarting:

```java
service.setHost("other.example.com");
service.setUpdateRateMillis(1000);
service.setMethod(PingMethod.NATIVE_ICMP.method());
service.setOnPingResponse(r -> { /* a different callback */ });
```

### Observable properties

Since 1.5, the service also exposes JavaFX properties (`javafx.base`, headless).
Any number of consumers can subscribe without replacing each other — unlike the
single-slot `Consumer` setters:

```java
service.responseProperty().addListener((obs, old, r) -> { /* every ping */ });
service.statusProperty().addListener((obs, old, s) -> { /* RUNNING/STOPPED */ });
HostActivityService.Status current = service.statusProperty().get();
```

Events fire on the scheduler thread; marshal UI updates yourself
(e.g. `Platform.runLater`).

## More ways to check a host

`PingMethod` also has `TCP_PORT` (the host is written `name:port`, e.g. `db.example.com:5432`), `DNS` (how long the
system takes to resolve the name, without connecting) and `HTTP_CHECK` (a real GET request that counts only as a
success if the status is below 400).

For stricter checks build the method yourself and hand it to the service:

```java
// Only a 200 whose body contains "ok" is an answer; a 500 or an error page is a failure
HostActivityService service = HostActivityService.of("https://api.example.com/health", 2000, 1000, -1,
        new HttpCheckMethodFactory(200, "ok"), null);
```

DNS measurements need the JVM not to cache lookups: the `DNS` method switches that cache off when it loads, which only
works if nothing has resolved a name yet.

## Diagnosing an outage

```java
import io.github.blacknoize404.networkUtils.diagnosis.NetworkDiagnosis;

NetworkDiagnosis.Result result = NetworkDiagnosis.diagnose("api.example.com", 2000);
result.cause();     // LOCAL_NETWORK, INTERNET, DNS, HOST or UNKNOWN
result.gateway();   // the router that was checked, or null if none was found
```

It checks, in order, the default gateway (`route print` on Windows, `/proc/net/route` on Linux), well-known public
servers (ICMP, then TCP) and the resolution of the host name; the first thing that fails is the cause, and if all of
them work the problem is the host. Call it once a host has failed: it can take a few seconds.

## Download and upload speed

`TrafficMonitor` measures how many bytes per second the system downloads and uploads. Java does not
expose those counters, so it reads them from the operating system: the interface table
(`GetIfTable2`, through the foreign function API) on Windows and `/proc/net/dev` on Linux. On any other
system `isAvailable()` is `false` and the monitor does nothing.

```java
import io.github.blacknoize404.networkUtils.traffic.TrafficMonitor;

TrafficMonitor traffic = new TrafficMonitor();
traffic.sampleProperty().addListener((obs, old, s) ->
        System.out.printf("down %.0f B/s, up %.0f B/s%n", s.downloadBytesPerSecond(), s.uploadBytesPerSecond()));
traffic.start();                 // one sample per second

traffic.setIntervalSeconds(5);   // sample less often while nobody is watching
List<TrafficMonitor.Sample> recent = traffic.history();   // last 2048 samples, oldest first
```

### Choosing the adapter

By default the monitor follows the adapter that carries the default route (`TrafficSelection.AUTO`), so
switching from Wi-Fi to a cable moves the measurement with it. You can also add up every adapter, or pin one:

```java
List<TrafficInterface> adapters = traffic.refreshInterfaces();   // connected first; call again to refresh

traffic.setSelection(TrafficSelection.ALL);
traffic.setSelection(TrafficSelection.only(adapters.get(0).id()));   // ids are stable: GUID / interface name
traffic.setSelection(TrafficSelection.AUTO);

traffic.activeInterfaceProperty();   // the adapter being measured, null when several are added up

// Each sample also carries the bytes moved in its interval, to add up how much data was used
// sample.downloadBytes(), sample.uploadBytes()
```

A change applies on the next sample without losing traffic. Loopback, Windows' driver filter layers
(which repeat each adapter's counters) and its VPN/tunnel plumbing adapters are never listed. If the
system cannot tell the adapters apart (`supportsInterfaceSelection()` is `false`: the native call fails on
Windows, where `netstat -e` is the fallback), every selection measures the whole system.

On Windows the foreign function API needs `--enable-native-access=NetworkUtils` (or `ALL-UNNAMED` on the
class path); without it Java prints a warning, and a future release will refuse the call.

Like the latency service, samples are published from the sampling thread, so UI code must hop to its own
thread.

## Getting it

```xml
<dependency>
    <groupId>io.github.blacknoize404</groupId>
    <artifactId>NetworkUtils</artifactId>
    <version>1.3-RELEASE</version>
</dependency>
```

On the module path: `requires NetworkUtils;`. Requires Java 27 or newer.

## License

Apache License 2.0.
