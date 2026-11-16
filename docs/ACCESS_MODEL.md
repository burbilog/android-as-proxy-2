# AAP2 access model and unattended-phone principles

AAP2 exposes the Android handset as a **trusted TCP exit and access point**.
It is not solely an IP-geolocation proxy. An authorized SOCKS5 client can
connect to the public Internet **and to services accessible from the
handset**, including the phone's own loopback TCP services (for example,
droidVNC-NG on 127.0.0.1:5900) and other devices on its Wi-Fi LAN.

## Trust boundary

- The Android SOCKS5 server listens on `127.0.0.1` **on the phone**;
  the phone establishes outbound authenticated, server-host-key-pinned SSH
  with a restricted reverse listener on `127.0.0.1` **on the relay VM**.
- Only administrators who are authorized to reach that VM's local SOCKS5
  listener (directly, via trusted SSH forwarding, or via authenticated
  Hysteria 2 TCP forwarding) should be given access.
- SOCKS5 deliberately uses **no second username/password**, and does
  **not** block RFC1918, loopback or other local destinations. This is
  an intentional functional requirement for remote Android/LAN
  administration. The same privilege also allows access to sensitive
  phone-side LAN services: do not expose either SOCKS listener to a
  public address, share Hysteria credentials casually, or put untrusted
  users on the relay without isolation.
- A SOCKS5 address such as `127.0.0.1:5900` refers to the **phone's
  localhost**, NOT the user's laptop or relay VM. When a VNC service
  on the phone requires Android screen capture or Accessibility
  approval, AAP2 cannot grant that approval remotely by itself.
- The current temporary enrollment code may be reused to upload a
  different public key until `aap2ctl approve` snapshots the candidate
  or the invitation expires. Its compromise during the short window
  can therefore impersonate an enrollment. This **accepted residual
  risk** is mitigated with short expiry, independent SSH server host
  key verification, and manual administrator approval. No added
  fingerprint comparison, second code or PIN lockout is imposed on
  a nontechnical person during setup. Revisit if the trust model
  changes; any future one-time-upload design must safely handle
  partial SFTP writes, reconnects and malicious uploads.

## Unattended Android UX

A person holding the remote Android may be nontechnical or have only a
minute to help. Normal network transitions, transient SSH errors and
long-running TCP traffic must recover **without touching the UI**.
Where recovery requires the administrator, show a short human-language
error and a persistent notification; do not expose SSH exception
classes as actionable instructions. Never silently re-pin a changed
server host key, reset enrollment, uninstall, clear Keystore or
change the release signing identity.

Optional battery-settings assistance is acceptable; never make
battery-exemption prompts or additional proxy-password entry mandatory
for activation. Test all changes against a physical Android with
existing enrollment and avoid disturbing working remote handsets.

See [README](../README.md), [server/INSTALL](../server/INSTALL.md),
and [release policy](RELEASE_POLICY.md).
