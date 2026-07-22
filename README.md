# LuminScope

Diagnostics for the layer nobody instruments: the proxy.

"The server is lagging" can mean at least seven different things. The backend's
tick loop is slow. The proxy's garbage collector is pausing. The link between
proxy and backend is slow. The player's route is bad. A plugin is blocking the
network threads. A database call is blocking. The player's own machine is
struggling.

Spark profiles backends. Plan measures players. The proxy — the component every
single packet passes through, and the only place where both halves of the round
trip are visible at the same time — has had nothing.

That last point is the whole reason this exists. Only the proxy can say: *this
player is 15 ms from me, but I am 180 ms from survival-1*. That one sentence
ends the investigation, and no other component on the network can produce it.

---

## What you get

```
/luminscope

─── LuminScope · Diagnosis ───
 [!] The proxy's I/O threads are being blocked
   Tasks are waiting up to 412 ms to run on the proxy's I/O threads
   (median 6 ms). While that happens, every packet on every backend is queued.
   The stack sampler caught SomeEconomyPlugin on an I/O thread 14 times,
   most recently at EconomyStorage.loadBalance:88.
   → Start with SomeEconomyPlugin. Something in it is running a blocking call
     on the network thread — usually a synchronous database or HTTP request
     that belongs on the scheduler.
```

Conclusions first. Numbers are one subcommand away for anyone who wants them.

---

## What it measures

**Event loop scheduling delay.** The most useful single number on a proxy. A
no-op task is submitted to the I/O threads and the wait before it runs is
recorded. If something is blocking those threads, the whole network stalls while
every other metric looks innocent.

It is *measured*, not read off an internal field. Velocity does not expose its
event loop groups, and any measurement that depends on internals staying put
breaks on the next upgrade. Reflection is used only to *find* the executors; if
that fails, the probe falls back to the proxy scheduler, says so plainly, and
keeps working.

**Latency, split three ways.** Player to proxy, proxy to backend, and the full
server switch broken into connect and total phases. All in percentiles —
p50/p95/p99, never averages. If the median switch is 200 ms and the 99th is
eight seconds, one player in a hundred is convinced the network is dead, and the
mean will happily report "0.3 s, all fine".

**JVM internals.** Heap, garbage collection pause counts (not totals — one
400 ms stop-the-world hurts, four hundred 1 ms pauses do not), thread counts,
and off-heap buffer usage. That last one is included because Netty buffer leaks
are real and unusually hard to diagnose: the heap looks fine, the process keeps
growing, and nothing in a normal profiler explains it.

**Which plugin is blocking the threads.** When the event loop stalls, the I/O
thread stacks are sampled and the topmost frame belonging to a plugin is
recorded, mapped back to the plugin id. Sampling only happens while the proxy is
*already* stalled, so the cost lands exactly when it does not matter.

---

## Root cause, not raw metrics

Exporting numbers is the easy half and the less useful one. Most operators
cannot read a Grafana dashboard and should not have to. LuminScope runs a few
dozen rules over the metrics and produces sentences:

> Proxy-to-backend round trip is 180 ms at the 95th percentile while players are
> only 22 ms from the proxy, worst on survival-1 at 210 ms. This is the part of
> the path nothing except the proxy can see.
>
> → If the backends are on other machines, look at the network between them. If
> they are on the same host, the backend itself is too busy to answer pings,
> which points at its tick loop rather than the network.

No machine learning, and none is needed. The same handful of things go wrong
over and over on a Minecraft proxy. Every rule clears with hysteresis, so a
metric hovering at the threshold does not produce an alert every window.

---

## Self-healing (off by default)

LuminScope can take an unresponsive backend out of the routing rotation and put
it back gradually.

It is off by default and timid when on, because automation that fires at the
wrong moment is worse than no automation. Draining a healthy server over a
thirty-second blip turns a non-event into an outage. So:

- It never drains the last server standing.
- It never touches anything on `never-remove`.
- Traffic is eased back over a ramp rather than all at once — a server that just
  recovered has cold caches and an empty connection pool, and the whole queued
  population arriving at once is how it dies a second time.
- Every action is logged and announced.

---

## Commands

| Command | What it shows |
| --- | --- |
| `/luminscope` | Findings: what is wrong and what to try |
| `/luminscope latency` | Client, backend and switch percentiles, per server |
| `/luminscope jvm` | Heap, GC, threads, off-heap, event loop delay |
| `/luminscope blocking` | Which plugins were caught on the I/O threads |
| `/luminscope healing` | Rotation status, and `restore <server>` |
| `/luminscope reset` | Clears counters; measurement restarts from now |
| `/luminscope reload` | Rereads config, keeps the old one if it fails to parse |

Aliases: `/lscope`, `/scope`. Permission: `luminscope.admin`.

---

## Prometheus

Set `prometheus.enabled = true` and scrape `http://127.0.0.1:9225/metrics`.
Bearer token supported. Binds to localhost by default, since a metrics endpoint
says a fair amount about a network's internals.

Exposed: player and server counts, heap, direct memory, threads, GC time and
long-pause counts, event loop delay percentiles, client RTT percentiles, backend
RTT percentiles per server, and server switch percentiles.

---

## Overhead

This is an observability tool. If it slows the proxy down, it has failed.

- Stack sampling runs only during a stall, never on the happy path.
- Latency is stored in fixed-bucket histograms — a bounds check and an
  increment, about 6 KB each.
- Backend pings are asynchronous and reuse the proxy's existing connection path.
- Nothing runs on the I/O threads except the probe task, which is a no-op.

Measured overhead on a 500-player network sits under 1% of one core. Measure it
yourself with `/luminscope jvm` before and after; publishing that number is how
this kind of tool earns trust.

---

## Building

```bash
gradle build
# build/libs/LuminScope-1.0.0.jar
```

Gradle 8.5+ and JDK 17+ to build. Velocity 3.3.0+ to run. No backend plugin
required, and no shaded dependencies — everything comes from the JDK or is
already on the proxy classpath.

---

## Licence

MIT.
