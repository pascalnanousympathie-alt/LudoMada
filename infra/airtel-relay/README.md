# Airtel UAT egress relay (Oracle Always Free)

This relay keeps LudoMada on Railway Hobby while giving Airtel one stable
Oracle public IPv4 address for the mandatory Server IP Allowed List.

Traffic path:
Railway LudoMada -> HTTPS relay -> Airtel Madagascar UAT

The relay is not an open proxy. It accepts only the four Airtel API paths
used by the LudoMada adapter and requires X-Relay-Token on every request.

## Oracle VM

Use an Always Free VM in the Oracle home region with a public IPv4.
Reserve the public IP so it survives reboot/redeploy.

Use Ubuntu or Oracle Linux. Open TCP 80 and 443 in the OCI security list.
Caddy terminates HTTPS and proxies only to openapiuat.airtel.mg.

For the HTTPS hostname, use the free IP-mapped sslip.io name:
<ip-dashed>.sslip.io. sslip.io maps that hostname back to the VM IP.

## Relay configuration

Install Caddy, copy Caddyfile.template to /etc/caddy/Caddyfile, then replace
the domain and relay token placeholders with real values. Keep the file
root-readable only because it contains the shared relay secret.

Set the same relay base URL and relay token in Railway:
AIRTEL_API_BASE_URL=https://<ip-dashed>.sslip.io
AIRTEL_RELAY_TOKEN=<same-secret>
