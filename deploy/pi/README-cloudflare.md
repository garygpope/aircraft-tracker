# Publishing the aircraft tracker with Cloudflare

This guide explains how a domain bought from Dynadot connects to the aircraft
tracker running on the Raspberry Pi. It records the setup made on 13 November
2026 and the remaining steps needed to make it persistent.

## What each part does

- **Dynadot** is the registrar: it holds the registration for `garypope.dev`.
  Renew the domain there. We did not transfer its registration to Cloudflare.
- **Cloudflare DNS** manages the domain's DNS records after the nameserver change.
- **Cloudflare Tunnel** connects Cloudflare to the Pi through an outbound
  connection made by `cloudflared`. No router port forwarding was configured.
- **The tracker** is the Kotlin/Ktor application listening on port `9090` on
  the Pi (`piaware`, local IP `192.168.1.200`).
- **PiAware/lighttpd** continues to serve its own status page on port `80`.

The intended public route is:

```text
https://aircraft.garypope.dev/
    -> Cloudflare
    -> aircraft-tracker tunnel
    -> cloudflared on the Pi
    -> http://127.0.0.1:9090/
```

`127.0.0.1` here means the Pi itself, because the connector runs on the Pi.
The public tracker is separate from PiAware's local status page.

## 1. Buy the domain at Dynadot

The domain used here is `garypope.dev`. After purchase, open its domain details
in Dynadot. Initially, DNS Settings showed **Dynadot Parking**.

Buying a domain reserves its name; it does not deploy the tracker or connect
that name to the Pi.

## 2. Add the domain to Cloudflare

In Cloudflare, choose **Connect your domain**, enter `garypope.dev`, use the Free
plan, and let Cloudflare import existing DNS records automatically.

The scan imported three proxied A records: the root (`garypope.dev`), `www`, and
`*` (a wildcard), all pointing to `185.53.179.149`. These were inherited from the
previous parking setup; they were not the Pi's address or the tracker's endpoint.
The root domain later showed a 522 timeout. Activating Cloudflare alone did not
make these inherited records point to the tracker.

The tracker uses the separate hostname `aircraft.garypope.dev`. Do not copy the
parking IP into its tunnel route or use the Pi's private IP as a public A record.
The inherited root, www, and wildcard records were not cleaned up in this session.

## 3. Change nameservers at Dynadot

Cloudflare assigned this domain the following nameservers:

```text
cullen.ns.cloudflare.com
izabella.ns.cloudflare.com
```

In Dynadot's domain details, open **DNS Settings**, change **Dynadot Parking**
to **Name Servers**, enter those two names, and click **Save Name Server**.
They replace `ns1.dyna-ns.net` and `ns2.dyna-ns.net`.

Use the nameservers Cloudflare assigns to your own zone if repeating this with
another domain; this pair is specific to this setup.

Dynadot showed **DNSSEC: Not Set**. No DNSSEC change was made. If repeating this
with a domain that already has DNSSEC configured, follow Cloudflare's migration
instructions before changing nameservers.

Return to Cloudflare and click **I updated my nameservers**. Cloudflare first
showed a propagation wait, then confirmed **Your domain is now protected by
Cloudflare**. That confirms DNS activation, not that the tracker is configured.

## 4. Create a named tunnel

From the Cloudflare account menu, open **Networking -> Tunnels**. This is the
account-level menu, not the domain's **Network** settings page.

Create a tunnel named `aircraft-tracker`. Its ID in this setup is:

```text
11d4dd9b-90a4-4ee4-a7ea-3eee6025c3dd
```

The Pi reported `armv7l`, so the dashboard environment selection was **Debian**
and **arm32**. `cloudflared` was already installed for a temporary tunnel;
its reported version was `2026.11.0`. No reinstall was needed.

A temporary tunnel is separate from this named tunnel. It can keep running
while the named tunnel is tested. `systemctl is-active cloudflared` reported
`inactive`; this checks the service, not manually running connector processes.

In a second SSH session on the Pi, run the command provided under **Or, run
tunnel (manual)** in the dashboard. Its shape is:

```sh
cloudflared tunnel run --token <TUNNEL_TOKEN>
```

Replace the placeholder using the dashboard command. The token is a secret:
do not commit it, put it in documentation, or paste it into chat or an issue.
The named tunnel appeared **Healthy**, with one replica on host `piaware`.

## 5. Route the hostname directly to the tracker

Open `aircraft-tracker`, choose **Add route -> Published application** and use:

| Field | Intended value |
| --- | --- |
| Subdomain | `aircraft` |
| Domain | `garypope.dev` |
| Path | Leave completely empty |
| Service URL | `http://127.0.0.1:9090` |

Save the route. The dashboard configures the hostname's DNS record automatically.
Check for a conflicting record for this exact hostname if saving fails.

This publishes the tracker to the Internet. No Cloudflare Access login policy
was configured during this session.

### Why the route changed during setup

The first saved route matched `/overtheroof/` and forwarded to
`http://192.168.1.200:80`. It worked at:

```text
https://aircraft.garypope.dev/overtheroof/
```

But the bare hostname returned 404 because its `/` path did not match that route.
This was a URL-path issue, not a Brave browser problem.

The local lighttpd configuration in `/etc/lighttpd/conf-enabled/90-overtheroof.conf`
already proxies `/overtheroof/` to port `9090`, stripping the prefix. We confirmed
that `http://localhost/` serves **PiAware Info**, while
`http://127.0.0.1:9090/` serves **Over the roof · Live sky**.

Therefore, the intended final change is to edit the existing tunnel route,
clear its Path, and point it directly to port `9090`. No lighttpd change is
required. The tracker uses relative asset and API URLs and can run at `/`.

**Verification status:** the direct-to-9090 root route subsequently returned HTTP
200 at `https://aircraft.garypope.dev/`. The local tracker on port 9090 also
returned HTTP 200 after the manual named-tunnel connector was stopped.

## 6. Verify the final route

Keep the manual connector running while testing. Open:

```text
https://aircraft.garypope.dev/
```

Check that the tracker renders, its styles and radar load, and live aircraft
data updates. An empty sky is possible; check the receiver status as well.
The API can also be checked at `/api/data/aircraft` on the same public hostname.

Local PiAware should still open at `http://192.168.1.200/`, and the existing
local tracker link at `http://192.168.1.200/overtheroof/` should still work.

## 7. Make the connector survive SSH disconnects and reboots

**Completed on 13 November 2026:** installed the named tunnel as the systemd
`cloudflared` service and enabled it for startup. Verified `active` and `enabled`,
and confirmed registered tunnel connections in the service journal. Stopped the
duplicate manual named-tunnel process and verified the public root URL still
returned HTTP 200. The old temporary tunnel was left untouched. A physical Pi
reboot has not been tested.

The following notes explain how to reproduce or check the service setup.

Cloudflare supplies an **Install as service** command with this shape:

```sh
sudo cloudflared service install <TUNNEL_TOKEN>
```

Before using it, inspect any existing `cloudflared` service/configuration so the
old temporary tunnel or another connector is not accidentally replaced. An
`inactive` status alone does not prove there is no installed service. If the
installer says a service already exists, inspect it rather than uninstalling it
blindly.

After configuring the intended named tunnel as a service, verify:

```sh
systemctl is-active cloudflared
systemctl is-enabled cloudflared
```

Confirm the tunnel is Healthy in Cloudflare, then stop only the manually started
named-tunnel process and confirm the public tracker still works. Finally, test
a planned Pi reboot. Retire the old temporary tunnel only after the persistent
named tunnel is working independently.

## Troubleshooting

- **404 at the bare hostname:** check that the route Path is empty and that the
  service is `http://127.0.0.1:9090`, not the PiAware server on port 80.
- **PiAware Info appears publicly:** the route is reaching port 80 instead of
  the tracker on port 9090.
- **Tunnel offline after closing SSH:** the manual connector stopped; configure
  and verify the persistent service described above.
- **Page loads but assets/data fail:** inspect the failed requests and verify
  the tracker locally on port 9090. The public root route must allow its asset
  and API paths too.
- **Root `garypope.dev` times out:** that is a separate DNS record from
  `aircraft.garypope.dev`; the inherited parking records do not host the tracker.

For Pi deployment and the existing local `/overtheroof/` integration, see
[the Pi deployment README](README.md).

Official references: [Cloudflare Tunnel setup](https://developers.cloudflare.com/tunnel/get-started/)
and [tunnel routing](https://developers.cloudflare.com/tunnel/routing/).
