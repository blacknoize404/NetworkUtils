# PingUtils

> A small Java library for monitoring the activity of a remote host: it pings a server on a schedule and
> reports every response — success with latency, error, or status message — through a callback.

It is the library [Pingmaster](https://github.com/blacknoize404/Pingmaster) uses to keep an eye on hosts
and APIs in real time.

## What it does

- **Periodic pings on a background thread.** `start()` launches a scheduled task; `stop()` and `close()`
  shut it down cleanly. The service is `AutoCloseable` and its worker threads are daemons.
- **Several ping methods**, interchangeable at runtime: plain ICMP, native ICMP, HTTP and HTTPS.
- **A callback per response.** Every probe produces a `PingResponse`:
  - `PingSuccessResponse` — the host answered, with the round-trip time in milliseconds
    (`getResponseMsecs()`).
  - `PingErrorResponse` — the probe failed, with the reason (`getResponse()`).
  - `PingMessageResponse` — a status message from the service itself (started, stopped…).
- **Mutable while running.** The host, timeout, update rate, request quantity, method and even the
  response callback can be changed on the fly; the next cycles pick the new values up.
- **State events.** `setOnChangeStateEvent` is notified when the service changes `Status`
  (`RUNNING` / `STOPPED`).
- **Java module** (`module PingUtils`): only `org.jetbrains.annotations` is required.

## Using it

```java
import io.github.blacknoize404.pingUtils.HostActivityService;
import io.github.blacknoize404.pingUtils.methods.PingMethod;
import io.github.blacknoize404.pingUtils.responses.*;

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

## Getting it

```xml
<dependency>
    <groupId>io.github.blacknoize404</groupId>
    <artifactId>PingUtils</artifactId>
    <version>1.5-RELEASE</version>
</dependency>
```

On the module path: `requires PingUtils;`. Requires Java 17 or newer (built for 25).

## License

Apache License 2.0.
